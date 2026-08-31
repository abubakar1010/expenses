package com.app.finance.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.core.money.Money
import com.app.finance.domain.model.EntryError
import com.app.finance.domain.model.SaveOutcome
import com.app.finance.domain.model.Split
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Splitting a bill — FR-SHR-02, FR-SHR-03.
 *
 * Against real in-memory SQLite with the canonical schema. The behaviour that
 * matters here is entirely in the database — two triggers, a `RESTRICT`
 * foreign key, and the rollup triggers that must go on reading
 * `amount_minor` and seeing your share.
 */
@RunWith(AndroidJUnit4::class)
class SharedExpenseTest {

    private lateinit var fx: TestFixture

    @Before fun setUp() { fx = TestFixture() }
    @After fun tearDown() = fx.close()

    private suspend fun savedId(outcome: SaveOutcome): Long {
        assertTrue("expected a save, got $outcome", outcome is SaveOutcome.Saved)
        return (outcome as SaveOutcome.Saved).id
    }

    private suspend fun person(name: String): Long =
        (fx.people.findOrCreate(name) as SaveOutcome.Saved).id

    private fun rollup(period: Int, categoryId: Long): Long =
        fx.db.openHelper.writableDatabase
            .query("SELECT IFNULL(total_minor, 0) FROM rollup_expense_month " +
                "WHERE period_ym=$period AND category_id=$categoryId")
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun scalar(sql: String): Long =
        fx.db.openHelper.writableDatabase.query(sql)
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }

    // --- the case the whole design exists to prevent --------------------------

    @Test
    fun a_shared_dinner_charges_your_budget_your_share_and_nothing_else() = runBlocking {
        // FR-SHR-02's worked example. ৳1,000 four ways: you ate ৳250 and were
        // briefly the group's bank for the other ৳750. Charging the budget
        // ৳1,000 would say you ate four dinners.
        val grocery = fx.leafId("Grocery")
        val ids = listOf(person("Rahim"), person("Karim"), person("Jamal"))
        val (yours, split) = Split.evenly(Money.ofTaka(1_000), ids)

        val id = savedId(
            fx.expenses.insert(yours, grocery, fx.today, split = split),
        )

        assertEquals(Money.ofTaka(250), Money(fx.expenses.byId(id)!!.amountMinor))
        assertEquals("the budget heard the whole bill", 25_000L, rollup(202608, grocery))
        assertEquals(75_000L, scalar("SELECT SUM(share_minor) FROM expense_share"))
    }

    @Test
    fun a_repayment_next_month_does_not_touch_either_month() = runBlocking {
        // The month-boundary case, and the reason `amount_minor` is your share
        // rather than the bill. Recording ৳1,000 in August and −৳750 in
        // September would leave August claiming three dinners you did not eat
        // and September claiming a negative one — both permanently wrong,
        // because budgets and rollups are keyed by calendar month.
        val grocery = fx.leafId("Grocery")
        val rahim = person("Rahim")
        // Before the fixture's pinned today (14 Aug); a future date is refused.
        val august = LocalDate.of(2026, 8, 10)

        val (yours, split) = Split.evenly(Money.ofTaka(1_000), listOf(rahim))
        savedId(fx.expenses.insert(yours, grocery, august, split = split))

        assertEquals(50_000L, rollup(202608, grocery))

        // Rahim settles up on 2 September. A settlement is not consumption, so
        // it enters no rollup at all — that is FR-SHR-04, asserted here because
        // this is the pairing that goes wrong in every naive design.
        fx.db.openHelper.writableDatabase.execSQL(
            "INSERT INTO settlement (uuid, person_id, amount_minor, settled_on, " +
                "payment_method, created_at, updated_at) " +
                "VALUES ('s1', $rahim, 50000, ${LocalDate.of(2026, 9, 2).toEpochDay()}, 0, 1, 1)",
        )

        assertEquals("August moved", 50_000L, rollup(202608, grocery))
        assertEquals("September was charged", 0L, rollup(202609, grocery))
        assertEquals(
            "a settlement reached a rollup",
            50_000L,
            scalar("SELECT IFNULL(SUM(total_minor), 0) FROM rollup_expense_month"),
        )
    }

    // --- the two exclusive modes ----------------------------------------------

    @Test
    fun an_expense_someone_else_paid_still_charges_your_budget() = runBlocking {
        // FR-SHR-03. No money left your wallet and you still ate the food, so
        // the budget must hear about it — the case that breaks every design
        // storing the bill rather than the share.
        val grocery = fx.leafId("Grocery")
        val rahim = person("Rahim")

        val id = savedId(
            fx.expenses.insert(
                Money.ofTaka(250), grocery, fx.today,
                split = Split.TheyPaid(rahim),
            ),
        )

        assertEquals(rahim, fx.expenses.byId(id)!!.payerPersonId)
        assertEquals(25_000L, rollup(202608, grocery))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM expense_share"))
    }

    @Test
    fun a_share_cannot_be_recorded_on_an_expense_someone_else_paid() = runBlocking {
        // `trg_share_only_when_i_paid`, reached through the repository so the
        // user gets a sentence rather than a constraint violation. If Rahim
        // paid and three of you split it, the other two owe Rahim — not you.
        val grocery = fx.leafId("Grocery")
        val rahim = person("Rahim")
        val id = savedId(
            fx.expenses.insert(Money.ofTaka(250), grocery, fx.today, split = Split.TheyPaid(rahim)),
        )

        fx.db.openHelper.writableDatabase.execSQL(
            "INSERT INTO person (uuid, name, name_key, sort_order, is_archived, created_at, updated_at) " +
                "VALUES ('p2', 'Karim', 'karim', 1, 0, 1, 1)",
        )
        val karim = scalar("SELECT id FROM person WHERE name_key = 'karim'")

        val refused = runCatching {
            fx.db.openHelper.writableDatabase.execSQL(
                "INSERT INTO expense_share (uuid, expense_id, person_id, share_minor, " +
                    "created_at, updated_at) VALUES ('s1', $id, $karim, 25000, 1, 1)",
            )
        }.exceptionOrNull()

        assertNotNull("the guard did not fire", refused)
        assertEquals(0L, scalar("SELECT COUNT(*) FROM expense_share"))
    }

    @Test
    fun a_shared_expense_cannot_become_one_somebody_else_paid_while_shares_remain() = runBlocking {
        // The same rule from the other side — `trg_payer_excludes_shares`.
        // `update` clears the shares first precisely so the legitimate edit
        // works; this asserts the raw path is still guarded.
        val grocery = fx.leafId("Grocery")
        val rahim = person("Rahim")
        val (yours, split) = Split.evenly(Money.ofTaka(1_000), listOf(rahim))
        val id = savedId(fx.expenses.insert(yours, grocery, fx.today, split = split))

        val refused = runCatching {
            fx.db.openHelper.writableDatabase.execSQL(
                "UPDATE expense SET payer_person_id = $rahim WHERE id = $id",
            )
        }.exceptionOrNull()

        assertNotNull("the guard did not fire", refused)
        assertNull(fx.expenses.byId(id)!!.payerPersonId)
    }

    // --- delete and undo ------------------------------------------------------

    @Test
    fun deleting_a_shared_expense_succeeds_and_takes_its_shares_with_it() = runBlocking {
        // Without the cascade this throws: `expense_share.expense_id` is
        // `ON DELETE RESTRICT`, so swipe-to-delete — the ledger's most-used
        // gesture — would fail on exactly the rows this feature creates.
        val grocery = fx.leafId("Grocery")
        val (yours, split) = Split.evenly(Money.ofTaka(900), listOf(person("Rahim"), person("Karim")))
        val id = savedId(fx.expenses.insert(yours, grocery, fx.today, split = split))
        assertEquals(2L, scalar("SELECT COUNT(*) FROM expense_share"))

        val deleted = fx.expenses.delete(id)

        assertNotNull(deleted)
        assertEquals(2, deleted!!.shares.size)
        assertEquals(0L, scalar("SELECT COUNT(*) FROM expense"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM expense_share"))
        assertEquals("the rollup kept a deleted expense", 0L, rollup(202608, grocery))
    }

    @Test
    fun undo_puts_back_the_expense_and_what_people_owed_on_it() = runBlocking {
        // NFR-USE-03's five seconds have to restore the whole thing. After the
        // delete the shares exist nowhere but in the returned value, so an undo
        // that restored only the expense would silently forgive two debts.
        val grocery = fx.leafId("Grocery")
        val (yours, split) = Split.evenly(Money.ofTaka(900), listOf(person("Rahim"), person("Karim")))
        val id = savedId(fx.expenses.insert(yours, grocery, fx.today, split = split))
        val before = scalar("SELECT SUM(share_minor) FROM expense_share")

        val deleted = fx.expenses.delete(id)!!
        val restored = fx.expenses.restore(deleted)

        assertEquals(1L, scalar("SELECT COUNT(*) FROM expense"))
        assertEquals(2L, scalar("SELECT COUNT(*) FROM expense_share"))
        assertEquals(before, scalar("SELECT SUM(share_minor) FROM expense_share"))
        // Re-pointed at the new row, not the id it used to have.
        assertEquals(
            2L,
            scalar("SELECT COUNT(*) FROM expense_share WHERE expense_id = $restored"),
        )
        assertEquals("the rollup did not come back", 30_000L, rollup(202608, grocery))
    }

    // --- editing --------------------------------------------------------------

    @Test
    fun reopening_a_shared_expense_gives_back_the_bill_that_was_typed() = runBlocking {
        // The amount field holds the bill, but only your share is stored — so
        // the bill has to be reconstructed from the parts. There is no total
        // column to disagree with them, which is what makes this exact.
        val grocery = fx.leafId("Grocery")
        val ids = listOf(person("Rahim"), person("Karim"))
        val (yours, split) = Split.evenly(Money.ofTaka(1_000), ids)
        val id = savedId(fx.expenses.insert(yours, grocery, fx.today, split = split))

        val loaded = fx.expenses.splitOf(id)!!

        assertEquals(Money.ofTaka(1_000), loaded.bill)
        assertTrue(loaded.split is Split.YouPaid)
        assertEquals(2, loaded.split.owed.size)
    }

    @Test
    fun editing_a_split_replaces_it_rather_than_adding_to_it() = runBlocking {
        val grocery = fx.leafId("Grocery")
        val rahim = person("Rahim")
        val karim = person("Karim")
        val (yours, split) = Split.evenly(Money.ofTaka(1_000), listOf(rahim, karim))
        val id = savedId(fx.expenses.insert(yours, grocery, fx.today, split = split))

        // Karim was not there after all: the same bill, two ways.
        val (newYours, newSplit) = Split.evenly(Money.ofTaka(1_000), listOf(rahim))
        fx.expenses.update(id, newYours, grocery, fx.today, com.app.finance.domain.model.PaymentMethod.DEFAULT, null, newSplit)

        assertEquals(1L, scalar("SELECT COUNT(*) FROM expense_share"))
        assertEquals(50_000L, scalar("SELECT SUM(share_minor) FROM expense_share"))
        assertEquals(Money.ofTaka(500), Money(fx.expenses.byId(id)!!.amountMinor))
        assertEquals(50_000L, rollup(202608, grocery))
    }

    @Test
    fun a_shared_expense_can_become_one_somebody_else_paid() = runBlocking {
        // The edit the trigger would otherwise block. `update` sheds the shares
        // before the payer lands, in one transaction.
        val grocery = fx.leafId("Grocery")
        val rahim = person("Rahim")
        val (yours, split) = Split.evenly(Money.ofTaka(1_000), listOf(rahim))
        val id = savedId(fx.expenses.insert(yours, grocery, fx.today, split = split))

        val outcome = fx.expenses.update(
            id, Money.ofTaka(500), grocery, fx.today,
            com.app.finance.domain.model.PaymentMethod.DEFAULT, null, Split.TheyPaid(rahim),
        )

        assertTrue("the edit was refused: $outcome", outcome is SaveOutcome.Saved)
        assertEquals(rahim, fx.expenses.byId(id)!!.payerPersonId)
        assertEquals(0L, scalar("SELECT COUNT(*) FROM expense_share"))
    }

    // --- what a split may not be ----------------------------------------------

    @Test
    fun a_share_of_nothing_is_refused_with_a_sentence() = runBlocking {
        val grocery = fx.leafId("Grocery")
        val outcome = fx.expenses.insert(
            Money.ofTaka(500), grocery, fx.today,
            split = Split.YouPaid(listOf(Split.Owed(person("Rahim"), Money.ZERO))),
        )
        assertEquals(SaveOutcome.Rejected(EntryError.SPLIT_DOES_NOT_BALANCE), outcome)
        assertEquals(0L, scalar("SELECT COUNT(*) FROM expense"))
    }

    @Test
    fun the_same_person_cannot_owe_twice_on_one_bill() = runBlocking {
        // `ux_share_expense_person` would refuse it; catching it above means
        // the user reads why. Splitting with the same person twice is a
        // mistake, not a second debt.
        val grocery = fx.leafId("Grocery")
        val rahim = person("Rahim")
        val outcome = fx.expenses.insert(
            Money.ofTaka(900), grocery, fx.today,
            split = Split.YouPaid(
                listOf(Split.Owed(rahim, Money.ofTaka(300)), Split.Owed(rahim, Money.ofTaka(300))),
            ),
        )
        assertEquals(SaveOutcome.Rejected(EntryError.SPLIT_DOES_NOT_BALANCE), outcome)
        assertEquals(0L, scalar("SELECT COUNT(*) FROM expense"))
    }

    // --- FR-SHR-06: filtering by person ---------------------------------------

    @Test
    fun filtering_by_person_matches_from_both_sides() = runBlocking {
        // "Things I did with Rahim" means the dinner he owes me for *and* the
        // one he paid for. Catching only one silently answers half the question.
        val grocery = fx.leafId("Grocery")
        val rahim = person("Rahim")
        val karim = person("Karim")

        val (yours, split) = Split.evenly(Money.ofTaka(1_000), listOf(rahim))
        savedId(fx.expenses.insert(yours, grocery, fx.today, split = split))
        savedId(
            fx.expenses.insert(
                Money.ofTaka(250), grocery, fx.today, split = Split.TheyPaid(rahim),
            ),
        )
        // Karim's, and a plain one — neither should match.
        val (kYours, kSplit) = Split.evenly(Money.ofTaka(600), listOf(karim))
        savedId(fx.expenses.insert(kYours, grocery, fx.today, split = kSplit))
        savedId(fx.expenses.insert(Money.ofTaka(90), grocery, fx.today))

        val filters = com.app.finance.domain.model.LedgerFilters(personId = rahim)
        val page = fx.expenses.filteredPage(filters)

        assertEquals(2, page.size)
        assertEquals(1, filters.activeCount)
    }

    @Test
    fun the_filtered_total_agrees_with_the_page_under_a_person_filter() = runBlocking {
        // FR-EXP-11 still holds: `page` and `filteredTotal` share one predicate,
        // and adding a semi-join to both is exactly where they could diverge.
        val grocery = fx.leafId("Grocery")
        val rahim = person("Rahim")
        val (yours, split) = Split.evenly(Money.ofTaka(1_000), listOf(rahim))
        savedId(fx.expenses.insert(yours, grocery, fx.today, split = split))
        savedId(
            fx.expenses.insert(
                Money.ofTaka(250), grocery, fx.today, split = Split.TheyPaid(rahim),
            ),
        )
        savedId(fx.expenses.insert(Money.ofTaka(90), grocery, fx.today))

        val filters = com.app.finance.domain.model.LedgerFilters(personId = rahim)
        val page = fx.expenses.filteredPage(filters)
        val total = fx.expenses.filteredTotal(filters)

        assertEquals(page.size, total.txnCount)
        assertEquals(page.sumOf { it.expense.amountMinor }, total.totalMinor)
    }

    @Test
    fun an_unshared_expense_is_untouched_by_any_of_this() = runBlocking {
        // The common case, and the one that must cost nothing. No payer, no
        // shares, no extra rows.
        val grocery = fx.leafId("Grocery")
        val id = savedId(fx.expenses.insert(Money.ofTaka(250), grocery, fx.today))

        assertNull(fx.expenses.byId(id)!!.payerPersonId)
        assertEquals(0L, scalar("SELECT COUNT(*) FROM expense_share"))
        assertEquals(Split.NONE, fx.expenses.splitOf(id)!!.split)
        assertEquals(Money.ofTaka(250), fx.expenses.splitOf(id)!!.bill)
    }
}
