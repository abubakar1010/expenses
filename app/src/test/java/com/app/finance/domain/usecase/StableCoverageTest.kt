package com.app.finance.domain.usecase

import com.app.finance.core.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * FR-AN-06 — stable income ÷ total expenses, "the insight that matters"
 * (05 §5.7).
 */
class StableCoverageTest {

    private fun coverage(stable: Long, spent: Long) =
        StableCoverage.percent(Money.ofTaka(stable), Money.ofTaka(spent))

    @Test
    fun `the guide's own figure`() {
        // "Stable income covers 71% of your spending this year."
        assertEquals(71, coverage(360_000, 507_042))
    }

    @Test
    fun `nothing spent has no coverage figure at all`() {
        // Not 100%, and not an error: a ratio with no denominator. The screen
        // omits the line rather than printing something confident and wrong,
        // which is also 05 §5.4's "sections that have nothing to say are
        // absent, not empty".
        assertNull(coverage(50_000, 0))
        assertNull(coverage(0, 0))
    }

    @Test
    fun `no stable income is zero rather than nothing`() {
        // A real answer, and an alarming one — this user has spending and no
        // dependable income behind it. Suppressing the line here would hide
        // exactly the situation the metric exists to surface.
        assertEquals(0, coverage(0, 40_000))
    }

    @Test
    fun `covering more than everything is not clamped`() {
        // Earning more in stable income than you spent is real and it is good
        // news; rounding it to 100% would hide the size of the margin. The same
        // argument keeps `percentConsumed` unclamped above a budget limit.
        assertEquals(250, coverage(100_000, 40_000))
    }

    @Test
    fun `it rounds down rather than up`() {
        // 6,999 / 10,000 is 69.99%. Reporting 70 would overstate how covered
        // this user is, and overstating is the dangerous direction — the same
        // reason `Money.divideBy` rounds toward zero.
        assertEquals(69, coverage(6_999, 10_000))
    }

    @Test
    fun `exact coverage is a hundred`() {
        assertEquals(100, coverage(40_000, 40_000))
    }
}
