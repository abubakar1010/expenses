package com.app.finance.data.repo

import com.app.finance.core.time.Period
import com.app.finance.data.db.AppDatabase
import com.app.finance.data.db.dao.AppMetaDao
import com.app.finance.data.db.entity.AppMetaEntity
import com.app.finance.domain.model.PaymentMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock

/**
 * Preferences and entry-form defaults.
 *
 * 04 §2: a Room table rather than DataStore. For a handful of keys, DataStore
 * would add a dependency, a second persistence mechanism to reason about, and
 * another file opened during startup — against a budget that has none of those
 * to spare. Keeping it in the database also means the defaults are written in
 * the same transaction as the expense that changed them.
 */
class AppMetaRepository(
    db: AppDatabase,
    private val clock: Clock,
) {
    private val dao = db.appMetaDao()

    suspend fun lastCategoryId(): Long? =
        dao.get(AppMetaDao.KEY_LAST_CATEGORY)?.toLongOrNull()

    suspend fun lastPaymentMethod(): PaymentMethod =
        dao.get(AppMetaDao.KEY_LAST_METHOD)
            ?.toIntOrNull()
            ?.let { code -> PaymentMethod.entries.firstOrNull { it.code == code } }
            ?: PaymentMethod.DEFAULT

    /**
     * The six chips on the Quick Add sheet (05 §5.6).
     *
     * "Spending is habitual — a handful of categories cover most days." Ordered
     * most-recent-first, capped at six because that is what fits two rows above
     * the keypad without pushing the Save button out of the thumb arc.
     */
    fun observeRecentCategoryIds(): Flow<List<Long>> =
        dao.observe(AppMetaDao.KEY_RECENT_CATEGORIES).map { raw ->
            raw?.split(',')
                ?.mapNotNull(String::toLongOrNull)
                ?.take(AppMetaDao.RECENT_CATEGORY_LIMIT)
                .orEmpty()
        }

    suspend fun recentCategoryIds(): List<Long> =
        dao.get(AppMetaDao.KEY_RECENT_CATEGORIES)
            ?.split(',')
            ?.mapNotNull(String::toLongOrNull)
            ?.take(AppMetaDao.RECENT_CATEGORY_LIMIT)
            .orEmpty()

    suspend fun lastViewedPeriod(): Period? =
        dao.get(AppMetaDao.KEY_LAST_PERIOD)?.toIntOrNull()?.let(::Period)

    suspend fun setLastViewedPeriod(period: Period) =
        put(AppMetaDao.KEY_LAST_PERIOD, period.ym.toString())

    suspend fun schemaVersion(): Int? = dao.get(AppMetaDao.KEY_SCHEMA_VERSION)?.toIntOrNull()

    suspend fun put(key: String, value: String) =
        dao.put(AppMetaEntity(key, value, clock.millis()))
}
