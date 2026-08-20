package com.app.finance.ui.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.awaitState
import com.app.finance.core.money.Money
import com.app.finance.core.time.Period
import com.app.finance.domain.model.IncomeKind
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The dashboard's state machine — FR-AN-01 … FR-AN-10 through the ViewModel.
 *
 * The pure calculations each have a JVM suite; what is asserted here is that
 * the right nine reads reach the right fold with the right window, which is the
 * half no unit test can see. The fixture's clock is pinned to 14 August 2026,
 * so "days remaining" and "days elapsed" are constants rather than whatever the
 * suite happens to run at.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class DashboardViewModelTest {

    private lateinit var fx: TestFixture
    private val store = ViewModelStore()
    private var seq = 0

    private val aug = Period(202608)

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

    private fun vm(period: Period = aug): DashboardViewModel = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DashboardViewModel(fx.dashboard, fx.categories, fx.clock, period) as T
        },
    )["vm${seq++}", DashboardViewModel::class.java]

    private fun spend(taka: Long, category: String, day: Int = 3, month: Int = 8, year: Int = 2026) =
        runBlocking {
            fx.expenses.insert(
                amount = Money.ofTaka(taka),
                categoryId = fx.leafId(category),
                spentOn = LocalDate.of(year, month, day),
            )
        }

    private fun limit(taka: Long, category: String, period: Period = aug) = runBlocking {
        fx.budgets.setLimit(fx.leafId(category), period, Money.ofTaka(taka))
    }

    private fun earn(taka: Long, source: String, day: Int = 1, month: Int = 8) = runBlocking {
        fx.income.saveEntry(Money.ofTaka(taka), source, LocalDate.of(2026, month, day))
    }

    private fun DashboardUiState.leafRow(name: String) =
        groups.flatMap { it.leaves }.firstOrNull { it.name == name }

    // --- FR-AN-01 -------------------------------------------------------------

    @Test
    fun safe_to_spend_divides_the_day_to_day_remainder_by_the_days_left() = runBlocking {
        // 14 August 2026 — 18 days remain, counting today.
        limit(18_000, "Grocery")
        spend(9_000, "Grocery")

        val state = vm().state.awaitState { !it.initialLoad && it.safeToSpend != null }
        assertEquals(18, state.safeToSpend!!.daysRemaining)
        assertEquals(Money.ofTaka(9_000), state.safeToSpend!!.remaining)
        assertEquals(Money.ofTaka(500), state.safeToSpend!!.perDay)
        assertFalse(state.safeToSpend!!.isOver)
    }

    @Test
    fun rent_is_not_part_of_what_is_safe_to_spend_today() = runBlocking {
        // FR-AN-01 names variable and unpredictable only. An untouched rent
        // limit is not money available at a shop counter.
        limit(20_000, "House Rent")
        limit(1_800, "Grocery")

        val state = vm().state.awaitState { !it.initialLoad && it.safeToSpend != null }
        assertEquals(Money.ofTaka(1_800), state.safeToSpend!!.remaining)
        assertEquals(Money.ofTaka(100), state.safeToSpend!!.perDay)
    }

    @Test
    fun overspending_one_category_reduces_what_is_safe_everywhere() = runBlocking {
        limit(5_000, "Grocery")
        limit(10_000, "Transport")
        spend(8_000, "Grocery")

        val state = vm().state.awaitState { !it.initialLoad && it.safeToSpend != null }
        assertEquals("10,000 − 3,000 overspent", Money.ofTaka(7_000), state.safeToSpend!!.remaining)
    }

    // --- FR-AN-02, FR-AN-03 ---------------------------------------------------

    @Test
    fun the_net_line_is_income_minus_expenses_for_the_period() = runBlocking {
        earn(48_000, "Salary")
        spend(31_600, "Grocery")

        val state = vm().state.awaitState { !it.initialLoad && !it.net.income.isZero }
        assertEquals(Money.ofTaka(48_000), state.net.income)
        assertEquals(Money.ofTaka(31_600), state.net.expenses)
        assertEquals(Money.ofTaka(16_400), state.net.net)
        assertEquals(34, state.net.savingsRate)
    }

    @Test
    fun a_month_with_no_income_suppresses_the_savings_rate() = runBlocking {
        spend(4_000, "Grocery")
        val state = vm().state.awaitState { !it.initialLoad && !it.net.expenses.isZero }
        assertNull(state.net.savingsRate)
    }

    @Test
    fun the_expense_total_agrees_with_the_budget_rows_it_is_read_from() = runBlocking {
        // Both come from one query on purpose. Reading a separate scalar would
        // let the net line and the group totals disagree by a rounding or a
        // filter, on a screen whose whole job is to be trusted.
        spend(4_000, "Grocery")
        spend(6_000, "Transport")
        spend(15_000, "House Rent")

        val state = vm().state.awaitState { !it.initialLoad && !it.net.expenses.isZero }
        val fromGroups = state.groups.fold(Money.ZERO) { acc, g -> acc + g.spent }
        assertEquals(fromGroups, state.net.expenses)
        assertEquals(Money.ofTaka(25_000), state.net.expenses)
    }

    // --- FR-AN-04 -------------------------------------------------------------

    @Test
    fun a_category_on_pace_to_overspend_is_projected_before_it_gets_there() = runBlocking {
        // Day 14 of 31. ৳12,000 projects to ৳26,571 against a ৳18,000 limit,
        // and nothing is over yet — PRD §6.4's "warns on day 12, not day 30".
        limit(18_000, "Grocery")
        spend(12_000, "Grocery")

        val state = vm().state.awaitState { !it.initialLoad && it.projections.isNotEmpty() }
        val row = state.projections.single()
        assertEquals("Grocery", row.name)
        assertFalse("not yet over — that is the point", row.isAlreadyOver)
        assertTrue(row.projected > Money.ofTaka(18_000))
    }

    @Test
    fun an_unpredictable_category_is_never_projected() = runBlocking {
        // FR-BUD-07 and PRD §6.2 — a buffer has no pace to extrapolate.
        limit(5_000, "Medical")
        spend(4_000, "Medical")

        val state = vm().state.awaitState { !it.initialLoad && it.groups.isNotEmpty() }
        assertTrue(state.projections.none { it.name == "Medical" })
    }

    // --- FR-AN-05 -------------------------------------------------------------

    @Test
    fun the_biggest_change_is_measured_against_the_trailing_three_months() = runBlocking {
        (5..7).forEach { spend(6_000, "Grocery", month = it) }
        spend(10_000, "Grocery")

        val state = vm().state.awaitState { !it.initialLoad && it.deltas.isNotEmpty() }
        val delta = state.deltas.first { it.name == "Grocery" }
        assertEquals(Money.ofTaka(6_000), delta.baseline)
        assertEquals(Money.ofTaka(4_000), delta.increase)
    }

    @Test
    fun a_category_that_fell_is_not_a_change_worth_acting_on() = runBlocking {
        (5..7).forEach { spend(9_000, "Transport", month = it) }
        spend(1_000, "Transport")

        val state = vm().state.awaitState { !it.initialLoad && it.groups.isNotEmpty() }
        assertTrue(state.deltas.none { it.name == "Transport" })
    }

    // --- FR-AN-06, FR-AN-07 ---------------------------------------------------

    @Test
    fun stable_coverage_uses_only_stable_sources() = runBlocking {
        fx.income.createSource("Farming", IncomeKind.VARIABLE)
        earn(20_000, "Salary")
        earn(80_000, "Farming")
        spend(40_000, "Grocery")

        val state = vm().state.awaitState { !it.initialLoad && it.coverage != null }
        // Salary is the seeded Stable source; farming income does not count
        // toward the figure that says how exposed the user is to a bad season.
        assertEquals(50, state.coverage)
    }

    @Test
    fun coverage_is_absent_rather_than_a_hundred_when_nothing_was_spent() = runBlocking {
        earn(20_000, "Salary")
        val state = vm().state.awaitState { !it.initialLoad && !it.net.income.isZero }
        assertNull(state.coverage)
    }

    @Test
    fun the_spend_mix_splits_by_nature_and_sums_to_a_hundred() = runBlocking {
        spend(12_000, "Grocery")
        spend(18_000, "House Rent")

        val state = vm().state.awaitState { !it.initialLoad && it.mix.isNotEmpty() }
        assertEquals(100, state.mix.sumOf { it.share })
        assertEquals(
            "variable before fixed — rent is not a decision",
            listOf(Nature.VARIABLE, Nature.FIXED),
            state.mix.map { it.nature },
        )
    }

    // --- FR-AN-08, FR-AN-09, FR-AN-10 -----------------------------------------

    @Test
    fun the_five_largest_expenses_are_listed_biggest_first() = runBlocking {
        listOf(900L, 5_000L, 1_200L, 300L, 7_000L, 40L).forEach { spend(it, "Grocery") }

        val state = vm().state.awaitState { !it.initialLoad && it.largest.size == 5 }
        assertEquals(
            listOf(700_000L, 500_000L, 120_000L, 90_000L, 30_000L),
            state.largest.map { it.expense.amountMinor },
        )
    }

    @Test
    fun the_trend_covers_six_periods_ending_at_the_selection() = runBlocking {
        spend(1_000, "Grocery", month = 3)
        spend(2_000, "Grocery")

        val state = vm().state.awaitState { !it.initialLoad && it.trend != null }
        val trend = state.trend!!
        assertEquals(6, trend.periods.size)
        assertEquals(Period(202603), trend.periods.first())
        assertEquals(aug, trend.periods.last())
        assertEquals(100_000L, trend.spend.first())
        assertEquals(200_000L, trend.spend.last())
    }

    @Test
    fun the_trend_carries_the_budget_reference_and_flags_the_months_over_it() = runBlocking {
        limit(1_000, "Grocery")
        spend(3_000, "Grocery")

        val state = vm().state.awaitState { !it.initialLoad && it.trend != null }
        val trend = state.trend!!
        assertEquals(100_000L, trend.reference.last())
        assertTrue(trend.isOver(trend.periods.lastIndex))
    }

    @Test
    fun the_income_average_is_over_twelve_periods_and_not_over_one() = runBlocking {
        // FR-AN-10 exists because "seasonal sources earn nothing for months and
        // then a lump sum; a short window produces figures that are wrong in
        // both directions". One harvest of ৳120,000 is ৳10,000 a month, not
        // ৳120,000 a month.
        earn(120_000, "Salary", month = 6)

        val state = vm().state.awaitState { !it.initialLoad && !it.averageIncome.isZero }
        assertEquals(Money.ofTaka(10_000), state.averageIncome)
    }

    // --- the ribbon and the period --------------------------------------------

    @Test
    fun the_ribbon_has_one_bar_per_day_and_marks_today() = runBlocking {
        spend(1_000, "Grocery", day = 3)

        val state = vm().state.awaitState { !it.initialLoad && it.ribbon.dailyTotals.isNotEmpty() }
        assertEquals(31, state.ribbon.dailyTotals.size)
        assertEquals(100_000L, state.ribbon.dailyTotals[2])
        assertEquals("14 August, zero-based", 13, state.ribbon.todayIndex)
    }

    @Test
    fun another_month_has_no_today_to_mark() = runBlocking {
        val state = vm(Period(202606)).state.awaitState { !it.initialLoad }
        assertEquals(-1, state.ribbon.todayIndex)
        assertEquals(30, state.ribbon.dailyTotals.size)
    }

    @Test
    fun a_finished_period_reports_what_was_left_rather_than_a_daily_figure() = runBlocking {
        limit(5_000, "Grocery", period = Period(202606))
        val state = vm(Period(202606)).state.awaitState { !it.initialLoad && it.safeToSpend != null }
        assertNull("no today to spend in", state.safeToSpend!!.perDay)
        assertEquals(Money.ofTaka(5_000), state.safeToSpend!!.remaining)
    }

    @Test
    fun switching_the_period_re_points_every_read() = runBlocking {
        spend(4_000, "Grocery", month = 8)
        spend(9_000, "Grocery", month = 7)

        val vm = vm()
        vm.state.awaitState { !it.initialLoad && it.net.expenses == Money.ofTaka(4_000) }
        vm.setPeriod(Period(202607))

        val state = vm.state.awaitState { !it.initialLoad && it.period == Period(202607) }
        assertEquals(Money.ofTaka(9_000), state.net.expenses)
        assertEquals(-1, state.ribbon.todayIndex)
    }

    @Test
    fun saving_an_expense_moves_the_figures_without_a_refresh() = runBlocking {
        // 04 §5.1 — Room's invalidation tracker is the whole mechanism, and
        // FR-AN-08's criterion is "within one frame of the confirming action".
        limit(10_000, "Grocery")
        val vm = vm()
        vm.state.awaitState { !it.initialLoad && it.safeToSpend != null }

        spend(4_000, "Grocery")

        val state = vm.state.awaitState { it.net.expenses == Money.ofTaka(4_000) }
        assertEquals(Money.ofTaka(6_000), state.safeToSpend!!.remaining)
        assertEquals(Money.ofTaka(4_000), state.leafRow("Grocery")!!.status.spent)
    }

    // --- absent, not empty ----------------------------------------------------

    @Test
    fun a_month_with_nothing_in_it_reports_itself_empty() = runBlocking {
        val state = vm().state.awaitState { !it.initialLoad }
        assertTrue(state.isEmpty)
        assertTrue(state.alerts.isEmpty())
        assertTrue(state.deltas.isEmpty())
        assertTrue(state.mix.isEmpty())
        assertTrue(state.largest.isEmpty())
    }

    @Test
    fun limits_without_spending_are_not_an_empty_dashboard() = runBlocking {
        // The most useful the screen ever is: a full safe-to-spend figure and
        // nothing spent against it yet. Treating that as empty would hide the
        // one number the user opened the app for.
        limit(18_000, "Grocery")

        val state = vm().state.awaitState { !it.initialLoad && it.safeToSpend != null }
        assertFalse(state.isEmpty)
        assertNotNull(state.safeToSpend!!.perDay)
    }

    // --- archived categories (A2) --------------------------------------------

    @Test
    fun archiving_a_category_takes_its_budget_out_of_safe_to_spend() = runBlocking {
        // FR-CAT-08 keeps the row on the screen while it carries spend, and the
        // same requirement is why the figure must drop it: the entry picker no
        // longer offers the category, so what is left of its limit cannot be
        // spent. Before the audit this figure was still offering ৳16,000.
        limit(18_000, "Grocery")
        limit(5_000, "Transport")
        spend(2_000, "Grocery")

        val vm = vm()
        vm.state.awaitState { !it.initialLoad && it.safeToSpend?.remaining == Money.ofTaka(21_000) }

        fx.categories.archive(fx.leafId("Grocery"))

        val state = vm.state.awaitState { it.safeToSpend?.remaining == Money.ofTaka(5_000) }
        assertEquals(Money.ofTaka(5_000), state.safeToSpend!!.remaining)
    }

    @Test
    fun an_archived_category_keeps_its_spending_in_every_backward_looking_figure() = runBlocking {
        // The other half. The money really was spent, so the net line, the
        // group total and the spend mix all keep it — only the two
        // forward-looking figures drop it.
        limit(18_000, "Grocery")
        spend(2_000, "Grocery")

        val vm = vm()
        vm.state.awaitState { !it.initialLoad && it.net.expenses == Money.ofTaka(2_000) }
        fx.categories.archive(fx.leafId("Grocery"))

        val state = vm.state.awaitState { it.safeToSpend?.remaining == Money.ZERO }
        assertEquals("still spent", Money.ofTaka(2_000), state.net.expenses)
        assertEquals(
            "still in the group total",
            Money.ofTaka(2_000),
            state.groups.fold(Money.ZERO) { acc, g -> acc + g.spent },
        )
        assertEquals("still in the mix", Money.ofTaka(2_000), state.mix.sumOf { it.total.paisa }.let(::Money))
        assertTrue(
            "and still on the screen",
            state.groups.flatMap { it.leaves }.any { it.name == "Grocery" },
        )
    }

    @Test
    fun an_archived_category_is_no_longer_projected_to_overspend() = runBlocking {
        // Day 14 of 31, ৳12,000 against ৳18,000 — over pace. Archiving it must
        // stop the warning: nothing more can be spent there.
        limit(18_000, "Grocery")
        spend(12_000, "Grocery")

        val vm = vm()
        vm.state.awaitState { !it.initialLoad && it.projections.isNotEmpty() }

        fx.categories.archive(fx.leafId("Grocery"))

        assertTrue(vm.state.awaitState { it.projections.isEmpty() }.projections.isEmpty())
    }
}
