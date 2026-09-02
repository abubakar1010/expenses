package com.app.finance.domain.model

import com.app.finance.core.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Split.validate] — FR-SHR-02's arithmetic, on the way in.
 *
 * There is no stored total, so a bad allocation is not a constraint violation
 * waiting to happen: it stores cleanly and means something else. This is the
 * only layer that can refuse one, which is why it is tested on its own rather
 * than through a repository.
 */
class SplitTest {

    private fun taka(n: Long) = Money.ofTaka(n)

    @Test
    fun an_unshared_expense_has_nothing_to_check() {
        assertNull(Split.NONE.validate(taka(340)))
        assertFalse(Split.NONE.isShared)
    }

    @Test
    fun a_refund_is_not_a_split_and_is_left_alone() {
        // FR-EXP-06 — a negative amount is a legal expense, and `Split.NONE`
        // must not be dragged into the balance check meant for shares.
        assertNull(Split.NONE.validate(taka(-340)))
    }

    @Test
    fun an_even_split_of_a_bill_balances() {
        val (yours, split) = Split.evenly(taka(1_000), listOf(1L, 2L, 3L))
        assertNull(split.validate(yours))
        assertEquals(
            taka(1_000).paisa,
            yours.paisa + split.owed.sumOf { it.amount.paisa },
        )
    }

    @Test
    fun a_share_of_nothing_is_refused() {
        val split = Split.YouPaid(listOf(Split.Owed(1L, Money.ZERO)))
        assertEquals(EntryError.SPLIT_DOES_NOT_BALANCE, split.validate(taka(1_000)))
    }

    @Test
    fun the_same_person_listed_twice_is_refused() {
        val split = Split.YouPaid(
            listOf(Split.Owed(1L, taka(100)), Split.Owed(1L, taka(200))),
        )
        assertEquals(EntryError.SPLIT_DOES_NOT_BALANCE, split.validate(taka(700)))
    }

    @Test
    fun shares_that_swallow_the_whole_bill_are_refused() {
        // The defect this rule exists for: ৳600 and ৳500 typed against a
        // ৳1,000 dinner leaves your share at −৳100. `CHECK (amount_minor <> 0)`
        // accepts that — it is how FR-EXP-06's refund is stored — so the row
        // saved and the month took a ৳100 credit for a meal that was eaten.
        val split = Split.YouPaid(
            listOf(Split.Owed(1L, taka(600)), Split.Owed(2L, taka(500))),
        )
        assertEquals(EntryError.SPLIT_DOES_NOT_BALANCE, split.validate(taka(-100)))
    }

    @Test
    fun a_bill_left_exactly_covered_is_refused_too() {
        // Paying entirely on somebody's behalf is a loan, which FR-SHR-04's
        // settlement records without pretending anything was consumed.
        val split = Split.YouPaid(listOf(Split.Owed(1L, taka(1_000))))
        assertEquals(EntryError.SPLIT_DOES_NOT_BALANCE, split.validate(Money.ZERO))
    }

    @Test
    fun one_paisa_left_for_you_is_enough() {
        val split = Split.YouPaid(listOf(Split.Owed(1L, Money(99))))
        assertNull(split.validate(Money(1)))
    }

    @Test
    fun somebody_else_paying_carries_no_shares_to_check() {
        val split = Split.TheyPaid(7L)
        assertNull(split.validate(taka(250)))
        assertEquals(7L, split.payerPersonId)
        assertTrue(split.owed.isEmpty())
    }
}
