package com.app.finance.ui.feature.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.awaitState
import com.app.finance.core.money.Money
import com.app.finance.core.time.Period
import com.app.finance.data.db.entity.ExpenseEntity
import com.app.finance.domain.model.PaymentMethod
import com.app.finance.domain.usecase.BudgetGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * **The M2 exit criterion.** `01-PRD.md` §8: "Budgets reconcile against ledger."
 *
 * Every figure the budget screen shows is read from `rollup_expense_month`,
 * which is maintained by triggers and never recomputed — that is the whole
 * reason the screen stays flat as history grows (03 §5.1). The cost of that
 * choice is that the same fact is stored twice, and NFR-REL-02 requires the two
 * copies to agree.
 *
 * So this suite asserts the agreement directly: for every leaf the screen
 * renders, `status.spent` must equal `SUM(amount_minor)` taken straight off the
 * `expense` table for that (category, period). Not the rollup read a second way
 * — the ledger itself, which is the only authority.
 *
 * It runs through `BudgetViewModel` rather than the DAO on purpose. The
 * reconciliation that matters is between what a *user sees* and what they
 * logged, so every layer that could drop or double a figure — the query, the
 * grouping fold, the root sums — is inside the assertion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class BudgetReconciliationTest {

    private lateinit var fx: TestFixture
    private val store = ViewModelStore()
    private var seq = 0

    private val aug = Period(202608)
    private val jul = Period(202607)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        fx = TestFixture()
    }

    @After
    fun tearDown() {
        store.clear()
        fx.closeAfterDraining()
        Dispatchers.resetMain()
    }

    private fun vm(period: Period): BudgetViewModel = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                BudgetViewModel(fx.budgets, fx.categories, fx.clock, period) as T
        },
    )["vm${seq++}", BudgetViewModel::class.java]

    /** The authority: the ledger, summed directly, with no rollup involved. */
    private fun ledgerSum(categoryId: Long, period: Period): Long =
        fx.db.openHelper.writableDatabase.query(
            """
            SELECT IFNULL(SUM(amount_minor), 0) FROM expense
             WHERE category_id = $categoryId AND period_ym = ${period.ym} AND status = 0
            """.trimIndent(),
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun ledgerTotal(period: Period): Long =
        fx.db.openHelper.writableDatabase.query(
            "SELECT IFNULL(SUM(amount_minor), 0) FROM expense WHERE period_ym = ${period.ym} AND status = 0",
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    /**
     * Asserts the whole screen against the ledger at once: every leaf, every
     * root total, and the grand total across all groups.
     */
    private suspend fun reconcile(period: Period) {
        val groups: List<BudgetGroup> = vm(period).state.awaitState { !it.initialLoad }.groups

        groups.flatMap { it.leaves }.forEach { leaf ->
            assertEquals(
                "leaf '${leaf.name}' in ${period.ym}",
                ledgerSum(leaf.id, period),
                leaf.status.spent.paisa,
            )
        }

        groups.forEach { group ->
            assertEquals(
                "root total '${group.name}' in ${period.ym}",
                group.leaves.sumOf { ledgerSum(it.id, period) },
                group.spent.paisa,
            )
        }

        assertEquals(
            "grand total in ${period.ym}",
            ledgerTotal(period),
            groups.sumOf { it.spent.paisa },
        )
    }

    private suspend fun spend(taka: Long, category: String, day: Int, period: Period = aug) =
        fx.expenses.insert(
            amount = Money.ofTaka(taka),
            categoryId = fx.leafId(category),
            spentOn = java.time.LocalDate.of(period.ym / 100, period.ym % 100, day),
        )

    // ------------------------------------------------------------------------

    @Test
    fun a_month_of_ordinary_spending_reconciles() = runBlocking {
        spend(1_200, "Grocery", 1)
        spend(850, "Grocery", 4)
        spend(300, "Transport", 4)
        spend(12_000, "House Rent", 1)
        spend(2_400, "Medical", 9)

        reconcile(aug)
    }

    @Test
    fun refunds_reconcile_as_reductions_not_as_separate_rows() = runBlocking {
        // FR-EXP-06 — a refund is a negative amount against the same category,
        // so both the rollup and the direct sum must net it off.
        spend(5_000, "Grocery", 2)
        spend(-1_500, "Grocery", 6)
        spend(-400, "Transport", 6) // net negative for the month

        reconcile(aug)
        val grocery = fx.leafId("Grocery")
        assertEquals(350_000L, ledgerSum(grocery, aug))
    }

    @Test
    fun a_refiled_expense_reconciles_in_both_the_old_and_the_new_period() = runBlocking {
        // FR-EXP-07 — editing "MUST recalculate all dependent aggregates,
        // including those of prior periods". The update trigger decrements the
        // old (period, category) bucket and increments the new one; if it did
        // only half of that, the old month would keep a phantom figure and this
        // is the assertion that catches it.
        spend(2_000, "Grocery", 10, period = jul)
        spend(900, "Transport", 12, period = jul)
        val misfiled = spend(4_500, "Grocery", 3, period = jul)

        reconcile(jul)

        val id = (misfiled as com.app.finance.domain.model.SaveOutcome.Saved).id
        fx.expenses.update(
            id = id,
            amount = Money.ofTaka(4_500),
            categoryId = fx.leafId("Dining Out"),
            spentOn = java.time.LocalDate.of(2026, 8, 3),
            method = PaymentMethod.DEFAULT,
            note = null,
        )

        reconcile(jul) // the old month must have given the money up
        reconcile(aug) // and the new one must have taken it

        assertEquals(0L, ledgerSum(fx.leafId("Dining Out"), jul))
        assertEquals(450_000L, ledgerSum(fx.leafId("Dining Out"), aug))
    }

    @Test
    fun a_deleted_expense_leaves_no_residue_in_either_copy() = runBlocking {
        spend(1_000, "Grocery", 5)
        val doomed = spend(3_300, "Grocery", 7)
        spend(700, "Medical", 7)

        fx.expenses.delete((doomed as com.app.finance.domain.model.SaveOutcome.Saved).id)

        reconcile(aug)
        assertEquals(100_000L, ledgerSum(fx.leafId("Grocery"), aug))
    }

    @Test
    fun a_pending_entry_is_excluded_from_both_copies_identically() = runBlocking {
        // Recurring rules (M5) write pending rows. The rollup triggers skip
        // status <> 0; if the screen's figure and the ledger sum disagreed about
        // that, every projected expense would double-count on confirmation.
        spend(1_500, "Grocery", 8)
        val grocery = fx.leafId("Grocery")
        fx.db.expenseDao().insert(
            ExpenseEntity(
                uuid = UUID.randomUUID().toString(),
                categoryId = grocery,
                amountMinor = 999_900,
                spentOn = java.time.LocalDate.of(2026, 8, 20).toEpochDay(),
                periodYm = aug.ym,
                status = 1,
                createdAt = fx.clock.millis(),
                updatedAt = fx.clock.millis(),
            ),
        )

        reconcile(aug)
        assertEquals("the pending row must not be counted", 150_000L, ledgerSum(grocery, aug))
    }

    @Test
    fun limits_do_not_move_the_spent_figure() = runBlocking {
        // Setting, revising, copying and clearing limits all write to `budget`,
        // which the bar query joins. A join that dropped or duplicated rows
        // would change `spent` while nobody spent anything.
        spend(6_400, "Grocery", 2)
        spend(1_100, "Transport", 3)
        spend(2_400, "Medical", 4)

        reconcile(aug)

        fx.budgets.setLimit(fx.leafId("Grocery"), jul, Money.ofTaka(7_000))
        fx.budgets.setLimit(fx.leafId("Grocery"), aug, Money.ofTaka(7_000))
        fx.budgets.setLimit(fx.leafId("Grocery"), aug, Money.ofTaka(6_000)) // revise
        fx.budgets.setLimit(fx.leafId("Transport"), aug, Money.ofTaka(3_000))
        fx.budgets.copyFromPreviousPeriod(aug)
        fx.budgets.clearLimit(fx.leafId("Transport"), aug)

        reconcile(aug)
    }

    @Test
    fun an_archived_category_still_reconciles_for_the_month_it_has_spend_in() = runBlocking {
        // FR-CAT-08's exception in the bar query is `OR r.total_minor IS NOT
        // NULL`. Get it wrong and the leaf vanishes from the screen while its
        // money stays in the ledger — the exact shape of an unreconciled month.
        spend(2_750, "Gifts", 11)
        spend(500, "Grocery", 11)
        fx.categories.archive(fx.leafId("Gifts"))

        reconcile(aug)
        val groups = vm(aug).state.awaitState { !it.initialLoad }.groups
        assertTrue(
            "an archived leaf with spend must still be rendered",
            groups.flatMap { it.leaves }.any { it.name == "Gifts" },
        )
    }

    @Test
    fun the_rebuild_query_agrees_with_the_screen_after_every_kind_of_mutation() = runBlocking {
        // The strongest form: mutate through every path the app has, then
        // rebuild the rollups from the ledger and confirm the screen would show
        // the same figures either way. This is assertion 19 of 03 §10.1 stated
        // at the level the user actually reads.
        spend(3_000, "Grocery", 2)
        spend(-500, "Grocery", 3)
        val moved = spend(1_800, "Transport", 4)
        spend(9_000, "House Rent", 1)
        val gone = spend(450, "Mobile Recharge", 5)

        fx.expenses.update(
            id = (moved as com.app.finance.domain.model.SaveOutcome.Saved).id,
            amount = Money.ofTaka(2_100),
            categoryId = fx.leafId("Dining Out"),
            spentOn = java.time.LocalDate.of(2026, 8, 4),
            method = PaymentMethod.BKASH,
            note = "changed my mind",
        )
        fx.expenses.delete((gone as com.app.finance.domain.model.SaveOutcome.Saved).id)

        val fromTriggers = vm(aug).state.awaitState { !it.initialLoad }
            .groups.flatMap { it.leaves }.associate { it.name to it.status.spent.paisa }

        com.app.finance.data.db.Schema.REBUILD_ROLLUPS.forEach {
            fx.db.openHelper.writableDatabase.execSQL(it)
        }

        val fromRebuild = vm(aug).state.awaitState { !it.initialLoad }
            .groups.flatMap { it.leaves }.associate { it.name to it.status.spent.paisa }

        assertEquals(fromTriggers, fromRebuild)
        reconcile(aug)
    }
}
