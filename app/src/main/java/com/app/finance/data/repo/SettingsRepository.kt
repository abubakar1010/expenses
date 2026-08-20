package com.app.finance.data.repo

import androidx.room.withTransaction
import com.app.finance.data.db.AppDatabase
import com.app.finance.data.db.Schema
import com.app.finance.data.db.dao.AppMetaDao
import com.app.finance.data.db.dao.AppMetaDao.Companion.KEY_APP_LOCK
import com.app.finance.data.db.dao.AppMetaDao.Companion.KEY_SECURE_SCREEN
import com.app.finance.data.db.entity.AppMetaEntity
import com.app.finance.domain.model.ThemeChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock

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
