package com.app.finance.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.core.money.Money
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * NFR-REL-02's debug self-check, tested for the thing a self-check is easy to
 * get wrong: **being able to fail**.
 *
 * `AppContainer.assertRollupsReconcile` runs on every debug launch and is the
 * only runtime guard that the trigger-maintained aggregates still agree with
 * the ledger. A check that returns true no matter what the database contains is
 * worse than none, because it is read as evidence.
 *
 * Each case corrupts the rollup tables directly — the one thing nothing in the
 * app is allowed to do, and therefore exactly the fault the check exists for —
 * and asserts the check notices.
 */
@RunWith(AndroidJUnit4::class)
class RollupDriftCheckTest {

    private lateinit var fx: TestFixture

    @Before fun setUp() { fx = TestFixture() }
    @After fun tearDown() = fx.close()

    private fun exec(sql: String) = fx.db.openHelper.writableDatabase.execSQL(sql)

    @Test
    fun a_healthy_database_reconciles() = runBlocking {
        fx.expenses.insert(Money.ofTaka(300), fx.leafId("Grocery"), fx.today)
        fx.expenses.insert(Money.ofTaka(120), fx.leafId("Transport"), fx.today)
        // A refund and a deletion, because both move a bucket without inserting
        // into it — the paths most likely to leave a residue.
        val refund = fx.expenses.insert(Money.ofTaka(-50), fx.leafId("Grocery"), fx.today)
        assertTrue(fx.container.assertRollupsReconcile())

        fx.expenses.delete((refund as com.app.finance.domain.model.SaveOutcome.Saved).id)
        assertTrue(fx.container.assertRollupsReconcile())
    }

    @Test
    fun an_empty_database_reconciles() = runBlocking {
        assertTrue(fx.container.assertRollupsReconcile())
    }

    @Test
    fun a_wrong_total_is_caught() = runBlocking {
        fx.expenses.insert(Money.ofTaka(300), fx.leafId("Grocery"), fx.today)
        exec("UPDATE rollup_expense_month SET total_minor = total_minor + 1")
        assertFalse(fx.container.assertRollupsReconcile())
    }

    @Test
    fun a_wrong_transaction_count_is_caught() = runBlocking {
        // The total can be right while the count is not — a rebuild would then
        // silently disagree with the triggers, which is the invariant 03 §6
        // rests on.
        fx.expenses.insert(Money.ofTaka(300), fx.leafId("Grocery"), fx.today)
        exec("UPDATE rollup_expense_month SET txn_count = txn_count + 1")
        assertFalse(fx.container.assertRollupsReconcile())
    }

    @Test
    fun a_missing_bucket_is_caught() = runBlocking {
        // The regression this test exists for. A check that joins outward from
        // the rollup table cannot see a bucket that is not there — and a
        // trigger that failed to fire produces exactly that, which is the most
        // likely fault of all. Ledger rows with no aggregate at all is the
        // silent-wrong-numbers case.
        fx.expenses.insert(Money.ofTaka(300), fx.leafId("Grocery"), fx.today)
        exec("DELETE FROM rollup_expense_month")
        assertFalse(fx.container.assertRollupsReconcile())
    }

    @Test
    fun an_orphan_bucket_is_caught() = runBlocking {
        // The other direction: an aggregate with no ledger behind it. Nothing
        // in the app can create one, which is the point — if one appears,
        // something has written around the triggers.
        exec(
            """
            INSERT INTO rollup_expense_month(period_ym, category_id, total_minor, txn_count)
            VALUES (202608, ${fx.leafId("Grocery")}, 50000, 1)
            """.trimIndent(),
        )
        assertFalse(fx.container.assertRollupsReconcile())
    }

    @Test
    fun the_income_rollups_are_checked_too() = runBlocking {
        // `rollup_income_month` has no screen reading it until M3, so a fault
        // there would go unseen for longest — and its three triggers were the
        // ones missing from the published SQL, which the drift check is the
        // last line of defence against recurring.
        exec(
            """
            INSERT INTO rollup_income_month(period_ym, source_id, total_minor, entry_count)
            SELECT 202608, id, 100000, 1 FROM income_source LIMIT 1
            """.trimIndent(),
        )
        assertFalse(fx.container.assertRollupsReconcile())
    }

    // --- 03 §4.3's integrity check (C3) --------------------------------------

    @Test
    fun a_healthy_ledger_has_every_period_derived_from_its_date() = runBlocking {
        fx.expenses.insert(
            Money.ofTaka(340),
            fx.leafId("Grocery"),
            java.time.LocalDate.of(2026, 8, 3),
        )
        fx.income.saveEntry(Money.ofTaka(30_000), "Salary", java.time.LocalDate.of(2026, 8, 1))

        assertTrue(fx.container.assertPeriodsDerived())
    }

    @Test
    fun a_row_filed_in_the_wrong_month_is_caught() = runBlocking {
        // The failure this check exists for, and the one no other check in the
        // app can see: `assertRollupsReconcile` compares the rollups against
        // the ledger's `period_ym`, so a wrong one is wrong on both sides and
        // cancels out.
        fx.expenses.insert(
            Money.ofTaka(340),
            fx.leafId("Grocery"),
            java.time.LocalDate.of(2026, 8, 3),
        )
        fx.db.openHelper.writableDatabase.execSQL("UPDATE expense SET period_ym = 202606")

        assertFalse(fx.container.assertPeriodsDerived())
    }

    @Test
    fun an_income_entry_filed_in_the_wrong_month_is_caught_too() = runBlocking {
        fx.income.saveEntry(Money.ofTaka(30_000), "Salary", java.time.LocalDate.of(2026, 8, 1))
        fx.db.openHelper.writableDatabase.execSQL("UPDATE income_entry SET period_ym = 202512")

        assertFalse(fx.container.assertPeriodsDerived())
    }

    @Test
    fun a_year_boundary_is_derived_correctly() = runBlocking {
        // The `strftime` arithmetic has to agree with `Period.from` on the days
        // where they are easiest to disagree.
        listOf(
            java.time.LocalDate.of(2025, 12, 31),
            java.time.LocalDate.of(2026, 1, 1),
            java.time.LocalDate.of(2028, 2, 29),
        ).forEach { day ->
            fx.expenses.insert(Money.ofTaka(100), fx.leafId("Grocery"), day)
        }
        assertTrue(fx.container.assertPeriodsDerived())
    }
}
