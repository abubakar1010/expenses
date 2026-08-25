package com.app.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The order in which the launch gates apply — 04 §8, FR-APP-04, FR-DAT-10.
 *
 * **`06 §20.3` claimed this test existed for two milestones and it did not.**
 * The ordering lived in a `when` inside `MainActivity.setContent`, and nothing
 * in the suite composes `MainActivity`. Worse, the obvious way to reach it
 * cannot work: a test that breaks the database to get to the recovery branch
 * also breaks the `app_meta` read the lock setting comes from, so
 * `observeAppLock` catches, emits `false`, and the branch whose precedence is
 * being asserted is not in the running at all. The assertion would pass while
 * measuring nothing — which is `§22.5`'s whole subject.
 *
 * So the decision was extracted instead of the activity being launched. Every
 * combination is enumerated below, because five inputs is thirty-six reachable
 * states and "I checked the interesting ones" is how an ordering acquires a
 * hole.
 */
class RootScreenTest {

    // --- the ordering that matters most ---------------------------------------

    @Test
    fun a_broken_database_wins_over_an_engaged_lock() {
        // §19.1's defect was a setting read that ran ahead of the recovery
        // path. A gate that failed *shut* would lock a user out of the one
        // screen that can rescue five years of data, so the lock is never
        // consulted when the database is broken.
        assertEquals(
            RootScreen.RECOVERY,
            rootScreen(
                databaseFailed = true,
                lockEnabled = true,
                locked = true,
                welcomeLatch = true,
                welcomeDone = false,
            ),
        )
    }

    @Test
    fun a_broken_database_wins_even_while_the_lock_setting_is_still_loading() {
        // The subtler half. `lockEnabled == null` means "not read yet", and
        // waiting for it would put the recovery screen behind a read that a
        // broken database is never going to complete.
        assertEquals(
            RootScreen.RECOVERY,
            rootScreen(
                databaseFailed = true,
                lockEnabled = null,
                locked = true,
                welcomeLatch = null,
                welcomeDone = false,
            ),
        )
    }

    @Test
    fun the_lock_wins_over_the_welcome_offer() {
        // FR-DAT-10 puts the restore behind the gate: a backup file is the
        // whole ledger, so the gate that protects the ledger should protect the
        // door it can be replaced through.
        assertEquals(
            RootScreen.LOCK,
            rootScreen(
                databaseFailed = false,
                lockEnabled = true,
                locked = true,
                welcomeLatch = true,
                welcomeDone = false,
            ),
        )
    }

    // --- a setting that has not arrived is not a setting that is off ----------

    @Test
    fun an_unread_lock_setting_shows_a_blank_rather_than_the_ledger() {
        // Defaulting to "unlocked" would show the ledger for a frame or two
        // before the gate arrived, which is the one thing a lock must not do.
        assertEquals(
            RootScreen.LOADING,
            rootScreen(
                databaseFailed = false,
                lockEnabled = null,
                locked = true,
                welcomeLatch = false,
                welcomeDone = true,
            ),
        )
    }

    @Test
    fun an_unread_onboarding_latch_shows_a_blank_rather_than_the_ledger() {
        // Same reason one gate down: FR-DAT-10's offer is only useful before
        // anything is entered, and flashing the empty dashboard first is how a
        // user concludes their data is gone.
        assertEquals(
            RootScreen.LOADING,
            rootScreen(
                databaseFailed = false,
                lockEnabled = false,
                locked = false,
                welcomeLatch = null,
                welcomeDone = false,
            ),
        )
    }

    @Test
    fun an_unread_onboarding_latch_still_waits_behind_an_open_lock() {
        // The lock is satisfied here — enabled but unlocked — so the next gate
        // gets its turn, and it is still resolving.
        assertEquals(
            RootScreen.LOADING,
            rootScreen(
                databaseFailed = false,
                lockEnabled = true,
                locked = false,
                welcomeLatch = null,
                welcomeDone = false,
            ),
        )
    }

    // --- the ordinary ways through ---------------------------------------------

    @Test
    fun an_unlocked_app_with_an_empty_ledger_offers_the_restore() {
        assertEquals(
            RootScreen.WELCOME,
            rootScreen(
                databaseFailed = false,
                lockEnabled = true,
                locked = false,
                welcomeLatch = true,
                welcomeDone = false,
            ),
        )
    }

