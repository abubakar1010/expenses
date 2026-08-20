package com.app.finance.ui.feature.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.awaitState
import com.app.finance.core.money.Money
import com.app.finance.domain.model.Nature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Reports — 04 §7, and the one screen that reads the ledger on purpose.
 *
 * The fixture's clock is pinned at 14 August 2026, so "this month" is 1–14
 * August and "last month" is the whole of July.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ReportsViewModelTest {

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

    private fun vm(): ReportsViewModel = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ReportsViewModel(fx.reports, fx.clock) as T
        },
    )["vm${seq++}", ReportsViewModel::class.java]

    private suspend fun spend(taka: Long, category: String, on: LocalDate) =
        fx.expenses.insert(
            amount = Money.ofTaka(taka),
            categoryId = fx.leafId(category),
            spentOn = on,
        )

    @Test
    fun it_opens_on_this_month_so_far() = runBlocking {
        val state = vm().state.awaitState { !it.loading }
        assertEquals(LocalDate.of(2026, 8, 1), state.range.from)
        assertEquals("today, not the end of the month", LocalDate.of(2026, 8, 14), state.range.to)
        assertEquals(RangePreset.THIS_MONTH, state.range.preset)
    }

    @Test
    fun the_total_counts_only_what_falls_inside_the_range() = runBlocking {
        spend(500, "Grocery", LocalDate.of(2026, 8, 3))
        spend(900, "Grocery", LocalDate.of(2026, 7, 20))

        val state = vm().state.awaitState { !it.loading && it.count > 0 }
        assertEquals(Money.ofTaka(500), state.total)
        assertEquals(1, state.count)
    }

    @Test
    fun last_month_is_the_whole_of_last_month() = runBlocking {
        // Not "thirty days back": a spending report about July should contain
        // the 31st of July.
        spend(900, "Grocery", LocalDate.of(2026, 7, 31))
        spend(500, "Grocery", LocalDate.of(2026, 8, 3))

        val vm = vm()
        vm.state.awaitState { !it.loading }
        vm.setPreset(RangePreset.LAST_MONTH)

        val state = vm.state.awaitState { it.range.preset == RangePreset.LAST_MONTH && !it.loading }
        assertEquals(LocalDate.of(2026, 7, 1), state.range.from)
        assertEquals(LocalDate.of(2026, 7, 31), state.range.to)
        assertEquals(Money.ofTaka(900), state.total)
    }

    @Test
    fun a_range_that_crosses_months_is_what_the_rollups_cannot_answer() = runBlocking {
        // The reason this screen exists. Neither month's rollup holds this
        // figure, and 03 §5.3 is the licence to read the ledger for it.
        spend(300, "Grocery", LocalDate.of(2026, 7, 28))
        spend(400, "Grocery", LocalDate.of(2026, 8, 2))
        spend(999, "Grocery", LocalDate.of(2026, 8, 12))

        val vm = vm()
        vm.state.awaitState { !it.loading }
        vm.setFrom(LocalDate.of(2026, 7, 25))
        vm.setTo(LocalDate.of(2026, 8, 5))

        val state = vm.state.awaitState { it.range.preset == RangePreset.CUSTOM && !it.loading }
        assertEquals(Money.ofTaka(700), state.total)
        assertEquals(2, state.count)
    }

    @Test
    fun endpoints_picked_in_the_wrong_order_are_swapped_rather_than_refused() = runBlocking {
        spend(300, "Grocery", LocalDate.of(2026, 8, 3))

        val vm = vm()
        vm.state.awaitState { !it.loading }
        // "To" first, and earlier than the "from" that follows.
        vm.setTo(LocalDate.of(2026, 8, 1))
        vm.setFrom(LocalDate.of(2026, 8, 10))

        val state = vm.state.awaitState { it.range.preset == RangePreset.CUSTOM && !it.loading }
        assertTrue("the range reads forwards", state.range.from <= state.range.to)
        assertEquals(LocalDate.of(2026, 8, 1), state.range.from)
        assertEquals(LocalDate.of(2026, 8, 10), state.range.to)
    }

    @Test
    fun the_split_is_the_same_apportionment_the_dashboard_uses() = runBlocking {
        // FR-AN-07, through `SpendMix.ofTotals` — the shared implementation, so
        // a percentage cannot mean one thing here and another on the dashboard.
        spend(6_000, "Grocery", LocalDate.of(2026, 8, 3))
        spend(4_000, "House Rent", LocalDate.of(2026, 8, 1))

        val state = vm().state.awaitState { !it.loading && it.mix.size == 2 }
        assertEquals(100, state.mix.sumOf { it.share })

        val variable = state.mix.first { it.nature == Nature.VARIABLE }
        assertEquals(Money.ofTaka(6_000), variable.total)
        assertEquals(60, variable.share)
    }

    @Test
    fun a_nature_with_no_spend_is_absent_rather_than_zero() = runBlocking {
        // 05 §5.4, the same rule the dashboard follows.
        spend(6_000, "Grocery", LocalDate.of(2026, 8, 3))

        val state = vm().state.awaitState { !it.loading && it.mix.isNotEmpty() }
        assertEquals(1, state.mix.size)
        assertEquals(Nature.VARIABLE, state.mix.single().nature)
    }

    @Test
    fun the_largest_expenses_are_the_five_biggest_in_the_range() = runBlocking {
        // FR-AN-08's shape, over a range rather than a period.
        repeat(8) { i -> spend(100L * (i + 1), "Grocery", LocalDate.of(2026, 8, 2 + i)) }
        spend(9_999, "Grocery", LocalDate.of(2026, 7, 5))

        val state = vm().state.awaitState { !it.loading && it.largest.isNotEmpty() }
        assertEquals(5, state.largest.size)
        assertEquals(Money.ofTaka(800).paisa, state.largest.first().expense.amountMinor)
        assertTrue(
            "July's outlier is outside the range",
            state.largest.none { it.expense.amountMinor == Money.ofTaka(9_999).paisa },
        )
    }

    @Test
    fun an_empty_range_is_distinguishable_from_one_still_loading() = runBlocking {
        val state = vm().state.awaitState { !it.loading }
        assertTrue(state.isEmpty)
        assertFalse(state.loading)
        assertEquals(Money.ZERO, state.total)
    }

    @Test
    fun a_pending_row_stays_out_of_every_figure() = runBlocking {
        // Every read on this screen filters `status = 0`, like every other read
        // in the app: a rule's unconfirmed entry is not money anyone spent yet.
        spend(500, "Grocery", LocalDate.of(2026, 8, 3))
        fx.db.openHelper.writableDatabase.execSQL(
            "UPDATE expense SET status = 1 WHERE amount_minor = 50000",
        )

        val state = vm().state.awaitState { !it.loading }
        assertEquals(Money.ZERO, state.total)
        assertEquals(0, state.count)
        assertTrue(state.mix.isEmpty())
        assertTrue(state.largest.isEmpty())
    }
}
