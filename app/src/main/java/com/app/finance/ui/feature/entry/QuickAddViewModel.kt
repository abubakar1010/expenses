package com.app.finance.ui.feature.entry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.finance.core.money.Money
import com.app.finance.data.db.entity.CategoryEntity
import com.app.finance.data.repo.AppMetaRepository
import com.app.finance.data.repo.CategoryRepository
import com.app.finance.data.repo.ExpenseRepository
import com.app.finance.domain.model.EntryError
import com.app.finance.domain.model.PaymentMethod
import com.app.finance.domain.model.SaveOutcome
import com.app.finance.ui.common.KeypadKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate

data class QuickAddUiState(
    /** Raw keypad input, not yet money — "12", "12.", "12.5" are all valid
     *  intermediate states that [Money.parseOrNull] refuses. */
    val input: String = "",
    val negative: Boolean = false,
    val chips: List<CategoryEntity> = emptyList(),
    val selectedCategoryId: Long? = null,
    val date: LocalDate = LocalDate.now(),
    val method: PaymentMethod = PaymentMethod.DEFAULT,
    val note: String? = null,
    val error: EntryError? = null,
    val saving: Boolean = false,
) {
    val amount: Money?
        get() = Money.parseOrNull(input)?.let { if (negative) -it.absoluteValue else it }

    /** The save button is enabled only when the write would actually succeed. */
    val canSave: Boolean
        get() = !saving && selectedCategoryId != null && amount?.isZero == false
}

/**
 * 05-ui-ux-guide.md §5.6 — three taps, under five seconds.
 *
 * The speed comes from defaults rather than from a faster form: the date is
 * today, the payment method is whatever was used last, and the six chips are
 * the six most recently used categories. "The user should be able to log a
 * typical expense without changing a single default."
 */
class QuickAddViewModel(
    private val expenses: ExpenseRepository,
    private val categories: CategoryRepository,
    private val meta: AppMetaRepository,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(QuickAddUiState(date = LocalDate.now(clock)))
    val state: StateFlow<QuickAddUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val (chips, lastCategory, lastMethod) = withContext(Dispatchers.IO) {
                val leaves = categories.leaves()
                val recent = meta.recentCategoryIds()
                val byId = leaves.associateBy { it.id }

                // Recently used first, then whatever else fills the six slots,
                // so the sheet is never sparse on a fresh install.
                val ordered = (recent.mapNotNull(byId::get) + leaves)
                    .distinctBy { it.id }
                    .take(CHIP_COUNT)

                Triple(ordered, meta.lastCategoryId(), meta.lastPaymentMethod())
            }
            _state.update {
                it.copy(
                    chips = chips,
                    selectedCategoryId = lastCategory?.takeIf { id -> chips.any { c -> c.id == id } }
                        ?: chips.firstOrNull()?.id,
                    method = lastMethod,
                )
            }
        }
    }

    fun onKey(key: KeypadKey) = _state.update { s ->
        when (key) {
            is KeypadKey.Digit -> s.copy(input = s.input.appendDigit(key.value), error = null)
            KeypadKey.DoubleZero -> s.copy(input = s.input.appendDigit('0').appendDigit('0'), error = null)
            KeypadKey.Decimal ->
                if (s.input.contains('.') || s.input.isEmpty()) s
                else s.copy(input = s.input + '.', error = null)
            KeypadKey.Backspace -> s.copy(input = s.input.dropLast(1), error = null)
            // A refund is the same entry with the sign flipped, not a different
            // kind of record (FR-EXP-06).
            KeypadKey.Negate -> s.copy(negative = !s.negative, error = null)
        }
    }

    fun selectCategory(id: Long) = _state.update { it.copy(selectedCategoryId = id, error = null) }

    fun setMethod(method: PaymentMethod) = _state.update { it.copy(method = method) }

    fun setDate(date: LocalDate) = _state.update { it.copy(date = date) }

    fun setNote(note: String?) = _state.update { it.copy(note = note) }

    /**
     * The write path. Everything happens on IO and the result is a typed
     * outcome — 04 §5.1 and §8.
     */
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
            val outcome = withContext(Dispatchers.IO) {
                expenses.insert(
                    amount = amount,
                    categoryId = categoryId,
                    spentOn = snapshot.date,
                    method = snapshot.method,
                    note = snapshot.note,
                )
            }
            when (outcome) {
                is SaveOutcome.Saved -> onSaved()
                is SaveOutcome.Rejected ->
                    _state.update { it.copy(saving = false, error = outcome.error) }
            }
        }
    }

    private fun String.appendDigit(c: Char): String {
        // Two decimal places is all paisa has; further digits are silently
        // ignored rather than accepted and then truncated at parse time.
        val decimals = substringAfter('.', "")
        if (contains('.') && decimals.length >= 2) return this
        if (this == "0") return c.toString()
        return this + c
    }

    private companion object {
        const val CHIP_COUNT = 6
    }
}
