package com.app.finance.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.core.money.Money
import com.app.finance.core.time.Period
import com.app.finance.domain.model.EntryError
import com.app.finance.domain.model.Nature
import com.app.finance.domain.model.SaveOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `BudgetRepository` against real SQLite — FR-BUD-01 … FR-BUD-04, FR-BUD-08.
 *
 * The interesting cases are the ones where the repository and the schema both
 * have an opinion. A second limit for the same (category, period) is a unique
 * index violation *and* an upsert; a limit on a root is a trigger abort *and* a
 * typed rejection. Testing against the real database is the only way to know
 * which one actually fires, and that the caller sees the typed error either way.
 */
@RunWith(AndroidJUnit4::class)
class BudgetRepositoryTest {

    private lateinit var fx: TestFixture

    private val aug = Period(202608)
    private val jul = Period(202607)

    @Before fun setUp() { fx = TestFixture() }
    @After fun tearDown() = fx.close()

    /** The stored row, bypassing the repository — for the fields it preserves. */
    private suspend fun row(categoryId: Long, period: Period) =
        fx.db.budgetDao().forCategory(categoryId, period.ym)

    // --- FR-BUD-01, FR-BUD-02 ----------------------------------------------

    @Test
    fun an_archived_leaf_whose_last_expense_is_deleted_stops_showing_a_bar() = runBlocking {
        // The delete trigger decrements a rollup bucket rather than removing
        // it, so a spent-then-emptied period leaves `(0, 0)` behind forever.
        // `observeCategoryCells` and `observeIncomeBySource` both guard against
        // that residue with `txn_count > 0` / `entry_count > 0` and both cite
        // §15.3 — this is the query §15.3 missed, so an archived leaf kept a
        // permanent ৳0 bar that no user action could clear.
        val grocery = fx.leafId("Grocery")
        val saved = fx.expenses.insert(Money.ofTaka(500), grocery, fx.today) as SaveOutcome.Saved
        fx.categories.archive(grocery)

        // Still shown while it carries spend — FR-CAT-08, and the reason the
        // archived clause is not a plain `is_archived = 0`.
        assertTrue(
            "an archived leaf with spend in the period must stay visible",
            fx.budgets.observeBars(aug).first().any { it.id == grocery },
        )

        fx.expenses.delete(saved.id)

        assertFalse(
            "the emptied rollup row left a bar nothing can clear",
            fx.budgets.observeBars(aug).first().any { it.id == grocery },
        )
    }

    @Test
    fun a_limit_is_stored_for_exactly_one_category_and_period() = runBlocking {
        val grocery = fx.leafId("Grocery")
        assertTrue(fx.budgets.setLimit(grocery, aug, Money.ofTaka(8_000)) is SaveOutcome.Saved)

        assertEquals(Money.ofTaka(8_000), fx.budgets.limitFor(grocery, aug))
        assertNull("July must be untouched", fx.budgets.limitFor(grocery, jul))
        assertNull("Transport must be untouched", fx.budgets.limitFor(fx.leafId("Transport"), aug))
    }

    @Test
    fun setting_a_second_limit_updates_the_existing_row_rather_than_inserting() = runBlocking {
        // FR-BUD-02's acceptance criterion, verbatim. A plain insert would hit
        // ux_budget_cat_period and surface as a constraint violation instead.
        val grocery = fx.leafId("Grocery")
        fx.budgets.setLimit(grocery, aug, Money.ofTaka(8_000))
        fx.budgets.setLimit(grocery, aug, Money.ofTaka(9_500))

        assertEquals(Money.ofTaka(9_500), fx.budgets.limitFor(grocery, aug))
        assertEquals(1, fx.db.budgetDao().forPeriod(aug.ym).size)
    }

    @Test
    fun revising_a_limit_keeps_its_uuid_and_creation_time() = runBlocking {
        // Export dedup is by uuid (03 §1), so an edit that mints a new one would
        // make the same budget import twice as two rows.
        val grocery = fx.leafId("Grocery")
        fx.budgets.setLimit(grocery, aug, Money.ofTaka(8_000))
        val before = row(grocery, aug)!!

        fx.budgets.setLimit(grocery, aug, Money.ofTaka(9_500))
        val after = row(grocery, aug)!!

        assertEquals(before.id, after.id)
        assertEquals(before.uuid, after.uuid)
        assertEquals(before.createdAt, after.createdAt)
    }

    // --- FR-BUD-03 ----------------------------------------------------------

    @Test
    fun a_limit_on_a_root_is_rejected_with_a_typed_error() = runBlocking {
        // The trigger would abort this anyway; the point is that the caller gets
        // BUDGET_ON_NON_LEAF rather than a raw SQLiteConstraintException.
        val outcome = fx.budgets.setLimit(fx.rootId("Variable Expenses"), aug, Money.ofTaka(20_000))
        assertEquals(EntryError.BUDGET_ON_NON_LEAF, (outcome as SaveOutcome.Rejected).error)
    }

