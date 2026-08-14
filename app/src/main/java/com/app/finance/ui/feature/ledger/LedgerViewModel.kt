package com.app.finance.ui.feature.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.finance.core.money.Money
import com.app.finance.data.db.dao.ExpenseWithCategory
import com.app.finance.data.db.entity.ExpenseEntity
import com.app.finance.data.repo.CategoryRepository
import com.app.finance.data.repo.ExpenseRepository
import com.app.finance.domain.model.CategoryNode
import com.app.finance.domain.model.LedgerFilters
import com.app.finance.domain.model.PaymentMethod
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
    val filters: LedgerFilters = LedgerFilters.NONE,
    val tree: List<CategoryNode> = emptyList(),
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
    /** Held for the five seconds the undo snackbar is on screen. */
    val lastDeleted: ExpenseEntity? = null,
) {
    val isEmpty: Boolean get() = !initialLoad && days.isEmpty()

    /** An empty result means something different when a filter is applied. */
    val isFilteredEmpty: Boolean get() = isEmpty && !filters.isDefault
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
            val removed = withContext(io) { repo.delete(id) }
            _state.update { it.copy(lastDeleted = removed) }
        }
    }

    fun undoDelete() {
        val row = _state.value.lastDeleted ?: return
        viewModelScope.launch {
            withContext(io) { repo.restore(row) }
            _state.update { it.copy(lastDeleted = null) }
        }
    }

    fun clearUndo() = _state.update { it.copy(lastDeleted = null) }

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
            val fresh = withContext(io) {
                buildList {
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
            }
            rows = fresh
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
