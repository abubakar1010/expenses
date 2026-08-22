package com.app.finance.data.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A backup folder in memory.
 *
 * The reason [BackupStore] is an interface at all. A document tree cannot be
 * granted without a human tapping a picker, so the alternative to this is not
 * testing `BackupRepository` — and rotation, scheduling and the keep-the-last-N
 * arithmetic are exactly where a bug quietly deletes somebody's backups without
 * anything appearing to go wrong.
 *
 * It imitates two things real providers do that a naive fake would not, because
 * both have caused bugs in shipped apps:
 *
 * - **A creation can be refused** ([refuseCreate]) and **a write can fail
 *   part-way** ([failWriteAfter]), which is what a full card or a pulled cable
 *   looks like from up here.
 * - **A name is what the provider decides**, not what was asked for: a
 *   collision is deduped rather than overwritten, exactly as
 *   `ExternalStorageProvider` does.
 */
class FakeBackupStore(
    var reachable: Boolean = true,
    var refuseCreate: Boolean = false,
    /** Bytes to accept before throwing. Null means never fail. */
    var failWriteAfter: Int? = null,
) : BackupStore {

    private class Entry(var name: String, var bytes: ByteArray = ByteArray(0), var modifiedAt: Long = 0)

    private val entries = LinkedHashMap<String, Entry>()
    private var nextId = 1
    private var clock = 1_000L

    /** Everything currently in the folder, in the order it was created. */
    val names: List<String> get() = entries.values.map { it.name }

    fun bytesOf(name: String): ByteArray? = entries.values.firstOrNull { it.name == name }?.bytes

    /** Drops a file in without going through the app, for arranging a test. */
    fun put(name: String, bytes: ByteArray = ByteArray(0)) {
        entries["fake:" + nextId++] = Entry(name, bytes, clock++)
    }

    override suspend fun isReachable() = reachable

    override suspend fun label() = if (reachable) "Documents/Khata" else null

    override suspend fun create(name: String, mime: String): BackupFile? {
        if (!reachable || refuseCreate) return null
        val id = "fake:" + nextId++
        entries[id] = Entry(unique(name), modifiedAt = clock++)
        return describe(id)
    }

    override suspend fun write(id: String): OutputStream? {
        val entry = entries[id] ?: return null
        if (!reachable) return null
        return object : ByteArrayOutputStream() {
            private var written = 0

            override fun write(b: Int) {
                guard(1)
                super.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                guard(len)
                super.write(b, off, len)
            }

            override fun close() {
                entry.bytes = toByteArray()
                entry.modifiedAt = clock++
                super.close()
            }

            private fun guard(n: Int) {
                val limit = failWriteAfter ?: return
                written += n
                if (written > limit) throw IOException("the fake folder ran out of room")
            }
        }
    }

    override suspend fun read(id: String): InputStream? =
        entries[id]?.takeIf { reachable }?.let { ByteArrayInputStream(it.bytes) }

    override suspend fun rename(id: String, name: String): BackupFile? {
        val entry = entries[id] ?: return null
        if (!reachable) return null
        entry.name = unique(name)
        return describe(id)
    }

    override suspend fun delete(id: String): Boolean =
        reachable && entries.remove(id) != null

    override suspend fun list(): List<BackupFile> =
        if (!reachable) emptyList() else entries.keys.mapNotNull(::describe)

    private fun describe(id: String): BackupFile? = entries[id]?.let {
        BackupFile(id = id, name = it.name, sizeBytes = it.bytes.size.toLong(), modifiedAt = it.modifiedAt)
    }

    /** What a provider does with a colliding display name. */
    private fun unique(name: String): String {
        if (entries.values.none { it.name == name }) return name
        val stem = name.substringBeforeLast('.', name)
        val dot = name.substringAfterLast('.', "")
        var n = 1
        while (true) {
            val candidate = if (dot.isEmpty()) "$stem ($n)" else "$stem ($n).$dot"
            if (entries.values.none { it.name == candidate }) return candidate
            n++
        }
    }
}
