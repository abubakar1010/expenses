package com.app.finance.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.core.money.Money
import com.app.finance.core.time.Period
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two pieces of state that decide what the app looks like when it opens —
 * FR-APP-03's remembered period and FR-DAT-10's welcome gate.
 *
 * Neither had a test. Both are read on the launch path, both are written from
 * exactly one place, and a defect in either is invisible in the way that
 * matters: the app simply opens on the wrong month, or asks a question it has
 * already been answered. §22 found the period's write racing its own read in
 * `AppNav`, which is the sort of thing that is only findable once somebody
 * decides these functions are worth asserting on.
 */
@RunWith(AndroidJUnit4::class)
class AppStateRepositoryTest {

    private lateinit var fx: TestFixture

    @Before fun setUp() { fx = TestFixture() }
    @After fun tearDown() = fx.close()

    // --- FR-APP-03: the period survives a relaunch ---------------------------

    @Test
    fun nothing_is_remembered_before_anything_has_been_viewed() = runBlocking {
        // Null and not "this month": the caller has to be able to tell "never
        // opened" from "opened, on the current month", because only one of them
        // should leave the default alone.
        assertNull(fx.container.appMetaRepo.lastViewedPeriod())
    }

    @Test
    fun the_viewed_period_comes_back_as_it_went_in() = runBlocking {
        val meta = fx.container.appMetaRepo
        meta.setLastViewedPeriod(Period(202601))

        assertEquals(Period(202601), meta.lastViewedPeriod())
    }

    @Test
    fun the_newest_write_is_the_one_that_is_remembered() = runBlocking {
        // It is an upsert on one key, not an append. A second row under
        // `last_period` would make the read non-deterministic.
        val meta = fx.container.appMetaRepo
        meta.setLastViewedPeriod(Period(202601))
        meta.setLastViewedPeriod(Period(202612))

        assertEquals(Period(202612), meta.lastViewedPeriod())
    }

    @Test
    fun a_stored_period_that_cannot_be_read_is_null_rather_than_a_crash() = runBlocking {
        // `Period`'s init throws on a value outside `YYYYMM`, and this is read
        // on the launch path — so a corrupted row must not be able to take the
        // app down for the sake of remembering a month. The repository returns
        // null; `AppNav` wraps the call in `runCatching` on top of that.
        fx.container.appMetaRepo.put("last_period", "not a period")

        assertNull(fx.container.appMetaRepo.lastViewedPeriod())
    }

    // --- FR-DAT-10: the welcome gate -----------------------------------------

    @Test
    fun a_fresh_install_with_an_empty_ledger_is_asked() = runBlocking {
        assertTrue(fx.settings.observeNeedsWelcome().first())
    }

    @Test
    fun answering_it_stops_it_being_asked_again() = runBlocking {
        // The half that was silently not working: `WelcomeScreen` launched this
        // write in a scope it then cancelled by dismissing itself, so "Start
        // fresh" did not stick and the gate returned on every launch.
        fx.settings.setOnboarded()

        assertFalse(fx.settings.observeNeedsWelcome().first())
    }

    @Test
    fun a_ledger_with_entries_is_not_asked_even_if_it_was_never_answered() = runBlocking {
        // The second half of the condition, and the reason it is not a plain
        // flag: an install that predates this feature has no `onboarded` row
        // and must not be shown a restore offer over a year of data.
        fx.expenses.insert(Money.ofTaka(500), fx.leafId("Grocery"), fx.today)

        assertFalse(fx.settings.observeNeedsWelcome().first())
    }
}
