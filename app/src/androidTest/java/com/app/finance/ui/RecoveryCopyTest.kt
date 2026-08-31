package com.app.finance.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipInputStream

/**
 * The copy that [RecoveryScreen] exists to make — FR-DAT-10, 04 §8.
 *
 * This is the last thing standing between a user whose migration failed and
 * five years of lost history, and until now nothing exercised it: the whole
 * function sat behind an `ACTION_CREATE_DOCUMENT` result callback that no
 * instrumented test can reach, so the rescue path was the least-tested code in
 * the app rather than the most (§26.2).
 *
 * What is pinned here is the reasoning already written above `zipDatabase`: a
 * zip **because** the database is in WAL mode, and all three files **because**
 * the main one alone can be missing the newest expenses.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryCopyTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var main: File

    private fun db(name: String = "daybook.db"): File =
        folder.newFile(name).apply { writeBytes(byteArrayOf(1, 2, 3)) }

    private fun sidecar(suffix: String, bytes: ByteArray): File =
        File(main.parentFile, "${main.name}$suffix").apply { writeBytes(bytes) }

    /** Entry name to bytes, which is the whole of what a restorer sees. */
    private fun entries(zip: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(zip.inputStream()).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                put(entry.name, stream.readBytes())
            }
        }
    }

    private fun copy(into: ByteArrayOutputStream = ByteArrayOutputStream()): Pair<Boolean, ByteArray> {
        val ok = zipDatabase(main) { into }
        return ok to into.toByteArray()
    }

    @Test
    fun the_wal_travels_with_the_database() {
        // The defect the zip exists to prevent. WAL mode keeps transactions in
        // the `-wal` sidecar until a checkpoint folds them in, so a copy of
        // `daybook.db` alone is missing the most recent expenses — the ones a
        // user rescuing their ledger cares about most. And this screen cannot
        // checkpoint first: it is here precisely because the database would not
        // open.
        main = db()
        val wal = sidecar("-wal", ByteArray(64) { 7 })
        val shm = sidecar("-shm", ByteArray(32) { 9 })

        val (ok, bytes) = copy()

        assertTrue(ok)
        val found = entries(bytes)
        assertEquals(setOf("daybook.db", "daybook.db-wal", "daybook.db-shm"), found.keys)
        assertArrayEquals(main.readBytes(), found["daybook.db"])
        assertArrayEquals(wal.readBytes(), found["daybook.db-wal"])
        assertArrayEquals(shm.readBytes(), found["daybook.db-shm"])
    }

    @Test
    fun a_checkpointed_database_with_no_sidecars_still_copies() {
        // The ordinary case after a clean shutdown: `filter(File::exists)` must
        // skip what is not there rather than fail the rescue over it.
        main = db()

        val (ok, bytes) = copy()

        assertTrue(ok)
        assertEquals(setOf("daybook.db"), entries(bytes).keys)
    }

    @Test
    fun the_entries_are_separate_files_and_not_a_concatenation() {
        // Named because the comment on `zipDatabase` calls concatenation "worse
        // still: the result is not a valid database at all". A single entry
        // holding db+wal+shm end to end would pass a size check and be
        // unopenable, so the count is asserted, not just the total bytes.
        main = db()
        sidecar("-wal", ByteArray(64) { 7 })

        val (_, bytes) = copy()

        assertEquals(2, entries(bytes).size)
    }

    @Test
    fun no_database_is_not_a_silent_success() {
        // Returning true here would light the interlock and enable "Start over"
        // — the destructive button — on the strength of a copy that never
        // happened.
        main = File(folder.root, "absent.db")

        val (ok, bytes) = copy()

        assertFalse(ok)
        assertEquals(0, bytes.size)
    }

    @Test
    fun a_target_that_cannot_be_opened_is_reported_rather_than_thrown() {
        // `openOutputStream` returns null for a provider that has gone away —
        // a removed SD card, a revoked grant. The screen has to say so, not
        // crash the one activity that can still save the data.
        main = db()

        assertFalse(zipDatabase(main) { null })
    }

    @Test
    fun a_stream_that_fails_midway_reports_failure() {
        // The full-disk case, and the reason for the `runCatching`: a rescue
        // that half-wrote a zip must not report success, because the interlock
        // would then let the user discard the original.
        main = db()
        sidecar("-wal", ByteArray(4096) { 7 })

        val failing = object : OutputStream() {
            var written = 0
            override fun write(b: Int) {
                if (++written > 128) throw IOException("no space left on device")
            }
        }

        assertFalse(zipDatabase(main) { failing })
    }
}
