package com.app.finance.ui.feature.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.finance.core.money.Money
import com.app.finance.data.db.dao.PersonBalanceRow
import com.app.finance.data.db.entity.SettlementEntity
import com.app.finance.data.repo.PersonRepository
import com.app.finance.data.repo.SettlementRepository
import com.app.finance.domain.model.EntryError
import com.app.finance.domain.model.PaymentMethod
import com.app.finance.domain.model.SaveOutcome
import com.app.finance.ui.common.Undoable
import com.app.finance.ui.common.editableText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** The editor sheet, when one is open — the shape `CategoryEditor` set. */
sealed interface PersonEditor {
    val name: String
    val error: EntryError?

    data class New(override val name: String = "", override val error: EntryError? = null) :
        PersonEditor

    data class Rename(
        val personId: Long,
        override val name: String,
        override val error: EntryError? = null,
    ) : PersonEditor
}

/** Recording money moving, in either direction — FR-SHR-04. */
data class SettleEditor(
    val personId: Long,
    val personName: String,
    /** True when they are paying you; false when you are paying them. */
    val theyPay: Boolean,
    val input: String = "",
    val error: EntryError? = null,
) {
    val amount: Money? get() = Money.parseOrNull(input)?.takeIf { !it.isZero }

    /** Signed as [SettlementRepository.record] wants it: positive means they paid you. */
    val signed: Money? get() = amount?.let { if (theyPay) it else -it }
}

data class PeopleUiState(
    val balances: List<PersonBalanceRow> = emptyList(),
    val loading: Boolean = true,
    val editor: PersonEditor? = null,
    val settle: SettleEditor? = null,
    val undoQueue: List<Undoable<SettlementEntity>> = emptyList(),
) {
    /**
     * Split by direction, and **square people appear in neither** — FR-SHR-05.
     *
     * A balance of zero is a settled account, and listing it would bury the two
     * names that need acting on among a dozen that do not. They stay reachable
     * through the archive-style section below.
     */
    val owedToYou: List<PersonBalanceRow> get() = balances.filter { it.balanceMinor > 0 }

    val youOwe: List<PersonBalanceRow> get() = balances.filter { it.balanceMinor < 0 }

    val settled: List<PersonBalanceRow> get() = balances.filter { it.balanceMinor == 0L }

    val totalOwedToYou: Money get() = Money(owedToYou.sumOf { it.balanceMinor })

    /** Positive for display; the section header says which way it points. */
    val totalYouOwe: Money get() = Money(-youOwe.sumOf { it.balanceMinor })

    val isEmpty: Boolean get() = !loading && balances.isEmpty()
}

/**
 * People and what they owe — FR-SHR-01, FR-SHR-04, FR-SHR-05.
 *
 * The balance comes from one query over three tables and is not backed by a
 * rollup; see [SettlementRepository]. Nothing here computes it in Kotlin, which
 * is what keeps NFR-REL-02's reconciliation a property of the query rather than
 * of this class.
 */
