package com.app.finance.domain.usecase

import com.app.finance.core.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-SHR-02 — the parts must account for every paisa of the bill.
 *
 * The property under test is one sentence: whatever comes out sums to what went
 * in. Everything else here is a way for that to fail.
 */
class SplitAllocatorTest {

    private fun taka(n: Long) = Money.ofTaka(n)

    private fun List<Money>.total() = Money(sumOf { it.paisa })

    // --- the property ---------------------------------------------------------

    @Test
    fun `a thousand taka three ways keeps every paisa`() {
        // The case that motivates the class. 100,000 / 3 = 33,333 remainder 1,
        // so a truncating divide loses one paisa and the parts describe a bill
        // of ৳999.99 that nobody paid.
        val parts = SplitAllocator.even(taka(1_000), parts = 3)

        assertEquals(taka(1_000), parts.total())
        assertEquals(listOf(33_334L, 33_333L, 33_333L), parts.map { it.paisa })
    }

    @Test
    fun `an even division leaves no part carrying extra`() {
        val parts = SplitAllocator.even(taka(1_000), parts = 4)
        assertEquals(taka(1_000), parts.total())
        assertTrue("no part should differ", parts.all { it == taka(250) })
    }

    @Test
    fun `every division from two to twenty ways adds back up`() {
        // The property, swept. A bill of 100,001 paisa is deliberately awkward:
        // it is prime-ish in the small divisors and leaves a remainder for most
        // of them.
        val bill = Money(100_001)
        for (n in 2..20) {
            val parts = SplitAllocator.even(bill, parts = n)
            assertEquals("$n ways", bill, parts.total())
            assertEquals("$n ways", n, parts.size)
        }
    }

    @Test
    fun `no part is ever more than one paisa from another`() {
        // What makes the split fair as well as exact: the leftover is spread
        // one paisa at a time, never dumped on a single person.
        val parts = SplitAllocator.even(Money(1_000), parts = 7).map { it.paisa }
        assertEquals(1L, parts.max() - parts.min())
    }

    @Test
    fun `a refund splits in the same proportions`() {
        // FR-EXP-06's negative amounts reach here too: returning a shared
        // purchase refunds each person their part.
        val parts = SplitAllocator.even(taka(-1_000), parts = 3)

        assertEquals(taka(-1_000), parts.total())
        assertTrue("every part should be a refund", parts.all { it.isNegative })
        // The magnitude is allocated and the sign restored, so the leftover
        // paisa lands the same way it does on the positive side rather than
        // wherever integer division happened to truncate.
        assertEquals(listOf(-33_334L, -33_333L, -33_333L), parts.map { it.paisa })
    }

    @Test
    fun `a bill smaller than the number of people still adds up`() {
        // Two paisa, three ways. Somebody gets nothing, which is arithmetic
        // rather than a bug — and the total is still two paisa.
        val parts = SplitAllocator.even(Money(2), parts = 3)
        assertEquals(Money(2), parts.total())
        assertEquals(listOf(1L, 1L, 0L), parts.map { it.paisa })
    }

    @Test
    fun `splitting no ways yields nothing rather than dividing by zero`() {
        assertTrue(SplitAllocator.even(taka(100), parts = 0).isEmpty())
        assertTrue(SplitAllocator.even(taka(100), parts = -1).isEmpty())
    }

    // --- your share, and what makes a split legal -----------------------------

    @Test
    fun `your share is whatever the others do not account for`() {
        assertEquals(
            taka(250),
            SplitAllocator.yourShare(taka(1_000), listOf(taka(250), taka(250), taka(250))),
        )
    }

    @Test
    fun `your share absorbs the rounding`() {
        // ৳1,000 three ways: two others take 33,334 and 33,333, and what is
        // left is what the ledger stores. Reconstructing the bill from the
        // stored parts has to give back exactly what was typed.
        val all = SplitAllocator.even(taka(1_000), parts = 3)
        val others = all.drop(1)
        val yours = SplitAllocator.yourShare(taka(1_000), others)

        assertEquals(taka(1_000), Money(yours.paisa + others.sumOf { it.paisa }))
    }

    @Test
    fun `a share of nothing is refused`() {
        // `CHECK (share_minor > 0)` would refuse it anyway; catching it here
        // means the user reads a sentence instead of a constraint violation.
        assertFalse(SplitAllocator.isBalanced(taka(1_000), listOf(taka(500), Money.ZERO)))
        assertFalse(SplitAllocator.isBalanced(taka(1_000), listOf(taka(500), taka(-100))))
    }

    @Test
    fun `others accounting for the whole bill is not a shared expense`() {
        // Paying entirely on somebody else's behalf is a loan — FR-SHR-04's
        // settlement — not an expense of yours. Your share would be zero, and
        // `CHECK (amount_minor <> 0)` refuses that row for the same reason.
        assertFalse(SplitAllocator.isBalanced(taka(1_000), listOf(taka(1_000))))
        assertFalse(SplitAllocator.isBalanced(taka(1_000), listOf(taka(600), taka(500))))
    }

    @Test
    fun `a split leaving you a single paisa is legal`() {
        // The boundary, from the legal side, so the guard is a `> 0` and not an
        // accidental `>= some minimum`.
        assertTrue(SplitAllocator.isBalanced(Money(100), listOf(Money(99))))
    }
}
