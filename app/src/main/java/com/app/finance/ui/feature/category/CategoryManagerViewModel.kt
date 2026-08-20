package com.app.finance.ui.feature.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.finance.data.repo.ArchiveOutcome
import com.app.finance.data.repo.CategoryRepository
import com.app.finance.domain.model.CategoryNode
import com.app.finance.domain.model.EntryError
import com.app.finance.domain.model.Nature
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
sealed interface CategoryEditor {
    val name: String
    val error: EntryError?

    /** FR-CAT-04 — a new root, and the only place a nature is chosen. */
    data class NewRoot(
        override val name: String = "",
        val nature: Nature = Nature.VARIABLE,
        override val error: EntryError? = null,
    ) : CategoryEditor

    /** FR-CAT-05/06 — a child. Nature is inherited, so it is never offered. */
    data class NewChild(
        val parentId: Long,
        val parentName: String,
        override val name: String = "",
        override val error: EntryError? = null,
    ) : CategoryEditor

    /** FR-CAT-03 — permitted on system roots; it is the one thing they allow. */
    data class Rename(
        val categoryId: Long,
        override val name: String,
        override val error: EntryError? = null,
    ) : CategoryEditor
}

/**
 * One row of the archived section.
 *
 * [restorable] is false for a child whose root is also archived. Restoring it
 * alone would put an active leaf under a group that is not there — the entry
 * picker would offer it while the manager shows no home for it — so the action
 * is absent and the root's own Restore is what brings the child back into
 * reach. The same reasoning as FR-CAT-03's "actions are absent, not rejected".
 */
data class ArchivedEntry(val node: CategoryNode, val restorable: Boolean)

data class CategoryManagerUiState(
    val tree: List<CategoryNode> = emptyList(),
    val loading: Boolean = true,
    val editor: CategoryEditor? = null,
) {
    /** Archived categories are listed apart, not hidden — FR-CAT-08. */
    val archived: List<ArchivedEntry>
        get() = tree.flatMap { root ->
            (if (root.isArchived) listOf(ArchivedEntry(root, restorable = true)) else emptyList()) +
                root.children
                    .filter { it.isArchived }
                    .map { ArchivedEntry(it, restorable = !root.isArchived) }
        }

    val active: List<CategoryNode> get() = tree.filterNot { it.isArchived }
}

/**
 * The category manager — FR-CAT-03 … FR-CAT-10.
 *
 * Every rule here is already enforced beneath this layer: depth and nature
 * inheritance by trigger, name uniqueness by a unique index, deletion by
 * `ON DELETE RESTRICT`, and the system-root and cascade rules by
 * `CategoryRepository`. None of that is re-implemented.
 *
 * What this adds is making the rules *visible*. FR-CAT-03's acceptance
 * criterion is "Delete and archive actions are **absent** for `is_system = 1`
 * roots" and FR-CAT-05's is "The 'add subcategory' action is **unavailable** on
 * a category whose `parent_id` is non-null" — absent, not rejected. A screen
 * that offers an action and then explains why it failed satisfies neither.
 *
 * Reorder (FR-CAT-11) is here as of §20.2, after being deferred through M2,
 * M3 and M5 as the one `SHOULD` in FR-CAT.
 */
class CategoryManagerViewModel(
    private val categories: CategoryRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(CategoryManagerUiState())
    val state: StateFlow<CategoryManagerUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            categories.observeTree().collect { tree ->
                _state.update { it.copy(tree = tree, loading = false) }
            }
        }
    }

    // --- editors ------------------------------------------------------------

    fun addRoot() = _state.update { it.copy(editor = CategoryEditor.NewRoot()) }

    fun addChild(parent: CategoryNode) = _state.update {
        it.copy(editor = CategoryEditor.NewChild(parent.id, parent.name))
    }

    fun rename(category: CategoryNode) = _state.update {
        it.copy(editor = CategoryEditor.Rename(category.id, category.name))
    }

    fun setName(name: String) = _state.update { s ->
        val editor = when (val e = s.editor) {
            is CategoryEditor.NewRoot -> e.copy(name = name, error = null)
            is CategoryEditor.NewChild -> e.copy(name = name, error = null)
            is CategoryEditor.Rename -> e.copy(name = name, error = null)
            null -> null
        }
        s.copy(editor = editor)
    }

    fun setNature(nature: Nature) = _state.update { s ->
        val editor = s.editor as? CategoryEditor.NewRoot ?: return@update s
        s.copy(editor = editor.copy(nature = nature))
    }

    fun dismissEditor() = _state.update { it.copy(editor = null) }

    fun submit(onSaved: () -> Unit) {
        val editor = _state.value.editor ?: return
        viewModelScope.launch {
            val outcome = withContext(io) {
                when (editor) {
                    is CategoryEditor.NewRoot ->
                        categories.createRoot(editor.name, editor.nature)
                    is CategoryEditor.NewChild ->
                        categories.createSubcategory(editor.parentId, editor.name)
                    is CategoryEditor.Rename ->
                        categories.rename(editor.categoryId, editor.name)
                }
            }
            when (outcome) {
                is SaveOutcome.Saved -> {
                    _state.update { it.copy(editor = null) }
                    onSaved()
                }
                is SaveOutcome.Rejected -> _state.update { s ->
                    // The typed error the repository mapped, surfaced under the
                    // field rather than as a raw exception (04 §8).
                    val withError = when (editor) {
                        is CategoryEditor.NewRoot -> editor.copy(error = outcome.error)
                        is CategoryEditor.NewChild -> editor.copy(error = outcome.error)
                        is CategoryEditor.Rename -> editor.copy(error = outcome.error)
                    }
                    s.copy(editor = withError)
                }
            }
        }
    }

    // --- reorder (FR-CAT-11) ------------------------------------------------

    /**
     * No undo, and deliberately: NFR-USE-03 is about *destructive* actions and
     * a move destroys nothing. The reverse of moving up is moving down, which
     * is the control immediately beside it.
     */
    fun move(category: CategoryNode, up: Boolean) {
        viewModelScope.launch { withContext(io) { categories.move(category.id, up) } }
    }

    // --- archiving (FR-CAT-08, FR-CAT-09) -----------------------------------

    /**
     * Archives, and reports back so the caller can offer Undo.
     *
     * 05 §8: "Every destructive action is undoable for 5 seconds. No
     * confirmation dialogs for deletes." Archiving a root takes its children
     * with it in one transaction, and [onDone] receives the exact set of ids
     * that changed — so [undoArchive] reverses that operation and nothing else.
     * A child the user had archived earlier is not in the set and stays put.
     */
    fun archive(category: CategoryNode, onDone: (String, List<Long>) -> Unit) {
        viewModelScope.launch {
            val outcome = withContext(io) { categories.archive(category.id) }
            if (outcome is ArchiveOutcome.Archived) onDone(category.name, outcome.changed)
        }
    }

    fun undoArchive(ids: List<Long>) {
        viewModelScope.launch { withContext(io) { categories.restoreAll(ids) } }
    }

    /**
     * The archived section's Restore. A root comes back on its own; its
     * children stay archived and become restorable now that they have a group.
     */
    fun restore(category: CategoryNode, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val outcome = withContext(io) { categories.restore(category.id) }
            if (outcome is SaveOutcome.Saved) onDone(category.name)
        }
    }
}
