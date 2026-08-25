package com.app.finance.ui.lock

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FR-APP-04's gate, and the two ways it is usually got wrong.
 *
 * The controller is the whole of the lock's logic — the prompt itself belongs
 * to the OS — so this is where the behaviour is pinned.
 *
 * **This suite was green while the lock was broken, and worth being precise
 * about why.** It tested a `suppressNextBackground()` boolean and asserted, as
 * a requirement, that "the suppression is consumed by the stop it was set for":
 * one `ON_STOP` took the flag down, whatever had caused that stop. That is a
 * faithful description of what the code did and it is the defect. A suppression
 * armed for a picker that then failed to open sat there until the user pressed
 * Home, swallowed *that* stop, and left the ledger unlocked in recents; and
 * "Send a copy" armed it before a plain `startActivity` to the share sheet,
 * which returns no result and so had no moment at which anything could take it
 * back down at all.
 *
 * What is asserted now is the pairing: a hand-off that is begun is a hand-off
 * that ends, and only a hand-off in progress suppresses anything.
 */
@RunWith(AndroidJUnit4::class)
class LockControllerTest {

    @Test
    fun it_starts_locked() {
        // A gate that has to be switched on after launch is not a gate. The
        // setting decides whether the screen is *shown*; the controller assumes
        // locked until somebody authenticates.
        assertTrue(LockController().locked)
    }

    @Test
    fun leaving_the_app_locks_it_again() {
        // A lock that only applies at cold start protects nothing: the app a
        // thief finds is the one still open in recents.
        val lock = LockController()
        lock.unlock()
        assertFalse(lock.locked)

        lock.onStopped()
        assertTrue(lock.locked)
    }

    @Test
    fun the_unlock_prompt_does_not_lock_the_app_underneath_itself() {
        // A device-credential prompt is a separate activity, so the app stops
        // beneath it. Without the hand-off the app re-locks while the user is
        // in the middle of unlocking it — the classic implementation of this
        // bug.
        val lock = LockController()
        lock.unlock()

        lock.beginHandoff()
        lock.onStopped()

        assertFalse("still unlocked", lock.locked)
    }

    // --- the defect this suite exists for -------------------------------------

    @Test
    fun a_handoff_that_never_started_does_not_swallow_a_real_departure() {
        // The reachable failure. `rememberHandoffLauncher` announces the
        // hand-off before it launches, and the launch can fail — a phone with
        // no document provider throws `ActivityNotFoundException`. Under the
        // old boolean the flag stayed raised with nothing to bring it down, so
        // the *next* time the user pressed Home the app stayed unlocked and sat
        // in recents showing the ledger.
        val lock = LockController()
        lock.unlock()

        lock.beginHandoff()
        lock.endHandoff() // the launch threw; nothing took the foreground

        lock.onStopped()
        assertTrue("pressing Home must lock, whatever was armed earlier", lock.locked)
    }

    @Test
    fun the_picker_returning_arms_the_lock_again() {
        // The whole ordinary sequence: choose Restore, pick a file, come back,
        // and later leave for real.
        val lock = LockController()
        lock.unlock()

        lock.beginHandoff()
        lock.onStopped()
        assertFalse("the picker must not lock the app behind it", lock.locked)

        lock.endHandoff() // the result callback
        lock.onStarted()

        lock.onStopped()
        assertTrue("leaving after the picker is a real departure", lock.locked)
    }

    @Test
    fun two_hand_offs_started_at_once_both_have_to_finish() {
        // A double tap gets two launches through Compose, and a count is the
        // difference between that being harmless and the first result taking
        // the suppression down while the second picker is still on screen.
        val lock = LockController()
        lock.unlock()

        lock.beginHandoff()
        lock.beginHandoff()

        lock.endHandoff()
        lock.onStopped()
        assertFalse("one picker is still up", lock.locked)

        lock.endHandoff()
        lock.onStopped()
        assertTrue(lock.locked)
    }

    @Test
    fun coming_back_clears_a_hand_off_that_was_never_finished() {
        // The backstop. A count that leaked would otherwise leave the app
        // permanently unlockable, which is worse than the defect this fixes —
        // so being in the foreground at all resets it.
        val lock = LockController()
        lock.unlock()

        lock.beginHandoff()
        lock.onStarted()
        lock.onStopped()

        assertTrue(lock.locked)
    }

    @Test
    fun turning_the_setting_off_opens_the_gate_immediately() {
        // Not at the next launch: the user just said they do not want this,
        // and leaving them at a lock screen would be the app arguing.
        val lock = LockController()
        lock.beginHandoff()

        lock.release()

        assertFalse(lock.locked)
        lock.onStopped()
        assertTrue("and the hand-off went with it", lock.locked)
    }
}
