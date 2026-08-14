package com.app.finance.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.app.finance.data.db.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    /**
     * The whole tree — dozens of rows, so it is cheaper to read it once and
     * shape it in Kotlin than to run a query per level.
     */
    @Query("SELECT * FROM category ORDER BY sort_order, name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category WHERE is_archived = 0 ORDER BY sort_order, name")
    fun observeActive(): Flow<List<CategoryEntity>>

    /**
     * Leaves are the only categories an expense may reference (FR-EXP-04), so
     * this is what the entry picker binds to. `NOT EXISTS` rather than
     * `parent_id IS NOT NULL` because a root with no children is not a valid
     * expense target either — it is an empty group.
     */
    @Query(
        """
        SELECT * FROM category c
         WHERE c.is_archived = 0
           AND c.parent_id IS NOT NULL
           AND NOT EXISTS (SELECT 1 FROM category k WHERE k.parent_id = c.id)
         ORDER BY c.sort_order, c.name
        """,
    )
    fun observeSelectableLeaves(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category WHERE id = :id")
    suspend fun byId(id: Long): CategoryEntity?

    @Query("SELECT * FROM category WHERE parent_id IS NULL ORDER BY sort_order")
    suspend fun roots(): List<CategoryEntity>

    @Query("SELECT * FROM category WHERE parent_id = :parentId ORDER BY sort_order")
    suspend fun children(parentId: Long): List<CategoryEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM category WHERE parent_id = :id)")
    suspend fun hasChildren(id: Long): Boolean

    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Update
    suspend fun update(category: CategoryEntity)

    /**
     * FR-CAT-08/09. Archiving is the only removal the app offers: deleting a
     * category would silently rewrite historical reports, so foreign keys are
     * `ON DELETE RESTRICT` throughout and this flag is what hides a category
     * from pickers while leaving history intact.
     */
    @Query("UPDATE category SET is_archived = :archived, updated_at = :now WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean, now: Long)

    /** FR-CAT-09 — archiving a root archives its descendants. */
    @Query("UPDATE category SET is_archived = :archived, updated_at = :now WHERE parent_id = :rootId")
    suspend fun setArchivedForChildren(rootId: Long, archived: Boolean, now: Long)

    @Query("SELECT COUNT(*) FROM category")
    suspend fun count(): Int
}
