package com.app.finance.dev

import androidx.room.withTransaction
import com.app.finance.core.text.NameKey
import com.app.finance.core.time.Period
import com.app.finance.data.db.AppDatabase
import com.app.finance.data.db.entity.BudgetEntity
import com.app.finance.data.db.entity.CategoryEntity
import com.app.finance.data.db.entity.ExpenseEntity
import com.app.finance.data.db.entity.IncomeEntryEntity
import com.app.finance.data.db.entity.IncomeSourceEntity
import com.app.finance.domain.model.IncomeKind
import com.app.finance.domain.model.Nature
import kotlinx.coroutines.flow.first
import java.util.UUID
import kotlin.random.Random

/**
 * Five years of data, for the M4 exit criterion.
 *
 * > `| M4 | Dashboard analytics | Dashboard renders in ≤ 300 ms with 5 years
 * > seeded data |`
 *
 * **Debug source set only.** It is not compiled into a release build at all —
 * not stripped by R8, not guarded by a flag, simply not present, which is the
 * only form of "test code cannot reach production" worth relying on. The
 * `androidTest` variant compiles against the debug app, so `DashboardScaleTest`
 * uses this generator rather than keeping a second copy in step with it.
 *
 * **Deterministic.** A fixed [Random] seed means two runs produce identical
 * data, so a benchmark comparing yesterday's number with today's is comparing
 * the same workload. A random one would make every regression arguable.
 *
 * The shape matters as much as the volume. 03 §9 sizes the tables at roughly
 * 9,000 expenses and 400 income entries over five years, and PRD §1 describes
 * the income that has to be represented: "five months earn nothing and the
 * sixth earns a year's worth". A uniform generator would produce a dashboard
 * whose deltas are all zero, whose trend is a flat line and whose alerts never
 * fire — one where every metric under test happens to sit in its least
 * interesting state.
 */
object SeedFiveYears {

    /** 03 §9's sizing, and the criterion's own words. */
    const val PERIODS = 60

    private const val SEED = 20260819L

    data class Counts(val expenses: Int, val income: Int, val periods: Int)

    /**
     * The two corpora, because the documents describe two and they are not the
     * same database.
     *
     * [INSTALL] is what a real phone holds: the thirteen seeded leaves and the
     * traffic they attract. `DashboardScaleTest`'s claim — that a dashboard
     * read is bounded by the category tree rather than by history — is a claim
     * about *that* number, not one this object chose, which is why it stayed
     * the default.
     *
     * [BENCHMARK] is 02 §3.1's, quoted exactly: "a seeded database of 5 years,
     * 20,000 expenses, 400 income entries, 60 categories". Every NFR-PERF
     * figure is defined against it, so measuring on anything smaller measures
     * the wrong database — and until this existed, the only corpus available
     * was a fifth of the size the targets are written about.
     */
    enum class Scale(
        val variable: IntRange,
        val categoryTarget: Int,
        /** Payments per source per period. One is a salary; a freelancer is not. */
        val incomeRepeats: IntRange,
    ) {
        INSTALL(8..17, 0, 1..1),
        BENCHMARK(11..23, 60, 1..4),
    }

