package com.app.finance.data.repo

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.domain.model.EntryError
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * The shared half of every repository's failure mapping — §22.4.
 *
 * These are the branches a repository suite cannot reach: a full disk and a
 * failing one are not states a test can put SQLite into, and a cancellation
 * arriving mid-write is not a state the repository tests construct. So they had
 * never been executed, in five hand-written copies of the same `when`, and the
 * copy that mattered most reported **"That didn't save. Check the amount and
 * category, then try again"** to a user whose phone had run out of space.
 *
 * Reaching the mapper directly is the point. The exceptions are real ones —
 * `SQLiteFullException` is what SQLite actually throws — so what is asserted
 * here is the same dispatch the repositories perform, on the same types, rather
 * than a rehearsal of it.
 *
 * Instrumented rather than JVM because `android.util.Log` and the SQLite
 * exception hierarchy are framework classes; the mapper logs on every path, and
 * a unit test would either stub that away or throw.
 */
@RunWith(AndroidJUnit4::class)
class WriteErrorsTest {

    private fun map(error: Throwable): EntryError =
        error.toWriteError("save a test row") { constraint ->
            if (constraint.message?.contains("leaf categories") == true) {
                EntryError.NOT_A_LEAF_CATEGORY
            } else {
                null
            }
        }

    // --- storage, which is not the user's input -------------------------------

    @Test
    fun a_full_disk_is_reported_as_a_full_disk() {
        // The defect this file exists for. Every repository opened with
        // `this !is SQLiteConstraintException -> CONSTRAINT_VIOLATION`, whose
        // copy asks the user to check the amount and the category — advice that
        // cannot work, about a cause that is not theirs, on a write that will
        // fail identically every time they follow it.
        assertEquals(EntryError.STORAGE_FULL, map(SQLiteFullException("database or disk is full")))
    }

    @Test
    fun storage_that_will_not_accept_a_write_is_distinct_from_being_out_of_space() {
        // Different fix, and there isn't one the user can perform. Telling them
        // to delete photos would send them to do something that will not help.
        assertEquals(EntryError.STORAGE_FAILED, map(SQLiteDiskIOException("disk I/O error")))
    }

    // --- constraints, which are the caller's to read --------------------------

    @Test
    fun the_callers_own_reading_of_a_constraint_wins() {
        assertEquals(
            EntryError.NOT_A_LEAF_CATEGORY,
            map(SQLiteConstraintException("expenses may only reference leaf categories")),
        )
    }

    @Test
    fun a_constraint_the_caller_does_not_recognise_falls_through_rather_than_guessing() {
        // Returning null from the caller's block means "not one of mine", and
        // that has to land somewhere honest. It lands on the generic error —
        // *with* a log line, which is the part that was missing: a constraint
        // nobody anticipated is precisely the case where the message is the
        // only evidence there will ever be. §18.7 A12 is the precedent, and
        // this is the third time it has come up in this log.
        assertEquals(
            EntryError.CONSTRAINT_VIOLATION,
            map(SQLiteConstraintException("FOREIGN KEY constraint failed")),
        )
    }

    @Test
    fun something_that_is_not_a_database_failure_at_all_is_still_answered() {
        // `runCatching` catches `Throwable`, so anything can arrive here.
        assertEquals(EntryError.CONSTRAINT_VIOLATION, map(IllegalStateException("nothing to do with SQL")))
        assertEquals(EntryError.CONSTRAINT_VIOLATION, map(IOException("the file went away")))
    }

    // --- cancellation is not a verdict about the user's data ------------------

    @Test
    fun a_cancellation_is_rethrown_rather_than_becoming_a_failure() {
        // `runCatching` catches `Throwable` and `CancellationException` is one,
        // so a screen closed mid-save came back as a `Result.failure` and was
        // mapped to a typed refusal like any other — the coroutine machinery's
        // own signal turned into a verdict about the user's data. Seven of the
        // fifteen call sites wrap a `withTransaction`, where swallowing it is
        // how a transaction gets committed by a coroutine that was told to stop.
        val cancellation = CancellationException("the screen closed")

        val thrown = assertThrows(CancellationException::class.java) {
            runCatchingWrite<Unit> { throw cancellation }
        }

        assertSame("the original cancellation must propagate", cancellation, thrown)
    }

    @Test
    fun everything_other_than_a_cancellation_is_still_captured() {
        // The guard must not have turned the helper into a rethrow-everything,
        // which would take down the ViewModel it was protecting.
        val boom = SQLiteFullException("database or disk is full")

        val result = runCatchingWrite<Unit> { throw boom }

        assertTrue(result.isFailure)
        assertSame(boom, result.exceptionOrNull())
        assertEquals(EntryError.STORAGE_FULL, map(result.exceptionOrNull()!!))
    }

    @Test
    fun a_write_that_succeeds_returns_what_it_produced() {
        assertEquals(42L, runCatchingWrite { 42L }.getOrNull())
    }
}
