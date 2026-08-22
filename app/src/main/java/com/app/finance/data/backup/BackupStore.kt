package com.app.finance.data.backup

import java.io.InputStream
import java.io.OutputStream

/** One file in the backup folder. [id] is opaque and provider-assigned. */
data class BackupFile(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
)

/**
 * The folder backups are written to — FR-DAT-07.
 *
 * An interface, and the only reason is testability. Everything that decides
 * *what* to write and *when* lives in `BackupRepository`, which
 * `NFR-MAIN-02` measures at 80% along with the rest of `data/repo/`. The one
 * implementation of this that ships talks to `DocumentsContract`, and a document
 * tree cannot be granted without a human tapping a picker — so a repository that
 * spoke to SAF directly could not be tested at all, and rotation, scheduling and
 * the "keep the last N" arithmetic are exactly the parts where a bug quietly
 * eats somebody's backups.
 *
 * **Names are what the provider says they are, not what was asked for.** SAF
 * providers may dedupe a colliding display name, and some append an extension
 * derived from the MIME type. Every method that can affect a name therefore
 * returns the resulting [BackupFile] rather than assuming the request was
 * honoured verbatim.
 */
interface BackupStore {

    /**
     * Whether the folder can still be reached.
     *
     * False is an ordinary state, not an error: the grant may have been revoked
     * in system settings, the folder deleted, or the SD card it lived on taken
     * out. The setting is kept when this is false, because a card that is out
     * today goes back in tomorrow.
     */
    suspend fun isReachable(): Boolean

    /** What to show the user so they recognise where their backups go. */
    suspend fun label(): String?

    /** Creates an empty document, or null if the folder refuses it. */
    suspend fun create(name: String, mime: String): BackupFile?

    suspend fun write(id: String): OutputStream?

    suspend fun read(id: String): InputStream?

    suspend fun rename(id: String, name: String): BackupFile?

    suspend fun delete(id: String): Boolean

    /** Every file in the folder, the app's own or not. Filtering is the caller's. */
    suspend fun list(): List<BackupFile>
}
