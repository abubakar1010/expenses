package com.app.finance.ui.feature.entry

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.finance.core.money.Money
import com.app.finance.data.repo.AppMetaRepository
import com.app.finance.data.repo.CategoryRepository
import com.app.finance.data.repo.ExpenseRepository
import com.app.finance.domain.model.CategoryNode
import com.app.finance.domain.model.EntryError
import com.app.finance.domain.model.PaymentMethod
import com.app.finance.domain.model.SaveOutcome
import com.app.finance.ui.common.KeypadKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate

/** Which of the sheet's secondary pickers is open, if any. */
enum class EntrySheet { NONE, CATEGORY, METHOD, DATE, NOTE }

data class QuickAddUiState(
    /**
     * Raw keypad input, not yet money — "12", "12.", "12.5" are all valid
     * intermediate states that [Money.parseOrNull] refuses.
     */
    val input: String = "",
    val negative: Boolean = false,
    /** The full two-level tree, for the `More…` picker. */
    val tree: List<CategoryNode> = emptyList(),
    /** The six most-recently-used leaves (05 §5.6). */
    val chips: List<CategoryNode> = emptyList(),
    val allLeaves: List<CategoryNode> = emptyList(),
    val selectedCategoryId: Long? = null,
    val date: LocalDate,
    val today: LocalDate,
    val method: PaymentMethod = PaymentMethod.DEFAULT,
    val note: String? = null,
    val error: EntryError? = null,
    val saving: Boolean = false,
    val openSheet: EntrySheet = EntrySheet.NONE,
    /**
     * True once [QuickAddViewModel.start] has finished seeding the form.
     *
     * The category tree and the chip row arrive from their own flow and can
     * settle first, so without this there is a window where the sheet shows a
     * chip selection but still the *default* payment method rather than the
     * last-used one.
     */
    val seeded: Boolean = false,
    /** Null for a new entry; the row being edited otherwise (FR-EXP-07). */
    val editingId: Long? = null,
) {
    val isEditing: Boolean get() = editingId != null

    val amount: Money?
        get() = Money.parseOrNull(input)?.let { if (negative) -it.absoluteValue else it }

    val selectedCategory: CategoryNode?
        get() = allLeaves.firstOrNull { it.id == selectedCategoryId }

    /** The save button is enabled only when the write would actually succeed. */
    val canSave: Boolean
        get() = !saving && selectedCategoryId != null && amount?.isZero == false
}

/**
 * 05-ui-ux-guide.md §5.6 — three taps, under five seconds.
 *
 * The speed comes from defaults rather than from a faster form: the date is
 * today, the payment method is whatever was used last, and the six chips are
 * the most recently used categories. "The user should be able to log a typical
 * expense without changing a single default."
 *
 * The same sheet edits an existing expense (FR-EXP-07). One component means one
 * set of validation rules, one keypad, and one place to keep accessible.
 *
 * State is mirrored into [SavedStateHandle] so a half-typed expense survives
 * process death — NFR-REL-01 is about committed writes, but FR-APP-03 asks for
 * the in-progress screen too, and losing a typed amount to a background kill is
 * exactly the friction this product exists to remove.
 */
