package com.app.finance.ui.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.finance.core.money.Money
import com.app.finance.data.db.dao.RuleWithTarget
import com.app.finance.data.db.entity.CategoryEntity
import com.app.finance.data.db.entity.IncomeSourceEntity
import com.app.finance.data.db.entity.PersonEntity
import com.app.finance.data.repo.CategoryRepository
import com.app.finance.data.repo.DeletedRule
import com.app.finance.data.repo.IncomeRepository
import com.app.finance.data.repo.PersonRepository
import com.app.finance.data.repo.RecurringRepository
import com.app.finance.domain.model.EntryError
import com.app.finance.domain.model.Frequency
import com.app.finance.domain.model.RuleTarget
import com.app.finance.domain.model.SaveOutcome
import com.app.finance.domain.model.SplitDraft
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

/** The rule editor, open on a new rule. Editing an existing one is P2. */
data class RuleEditor(
    val target: RuleTarget = RuleTarget.EXPENSE,
    val targetId: Long? = null,
    val input: String = "",
    val frequency: Frequency = Frequency.MONTHLY,
    val anchorDay: Int = 1,
    /**
     * PRD §6.5 — off by default, "because silently generated transactions that
     * didn't actually happen destroy trust in the ledger faster than any other
     * bug".
     */
    val autoPost: Boolean = false,
    /**
     * FR-REC-06 — who shares the bill, the split sheet's own [SplitDraft].
     *
     * Spending rules only; choosing *money coming in* clears it.
     */
    val split: SplitDraft = SplitDraft.NONE,
    val splitOpen: Boolean = false,
    val error: EntryError? = null,
) {
    /** What was typed — **the whole bill**, as on Quick Add. */
    val amount: Money? get() = Money.parseOrNull(input)

    /** What every generated expense will store: the bill less the others' shares. */
    val yourShare: Money? get() = split.yourShare(amount)

    val canSave: Boolean
        get() = targetId != null &&
            yourShare?.let { it.paisa > 0L && split.split(amount).validate(it) == null } == true
}

data class RecurringUiState(
    val rules: List<RuleWithTarget> = emptyList(),
    /** Leaves only — a rule posts an expense, and FR-EXP-04 says where. */
    val categories: List<CategoryEntity> = emptyList(),
    val sources: List<IncomeSourceEntity> = emptyList(),
    /** Everybody on file; [splitCandidates] narrows it for the sheet. */
    val people: List<PersonEntity> = emptyList(),
    val loading: Boolean = true,
    val editor: RuleEditor? = null,
) {
    val isEmpty: Boolean get() = !loading && rules.isEmpty()

    /** Active people, plus anybody [editor]'s split already names — FR-CAT-08's rule. */
    fun splitCandidates(editor: RuleEditor): List<PersonEntity> =
        people.filter { !it.isArchived || it.id in editor.split.participants }
}

/**
 * Repeating entries — FR-REC-01, and the surface for FR-REC-02's `auto_post`.
 *
 * A detail route off Settings rather than a primary screen: PRD §7 puts
 * recurring rules at P1, and a bottom-bar slot is worth more to something the
 * user opens daily. The entries the rules generate appear where transactions
 * live — at the top of the ledger — which is the part that matters.
 */