    /**
     * Wipes the ledger and regenerates it.
     *
     * At [Scale.INSTALL] the category tree is left alone: the thirteen seeded
     * leaves are what a real install has, and the bounded-row claim under test
     * is a claim about *that* number rather than about a number this function
     * chose. At [Scale.BENCHMARK] the tree is grown to 02 §3.1's sixty, which
     * is the worst case those bounds are supposed to hold at.
     */
    suspend fun into(
        db: AppDatabase,
        endingAt: Period,
        scale: Scale = Scale.INSTALL,
    ): Counts {
        val random = Random(SEED)
        val now = System.currentTimeMillis()

        if (scale.categoryTarget > 0) growTree(db, scale.categoryTarget, now)

        val leaves = db.categoryDao().observeSelectableLeaves().first().map(::toSeed)
        require(leaves.isNotEmpty()) { "seed the category tree before the ledger" }

        val sources = db.withTransaction {
            val sql = db.openHelper.writableDatabase
            // Rollups included: the triggers will rebuild them row by row, which
            // is the same path a real ledger takes and the state the exit
            // criterion is about.
            listOf(
                "DELETE FROM expense",
                "DELETE FROM income_entry",
                "DELETE FROM budget",
                "DELETE FROM rollup_expense_month",
                "DELETE FROM rollup_income_month",
            ).forEach(sql::execSQL)
            ensureSources(db, now)
        }

        var expenses = 0
        var income = 0
        val periods = endingAt.trailing(PERIODS)

        // One transaction per period rather than one for all sixty: the triggers
        // fire per row either way, and a single ten-thousand-row transaction
        // holds the write lock long enough to matter on eMMC storage.
        periods.forEach { period ->
            db.withTransaction {
                expenses += seedExpenses(db, period, leaves, random, now, scale)
                income += seedIncome(db, period, sources, random, now, scale)
                seedBudgets(db, period, leaves, now)
            }
        }
        return Counts(expenses = expenses, income = income, periods = periods.size)
    }

