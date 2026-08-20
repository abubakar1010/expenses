package com.app.finance.ui.feature.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.awaitState
import com.app.finance.core.money.Money
import com.app.finance.core.time.Period
import com.app.finance.domain.model.BudgetState
import com.app.finance.domain.model.EntryError
import com.app.finance.domain.model.Nature
import com.app.finance.ui.common.KeypadKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class BudgetViewModelTest {

    private lateinit var fx: TestFixture
    private val store = ViewModelStore()
    private var seq = 0

    private val aug = Period(202608)
    private val jul = Period(202607)

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

    private fun vm(period: Period = aug): BudgetViewModel = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                BudgetViewModel(fx.budgets, fx.categories, fx.clock, period) as T
        },
    )["vm${seq++}", BudgetViewModel::class.java]

    private suspend fun spend(taka: Long, category: String, day: Int = 3, period: Period = aug) =
        fx.expenses.insert(
            amount = Money.ofTaka(taka),
            categoryId = fx.leafId(category),
            spentOn = LocalDate.of(period.ym / 100, period.ym % 100, day),
        )

    private suspend fun limit(taka: Long, category: String, period: Period = aug) =
        fx.budgets.setLimit(fx.leafId(category), period, Money.ofTaka(taka))

    /**
     * Null-safe on purpose: these run inside `awaitState` predicates, which are
     * evaluated against the *initial* state first — where `groups` is empty
     * because the query has not answered yet. A throwing lookup there fails the
     * test with "no element matching the predicate" instead of simply waiting.
     */
    private fun BudgetUiState.leafOrNull(name: String) =
        groups.flatMap { it.leaves }.firstOrNull { it.name == name }

    private fun BudgetUiState.leaf(name: String) =
        requireNotNull(leafOrNull(name)) { "no leaf named '$name' in ${groups.map { it.name }}" }

    // Written for `awaitState` predicates: absent leaf means "not yet", never
    // an exception.
    private fun BudgetUiState.spentPaisa(name: String) = leafOrNull(name)?.status?.spent?.paisa
    private fun BudgetUiState.hasLimit(name: String) = leafOrNull(name)?.hasLimit
    private fun BudgetUiState.stateOf(name: String) = leafOrNull(name)?.status?.state

    // --- structure ----------------------------------------------------------

    @Test
    fun groups_are_ordered_variable_then_unpredictable_then_fixed() = runBlocking {
        // 05 §5.4 — "Fixed expenses sit below variable ones, despite being
        // larger, because rent is not a decision."
        val natures = vm().state.awaitState { !it.initialLoad }.groups.map { it.nature }
        assertEquals(
            listOf(Nature.VARIABLE, Nature.UNPREDICTABLE, Nature.FIXED),
            natures,
        )
    }

    @Test
    fun a_root_total_is_the_sum_of_its_children_and_is_never_stored() = runBlocking {
        // FR-BUD-03. There is no setLimit overload taking a root, so the only
        // way this can be wrong is the fold itself.
        limit(8_000, "Grocery")
        limit(3_000, "Transport")
        spend(1_200, "Grocery")
        spend(400, "Transport")

        val variable = vm().state
            .awaitState { it.groups.any { g -> g.limit.paisa > 0L } }
            .groups.first { it.nature == Nature.VARIABLE }

        assertEquals(Money.ofTaka(11_000), variable.limit)
        assertEquals(Money.ofTaka(1_600), variable.spent)
        assertEquals(0, fx.db.budgetDao().forPeriod(aug.ym).count { it.categoryId == variable.id })
    }

    @Test
    fun the_screen_shows_categories_even_when_no_limit_is_set_anywhere() = runBlocking {
        // The first-run state. An "empty" screen here would hide the thirteen
        // rows that each carry the action for fixing it.
        val state = vm().state.awaitState { !it.initialLoad }
        assertFalse("with categories present there is nothing empty about it", state.isEmpty)
        assertEquals(13, state.groups.sumOf { it.leaves.size })
        assertTrue(state.groups.all { it.isUnbudgeted })
    }

    @Test
    fun the_initial_load_flag_clears_so_the_skeleton_is_replaced() = runBlocking {
        assertFalse(vm().state.awaitState { !it.initialLoad }.initialLoad)
    }

    // --- FR-BUD-05, FR-BUD-06 ----------------------------------------------

    @Test
    fun a_leaf_moves_through_under_near_and_over_as_spending_accumulates() = runBlocking {
        // FR-BUD-06's thresholds, driven through the real write path rather
        // than constructed in memory.
        limit(1_000, "Grocery")
        val vm = vm()

        spend(500, "Grocery", day = 1)
        assertEquals(
            BudgetState.UNDER,
            vm.state.awaitState { it.spentPaisa("Grocery") == 50_000L }
                .leaf("Grocery").status.state,
        )

        spend(300, "Grocery", day = 2) // 800 / 1000 = exactly 80%
        assertEquals(
            BudgetState.NEAR,
            vm.state.awaitState { it.spentPaisa("Grocery") == 80_000L }
                .leaf("Grocery").status.state,
        )

        spend(200, "Grocery", day = 3) // exactly 100%
        val over = vm.state.awaitState { it.spentPaisa("Grocery") == 100_000L }
        assertEquals(BudgetState.OVER, over.leaf("Grocery").status.state)
        assertEquals(100, over.leaf("Grocery").status.percentConsumed)
    }

    @Test
    fun a_bar_moves_without_anyone_asking_for_a_refresh() = runBlocking {
        // 04 §5.1 — Room's invalidation tracker is the whole mechanism. Saving
        // an expense from the FAB on another screen must move this one.
        limit(2_000, "Transport")
        val vm = vm()
        assertEquals(
            Money.ZERO,
            vm.state.awaitState { !it.initialLoad }.leaf("Transport").status.spent,
        )

        spend(750, "Transport")

        assertEquals(
            Money.ofTaka(750),
            vm.state.awaitState { it.spentPaisa("Transport") == 75_000L }
                .leaf("Transport").status.spent,
        )
    }

    // --- alerts -------------------------------------------------------------

    @Test
    fun the_needs_attention_block_lists_over_budget_first() = runBlocking {
        limit(1_000, "Grocery")
        limit(1_000, "Transport")
        spend(1_400, "Grocery")   // over
        spend(850, "Transport")   // near

        val alerts = vm().state.awaitState { it.alerts.size == 2 }.alerts
        assertEquals(listOf("Grocery", "Transport"), alerts.map { it.name })
        assertTrue(alerts.first().isOver)
        assertEquals(Money.ofTaka(400), alerts.first().overspend)
        assertEquals(Money.ofTaka(150), alerts[1].remaining)
    }

    @Test
    fun an_unbudgeted_leaf_with_spending_never_becomes_an_alert() = runBlocking {
        // With no limit there is no threshold to cross. The row still renders
        // below — it just is not a warning.
        spend(9_999, "Dining Out")
        val state = vm().state.awaitState { (it.spentPaisa("Dining Out") ?: 0L) > 0L }
        assertTrue(state.alerts.isEmpty())
        assertFalse(state.leaf("Dining Out").hasLimit)
    }

    @Test
    fun an_unpredictable_leaf_alerts_when_over_but_never_when_merely_approaching() = runBlocking {
        // FR-BUD-07 — "MUST NOT produce under-spend nagging". Approaching a
        // buffer is not a problem; exceeding a limit you set still is.
        limit(1_000, "Medical")
        limit(1_000, "Repairs")
        spend(850, "Medical")    // near — suppressed
        spend(1_200, "Repairs")  // over — reported

        val state = vm().state.awaitState { it.stateOf("Repairs") == BudgetState.OVER }
        assertEquals(listOf("Repairs"), state.alerts.map { it.name })
        assertTrue(state.leaf("Medical").isUnplanned)
        assertEquals(BudgetState.NEAR, state.leaf("Medical").status.state)
    }

    @Test
    fun alerts_carry_the_days_left_in_the_period() = runBlocking {
        // ৳900 with six days to go is a different situation from ৳900 with one,
        // so the copy needs both figures.
        limit(1_000, "Grocery")
        spend(900, "Grocery")
        val state = vm().state.awaitState { it.alerts.isNotEmpty() }
        assertEquals(aug.daysRemainingInclusive(fx.today), state.alerts.single().daysRemaining)
        assertEquals(state.daysRemaining, state.alerts.single().daysRemaining)
    }

    // --- the limit editor ---------------------------------------------------

    @Test
    fun the_editor_opens_prefilled_with_the_existing_limit() = runBlocking {
        // Adjusting a limit should not mean retyping it.
        limit(8_000, "Grocery")
        val vm = vm()
        vm.state.awaitState { !it.initialLoad }
        vm.editLimit(fx.leafId("Grocery"), "Grocery")

        val editor = vm.state.awaitState { it.editor != null }.editor!!
        assertEquals("8000", editor.input)
        assertEquals(Money.ofTaka(8_000), editor.existing)
    }

    @Test
    fun typing_and_saving_a_limit_writes_it_and_closes_the_editor() = runBlocking {
        val vm = vm()
        vm.state.awaitState { !it.initialLoad }
        vm.editLimit(fx.leafId("Grocery"), "Grocery")
        vm.state.awaitState { it.editor != null }

        listOf('2', '5', '0', '0').forEach { vm.onKey(KeypadKey.Digit(it)) }
        assertTrue(vm.state.value.editor!!.canSave)
        vm.saveLimit {}

        vm.state.awaitState { it.editor == null }
        assertEquals(Money.ofTaka(2_500), fx.budgets.limitFor(fx.leafId("Grocery"), aug))
    }

    @Test
    fun a_zero_limit_is_refused_with_the_error_shown_on_the_field() = runBlocking {
        val vm = vm()
        vm.state.awaitState { !it.initialLoad }
        vm.editLimit(fx.leafId("Grocery"), "Grocery")
        vm.state.awaitState { it.editor != null }

        vm.onKey(KeypadKey.Digit('0'))
        assertFalse(vm.state.value.editor!!.canSave)
        vm.saveLimit {}

        assertEquals(EntryError.ZERO_LIMIT, vm.state.awaitState { it.editor?.error != null }.editor!!.error)
        assertNull(fx.budgets.limitFor(fx.leafId("Grocery"), aug))
    }

    @Test
    fun the_negate_key_does_nothing_because_a_negative_limit_is_meaningless() = runBlocking {
        // The pad is shared with expense entry, where a refund is a real thing.
        val vm = vm()
        vm.state.awaitState { !it.initialLoad }
        vm.editLimit(fx.leafId("Grocery"), "Grocery")
        vm.state.awaitState { it.editor != null }

        listOf('5', '0', '0').forEach { vm.onKey(KeypadKey.Digit(it)) }
        vm.onKey(KeypadKey.Negate)
        assertEquals("500", vm.state.value.editor!!.input)
    }

    @Test
    fun clearing_returns_the_leaf_to_unbudgeted() = runBlocking {
        limit(8_000, "Grocery")
        val vm = vm()
        vm.state.awaitState { it.hasLimit("Grocery") == true }

        vm.editLimit(fx.leafId("Grocery"), "Grocery")
        vm.state.awaitState { it.editor != null }
        vm.clearLimit { _, _ -> }

        assertFalse(vm.state.awaitState { it.hasLimit("Grocery") == false }.leaf("Grocery").hasLimit)
    }

    @Test
    fun a_limit_on_a_root_never_reaches_the_database() = runBlocking {
        // The screen offers no such control, so this is defence in depth — the
        // ViewModel is the layer a future dashboard would reuse.
        val vm = vm()
        vm.state.awaitState { !it.initialLoad }
        vm.editLimit(fx.rootId("Variable Expenses"), "Variable Expenses")
        vm.state.awaitState { it.editor != null }

        listOf('9', '0', '0', '0').forEach { vm.onKey(KeypadKey.Digit(it)) }
        vm.saveLimit {}

        assertEquals(
            EntryError.BUDGET_ON_NON_LEAF,
            vm.state.awaitState { it.editor?.error != null }.editor!!.error,
        )
    }

    // --- periods ------------------------------------------------------------

    @Test
    fun switching_periods_re_points_every_figure() = runBlocking {
        limit(8_000, "Grocery", period = jul)
        spend(2_000, "Grocery", day = 10, period = jul)
        spend(500, "Grocery", day = 3, period = aug)

        val vm = vm(aug)
        assertEquals(
            Money.ofTaka(500),
            vm.state.awaitState { it.spentPaisa("Grocery") == 50_000L }
                .leaf("Grocery").status.spent,
        )
        assertFalse(vm.state.value.leaf("Grocery").hasLimit)

        vm.setPeriod(jul)

        val july = vm.state.awaitState { it.period == jul && !it.initialLoad && it.hasLimit("Grocery") == true }
        assertEquals(Money.ofTaka(2_000), july.leaf("Grocery").status.spent)
        assertEquals(Money.ofTaka(8_000), july.leaf("Grocery").status.limit)
    }

    @Test
    fun switching_to_the_period_already_shown_is_a_no_op() = runBlocking {
        val vm = vm(aug)
        val before = vm.state.awaitState { !it.initialLoad }
        vm.setPeriod(aug)
        assertFalse("must not re-enter the skeleton", vm.state.value.initialLoad)
        assertEquals(before.period, vm.state.value.period)
    }

    // --- FR-BUD-04 ----------------------------------------------------------

    @Test
    fun copy_from_last_month_reports_what_it_added_and_undo_removes_exactly_that() = runBlocking {
        limit(8_000, "Grocery", period = jul)
        limit(3_000, "Transport", period = jul)
        limit(9_000, "Grocery", period = aug) // already decided this month

        val vm = vm(aug)
        assertEquals(1, vm.state.awaitState { it.copyableCount > 0 }.copyableCount)

        var reported = 0
        var added: List<Long> = emptyList()
        vm.copyFromLastMonth { count, ids -> reported = count; added = ids }

        vm.state.awaitState { it.hasLimit("Transport") == true }
        assertEquals(1, reported)
        assertEquals(listOf(fx.leafId("Transport")), added)
        assertEquals(Money.ofTaka(9_000), fx.budgets.limitFor(fx.leafId("Grocery"), aug))

        vm.undoCopy(added)
        assertFalse(vm.state.awaitState { it.hasLimit("Transport") == false }.leaf("Transport").hasLimit)
        assertEquals(
            "undo must not touch what was already there",
            Money.ofTaka(9_000),
            fx.budgets.limitFor(fx.leafId("Grocery"), aug),
        )
    }

    @Test
    fun the_copy_action_is_disabled_when_last_month_has_nothing_to_give() = runBlocking {
        assertEquals(0, vm(aug).state.awaitState { !it.initialLoad }.copyableCount)
    }

    @Test
    fun the_copy_count_falls_to_zero_once_there_are_no_gaps_left() = runBlocking {
        limit(8_000, "Grocery", period = jul)
        val vm = vm(aug)
        vm.state.awaitState { it.copyableCount == 1 }

        vm.copyFromLastMonth { _, _ -> }

        assertEquals(0, vm.state.awaitState { it.copyableCount == 0 }.copyableCount)
    }

    // --- C5: NFR-USE-03 reaches this one too ---------------------------------

    @Test
    fun clearing_a_limit_hands_it_back_so_it_can_be_undone() = runBlocking {
        // "Every destructive action is undoable for at least 5 seconds", and
        // this destroys a figure the user typed. Archiving a category — less
        // destructive than this — has had an undo since M2.
        limit(8_000, "Grocery")
        val vm = vm()
        vm.state.awaitState { it.hasLimit("Grocery") == true }
        vm.editLimit(fx.leafId("Grocery"), "Grocery")
        vm.state.awaitState { it.editor != null }

        var removed: Money? = null
        var removedFrom: Long? = null
        vm.clearLimit { categoryId, limit -> removedFrom = categoryId; removed = limit }

        vm.state.awaitState { it.hasLimit("Grocery") == false }
        assertEquals(Money.ofTaka(8_000), removed)
        assertEquals(fx.leafId("Grocery"), removedFrom)
    }

    @Test
    fun undoing_a_clear_puts_the_limit_back_exactly() = runBlocking {
        limit(8_000, "Grocery")
        val vm = vm()
        vm.state.awaitState { it.hasLimit("Grocery") == true }
        vm.editLimit(fx.leafId("Grocery"), "Grocery")
        vm.state.awaitState { it.editor != null }

        var removed: Money? = null
        var removedFrom: Long? = null
        vm.clearLimit { categoryId, limit -> removedFrom = categoryId; removed = limit }
        vm.state.awaitState { it.hasLimit("Grocery") == false }

        vm.undoClear(removedFrom!!, removed!!)

        val state = vm.state.awaitState { it.hasLimit("Grocery") == true }
        assertEquals(Money.ofTaka(8_000), state.leaf("Grocery").status.limit)
    }

    @Test
    fun clearing_a_leaf_that_had_no_limit_reports_nothing_to_undo() = runBlocking {
        val vm = vm()
        vm.state.awaitState { !it.initialLoad }
        vm.editLimit(fx.leafId("Grocery"), "Grocery")
        vm.state.awaitState { it.editor != null }

        var called = false
        vm.clearLimit { _, _ -> called = true }

        vm.state.awaitState { it.editor == null }
        assertFalse("no snackbar for a limit that was never there", called)
    }
}
