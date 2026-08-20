package com.app.finance.ui.feature.income

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.awaitState
import com.app.finance.core.money.Money
import com.app.finance.core.time.Period
import com.app.finance.data.db.Schema
import com.app.finance.domain.model.IncomeKind
import com.app.finance.domain.model.SaveOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.time.LocalDate
import java.util.UUID

/**
 * **The M3 exit criterion.** `01-PRD.md` §8: "Yearly totals match manual
 * calculation."
 *
 * Every figure the income screen shows is read from `rollup_income_month`,
 * which is maintained by triggers and never recomputed. The cost of that choice
 * is that the same fact is stored twice, and NFR-REL-02 requires the two copies
 * to agree — FR-IE-04's acceptance criterion says the same thing in the
 * milestone's own words: "each total equals the sum of matching
 * `income_entry.amount_minor` rows, **verified against a manual sum**".
 *
 * So this suite performs the manual sum. For every figure the screen renders —
 * the hero total, each of the twelve bars, every breakdown row and its
 * percentage, and the stable subtotal — the assertion is against
 * `SUM(amount_minor)` taken straight off `income_entry`, never against the
 * rollup read a second way.
 *
 * It runs through [IncomeViewModel] rather than the DAO on purpose. The
 * reconciliation that matters is between what a *user sees* and what they
 * recorded, so every layer that could drop or double a figure — the query, the
 * source-subset filter, the fold, the apportionment — is inside the assertion.
 *
 * The `rollup_income_month` triggers are also the three that were **missing
 * from the published `schema_v1.sql`** and added during M1 (§5 of the log).
 * Without them every figure here reads ৳0 while the ledger underneath is
 * perfectly correct, which is precisely the failure this milestone exists to
 * rule out.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class IncomeReconciliationTest {

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

    private fun vm(period: Period = aug): IncomeViewModel = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                IncomeViewModel(fx.income, fx.clock, period) as T
        },
    )["vm${seq++}", IncomeViewModel::class.java]

    // --- the authority: the ledger, summed directly --------------------------

    private fun ledgerSum(where: String): Long =
        fx.db.openHelper.writableDatabase.query(
            "SELECT IFNULL(SUM(amount_minor), 0) FROM income_entry WHERE status = 0 AND $where",
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun yearSum(year: Int) = ledgerSum("period_ym BETWEEN ${year}01 AND ${year}12")

    private fun periodSum(period: Period) = ledgerSum("period_ym = ${period.ym}")

    private fun sourceYearSum(sourceId: Long, year: Int) =
        ledgerSum("source_id = $sourceId AND period_ym BETWEEN ${year}01 AND ${year}12")

    private fun stableYearSum(year: Int) = fx.db.openHelper.writableDatabase.query(
        """
        SELECT IFNULL(SUM(e.amount_minor), 0)
          FROM income_entry e JOIN income_source s ON s.id = e.source_id
         WHERE e.status = 0 AND s.kind = ${IncomeKind.STABLE.code}
           AND e.period_ym BETWEEN ${year}01 AND ${year}12
        """.trimIndent(),
    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    /**
     * The whole assertion, in one place: every figure the screen would render,
     * checked against a direct sum over the ledger.
     */
    private fun IncomeUiState.reconcilesAgainstTheLedger(year: Int) {
        assertEquals("hero total", yearSum(year), summary.total.paisa)
        assertEquals("stable subtotal", stableYearSum(year), summary.stableTotal.paisa)

        shares.forEach { share ->
            assertEquals(
                "breakdown row for ${share.name}",
                sourceYearSum(share.sourceId, year),
                share.total.paisa,
            )
        }
        assertEquals(
            "the breakdown accounts for the whole total",
            summary.total.paisa,
            shares.sumOf { it.total.paisa },
        )

        trendPeriods.forEachIndexed { i, period ->
            assertEquals("bar for $period", periodSum(period), summary.trend[i])
        }
        assertEquals(
            "the twelve bars account for the year",
            yearSum(year),
            summary.trend.sum(),
        )

        if (summary.total.paisa > 0L) {
            assertEquals(
                "FR-IE-06 — the percentages sum to exactly 100",
                100,
                shares.sumOf { it.share },
            )
        }
    }

    private val IncomeUiState.shares get() = summary.shares

    private suspend fun sourceId(name: String): Long =
        fx.db.incomeDao().observeAllSources().first().first { it.name == name }.id

    private fun seed(taka: Long, source: String, month: Int, day: Int, year: Int = 2026) =
        runBlocking {
            fx.income.saveEntry(
                amount = Money.ofTaka(taka),
                sourceName = source,
                earnedOn = LocalDate.of(year, month, day),
            ) as SaveOutcome.Saved
        }

    private fun settled(vm: IncomeViewModel, expected: Long) = runBlocking {
        vm.state.awaitState { !it.initialLoad && it.summary.total.paisa == expected }
    }

    // --- scenarios ------------------------------------------------------------

    @Test
    fun ordinary_income_across_a_year_reconciles() = runBlocking {
        // FR-IE-02's shape: several entries for one source in one month, and a
        // source with entries in most months of the year.
        (1..11).forEach { seed(30_000, "Salary", it, 1) }
        seed(50_000, "Farming", 6, 4)
        seed(30_000, "Farming", 6, 19)
        seed(144_000, "Real estate", 9, 2)

        settled(vm(), yearSum(2026)).reconcilesAgainstTheLedger(2026)
    }

    @Test
    fun a_month_scope_reconciles_against_that_month_alone() = runBlocking {
        seed(30_000, "Salary", 8, 1)
        seed(80_000, "Farming", 6, 4)

        val vm = vm()
        settled(vm, yearSum(2026))
        vm.setScopeKind(ScopeKind.MONTH)

        val state = vm.state.awaitState {
            !it.initialLoad && it.summary.total.paisa == periodSum(aug)
        }
        assertEquals(periodSum(aug), state.summary.total.paisa)
        // And the twelve bars are the trailing twelve, each still exact.
        state.trendPeriods.forEachIndexed { i, period ->
            assertEquals(periodSum(period), state.summary.trend[i])
        }
    }

    @Test
    fun an_edit_across_a_year_boundary_leaves_both_years_reconciled() = runBlocking {
        // The trigger decrements the old bucket and increments the new one. If
        // it ever stopped doing that, 2025 would keep money it no longer holds
        // and 2026 would be short — and nothing else in the app would notice.
        val saved = seed(40_000, "Property", 12, 20, year = 2025)
        seed(30_000, "Salary", 3, 1)

        fx.income.updateEntry(
            saved.id,
            Money.ofTaka(40_000),
            "Property",
            LocalDate.of(2026, 1, 8),
            null,
        )

        settled(vm(), yearSum(2026)).reconcilesAgainstTheLedger(2026)
        settled(vm(Period(202506)), yearSum(2025)).reconcilesAgainstTheLedger(2025)
    }

    @Test
    fun a_deletion_reconciles() = runBlocking {
        seed(30_000, "Salary", 8, 1)
        val doomed = seed(80_000, "Farming", 6, 4)
        seed(20_000, "Farming", 7, 9)

        fx.income.deleteEntry(doomed.id)

        settled(vm(), yearSum(2026)).reconcilesAgainstTheLedger(2026)
    }

    @Test
    fun a_delete_then_undo_reconciles() = runBlocking {
        seed(30_000, "Salary", 8, 1)
        val doomed = seed(80_000, "Farming", 6, 4)

        val row = fx.income.deleteEntry(doomed.id)!!
        fx.income.restoreEntry(row)

        settled(vm(), yearSum(2026)).reconcilesAgainstTheLedger(2026)
        assertEquals(110_000_00L, yearSum(2026))
    }

    @Test
    fun a_pending_entry_appears_in_neither() = runBlocking {
        // status = 1 is excluded from the rollup triggers *and* from the direct
        // sum this test takes, so the assertion only holds if both agree. An
        // unconfirmed recurring entry must never inflate a year's income.
        seed(30_000, "Salary", 8, 1)
        val salary = sourceId("Salary")

        fx.db.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO income_entry
                (uuid, source_id, amount_minor, earned_on, period_ym, status, created_at, updated_at)
            VALUES ('${UUID.randomUUID()}', $salary, 9999900,
                    ${LocalDate.of(2026, 9, 1).toEpochDay()}, 202609, 1, 0, 0)
            """.trimIndent(),
        )

        val state = settled(vm(), yearSum(2026))
        state.reconcilesAgainstTheLedger(2026)
        assertEquals("the pending row is nowhere in the total", 30_000_00L, state.summary.total.paisa)
    }

    @Test
    fun an_archived_source_still_reconciles_and_still_shows() = runBlocking {
        // FR-IS-04 — "MUST remain visible in historical reports". A breakdown
        // that dropped the row would under-report the year while every entry
        // was still in the ledger, which is the exact failure NFR-REL-02 names.
        seed(30_000, "Salary", 8, 1)
        seed(80_000, "Farming", 6, 4)
        val farming = sourceId("Farming")
        fx.income.setSourceArchived(farming, archived = true)

        val state = settled(vm(), yearSum(2026))
        state.reconcilesAgainstTheLedger(2026)
        assertTrue(state.summary.shares.any { it.name == "Farming" })
    }

    @Test
    fun a_source_subset_reconciles_against_that_subset() = runBlocking {
        // FR-IE-05 combined with FR-IE-06: filtering must narrow the total, the
        // bars and the breakdown by the same predicate, and the percentages
        // must still sum to 100 over what is left.
        seed(30_000, "Salary", 8, 1)
        seed(80_000, "Farming", 6, 4)
        seed(15_000, "Property", 7, 9)

        val vm = vm()
        settled(vm, yearSum(2026))
        val farming = sourceId("Farming")
        vm.setSources(setOf(farming))

        val state = vm.state.awaitState {
            !it.initialLoad && it.summary.shares.size == 1
        }
        assertEquals(sourceYearSum(farming, 2026), state.summary.total.paisa)
        assertEquals(sourceYearSum(farming, 2026), state.summary.trend.sum())
        assertEquals(100, state.summary.shares.single().share)
    }

    @Test
    fun a_custom_range_reconciles_against_the_ledger_for_those_days() = runBlocking {
        // 03 §5.3's ledger fallback. The rollup cannot answer this window, so
        // this is the one figure on the screen computed a different way — and
        // it has to agree with the same authority as all the others.
        seed(50_000, "Farming", 6, 4)
        seed(30_000, "Farming", 6, 19)
        seed(20_000, "Salary", 8, 1)

        val vm = vm()
        settled(vm, yearSum(2026))
        vm.setRange(LocalDate.of(2026, 6, 15), LocalDate.of(2026, 8, 10))

        val expected = ledgerSum(
            "earned_on BETWEEN ${LocalDate.of(2026, 6, 15).toEpochDay()} " +
                "AND ${LocalDate.of(2026, 8, 10).toEpochDay()}",
        )
        val state = vm.state.awaitState { !it.initialLoad && it.summary.total.paisa == expected }
        assertEquals(expected, state.summary.total.paisa)
        assertEquals(50_000_00L, expected)
    }

    @Test
    fun a_rebuild_reproduces_exactly_what_the_triggers_maintained() = runBlocking {
        // 03 §6's repair path, and the invariant the whole aggregate strategy
        // rests on: truncating and regenerating from the ledger must land on
        // the state the triggers had already produced. If the two ever differ,
        // one of them is wrong and every figure in the app is suspect.
        (1..12).forEach { seed(10_000 + it * 100L, "Salary", it, 1) }
        seed(80_000, "Farming", 6, 4)
        val doomed = seed(5_000, "Property", 3, 3)
        fx.income.deleteEntry(doomed.id)
        fx.income.updateEntry(
            seed(1_000, "Tuition", 4, 4).id,
            Money.ofTaka(2_500),
            "Tuition",
            LocalDate.of(2026, 5, 5),
            null,
        )

        val before = settled(vm(), yearSum(2026))

        val db = fx.db.openHelper.writableDatabase
        Schema.REBUILD_ROLLUPS.forEach(db::execSQL)

        val after = settled(vm(), yearSum(2026))
        assertEquals(before.summary.total, after.summary.total)
        assertEquals(before.summary.stableTotal, after.summary.stableTotal)
        assertEquals(
            before.summary.shares.map { it.name to it.total },
            after.summary.shares.map { it.name to it.total },
        )
        assertTrue(before.summary.trend.contentEquals(after.summary.trend))
        after.reconcilesAgainstTheLedger(2026)
    }
}
