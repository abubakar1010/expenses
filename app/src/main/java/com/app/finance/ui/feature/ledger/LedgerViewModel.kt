package com.app.finance.ui.feature.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.finance.core.money.Money
import com.app.finance.data.db.dao.ExpenseWithCategory
import com.app.finance.data.db.entity.ExpenseEntity
import com.app.finance.data.repo.ExpenseRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** A day's expenses with its subtotal — the ledger is grouped by day (FR-EXP-09). */
data class LedgerDay(
    val date: LocalDate,
    val total: Money,
    val rows: List<ExpenseWithCategory>,
)

data class LedgerUiState(
    val days: List<LedgerDay> = emptyList(),
    val loading: Boolean = true,
    val endReached: Boolean = false,
    /** Held for the five seconds the undo snackbar is on screen. */
    val lastDeleted: ExpenseEntity? = null,
) {
    val isEmpty: Boolean get() = !loading && days.isEmpty()
}

/**
 * The paged ledger.
 *
 * Pagination is keyset, not offset (03 §5.5) and not `androidx.paging` — the
 * library is not in the dependency budget and would earn its ~200 KB only for
 * behaviour this screen does not need. What it does need is forty lines: a
 * cursor, a page count, and a reload that respects how far the user has
 * already scrolled.
 *
 * Nothing here polls or refreshes. `observeRevision()` is a Room-invalidated
 * flow, so an expense saved from the Quick Add sheet — on any screen — causes
 * this list to re-emit on its own (04 §5.1).
 */
class LedgerViewModel(
    private val repo: ExpenseRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LedgerUiState())
    val state: StateFlow<LedgerUiState> = _state.asStateFlow()

    private var rows = emptyList<ExpenseWithCategory>()
    private var pagesLoaded = 1

    init {
        viewModelScope.launch { reload() }
        viewModelScope.launch {
            // drop(1): the first emission is the initial query result, which
            // reload() above is already handling.
            repo.observeRevision().drop(1).collect { reload() }
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.loading || current.endReached) return
        val cursor = rows.lastOrNull() ?: return

        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val next = withContext(Dispatchers.IO) { repo.pageAfter(cursor) }
            rows = rows + next
            if (next.isNotEmpty()) pagesLoaded++
            publish(endReached = next.size < repo.pageSize)
        }
    }

    /**
     * Deletes immediately and keeps the row for Undo.
     *
     * 05 §8: no confirmation dialog. "A dialog interrupts before the fact and
     * is dismissed reflexively; a snackbar corrects after it and costs nothing
     * when the action was intended."
     */
    fun delete(id: Long) {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) { repo.delete(id) }
            _state.update { it.copy(lastDeleted = removed) }
        }
    }

    fun undoDelete() {
        val row = _state.value.lastDeleted ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.restore(row) }
            _state.update { it.copy(lastDeleted = null) }
        }
    }

    fun clearUndo() = _state.update { it.copy(lastDeleted = null) }

    /**
     * Re-reads exactly as many pages as were loaded before, so an edit made
     * while the user is scrolled deep into 2023 does not snap them back to
     * today. The alternative — reloading page one — is simpler and wrong.
     */
    private suspend fun reload() {
        _state.update { it.copy(loading = true) }
        val fresh = withContext(Dispatchers.IO) {
            buildList {
                addAll(repo.firstPage())
                var page = 1
                while (page < pagesLoaded) {
                    val cursor = lastOrNull() ?: break
                    val next = repo.pageAfter(cursor)
                    if (next.isEmpty()) break
                    addAll(next)
                    page++
                }
            }
        }
        rows = fresh
        publish(endReached = fresh.size < repo.pageSize * pagesLoaded)
    }

    private fun publish(endReached: Boolean) {
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
            it.copy(days = grouped, loading = false, endReached = endReached)
        }
    }
}
