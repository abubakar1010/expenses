package com.app.finance.data.repo

import androidx.room.withTransaction
import com.app.finance.data.db.AppDatabase
import com.app.finance.data.db.Schema
import com.app.finance.data.db.dao.AppMetaDao
import com.app.finance.data.db.dao.AppMetaDao.Companion.KEY_APP_LOCK
import com.app.finance.data.db.dao.AppMetaDao.Companion.KEY_SECURE_SCREEN
import com.app.finance.data.db.entity.AppMetaEntity
import com.app.finance.data.export.BackupCodec
import com.app.finance.domain.model.BackupInterval
import com.app.finance.domain.model.BackupSettings
import com.app.finance.domain.model.ThemeChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.util.Base64

/**
 * The three maintenance actions 04 §7 puts on the Settings screen, plus the
 * theme preference.
 *
 * They live together because they share a property nothing else in the app has:
 * each one operates on the database as a whole rather than on a row, and each
 * runs in a single transaction so there is no state in between.
 */
class SettingsRepository(
    private val db: AppDatabase,
    private val clock: Clock,
) {
    private val meta = db.appMetaDao()

    // --- theme ---------------------------------------------------------------

    /**
     * 04 §7 lists "theme" among Settings' contents; PRD §7 puts dark mode at P1.
     *
     * The default is [ThemeChoice.SYSTEM] rather than light, even though 05 §2
     * chooses light as the app's own default. A user who has set their phone to
     * dark has already answered this question, and asking again by ignoring them
     * is not a design decision, it is a bug.
     */
    fun observeTheme(): Flow<ThemeChoice> =
        meta.observe(AppMetaDao.KEY_THEME).map(ThemeChoice::fromStored)

    suspend fun theme(): ThemeChoice = ThemeChoice.fromStored(meta.get(AppMetaDao.KEY_THEME))

    suspend fun setTheme(choice: ThemeChoice) =
        meta.put(AppMetaEntity(AppMetaDao.KEY_THEME, choice.stored, clock.millis()))

    // --- privacy (NFR-SEC-04, FR-APP-04) -------------------------------------

    /**
     * Both are stored off by default, and both requirements say "optional" in
     * as many words — NFR-SEC-04's `FLAG_SECURE` is "applied *optionally*" and
     * FR-APP-04 is an "*optional* app-lock". A privacy control the user did not
     * ask for is a surprise, and in `FLAG_SECURE`'s case a surprise that breaks
     * their screenshots.
     */
    fun observeSecureScreen(): Flow<Boolean> = meta.observe(KEY_SECURE_SCREEN).map { it == ON }

    suspend fun setSecureScreen(on: Boolean) =
        meta.put(AppMetaEntity(KEY_SECURE_SCREEN, if (on) ON else OFF, clock.millis()))

    fun observeAppLock(): Flow<Boolean> = meta.observe(KEY_APP_LOCK).map { it == ON }

    suspend fun setAppLock(on: Boolean) =
        meta.put(AppMetaEntity(KEY_APP_LOCK, if (on) ON else OFF, clock.millis()))

    // --- FR-DAT-10: the first launch after an install -------------------------

    /**
     * Whether to offer a restore before anything is entered.
     *
     * Two conditions, and the second is there for the upgrade. `onboarded` has
     * been declared since M1 and never written, so **every existing install has
     * it absent** — on the flag alone, a user who has kept this ledger for a
     * year would be met by a welcome screen offering to replace it. Requiring an
     * empty ledger as well makes the question only reach the installs it is
     * actually about.
     *
     * The `&&` short-circuits, which matters: this flow re-emits on every
     * `app_meta` write, and `ExpenseRepository` writes one on every save. Once
     * the flag is set the count is never run again.
     */
    fun observeNeedsWelcome(): Flow<Boolean> =
        meta.observe(AppMetaDao.KEY_ONBOARDED).map { stored ->
            stored == null && db.backupDao().ledgerEntryCount() == 0
        }

    /** Answered — by restoring, or by starting fresh. Either way, not asked again. */
    suspend fun setOnboarded() =
        meta.put(AppMetaEntity(AppMetaDao.KEY_ONBOARDED, ON, clock.millis()))

    // --- backup (FR-DAT-07 … FR-DAT-11) --------------------------------------

    /**
     * Read as a whole rather than key by key.
     *
     * Every decision the backup makes — is it due, where does it go, does it
     * need a passphrase, how many to keep — depends on several of these at once,
     * and reading them one at a time on the launch path would be six `app_meta`
     * round trips to answer one question.
     */
    suspend fun backupSettings() = BackupSettings(
        treeUri = meta.get(AppMetaDao.KEY_BACKUP_TREE),
        interval = BackupInterval.fromStored(meta.get(AppMetaDao.KEY_BACKUP_INTERVAL)),
        keep = meta.get(AppMetaDao.KEY_BACKUP_KEEP)?.toIntOrNull()?.coerceIn(KEEP_RANGE) ?: KEEP_DEFAULT,
        // FR-DAT-11 — "optional and off by default". No flag of its own: it is
        // on exactly when there is a key, which is a fact that cannot disagree
        // with itself. See AppMetaDao.KEY_BACKUP_KEY.
        encrypted = meta.get(AppMetaDao.KEY_BACKUP_KEY) != null,
        lastAt = meta.get(AppMetaDao.KEY_BACKUP_LAST_AT)?.toLongOrNull(),
        lastCount = meta.get(AppMetaDao.KEY_BACKUP_LAST_COUNT)?.toIntOrNull(),
        lastRevision = meta.get(AppMetaDao.KEY_BACKUP_LAST_REVISION)?.toLongOrNull(),
    )

    /** Emits on any change to any backup key, so the screen can follow along. */
    fun observeBackupSettings(): Flow<BackupSettings> =
        meta.observeBackupKeys().distinctUntilChanged().map { backupSettings() }

    suspend fun setBackupFolder(treeUri: String?) {
        if (treeUri == null) meta.remove(AppMetaDao.KEY_BACKUP_TREE)
        else meta.put(AppMetaEntity(AppMetaDao.KEY_BACKUP_TREE, treeUri, clock.millis()))
    }

    suspend fun setBackupInterval(interval: BackupInterval) =
        meta.put(AppMetaEntity(AppMetaDao.KEY_BACKUP_INTERVAL, interval.stored, clock.millis()))

    suspend fun setBackupKeep(keep: Int) =
        meta.put(
            AppMetaEntity(
                AppMetaDao.KEY_BACKUP_KEEP,
                keep.coerceIn(KEEP_RANGE).toString(),
                clock.millis(),
            ),
        )

    /**
     * The key automatic backups are sealed with, or null when they are not.
     *
     * Read on every backup and derived on none of them: `BackupCodec.Secret`
     * explains why a launch-time job cannot afford 210,000 rounds of PBKDF2.
     */
    suspend fun backupSecret(): BackupCodec.Secret? {
        val key = meta.get(AppMetaDao.KEY_BACKUP_KEY)?.decode() ?: return null
        val salt = meta.get(AppMetaDao.KEY_BACKUP_SALT)?.decode() ?: return null
        val rounds = meta.get(AppMetaDao.KEY_BACKUP_ROUNDS)?.toIntOrNull() ?: return null
        return BackupCodec.secretFrom(key, salt, rounds)
    }

    /** Null turns encryption off, taking the key with it. */
    suspend fun setBackupSecret(secret: BackupCodec.Secret?) {
        if (secret == null) {
            listOf(
                AppMetaDao.KEY_BACKUP_KEY,
                AppMetaDao.KEY_BACKUP_SALT,
                AppMetaDao.KEY_BACKUP_ROUNDS,
            ).forEach { meta.remove(it) }
            return
        }
        val now = clock.millis()
        meta.put(AppMetaEntity(AppMetaDao.KEY_BACKUP_KEY, secret.keyBytes.encode(), now))
        meta.put(AppMetaEntity(AppMetaDao.KEY_BACKUP_SALT, secret.saltBytes.encode(), now))
        meta.put(AppMetaEntity(AppMetaDao.KEY_BACKUP_ROUNDS, secret.rounds.toString(), now))
    }

    /**
     * Records a backup that completed — and only one that did.
     *
     * Written after the file is closed and renamed, never before. A crash
     * half-way through must leave the app believing it has not backed up since
     * the previous success, because the alternative is a phone that quietly
     * stops taking backups after the first failure.
     */
    suspend fun recordBackup(at: Long, rows: Int, revision: Long) {
        meta.put(AppMetaEntity(AppMetaDao.KEY_BACKUP_LAST_AT, at.toString(), at))
        meta.put(AppMetaEntity(AppMetaDao.KEY_BACKUP_LAST_COUNT, rows.toString(), at))
        meta.put(AppMetaEntity(AppMetaDao.KEY_BACKUP_LAST_REVISION, revision.toString(), at))
    }

    /**
     * Forgets what this phone has already written, keeping the user's choices.
     *
     * Called after a restore. The file carried `backup_interval`, `backup_keep`
     * and `backup_encrypted` — those are decisions the user made and would
     * otherwise have to make again — but the folder grant and the last-backup
     * record describe the phone the backup came *from*, and
     * `AppMetaDao.TRANSIENT_KEYS` keeps them out of the file for that reason.
     * This clears anything an older file may still carry.
     */
    suspend fun forgetBackupHistory() {
        AppMetaDao.TRANSIENT_KEYS.forEach { meta.remove(it) }
    }

    // java.util.Base64 rather than android.util: it is in the platform at API 26
    // and keeps this class readable from the JVM suite.
    private fun ByteArray.encode(): String = Base64.getEncoder().encodeToString(this)

    private fun String.decode(): ByteArray? = runCatching { Base64.getDecoder().decode(this) }.getOrNull()

    // --- 03 §6's third defence -----------------------------------------------

    /**
     * Truncates and regenerates both rollup tables from the ledger.
     *
     * > "A user-invocable 'rebuild aggregates' action in settings that truncates
     * > and regenerates both rollup tables inside one transaction. This is the
     * > recovery path if a future migration bug corrupts them, and it costs a
     * > few hundred milliseconds even at five years of data."
     *
     * It should never change a figure. `assertion19` asserts that the rebuild
     * reproduces the trigger-maintained state exactly, which is what makes this
     * safe to offer to a user rather than a debug tool.
     */
    suspend fun rebuildAggregates() = db.withTransaction {
        Schema.REBUILD_ROLLUPS.forEach { db.openHelper.writableDatabase.execSQL(it) }
    }

    // --- FR-DAT-06 -----------------------------------------------------------

    /**
     * Deletes everything and re-seeds — the state a fresh install is in.
     *
     * Re-seeding is not a nicety. `Schema.SEED` creates the three roots and
     * thirteen leaves, and an expense must reference a leaf (FR-EXP-04, enforced
     * by trigger), so a wipe that left the category table empty would leave an
     * app that cannot record anything. "Delete all data" has to produce a fresh
     * install, not a broken one.
     *
     * One transaction, so there is no instant at which the user has neither
     * their data nor a working app.
     *
     * The confirmation is the caller's job, and 05 §8 is specific about its
     * shape: "The exception is 'delete all data,' which requires typed
     * confirmation, because there is no undo for it."
     */
    private companion object {
        const val ON = "1"
        const val OFF = "0"

        /**
         * How many generations to keep -- FR-DAT-09.
         *
         * Five, because the failure a backup folder actually protects against is
         * rarely "the phone died" and often "I deleted a category last Tuesday
         * and only noticed today". One generation cannot recover from a mistake
         * that was itself backed up; five covers a working week of them, at a
         * few hundred kilobytes each.
         */
        const val KEEP_DEFAULT = 5
        val KEEP_RANGE = 1..20
    }

    suspend fun deleteAllData() = db.withTransaction {
        val sql = db.openHelper.writableDatabase
        Schema.WIPE_ORDER.forEach(sql::execSQL)
        // Reset the autoincrement counters too, so a fresh install really is
        // what the user gets rather than one whose first expense is id 9,001.
        sql.execSQL("DELETE FROM sqlite_sequence")
        Schema.SEED.forEach(sql::execSQL)
    }
}
