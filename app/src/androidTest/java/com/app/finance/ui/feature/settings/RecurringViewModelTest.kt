package com.app.finance.ui.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.awaitState
import com.app.finance.domain.model.Frequency
import com.app.finance.ui.common.KeypadKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The repeating-entry editor (FR-REC-01) — 06 §36.3 and §35.4.
 *
 * The fixture's today is Friday 14 August 2026.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class RecurringViewModelTest {

    private lateinit var fx: TestFixture
    private val store = ViewModelStore()
    private var seq = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        fx = TestFixture()
    }

    @After
    fun tearDown() {
        store.clear()
        fx.closeAfterDraining()
        Dispatchers.resetMain()
    }

    private fun vm(): RecurringViewModel = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                RecurringViewModel(fx.recurring, fx.categories, fx.income, fx.people, fx.clock) as T
        },
    )["vm${seq++}", RecurringViewModel::class.java]

    /** An editor with a category and ৳1,200 typed — everything but the schedule. */
    private suspend fun editing(): RecurringViewModel {
        val vm = vm()
        vm.state.awaitState { !it.loading }
        vm.add()
        vm.setTargetId(fx.leafId("Internet"))
        "1200".forEach { vm.onKey(KeypadKey.Digit(it)) }
        return vm
    }

    private suspend fun savedFirstDue(vm: RecurringViewModel): LocalDate {
        vm.submit {}
        val rules = vm.state.awaitState { it.editor == null && it.rules.isNotEmpty() }.rules
        return LocalDate.ofEpochDay(rules.single().rule.nextDueDay)
    }

    @Test
    fun a_double_tap_on_save_creates_one_rule() = runBlocking {
        val vm = editing()

        vm.submit {}
        vm.submit {}
        vm.state.awaitState { it.editor == null }
        delay(500)

        val rules = fx.db.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM recurring_rule").use { it.moveToFirst(); it.getLong(0) }
        assertEquals(1L, rules)
    }

    @Test
    fun the_schedule_defaults_to_today_s_month_and_weekday() = runBlocking {
        val editor = editing().state.value.editor!!

        assertEquals(8, editor.month)
        assertEquals(DayOfWeek.FRIDAY, editor.weekday)
    }

    @Test
    fun a_yearly_rule_can_be_set_for_a_month_other_than_this_one() = runBlocking {
        // A premium due every March could not be entered in August: the month
        // came from the day the rule was made.
        val vm = editing()
        vm.setFrequency(Frequency.YEARLY)
        vm.setAnchorDay(10)
        vm.setMonth(3)

        assertEquals(LocalDate.of(2027, 3, 10), vm.state.value.editor!!.firstDue)
        assertEquals(LocalDate.of(2027, 3, 10), savedFirstDue(vm))
    }

    @Test
    fun a_weekly_rule_can_be_set_for_a_weekday_other_than_today() = runBlocking {
        val vm = editing()
        vm.setFrequency(Frequency.WEEKLY)
        vm.setWeekday(DayOfWeek.MONDAY)

        assertEquals(LocalDate.of(2026, 8, 17), savedFirstDue(vm))
    }
}
