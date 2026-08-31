package com.app.finance.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.data.db.entity.BudgetEntity
import com.app.finance.core.text.NameKey
import com.app.finance.data.db.entity.CategoryEntity
import com.app.finance.data.db.entity.ExpenseEntity
import com.app.finance.data.db.entity.IncomeEntryEntity
import com.app.finance.data.db.entity.IncomeSourceEntity
import com.app.finance.data.db.dao.ExpenseDao
import com.app.finance.data.db.dao.RollupDao
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The nineteen behavioural assertions recorded in 03-database-design.md §10.1,
 * ported one-to-one, plus coverage for the triggers that document specified but
 * `schema_v1.sql` did not implement.
 *
 * 04-system-architecture.md §9: "The DAO tests are the highest-value suite in
 * the project, because the trigger-maintained rollups are the only place where
 * the same fact is stored twice."
 */
@RunWith(AndroidJUnit4::class)
class SchemaAssertionsTest {

    private lateinit var db: AppDatabase

    private val now = 1_755_000_000_000L
    private val aug = 202608
    private val sep = 202609
    private val augDay = 20_678L // an epoch day inside August 2026
    private val sepDay = 20_710L

    @Before
    fun setUp() {
        db = AppDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = db.close()

    // ---------------------------------------------------------------- helpers

    private fun exec(sql: String) = db.openHelper.writableDatabase.execSQL(sql)

    private fun scalar(sql: String): Long =
        db.openHelper.writableDatabase.query(sql).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
        }

    private suspend fun leafId(name: String): Long =
        db.categoryDao().observeAll().let { _ ->
            db.categoryDao().roots()
            requireNotNull(
                db.openHelper.writableDatabase
                    .query("SELECT id FROM category WHERE name = '$name'")
                    .use { if (it.moveToFirst()) it.getLong(0) else null },
            ) { "seed category '$name' not found" }
        }

    private suspend fun rootId(name: String): Long =
        db.categoryDao().roots().first { it.name == name }.id

    private fun expense(
        categoryId: Long,
        amount: Long,
        day: Long = augDay,
        period: Int = aug,
        status: Int = 0,
    ) = ExpenseEntity(
        uuid = java.util.UUID.randomUUID().toString(),
        categoryId = categoryId,
        amountMinor = amount,
        spentOn = day,
        periodYm = period,
        status = status,
        createdAt = now,
        updatedAt = now,
    )

    private fun rollup(period: Int, categoryId: Long): Long =
        scalar("SELECT total_minor FROM rollup_expense_month WHERE period_ym = $period AND category_id = $categoryId")

    private fun rollupCount(period: Int, categoryId: Long): Long =
        scalar("SELECT txn_count FROM rollup_expense_month WHERE period_ym = $period AND category_id = $categoryId")

    // ------------------------------------------------------------ seed sanity

    @Test
    fun seed_creates_three_system_roots_and_thirteen_leaves() = runBlocking {
        // FR-CAT-01 / FR-CAT-02, seeded in the same transaction as the schema.
        val roots = db.categoryDao().roots()
        assertEquals(3, roots.size)
        assertEquals(
            listOf("Fixed Expenses", "Variable Expenses", "Unpredictable Expenses"),
            roots.map { it.name },
        )
        assertTrue("system roots must be flagged", roots.all { it.isSystem })
        assertEquals(13, scalar("SELECT COUNT(*) FROM category WHERE parent_id IS NOT NULL").toInt())
        assertEquals(1, scalar("SELECT COUNT(*) FROM income_source").toInt())
        assertEquals(Schema.VERSION.toString(), db.appMetaDao().get("schema_version"))
    }

    // ------------------------------------------------- 1. nature inheritance

    @Test
    fun assertion01_child_with_conflicting_nature_is_corrected_to_its_parents() = runBlocking {
        val fixed = rootId("Fixed Expenses") // nature = 0
        val id = db.categoryDao().insert(
            CategoryEntity(
                uuid = "c1", parentId = fixed, name = "Water Bill", nameKey = "water bill",
                nature = 2, createdAt = now, updatedAt = now, // deliberately wrong
            ),
        )
        assertEquals(0, db.categoryDao().byId(id)!!.nature)
    }

    // -------------------------------------------------------- 2. depth limit

