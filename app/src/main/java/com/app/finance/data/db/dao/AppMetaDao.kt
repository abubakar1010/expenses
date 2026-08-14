package com.app.finance.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.app.finance.data.db.entity.AppMetaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppMetaDao {

    @Query("SELECT value FROM app_meta WHERE key = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM app_meta WHERE key = :key")
    fun observe(key: String): Flow<String?>

    @Upsert(entity = AppMetaEntity::class)
    suspend fun put(row: AppMetaEntity)

    @Query("DELETE FROM app_meta WHERE key = :key")
    suspend fun remove(key: String)

    companion object {
        /**
         * FR-EXP-02/03 — "defaults do the work". The user should be able to log
         * a typical expense without changing a single default, and these are
         * what the Quick Add sheet reads to make that true.
         */
        const val KEY_LAST_CATEGORY = "last_category_id"
        const val KEY_LAST_METHOD = "last_payment_method"
        const val KEY_LAST_PERIOD = "last_viewed_period"
        const val KEY_RECENT_CATEGORIES = "recent_category_ids"
        const val KEY_SCHEMA_VERSION = "schema_version"
        const val KEY_ONBOARDED = "onboarded"

        /** Six chips fit the Quick Add sheet two rows deep (05 §5.6). */
        const val RECENT_CATEGORY_LIMIT = 6
    }
}
