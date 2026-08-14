package com.app.finance.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.app.finance.data.db.entity.IncomeEntryEntity
import com.app.finance.data.db.entity.IncomeSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface IncomeDao {

    // --- sources ------------------------------------------------------------

    @Query("SELECT * FROM income_source WHERE is_archived = 0 ORDER BY sort_order, name")
    fun observeActiveSources(): Flow<List<IncomeSourceEntity>>

    @Query("SELECT * FROM income_source ORDER BY sort_order, name")
    fun observeAllSources(): Flow<List<IncomeSourceEntity>>

    /**
     * Lookup by normalised key, which is what makes "type a name that does not
     * exist yet and it is created inline" safe: the same typed name always
     * resolves to the same source rather than creating a near-duplicate.
     */
    @Query("SELECT * FROM income_source WHERE name_key = :nameKey")
    suspend fun sourceByKey(nameKey: String): IncomeSourceEntity?

    @Query("SELECT * FROM income_source WHERE id = :id")
    suspend fun sourceById(id: Long): IncomeSourceEntity?

    @Insert
    suspend fun insertSource(source: IncomeSourceEntity): Long

    @Update
    suspend fun updateSource(source: IncomeSourceEntity)

    @Query("UPDATE income_source SET is_archived = :archived, updated_at = :now WHERE id = :id")
    suspend fun setSourceArchived(id: Long, archived: Boolean, now: Long)

    // --- entries ------------------------------------------------------------

    @Insert
    suspend fun insertEntry(entry: IncomeEntryEntity): Long

    @Update
    suspend fun updateEntry(entry: IncomeEntryEntity)

    @Delete
    suspend fun deleteEntry(entry: IncomeEntryEntity)

    @Query("SELECT * FROM income_entry WHERE id = :id")
    suspend fun entryById(id: Long): IncomeEntryEntity?

    @Query(
        """
        SELECT * FROM income_entry
         WHERE status = 0 AND period_ym = :period
         ORDER BY earned_on DESC, id DESC
        """,
    )
    fun observeEntriesInPeriod(period: Int): Flow<List<IncomeEntryEntity>>

    /**
     * The income screen defaults to a *year*, not a month (05 §5.7): a farming
     * month showing ৳0 is alarming and meaningless in isolation, so the year is
     * the honest unit for this user's income even though the month is the
     * honest unit for their spending.
     */
    @Query(
        """
        SELECT IFNULL(SUM(amount_minor), 0) FROM income_entry
         WHERE status = 0 AND period_ym BETWEEN :startPeriod AND :endPeriod
        """,
    )
    fun observeTotalInPeriods(startPeriod: Int, endPeriod: Int): Flow<Long>

    /** Stable-source income only — the numerator of the coverage figure. */
    @Query(
        """
        SELECT IFNULL(SUM(e.amount_minor), 0)
          FROM income_entry e JOIN income_source s ON s.id = e.source_id
         WHERE e.status = 0 AND s.kind = 0 AND e.period_ym BETWEEN :startPeriod AND :endPeriod
        """,
    )
    fun observeStableTotalInPeriods(startPeriod: Int, endPeriod: Int): Flow<Long>
}