    @Test
    fun assertion02_third_level_category_insert_aborts() = runBlocking {
        val grocery = leafId("Grocery") // already a child
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                db.categoryDao().insert(
                    CategoryEntity(
                        uuid = "c2", parentId = grocery, name = "Rice", nameKey = "rice",
                        nature = 1, createdAt = now, updatedAt = now,
                    ),
                )
            }
        }
        Unit
    }

    // ----------------------------------------------------- 3 & 4. name scope

    @Test
    fun assertion03_same_leaf_name_under_two_different_roots_is_accepted() = runBlocking {
        // FR-CAT-07 — "Fixed → Misc" and "Variable → Misc" must coexist.
        val fixed = rootId("Fixed Expenses")
        val variable = rootId("Variable Expenses")
        db.categoryDao().insert(
            CategoryEntity(uuid = "m1", parentId = fixed, name = "Misc", nameKey = "misc", nature = 0, createdAt = now, updatedAt = now),
        )
        db.categoryDao().insert(
            CategoryEntity(uuid = "m2", parentId = variable, name = "Misc", nameKey = "misc", nature = 1, createdAt = now, updatedAt = now),
        )
        assertEquals(2, scalar("SELECT COUNT(*) FROM category WHERE name_key = 'misc'").toInt())
    }

    @Test
    fun assertion04_duplicate_root_name_is_rejected_by_the_ifnull_index() {
        // SQL treats NULLs as distinct, so a plain UNIQUE(parent_id, name_key)
        // would let this through. IFNULL(parent_id, -1) is what stops it.
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                db.categoryDao().insert(
                    CategoryEntity(
                        uuid = "r2", parentId = null, name = "Fixed Expenses",
                        nameKey = "fixed expenses", nature = 0, createdAt = now, updatedAt = now,
                    ),
                )
            }
        }
    }

    // ---------------------------------------------------------- 5, 6, 7. budgets

    @Test
    fun assertion05_budget_on_a_root_category_aborts() = runBlocking {
        val fixed = rootId("Fixed Expenses")
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                db.budgetDao().insert(
                    BudgetEntity(uuid = "b1", categoryId = fixed, periodYm = aug, limitMinor = 100_000, createdAt = now, updatedAt = now),
                )
            }
        }
        Unit
    }

    @Test
    fun assertion06_budget_on_a_leaf_is_accepted() = runBlocking {
        val grocery = leafId("Grocery")
        db.budgetDao().insert(
            BudgetEntity(uuid = "b2", categoryId = grocery, periodYm = aug, limitMinor = 800_000, createdAt = now, updatedAt = now),
        )
        assertEquals(800_000L, db.budgetDao().forCategory(grocery, aug)!!.limitMinor)
    }

    @Test
    fun assertion07_second_budget_for_the_same_category_and_period_is_rejected() = runBlocking {
        val grocery = leafId("Grocery")
        db.budgetDao().insert(
            BudgetEntity(uuid = "b3", categoryId = grocery, periodYm = aug, limitMinor = 800_000, createdAt = now, updatedAt = now),
        )
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                db.budgetDao().insert(
                    BudgetEntity(uuid = "b4", categoryId = grocery, periodYm = aug, limitMinor = 900_000, createdAt = now, updatedAt = now),
                )
            }
        }
        Unit
    }

    // ------------------------------------------------- 8, 9, 10. rollup maths

    @Test
    fun assertion08_two_inserts_accumulate_correctly_in_the_rollup() = runBlocking {
        val grocery = leafId("Grocery")
        db.expenseDao().insert(expense(grocery, 120_000))
        db.expenseDao().insert(expense(grocery, 45_050))
        assertEquals(165_050L, rollup(aug, grocery))
        assertEquals(2L, rollupCount(aug, grocery))
    }

    @Test
    fun assertion09_a_negative_refund_reduces_the_rollup_total() = runBlocking {
        // FR-EXP-06 — refunds are negative amounts, not a separate entity.
        val grocery = leafId("Grocery")
        db.expenseDao().insert(expense(grocery, 120_000))
        db.expenseDao().insert(expense(grocery, -20_000))
        assertEquals(100_000L, rollup(aug, grocery))
        assertEquals(2L, rollupCount(aug, grocery))
    }

    @Test
    fun assertion10_a_zero_amount_is_rejected() = runBlocking {
        val grocery = leafId("Grocery")
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { db.expenseDao().insert(expense(grocery, 0)) }
        }
        Unit
    }

    // ------------------------------------------------- 11, 12. pending status

    @Test
    fun assertion11_a_pending_entry_is_excluded_from_the_rollups() = runBlocking {
        val grocery = leafId("Grocery")
        db.expenseDao().insert(expense(grocery, 50_000, status = 1))
        assertEquals(0L, rollup(aug, grocery))
    }

    @Test
    fun assertion12_confirming_a_pending_entry_adds_it_to_the_rollups() = runBlocking {
        val grocery = leafId("Grocery")
        val id = db.expenseDao().insert(expense(grocery, 50_000, status = 1))
        val pending = db.expenseDao().byId(id)!!
        db.expenseDao().update(pending.copy(status = 0))
        assertEquals(50_000L, rollup(aug, grocery))
        assertEquals(1L, rollupCount(aug, grocery))
    }

    // ------------------------------------------------------------ 13. delete

    @Test
    fun assertion13_delete_decrements_the_rollup_symmetrically() = runBlocking {
        val grocery = leafId("Grocery")
        val id = db.expenseDao().insert(expense(grocery, 70_000))
        db.expenseDao().insert(expense(grocery, 30_000))
        db.expenseDao().delete(db.expenseDao().byId(id)!!)
        assertEquals(30_000L, rollup(aug, grocery))
        assertEquals(1L, rollupCount(aug, grocery))
    }

    // ----------------------------------------------------- 14, 15. re-filing

    @Test
    fun assertion14and15_recategorising_moves_the_total_between_buckets() = runBlocking {
        // FR-EXP-07 — editing an old expense must recalculate prior periods.
        // The update trigger decrements the old (period, category) then
        // increments the new, so this is correct by construction.
        val grocery = leafId("Grocery")
        val transport = leafId("Transport")

        val id = db.expenseDao().insert(expense(grocery, 90_000, day = augDay, period = aug))
        assertEquals(90_000L, rollup(aug, grocery))

        val moved = db.expenseDao().byId(id)!!.copy(
            categoryId = transport, spentOn = sepDay, periodYm = sep,
        )
        db.expenseDao().update(moved)

        assertEquals("must leave the old bucket", 0L, rollup(aug, grocery))
        assertEquals("and land in the new one", 90_000L, rollup(sep, transport))
    }

    // ------------------------------------------------ 16. referential safety

    @Test
    fun assertion16_deleting_a_referenced_category_is_rejected() = runBlocking {
        // Also proves PRAGMA foreign_keys is actually ON at runtime: without
        // it SQLite would happily orphan the expense row.
        val grocery = leafId("Grocery")
        db.expenseDao().insert(expense(grocery, 25_000))
        assertThrows(SQLiteConstraintException::class.java) {
            exec("DELETE FROM category WHERE id = $grocery")
        }
        Unit
    }

    // --------------------------------------------------- 17. recurring rules

    @Test
    fun assertion17_a_rule_targeting_both_a_category_and_a_source_is_rejected() = runBlocking {
        val grocery = leafId("Grocery")
        val salary = scalar("SELECT id FROM income_source WHERE name_key = 'salary'")
        assertThrows(SQLiteConstraintException::class.java) {
            exec(
                """
                INSERT INTO recurring_rule
                    (uuid, target, category_id, source_id, amount_minor, frequency,
                     anchor_day, next_due_day, created_at, updated_at)
                VALUES ('rr1', 0, $grocery, $salary, 100000, 0, 1, $augDay, $now, $now)
                """.trimIndent(),
            )
        }
        Unit
    }

    // ------------------------------------------------ 18. income source keys

    @Test
    fun assertion18_a_duplicate_income_source_name_key_is_rejected() {
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                db.incomeDao().insertSource(
                    IncomeSourceEntity(
                        uuid = "s2", name = "salary ", nameKey = "salary",
                        createdAt = now, updatedAt = now,
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------- 19. the invariant

    @Test
    fun assertion19_rebuilt_rollups_match_the_trigger_maintained_state_exactly() = runBlocking {
        val grocery = leafId("Grocery")
        val transport = leafId("Transport")
        val salary = scalar("SELECT id FROM income_source WHERE name_key = 'salary'")

        db.expenseDao().insert(expense(grocery, 120_000))
        db.expenseDao().insert(expense(grocery, -15_000))
        db.expenseDao().insert(expense(transport, 60_000, day = sepDay, period = sep))
        db.expenseDao().insert(expense(transport, 999_999, status = 1)) // pending, excluded
        db.incomeDao().insertEntry(
            IncomeEntryEntity(
                uuid = "i1", sourceId = salary, amountMinor = 4_800_000,
                earnedOn = augDay, periodYm = aug, createdAt = now, updatedAt = now,
            ),
        )

        val before = snapshotRollups()
        Schema.REBUILD_ROLLUPS.forEach(::exec)
        val after = snapshotRollups()

        // This is the assertion the entire aggregate strategy rests on: the
        // trigger set and the rebuild query must agree, or the rollups are just
        // a second, quietly divergent copy of the truth.
        assertEquals(before, after)
    }

    private fun snapshotRollups(): List<String> = buildList {
        db.openHelper.writableDatabase.query(
            "SELECT period_ym, category_id, total_minor, txn_count FROM rollup_expense_month ORDER BY 1,2",
        ).use { while (it.moveToNext()) add("e:${it.getInt(0)}:${it.getLong(1)}:${it.getLong(2)}:${it.getInt(3)}") }
        db.openHelper.writableDatabase.query(
            "SELECT period_ym, source_id, total_minor, entry_count FROM rollup_income_month ORDER BY 1,2",
        ).use { while (it.moveToNext()) add("i:${it.getInt(0)}:${it.getLong(1)}:${it.getLong(2)}:${it.getInt(3)}") }
    }

    // ============ triggers the docs specified but schema_v1.sql omitted =======

    @Test
    fun income_rollups_are_maintained_by_trigger_on_all_three_mutations() = runBlocking {
        // Without these three triggers rollup_income_month is created and then
        // never written to, so every income figure in the app reads ৳0 while
        // the ledger underneath is perfectly correct — a silent, total failure
        // of the income module.
        val salary = scalar("SELECT id FROM income_source WHERE name_key = 'salary'")
        fun total() = scalar("SELECT total_minor FROM rollup_income_month WHERE period_ym = $aug AND source_id = $salary")

        val id = db.incomeDao().insertEntry(
            IncomeEntryEntity(uuid = "i1", sourceId = salary, amountMinor = 3_600_000, earnedOn = augDay, periodYm = aug, createdAt = now, updatedAt = now),
        )
        assertEquals("insert", 3_600_000L, total())

        db.incomeDao().updateEntry(db.incomeDao().entryById(id)!!.copy(amountMinor = 4_000_000))
        assertEquals("update", 4_000_000L, total())

        db.incomeDao().deleteEntry(db.incomeDao().entryById(id)!!)
        assertEquals("delete", 0L, total())
    }

    @Test
    fun expenses_may_not_reference_a_non_leaf_category() = runBlocking {
        // 03 §4.6 states this trigger exists; the SQL file never defined it.
        val fixed = rootId("Fixed Expenses")
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { db.expenseDao().insert(expense(fixed, 10_000)) }
        }
        Unit
    }

    @Test
    fun reparenting_cannot_create_a_third_level() = runBlocking {
        val grocery = leafId("Grocery")
        val transport = leafId("Transport")
        assertThrows(SQLiteConstraintException::class.java) {
            exec("UPDATE category SET parent_id = $grocery WHERE id = $transport")
        }
        Unit
    }

    @Test
    fun reparenting_a_subcategory_updates_its_effective_nature() = runBlocking {
        // FR-CAT-06 explicitly requires this; nothing in schema_v1.sql did it.
        val grocery = leafId("Grocery") // under Variable, nature = 1
        val unpredictable = rootId("Unpredictable Expenses") // nature = 2
        assertEquals(1, db.categoryDao().byId(grocery)!!.nature)

        exec("UPDATE category SET parent_id = $unpredictable WHERE id = $grocery")

        assertEquals(2, db.categoryDao().byId(grocery)!!.nature)
    }

    @Test
    fun a_budget_cannot_be_moved_onto_a_root_by_update() = runBlocking {
        val grocery = leafId("Grocery")
        val fixed = rootId("Fixed Expenses")
        db.budgetDao().insert(
            BudgetEntity(uuid = "b9", categoryId = grocery, periodYm = aug, limitMinor = 500_000, createdAt = now, updatedAt = now),
        )
        assertThrows(SQLiteConstraintException::class.java) {
            exec("UPDATE budget SET category_id = $fixed WHERE category_id = $grocery")
        }
        Unit
    }

    // ==================== storage-level choices ==============================

    @Test
    fun rollup_and_meta_tables_are_stored_without_rowid() {
        // 03 §4.7 — always accessed by the full composite primary key, so the
        // rowid indirection is pure overhead. Room cannot express this, which
        // is the reason the canonical DDL creates these tables rather than Room.
        listOf("rollup_expense_month", "rollup_income_month", "app_meta").forEach { table ->
            val sql = db.openHelper.writableDatabase
                .query("SELECT sql FROM sqlite_master WHERE type='table' AND name='$table'")
                .use { if (it.moveToFirst()) it.getString(0) else "" }
            assertTrue("$table must be WITHOUT ROWID, got: $sql", sql.contains("WITHOUT ROWID", true))
        }
    }

    @Test
    fun check_constraints_survived_the_canonical_rebuild() {
        val sql = db.openHelper.writableDatabase
            .query("SELECT sql FROM sqlite_master WHERE type='table' AND name='expense'")
            .use { if (it.moveToFirst()) it.getString(0) else "" }
        assertTrue("expense must keep CHECK (amount_minor <> 0): $sql", sql.contains("amount_minor <> 0"))
    }

    @Test
    fun the_paged_ledger_query_is_a_pure_index_walk() {
        // NFR-MAIN-03 requires a documented EXPLAIN QUERY PLAN for every
        // hot-path query confirming index use. A temp B-tree here would mean
        // keyset pagination had quietly stopped working.
        //
        // `ExpenseDao.PAGE`, not a transcription of it. This test used to keep
        // its own simplified copy — `SELECT * FROM expense WHERE status = 0 AND
        // (spent_on, id) < (…)` — which had drifted past the category join, and
        // then past the person join and the shared-expense subquery FR-SHR
        // added. It was explaining a statement the app does not run, which is
        // the defect §22.5 found in the budget-bar plan check and fixed the
        // same way.
        val plan = queryPlan(boundPageQuery())

        assertTrue("expected ix_expense_date to be used, plan was:\n$plan", plan.contains("ix_expense_date"))
        assertTrue("expected no temp B-tree sort, plan was:\n$plan", !plan.contains("TEMP B-TREE"))
    }

    @Test
    fun the_shared_expense_subquery_is_an_index_probe_not_a_scan() {
        // FR-SHR-02 put a correlated subquery on the app's most
        // performance-sensitive read. Per row of a fifty-row page it must be a
        // lookup on `ux_share_expense_person`, whose leading column is
        // `expense_id` — a scan here would be invisible with three shares and
        // quadratic with three thousand, which is exactly the shape NFR-PERF-05
        // exists to prevent.
        val plan = queryPlan(boundPageQuery())

        assertTrue(
            "the share subquery must use ux_share_expense_person, plan was:\n$plan",
            plan.contains("ux_share_expense_person"),
        )
        assertTrue(
            "the share subquery must not scan expense_share, plan was:\n$plan",
            !plan.contains("SCAN s") && !plan.contains("SCAN expense_share"),
        )
        // The payer join is a primary-key lookup on a table with tens of rows.
        // Asserted so a future index change cannot quietly turn it into a scan
        // per ledger row.
        assertTrue(
            "the payer join must not scan person, plan was:\n$plan",
            !plan.contains("SCAN p") && !plan.contains("SCAN person"),
        )
    }

    /**
     * [ExpenseDao.PAGE] with its bind parameters substituted.
     *
     * Every filter is off and the keyset is engaged — the state the ledger is
     * in while the user scrolls, which is the one NFR-PERF-05 measures.
     */
    private fun boundPageQuery(): String = ExpenseDao.PAGE
        .replace(":noKeyset", "0")
        .replace(":lastDay", "20678")
        .replace(":lastId", "5")
        .replace(":fromDay", "-1000000")
        .replace(":toDay", "1000000")
        .replace(":anyCategory", "1")
        .replace(":categoryIds", "-1")
        .replace(":anyMethod", "1")
        .replace(":method", "-1")
        .replace(":anyPerson", "1")
        .replace(":personId", "-1")
        .replace(":noQuery", "1")
        .replace(":query", "''")
        .replace(":hasAmount", "0")
        .replace(":exactAmount", "0")
        .replace(":limit", "50")

    @Test
    fun the_budget_bar_query_never_touches_the_expense_table() {
        // NFR-MAIN-03 requires a documented EXPLAIN QUERY PLAN for every
        // hot-path query. This **is** `RollupDao.observeBudgetBars` — the same
        // string the DAO is annotated with, not a transcription of it.
        //
        // It used to be a copy, under a comment claiming that "the assertion
        // cannot drift away from the query it is about". It had drifted: the
        // copy was missing `c.is_archived AS isArchived`, added to the real
        // query some milestones earlier, so this test was explaining a query
        // the app does not run and would have gone on passing however far the
        // two diverged. `@Query` accepts a compile-time constant, so there is
        // one string now and the claim is true.
        val plan = queryPlan(RollupDao.BUDGET_BARS.replace(":period", "$aug"))

        // The property that keeps the screen flat as history grows: cost is
        // bounded by the leaf count — dozens — not by how many transactions
        // exist. That is what holds NFR-PERF-04 at 300 ms over five years.
        assertTrue("must not read the ledger, plan was:\n$plan", !plan.contains("TABLE expense"))

        // Both joins must be index lookups. A scan here would be invisible at
        // thirteen leaves and quadratic at a hundred.
        assertTrue("budget join must use ux_budget_cat_period, plan was:\n$plan", plan.contains("ux_budget_cat_period"))
        assertTrue(
            "rollup join must be a search, not a scan, plan was:\n$plan",
            plan.contains("SEARCH r") || plan.contains("SEARCH TABLE rollup_expense_month"),
        )
        assertTrue(
            "rollup must not be scanned end to end, plan was:\n$plan",
            !plan.contains("SCAN rollup_expense_month") && !plan.contains("SCAN TABLE rollup_expense_month"),
        )
    }

    @Test
    fun the_period_total_queries_read_only_the_rollups() {
        // 03 §5.1's other two hot reads — the figures the dashboard and the
        // income screen open with.
        listOf(
            "SELECT IFNULL(SUM(total_minor), 0) FROM rollup_expense_month WHERE period_ym = $aug",
            "SELECT IFNULL(SUM(total_minor), 0) FROM rollup_income_month WHERE period_ym = $aug",
        ).forEach { sql ->
            val plan = queryPlan(sql)
            assertTrue("must not read the ledger, plan was:\n$plan", !plan.contains("TABLE expense"))
            assertTrue("must not read income_entry, plan was:\n$plan", !plan.contains("TABLE income_entry"))
        }
    }

    @Test
    fun the_income_breakdown_query_never_touches_the_income_ledger() {
        // NFR-MAIN-03 for M3's hot read. This is `IncomeDao.observeCellsInPeriods`
        // verbatim — the income screen's only aggregate query, and the one every
        // figure on the screen is folded from — with the bound parameters
        // substituted, so the assertion cannot drift away from the query.
        val plan = queryPlan(
            """
            SELECT r.period_ym   AS periodYm,
                   s.id          AS sourceId,
                   s.name        AS sourceName,
                   s.kind        AS kind,
                   r.total_minor AS totalMinor
              FROM rollup_income_month r
              JOIN income_source s ON s.id = r.source_id
             WHERE r.period_ym BETWEEN 202601 AND 202612
            """.trimIndent(),
        )

        // The property that keeps the screen flat as history grows: cost is
        // bounded by (months × sources) — sixty rows for a year — not by how
        // many entries exist.
        assertTrue(
            "must not read the income ledger, plan was:\n$plan",
            !plan.contains("TABLE income_entry"),
        )
        // `rollup_income_month` is WITHOUT ROWID on (period_ym, source_id), so
        // a period range is a prefix of the primary key and must be a search.
        assertTrue(
            "the rollup must be searched, not scanned, plan was:\n$plan",
            plan.contains("SEARCH r") || plan.contains("SEARCH TABLE rollup_income_month"),
        )
        assertTrue(
            "the source join must be a lookup, plan was:\n$plan",
            plan.contains("SEARCH s") || plan.contains("SEARCH TABLE income_source"),
        )
    }

    @Test
    fun the_income_range_fallback_uses_the_date_index() {
        // 03 §5.3's deliberate exception: a range that does not align to month
        // boundaries cannot use the rollup, so it falls back to the ledger —
        // and the fallback must still be a bounded index walk rather than a
        // full scan of five years of entries.
        val plan = queryPlan(
            """
            SELECT e.period_ym        AS periodYm,
                   s.id               AS sourceId,
                   s.name             AS sourceName,
                   s.kind             AS kind,
                   SUM(e.amount_minor) AS totalMinor
              FROM income_entry e
              JOIN income_source s ON s.id = e.source_id
             WHERE e.status = 0 AND e.earned_on BETWEEN 20000 AND 20100
             GROUP BY e.period_ym, s.id
            """.trimIndent(),
        )

        assertTrue(
            "the range must walk ix_income_entry_date, plan was:\n$plan",
            plan.contains("ix_income_entry_date"),
        )
        assertTrue(
            "and must not scan income_entry, plan was:\n$plan",
            !plan.contains("SCAN e") && !plan.contains("SCAN TABLE income_entry"),
        )
    }

    // ------------------------------------ the dashboard's reads (NFR-MAIN-03)

    /**
     * NFR-PERF-04 gives the dashboard 300 ms, and 03 §5.1 says why that holds
     * as the ledger grows: "row count is bounded by the number of leaf
     * categories — dozens, not thousands — **independent of transaction history
     * size**."
     *
     * That is a claim about which tables the screen reads, so these four
     * assertions are the claim itself rather than a proxy for it. Each is the
     * DAO query verbatim with its bound parameters substituted, so an assertion
     * cannot drift away from the statement it is about.
     */
    @Test
    fun the_category_delta_query_never_touches_the_expense_table() {
        val plan = queryPlan(
            """
            SELECT r.period_ym    AS periodYm,
                   c.id           AS categoryId,
                   c.name         AS name,
                   r.total_minor  AS totalMinor
              FROM rollup_expense_month r
              JOIN category c ON c.id = r.category_id
             WHERE r.period_ym BETWEEN ${aug - 3} AND $aug
               AND r.txn_count > 0
            """.trimIndent(),
        )
        assertTrue(
            "the delta query must not read the ledger, plan was:\n" + plan,
            !plan.contains("expense ") && !plan.contains("TABLE expense"),
        )
        // SQLite names the *alias* in a query plan, not the table, so this
        // asked for a string the planner never emits: the real plan reads
        // "SEARCH r USING PRIMARY KEY (period_ym>? AND period_ym<?)", which is
        // a stronger statement than the one the assertion was making.
        assertTrue(
            "and must search the rollup by its primary key, plan was:\n" + plan,
            plan.contains("SEARCH") && plan.contains("PRIMARY KEY"),
        )
    }

    @Test
    fun the_budget_reference_query_reads_only_budget_and_category() {
        val plan = queryPlan(
            """
            SELECT b.period_ym AS periodYm, SUM(b.limit_minor) AS total
              FROM budget b
              JOIN category c ON c.id = b.category_id
             WHERE b.period_ym BETWEEN ${aug - 5} AND $aug
               AND c.is_archived = 0
             GROUP BY b.period_ym
            """.trimIndent(),
        )
        assertTrue(
            "the reference line must not read the ledger, plan was:\n" + plan,
            !plan.contains("TABLE expense"),
        )
    }

    @Test
    fun the_trend_series_query_never_touches_the_expense_table() {
        val plan = queryPlan(
            """
            SELECT period_ym AS periodYm, SUM(total_minor) AS total
              FROM rollup_expense_month
             WHERE period_ym BETWEEN ${aug - 5} AND $aug
             GROUP BY period_ym ORDER BY period_ym
            """.trimIndent(),
        )
        assertTrue(
            "plan was:\n" + plan,
            !plan.contains("TABLE expense") && plan.contains("rollup_expense_month"),
        )
    }

    @Test
    fun the_largest_expenses_query_is_the_one_read_that_touches_the_ledger() {
        // FR-AN-08 asks for the five largest *transactions*, which no rollup
        // can answer — a bucket has no largest row. It is bounded instead: an
        // indexed equality on the period, then LIMIT 5.
        val plan = queryPlan(
            """
            SELECT e.*, c.name AS categoryName, c.nature AS categoryNature
              FROM expense e JOIN category c ON c.id = e.category_id
             WHERE e.status = 0 AND e.period_ym = $aug
             ORDER BY e.amount_minor DESC
             LIMIT 5
            """.trimIndent(),
        )
        assertTrue(
            "it must search ix_expense_period rather than scan, plan was:\n" + plan,
            plan.contains("ix_expense_period") && !plan.contains("SCAN e"),
        )
    }

    private fun queryPlan(sql: String): String =
        db.openHelper.writableDatabase.query("EXPLAIN QUERY PLAN\n$sql").use {
            buildString { while (it.moveToNext()) append(it.getString(it.columnCount - 1)).append('\n') }
        }

    @Test
    fun a_category_cannot_be_made_its_own_parent() = runBlocking {
        // `BEFORE UPDATE` sees the row's OLD values, so `parent_id = id` on a
        // childless root used to read that root's own NULL parent and pass the
        // depth guard, while the foreign key was satisfied by the
        // self-reference. The result was a row that is neither root nor leaf.
        val fixed = scalar("SELECT id FROM category WHERE name_key = 'fixed expenses'")

        assertThrows(SQLiteConstraintException::class.java) {
            exec("UPDATE category SET parent_id = $fixed WHERE id = $fixed")
        }

        assertEquals(
            "the root stopped being a root",
            0L,
            scalar("SELECT COUNT(*) FROM category WHERE id = parent_id"),
        )
    }

    @Test
    fun a_category_that_carries_expenses_cannot_gain_a_child() = runBlocking<Unit> {
        // The leaf-only rule was defended only from the reference side: an
        // expense may not point at a category with children, but nothing
        // stopped a category with expenses being *given* children. `Importer`
        // reaches this path — it inserts categories straight through BackupDao.
        val grocery = scalar("SELECT id FROM category WHERE name_key = 'grocery'")
        db.expenseDao().insert(
            ExpenseEntity(
                uuid = "e-leaf", categoryId = grocery, amountMinor = 1_000,
                spentOn = augDay, periodYm = aug, createdAt = now, updatedAt = now,
            ),
        )

        assertThrows(SQLiteConstraintException::class.java) {
            exec(
                """
                INSERT INTO category (uuid, parent_id, name, name_key, nature, created_at, updated_at)
                VALUES ('c-under-used', $grocery, 'Rice', 'rice', 1, $now, $now)
                """.trimIndent(),
            )
        }
    }

    @Test
    fun a_category_cannot_be_moved_under_one_that_carries_expenses() = runBlocking<Unit> {
        // The same rule for a re-parent rather than an insert.
        val grocery = scalar("SELECT id FROM category WHERE name_key = 'grocery'")
        val transport = scalar("SELECT id FROM category WHERE name_key = 'transport'")
        db.expenseDao().insert(
            ExpenseEntity(
                uuid = "e-move", categoryId = grocery, amountMinor = 1_000,
                spentOn = augDay, periodYm = aug, createdAt = now, updatedAt = now,
            ),
        )

        assertThrows(SQLiteConstraintException::class.java) {
            exec("UPDATE category SET parent_id = $grocery WHERE id = $transport")
        }
    }

    @Test
    fun every_seeded_name_key_is_the_one_the_app_would_compute() = runBlocking {
        // The seed used to fold its keys with Kotlin's default-locale
        // `lowercase()`, so on a Turkish or Azerbaijani phone the seeded
        // "Internet" carried `ınternet` — a key no write path would ever
        // produce. `ux_category_parent_key` then stopped guarding it and
        // merge-import's natural-key match missed it.
        //
        // This is the invariant, not the locale: every stored key must equal
        // what `NameKey.of` gives for the same name. `NameKeyTest` separately
        // proves `NameKey.of` does not depend on the device locale, and the two
        // together are what make the seed safe anywhere.
        db.backupDao().allCategories().forEach { category ->
            assertEquals(
                "seeded category '${category.name}' carries a key the app would not compute",
                NameKey.of(category.name),
                category.nameKey,
            )
        }
        db.backupDao().allSources().forEach { source ->
            assertEquals(
                "seeded source '${source.name}' carries a key the app would not compute",
                NameKey.of(source.name),
                source.nameKey,
            )
        }
    }

    @Test
    fun seeded_uuids_are_present_and_distinct() = runBlocking {
        val total = scalar("SELECT COUNT(*) FROM category")
        val distinct = scalar("SELECT COUNT(DISTINCT uuid) FROM category")
        assertEquals(total, distinct)
        assertNull(scalar("SELECT COUNT(*) FROM category WHERE uuid IS NULL OR uuid = ''").takeIf { it != 0L })
        assertNotNull(db.appMetaDao().get("schema_version"))
    }
}