    @Test
    fun answering_the_offer_moves_past_it_without_waiting_for_the_flag_to_be_reread() {
        // `welcomeDone` is the in-session latch. The stored flag is written
        // too, but a screen that waited for its own write to come back around a
        // Room flow would show itself again for as long as that took.
        assertEquals(
            RootScreen.APP,
            rootScreen(
                databaseFailed = false,
                lockEnabled = true,
                locked = false,
                welcomeLatch = true,
                welcomeDone = true,
            ),
        )
    }

    @Test
    fun a_ledger_with_entries_and_no_lock_goes_straight_in() {
        assertEquals(
            RootScreen.APP,
            rootScreen(
                databaseFailed = false,
                lockEnabled = false,
                locked = false,
                welcomeLatch = false,
                welcomeDone = false,
            ),
        )
    }

    @Test
    fun the_lock_being_disabled_ignores_the_controller_entirely() {
        // The controller starts locked and stays locked until something
        // authenticates. If the *setting* is off, that must not matter — and
        // reading the two in the wrong order is how an app-lock people never
        // switched on locks them out.
        assertEquals(
            RootScreen.APP,
            rootScreen(
                databaseFailed = false,
                lockEnabled = false,
                locked = true,
                welcomeLatch = false,
                welcomeDone = false,
            ),
        )
    }

    // --- and every combination, so the ordering has no hole -------------------

    @Test
    fun the_gates_apply_in_order_across_every_reachable_state() {
        // Thirty-six states. Enumerating them is cheap and the alternative is
        // trusting that the six cases above are the six that matter.
        val tri = listOf(true, false, null)
        val bool = listOf(true, false)

        for (databaseFailed in bool) {
            for (lockEnabled in tri) {
                for (locked in bool) {
                    for (welcomeLatch in tri) {
                        for (welcomeDone in bool) {
                            val actual = rootScreen(
                                databaseFailed, lockEnabled, locked, welcomeLatch, welcomeDone,
                            )
                            val expected = when {
                                databaseFailed -> RootScreen.RECOVERY
                                lockEnabled == null -> RootScreen.LOADING
                                lockEnabled && locked -> RootScreen.LOCK
                                welcomeLatch == null -> RootScreen.LOADING
                                welcomeLatch && !welcomeDone -> RootScreen.WELCOME
                                else -> RootScreen.APP
                            }
                            assertEquals(
                                "databaseFailed=$databaseFailed lockEnabled=$lockEnabled " +
                                    "locked=$locked welcomeLatch=$welcomeLatch " +
                                    "welcomeDone=$welcomeDone",
                                expected,
                                actual,
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun the_ledger_is_never_reached_while_anything_is_unresolved() {
        // The property the enumeration above is really about, stated once so a
        // future branch cannot satisfy the table and still leak the ledger.
        //
        // Stated as "never `APP`" rather than "always `LOADING`", which is what
        // the first version of this test asserted and why it failed: an
        // *engaged* lock correctly takes precedence over an unresolved welcome
        // latch. `lockEnabled` has been read and says locked, so there is
        // nothing left to wait for — showing a blank there would be waiting on
        // a gate that has already answered.
        val tri = listOf(true, false, null)
        val bool = listOf(true, false)

        for (lockEnabled in tri) {
            for (locked in bool) {
                for (welcomeLatch in tri) {
                    for (welcomeDone in bool) {
                        val unresolved = lockEnabled == null || welcomeLatch == null
                        val gated = lockEnabled == true && locked
                        if (!unresolved && !gated) continue

                        val screen = rootScreen(
                            databaseFailed = false,
                            lockEnabled = lockEnabled,
                            locked = locked,
                            welcomeLatch = welcomeLatch,
                            welcomeDone = welcomeDone,
                        )
                        assertNotEquals(
                            "reached the ledger with lockEnabled=$lockEnabled locked=$locked " +
                                "welcomeLatch=$welcomeLatch",
                            RootScreen.APP,
                            screen,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun an_engaged_lock_takes_precedence_over_a_setting_that_is_still_loading() {
        // The case the test above had to be corrected for, pinned on its own so
        // it is a decision rather than an exception in a loop.
        assertEquals(
            RootScreen.LOCK,
            rootScreen(
                databaseFailed = false,
                lockEnabled = true,
                locked = true,
                welcomeLatch = null,
                welcomeDone = false,
            ),
        )
    }
}
