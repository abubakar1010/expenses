package com.app.finance.ui.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.awaitState
import com.app.finance.core.money.Money
import com.app.finance.core.time.Period
import com.app.finance.dev.SeedFiveYears
import com.app.finance.domain.model.IncomeKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **The M4 exit criterion, in the half that does not need a stopwatch.**
 *
 * > `| M4 | Dashboard analytics | Dashboard renders in ≤ 300 ms with 5 years
 * > seeded data |`
 *
 * NFR-PERF-04's 300 ms is a wall-clock number and belongs to `DashboardBenchmark`
 * on the reference device. But the *reason* it holds is a structural claim, and
 * that claim is testable here. 03 §5.1:
 *
 * > "Row count is bounded by the number of leaf categories — dozens, not
 * > thousands — independent of transaction history size. **This is what holds
 * > NFR-PERF-04 at 300 ms as the ledger grows to five years.**"
 *
 * So this suite seeds five years and asserts two things: that the dashboard's
 * reads return the same number of rows over sixty periods as over one, and that
 * every figure it renders still reconciles against a direct sum over the ledger
 * at that scale. A screen that is fast because it is wrong is not fast.
 *
 * [SeedFiveYears] lives in the debug source set, which `androidTestDebug`
 * compiles against — one generator, shared with the on-device seeder, rather
 * than a second copy of the same data drifting out of step with it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class DashboardScaleTest {

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

    private suspend fun seed(): SeedFiveYears.Counts = SeedFiveYears.into(fx.db, aug)

    private fun scalar(sql: String): Long =
        fx.db.openHelper.writableDatabase.query(sql)
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun ledgerSpend(period: Period) = scalar(
        "SELECT IFNULL(SUM(amount_minor), 0) FROM expense " +
            "WHERE status = 0 AND period_ym = ${period.ym}",
    )

    private fun ledgerIncome(period: Period) = scalar(
        "SELECT IFNULL(SUM(amount_minor), 0) FROM income_entry " +
            "WHERE status = 0 AND period_ym = ${period.ym}",
    )

    private fun ledgerStableIncome(period: Period) = scalar(
        """
        SELECT IFNULL(SUM(e.amount_minor), 0)
          FROM income_entry e JOIN income_source s ON s.id = e.source_id
         WHERE e.status = 0 AND s.kind = ${IncomeKind.STABLE.code}
           AND e.period_ym = ${period.ym}
        """.trimIndent(),
    )

    // --- the data is what the criterion names ---------------------------------

    @Test
    fun five_years_is_five_years() = runBlocking {
        val counts = seed()
        assertEquals(SeedFiveYears.PERIODS, counts.periods)
        // This is the INSTALL corpus — thirteen leaves and the traffic they
        // attract, which lands near 4,200 over five years. 03 §9's 20,000 and
        // 02 §3.1's "60 categories" describe the *benchmark* corpus instead,
        // and `SeedFiveYears.Scale.BENCHMARK` is where that lives; the comment
        // here used to quote 9,000, which is neither number.
        //
        // The band still has to be a band: a generator that quietly produced a
        // tenth of this would make every assertion below pass for the wrong
        // reason.
        assertTrue(
            "expected a five-year ledger, got ${counts.expenses} expenses",
            counts.expenses in 3_000..6_000,
        )
        // Again the INSTALL corpus: one payment per source per period, which
        // lands near 160. 02 §3.1's 400 belongs to BENCHMARK.
        assertTrue(
            "expected a five-year income history, got ${counts.income} entries",
            counts.income in 100..300,
        )
        assertEquals(counts.expenses.toLong(), scalar("SELECT COUNT(*) FROM expense"))
    }

    @Test
    fun the_income_is_lumpy_rather_than_smooth() = runBlocking {
        // PRD §1: "five months earn nothing and the sixth earns a year's worth".
        // A uniform generator would leave every delta at zero and every trend
        // flat — the states in which none of these metrics can be wrong.
        seed()
        val monthly = (1..12).map { ledgerIncome(Period.of(2026, it)) }.filter { it > 0 }
        assertTrue("expected some months to differ wildly", monthly.max() > monthly.min() * 3)
    }

    // --- 03 §5.1's claim, as an assertion -------------------------------------

    @Test
    fun the_dashboard_reads_the_same_number_of_rows_over_five_years_as_over_one() = runBlocking {
        // The whole performance argument in one test. If a dashboard read ever
        // starts scanning the ledger, this is where it shows up — as a row
        // count that grows with history rather than with the category tree.
        val oneMonth = readSizes()
        seed()
        val fiveYears = readSizes()

        assertEquals("budget bars", oneMonth.bars, fiveYears.bars)
        // Bounded by the number of *sources*, not compared against the empty
        // baseline: before seeding there is no income at all, so the old
        // equality asserted 0 == 0 on an empty database and 0 == 2 on a
        // seeded one. What the claim is actually about is that the read does
        // not grow with history.
        assertTrue(
            "income cells are bounded by the sources, got ${fiveYears.incomeCells}",
            fiveYears.incomeCells <= scalar("SELECT COUNT(*) FROM income_source").toInt(),
        )
        assertTrue(
            "daily spend is bounded by the month's length, got ${fiveYears.daily}",
            fiveYears.daily <= aug.daysInMonth(),
        )
        assertTrue(
            "the delta read is bounded by 4 periods x leaves, got ${fiveYears.categoryCells}",
            fiveYears.categoryCells <= 4 * LEAF_COUNT,
        )
        assertTrue(
            "the trend read is bounded by 6 periods, got ${fiveYears.series}",
            fiveYears.series <= 6,
        )
        assertEquals("FR-AN-08 is a LIMIT 5", 5, fiveYears.largest)
    }

    private data class Sizes(
        val bars: Int,
        val incomeCells: Int,
        val daily: Int,
        val categoryCells: Int,
        val series: Int,
        val largest: Int,
    )

    private suspend fun readSizes() = Sizes(
        bars = fx.dashboard.observeBars(aug).first().size,
        incomeCells = fx.dashboard.observeIncomeCells(aug).first().size,
        daily = fx.dashboard.observeDailySpend(aug).first().size,
        categoryCells = fx.dashboard.observeCategoryCells(aug).first().size,
        series = fx.dashboard.observeExpenseSeries(aug).first().size,
        largest = fx.dashboard.observeLargestExpenses(aug).first().size,
    )

    // --- and it is still correct at that scale --------------------------------

    @Test
    fun every_figure_still_reconciles_against_the_ledger_at_five_year_scale() = runBlocking {
        seed()
        val expected = ledgerSpend(aug)
        val state = vm().state.awaitState(timeoutMillis = 30_000) {
            !it.initialLoad && it.net.expenses.paisa == expected
        }

        assertEquals("period spend", expected, state.net.expenses.paisa)
        assertEquals("period income", ledgerIncome(aug), state.net.income.paisa)
        assertEquals(
            "the groups account for the whole of it",
            expected,
            state.groups.fold(Money.ZERO) { acc, g -> acc + g.spent }.paisa,
        )
        assertEquals(
            "the ribbon accounts for the whole of it",
            expected,
            state.ribbon.dailyTotals.sum(),
        )
        assertEquals(
            "the spend mix accounts for the whole of it",
            expected,
            state.mix.sumOf { it.total.paisa },
        )
        assertEquals("FR-AN-07's shares still total 100", 100, state.mix.sumOf { it.share })
    }

    @Test
    fun the_trend_reconciles_period_by_period_at_scale() = runBlocking {
        seed()
        val expected = ledgerSpend(aug)
        val state = vm().state.awaitState(timeoutMillis = 30_000) {
            !it.initialLoad && it.net.expenses.paisa == expected
        }

        val trend = state.trend!!
        trend.periods.forEachIndexed { i, period ->
            assertEquals("bar for $period", ledgerSpend(period), trend.spend[i])
        }
    }

    @Test
    fun stable_coverage_reconciles_at_scale() = runBlocking {
        // FR-AN-06's numerator is a filtered sum over two tables, which is the
        // shape most likely to quietly drift once there is enough data to hide
        // in.
        seed()
        val expected = ledgerSpend(aug)
        val state = vm().state.awaitState(timeoutMillis = 30_000) {
            !it.initialLoad && it.net.expenses.paisa == expected
        }

        val stable = ledgerStableIncome(aug)
        assertEquals(((stable * 100.0) / expected).toInt(), state.coverage)
    }

    @Test
    fun the_twelve_month_average_reconciles_at_scale() = runBlocking {
        seed()
        val expected = ledgerSpend(aug)
        val state = vm().state.awaitState(timeoutMillis = 30_000) {
            !it.initialLoad && it.net.expenses.paisa == expected
        }

        val trailing = aug.trailing(12)
        val direct = scalar(
            "SELECT IFNULL(SUM(amount_minor), 0) FROM income_entry WHERE status = 0 " +
                "AND period_ym BETWEEN ${trailing.first().ym} AND ${trailing.last().ym}",
        )
        assertEquals(direct / 12, state.averageIncome.paisa)
    }

    // --- a smoke bound, not the measurement -----------------------------------

    @Test
    fun the_dashboard_settles_in_a_time_that_is_not_absurd() = runBlocking {
        // **This is not NFR-PERF-04.** That target is 300 ms of *rendering* on
        // a 1.4 GHz Cortex-A53, and the SRS is explicit that "targets measured
        // on a flagship device are not evidence of compliance" — which goes
        // double for whatever this suite is running on. `DashboardBenchmark` is
        // where the real number comes from.
        //
        // What this catches is the regression that matters between now and
        // then: a read that starts scanning the ledger turns a few milliseconds
        // into seconds, and the bound below is loose enough never to be flaky
        // and tight enough to notice that.
        seed()
        val expected = ledgerSpend(aug)

        val started = System.nanoTime()
        vm().state.awaitState(timeoutMillis = 30_000) {
            !it.initialLoad && it.net.expenses.paisa == expected
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(
            "the dashboard took ${elapsedMs}ms to settle over five years of data",
            elapsedMs < SMOKE_BOUND_MS,
        )
    }

    private companion object {
        /** Thirteen leaves are seeded at install (03 §7). */
        const val LEAF_COUNT = 13

        /** Two orders of magnitude above the target — a scan detector, not a gate. */
        const val SMOKE_BOUND_MS = 5_000L
    }
}
