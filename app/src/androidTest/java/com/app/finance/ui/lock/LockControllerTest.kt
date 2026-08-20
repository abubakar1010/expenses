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
        // beneath it. Without the suppression the app re-locks while the user
        // is in the middle of unlocking it — the classic implementation of
        // this bug, and the reason `suppressNextBackground` exists.
        val lock = LockController()
        lock.unlock()

        lock.suppressNextBackground()
        lock.onStopped()

        assertFalse("still unlocked", lock.locked)
    }

    @Test
    fun the_suppression_is_consumed_by_the_stop_it_was_set_for() {
        // Otherwise one file picker would buy a permanent exemption.
        val lock = LockController()
        lock.unlock()

        lock.suppressNextBackground()
        lock.onStopped()
        lock.onStopped()

        assertTrue("the second departure is a real one", lock.locked)
    }

    @Test
    fun coming_back_clears_a_suppression_that_was_never_used() {
        // A picker the user dismissed without the app ever stopping would
        // otherwise leave the flag standing until the next time they left.
        val lock = LockController()
        lock.unlock()

        lock.suppressNextBackground()
        lock.onStarted()
        lock.onStopped()

        assertTrue(lock.locked)
    }

    @Test
    fun turning_the_setting_off_opens_the_gate_immediately() {
        // Not at the next launch: the user just said they do not want this,
        // and leaving them at a lock screen would be the app arguing.
        val lock = LockController()
        lock.suppressNextBackground()

        lock.release()

        assertFalse(lock.locked)
        lock.onStopped()
        assertTrue("and the suppression went with it", lock.locked)
    }
}
