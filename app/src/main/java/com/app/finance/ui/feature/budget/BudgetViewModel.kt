package com.app.finance.ui.feature.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.finance.core.money.Money
import com.app.finance.core.time.Period
import com.app.finance.data.repo.BudgetRepository
import com.app.finance.data.repo.CategoryRepository
import com.app.finance.domain.model.EntryError
import com.app.finance.domain.model.SaveOutcome
import com.app.finance.domain.usecase.BudgetAlert
import com.app.finance.domain.usecase.BudgetAlerts
import com.app.finance.domain.usecase.BudgetGroup
import com.app.finance.domain.usecase.BudgetSummary
import com.app.finance.ui.common.KeypadKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate

/** The limit editor, open on one leaf. */
data class LimitEditor(
    val categoryId: Long,
    val categoryName: String,
    val input: String = "",
    val existing: Money? = null,
    val error: EntryError? = null,
) {
    val amount: Money? get() = Money.parseOrNull(input)
    val canSave: Boolean get() = amount?.let { it.paisa > 0L } == true
}

data class BudgetUiState(
    val period: Period,
    val groups: List<BudgetGroup> = emptyList(),
    val alerts: List<BudgetAlert> = emptyList(),
    val daysRemaining: Int = 0,
    val initialLoad: Boolean = true,
    /** How many leaves a copy would add — zero disables the action. */
    val copyableCount: Int = 0,
    val editor: LimitEditor? = null,
) {
    /**
     * Only when there is nothing to budget *at all*.
     *
     * Deliberately not "no limits are set": that is the state a first-time user
     * is in, and it is exactly when the list of categories is most useful —
     * every row carries a "Set one" action, so the list *is* the empty state's
     * call to action. Hiding it behind a message would leave the screen with
     * nothing to act on.
     *
     * Since three roots and thirteen leaves are seeded at install, this is
     * effectively unreachable — it exists for the case where every category has
     * been archived.
     */
    val isEmpty: Boolean get() = !initialLoad && groups.isEmpty()
}

/**
 * The budget screen — FR-BUD-01 … FR-BUD-08.
 *
 * Everything it shows is derived, nothing is cached: the bars come from the
 * rollup query, the groups and their totals from pure functions over that, and
 * the alerts from the groups. So saving an expense anywhere in the app moves
 * the bars here without a refresh, for the same reason the ledger updates —
 * Room's invalidation tracker is the whole mechanism (04 §5.1).
 *
 * That is also what the M2 exit criterion depends on. "Budgets reconcile
 * against ledger" holds because the spent figure is never computed here; it is
 * the trigger-maintained rollup, which `SchemaAssertionsTest` already proves
 * equals a direct sum over `expense`.
 */
