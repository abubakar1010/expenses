package com.app.finance.data.repo

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import android.util.Log
import com.app.finance.domain.model.EntryError
import kotlin.coroutines.cancellation.CancellationException

/**
 * The half of every repository's failure mapping that is not about its own
 * constraints — 04 §8's "a raw exception is never surfaced to the user".
 *
 * Five repositories had each written the same `when`, and having written it five
 * times they had drifted in the two ways duplication drifts. Every one of them
 * opened with `this !is SQLiteConstraintException -> CONSTRAINT_VIOLATION`, so a
 * phone that had run out of storage was told **"That didn't save. Check the
 * amount and category, then try again"** — advice that cannot work, about a
 * cause that is not theirs, on a write that will fail identically every time
 * they follow it. And [RecurringRepository] had no mapping at all: it returned
 * `CONSTRAINT_VIOLATION` unconditionally, so a duplicate rule name and a full
 * disk were the same sentence.
 *
 * Nothing logged, either. A constraint nobody anticipated is precisely the case
 * where the message is the only evidence there will ever be, and §18.7 A12
 * already recorded the cost of discarding one.
 */
private const val TAG = "DayBook"

/**
 * Maps [this] onto the error the user should read.
 *
 * [constraint] is the repository's own reading of a constraint message and is
 * consulted first for anything that *is* a constraint — it is the part only the
 * caller knows. Returning null from it means "not one of mine", which lands on
 * [EntryError.CONSTRAINT_VIOLATION] with a log line rather than silently.
 *
 * **Not `inline`, though it takes a lambda.** Every path through this is a write
 * that has already failed, so one lambda allocation costs nothing measurable —
 * and inlining it hid the whole body from coverage, because JaCoCo credits the
 * inlined copies at each call site rather than the declaration. That put this
 * file at exactly the 50% per-file floor `coverageVerify` enforces: passing, but
 * one line away from failing for a reason that has nothing to do with whether
 * anything tests it. [runCatchingWrite] below stays inline, because that one is
 * on the success path of every write and `runCatching` is inline for the same
 * reason.
 */
internal fun Throwable.toWriteError(
    what: String,
    constraint: (SQLiteConstraintException) -> EntryError?,
): EntryError {
    when (this) {
        // Distinct from a disk that is failing: this one the user can act on,
        // and the copy tells them how.
        is SQLiteFullException -> {
            Log.e(TAG, "no space left to $what", this)
            return EntryError.STORAGE_FULL
        }

        is SQLiteDiskIOException -> {
            Log.e(TAG, "storage would not accept the write to $what", this)
            return EntryError.STORAGE_FAILED
        }

        is SQLiteConstraintException -> constraint(this)?.let { return it }

        else -> Unit
    }
    Log.w(TAG, "unanticipated failure trying to $what", this)
    return EntryError.CONSTRAINT_VIOLATION
}

/**
 * [runCatching], except that a cancellation stays a cancellation.
 *
 * `runCatching` catches `Throwable`, and `CancellationException` is one. A
 * screen closed mid-save therefore came back as a `Result.failure` and was
 * mapped to a typed error like any other refusal — the coroutine machinery's
 * own signal turned into a verdict about the user's data.
 *
 * In practice every caller here is a `viewModelScope.launch { withContext(io) }`
 * and `withContext` throws again on resumption, so the wrong answer was
 * discarded before anything rendered it. That is luck rather than design: it
 * depends on the call shape of every present and future caller, and swallowing
 * a cancellation inside a `withTransaction` is how a transaction gets committed
 * by a coroutine that was told to stop.
 */
internal inline fun <T> runCatchingWrite(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
