package com.app.finance.ui.feature.income

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.awaitState
import com.app.finance.core.money.Money
import com.app.finance.core.time.Period
import com.app.finance.domain.model.EntryError
import com.app.finance.domain.model.IncomeKind
import com.app.finance.domain.model.SaveOutcome
import com.app.finance.ui.common.KeypadKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The income screen's state machine — scope, filters, and the entry sheet.
 *
 * Predicates inside [awaitState] are written null-safely and never with
 * `first {}`: the initial emission is empty, and a predicate that throws there
 * fails the wait rather than returning false.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class IncomeViewModelTest {

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

    private fun seed(taka: Long, source: String, month: Int, day: Int, year: Int = 2026) =
        runBlocking {
            fx.income.saveEntry(
                Money.ofTaka(taka),
                source,
                LocalDate.of(year, month, day),
            ) as SaveOutcome.Saved
        }

    private suspend fun sourceId(name: String): Long =
        fx.db.incomeDao().observeAllSources().first().first { it.name == name }.id

    private fun IncomeUiState.share(name: String) =
        summary.shares.firstOrNull { it.name == name }

    private fun IncomeUiState.totalTaka() = summary.total.paisa / 100

    // --- the default scope ---------------------------------------------------

    @Test
    fun the_screen_opens_on_the_year() = runBlocking {
        // 05 §5.7 — the single most important accommodation on this screen, and
        // the one thing a refactor is most likely to "fix" into consistency.
        seed(30_000, "Salary", 8, 1)
        seed(80_000, "Farming", 2, 4)

        val state = vm().state.awaitState { !it.initialLoad }
        assertEquals(ScopeKind.YEAR, state.scopeKind)
        assertEquals(110_000L, state.totalTaka())
    }

    @Test
    fun switching_to_the_month_narrows_every_figure() = runBlocking {
        seed(30_000, "Salary", 8, 1)
        seed(80_000, "Farming", 2, 4)

        val vm = vm()
        vm.state.awaitState { !it.initialLoad && it.totalTaka() == 110_000L }
        vm.setScopeKind(ScopeKind.MONTH)

        val state = vm.state.awaitState { !it.initialLoad && it.totalTaka() == 30_000L }
        assertEquals(1, state.summary.shares.size)
        assertEquals("Salary", state.summary.shares.single().name)
    }

    @Test
    fun a_new_period_from_above_re_reads_the_year() = runBlocking {
        // The period is hoisted above the NavHost; stepping the year on this
        // screen writes back there and comes down as a new period.
        seed(30_000, "Salary", 8, 1, year = 2026)
        seed(90_000, "Salary", 3, 1, year = 2025)

        val vm = vm()
        vm.state.awaitState { !it.initialLoad && it.totalTaka() == 30_000L }

        vm.setPeriod(Period(202508))
        val state = vm.state.awaitState { !it.initialLoad && it.totalTaka() == 90_000L }
        assertEquals(2025, state.period.year)
    }

    @Test
    fun a_custom_range_is_neither_the_year_nor_the_month() = runBlocking {
        seed(50_000, "Farming", 6, 4)
        seed(30_000, "Farming", 6, 19)
        seed(20_000, "Salary", 8, 1)

        val vm = vm()
        vm.state.awaitState { !it.initialLoad && it.totalTaka() == 100_000L }

        vm.setRange(LocalDate.of(2026, 6, 15), LocalDate.of(2026, 8, 10))
        val state = vm.state.awaitState { !it.initialLoad && it.totalTaka() == 50_000L }
        assertEquals(ScopeKind.RANGE, state.scopeKind)
    }

    @Test
    fun a_reversed_range_is_taken_the_way_round_it_was_meant() = runBlocking {
        // The two ends are set independently, so "to before from" is a state
        // the user passes through rather than one they chose.
        seed(50_000, "Farming", 6, 4)

        val vm = vm()
        vm.state.awaitState { !it.initialLoad }
        vm.setRange(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 6, 1))

        val state = vm.state.awaitState { it.scopeKind == ScopeKind.RANGE && !it.initialLoad }
        assertEquals(LocalDate.of(2026, 6, 1), state.rangeFrom)
        assertEquals(LocalDate.of(2026, 8, 10), state.rangeTo)
        assertEquals(50_000L, state.totalTaka())
    }

    // --- FR-IE-05 -------------------------------------------------------------

    @Test
    fun a_source_subset_filters_the_whole_screen() = runBlocking {
        seed(30_000, "Salary", 8, 1)
        seed(80_000, "Farming", 6, 4)

        val vm = vm()
        vm.state.awaitState { !it.initialLoad && it.totalTaka() == 110_000L }
        vm.setSources(setOf(sourceId("Farming")))

        val state = vm.state.awaitState { !it.initialLoad && it.totalTaka() == 80_000L }
        assertNull(state.share("Salary"))
        assertEquals(1, state.entries.size)
        assertEquals(1, state.activeFilterCount)
    }

    @Test
    fun tapping_a_source_twice_clears_the_filter_again() = runBlocking {
        // The breakdown row is a toggle, not a one-way trip — otherwise the
        // only way back is the filter sheet the user never opened.
        seed(30_000, "Salary", 8, 1)
        seed(80_000, "Farming", 6, 4)
        val farming = sourceId("Farming")

        val vm = vm()
        vm.state.awaitState { !it.initialLoad && it.totalTaka() == 110_000L }

        vm.toggleSource(farming)
        vm.state.awaitState { !it.initialLoad && it.totalTaka() == 80_000L }

        vm.toggleSource(farming)
        val state = vm.state.awaitState { !it.initialLoad && it.totalTaka() == 110_000L }
        assertTrue(state.sourceIds.isEmpty())
    }

    @Test
    fun clearing_filters_returns_to_the_default_year() = runBlocking {
        seed(30_000, "Salary", 8, 1)

        val vm = vm()
        vm.state.awaitState { !it.initialLoad }
        vm.setScopeKind(ScopeKind.MONTH)
        vm.setSources(setOf(sourceId("Salary")))
        vm.state.awaitState { it.scopeKind == ScopeKind.MONTH && it.sourceIds.isNotEmpty() }

        vm.clearFilters()
        val state = vm.state.awaitState { !it.initialLoad && it.scopeKind == ScopeKind.YEAR }
        assertTrue(state.sourceIds.isEmpty())
        assertEquals(0, state.activeFilterCount)
    }

    // --- the entry sheet ------------------------------------------------------

    @Test
    fun the_save_button_stays_disabled_without_an_amount_and_a_source() = runBlocking {
        val vm = vm()
        vm.state.awaitState { !it.initialLoad }

        vm.addEntry()
        assertFalse(vm.state.value.editor!!.canSave)

        vm.onKey(KeypadKey.Digit('5'))
        vm.onKey(KeypadKey.Digit('0'))
        assertFalse("an amount alone is not enough", vm.state.value.editor!!.canSave)

        vm.setSourceName("Poultry")
        assertTrue(vm.state.value.editor!!.canSave)
    }

    @Test
    fun the_negate_key_does_nothing_here() = runBlocking {
        // Income has no refund case — the column's CHECK is amount_minor > 0.
        // The key stays on the pad because it is shared with expense entry.
        val vm = vm()
        vm.state.awaitState { !it.initialLoad }
        vm.addEntry()
        vm.onKey(KeypadKey.Digit('7'))
        vm.onKey(KeypadKey.Negate)
        assertEquals(Money.ofTaka(7), vm.state.value.editor!!.amount)
    }

    @Test
    fun a_future_date_is_refused_rather_than_stored() = runBlocking {
        // It would post straight into the period rollup and inflate income that
        // has not arrived. The same clamp the expense sheet applies.
        val vm = vm()
        vm.state.awaitState { !it.initialLoad }
        vm.addEntry()
        vm.setDate(fx.today.plusDays(1))

        val editor = vm.state.value.editor!!
        assertEquals(EntryError.FUTURE_DATE, editor.error)
        assertEquals(fx.today, editor.date)
    }

    @Test
    fun saving_an_entry_moves_the_figures_without_a_refresh() = runBlocking {
        // Room's invalidation tracker is the whole mechanism — no event bus, no
        // manual reload (04 §5.1).
        val vm = vm()
        vm.state.awaitState { !it.initialLoad }

        vm.addEntry()
        vm.setSourceName("Poultry")
        listOf('1', '2', '0', '0', '0').forEach { vm.onKey(KeypadKey.Digit(it)) }
        vm.saveEntry {}

        val state = vm.state.awaitState { it.totalTaka() == 12_000L }
        assertNull("the sheet closes on success", state.editor)
        assertEquals(12_000L, state.share("Poultry")?.total?.paisa?.div(100))
    }

    @Test
    fun editing_an_entry_pre_fills_the_sheet() = runBlocking {
        seed(30_000, "Salary", 8, 1)

        val vm = vm()
        val state = vm.state.awaitState { !it.initialLoad && it.entries.isNotEmpty() }
        vm.editEntry(state.entries.first())

        val editor = vm.state.value.editor!!
        assertTrue(editor.isEditing)
        assertEquals("30000", editor.input)
        assertEquals("Salary", editor.sourceName)
        assertEquals(LocalDate.of(2026, 8, 1), editor.date)
    }

    @Test
    fun deleting_an_entry_can_be_undone() = runBlocking {
        // NFR-USE-03. The row is held so the snackbar can put it back.
        seed(80_000, "Farming", 6, 4)

        val vm = vm()
        val state = vm.state.awaitState { !it.initialLoad && it.totalTaka() == 80_000L }
        vm.deleteEntry(state.entries.first().entry.id)

        // Both halves, not just the total. The figure arrives on the rollup flow
        // and the queued row is appended by the delete coroutine; awaiting only
        // the first can observe a state where the second has not landed. It
        // failed that way under JaCoCo's slower timing — see §21.9 J for the
        // same shape in the category suite.
        val afterDelete = vm.state.awaitState { it.totalTaka() == 0L && it.undoQueue.isNotEmpty() }

        vm.undo(afterDelete.undoQueue.single().id)
        assertEquals(80_000L, vm.state.awaitState { it.totalTaka() == 80_000L }.totalTaka())
    }

    @Test
    fun two_deletions_inside_the_window_are_both_still_undoable() = runBlocking {
        // The single slot here was overwritten exactly as the ledger's was, and
        // the entry it held is the only copy left once the delete has happened.
        seed(80_000, "Farming", 6, 4)
        seed(20_000, "Farming", 6, 5)

        val vm = vm()
        vm.state.awaitState { !it.initialLoad && it.totalTaka() == 100_000L }
            .entries.take(2).forEach { vm.deleteEntry(it.entry.id) }

        val queue = vm.state.awaitState { it.undoQueue.size == 2 && it.totalTaka() == 0L }.undoQueue
        queue.forEach { vm.undo(it.id) }

        assertEquals(100_000L, vm.state.awaitState { it.totalTaka() == 100_000L }.totalTaka())
    }

    @Test
    fun a_zero_amount_is_reported_on_the_field_rather_than_thrown() = runBlocking {
        val vm = vm()
        vm.state.awaitState { !it.initialLoad }
        vm.addEntry()
        vm.setSourceName("Poultry")
        vm.onKey(KeypadKey.Digit('0'))
        vm.saveEntry {}

        assertEquals(EntryError.NON_POSITIVE_INCOME, vm.state.value.editor?.error)
    }

    // --- coverage -------------------------------------------------------------

    @Test
    fun the_coverage_line_is_absent_when_nothing_was_spent() = runBlocking {
        // Not zero — absent. A ratio with no denominator is not a number, and
        // 05 §5.4 says sections with nothing to say are absent, not empty.
        seed(30_000, "Salary", 8, 1)
        val state = vm().state.awaitState { !it.initialLoad && it.totalTaka() == 30_000L }
        assertNull(state.coverage)
    }

    @Test
    fun the_coverage_line_appears_once_there_is_spending_to_cover() = runBlocking {
        fx.income.createSource("Wages", IncomeKind.STABLE)
        fx.income.saveEntry(Money.ofTaka(40_000), "Wages", LocalDate.of(2026, 8, 1))
        fx.expenses.insert(Money.ofTaka(80_000), fx.leafId("Grocery"), LocalDate.of(2026, 8, 2))

        val state = vm().state.awaitState { !it.initialLoad && it.coverage != null }
        assertEquals(50, state.coverage)
    }

    @Test
    fun an_empty_year_reports_itself_empty() = runBlocking {
        val state = vm().state.awaitState { !it.initialLoad }
        assertTrue(state.isEmpty)
        assertEquals(0L, state.totalTaka())
    }

    // --- the trend reads twelve months, whatever the scope (D1) --------------

    @Test
    fun a_month_scope_still_draws_twelve_months_of_bars() = runBlocking {
        // FR-IE-07 — "a 12-month income trend ending at the currently selected
        // period". The window is one month and the chart is twelve, so they
        // cannot be the same read. They were, and eleven bars were structurally
        // zero: a chart telling a farmer they earned nothing all year, on the
        // one screen built around the shape of the year being the information.
        seed(30_000, "Salary", 8, 1)
        seed(80_000, "Farming", 2, 4)
        seed(50_000, "Farming", 6, 19)

        val vm = vm()
        vm.state.awaitState { !it.initialLoad }
        vm.setScopeKind(ScopeKind.MONTH)

        val state = vm.state.awaitState {
            !it.initialLoad && it.scopeKind == ScopeKind.MONTH && it.totalTaka() == 30_000L
        }
        assertEquals("the hero total is August alone", 30_000L, state.totalTaka())

        val bars = state.trendPeriods.zip(state.summary.trend.toList()).toMap()
        assertEquals(80_000_00L, bars[Period(202602)])
        assertEquals(50_000_00L, bars[Period(202606)])
        assertEquals(30_000_00L, bars[Period(202608)])
        assertEquals("and they sum to the trailing twelve", 160_000_00L, state.summary.trend.sum())
    }

    @Test
    fun a_range_scope_draws_whole_months_around_the_range() = runBlocking {
        // The range's own total comes from the ledger (03 §5.3); the bars are
        // months either way, so they stay on the rollup and show the context
        // the range sits in rather than eleven blanks.
        seed(50_000, "Farming", 6, 4)
        seed(30_000, "Farming", 6, 19)
        seed(20_000, "Salary", 8, 1)

        val vm = vm()
        vm.state.awaitState { !it.initialLoad }
        vm.setRange(LocalDate.of(2026, 6, 15), LocalDate.of(2026, 8, 10))

        val state = vm.state.awaitState {
            !it.initialLoad && it.scopeKind == ScopeKind.RANGE && it.totalTaka() == 50_000L
        }
        assertEquals("the total is the range's days", 50_000L, state.totalTaka())

        val bars = state.trendPeriods.zip(state.summary.trend.toList()).toMap()
        assertEquals("June's bar is the whole month, not the sliver in range", 80_000_00L, bars[Period(202606)])
        assertEquals(20_000_00L, bars[Period(202608)])
    }

    // --- an emptied bucket is not a source (D2) ------------------------------

    @Test
    fun deleting_a_sources_last_entry_removes_it_from_the_breakdown() = runBlocking {
        // The delete trigger zeroes the rollup bucket rather than removing it,
        // so without a filter on the read the source hangs around as a
        // permanent "৳0 · 0%" row that nothing can ever clear.
        seed(30_000, "Salary", 8, 1)
        val doomed = seed(5_000, "Consulting", 3, 3)

        val vm = vm()
        vm.state.awaitState { !it.initialLoad && it.share("Consulting") != null }

        fx.income.deleteEntry(doomed.id)

        val state = vm.state.awaitState { it.share("Consulting") == null }
        assertNull(state.share("Consulting"))
        assertEquals(30_000L, state.totalTaka())
    }

    @Test
    fun deleting_the_last_entry_of_the_year_returns_the_empty_state() = runBlocking {
        // `isEmpty` is "no breakdown rows", so a residual zero row kept the
        // screen out of its empty state forever: a hero total of ৳0 above a
        // list of ৳0 sources, which is not an empty state, it is a wrong one.
        val only = seed(30_000, "Salary", 8, 1)

        val vm = vm()
        vm.state.awaitState { !it.initialLoad && !it.isEmpty }

        fx.income.deleteEntry(only.id)

        val state = vm.state.awaitState { it.isEmpty }
        assertTrue(state.isEmpty)
        assertTrue(state.summary.shares.isEmpty())
    }

    // --- 05 §9's zero-income month (D3) --------------------------------------

    @Test
    fun an_empty_month_inside_a_year_with_income_reframes_to_the_year() = runBlocking {
        // 05 §9 — "Nothing recorded in August. Your year is at ৳5,84,000". A
        // farming month at ৳0 is the case this screen exists for; reporting it
        // with the generic first-run invitation renders it as a failure.
        seed(80_000, "Farming", 2, 4)

        val vm = vm()
        vm.state.awaitState { !it.initialLoad }
        vm.setScopeKind(ScopeKind.MONTH)

        val state = vm.state.awaitState { it.scopeKind == ScopeKind.MONTH && it.isEmpty }
        assertTrue(state.showsEmptyMonthReframe)
        assertEquals(Money.ofTaka(80_000), state.yearTotal)
    }

    @Test
    fun an_empty_month_in_an_empty_year_keeps_the_invitation() = runBlocking {
        // There is nothing to reframe to. "Your year is at ৳0" would be a
        // worse sentence than the invitation it replaced.
        val vm = vm()
        vm.state.awaitState { !it.initialLoad }
        vm.setScopeKind(ScopeKind.MONTH)

        val state = vm.state.awaitState { it.scopeKind == ScopeKind.MONTH && it.isEmpty }
        assertFalse(state.showsEmptyMonthReframe)
    }

    @Test
    fun the_year_figure_is_not_read_outside_month_scope() = runBlocking {
        // It has no caller there, and a flow nobody reads is a Room
        // invalidation subscription nobody needs.
        seed(80_000, "Farming", 2, 4)
        val state = vm().state.awaitState { !it.initialLoad }
        assertEquals(ScopeKind.YEAR, state.scopeKind)
        assertEquals(Money.ZERO, state.yearTotal)
    }

    // --- the range arrows (D4) -----------------------------------------------

    @Test
    fun stepping_back_in_range_scope_shifts_the_range_by_its_own_span() = runBlocking {
        val vm = vm()
        vm.state.awaitState { !it.initialLoad }
        // 15 June to 10 August inclusive is 57 days.
        vm.setRange(LocalDate.of(2026, 6, 15), LocalDate.of(2026, 8, 10))
        vm.state.awaitState { it.scopeKind == ScopeKind.RANGE }

        vm.stepRange(forward = false)

        val state = vm.state.awaitState { it.rangeTo == LocalDate.of(2026, 6, 14) }
        assertEquals(LocalDate.of(2026, 4, 19), state.rangeFrom)
        assertEquals(LocalDate.of(2026, 6, 14), state.rangeTo)
        assertEquals("and the shared period is untouched", aug, state.period)
    }

    @Test
    fun stepping_forward_in_range_scope_stops_at_today() = runBlocking {
        // The range picker refuses a future date, so the arrows must not
        // produce a window it would have rejected. The fixture's clock is
        // pinned, which is the only reason this is assertable at all.
        val vm = vm()
        vm.state.awaitState { !it.initialLoad }
        val today = vm.state.value.today
        vm.setRange(today.minusDays(9), today.minusDays(5))
        vm.state.awaitState { it.scopeKind == ScopeKind.RANGE }

        vm.stepRange(forward = true)

        val state = vm.state.awaitState { it.rangeTo == today }
        assertEquals(today, state.rangeTo)
        assertEquals("the span is preserved", today.minusDays(4), state.rangeFrom)
    }

    @Test
    fun the_arrows_do_nothing_to_the_range_in_the_other_scopes() = runBlocking {
        val vm = vm()
        val before = vm.state.awaitState { !it.initialLoad }
        vm.stepRange(forward = false)
        assertNull(vm.state.value.rangeFrom)
        assertEquals(before.period, vm.state.value.period)
    }

    // --- what the filter sheet can offer (D5) --------------------------------

    @Test
    fun an_archived_source_with_history_stays_filterable() = runBlocking {
        // FR-IS-04 keeps archived sources out of *entry pickers*; a filter is
        // not one, and the same requirement puts them squarely in historical
        // reports. FR-IE-05's own example is {Salary, Farming} across a year,
        // which becomes unperformable the moment Farming is archived.
        seed(30_000, "Salary", 8, 1)
        seed(80_000, "Farming", 6, 4)
        val farming = sourceId("Farming")
        fx.income.setSourceArchived(farming, archived = true)

        val vm = vm()
        val state = vm.state.awaitState {
            !it.initialLoad && it.sources.none { s -> s.name == "Farming" }
        }
        assertFalse("out of the entry picker", state.sources.any { it.name == "Farming" })
        assertTrue("but still in the filter", state.filterSources.any { it.name == "Farming" })
    }

    @Test
    fun filtering_to_one_source_does_not_remove_the_others_from_the_filter() = runBlocking {
        // The trap in deriving the chip list from the breakdown: narrowing to
        // one source empties the breakdown of every other, and the control that
        // would widen it again goes with them.
        seed(30_000, "Salary", 8, 1)
        seed(80_000, "Farming", 6, 4)
        val farming = sourceId("Farming")
        fx.income.setSourceArchived(farming, archived = true)

        val vm = vm()
        vm.state.awaitState { !it.initialLoad && it.filterSources.size == 2 }
        vm.setSources(setOf(farming))

        val state = vm.state.awaitState { it.sourceIds.isNotEmpty() && it.summary.shares.size == 1 }
        assertEquals(listOf("Farming"), state.summary.shares.map { it.name })
        assertTrue(
            "Salary is still offered, or there is no way back",
            state.filterSources.any { it.name == "Salary" },
        )
    }
}