    @Test
    fun a_limit_on_a_category_that_has_children_is_rejected() = runBlocking {
        // A leaf can stop being one: adding a child to "Grocery" makes it a
        // parent, and FR-BUD-03 puts limits on leaves only.
        val newRoot = (fx.categories.createRoot("Travel", com.app.finance.domain.model.Nature.VARIABLE)
            as SaveOutcome.Saved).id
        val child = (fx.categories.createSubcategory(newRoot, "Bus fare") as SaveOutcome.Saved).id

        assertTrue(fx.budgets.setLimit(child, aug, Money.ofTaka(1_000)) is SaveOutcome.Saved)
        assertEquals(
            EntryError.BUDGET_ON_NON_LEAF,
            (fx.budgets.setLimit(newRoot, aug, Money.ofTaka(1_000)) as SaveOutcome.Rejected).error,
        )
    }

    @Test
    fun a_limit_on_a_missing_category_is_rejected() = runBlocking {
        val outcome = fx.budgets.setLimit(9_999L, aug, Money.ofTaka(500))
        assertEquals(EntryError.CATEGORY_NOT_FOUND, (outcome as SaveOutcome.Rejected).error)
    }

    // --- FR-BUD-08 ----------------------------------------------------------

    @Test
    fun zero_and_negative_limits_are_refused() = runBlocking {
        // FR-BUD-08 permits >= 0, but the bar query reads a missing row as
        // IFNULL(limit_minor, 0): a stored zero and no budget at all would be
        // indistinguishable downstream, and the percentage would divide by zero.
        val grocery = fx.leafId("Grocery")
        assertEquals(
            EntryError.ZERO_LIMIT,
            (fx.budgets.setLimit(grocery, aug, Money.ZERO) as SaveOutcome.Rejected).error,
        )
        assertEquals(
            EntryError.ZERO_LIMIT,
            (fx.budgets.setLimit(grocery, aug, Money.ofTaka(-100)) as SaveOutcome.Rejected).error,
        )
        assertNull(fx.budgets.limitFor(grocery, aug))
    }

    @Test
    fun clearing_a_limit_returns_the_leaf_to_unbudgeted() = runBlocking {
        val grocery = fx.leafId("Grocery")
        fx.budgets.setLimit(grocery, aug, Money.ofTaka(8_000))
        fx.budgets.clearLimit(grocery, aug)

        assertNull(fx.budgets.limitFor(grocery, aug))
        assertEquals(0L, fx.budgets.observeBars(aug).first().first { it.id == grocery }.limitMinor)
    }

    // --- FR-BUD-04 ----------------------------------------------------------

    @Test
    fun copying_from_last_month_fills_only_the_gaps() = runBlocking {
        // "Leaves already carrying a limit this period are not overwritten
        // without confirmation" — satisfied by never overwriting at all, which
        // is also what makes Undo a pure removal.
        val grocery = fx.leafId("Grocery")
        val transport = fx.leafId("Transport")
        fx.budgets.setLimit(grocery, jul, Money.ofTaka(8_000))
        fx.budgets.setLimit(transport, jul, Money.ofTaka(3_000))
        fx.budgets.setLimit(grocery, aug, Money.ofTaka(9_000)) // already decided

        assertEquals(1, fx.budgets.copyFromPreviousPeriod(aug))

        assertEquals("must not be overwritten", Money.ofTaka(9_000), fx.budgets.limitFor(grocery, aug))
        assertEquals("must be filled in", Money.ofTaka(3_000), fx.budgets.limitFor(transport, aug))
    }

    @Test
    fun copying_mints_new_uuids_rather_than_moving_rows() = runBlocking {
        val grocery = fx.leafId("Grocery")
        fx.budgets.setLimit(grocery, jul, Money.ofTaka(8_000))
        fx.budgets.copyFromPreviousPeriod(aug)

        val july = row(grocery, jul)!!
        val august = row(grocery, aug)!!
        assertTrue("a distinct budget for a distinct period", july.uuid != august.uuid)
        assertEquals(july.limitMinor, august.limitMinor)
    }

    @Test
    fun copying_from_an_empty_month_is_a_no_op() = runBlocking {
        assertEquals(0, fx.budgets.copyFromPreviousPeriod(aug))
        assertTrue(fx.budgets.copyableFromPreviousPeriod(aug).isEmpty())
    }

