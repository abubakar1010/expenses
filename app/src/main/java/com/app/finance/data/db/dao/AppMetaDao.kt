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
        const val KEY_THEME = "theme"

        /** NFR-SEC-04, optional by requirement — so it is stored, not assumed. */
        const val KEY_SECURE_SCREEN = "secure_screen"

        /** FR-APP-04, likewise optional. */
        const val KEY_APP_LOCK = "app_lock"

        /**
         * FR-DAT-07 … FR-DAT-11 — the automatic backup.
         *
         * In `app_meta` for the reason every other preference is (04 §2): these
         * are written beside the ledger they describe, in the same transaction,
         * rather than in a second file with its own handle on the startup path.
         */
        const val KEY_BACKUP_TREE = "backup_tree_uri"
        const val KEY_BACKUP_INTERVAL = "backup_interval"
        const val KEY_BACKUP_KEEP = "backup_keep"
        const val KEY_BACKUP_ENCRYPTED = "backup_encrypted"
        const val KEY_BACKUP_LAST_AT = "backup_last_at"
        const val KEY_BACKUP_LAST_COUNT = "backup_last_count"
        const val KEY_BACKUP_LAST_REVISION = "backup_last_revision"

        /**
         * `app_meta` rows that describe *this installation* and must not travel
         * in a backup — FR-DAT-12.
         *
         * The rest of `app_meta` is exported and restored deliberately: a
         * restore that brought back the ledger but not the theme, the lock, or
         * the last-used category would not be "as it was before", which is the
         * whole claim FR-DAT-04 makes. These four are the exception, because
         * they are not preferences at all:
         *
         * - the tree URI names a folder grant **this** install holds and a
         *   restored phone does not, so carrying it over would leave the app
         *   pointing at a folder it cannot write to and reporting no error until
         *   the next backup silently failed;
         * - the three `last_*` rows are a record of what this phone has already
         *   written, and importing them would tell a fresh install it had just
         *   backed up when it never has.
         *
         * `backup_interval`, `backup_keep` and `backup_encrypted` are **not**
         * here. Those are choices the user made and would have to make again.
         *
         * The same shape as the `schema_version` re-stamp in `Importer` (§18.7
         * A8): the file describes a ledger, not a device.
         */
        val TRANSIENT_KEYS = setOf(
            KEY_BACKUP_TREE,
            KEY_BACKUP_LAST_AT,
            KEY_BACKUP_LAST_COUNT,
            KEY_BACKUP_LAST_REVISION,
        )

        /** Six chips fit the Quick Add sheet two rows deep (05 §5.6). */
        const val RECENT_CATEGORY_LIMIT = 6
    }
}
