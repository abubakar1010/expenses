package com.app.finance.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.app.finance.data.db.entity.PersonEntity
import kotlinx.coroutines.flow.Flow

/**
 * The people you split expenses with — FR-SHR-01.
 *
 * Small by nature: a few dozen rows at most, so everything here reads the whole
 * table and shapes it in Kotlin rather than paging.
 */
@Dao
interface PersonDao {

    @Query("SELECT * FROM person ORDER BY sort_order, name")
    fun observeAll(): Flow<List<PersonEntity>>

    /** What the split picker binds to — an archived person cannot take a share. */
    @Query("SELECT * FROM person WHERE is_archived = 0 ORDER BY sort_order, name")
    fun observeActive(): Flow<List<PersonEntity>>

    @Query("SELECT * FROM person WHERE id = :id")
    suspend fun byId(id: Long): PersonEntity?

    @Query("SELECT * FROM person WHERE name_key = :nameKey")
    suspend fun byNameKey(nameKey: String): PersonEntity?

    @Query("SELECT * FROM person ORDER BY sort_order, name")
    suspend fun all(): List<PersonEntity>

    @Query("SELECT IFNULL(MAX(sort_order), -1) + 1 FROM person")
    suspend fun nextSortOrder(): Int

    @Insert
    suspend fun insert(person: PersonEntity): Long

    @Update
    suspend fun update(person: PersonEntity)

    @Query("UPDATE person SET is_archived = :archived, updated_at = :now WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean, now: Long)

    /**
     * Whether anything at all references this person.
     *
     * FR-SHR-01 archives rather than deletes, exactly as categories do — every
     * foreign key here is `ON DELETE RESTRICT`, so a person carrying history
     * cannot be removed without rewriting it. This is what lets the UI say so
     * before the database refuses.
     */
    @Query(
        """
        SELECT EXISTS (SELECT 1 FROM expense_share WHERE person_id = :id)
            OR EXISTS (SELECT 1 FROM expense    WHERE payer_person_id = :id)
            OR EXISTS (SELECT 1 FROM settlement WHERE person_id = :id)
        """,
    )
    suspend fun hasHistory(id: Long): Boolean

    /** Deletes a person nothing references — the FK above is the real guard. */
    @Query("DELETE FROM person WHERE id = :id")
    suspend fun delete(id: Long)
}
