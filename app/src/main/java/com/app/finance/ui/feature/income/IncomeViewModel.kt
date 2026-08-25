package com.app.finance.ui.feature.income

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.finance.core.money.Money
import com.app.finance.core.time.Period
import com.app.finance.data.db.dao.IncomeCellRow
import com.app.finance.data.db.dao.IncomeEntryWithSource
import com.app.finance.data.repo.IncomeRepository
import com.app.finance.data.db.entity.IncomeEntryEntity
import com.app.finance.data.db.entity.IncomeSourceEntity
import com.app.finance.domain.model.EntryError
import com.app.finance.domain.model.IncomeScope
import com.app.finance.domain.model.SaveOutcome
import com.app.finance.domain.usecase.IncomeBreakdown
import com.app.finance.domain.usecase.IncomeCell
import com.app.finance.domain.usecase.IncomeSummary
import com.app.finance.domain.usecase.SourceOption
import com.app.finance.domain.usecase.StableCoverage
import com.app.finance.ui.common.KeypadKey
import com.app.finance.ui.common.Undoable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Which unit the screen is showing. The year is the default — 05 §5.7. */
enum class ScopeKind { YEAR, MONTH, RANGE }

/** The income entry sheet, open on a new or existing entry. */
data class IncomeEditor(
    val input: String = "",
    val sourceName: String = "",
    val date: LocalDate,
    val today: LocalDate,
    val note: String? = null,
    val error: EntryError? = null,
    /** Null for a new entry; the row being edited otherwise (FR-IE-08). */
    val editingId: Long? = null,
    val openSheet: IncomeSheet = IncomeSheet.NONE,
) {
    val isEditing: Boolean get() = editingId != null

    val amount: Money? get() = Money.parseOrNull(input)

    /**
     * FR-IE-03 — greater than zero — plus a source, which FR-IE-01 makes
     * mandatory. The button is enabled only when the write would actually
     * succeed, so a rejection is a bug rather than routine.
     */
    val canSave: Boolean
        get() = sourceName.isNotBlank() && amount?.let { it.paisa > 0L } == true
}

enum class IncomeSheet { NONE, DATE, NOTE }

data class IncomeUiState(
    val period: Period,
    /** From the injected clock, so the range picker clamps where the rest does. */
    val today: LocalDate,
    val scopeKind: ScopeKind = ScopeKind.YEAR,
    val rangeFrom: LocalDate? = null,
    val rangeTo: LocalDate? = null,
    /** FR-IE-05's subset. Empty means every source. */
    val sourceIds: Set<Long> = emptySet(),
    val summary: IncomeSummary = EMPTY_SUMMARY,
    /** Whole percent, or null when there is nothing to cover — see [StableCoverage]. */
    val coverage: Int? = null,
    /** The calendar year, for 05 §9's zero-income month line. Month scope only. */
    val yearTotal: Money = Money.ZERO,
    val entries: List<IncomeEntryWithSource> = emptyList(),
    val sources: List<IncomeSourceEntity> = emptyList(),
    val initialLoad: Boolean = true,
    val editor: IncomeEditor? = null,
    val filterSheetOpen: Boolean = false,
    /**
     * Deletions still inside their undo window — FR-IE-08, NFR-USE-03.
     *
     * A queue for the reason [com.app.finance.ui.feature.ledger.LedgerUiState
     * .undoQueue] is one: a single slot was overwritten by a second delete
     * taken within the five seconds, and the row it held is the only copy left
     * once the delete has happened.
     */
    val undoQueue: List<Undoable<IncomeEntryEntity>> = emptyList(),
) {
    val scope: IncomeScope
        get() = when (scopeKind) {
            ScopeKind.YEAR -> IncomeScope.Year(period)
            ScopeKind.MONTH -> IncomeScope.Month(period)
            ScopeKind.RANGE -> IncomeScope.Range(
                from = rangeFrom ?: period.firstDay(),
                to = rangeTo ?: period.lastDay(),
            )
        }

    /** The twelve bars, in order — the labels come from these. */
    val trendPeriods: List<Period> get() = scope.trendPeriods()

    /** Nothing earned in this window, once the first read has landed. */
    val isEmpty: Boolean get() = !initialLoad && summary.isEmpty

    val activeFilterCount: Int
        get() = (if (sourceIds.isEmpty()) 0 else 1) + (if (scopeKind == ScopeKind.RANGE) 1 else 0)

    /**
     * What the filter sheet offers — FR-IE-05's "any subset of sources".
     *
     * Active sources **plus** any source with income in this window, which is
     * how an archived one stays reachable. FR-IS-04 keeps archived sources out
     * of *entry pickers*; a filter is not one, and the same requirement puts
     * them squarely in historical reports. Without the second half, the sheet
     * cannot even represent a filter set by tapping an archived source's
     * breakdown row — no chip selected, and "Any source" unselected too.
     */
    val filterSources: List<SourceOption>
        get() {
            val active = sources.map { SourceOption(it.id, it.name) }
            val seen = active.mapTo(HashSet()) { it.id }
            return active + summary.presentSources.filterNot { it.id in seen }
        }

    /**
     * 05 §9 — "Nothing recorded in August. Your year is at ৳5,84,000".
     *
     * A month with no income is not a failure and must not be reported as one.
     * It needs a year behind it to reframe to, though: with nothing recorded
     * anywhere, the plain invitation is the better sentence.
     */
    val showsEmptyMonthReframe: Boolean
        get() = scopeKind == ScopeKind.MONTH &&
            activeFilterCount == 0 &&
            yearTotal.paisa > 0L

    companion object {
        val EMPTY_SUMMARY = IncomeSummary(
            total = Money.ZERO,
            stableTotal = Money.ZERO,
            shares = emptyList(),
            trend = LongArray(IncomeScope.TREND_LENGTH),
        )
    }
}