class PeopleViewModel(
    private val people: PersonRepository,
    private val settlements: SettlementRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(PeopleUiState())
    val state: StateFlow<PeopleUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settlements.observeBalances().collect { rows ->
                _state.update { it.copy(balances = rows, loading = false) }
            }
        }
    }

    // --- the editor ----------------------------------------------------------

    fun addPerson() = _state.update { it.copy(editor = PersonEditor.New()) }

    fun rename(row: PersonBalanceRow) =
        _state.update { it.copy(editor = PersonEditor.Rename(row.personId, row.personName)) }

    /** Clears the error on every keystroke, so it names the last attempt only. */
    fun setEditorName(name: String) = _state.update { s ->
        s.copy(
            editor = when (val e = s.editor) {
                is PersonEditor.New -> e.copy(name = name, error = null)
                is PersonEditor.Rename -> e.copy(name = name, error = null)
                null -> null
            },
        )
    }

    fun dismissEditor() = _state.update { it.copy(editor = null) }

    fun submitEditor() {
        val editor = _state.value.editor ?: return
        viewModelScope.launch {
            val outcome = withContext(io) {
                when (editor) {
                    is PersonEditor.New -> people.findOrCreate(editor.name)
                    is PersonEditor.Rename -> people.rename(editor.personId, editor.name)
                }
            }
            when (outcome) {
                is SaveOutcome.Saved -> _state.update { it.copy(editor = null) }
                is SaveOutcome.Rejected -> _state.update { s ->
                    s.copy(
                        editor = when (val e = s.editor) {
                            is PersonEditor.New -> e.copy(error = outcome.error)
                            is PersonEditor.Rename -> e.copy(error = outcome.error)
                            null -> null
                        },
                    )
                }
            }
        }
    }

    // --- archive and delete --------------------------------------------------

    /**
     * Archiving hides somebody from the split picker without touching history.
     *
     * Their balance stays visible while they are not square — an archived
     * person you still owe is precisely the one you must not lose sight of.
     */
    fun setArchived(personId: Long, archived: Boolean, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val outcome = withContext(io) { people.setArchived(personId, archived) }
            if (outcome is SaveOutcome.Saved) onDone(archived)
        }
    }

    /** Only ever offered for somebody with no history — the repository re-checks. */
    fun delete(personId: Long, onRefused: (EntryError) -> Unit = {}) {
        viewModelScope.launch {
            val outcome = withContext(io) { people.delete(personId) }
            if (outcome is SaveOutcome.Rejected) onRefused(outcome.error)
        }
    }

    // --- settling up (FR-SHR-04) ---------------------------------------------

    /**
     * Opens the settle sheet, pointed the way the balance already leans.
     *
     * Somebody who owes you is usually paying you back; somebody you owe is
     * usually being paid. The direction is still a control — this only picks
     * which way it starts.
     */
    fun settleUp(row: PersonBalanceRow) = _state.update {
        it.copy(
            settle = SettleEditor(
                personId = row.personId,
                personName = row.personName,
                theyPay = row.balanceMinor >= 0,
                input = Money(row.balanceMinor).absoluteValue
                    .takeIf { m -> !m.isZero }?.editableText().orEmpty(),
            ),
        )
    }

    fun setSettleAmount(input: String) =
        _state.update { s -> s.copy(settle = s.settle?.copy(input = input, error = null)) }

    fun setSettleDirection(theyPay: Boolean) =
        _state.update { s -> s.copy(settle = s.settle?.copy(theyPay = theyPay, error = null)) }

    fun dismissSettle() = _state.update { it.copy(settle = null) }

    fun submitSettle(today: LocalDate) {
        val editor = _state.value.settle ?: return
        val signed = editor.signed
        if (signed == null) {
            _state.update { s -> s.copy(settle = s.settle?.copy(error = EntryError.ZERO_AMOUNT)) }
            return
        }
        viewModelScope.launch {
            val outcome = withContext(io) {
                settlements.record(
                    personId = editor.personId,
                    amount = signed,
                    settledOn = today,
                    method = PaymentMethod.DEFAULT,
                )
            }
            when (outcome) {
                is SaveOutcome.Saved -> _state.update { it.copy(settle = null) }
                is SaveOutcome.Rejected ->
                    _state.update { s -> s.copy(settle = s.settle?.copy(error = outcome.error)) }
            }
        }
    }

    // --- undo (NFR-USE-03) ---------------------------------------------------

    private var nextUndoId = 0L

    /**
     * Removes a settlement, keeping it for Undo.
     *
     * The queue rather than a slot, and for the reason `LedgerViewModel`'s
     * comment records: two deletions inside one window re-key a `LaunchedEffect`
     * that then runs neither branch, losing the only surviving copy.
     */
    fun deleteSettlement(id: Long) {
        viewModelScope.launch {
            val removed = withContext(io) { settlements.delete(id) } ?: return@launch
            // Computed outside `update`, whose lambda can be re-run.
            val undoId = ++nextUndoId
            _state.update { it.copy(undoQueue = it.undoQueue + Undoable(undoId, removed)) }
        }
    }

    fun undo(id: Long) {
        val item = _state.value.undoQueue.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            withContext(io) { settlements.restore(item.payload) }
            dropUndo(id)
        }
    }

    fun dropUndo(id: Long) =
        _state.update { s -> s.copy(undoQueue = s.undoQueue.filterNot { it.id == id }) }
}