class BudgetViewModel(
    private val budgets: BudgetRepository,
    private val categories: CategoryRepository,
    private val clock: Clock,
    initialPeriod: Period,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(
        BudgetUiState(
            period = initialPeriod,
            daysRemaining = initialPeriod.daysRemainingInclusive(LocalDate.now(clock)),
        ),
    )
    val state: StateFlow<BudgetUiState> = _state.asStateFlow()

    private var observeJob: Job? = null

    init {
        observe(initialPeriod)
    }

    /**
     * Re-points every flow at a new period.
     *
     * The old collection is cancelled rather than left running: the bars, the
     * copy-availability count and the day count are all period-scoped, and a
     * stale collector would race the new one to publish.
     */
    fun setPeriod(period: Period) {
        if (period == _state.value.period) return
        _state.update {
            it.copy(
                period = period,
                initialLoad = true,
                daysRemaining = period.daysRemainingInclusive(LocalDate.now(clock)),
            )
        }
        observe(period)
    }

    private fun observe(period: Period) {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            combine(
                budgets.observeBars(period),
                categories.observeTree(),
            ) { bars, tree -> bars to tree }
                .collect { (bars, tree) ->
                    val groups = BudgetSummary.build(
                        bars = bars.map {
                            BudgetSummary.LeafSpend(
                                id = it.id,
                                parentId = it.parentId,
                                name = it.name,
                                nature = it.nature,
                                limitMinor = it.limitMinor,
                                spentMinor = it.spentMinor,
                                isArchived = it.isArchived,
                            )
                        },
                        tree = tree,
                    )
                    val days = period.daysRemainingInclusive(LocalDate.now(clock))
                    val copyable = withContext(io) {
                        budgets.copyableFromPreviousPeriod(period).size
                    }

                    _state.update {
                        it.copy(
                            groups = groups,
                            alerts = BudgetAlerts.from(groups, days),
                            daysRemaining = days,
                            copyableCount = copyable,
                            initialLoad = false,
                        )
                    }
                }
        }
    }

    // --- the limit editor (FR-BUD-01) ---------------------------------------

    fun editLimit(categoryId: Long, categoryName: String) {
        viewModelScope.launch {
            val existing = withContext(io) { budgets.limitFor(categoryId, _state.value.period) }
            _state.update {
                it.copy(
                    editor = LimitEditor(
                        categoryId = categoryId,
                        categoryName = categoryName,
                        // Pre-filled with what is already set, so adjusting a
                        // limit does not mean retyping it.
                        input = existing?.let(::editableText).orEmpty(),
                        existing = existing,
                    ),
                )
            }
        }
    }

    fun onKey(key: KeypadKey) = _state.update { s ->
        val editor = s.editor ?: return@update s
        val next = when (key) {
            is KeypadKey.Digit -> editor.input.appendDigit(key.value)
            KeypadKey.DoubleZero -> editor.input.appendDigit('0').appendDigit('0')
            KeypadKey.Decimal ->
                if (editor.input.contains('.') || editor.input.isEmpty()) editor.input
                else editor.input + '.'
            KeypadKey.Backspace -> editor.input.dropLast(1)
            // A negative limit is meaningless, and FR-BUD-08 forbids it. The
            // key stays on the pad because it is shared with entry, but here
            // it does nothing.
            KeypadKey.Negate -> editor.input
        }
        s.copy(editor = editor.copy(input = next, error = null))
    }

    fun saveLimit(onSaved: () -> Unit) {
        val editor = _state.value.editor ?: return
        val amount = editor.amount
        if (amount == null || amount.paisa <= 0L) {
            _state.update { it.copy(editor = editor.copy(error = EntryError.ZERO_LIMIT)) }
            return
        }
        viewModelScope.launch {
            val outcome = withContext(io) {
                budgets.setLimit(editor.categoryId, _state.value.period, amount)
            }
            when (outcome) {
                is SaveOutcome.Saved -> {
                    _state.update { it.copy(editor = null) }
                    onSaved()
                }
                is SaveOutcome.Rejected ->
                    _state.update { it.copy(editor = editor.copy(error = outcome.error)) }
            }
        }
    }

    /**
     * Returns a leaf to the unbudgeted state — the only way to have no limit.
     *
     * [onCleared] carries the removed limit back so the screen can offer the
     * five seconds NFR-USE-03 requires of every destructive action.
     */
    fun clearLimit(onCleared: (Long, Money) -> Unit) {
        val editor = _state.value.editor ?: return
        viewModelScope.launch {
            val removed = withContext(io) {
                budgets.clearLimit(editor.categoryId, _state.value.period)
            }
            _state.update { it.copy(editor = null) }
            if (removed != null) onCleared(editor.categoryId, removed)
        }
    }

    fun undoClear(categoryId: Long, limit: Money) {
        viewModelScope.launch {
            withContext(io) { budgets.setLimit(categoryId, _state.value.period, limit) }
        }
    }

    fun dismissEditor() = _state.update { it.copy(editor = null) }

    // --- FR-BUD-04 ----------------------------------------------------------

    /**
     * Copies last period's limits into this one, filling only the gaps.
     *
     * @param onCopied receives the count and the leaves that were added, so the
     *   caller can offer Undo — which is why nothing is overwritten: an undo
     *   that had to restore replaced values would be a different, riskier
     *   operation.
     */
    fun copyFromLastMonth(onCopied: (Int, List<Long>) -> Unit) {
        viewModelScope.launch {
            val period = _state.value.period
            val added = withContext(io) { budgets.copyableFromPreviousPeriod(period) }
            if (added.isEmpty()) return@launch
            withContext(io) { budgets.copyFromPreviousPeriod(period) }
            onCopied(added.size, added)
        }
    }

    fun undoCopy(added: List<Long>) {
        viewModelScope.launch {
            withContext(io) { budgets.removeLimits(added, _state.value.period) }
        }
    }

    // --- internals ----------------------------------------------------------

    private fun String.appendDigit(c: Char): String {
        val decimals = substringAfter('.', "")
        if (contains('.') && decimals.length >= 2) return this
        if (length >= MAX_DIGITS) return this
        if (this == "0") return c.toString()
        return this + c
    }

    private fun editableText(money: Money): String {
        val whole = money.paisa / 100
        val fraction = (money.paisa % 100).toInt()
        return if (fraction == 0) whole.toString()
        else "$whole.${fraction.toString().padStart(2, '0')}"
    }

    private companion object {
        const val MAX_DIGITS = 10
    }
}
