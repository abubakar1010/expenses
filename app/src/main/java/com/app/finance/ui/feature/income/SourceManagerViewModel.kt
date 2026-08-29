package com.app.finance.ui.feature.income

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.finance.data.db.dao.SourceWithCount
import com.app.finance.data.db.entity.IncomeSourceEntity
import com.app.finance.data.repo.DeleteSourceOutcome
import com.app.finance.data.repo.IncomeRepository
import com.app.finance.domain.model.EntryError
import com.app.finance.domain.model.IncomeKind
import com.app.finance.domain.model.SaveOutcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which editor is open, if any. */
sealed interface SourceEditor {
    val name: String
    val error: EntryError?

    /** FR-IS-01 — a new source, and the only place a kind is chosen at creation. */
    data class New(
        override val name: String = "",
        val kind: IncomeKind = IncomeKind.VARIABLE,
        override val error: EntryError? = null,
    ) : SourceEditor

    data class Rename(
        val sourceId: Long,
        override val name: String,
        val kind: IncomeKind,
        override val error: EntryError? = null,
    ) : SourceEditor
}

data class SourceManagerUiState(
    val sources: List<SourceWithCount> = emptyList(),
    val loading: Boolean = true,
    val editor: SourceEditor? = null,
) {
    val active: List<SourceWithCount> get() = sources.filterNot { it.source.isArchived }

    /** FR-IS-04 — listed apart, not hidden. */
    val archived: List<SourceWithCount> get() = sources.filter { it.source.isArchived }
}

/**
 * The income source manager — FR-IS-01 … FR-IS-06.
 *
 * **The one screen in DayBook with a real delete**, and the one place the
 * category manager's "constraints are absent, never disabled" rule is
 * deliberately not followed. FR-IS-05's acceptance criterion says the opposite
 * in as many words:
 *
 * > "Delete action on such a source is *disabled* with an explanatory message
 * > offering Archive instead."
 *
 * The SRS is normative, so the control is present and disabled with its reason.
 * The reasoning is defensible on its own terms too: a category can never be
 * deleted at all, so an absent action teaches a true rule; a source *can* be
 * deleted, just not this one, and hiding the action would teach a false one.
 */
class SourceManagerViewModel(
    private val income: IncomeRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(SourceManagerUiState())
    val state: StateFlow<SourceManagerUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            income.observeSourcesWithCounts().collect { rows ->
                _state.update { it.copy(sources = rows, loading = false) }
            }
        }
    }

    // --- editors -------------------------------------------------------------

    fun add() = _state.update { it.copy(editor = SourceEditor.New()) }

    fun rename(row: SourceWithCount) = _state.update {
        it.copy(
            editor = SourceEditor.Rename(
                sourceId = row.source.id,
                name = row.source.name,
                kind = IncomeKind.fromCode(row.source.kind),
            ),
        )
    }

    fun setName(name: String) = _state.update { s ->
        val editor = when (val e = s.editor) {
            is SourceEditor.New -> e.copy(name = name, error = null)
            is SourceEditor.Rename -> e.copy(name = name, error = null)
            null -> null
        }
        s.copy(editor = editor)
    }

    /**
     * The kind is editable after creation as well as during it — a source that
     * turns out to arrive on a rhythm should be reclassifiable without deleting
     * and re-entering its history, and the coverage figure depends on it being
     * right.
     */
    fun setKind(kind: IncomeKind) = _state.update { s ->
        val editor = when (val e = s.editor) {
            is SourceEditor.New -> e.copy(kind = kind)
            is SourceEditor.Rename -> e.copy(kind = kind)
            null -> null
        }
        s.copy(editor = editor)
    }

    fun dismissEditor() = _state.update { it.copy(editor = null) }

    fun submit(onSaved: () -> Unit) {
        val editor = _state.value.editor ?: return
        viewModelScope.launch {
            val outcome = withContext(io) {
                when (editor) {
                    is SourceEditor.New -> income.createSource(editor.name, editor.kind)
                    // One write, not a rename followed by a kind change: the
                    // second could fail behind the first and leave a source
                    // renamed but still classified wrongly, and the kind is the
                    // one field the coverage figure depends on.
                    is SourceEditor.Rename ->
                        income.updateSource(editor.sourceId, editor.name, editor.kind)
                }
            }
            when (outcome) {
                is SaveOutcome.Saved -> {
                    _state.update { it.copy(editor = null) }
                    onSaved()
                }
                is SaveOutcome.Rejected -> _state.update { s ->
                    val withError = when (editor) {
                        is SourceEditor.New -> editor.copy(error = outcome.error)
                        is SourceEditor.Rename -> editor.copy(error = outcome.error)
                    }
                    s.copy(editor = withError)
                }
            }
        }
    }

    // --- archive and delete (FR-IS-04, FR-IS-05, FR-IS-06) -------------------

    /** FR-IS-07. No undo, for the reason [CategoryManagerViewModel.move] gives. */
    fun move(row: SourceWithCount, up: Boolean) {
        viewModelScope.launch { withContext(io) { income.moveSource(row.source.id, up) } }
    }

    fun setArchived(row: SourceWithCount, archived: Boolean, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val outcome = withContext(io) {
                income.setSourceArchived(row.source.id, archived)
            }
            if (outcome is SaveOutcome.Saved) onDone(row.source.name)
        }
    }

    /**
     * FR-IS-06. Guarded twice over — the UI only offers this where the count is
     * zero, and the repository checks again — because a delete is the one
     * action here that history cannot survive.
     *
     * [onDeleted] carries the row back so the snackbar can undo it (NFR-USE-03).
     * With no entries pointing at it, restoring is a single re-insert.
     */
    fun delete(row: SourceWithCount, onDeleted: (String, IncomeSourceEntity) -> Unit) {
        viewModelScope.launch {
            when (val outcome = withContext(io) { income.deleteSource(row.source.id) }) {
                is DeleteSourceOutcome.Deleted ->
                    onDeleted(outcome.source.name, outcome.source)
                is DeleteSourceOutcome.Rejected -> Unit
            }
        }
    }

    fun undoDelete(source: IncomeSourceEntity) {
        viewModelScope.launch { withContext(io) { income.restoreSource(source) } }
    }
}
