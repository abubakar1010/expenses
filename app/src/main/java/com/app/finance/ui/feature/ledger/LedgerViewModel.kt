package com.app.finance.ui.feature.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.finance.core.money.Money
import com.app.finance.data.db.dao.ExpenseWithCategory
import com.app.finance.data.db.dao.FilteredTotal
import com.app.finance.data.db.dao.PendingExpense
import com.app.finance.data.db.dao.PendingIncome
import com.app.finance.data.db.entity.ExpenseEntity
import com.app.finance.data.db.entity.IncomeEntryEntity
import com.app.finance.data.db.entity.PersonEntity
import com.app.finance.data.repo.CategoryRepository
import com.app.finance.data.repo.DeletedExpense
import com.app.finance.data.repo.ExpenseRepository
import com.app.finance.data.repo.PersonRepository
import com.app.finance.data.repo.SettlementRepository
import com.app.finance.data.repo.RecurringRepository
import com.app.finance.domain.model.CategoryNode
import com.app.finance.domain.model.LedgerFilters
import com.app.finance.domain.model.PaymentMethod
import com.app.finance.ui.common.Undoable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate

/** A day's expenses with its subtotal — the ledger is grouped by day (FR-EXP-09). */
data class LedgerDay(
    val date: LocalDate,
    val total: Money,
    val rows: List<ExpenseWithCategory>,
)

data class LedgerUiState(
    val days: List<LedgerDay> = emptyList(),
    /**
     * What the filter matches in total — FR-EXP-11.
     *
     * Not `days.sumOf { it.total }`. The day groups hold the pages scrolled so
     * far and nothing more (FR-EXP-10), so summing them would show a figure
     * that climbs while the user scrolls and is only right at the bottom of the
     * list. This comes from one aggregate over the whole predicate.
     */
    val filteredTotal: Money = Money.ZERO,
    /** How many rows that total is over — the count half of the same aggregate. */
    val filteredCount: Int = 0,
    val filters: LedgerFilters = LedgerFilters.NONE,
    val tree: List<CategoryNode> = emptyList(),
    /** Active people, for FR-SHR-06's filter chips. */
    val people: List<PersonEntity> = emptyList(),
    /** The balance with the filtered person, when one is filtered — FR-SHR-06. */
    val personBalance: Money = Money.ZERO,
    /**
     * `LocalDate.ofEpochDay(0)`, not `LocalDate.EPOCH` — that constant was only
     * added in API 34 and this app ships to API 26, so it would have thrown
     * `NoSuchFieldError` on every device below Android 14. Caught by lint,
     * which is the reason the lint block exists.
     */
    val today: LocalDate = LocalDate.ofEpochDay(0),
    /** True only until the first page arrives — drives the skeleton, not a spinner. */
    val initialLoad: Boolean = true,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val filterSheetOpen: Boolean = false,
    /**
     * Destructive actions still inside their undo window — FR-EXP-07,
     * NFR-USE-03.
     *
     * A queue, not a slot. There were two slots, one per kind, and each drove a
     * `LaunchedEffect` keyed on itself: a second swipe within the five seconds
     * re-keyed the effect, which cancels *without running either branch*, so
     * neither the restore nor the release happened and the first row's only
     * surviving copy — it is already gone from the database — was overwritten
     * and lost. The user got a snackbar that vanished after a second and an
     * entry that could never come back.
     *
     * Keyed on [Undoable.id], appending leaves the head alone, so the first
     * action keeps the whole window the requirement promises and the second
     * starts its own when that closes.
     */
    val undoQueue: List<Undoable<LedgerUndo>> = emptyList(),
    /**
     * FR-REC-02's one-tap confirmations, above the day groups.
     *
     * These are the only rows on this screen that are not in any figure
     * anywhere: `status = 1` is excluded by every rollup trigger and by every
     * other read in the app.
     */
    val pendingExpenses: List<PendingExpense> = emptyList(),
    val pendingIncome: List<PendingIncome> = emptyList(),
) {
    val pendingCount: Int get() = pendingExpenses.size + pendingIncome.size

    /**
     * Leaf ids the loaded rows actually reference.
     *
     * What lets the filter offer an archived category the user still has
     * expenses under — FR-EXP-08 filters by leaf, and FR-CAT-08 only takes
     * archived categories out of *entry* pickers.
     */
    val categoriesPresent: Set<Long>
        get() = days.flatMapTo(HashSet()) { day -> day.rows.map { it.expense.categoryId } }

    val isEmpty: Boolean get() = !initialLoad && days.isEmpty()

    /** An empty result means something different when a filter is applied. */
    val isFilteredEmpty: Boolean get() = isEmpty && !filters.isDefault

    /**
     * The filtered total earns its place only while a filter is narrowing the
     * list — FR-EXP-11.
     *
     * Unfiltered it would be the sum of the entire history, sitting above a
     * screen that is not asking that question, and it would be the one figure
     * on it that never changes. The day subtotals FR-EXP-09 requires are what
     * an unfiltered ledger is for.
     */
    val showsFilteredTotal: Boolean get() = !filters.isDefault && !initialLoad && days.isNotEmpty()
}

