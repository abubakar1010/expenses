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
     * FR-CAT-08/09. Archiving a root archives its descendants, in one
     * transaction so the tree is never half-archived.
     *
     * There is no delete. "A deleted category silently rewrites history; an
     * archived one preserves it" — which is also why every foreign key in the
     * schema is `ON DELETE RESTRICT`.
     */
    suspend fun setArchived(id: Long, archived: Boolean): SaveOutcome {
        val category = dao.byId(id) ?: return SaveOutcome.Rejected(EntryError.CATEGORY_NOT_FOUND)
        if (category.isSystem) return SaveOutcome.Rejected(EntryError.CONSTRAINT_VIOLATION)

        val now = clock.millis()
        db.withTransaction {
            dao.setArchived(id, archived, now)
            if (category.parentId == null) dao.setArchivedForChildren(id, archived, now)
        }
        return SaveOutcome.Saved(id)
    }

    private fun Throwable.toCategoryError(): EntryError = when {
        this !is SQLiteConstraintException -> EntryError.CONSTRAINT_VIOLATION
        message?.contains("two levels") == true -> EntryError.CATEGORY_TOO_DEEP
        message?.contains("UNIQUE", ignoreCase = true) == true -> EntryError.DUPLICATE_NAME
        else -> EntryError.CONSTRAINT_VIOLATION
    }
}
