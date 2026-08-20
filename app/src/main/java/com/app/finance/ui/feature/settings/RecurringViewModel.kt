package com.app.finance.ui.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.finance.core.money.Money
import com.app.finance.data.db.dao.RuleWithTarget
import com.app.finance.data.db.entity.CategoryEntity
import com.app.finance.data.db.entity.IncomeSourceEntity
import com.app.finance.data.db.entity.RecurringRuleEntity
import com.app.finance.data.repo.CategoryRepository
import com.app.finance.data.repo.IncomeRepository
import com.app.finance.data.repo.RecurringRepository
import com.app.finance.domain.model.EntryError
import com.app.finance.domain.model.Frequency
import com.app.finance.domain.model.RuleTarget
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
    val error: EntryError? = null,
) {
    val amount: Money? get() = Money.parseOrNull(input)

    val canSave: Boolean
        get() = targetId != null && amount?.let { it.paisa > 0L } == true
}

data class RecurringUiState(
    val rules: List<RuleWithTarget> = emptyList(),
    /** Leaves only — a rule posts an expense, and FR-EXP-04 says where. */
    val categories: List<CategoryEntity> = emptyList(),
    val sources: List<IncomeSourceEntity> = emptyList(),
    val loading: Boolean = true,
    val editor: RuleEditor? = null,
) {
    val isEmpty: Boolean get() = !loading && rules.isEmpty()
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
    }

    // --- the editor ----------------------------------------------------------

    fun add() = _state.update { it.copy(editor = RuleEditor()) }

    fun dismissEditor() = _state.update { it.copy(editor = null) }

    fun setTarget(target: RuleTarget) = _state.update { s ->
        // The chosen target is cleared with the kind: a category id means
        // nothing to an income rule, and carrying it over would let the XOR
        // `CHECK` reject a save the user thought was fine.
        s.copy(editor = s.editor?.copy(target = target, targetId = null, error = null))
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
        val amount = editor.amount
        val targetId = editor.targetId
        if (amount == null || targetId == null || amount.paisa <= 0L) {
            _state.update { it.copy(editor = editor.copy(error = EntryError.ZERO_AMOUNT)) }
            return
        }
        viewModelScope.launch {
            val outcome = withContext(io) {
                recurring.createRule(
                    target = editor.target,
                    targetId = targetId,
                    amount = amount,
                    frequency = editor.frequency,
                    anchorDay = editor.anchorDay,
                    autoPost = editor.autoPost,
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
    fun delete(id: Long, onDeleted: (String, RecurringRuleEntity) -> Unit) {
        viewModelScope.launch {
            val name = _state.value.rules
                .firstOrNull { it.rule.id == id }?.targetName.orEmpty()
            val row = withContext(io) { recurring.deleteRule(id) } ?: return@launch
            onDeleted(name, row)
        }
    }

    fun undoDelete(rule: RecurringRuleEntity) {
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