    /**
     * Grows the tree to [target] rows by adding leaves under the existing
     * roots, round-robin.
     *
     * Nature is copied from the root rather than chosen: the depth-and-nature
     * trigger requires a child to match its parent, so anything else would be
     * refused by the database — which is the trigger doing its job.
     */
    private suspend fun growTree(db: AppDatabase, target: Int, now: Long) {
        val dao = db.categoryDao()
        val roots = dao.roots()
        require(roots.isNotEmpty()) { "seed the category tree before growing it" }

        var total = dao.observeAll().first().size
        var i = 0
        while (total < target) {
            val root = roots[i % roots.size]
            val name = "Bench ${i + 1}"
            dao.insert(
                CategoryEntity(
                    uuid = UUID.randomUUID().toString(),
                    parentId = root.id,
                    name = name,
                    nameKey = NameKey.of(name),
                    nature = root.nature,
                    sortOrder = 1_000 + i,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            total++
            i++
        }
    }

    // ------------------------------------------------------------- expenses

    private suspend fun seedExpenses(
        db: AppDatabase,
        period: Period,
        leaves: List<LeafSeed>,
        random: Random,
        now: Long,
        scale: Scale,
    ): Int {
        val days = period.daysInMonth()
        val firstDay = period.firstDay().toEpochDay()
        var written = 0

        leaves.forEach { leaf ->
            val count = when (leaf.nature) {
                // Rent and fees are one payment a month, not thirty.
                Nature.FIXED -> 1
                Nature.VARIABLE -> random.nextInt(scale.variable.first, scale.variable.last + 1)
                // Real but not plannable — most months nothing, some a lot.
                Nature.UNPREDICTABLE ->
                    if (random.nextInt(100) < 35) random.nextInt(1, 3) else 0
            }
            repeat(count) {
                val day = if (leaf.nature == Nature.FIXED) 1 else random.nextInt(1, days + 1)
                db.expenseDao().insert(
                    ExpenseEntity(
                        uuid = UUID.randomUUID().toString(),
                        categoryId = leaf.id,
                        amountMinor = leaf.typical / 2 + random.nextLong(leaf.typical),
                        spentOn = firstDay + day - 1,
                        periodYm = period.ym,
                        paymentMethod = random.nextInt(0, 4),
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                written++
            }
        }
        return written
    }

    // --------------------------------------------------------------- income

    private suspend fun ensureSources(db: AppDatabase, now: Long): List<SourceSeed> =
        SOURCES.map { seed ->
            val key = NameKey.of(seed.name)
            val existing = db.incomeDao().sourceByKey(key)
            val id = existing?.id ?: db.incomeDao().insertSource(
                IncomeSourceEntity(
                    uuid = UUID.randomUUID().toString(),
                    name = seed.name,
                    nameKey = key,
                    kind = seed.kind.code,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            seed.copy(id = id)
        }

    private suspend fun seedIncome(
        db: AppDatabase,
        period: Period,
        sources: List<SourceSeed>,
        random: Random,
        now: Long,
        scale: Scale,
    ): Int {
        val firstDay = period.firstDay().toEpochDay()
        var written = 0

        sources.forEach { source ->
            // The whole reason the income screen defaults to a year: a farming
            // source earns nothing for most of it and then everything at once,
            // and a generator that smoothed that would produce data on which
            // none of this app's design decisions make sense.
            val arrives = when {
                source.kind == IncomeKind.STABLE -> true
                source.harvestMonths.isEmpty() -> random.nextInt(100) < 40
                else -> period.month in source.harvestMonths
            }
            if (!arrives) return@forEach

            val payments =
                random.nextInt(scale.incomeRepeats.first, scale.incomeRepeats.last + 1)
            repeat(payments) {
                db.incomeDao().insertEntry(
                    IncomeEntryEntity(
                        uuid = UUID.randomUUID().toString(),
                        sourceId = source.id,
                        amountMinor = source.typical / 2 + random.nextLong(source.typical),
                        earnedOn = firstDay + random.nextInt(0, period.daysInMonth()),
                        periodYm = period.ym,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                written++
            }
        }
        return written
    }

    // -------------------------------------------------------------- budgets

    private suspend fun seedBudgets(
        db: AppDatabase,
        period: Period,
        leaves: List<LeafSeed>,
        now: Long,
    ) {
        leaves.forEach { leaf ->
            db.budgetDao().insert(
                BudgetEntity(
                    uuid = UUID.randomUUID().toString(),
                    categoryId = leaf.id,
                    periodYm = period.ym,
                    // Near the typical monthly total, so some categories land
                    // over and some under. A dashboard where nothing is ever
                    // over exercises none of its own alert paths.
                    limitMinor = leaf.monthlyLimit,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    // ---------------------------------------------------------------- seeds

    data class LeafSeed(
        val id: Long,
        val nature: Nature,
        /** A typical single transaction, in paisa. */
        val typical: Long,
        val monthlyLimit: Long,
    )

    private data class SourceSeed(
        val id: Long = 0,
        val name: String,
        val kind: IncomeKind,
        val typical: Long,
        /** Empty for irregular sources; otherwise the months it arrives in. */
        val harvestMonths: Set<Int> = emptySet(),
    )

    /**
     * Amounts by nature rather than by name, so the generator keeps working if
     * the seeded tree changes. Fixed costs are large and monthly, variable ones
     * small and frequent, unpredictable ones large and rare — which is the
     * distinction the whole `Nature` enum exists to make.
     */
    private fun toSeed(category: CategoryEntity): LeafSeed {
        val nature = Nature.fromCode(category.nature)
        val typical = when (nature) {
            Nature.FIXED -> 800_000L
            Nature.VARIABLE -> 40_000L
            Nature.UNPREDICTABLE -> 250_000L
        }
        return LeafSeed(
            id = category.id,
            nature = nature,
            typical = typical,
            monthlyLimit = when (nature) {
                Nature.FIXED -> typical
                Nature.VARIABLE -> typical * 12
                Nature.UNPREDICTABLE -> typical * 2
            },
        )
    }

    private val SOURCES = listOf(
        SourceSeed(name = "Salary", kind = IncomeKind.STABLE, typical = 3_000_000),
        SourceSeed(
            name = "Farming",
            kind = IncomeKind.VARIABLE,
            typical = 12_000_000,
            harvestMonths = setOf(4, 5, 11, 12),
        ),
        SourceSeed(name = "Real estate", kind = IncomeKind.STABLE, typical = 1_200_000),
        SourceSeed(name = "Consulting", kind = IncomeKind.VARIABLE, typical = 800_000),
    )
}
