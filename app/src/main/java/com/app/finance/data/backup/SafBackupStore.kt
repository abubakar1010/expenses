package com.app.finance.data.backup

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * A [BackupStore] over a document tree the user granted — FR-DAT-07.
 *
 * ### Why this keeps FR-APP-01 and NFR-SEC-01 intact
 *
 * `ACTION_OPEN_DOCUMENT_TREE` needs **no manifest permission**, exactly as
 * `ACTION_CREATE_DOCUMENT` needs none — §17.11 already rests the offline
 * guarantee on that fact, and nothing here changes it. The app cannot reach a
 * byte of storage it was not handed, cannot enumerate folders it was not shown,
 * and has no transport off the device in any case. What the grant adds over the
 * one-file-at-a-time picker is only that the user does not have to repeat the
 * choice every time.
 *
 * The user's explicit act moves from "save this file, here" to "put backups in
 * this folder, on this schedule". It stays explicit, and it stays theirs — which
 * is what NFR-SEC-01 is protecting. That restatement is recorded in `02-SRS.md`
 * and reasoned about in `06-implementation-log.md` §21, because reinterpreting a
 * requirement quietly is not something this project does.
 *
 * ### DocumentsContract rather than androidx.documentfile
 *
 * `DocumentFile` would be a few lines shorter and about 20 KB heavier.
 * NFR-SIZE-04 asks for a written rationale for every dependency, and the honest
 * one here would be "it is slightly nicer" — the platform API has done the whole
 * job since API 21, five years below this app's minimum. `DocumentFile.listFiles`
 * also issues one query per child for its metadata, where the projection below
 * gets all of it in a single cursor.
 *
 * Every method returns rather than throws. A backup folder that has gone away is
 * an ordinary Tuesday — the card is out, the grant was revoked, the folder was
 * tidied up — and the caller's job is to tell the user, not to crash.
 */
class SafBackupStore(
    context: Context,
    private val tree: Uri,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : BackupStore {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    /** The tree's own document, which is the folder new files are created in. */
    private val folder: Uri
        get() = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )

    private val children: Uri
        get() = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )

    override suspend fun isReachable(): Boolean = withContext(io) {
        // The grant surviving is necessary but not sufficient — it outlives the
        // folder being deleted — so this actually opens the listing.
        val held = resolver.persistedUriPermissions.any {
            it.uri == tree && it.isReadPermission && it.isWritePermission
        }
        held && attempt("reach") { resolver.query(children, EMPTY, null, null, null)?.use { true } } == true
    }

    override suspend fun label(): String? = withContext(io) {
        attempt("label") {
            resolver.query(folder, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }
    }

    override suspend fun create(name: String, mime: String): BackupFile? = withContext(io) {
        attempt("create") {
            DocumentsContract.createDocument(resolver, folder, mime, name)?.let(::describe)
        }
    }

    override suspend fun write(id: String): OutputStream? = withContext(io) {
        // "wt" and not "w": several providers treat "w" as an append or leave
        // the previous tail in place when the new content is shorter, which on a
        // rewritten backup means a valid file with rubbish welded to the end.
        attempt("write") { resolver.openOutputStream(Uri.parse(id), "wt") }
    }

    override suspend fun read(id: String): InputStream? = withContext(io) {
        attempt("read") { resolver.openInputStream(Uri.parse(id)) }
    }

    override suspend fun rename(id: String, name: String): BackupFile? = withContext(io) {
        attempt("rename") {
            DocumentsContract.renameDocument(resolver, Uri.parse(id), name)
                // A provider that renamed in place returns null rather than a new
                // Uri, and the old one still resolves. Both are success.
                ?.let(::describe) ?: describe(Uri.parse(id))
        }
    }

    override suspend fun delete(id: String): Boolean = withContext(io) {
        attempt("delete") { DocumentsContract.deleteDocument(resolver, Uri.parse(id)) } == true
    }

    override suspend fun list(): List<BackupFile> = withContext(io) {
        attempt("list") {
            resolver.query(children, PROJECTION, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val documentId = cursor.getString(0) ?: continue
                        add(
                            BackupFile(
                                id = DocumentsContract
                                    .buildDocumentUriUsingTree(tree, documentId)
                                    .toString(),
                                name = cursor.getString(1) ?: continue,
                                sizeBytes = cursor.longOrZero(2),
                                modifiedAt = cursor.longOrZero(3),
                            ),
                        )
                    }
                }
            }
        }.orEmpty()
    }

    /** The metadata of one document, read back rather than assumed. */
    private fun describe(uri: Uri): BackupFile? =
        resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            BackupFile(
                id = uri.toString(),
                name = cursor.getString(1) ?: return@use null,
                sizeBytes = cursor.longOrZero(2),
                modifiedAt = cursor.longOrZero(3),
            )
        }

    /**
     * Runs [block], turning any provider failure into null.
     *
     * Broad on purpose. Providers throw `SecurityException` for a revoked grant,
     * `FileNotFoundException` for a folder that is gone, `IllegalArgumentException`
     * for a stale document id, and `IllegalStateException` from at least one OEM
     * implementation — and the caller's response to all of them is the same
     * sentence to the user. Logged, so a report has something in it.
     */
    private inline fun <T> attempt(what: String, block: () -> T): T? =
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "backup folder: $what failed", e)
            null
        }

    private fun Cursor.longOrZero(column: Int) = if (isNull(column)) 0L else getLong(column)

    companion object {
        private const val TAG = "Khata"

        /** What a backup file is written as. */
        const val MIME = "application/octet-stream"

        private val EMPTY = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)

        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        /**
         * The intent that asks for a folder.
         *
         * The flags are the whole point: without `PERSISTABLE` the grant dies
         * with the process, and the user would be choosing a folder on every
         * launch — which is the manual export they already have.
         */
        fun pickFolder(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }

        /**
         * Takes the grant the picker just handed back, releasing any previous
         * one. Returns the tree URI to store, or null if the grant did not stick.
         *
         * Released rather than accumulated because the persisted-permission list
         * is a fixed-size system resource, and a user who changes folder a dozen
         * times should not spend twelve slots of it.
         */
        fun persist(context: Context, tree: Uri, previous: String?): String? {
            val resolver = context.applicationContext.contentResolver
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            return try {
                resolver.takePersistableUriPermission(tree, flags)
                if (previous != null && previous != tree.toString()) {
                    runCatching { resolver.releasePersistableUriPermission(Uri.parse(previous), flags) }
                }
                tree.toString()
            } catch (e: SecurityException) {
                Log.w(TAG, "backup folder: the grant did not persist", e)
                null
            }
        }
    }
}