    @Test
    fun the_copyable_set_is_exactly_what_a_copy_would_add() = runBlocking {
        // It drives both the disabled state and the Undo list, so a disagreement
        // between the two would leave rows behind after an undo.
        val grocery = fx.leafId("Grocery")
        val transport = fx.leafId("Transport")
        fx.budgets.setLimit(grocery, jul, Money.ofTaka(8_000))
        fx.budgets.setLimit(transport, jul, Money.ofTaka(3_000))
        fx.budgets.setLimit(grocery, aug, Money.ofTaka(9_000))

        val copyable = fx.budgets.copyableFromPreviousPeriod(aug)
        assertEquals(listOf(transport), copyable)
        assertEquals(copyable.size, fx.budgets.copyFromPreviousPeriod(aug))
    }

    @Test
    fun undoing_a_copy_removes_exactly_what_it_added() = runBlocking {
        val grocery = fx.leafId("Grocery")
        val transport = fx.leafId("Transport")
        fx.budgets.setLimit(grocery, jul, Money.ofTaka(8_000))
        fx.budgets.setLimit(transport, jul, Money.ofTaka(3_000))
        fx.budgets.setLimit(grocery, aug, Money.ofTaka(9_000))

        val added = fx.budgets.copyableFromPreviousPeriod(aug)
        fx.budgets.copyFromPreviousPeriod(aug)
        fx.budgets.removeLimits(added, aug)

        assertNull(fx.budgets.limitFor(transport, aug))
        assertEquals("the pre-existing limit must survive", Money.ofTaka(9_000), fx.budgets.limitFor(grocery, aug))
        assertEquals("July is not touched by an undo", Money.ofTaka(3_000), fx.budgets.limitFor(transport, jul))
    }

    // --- the bar query ------------------------------------------------------

    @Test
    fun the_bars_carry_the_parent_so_leaves_can_be_grouped_without_a_second_query() = runBlocking {
        val variable = fx.rootId("Variable Expenses")
        val bars = fx.budgets.observeBars(aug).first()
        assertEquals(13, bars.size)
        assertTrue(bars.first { it.name == "Grocery" }.parentId == variable)
    }

    @Test
    fun spend_reaches_the_bars_without_the_query_ever_reading_the_expense_table() = runBlocking {
        val grocery = fx.leafId("Grocery")
        fx.expenses.insert(Money.ofTaka(1_200), grocery, fx.today)
        fx.expenses.insert(Money.ofTaka(-200), grocery, fx.today) // FR-EXP-06 refund
        fx.budgets.setLimit(grocery, aug, Money.ofTaka(8_000))

        val bar = fx.budgets.observeBars(aug).first().first { it.id == grocery }
        assertEquals(100_000L, bar.spentMinor)
        assertEquals(800_000L, bar.limitMinor)
    }

    @Test
    fun an_archived_leaf_stays_visible_while_it_carries_spend_in_the_period() = runBlocking {
        // FR-CAT-08 — archived categories are hidden from pickers, never from
        // history. A budget screen that dropped them would under-report a month.
        val grocery = fx.leafId("Grocery")
        fx.expenses.insert(Money.ofTaka(700), grocery, fx.today)
        fx.categories.archive(grocery)

        assertTrue(fx.budgets.observeBars(aug).first().any { it.id == grocery })
        assertTrue(
            "but gone from a month it has no spend in",
            fx.budgets.observeBars(jul).first().none { it.id == grocery },
        )
    }

    @Test
    fun the_copy_leaves_archived_leaves_behind() = runBlocking {
        // A limit carried forward onto a category the user has retired is a
        // target they cannot spend against — and with no spend in the new
        // period the row never renders, so nothing on any screen could clear
        // it again. FR-BUD-04 read together with FR-CAT-08.
        val grocery = fx.leafId("Grocery")
        val transport = fx.leafId("Transport")
        fx.budgets.setLimit(grocery, jul, Money.ofTaka(9_000))
        fx.budgets.setLimit(transport, jul, Money.ofTaka(3_000))
        fx.categories.archive(grocery)

        assertEquals(listOf(transport), fx.budgets.copyableFromPreviousPeriod(aug))
        assertEquals(1, fx.budgets.copyFromPreviousPeriod(aug))
        assertNull(fx.budgets.limitFor(grocery, aug))
        assertEquals(Money.ofTaka(3_000), fx.budgets.limitFor(transport, aug))
    }

    @Test
    fun the_copy_also_skips_a_leaf_whose_root_is_archived() = runBlocking {
        val travel = (fx.categories.createRoot("Travel", Nature.VARIABLE) as SaveOutcome.Saved).id
        val bus = (fx.categories.createSubcategory(travel, "Bus fare") as SaveOutcome.Saved).id
        fx.budgets.setLimit(bus, jul, Money.ofTaka(1_200))
        fx.categories.archive(travel)

        assertTrue(fx.budgets.copyableFromPreviousPeriod(aug).none { it == bus })
        assertNull(fx.budgets.limitFor(bus, aug))
    }
}
