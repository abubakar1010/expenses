package com.app.finance.domain.usecase

import com.app.finance.core.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The net strip — FR-AN-02 and FR-AN-03. */
class NetPositionTest {

    private fun of(income: Long, expenses: Long) =
        NetPosition(Money.ofTaka(income), Money.ofTaka(expenses))

    @Test
    fun `05's own example`() {
        // "Earned ৳48,000  Spent ৳31,600 / Net +৳16,400 · saving 34%"
        val net = of(income = 48_000, expenses = 31_600)
        assertEquals(Money.ofTaka(16_400), net.net)
        assertEquals(34, net.savingsRate)
    }

    @Test
    fun `spending more than was earned is a negative net, not a zero one`() {
        val net = of(income = 20_000, expenses = 32_000)
        assertEquals(Money.ofTaka(-12_000), net.net)
        assertTrue(net.net.isNegative)
    }

    @Test
    fun `a negative savings rate is reported rather than suppressed`() {
        // The month the user most needs to see is the one they overspent, and a
        // rate that only ever appears when it is good is a rate nobody trusts.
        assertEquals(-60, of(income = 20_000, expenses = 32_000).savingsRate)
    }

    @Test
    fun `zero income suppresses the rate entirely`() {
        // FR-AN-03 — "suppressed when income is zero". Null rather than 0%: a
        // ratio with no denominator is not a figure with a value, and a month
        // with no income yet is ordinary for this user, not an error.
        val net = of(income = 0, expenses = 8_000)
        assertNull(net.savingsRate)
        assertEquals(Money.ofTaka(-8_000), net.net)
    }

    @Test
    fun `zero income and zero spending still suppresses it`() {
        assertNull(of(income = 0, expenses = 0).savingsRate)
    }

    @Test
    fun `spending nothing is a hundred percent saved`() {
        assertEquals(100, of(income = 40_000, expenses = 0).savingsRate)
    }

    @Test
    fun `the rate truncates rather than rounding up`() {
        // 16,400 / 48,000 is 34.16%. The conservative direction for a figure
        // the user might feel good about.
        assertEquals(34, of(income = 48_000, expenses = 31_600).savingsRate)
        // 34.99% is still 34.
        assertEquals(34, of(income = 10_000, expenses = 6_501).savingsRate)
    }

    @Test
    fun `a lump-sum month is not clamped at a hundred`() {
        // A harvest lands and nothing is spent: the rate is what it is. The
        // same argument that keeps `percentConsumed` unclamped above a limit.
        val net = of(income = 500_000, expenses = 10_000)
        assertEquals(98, net.savingsRate)
    }

    // --- rounding (A6) --------------------------------------------------------

    @Test
    fun `a negative rate is floored rather than rounded toward zero`() {
        // −60.5% truncated toward zero reads −60%, which flatters a month that
        // went badly. `Money.divideBy` names that as the wrong direction to err.
        // 10,000 in, 16,050 out: net −6,050, exactly −60.5%.
        assertEquals(-61, of(income = 10_000, expenses = 16_050).savingsRate)
    }

    @Test
    fun `a positive rate is unchanged by the floor`() {
        assertEquals(34, of(income = 48_000, expenses = 31_600).savingsRate)
        assertEquals(34, of(income = 10_000, expenses = 6_501).savingsRate)
        assertEquals(100, of(income = 40_000, expenses = 0).savingsRate)
    }

    @Test
    fun `an exact negative rate is not pushed a point further down`() {
        // Flooring must not cost a point where there is no fraction to lose.
        assertEquals(-50, of(income = 10_000, expenses = 15_000).savingsRate)
    }
}
