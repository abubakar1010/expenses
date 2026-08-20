package com.app.finance.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shared apportionment — FR-IE-06 and FR-AN-07.
 *
 * `IncomeBreakdownTest` is the regression guard for the extraction: it was
 * written against the inlined version and passes unchanged against this one.
 * What is here is the property itself, stated once for both callers.
 */
class LargestRemainderTest {

    private fun sum(vararg weights: Long) = LargestRemainder.percentages(weights.toList()).sum()

    // --- the property ---------------------------------------------------------

    @Test
    fun `three equal parts sum to a hundred`() {
        // 33 + 33 + 33 = 99. This is the everyday case, not a corner one.
        assertEquals(listOf(34, 33, 33), LargestRemainder.percentages(listOf(1L, 1L, 1L)))
    }

    @Test
    fun `six equal parts sum to a hundred`() {
        // 16 x 6 = 96. Four parts take the leftover point.
        assertEquals(100, sum(1, 1, 1, 1, 1, 1))
        assertEquals(
            listOf(17, 17, 17, 17, 16, 16),
            LargestRemainder.percentages(List(6) { 1L }),
        )
    }

    @Test
    fun `seven awkward parts sum to a hundred`() {
        assertEquals(100, sum(1_237, 894, 663, 421, 318, 199, 77))
    }

    @Test
    fun `a long tail of ones sums to a hundred`() {
        val weights = listOf(9_100L) + List(9) { 100L }
        val shares = LargestRemainder.percentages(weights)
        assertEquals(100, shares.sum())
        assertEquals(91, shares.first())
    }

    @Test
    fun `a hundred equal parts each take one point`() {
        assertEquals(List(100) { 1 }, LargestRemainder.percentages(List(100) { 1L }))
    }

    @Test
    fun `two hundred equal parts still sum to a hundred`() {
        // Half get a point and half get none. The floor sum is zero here, so
        // this is the case where the leftover equals the whole hundred.
        val shares = LargestRemainder.percentages(List(200) { 1L })
        assertEquals(100, shares.sum())
        assertEquals(100, shares.count { it == 1 })
    }

    // --- ties and order -------------------------------------------------------

    @Test
    fun `ties break by position, so the caller's order decides`() {
        // Both callers sort largest-first before apportioning, so the leftover
        // point lands on the row a reader would expect rather than wherever a
        // hash happened to put it.
        assertEquals(listOf(34, 33, 33), LargestRemainder.percentages(listOf(5L, 5L, 5L)))
    }

    @Test
    fun `the same input always produces the same column`() {
        val weights = listOf(360_000L, 144_000L, 80_000L)
        assertEquals(
            LargestRemainder.percentages(weights),
            LargestRemainder.percentages(weights),
        )
    }

    @Test
    fun `05's own mock does not add up, and this is what does`() {
        // 05 §5.7 prints 62 / 25 / 14 against these amounts, which sums to 101.
        // The exact shares are 61.64, 24.66 and 13.70.
        assertEquals(
            listOf(61, 25, 14),
            LargestRemainder.percentages(listOf(360_000_00L, 144_000_00L, 80_000_00L)),
        )
    }

    // --- degenerate inputs ----------------------------------------------------

    @Test
    fun `one part takes the whole hundred`() {
        assertEquals(listOf(100), LargestRemainder.percentages(listOf(42L)))
    }

    @Test
    fun `nothing to divide gives zeroes rather than a division by zero`() {
        assertEquals(listOf(0, 0, 0), LargestRemainder.percentages(listOf(0L, 0L, 0L)))
    }

    @Test
    fun `an empty list is an empty list`() {
        assertEquals(emptyList<Int>(), LargestRemainder.percentages(emptyList()))
    }

    @Test
    fun `a zero part takes no share and steals no point`() {
        val shares = LargestRemainder.percentages(listOf(1L, 1L, 1L, 0L))
        assertEquals(100, shares.sum())
        assertEquals(0, shares.last())
    }

    @Test
    fun `a negative weight is treated as nothing rather than subtracting`() {
        // Expenses can be negative — FR-EXP-06's refunds — and a share of the
        // total is not a place where a refund should invert the arithmetic.
        val shares = LargestRemainder.percentages(listOf(3L, 1L, -5L))
        assertEquals(100, shares.sum())
        assertEquals(0, shares.last())
    }
}
