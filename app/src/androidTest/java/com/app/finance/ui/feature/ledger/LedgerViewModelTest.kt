package com.app.finance.ui.feature.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.awaitState
import com.app.finance.core.money.Money
import com.app.finance.domain.model.LedgerFilters
import com.app.finance.domain.model.PaymentMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LedgerViewModelTest {

    private lateinit var fx: TestFixture

    /**
     * ViewModels come from a store so teardown can cancel `viewModelScope`.
     * This one observes both the ledger and the category tree for as long as it
     * lives; left running it outlives the test and hits a closed connection
     * pool during the next, failing far from the cause.
     */
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

    /** A fresh key each call, so a test can hold two independent ledgers. */
    private fun vm(): LedgerViewModel = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                LedgerViewModel(fx.expenses, fx.categories, fx.recurring, fx.people, fx.settlements, fx.clock) as T
        },
    )["vm${seq++}", LedgerViewModel::class.java]

    private suspend fun seed(taka: Long, category: String, daysAgo: Long = 0, note: String? = null) =
        fx.expenses.insert(
            amount = Money.ofTaka(taka),
            categoryId = fx.leafId(category),
            spentOn = fx.today.minusDays(daysAgo),
            note = note,
        )

    @Test
    fun rows_are_grouped_by_day_with_a_subtotal_per_day() = runBlocking {
        // FR-EXP-09.
        seed(100, "Grocery")
        seed(250, "Transport")
        seed(400, "Grocery", daysAgo = 1)

        val state = vm().state.awaitState { it.days.size == 2 }

        val today = state.days.first()
        assertEquals(fx.today, today.date)
        assertEquals(Money.ofTaka(350), today.total)
        assertEquals(2, today.rows.size)

        assertEquals(Money.ofTaka(400), state.days[1].total)
    }

    @Test
    fun days_are_ordered_newest_first() = runBlocking {
        seed(10, "Grocery", daysAgo = 5)
        seed(20, "Grocery", daysAgo = 1)
        seed(30, "Grocery", daysAgo = 3)

        val dates = vm().state.awaitState { it.days.size == 3 }.days.map { it.date }
        assertEquals(dates.sortedDescending(), dates)
    }

    @Test
    fun a_refund_reduces_its_days_subtotal() = runBlocking {
        seed(500, "Grocery")
        seed(-200, "Grocery")
        assertEquals(Money.ofTaka(300), vm().state.awaitState { it.days.isNotEmpty() }.days.single().total)
    }

    @Test
    fun the_initial_load_flag_clears_once_rows_arrive() = runBlocking {
        // Drives the skeleton. If it never cleared the ledger would show
        // placeholder bars forever.
        seed(100, "Grocery")
        val state = vm().state.awaitState { !it.initialLoad }
        assertFalse(state.initialLoad)
    }

    @Test
    fun an_empty_ledger_and_an_empty_filter_result_are_different_states() = runBlocking {
        val empty = vm().state.awaitState { !it.initialLoad }
        assertTrue(empty.isEmpty)
        assertFalse("nothing logged is not a failed search", empty.isFilteredEmpty)

        seed(100, "Grocery")
        val filtered = vm()
        filtered.state.awaitState { it.days.isNotEmpty() }
        filtered.setQuery("nothing matches this")
        assertTrue(filtered.state.awaitState { it.isFilteredEmpty }.isFilteredEmpty)
    }

    @Test
    fun filters_narrow_the_list_and_clearing_restores_it() = runBlocking {
        seed(100, "Grocery")
        seed(200, "House Rent")

        val vm = vm()
        assertEquals(2, vm.state.awaitState { it.days.isNotEmpty() }.days.single().rows.size)

        vm.applyFilters(LedgerFilters(leafId = fx.leafId("Grocery")))
        val narrowed = vm.state.awaitState { it.days.firstOrNull()?.rows?.size == 1 }
        assertEquals(1, narrowed.filters.activeCount)

        vm.clearFilters()
        assertEquals(2, vm.state.awaitState { it.days.firstOrNull()?.rows?.size == 2 }.days.single().rows.size)
    }

    @Test
    fun clearing_filters_keeps_the_search_text() = runBlocking {
        // Clearing the chips should not silently discard what the user typed.
        seed(100, "Grocery", note = "rice")
        val vm = vm()
        vm.state.awaitState { it.days.isNotEmpty() }
        vm.setQuery("rice")
        vm.applyFilters(vm.state.value.filters.copy(method = PaymentMethod.CASH))
        vm.clearFilters()

        assertEquals("rice", vm.state.value.filters.query)
        assertEquals(0, vm.state.value.filters.activeCount)
    }

    @Test
    fun search_matches_a_note_or_an_exact_amount() = runBlocking {
        seed(250, "Grocery", note = "weekly shop")
        seed(1250, "Grocery", note = "stock up")

        val vm = vm()
        vm.state.awaitState { it.days.firstOrNull()?.rows?.size == 2 }

        vm.setQuery("weekly")
        assertEquals(1, vm.state.awaitState { it.days.firstOrNull()?.rows?.size == 1 }.days.single().rows.size)

        vm.setQuery("250")
        val rows = vm.state.awaitState {
            it.days.firstOrNull()?.rows?.singleOrNull()?.expense?.amountMinor == 25_000L
        }.days.single().rows
        assertEquals(1, rows.size)
        assertEquals(25_000L, rows.single().expense.amountMinor)
    }

    @Test
    fun deleting_keeps_the_row_for_undo_and_restoring_puts_it_back() = runBlocking {
        // NFR-USE-03 — undoable, with no confirmation dialog in front of it.
        seed(300, "Grocery")
        val vm = vm()
        val id = vm.state.awaitState { it.days.isNotEmpty() }.days.single().rows.single().expense.id

        vm.delete(id)
        val queued = vm.state.awaitState { it.undoQueue.isNotEmpty() }.undoQueue.single()
        assertTrue(vm.state.awaitState { it.days.isEmpty() }.days.isEmpty())

        vm.undo(queued.id)
        assertEquals(1, vm.state.awaitState { it.days.isNotEmpty() }.days.single().rows.size)
    }

    @Test
    fun a_second_deletion_inside_the_undo_window_does_not_take_the_first_one_away() = runBlocking {
        // NFR-USE-03 — "undoable for at least 5 seconds", and it was not.
        //
        // `lastDeleted` was one slot driving a `LaunchedEffect` keyed on itself.
        // A second swipe re-keyed the effect, and re-keying *cancels* the
        // running one without executing either branch — so neither the restore
        // nor the release ran, and the slot was overwritten by the second row.
        // The first row is already gone from the database; that held copy was
        // the only one left. It could not be recovered by any action, and the
        // snackbar offering to do so had vanished after a second.
        seed(300, "Grocery", daysAgo = 1)
        seed(400, "Grocery", daysAgo = 0)
        val vm = vm()
        val rows = vm.state
            .awaitState { it.days.sumOf { d -> d.rows.size } == 2 }
            .days.flatMap { it.rows }
        // By amount, not by position: the ledger orders `spent_on DESC, id DESC`,
        // so the newer row leads and an index would be asserting on the sort.
        val threeHundred = rows.first { it.expense.amountMinor == 300_00L }.expense.id
        val fourHundred = rows.first { it.expense.amountMinor == 400_00L }.expense.id

        vm.delete(threeHundred)
        val first = vm.state.awaitState { it.undoQueue.size == 1 }.undoQueue.single()
        vm.delete(fourHundred)
        val queue = vm.state.awaitState { it.undoQueue.size == 2 }.undoQueue

        // The head is untouched, which is what the screen keys its effect on:
        // appending must not restart the window the first row is still inside.
        assertEquals("the second deletion displaced the first", first.id, queue.first().id)

        // And the first is still restorable, which is the requirement. Under
        // the single slot it was not: the second delete overwrote the only
        // surviving copy of it.
        //
        // Awaiting the exact condition, not "one row is showing" — there was
        // already a state with one row in it, between the two deletions, and a
        // `StateFlow` will hand that back the moment it is asked. CLAUDE.md's
        // rule about awaiting the state you are about to assert on is not only
        // about ordering between coroutines; a stale match is the same bug.
        vm.undo(first.id)
        val restored = vm.state.awaitState { state ->
            state.days.flatMap { it.rows }.singleOrNull()?.expense?.amountMinor == 300_00L
        }
        assertEquals(
            "the first deletion could not be undone",
            300_00L,
            restored.days.single().rows.single().expense.amountMinor,
        )
    }

    @Test
    fun a_deletion_and_a_dismissal_share_one_queue() = runBlocking {
        // They used to have a slot and a `LaunchedEffect` each, both pointed at
        // the same snackbar host — so doing one of each within five seconds put
        // two coroutines in a race for the host's mutex on top of each losing
        // its own window.
        seed(300, "Grocery")
        val vm = vm()
        val id = vm.state.awaitState { it.days.isNotEmpty() }.days.single().rows.single().expense.id

        vm.delete(id)
        val queued = vm.state.awaitState { it.undoQueue.size == 1 }.undoQueue.single()
        assertTrue(
            "a swipe should queue a Deleted",
            queued.payload is LedgerUndo.Deleted,
        )

        // Dropping the head is what the screen does when the window closes with
        // no tap: the action stands and the next one gets its turn.
        vm.dropUndo(queued.id)
        assertTrue(vm.state.awaitState { it.undoQueue.isEmpty() }.undoQueue.isEmpty())
    }

    @Test
    fun paging_stops_when_the_ledger_is_exhausted() = runBlocking {
        repeat(60) { i -> seed(10L + i, "Grocery", daysAgo = i.toLong()) }

        val vm = vm()
        val first = vm.state.awaitState { it.days.sumOf { d -> d.rows.size } == 50 }
        assertFalse(first.endReached)

        vm.loadMore()
        val second = vm.state.awaitState { it.days.sumOf { d -> d.rows.size } == 60 }
        assertTrue(second.endReached)

        // Past the end, further requests are no-ops rather than repeats.
        vm.loadMore()
        assertEquals(60, vm.state.value.days.sumOf { it.rows.size })
    }

    @Test
    fun a_new_expense_appears_without_anyone_asking_for_a_refresh() = runBlocking {
        // 04 §5.1 — Room's invalidation tracker is the entire refresh
        // mechanism. Nothing here polls, and no screen posts an event.
        val vm = vm()
        assertTrue(vm.state.awaitState { !it.initialLoad }.days.isEmpty())

        seed(175, "Grocery")

        val after = vm.state.awaitState { it.days.isNotEmpty() }
        assertEquals(1, after.days.size)
        assertEquals(Money.ofTaka(175), after.days.single().total)
    }

    // --- C4: an archived category stays filterable ---------------------------

    @Test
    fun a_category_with_expenses_is_offered_as_a_filter_after_it_is_archived() = runBlocking {
        // FR-EXP-08 says the ledger is filterable by leaf. FR-CAT-08 takes an
        // archived category out of *entry pickers* and leaves it "visible in
        // historical reports" — and a year of grocery rows with "Grocery"
        // printed on each of them is exactly that. Third time this class of
        // defect has appeared: §15.6 on the income filter, §18.2 on the
        // dashboard's figures, and here.
        val grocery = fx.leafId("Grocery")
        seed(340, "Grocery")

        val vm = vm()
        vm.state.awaitState { !it.initialLoad && it.days.isNotEmpty() }
        fx.categories.archive(grocery)

        val state = vm.state.awaitState {
            it.tree.flatMap { root -> root.activeChildren }.none { leaf -> leaf.id == grocery }
        }
        assertTrue(
            "the rows are still there, so the filter must still reach them",
            grocery in state.categoriesPresent,
        )
    }

    @Test
    fun a_category_with_no_expenses_on_screen_is_not_offered() = runBlocking {
        // "Active ∪ present", not "everything ever" — an archived category the
        // user never spent on has nothing to filter to.
        seed(340, "Grocery")
        val state = vm().state.awaitState { !it.initialLoad && it.days.isNotEmpty() }

        assertTrue(fx.leafId("Grocery") in state.categoriesPresent)
        assertFalse(fx.leafId("Medical") in state.categoriesPresent)
    }

    @Test
    fun filtering_to_an_archived_category_returns_its_rows() = runBlocking {
        val grocery = fx.leafId("Grocery")
        seed(340, "Grocery")
        seed(900, "Transport")
        fx.categories.archive(grocery)

        val vm = vm()
        vm.state.awaitState { !it.initialLoad && it.days.isNotEmpty() }
        vm.applyFilters(LedgerFilters.NONE.copy(leafId = grocery))

        // The filter lands in state before the reload it triggers finishes,
        // so waiting on `filters` alone caught the old rows still in place.
        // Waiting on the reloaded page is the idiom the rest of this class
        // already uses.
        val state = vm.state.awaitState {
            it.filters.leafId == grocery && it.days.sumOf { d -> d.rows.size } == 1
        }
        assertEquals(1, state.days.sumOf { it.rows.size })
        assertEquals("Grocery", state.days.first().rows.first().categoryName)
    }

    // --- FR-EXP-11: what the filter comes to --------------------------------

    private fun scalar(sql: String): Long =
        fx.db.openHelper.writableDatabase.query(sql)
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }

    @Test
    fun the_filtered_total_covers_every_match_not_just_the_loaded_page() = runBlocking {
        // FR-EXP-11's acceptance case, and the whole reason the figure is a
        // query instead of `days.sumOf { it.total }`: PAGE_SIZE is 50, so a
        // 120-row filter leaves well over half its own total off screen.
        repeat(120) { i -> seed(10, "Grocery", daysAgo = (i % 30).toLong()) }
        seed(9_999, "Transport")

        val vm = vm()
        vm.state.awaitState { !it.initialLoad && it.days.isNotEmpty() }
        vm.applyFilters(LedgerFilters(leafId = fx.leafId("Grocery")))

        val state = vm.state.awaitState { it.filteredCount == 120 }
        assertEquals(Money.ofTaka(1_200), state.filteredTotal)

        // One page is loaded — which is what would have made summing the rows
        // in memory report ৳500 for a filter worth ৳1,200.
        assertEquals(50, state.days.sumOf { it.rows.size })
    }

    @Test
    fun scrolling_does_not_move_the_filtered_total() = runBlocking {
        // "The figure MUST NOT change as the user scrolls." A total that grows
        // while you read it is worse than no total, because it looks settled.
        repeat(120) { i -> seed(10, "Grocery", daysAgo = (i % 30).toLong()) }

        val vm = vm()
        vm.state.awaitState { !it.initialLoad && it.days.isNotEmpty() }
        vm.applyFilters(LedgerFilters(leafId = fx.leafId("Grocery")))
        val before = vm.state.awaitState { it.filteredCount == 120 }.filteredTotal

        vm.loadMore()
        val after = vm.state.awaitState { it.days.sumOf { d -> d.rows.size } == 100 }
        assertEquals("a second page revealed more rows, not more filter", before, after.filteredTotal)
        assertEquals(120, after.filteredCount)
    }

    @Test
    fun the_filtered_total_reconciles_with_a_direct_sum_over_the_ledger() = runBlocking {
        // NFR-REL-02, the same standard every other rendered aggregate is held
        // to: the figure equals a sum taken straight off the ledger, not the
        // same read performed a second way.
        val grocery = fx.leafId("Grocery")
        seed(100, "Grocery")
        seed(250, "Grocery", daysAgo = 3)
        seed(-40, "Grocery", daysAgo = 4)
        seed(70, "Transport")

        val vm = vm()
        vm.state.awaitState { !it.initialLoad && it.days.isNotEmpty() }
        vm.applyFilters(LedgerFilters(leafId = grocery))
        val state = vm.state.awaitState { it.filteredCount == 3 }

        assertEquals(
            scalar(
                "SELECT IFNULL(SUM(amount_minor), 0) FROM expense " +
                    "WHERE status = 0 AND category_id = $grocery",
            ),
            state.filteredTotal.paisa,
        )
    }

    @Test
    fun the_total_follows_the_filter_it_describes() = runBlocking {
        seed(100, "Grocery")
        seed(200, "Grocery", daysAgo = 10)

        val vm = vm()
        vm.state.awaitState { !it.initialLoad && it.days.isNotEmpty() }

        vm.applyFilters(LedgerFilters(from = fx.today.minusDays(2)))
        assertEquals(Money.ofTaka(100), vm.state.awaitState { it.filteredCount == 1 }.filteredTotal)

        vm.applyFilters(LedgerFilters(from = fx.today.minusDays(20)))
        assertEquals(Money.ofTaka(300), vm.state.awaitState { it.filteredCount == 2 }.filteredTotal)
    }

    @Test
    fun the_total_is_not_shown_until_a_filter_is_active() = runBlocking {
        seed(100, "Grocery")

        val vm = vm()
        val unfiltered = vm.state.awaitState { !it.initialLoad && it.days.isNotEmpty() }
        assertFalse(
            "an unfiltered ledger is not asking what everything comes to",
            unfiltered.showsFilteredTotal,
        )

        vm.setQuery("100")
        val searched = vm.state.awaitState { it.filters.hasQuery && it.filteredCount == 1 }
        assertTrue(searched.showsFilteredTotal)
        assertEquals(Money.ofTaka(100), searched.filteredTotal)
    }

    @Test
    fun a_filter_that_matches_nothing_totals_zero_rather_than_the_last_answer() = runBlocking {
        // The stale-figure case: the total has to be cleared by the reload that
        // empties the list, not left showing what the previous filter was worth.
        seed(100, "Grocery")

        val vm = vm()
        vm.state.awaitState { !it.initialLoad && it.days.isNotEmpty() }
        vm.applyFilters(LedgerFilters(leafId = fx.leafId("Grocery")))
        assertEquals(Money.ofTaka(100), vm.state.awaitState { it.filteredCount == 1 }.filteredTotal)

        vm.setQuery("nothing matches this")
        val empty = vm.state.awaitState { it.isFilteredEmpty }
        assertEquals(Money.ZERO, empty.filteredTotal)
        assertEquals(0, empty.filteredCount)
        assertFalse("nothing to total", empty.showsFilteredTotal)
    }
}