class QuickAddViewModel(
    private val expenses: ExpenseRepository,
    private val categories: CategoryRepository,
    private val meta: AppMetaRepository,
    private val clock: Clock,
    private val saved: SavedStateHandle = SavedStateHandle(),
    /**
     * Injected so tests can run the whole flow on a single deterministic
     * dispatcher. With a hardcoded `Dispatchers.IO`, cancelling a ViewModel in
     * teardown races the in-flight query it is still running, and the failure
     * lands in whichever test happens to run next.
     */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(
        QuickAddUiState(date = LocalDate.now(clock), today = LocalDate.now(clock)),
    )
    val state: StateFlow<QuickAddUiState> = _state.asStateFlow()

    /** Guards against re-seeding when the sheet recomposes. */
    private var started = false

    init {
        // The tree and the most-recently-used list are both observed, not read
        // once: saving an expense rewrites `app_meta`, and the chip row should
        // reflect that the next time the sheet opens without anyone having to
        // remember to refresh it.
        viewModelScope.launch {
            combine(
                categories.observeTree(),
                meta.observeRecentCategoryIds(),
            ) { tree, recent -> tree to recent }
                .collect { (tree, recent) ->
                    val leaves = tree.flatMap { it.activeChildren }
                    val byId = leaves.associateBy { it.id }
                    // Recently used first, then whatever else fills the six
                    // slots, so the row is never sparse on a fresh install.
                    val chips = (recent.mapNotNull(byId::get) + leaves)
                        .distinctBy { it.id }
                        .take(CHIP_COUNT)

                    _state.update {
                        it.copy(
                            tree = tree,
                            chips = chips,
                            allLeaves = leaves,
                            selectedCategoryId = it.selectedCategoryId?.takeIf(byId::containsKey)
                                ?: chips.firstOrNull()?.id,
                        )
                    }
                }
        }
    }

    /**
     * Seeds the form. Call on every open.
     *
     * This is also what makes a reused ViewModel safe: the sheet's owner is the
     * Activity, so the instance outlives a dismissal, and without an explicit
     * seed a reopened sheet would still show the last amount typed.
     */
    fun start(expenseId: Long?) {
        if (started) return
        started = true

        viewModelScope.launch {
            val today = LocalDate.now(clock)
            val restored = saved.get<String>(KEY_INPUT)
            val editing = withContext(io) { expenseId?.let { expenses.byId(it) } }
            val lastMethod = withContext(io) { meta.lastPaymentMethod() }
            val lastCategory = withContext(io) { meta.lastCategoryId() }

            _state.update { current ->
                when {
                    // Editing wins over any restored draft: the user asked for
                    // this specific row.
                    editing != null -> current.copy(
                        input = Money(editing.amountMinor).absoluteValue.editableText(),
                        negative = editing.amountMinor < 0,
                        selectedCategoryId = editing.categoryId,
                        date = LocalDate.ofEpochDay(editing.spentOn),
                        today = today,
                        method = PaymentMethod.fromCode(editing.paymentMethod),
                        note = editing.note,
                        editingId = editing.id,
                        seeded = true,
                    )

                    restored != null -> current.copy(
                        input = restored,
                        negative = saved.get<Boolean>(KEY_NEGATIVE) ?: false,
                        selectedCategoryId = saved.get<Long>(KEY_CATEGORY)
                            ?: current.selectedCategoryId,
                        date = saved.get<Long>(KEY_DATE)?.let(LocalDate::ofEpochDay) ?: today,
                        today = today,
                        method = saved.get<Int>(KEY_METHOD)
                            ?.let(PaymentMethod::fromCode) ?: lastMethod,
                        note = saved.get<String>(KEY_NOTE),
                        seeded = true,
                    )

                    else -> current.copy(
                        // The leaf list arrives from a Flow that may not have
                        // emitted yet. Validating against an empty list here
                        // would silently discard the last-used category on a
                        // cold open and fall back to the first chip — so when
                        // the tree is not loaded the stored id is trusted, and
                        // the collector above drops it if the category turns
                        // out to be archived or gone.
                        selectedCategoryId = lastCategory
                            ?.takeIf { id ->
                                current.allLeaves.isEmpty() ||
                                    current.allLeaves.any { it.id == id }
                            }
                            ?: current.selectedCategoryId,
                        date = today,
                        today = today,
                        method = lastMethod,
                        seeded = true,
                    )
                }
            }
        }
    }

    fun onKey(key: KeypadKey) {
        _state.update { s ->
            when (key) {
                is KeypadKey.Digit -> s.copy(input = s.input.appendDigit(key.value), error = null)
                KeypadKey.DoubleZero ->
                    s.copy(input = s.input.appendDigit('0').appendDigit('0'), error = null)
                KeypadKey.Decimal ->
                    if (s.input.contains('.') || s.input.isEmpty()) s
                    else s.copy(input = s.input + '.', error = null)
                KeypadKey.Backspace -> s.copy(input = s.input.dropLast(1), error = null)
                // A refund is the same entry with the sign flipped, not a
                // different kind of record (FR-EXP-06).
                KeypadKey.Negate -> s.copy(negative = !s.negative, error = null)
            }
        }
        persist()
    }

    fun selectCategory(id: Long) {
        _state.update { it.copy(selectedCategoryId = id, error = null, openSheet = EntrySheet.NONE) }
        persist()
    }

    fun setMethod(method: PaymentMethod) {
        _state.update { it.copy(method = method, openSheet = EntrySheet.NONE) }
        persist()
    }

    /**
     * FR-EXP-02. Future dates are refused: a future-dated row posts straight
     * into the period rollup, so it would inflate this month's spend and deflate
     * safe-to-spend with money that has not left the user's hand. The schema
     * already has the right mechanism for money that has not happened yet —
     * `status = pending` — and that arrives with recurring rules at P1.
     */
    fun setDate(date: LocalDate) {
        if (date.isAfter(_state.value.today)) {
            _state.update { it.copy(error = EntryError.FUTURE_DATE, openSheet = EntrySheet.NONE) }
            return
        }
        _state.update { it.copy(date = date, error = null, openSheet = EntrySheet.NONE) }
        persist()
    }

    fun setNote(note: String?) {
        _state.update { it.copy(note = note?.trim()?.ifBlank { null }) }
        persist()
    }

    fun openSheet(sheet: EntrySheet) = _state.update { it.copy(openSheet = sheet) }

    fun dismissSheet() = _state.update { it.copy(openSheet = EntrySheet.NONE) }

    /** The write path — 04 §5.1 and §8. Everything runs on IO. */
    fun save(onSaved: () -> Unit) {
        val snapshot = _state.value
        val amount = snapshot.amount
        val categoryId = snapshot.selectedCategoryId

        if (amount == null || amount.isZero) {
            _state.update { it.copy(error = EntryError.ZERO_AMOUNT) }
            return
        }
        if (categoryId == null) {
            _state.update { it.copy(error = EntryError.CATEGORY_NOT_FOUND) }
            return
        }

        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val outcome = withContext(io) {
                val editingId = snapshot.editingId
                if (editingId == null) {
                    expenses.insert(
                        amount = amount,
                        categoryId = categoryId,
                        spentOn = snapshot.date,
                        method = snapshot.method,
                        note = snapshot.note,
                    )
                } else {
                    expenses.update(
                        id = editingId,
                        amount = amount,
                        categoryId = categoryId,
                        spentOn = snapshot.date,
                        method = snapshot.method,
                        note = snapshot.note,
                    )
                }
            }
            when (outcome) {
                is SaveOutcome.Saved -> {
                    // `reset`, not `clearDraft`. The two are not
                    // interchangeable and the difference was a defect.
                    //
                    // `clearDraft` only empties the `SavedStateHandle`; it
                    // leaves `_state` and `started` exactly as they were. But
                    // this ViewModel belongs to the **Activity** (see `start`),
                    // so the instance survives the sheet closing, and the sheet
                    // is closed here by the caller setting its state — which
                    // does not go through `onDismissRequest`, the only other
                    // place `reset` was called from.
                    //
                    // So after a save the instance kept `saving = true` (the
                    // Save button was dead on the next open), kept `started`
                    // (so `start` returned early and never re-seeded), and kept
                    // `editingId` — which sends the *next* entry down the
                    // `update` branch above. Adding an expense after editing one
                    // silently rewrote the edited row.
                    reset()
                    onSaved()
                }
                is SaveOutcome.Rejected ->
                    _state.update { it.copy(saving = false, error = outcome.error) }
            }
        }
    }

    /** Edit mode only. Returns the removed row so the caller can offer Undo. */
    fun delete(onDeleted: (Long) -> Unit) {
        val id = _state.value.editingId ?: return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val removed = runCatching { withContext(io) { expenses.delete(id) } }
            if (removed.isFailure) {
                // Leaving `saving` set would disable the sheet for good, on an
                // instance that outlives this screen.
                _state.update { it.copy(saving = false, error = EntryError.CONSTRAINT_VIOLATION) }
                return@launch
            }
            reset()
            onDeleted(id)
        }
    }

    /**
     * Returns the sheet to the state a fresh open expects.
     *
     * Called on every way out — dismissed, saved, or deleted. Anything that
     * finishes with the sheet must come through here; see the note in `save`
     * for what happens when one path does not.
     */
    fun reset() {
        started = false
        clearDraft()
        val today = LocalDate.now(clock)
        _state.update {
            QuickAddUiState(
                tree = it.tree,
                chips = it.chips,
                allLeaves = it.allLeaves,
                selectedCategoryId = it.chips.firstOrNull()?.id,
                date = today,
                today = today,
                method = it.method,
            )
        }
    }

    // ------------------------------------------------------------- internals

    private fun persist() {
        val s = _state.value
        saved[KEY_INPUT] = s.input
        saved[KEY_NEGATIVE] = s.negative
        saved[KEY_CATEGORY] = s.selectedCategoryId
        saved[KEY_DATE] = s.date.toEpochDay()
        saved[KEY_METHOD] = s.method.code
        saved[KEY_NOTE] = s.note
    }

    private fun clearDraft() {
        listOf(KEY_INPUT, KEY_NEGATIVE, KEY_CATEGORY, KEY_DATE, KEY_METHOD, KEY_NOTE)
            .forEach { saved.remove<Any>(it) }
    }

    private fun String.appendDigit(c: Char): String {
        // Two decimal places is all paisa has; further digits are ignored
        // rather than accepted and then silently truncated at parse time.
        val decimals = substringAfter('.', "")
        if (contains('.') && decimals.length >= 2) return this
        if (length >= MAX_DIGITS) return this
        if (this == "0") return c.toString()
        return this + c
    }

    private companion object {
        const val CHIP_COUNT = 6

        /**
         * Ten digits caps an entry at ৳99,999,999.99, which is comfortably
         * inside `Long` paisa and past any plausible household expense. The SRS
         * states no maximum; this exists so a stuck key cannot overflow.
         */
        const val MAX_DIGITS = 10

        const val KEY_INPUT = "qa_input"
        const val KEY_NEGATIVE = "qa_negative"
        const val KEY_CATEGORY = "qa_category"
        const val KEY_DATE = "qa_date"
        const val KEY_METHOD = "qa_method"
        const val KEY_NOTE = "qa_note"
    }
}

/** Renders a stored amount back into the keypad's raw text form. */
private fun Money.editableText(): String {
    val whole = paisa / 100
    val fraction = (paisa % 100).toInt()
    return if (fraction == 0) whole.toString()
    else "$whole.${fraction.toString().padStart(2, '0')}"
}
