package com.app.finance.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.data.db.entity.BudgetEntity
import com.app.finance.data.db.entity.CategoryEntity
import com.app.finance.data.db.entity.ExpenseEntity
import com.app.finance.data.db.entity.IncomeEntryEntity
import com.app.finance.data.db.entity.IncomeSourceEntity
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
        assertEquals("1", db.appMetaDao().get("schema_version"))
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
        val plan = db.openHelper.writableDatabase.query(
            """
            EXPLAIN QUERY PLAN
            SELECT * FROM expense
             WHERE status = 0 AND (spent_on, id) < (20678, 5)
             ORDER BY spent_on DESC, id DESC LIMIT 50
            """.trimIndent(),
        ).use {
            buildString { while (it.moveToNext()) append(it.getString(it.columnCount - 1)).append('\n') }
        }
        assertTrue("expected ix_expense_date to be used, plan was:\n$plan", plan.contains("ix_expense_date"))
        assertTrue("expected no temp B-tree sort, plan was:\n$plan", !plan.contains("TEMP B-TREE"))
    }

    @Test
    fun the_dashboard_query_never_touches_the_expense_table() {
        val plan = db.openHelper.writableDatabase.query(
            """
            EXPLAIN QUERY PLAN
            SELECT c.id, c.name, c.nature, IFNULL(b.limit_minor,0), IFNULL(r.total_minor,0)
              FROM category c
              LEFT JOIN budget b ON b.category_id = c.id AND b.period_ym = 202608
              LEFT JOIN rollup_expense_month r ON r.category_id = c.id AND r.period_ym = 202608
             WHERE c.parent_id IS NOT NULL AND (c.is_archived = 0 OR r.total_minor IS NOT NULL)
             ORDER BY c.sort_order
            """.trimIndent(),
        ).use {
            buildString { while (it.moveToNext()) append(it.getString(it.columnCount - 1)).append('\n') }
        }
        // This is the property that keeps the dashboard flat as history grows:
        // cost depends on the leaf count, not on the number of transactions.
        assertTrue("dashboard must not scan expense, plan was:\n$plan", !plan.contains("TABLE expense"))
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
