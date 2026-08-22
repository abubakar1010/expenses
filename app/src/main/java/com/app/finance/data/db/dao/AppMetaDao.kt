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

    /**
     * Every backup preference at once, for the screen that shows them.
     *
     * Room invalidates per table, so this re-runs whenever any `app_meta` row is
     * written -- and `ExpenseRepository` writes one on every save. The query is
     * narrowed so the *result* only changes when a backup key does, which is what
     * lets the caller settle it with `distinctUntilChanged` instead of rebuilding
     * the settings object after every expense.
     */
    @Query("SELECT * FROM app_meta WHERE key LIKE 'backup_%' ORDER BY key")
    fun observeBackupKeys(): Flow<List<AppMetaEntity>>

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
        const val KEY_BACKUP_LAST_AT = "backup_last_at"
        const val KEY_BACKUP_LAST_COUNT = "backup_last_count"
        const val KEY_BACKUP_LAST_REVISION = "backup_last_revision"

        /**
         * The derived backup key and the parameters that produced it —
         * `BackupCodec.Secret`, where the reasoning for keeping a key rather
         * than re-deriving a passphrase is written out.
         *
         * **There is no separate "encryption is on" flag, deliberately.** A flag
         * and a key are two facts that can disagree, and the state they disagree
         * in — wanted but unavailable — is reachable in an ordinary way: these
         * keys are transient, so any restore lands in it. Encryption is on
         * exactly when there is a key to do it with, and that cannot be wrong.
         */
        const val KEY_BACKUP_KEY = "backup_key"
        const val KEY_BACKUP_SALT = "backup_salt"
        const val KEY_BACKUP_ROUNDS = "backup_rounds"

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
         *   backed up when it never has;
         * - the key would otherwise ride inside the very files it protects, and
         *   in a *plain* backup it would ride in the clear — so restoring one
         *   would hand the key to anyone holding an unencrypted file. Re-typing
         *   the passphrase once after a restore is the price, and it is small.
         *
         * `backup_interval` and `backup_keep` are **not** here. Those are choices
         * the user made and would otherwise have to make again.
         *
         * The same shape as the `schema_version` re-stamp in `Importer` (§18.7
         * A8): the file describes a ledger, not a device.
         */
        val TRANSIENT_KEYS = setOf(
            KEY_BACKUP_TREE,
            KEY_BACKUP_LAST_AT,
            KEY_BACKUP_LAST_COUNT,
            KEY_BACKUP_LAST_REVISION,
            KEY_BACKUP_KEY,
            KEY_BACKUP_SALT,
            KEY_BACKUP_ROUNDS,
        )

        /** Six chips fit the Quick Add sheet two rows deep (05 §5.6). */
        const val RECENT_CATEGORY_LIMIT = 6
    }
}