/**
 * The income screen — FR-IS-01 … FR-IS-06 and FR-IE-01 … FR-IE-08.
 *
 * Follows `BudgetViewModel` exactly: `viewModelFactory`, injected [clock] and
 * [io], `MutableStateFlow` + `asStateFlow`, one `combine` per scope, everything
 * derived and nothing cached. Saving an entry anywhere moves every figure here
 * without a refresh, because Room's invalidation tracker is the whole mechanism
 * (04 §5.1).
 *
 * **The period is not owned here.** It is hoisted above the `NavHost` and shared
 * with Budget and Dashboard, so stepping the income year to 2025 puts the whole
 * app on the same month of 2025 rather than inventing a second notion of "when".
 * [onPeriodChange] on the screen is how that write-back happens; the *scope* —
 * year, month or range — is this screen's alone.
 */
class IncomeViewModel(
    private val income: IncomeRepository,
    private val clock: Clock,
    initialPeriod: Period,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state =
        MutableStateFlow(IncomeUiState(period = initialPeriod, today = LocalDate.now(clock)))
    val state: StateFlow<IncomeUiState> = _state.asStateFlow()

    private var observeJob: Job? = null

    init {
        observe()
    }

    fun setPeriod(period: Period) {
        if (period == _state.value.period) return
        _state.update { it.copy(period = period, initialLoad = true) }
        observe()
    }

    fun setScopeKind(kind: ScopeKind) {
        if (kind == _state.value.scopeKind) return
        _state.update { it.copy(scopeKind = kind, initialLoad = true) }
        observe()
    }

    /** FR-IE-04's third total. Selecting a range implies the RANGE scope. */
    fun setRange(from: LocalDate, to: LocalDate) {
        _state.update {
            it.copy(
                scopeKind = ScopeKind.RANGE,
                // Tolerating a reversed pair here rather than in the picker:
                // the two fields are set independently, so "to before from" is
                // a state the user passes through, not one they chose.
                rangeFrom = minOf(from, to),
                rangeTo = maxOf(from, to),
                initialLoad = true,
            )
        }
        observe()
    }

    /**
     * The period arrows in Range scope — FR-IE-04's third total, stepped.
     *
     * A range is absolute, so stepping the shared period moved Budget and
     * Dashboard while this screen held perfectly still: a control that looked
     * dead and had an invisible effect elsewhere. It shifts the range by its
     * own span instead, which makes like-for-like comparison one tap, and it
     * does **not** write back — Range was always the transient scope.
     *
     * Clamped at today, because the range picker refuses a future date and the
     * arrows must not produce a window it would have rejected.
     */
    fun stepRange(forward: Boolean) {
        val snapshot = _state.value
        val scope = snapshot.scope as? IncomeScope.Range ?: return
        val span = ChronoUnit.DAYS.between(scope.from, scope.to) + 1
        val shifted = if (forward) scope.to.plusDays(span) else scope.to.minusDays(span)
        val to = minOf(shifted, snapshot.today)
        setRange(to.minusDays(span - 1), to)
    }

    fun setSources(ids: Set<Long>) {
        if (ids == _state.value.sourceIds) return
        _state.update { it.copy(sourceIds = ids, initialLoad = true) }
        observe()
    }

    /** Tapping a breakdown row narrows to that source, or clears it again. */
    fun toggleSource(id: Long) {
        val current = _state.value.sourceIds
        setSources(if (current == setOf(id)) emptySet() else setOf(id))
    }

    fun clearFilters() {
        _state.update {
            it.copy(
                sourceIds = emptySet(),
                scopeKind = ScopeKind.YEAR,
                rangeFrom = null,
                rangeTo = null,
                initialLoad = true,
            )
        }
        observe()
    }

    fun openFilters() = _state.update { it.copy(filterSheetOpen = true) }

    fun dismissFilters() = _state.update { it.copy(filterSheetOpen = false) }

    // --- the read ------------------------------------------------------------

    private fun observe() {
        val snapshot = _state.value
        val scope = snapshot.scope
        val window = scope.window
        val trendWindow = scope.trendWindow
        val trendPeriods = scope.trendPeriods()
        val sourceIds = snapshot.sourceIds

        // FR-IE-07's twelve bars are twelve *months*, whatever the hero total
        // is currently totalling. Only in Year scope are those the same span —
        // in Month scope they differ by eleven, and reusing one read for both
        // drew eleven guaranteed-zero bars. Subscribed only when they differ,
        // so the default scope still issues exactly one aggregate query.
        val cells = income.observeCells(window)
        val trendCells =
            if (trendWindow == window) flowOf<List<IncomeCellRow>?>(null)
            else income.observeCells(trendWindow)

        // 05 §9's reframe needs the calendar year, which a month's own window
        // cannot supply — and nothing else on the screen wants it.
        val yearTotal =
            if (scope is IncomeScope.Month) income.observeYearTotal(snapshot.period)
            else flowOf(0L)

        val figures = combine(cells, trendCells, yearTotal) { rows, trendRows, year ->
            Figures(
                summary = IncomeBreakdown.build(
                    cells = rows.toCells(),
                    trendCells = (trendRows ?: rows).toCells(),
                    sourceIds = sourceIds,
                    trendPeriods = trendPeriods,
                ),
                yearTotal = Money(year),
            )
        }

        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            combine(
                figures,
                income.observeExpenseTotal(window),
                income.observeEntries(window, sourceIds),
                income.observeActiveSources(),
            ) { computed, spend, entries, sources ->
                Reading(
                    summary = computed.summary,
                    coverage = StableCoverage.percent(computed.summary.stableTotal, Money(spend)),
                    yearTotal = computed.yearTotal,
                    entries = entries,
                    sources = sources,
                )
            }.collect { reading ->
                _state.update {
                    it.copy(
                        summary = reading.summary,
                        coverage = reading.coverage,
                        yearTotal = reading.yearTotal,
                        entries = reading.entries,
                        sources = reading.sources,
                        initialLoad = false,
                    )
                }
            }
        }
    }

    private fun List<IncomeCellRow>.toCells(): List<IncomeCell> = map {
        IncomeCell(
            periodYm = it.periodYm,
            sourceId = it.sourceId,
            sourceName = it.sourceName,
            kind = it.kind,
            totalMinor = it.totalMinor,
        )
    }

    private data class Figures(val summary: IncomeSummary, val yearTotal: Money)

    private data class Reading(
        val summary: IncomeSummary,
        val coverage: Int?,
        val yearTotal: Money,
        val entries: List<IncomeEntryWithSource>,
        val sources: List<IncomeSourceEntity>,
    )

    // --- the entry sheet (FR-IE-01, FR-IE-08) --------------------------------

    fun addEntry() {
        val today = LocalDate.now(clock)
        _state.update { it.copy(editor = IncomeEditor(date = today, today = today)) }
    }

    fun editEntry(row: IncomeEntryWithSource) {
        val today = LocalDate.now(clock)
        _state.update {
            it.copy(
                editor = IncomeEditor(
                    input = editableText(Money(row.entry.amountMinor)),
                    sourceName = row.sourceName,
                    date = LocalDate.ofEpochDay(row.entry.earnedOn),
                    today = today,
                    note = row.entry.note,
                    editingId = row.entry.id,
                ),
            )
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
            // Income has no refund case — the column's CHECK is
            // `amount_minor > 0` and FR-IE-03 agrees. The key stays on the pad
            // because it is shared with expense entry; here it does nothing.
            KeypadKey.Negate -> editor.input
        }
        s.copy(editor = editor.copy(input = next, error = null))
    }

    fun setSourceName(name: String) = _state.update { s ->
        s.copy(editor = s.editor?.copy(sourceName = name, error = null))
    }

    fun setDate(date: LocalDate) = _state.update { s ->
        val editor = s.editor ?: return@update s
        // The same clamp the expense sheet applies: a future date would post
        // straight into the period rollup and inflate income that has not
        // arrived. Pending status is the mechanism for future money and it
        // comes with recurring rules at P1.
        if (date.isAfter(editor.today)) {
            s.copy(editor = editor.copy(error = EntryError.FUTURE_DATE, openSheet = IncomeSheet.NONE))
        } else {
            s.copy(editor = editor.copy(date = date, error = null, openSheet = IncomeSheet.NONE))
        }
    }

    fun setNote(note: String?) = _state.update { s ->
        s.copy(editor = s.editor?.copy(note = note?.ifBlank { null }, openSheet = IncomeSheet.NONE))
    }

    fun openSheet(sheet: IncomeSheet) = _state.update { s ->
        s.copy(editor = s.editor?.copy(openSheet = sheet))
    }

    fun dismissEditor() = _state.update { it.copy(editor = null) }

    fun saveEntry(onSaved: () -> Unit) {
        val editor = _state.value.editor ?: return
        val amount = editor.amount
        if (amount == null || amount.paisa <= 0L) {
            _state.update { it.copy(editor = editor.copy(error = EntryError.NON_POSITIVE_INCOME)) }
            return
        }
        viewModelScope.launch {
            val outcome = withContext(io) {
                if (editor.editingId == null) {
                    income.saveEntry(amount, editor.sourceName, editor.date, editor.note)
                } else {
                    income.updateEntry(
                        editor.editingId,
                        amount,
                        editor.sourceName,
                        editor.date,
                        editor.note,
                    )
                }
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

    /** FR-IE-08's delete half, with the five-second undo NFR-USE-03 requires. */
    fun deleteEntry(id: Long) {
        viewModelScope.launch {
            val row = withContext(io) { income.deleteEntry(id) } ?: return@launch
            val undoId = ++nextUndoId
            _state.update { it.copy(editor = null, undoQueue = it.undoQueue + Undoable(undoId, row)) }
        }
    }

    // --- the undo queue (NFR-USE-03) ----------------------------------------

    /** See [com.app.finance.ui.feature.ledger.LedgerViewModel] — main thread only. */
    private var nextUndoId = 0L

    /** Puts back the entry [id] names, then releases it. */
    fun undo(id: Long) {
        val item = _state.value.undoQueue.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            withContext(io) { income.restoreEntry(item.payload) }
            dropUndo(id)
        }
    }

    /** The window closed without a tap: the deletion stands. */
    fun dropUndo(id: Long) =
        _state.update { state -> state.copy(undoQueue = state.undoQueue.filterNot { it.id == id }) }

    // --- internals -----------------------------------------------------------

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
