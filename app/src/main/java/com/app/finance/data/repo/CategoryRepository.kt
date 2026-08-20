package com.app.finance.data.repo

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.app.finance.core.text.NameKey
import com.app.finance.data.db.AppDatabase
import com.app.finance.data.db.entity.CategoryEntity
import com.app.finance.domain.model.CategoryNode
import com.app.finance.domain.model.EntryError
import com.app.finance.domain.model.Nature
import com.app.finance.domain.model.SaveOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.util.UUID

/**
 * The result of archiving, which carries more than "it worked".
 *
 * [ArchiveOutcome.Archived.changed] is every id the operation actually flipped
 * — the category itself plus, for a root, the children that were still active.
 * The caller hands that list straight back to
 * [CategoryRepository.restoreAll] for Undo, so the undo can only ever reverse
 * what the archive did.
 */
sealed interface ArchiveOutcome {
    data class Archived(val category: CategoryEntity, val changed: List<Long>) : ArchiveOutcome

    @JvmInline
    value class Rejected(val error: EntryError) : ArchiveOutcome
}

class CategoryRepository(
    private val db: AppDatabase,
    private val clock: Clock,
) {
    private val dao = db.categoryDao()

    /**
     * The two-level tree, assembled in Kotlin from one query.
     *
     * Dozens of rows, so a single read and an in-memory group is cheaper than a
     * query per level — and it keeps the shape of the tree in one place rather
     * than spread across SQL.
     */
    fun observeTree(): Flow<List<CategoryNode>> = dao.observeAll().map { rows ->
        val byParent = rows.groupBy { it.parentId }
        rows.filter { it.parentId == null }
            .sortedBy { it.sortOrder }
            .map { root ->
                CategoryNode(
                    id = root.id,
                    name = root.name,
                    nature = Nature.fromCode(root.nature),
                    isSystem = root.isSystem,
                    isArchived = root.isArchived,
                    children = byParent[root.id].orEmpty()
                        .sortedBy { it.sortOrder }
                        .map { child ->
                            CategoryNode(
                                id = child.id,
                                name = child.name,
                                nature = Nature.fromCode(child.nature),
                                isSystem = child.isSystem,
                                isArchived = child.isArchived,
                                children = emptyList(),
                            )
                        },
                )
            }
    }

    /** What the entry picker binds to — leaves only (FR-EXP-04). */
    fun observeSelectableLeaves(): Flow<List<CategoryEntity>> = dao.observeSelectableLeaves()

    suspend fun byId(id: Long): CategoryEntity? = dao.byId(id)

    suspend fun leaves(): List<CategoryEntity> =
        dao.roots().flatMap { dao.children(it.id) }.filter { !it.isArchived }

    /**
     * `nature` is intentionally not a parameter for subcategories: FR-CAT-06
     * says a subcategory inherits it from its root and may not override it, and
     * the insert trigger enforces that regardless of what is passed. The value
     * sent here is a placeholder the database corrects.
     */
    suspend fun createSubcategory(parentId: Long, name: String): SaveOutcome {
        if (NameKey.isBlank(name)) return SaveOutcome.Rejected(EntryError.BLANK_NAME)
        val parent = dao.byId(parentId) ?: return SaveOutcome.Rejected(EntryError.CATEGORY_NOT_FOUND)
        if (parent.parentId != null) return SaveOutcome.Rejected(EntryError.CATEGORY_TOO_DEEP)
        // An active leaf under an archived root is a state nothing else in the
        // app can express, and one the entry picker would happily offer while
        // the group it belongs to is gone from the manager. Restore the root
        // first; then the child has somewhere to live.
        if (parent.isArchived) return SaveOutcome.Rejected(EntryError.CATEGORY_ARCHIVED)

        val now = clock.millis()
        val siblings = dao.children(parentId)
        return runCatching {
            dao.insert(
                CategoryEntity(
                    uuid = UUID.randomUUID().toString(),
                    parentId = parentId,
                    name = name.trim(),
                    nameKey = NameKey.of(name),
                    nature = parent.nature,
                    sortOrder = siblings.size,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }.fold(
            onSuccess = { SaveOutcome.Saved(it) },
            onFailure = { SaveOutcome.Rejected(it.toCategoryError()) },
        )
    }

    suspend fun createRoot(name: String, nature: Nature): SaveOutcome {
        if (NameKey.isBlank(name)) return SaveOutcome.Rejected(EntryError.BLANK_NAME)
        val now = clock.millis()
        return runCatching {
            dao.insert(
                CategoryEntity(
                    uuid = UUID.randomUUID().toString(),
                    parentId = null,
                    name = name.trim(),
                    nameKey = NameKey.of(name),
                    nature = nature.code,
                    sortOrder = dao.roots().size,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }.fold(
            onSuccess = { SaveOutcome.Saved(it) },
            onFailure = { SaveOutcome.Rejected(it.toCategoryError()) },
        )
    }

    /** FR-CAT-03 — system roots are renameable; that is all they permit. */
    suspend fun rename(id: Long, name: String): SaveOutcome {
        if (NameKey.isBlank(name)) return SaveOutcome.Rejected(EntryError.BLANK_NAME)
        val existing = dao.byId(id) ?: return SaveOutcome.Rejected(EntryError.CATEGORY_NOT_FOUND)
        return runCatching {
            dao.update(
                existing.copy(
                    name = name.trim(),
                    nameKey = NameKey.of(name),
                    updatedAt = clock.millis(),
                ),
            )
            id
        }.fold(
            onSuccess = { SaveOutcome.Saved(it) },
            onFailure = { SaveOutcome.Rejected(it.toCategoryError()) },
        )
    }

    /**
     * FR-CAT-08/09. Archives a category and, for a root, its active children —
     * in one transaction, so the tree is never half-archived.
     *
     * There is no delete. "A deleted category silently rewrites history; an
     * archived one preserves it" — which is also why every foreign key in the
     * schema is `ON DELETE RESTRICT`.
     *
     * Returns **every id it actually changed**, which is what makes the
     * snackbar on top of it a real undo. A child the user had already archived
     * deliberately is not touched and is not in the list, so undoing does not
     * bring it back — an undo that restores more than the action removed is a
     * silent data change, and the fact that it is silent is what makes it bad.
     */
    /**
     * FR-CAT-11 — "reordering categories within their parent".
     *
     * Within their parent, so a root moves among roots and a child among its
     * own siblings; there is no operation here that moves a category out of
     * its group, and the depth trigger would refuse one anyway.
     *
     * **Every sibling is rewritten, not just the two that swapped.** The seed
     * assigns `sort_order` positionally but nothing has ever written the
     * column since, so a tree can hold rows that all share a value — and a
     * swap between two rows that both say 0 does nothing at all. Normalising
     * the whole run to 0..n-1 on each move makes the operation total instead
     * of depending on the state it started from.
     *
     * Archived siblings sort after the active ones and keep their relative
     * order. They are not reorderable — FR-CAT-08 keeps them out of the
     * pickers, and the order they would be in there is not a thing the user
     * is arranging — but they still need positions, or the next insert would
     * collide with them.
     *
     * @return true if the category moved; false at the end of its range.
     */
    suspend fun move(id: Long, up: Boolean): Boolean {
        val category = dao.byId(id) ?: return false
        val siblings = (
            if (category.parentId == null) dao.roots() else dao.children(category.parentId)
            ).sortedWith(compareBy({ it.isArchived }, { it.sortOrder }, { it.name }))

        val movable = siblings.filterNot { it.isArchived }.toMutableList()
        val from = movable.indexOfFirst { it.id == id }
        val to = if (up) from - 1 else from + 1
        if (from < 0 || to !in movable.indices) return false

        movable.add(to, movable.removeAt(from))

        val now = clock.millis()
        val ordered = movable + siblings.filter { it.isArchived }
        db.withTransaction {
            ordered.forEachIndexed { index, sibling ->
                if (sibling.sortOrder != index) dao.setSortOrder(sibling.id, index, now)
            }
        }
        return true
    }

    suspend fun archive(id: Long): ArchiveOutcome {
        val category = dao.byId(id) ?: return ArchiveOutcome.Rejected(EntryError.CATEGORY_NOT_FOUND)
        if (category.isSystem) return ArchiveOutcome.Rejected(EntryError.CONSTRAINT_VIOLATION)
        if (category.isArchived) return ArchiveOutcome.Archived(category, emptyList())

        val now = clock.millis()
        val cascaded = if (category.parentId == null) {
            dao.children(id).filterNot { it.isArchived }.map { it.id }
        } else {
            emptyList()
        }

        db.withTransaction {
            dao.setArchived(id, archived = true, now = now)
            if (category.parentId == null) dao.archiveChildren(id, now)
        }
        return ArchiveOutcome.Archived(category, listOf(id) + cascaded)
    }

    /**
     * Un-archives exactly [ids] — the undo half of [archive], and the only
     * caller that may restore a child while its root is still archived, because
     * in that case the root is in the same list and is being restored with it.
     */
    suspend fun restoreAll(ids: List<Long>) {
        if (ids.isEmpty()) return
        val now = clock.millis()
        db.withTransaction {
            ids.forEach { dao.setArchived(it, archived = false, now = now) }
        }
    }

    /**
     * The Restore action in the manager's archived section.
     *
     * A root restores alone: its children stay archived and become individually
     * restorable now that they have a group to belong to. That is deliberately
     * not the cascade run backwards — after an archive-everything the user may
     * well want only two of the five back, and a root with some children
     * archived is an ordinary state the rest of the app already handles.
     *
     * A child may not be restored while its root is archived. It would reappear
     * in the entry picker under a group that is not there, and nothing else in
     * the app can express that state.
     */
    suspend fun restore(id: Long): SaveOutcome {
        val category = dao.byId(id) ?: return SaveOutcome.Rejected(EntryError.CATEGORY_NOT_FOUND)
        val parent = category.parentId?.let { dao.byId(it) }
        if (parent?.isArchived == true) return SaveOutcome.Rejected(EntryError.CATEGORY_ARCHIVED)

        dao.setArchived(id, archived = false, now = clock.millis())
        return SaveOutcome.Saved(id)
    }

    private fun Throwable.toCategoryError(): EntryError = when {
        this !is SQLiteConstraintException -> EntryError.CONSTRAINT_VIOLATION
        message?.contains("two levels") == true -> EntryError.CATEGORY_TOO_DEEP
        message?.contains("UNIQUE", ignoreCase = true) == true -> EntryError.DUPLICATE_NAME
        else -> EntryError.CONSTRAINT_VIOLATION
    }
}