/**
 * The paged, filtered ledger.
 *
 * Pagination is keyset, not offset (03 §5.5), and not `androidx.paging` — the
 * library is not in the dependency budget and would earn its weight only for
 * behaviour this screen does not need. What it does need is a cursor, a page
 * count, and a reload that respects how far the user has already scrolled.
 *
 * Nothing here polls. `observeRevision()` is Room-invalidated, so an expense
 * saved from the Quick Add sheet — on any screen — causes this list to re-emit
 * on its own (04 §5.1).
 */
class LedgerViewModel(
    private val repo: ExpenseRepository,
    categories: CategoryRepository,
    private val recurring: RecurringRepository,
    people: PersonRepository,
    private val settlements: SettlementRepository,
    private val clock: Clock,
    /**
     * Injected so tests can run on a single deterministic dispatcher. With a
     * hardcoded `Dispatchers.IO`, cancelling a ViewModel in teardown races the
     * query it is still running, and the failure lands in whichever test runs
     * next.
     */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(LedgerUiState(today = LocalDate.now(clock)))
    val state: StateFlow<LedgerUiState> = _state.asStateFlow()

    private var rows = emptyList<ExpenseWithCategory>()

    /**
     * The filtered set's aggregate, held beside [rows] because it does not move
     * with them: paging in another fifty rows reveals more of the same filter,
     * it does not widen it. Only [reload] — a filter change, or the ledger
     * itself changing — can move this figure.
     */
    private var total = FilteredTotal(totalMinor = 0L, txnCount = 0)

    /** The balance with the filtered person, when one is filtered — FR-SHR-06. */
    private var personBalance = Money.ZERO
    private var pagesLoaded = 1
    private var loadJob: Job? = null

    init {
        reload()
        viewModelScope.launch {
            // drop(1): the first emission is the initial query result, which
            // reload() above already covers.
            repo.observeRevision().drop(1).collect { reload() }
        }
        viewModelScope.launch {
            categories.observeTree().collect { tree -> _state.update { it.copy(tree = tree) } }
        }
        viewModelScope.launch {
            people.observeActive().collect { rows -> _state.update { it.copy(people = rows) } }
        }
        // FR-REC-02. Two flows rather than one union query: an expense and an
        // income entry are different rows with different confirmations, and a
        // `UNION` would need a discriminator column to tell them apart again.
        viewModelScope.launch {
            recurring.observePendingExpenses().collect { rows ->
                _state.update { it.copy(pendingExpenses = rows) }
            }
        }
        viewModelScope.launch {
            recurring.observePendingIncome().collect { rows ->
                _state.update { it.copy(pendingIncome = rows) }
            }
        }
    }

    // --- FR-REC-02's one tap -------------------------------------------------

    /**
     * Confirming is a status change, which is what makes it safe.
     *
     * `trg_rollup_exp_upd` sees `OLD.status = 1` and `NEW.status = 0`, skips the
     * decrement and performs the increment, so the entry joins every aggregate
     * in the same transaction — without a line here touching a rollup, and
     * without the ledger needing a reload, because `observeRevision` fires.
     */
    fun confirmExpense(id: Long) {
        viewModelScope.launch { withContext(io) { recurring.confirmExpense(id) } }
    }

    fun confirmIncome(id: Long) {
        viewModelScope.launch { withContext(io) { recurring.confirmIncome(id) } }
    }

    /**
     * The rule fired but the thing did not happen. Deleted, not posted — and
     * undoable, because NFR-USE-03 says every destructive action is, and this
     * one cannot be recovered by waiting: the rule has already moved past that
     * due date and will not generate it again.
     */
    fun dismissExpense(id: Long) {
        viewModelScope.launch {
            val row = withContext(io) { recurring.dismissExpense(id) } ?: return@launch
            queueUndo(LedgerUndo.Dismissed(DismissedEntry.Expense(row)))
        }
    }

    fun dismissIncome(id: Long) {
        viewModelScope.launch {
            val row = withContext(io) { recurring.dismissIncome(id) } ?: return@launch
            queueUndo(LedgerUndo.Dismissed(DismissedEntry.Income(row)))
        }
    }


    // --- filtering (FR-EXP-08) ---------------------------------------------

    fun setQuery(query: String) = applyFilters(_state.value.filters.copy(query = query))

    fun applyFilters(filters: LedgerFilters) {
        _state.update { it.copy(filters = filters, filterSheetOpen = false) }
        // A filter change invalidates every loaded page, so paging restarts.
        pagesLoaded = 1
        reload()
    }

    fun clearFilters() = applyFilters(LedgerFilters(query = _state.value.filters.query))

    fun openFilters() = _state.update { it.copy(filterSheetOpen = true) }

    fun dismissFilters() = _state.update { it.copy(filterSheetOpen = false) }

    // --- paging (FR-EXP-10) -------------------------------------------------

    fun loadMore() {
        val current = _state.value
        if (current.initialLoad || current.loadingMore || current.endReached) return
        val cursor = rows.lastOrNull() ?: return

        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val next = withContext(io) {
                repo.filteredPage(current.filters, after = cursor)
            }
            rows = rows + next
            if (next.isNotEmpty()) pagesLoaded++
            publish(endReached = next.size < repo.pageSize, loadingMore = false)
        }
    }

    // --- deletion (FR-EXP-07, NFR-USE-03) -----------------------------------

    /**
     * Deletes immediately and keeps the row for Undo.
     *
     * 05 §8: no confirmation dialog. "A dialog interrupts before the fact and is
     * dismissed reflexively; a snackbar corrects after it and costs nothing when
     * the action was intended."
     */
    fun delete(id: Long) {
        viewModelScope.launch {
            val removed = withContext(io) { repo.delete(id) } ?: return@launch
            queueUndo(LedgerUndo.Deleted(removed))
        }
    }

    // --- the undo queue (NFR-USE-03) ----------------------------------------

    /**
     * Monotonic and never reused, so a screen keying an effect on it cannot
     * confuse two actions of the same kind.
     *
     * Read and written only from [viewModelScope]'s main dispatcher, which is
     * why it needs no synchronisation — and computed *outside* `_state.update`,
     * whose lambda can be run more than once under contention.
     */
    private var nextUndoId = 0L

    private fun queueUndo(action: LedgerUndo) {
        val id = ++nextUndoId
        _state.update { it.copy(undoQueue = it.undoQueue + Undoable(id, action)) }
    }

    /** Puts back whatever action [id] names, then releases it. */
    fun undo(id: Long) {
        val item = _state.value.undoQueue.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            withContext(io) {
                when (val action = item.payload) {
                    is LedgerUndo.Deleted -> repo.restore(action.row)
                    is LedgerUndo.Dismissed -> when (val entry = action.entry) {
                        is DismissedEntry.Expense -> recurring.restoreExpense(entry.row)
                        is DismissedEntry.Income -> recurring.restoreIncome(entry.row)
                    }
                }
            }
            dropUndo(id)
        }
    }

    /** The window closed without a tap: the action stands. */
    fun dropUndo(id: Long) =
        _state.update { state -> state.copy(undoQueue = state.undoQueue.filterNot { it.id == id }) }

    // --- internals ----------------------------------------------------------

    /**
     * Re-reads exactly as many pages as were loaded before, so an edit made
     * while the user is scrolled deep into 2023 does not snap them back to
     * today. Reloading page one would be simpler and wrong.
     *
     * Cancels any in-flight load: a rapid sequence of edits should produce one
     * final list, not a race between several.
     */
    private fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val filters = _state.value.filters
            // Both in one hop to `io`, and both from the same `filters`, so the
            // figure above the list always describes the list below it.
            val (fresh, freshTotal) = withContext(io) {
                val pages = buildList {
                    addAll(repo.filteredPage(filters))
                    var page = 1
                    while (page < pagesLoaded) {
                        val cursor = lastOrNull() ?: break
                        val next = repo.filteredPage(filters, after = cursor)
                        if (next.isEmpty()) break
                        addAll(next)
                        page++
                    }
                }
                pages to repo.filteredTotal(filters)
            }
            // FR-SHR-06. Filtered to a person, the header answers "what does
            // this come to between us" rather than FR-EXP-11's "what did I
            // spend" — which is a different question and not the one being
            // asked by picking a name.
            val balance = filters.personId?.let {
                withContext(io) { settlements.balanceOf(it) }
            } ?: Money.ZERO
            rows = fresh
            total = freshTotal
            personBalance = balance
            publish(
                endReached = fresh.size < repo.pageSize * pagesLoaded,
                loadingMore = false,
            )
        }
    }

    private fun publish(endReached: Boolean, loadingMore: Boolean) {
        val grouped = rows
            .groupBy { LocalDate.ofEpochDay(it.expense.spentOn) }
            .toSortedMap(compareByDescending { it })
            .map { (date, dayRows) ->
                LedgerDay(
                    date = date,
                    total = Money(dayRows.sumOf { it.expense.amountMinor }),
                    rows = dayRows,
                )
            }
        _state.update {
            it.copy(
                days = grouped,
                filteredTotal = Money(total.totalMinor),
                filteredCount = total.txnCount,
                people = it.people,
                personBalance = personBalance,
                initialLoad = false,
                loadingMore = loadingMore,
                endReached = endReached,
                today = LocalDate.now(clock),
            )
        }
    }
}

