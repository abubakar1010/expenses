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
                LedgerViewModel(fx.expenses, fx.categories, fx.clock) as T
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
        assertNotNull(vm.state.awaitState { it.lastDeleted != null }.lastDeleted)
        assertTrue(vm.state.awaitState { it.days.isEmpty() }.days.isEmpty())

        vm.undoDelete()
        assertEquals(1, vm.state.awaitState { it.days.isNotEmpty() }.days.single().rows.size)
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
}
