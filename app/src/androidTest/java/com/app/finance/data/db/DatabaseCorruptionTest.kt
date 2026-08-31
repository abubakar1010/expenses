package com.app.finance.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A ledger that will not open must still be **there** — FR-DAT-10, 04 §8.
 *
 * > "Release builds never fall back to destructive migration. Failure surfaces
 * > a recovery screen offering export of the raw database file"
 *
 * That policy was only half-enforced, and the missing half deleted user data.
 * `fallbackToDestructiveMigration` is withheld from release builds, which
 * covers a *migration* failure — but corruption never reaches Room's migration
 * path at all. SQLite raises `SQLITE_NOTADB`, androidx.sqlite's default
 * `onCorruption` deletes the file, Room creates an empty one in its place, and
 * `verifyDatabase()` then **succeeds**: no recovery screen, no copy, no
 * warning, and the user is looking at onboarding with five years gone.
 *
 * Found by driving it on a device rather than by reading: a 155 MB unreadable
 * `daybook.db` came back as a fresh 4 KB one across one launch (§26.3).
 *
 * These tests are deliberately about the **bytes on disk**, not about the
 * exception. What the recovery screen needs is the file.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseCorruptionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "corruption-probe.db"

    private fun files(): List<File> {
        val main = context.getDatabasePath(name)
        return listOf(main, File(main.parentFile, "$name-wal"), File(main.parentFile, "$name-shm"))
    }

    @Before fun setUp() = files().forEach { it.delete() }

    @After fun tearDown() = files().forEach { it.delete() }

    /** `RoomDatabase` is not `Closeable`, so the bracket is spelled out. */
    private inline fun <R> opened(block: (AppDatabase) -> R): R {
        val db = AppDatabase.named(context, name)
        return try { block(db) } finally { db.close() }
    }

    /** Not a database, and long enough that a truncation would show. */
    private val garbage = ByteArray(8192) { (it % 251).toByte() }

    /** Builds a real ledger, closes it cleanly, then overwrites it. */
    private fun corruptAnExistingLedger(): File {
        opened { db ->
            db.openHelper.writableDatabase.query("PRAGMA user_version").use { it.moveToFirst() }
        }
        val main = context.getDatabasePath(name)
        assertTrue("the ledger should exist before it is corrupted", main.exists())
        // The sidecars go too: a valid -wal lets SQLite recover page 1 and open
        // the file regardless, which is a real behaviour and not what is under
        // test here.
        files().drop(1).forEach { it.delete() }
        main.writeBytes(garbage)
        // The postcondition of this helper, asserted rather than assumed: if
        // the corruption did not land, everything below is testing nothing.
        assertArrayEquals(
            "the ledger was not actually corrupted",
            garbage,
            main.readBytes(),
        )
        return main
    }

    @Test
    fun a_corrupt_ledger_survives_the_attempt_to_open_it() {
        val main = corruptAnExistingLedger()

        val opened = runCatching {
            AppDatabase.named(context, name).openHelper.readableDatabase
                .query("PRAGMA user_version").use { it.moveToFirst() }
        }

        assertTrue(
            "open succeeded; header=" + main.readBytes().take(16).joinToString(",") +
                " size=" + main.length() +
                " sidecars=" + files().drop(1).filter { it.exists() }.map { it.name },
            opened.isFailure,
        )
        assertTrue("the ledger was deleted; there is nothing left to recover", main.exists())
        assertArrayEquals("the ledger was rewritten under the user", garbage, main.readBytes())
    }

    @Test
    fun the_failure_is_what_verifyDatabase_reports_to_the_launch_path() {
        // The other half. `MainActivity` decides `databaseFailed` from a query
        // exactly like this one; if the open silently succeeded against a
        // replacement database the recovery screen would never be reached, which
        // is precisely the defect.
        corruptAnExistingLedger()

        val probe = runCatching {
            AppDatabase.named(context, name).openHelper.readableDatabase
                .query("PRAGMA user_version").use { it.moveToFirst() }
        }

        assertEquals(false, probe.isSuccess)
    }

    @Test
    fun a_healthy_ledger_is_untouched_by_any_of_this() {
        // The control: the corruption hook must not change the ordinary open.
        opened { db ->
            db.openHelper.writableDatabase.query("PRAGMA user_version").use { it.moveToFirst() }
        }

        opened { db ->
            val categories = db.openHelper.readableDatabase
                .query("SELECT COUNT(*) FROM category").use { it.moveToFirst(); it.getInt(0) }
            assertTrue("the canonical schema and its seed should be there", categories > 0)
        }
    }
}