class RecurringViewModel(
    private val recurring: RecurringRepository,
    categories: CategoryRepository,
    income: IncomeRepository,
    private val people: PersonRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(RecurringUiState())
    val state: StateFlow<RecurringUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                recurring.observeRules(),
                categories.observeSelectableLeaves(),
                income.observeActiveSources(),
            ) { rules, leaves, sources -> Triple(rules, leaves, sources) }
                .collect { (rules, leaves, sources) ->
                    _state.update {
                        it.copy(
                            rules = rules,
                            categories = leaves,
                            sources = sources,
                            loading = false,
                        )
                    }
                }
        }
        // `observeAll`, not `observeActive`: the sheet keeps anybody the split
        // already names — see `RecurringUiState.splitCandidates`.
        viewModelScope.launch {
            people.observeAll().collect { rows -> _state.update { it.copy(people = rows) } }
        }
    }

    // --- the editor ----------------------------------------------------------

    fun add() = _state.update { it.copy(editor = RuleEditor()) }

    fun dismissEditor() = _state.update { it.copy(editor = null) }

    fun setTarget(target: RuleTarget) = _state.update { s ->
        // The chosen target is cleared with the kind: a category id means
        // nothing to an income rule, and carrying it over would let the XOR
        // `CHECK` reject a save the user thought was fine.
        // The split goes with it too: income is never shared, and
        // `trg_rule_payer_spending_only` would refuse the rule if it were.
        s.copy(
            editor = s.editor?.copy(
                target = target,
                targetId = null,
                split = if (target == RuleTarget.EXPENSE) s.editor.split else SplitDraft.NONE,
                splitOpen = false,
                error = null,
            ),
        )
    }

    // --- the split (FR-REC-06) -----------------------------------------------
    //
    // `QuickAddViewModel`'s intents, over the same `SplitDraft` transitions.

    fun openSplit() = _state.update { s -> s.copy(editor = s.editor?.copy(splitOpen = true)) }

    fun dismissSplit() = _state.update { s -> s.copy(editor = s.editor?.copy(splitOpen = false)) }

    fun togglePerson(personId: Long) = updateSplit { draft, _ -> draft.toggle(personId) }

    fun paidBy(personId: Long) = updateSplit { draft, _ -> draft.paidBy(personId) }

    fun setPaidByOther(theyPaid: Boolean) = updateSplit { draft, _ -> draft.paidByOther(theyPaid) }

    fun setSplitEvenly(evenly: Boolean) = updateSplit { draft, bill -> draft.evenly(evenly, bill) }

    fun setShare(personId: Long, amount: Money?) = updateSplit { draft, _ -> draft.share(personId, amount) }

    fun clearSplit() = updateSplit { _, _ -> SplitDraft.NONE }

    /** Adds somebody by name and selects them — `QuickAddViewModel.addPerson`. */
    fun addPerson(name: String) {
        viewModelScope.launch {
            when (val outcome = withContext(io) { people.findOrCreate(name) }) {
                is SaveOutcome.Saved -> updateSplit { draft, _ -> draft.including(outcome.id) }
                is SaveOutcome.Rejected ->
                    _state.update { s -> s.copy(editor = s.editor?.copy(error = outcome.error)) }
            }
        }
    }

    private fun updateSplit(block: (SplitDraft, Money?) -> SplitDraft) = _state.update { s ->
        s.copy(editor = s.editor?.let { e -> e.copy(split = block(e.split, e.amount), error = null) })
    }

    fun setTargetId(id: Long) = _state.update { s ->
        s.copy(editor = s.editor?.copy(targetId = id, error = null))
    }

    fun setFrequency(frequency: Frequency) = _state.update { s ->
        s.copy(editor = s.editor?.copy(frequency = frequency))
    }

    fun setAnchorDay(day: Int) = _state.update { s ->
        s.copy(editor = s.editor?.copy(anchorDay = day))
    }

    fun setAutoPost(autoPost: Boolean) = _state.update { s ->
        s.copy(editor = s.editor?.copy(autoPost = autoPost))
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
            // A repeating refund is not a thing anyone has asked for, and the
            // column's CHECK forbids zero either way.
            KeypadKey.Negate -> editor.input
        }
        s.copy(editor = editor.copy(input = next, error = null))
    }

    fun submit(onSaved: () -> Unit) {
        val editor = _state.value.editor ?: return
        val bill = editor.amount
        val yourShare = editor.yourShare
        val targetId = editor.targetId
        if (bill == null || yourShare == null || targetId == null || bill.paisa <= 0L) {
            _state.update { it.copy(editor = editor.copy(error = EntryError.ZERO_AMOUNT)) }
            return
        }
        viewModelScope.launch {
            val outcome = withContext(io) {
                recurring.createRule(
                    target = editor.target,
                    targetId = targetId,
                    // Your share, as every expense stores it; the repository
                    // refuses a split that leaves you nothing, with the reason.
                    amount = yourShare,
                    frequency = editor.frequency,
                    anchorDay = editor.anchorDay,
                    autoPost = editor.autoPost,
                    split = editor.split.split(bill),
                )
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

    // --- the list ------------------------------------------------------------

    fun setActive(id: Long, active: Boolean) {
        viewModelScope.launch { withContext(io) { recurring.setActive(id, active) } }
    }

    /**
     * NFR-USE-03 — "every destructive action is undoable for at least 5 seconds
     * via snackbar", and deleting a rule is one. [onDeleted] carries the row
     * back so the screen can offer it.
     */
    fun delete(id: Long, onDeleted: (String, DeletedRule) -> Unit) {
        viewModelScope.launch {
            val name = _state.value.rules
                .firstOrNull { it.rule.id == id }?.targetName.orEmpty()
            val row = withContext(io) { recurring.deleteRule(id) } ?: return@launch
            onDeleted(name, row)
        }
    }

    fun undoDelete(rule: DeletedRule) {
        viewModelScope.launch { withContext(io) { recurring.restoreRule(rule) } }
    }

    private fun String.appendDigit(c: Char): String {
        val decimals = substringAfter('.', "")
        if (contains('.') && decimals.length >= 2) return this
        if (length >= MAX_DIGITS) return this
        if (this == "0") return c.toString()
        return this + c
    }

    private companion object {
        const val MAX_DIGITS = 10
    }
}