/** The payment methods offered in the filter sheet, plus "any". */
val FILTERABLE_METHODS: List<PaymentMethod?> = listOf(null) + PaymentMethod.SELECTABLE

/** A dismissed pending row, held only while its undo snackbar is up. */
sealed interface DismissedEntry {
    @JvmInline value class Expense(val row: ExpenseEntity) : DismissedEntry

    @JvmInline value class Income(val row: IncomeEntryEntity) : DismissedEntry
}

/**
 * The two destructive actions this screen offers, so one queue can hold both.
 *
 * They used to have a slot each and a `LaunchedEffect` each, both keyed on the
 * same snackbar host — which means deleting an entry and dismissing a pending
 * one within five seconds put two coroutines in a race for the host's mutex on
 * top of each losing its own window. One queue, one effect, one at a time.
 */
sealed interface LedgerUndo {
    /**
     * An expense removed by FR-EXP-07's swipe, with its shares.
     *
     * [DeletedExpense] rather than the row alone: a shared expense's
     * `expense_share` rows are deleted with it and exist nowhere else
     * afterwards, so undoing only the expense would restore a dinner and forget
     * that three people owed for it.
     */
    @JvmInline value class Deleted(val row: DeletedExpense) : LedgerUndo

    /** A pending row turned down rather than confirmed — FR-REC-02. */
    @JvmInline value class Dismissed(val entry: DismissedEntry) : LedgerUndo
}
