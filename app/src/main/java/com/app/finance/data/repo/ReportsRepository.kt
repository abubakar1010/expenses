package com.app.finance.data.repo

import com.app.finance.core.money.Money
import com.app.finance.data.db.AppDatabase
import com.app.finance.data.db.dao.ExpenseWithCategory
import com.app.finance.domain.model.Nature
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * The Reports screen's reads — 04 §7's "Custom date range, fixed/variable
 * split, top expenses", noted there as "**ledger-backed rather than
 * rollup-backed**".
 *
 * **This is the one place in the app that is supposed to read the ledger
 * directly**, and 03 §5.3 says why in as many words:
 *
 * > "Range queries are a deliberate exception to the rollup strategy: they are
 * > invoked from the reports screen on explicit user action, not on every
 * > dashboard render, so a bounded index scan is acceptable there."
 *
 * Everywhere else, a read that scanned `expense` for a total would be a defect;
 * here it is the design. The distinction is not size but frequency — nothing on
 * this screen runs unless the user asked for a range, and no frame budget
 * depends on it.
 *
 * Every query filters `status = 0`, like every other read in the app, so
 * pending rows from recurring rules stay out of the figures until confirmed.
 */
class ReportsRepository(db: AppDatabase) {

    private val dao = db.expenseDao()

    fun observeTotal(from: LocalDate, to: LocalDate): Flow<Money> =
        dao.observeTotalInRange(from.toEpochDay(), to.toEpochDay()).map(::Money)

    /**
     * Per-nature totals, keyed by [Nature] so the caller can hand them straight
     * to `SpendMix.ofTotals` — the same apportionment the dashboard uses, so
     * the two surfaces cannot disagree about a percentage.
     */
    fun observeMix(from: LocalDate, to: LocalDate): Flow<Map<Nature, Money>> =
        dao.observeNatureTotalsInRange(from.toEpochDay(), to.toEpochDay()).map { rows ->
            rows.associate { Nature.fromCode(it.nature) to Money(it.totalMinor) }
        }

    /** FR-AN-08's shape — the same five, over a range instead of a period. */
    fun observeLargest(from: LocalDate, to: LocalDate, limit: Int = LARGEST): Flow<List<ExpenseWithCategory>> =
        dao.observeLargestInRange(from.toEpochDay(), to.toEpochDay(), limit)

    fun observeCount(from: LocalDate, to: LocalDate): Flow<Int> =
        dao.observeNatureTotalsInRange(from.toEpochDay(), to.toEpochDay())
            .map { rows -> rows.sumOf { it.txnCount } }

    private companion object {
        const val LARGEST = 5
    }
}
