package com.app.finance.domain.usecase

import com.app.finance.core.money.Money
import com.app.finance.core.time.Period
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The six-period trend and its reference line — FR-AN-09. */
class ExpenseTrendTest {

    private val aug = Period.of(2026, 8)
    private val periods = aug.trailing(ExpenseTrend.LENGTH)

    private fun trend(
        spend: Map<Int, Long> = emptyMap(),
        limits: Map<Int, Long> = emptyMap(),
    ) = ExpenseTrend.of(periods, spend, limits)

    private fun ym(month: Int, year: Int = 2026) = Period.of(year, month).ym

    @Test
    fun `six periods, oldest first, ending at the selection`() {
        assertEquals(6, periods.size)
        assertEquals(Period.of(2026, 3), periods.first())
        assertEquals(aug, periods.last())
    }

    @Test
    fun `each period lands in its own column`() {
        val t = trend(spend = mapOf(ym(3) to 100L, ym(8) to 600L))
        assertArrayEquals(longArrayOf(100, 0, 0, 0, 0, 600), t.spend)
    }

    @Test
    fun `a period with nothing recorded is zero rather than absent`() {
        // The chart is a fixed six columns. A month the user logged nothing in
        // is a real month with a real answer, and a gap would misalign every
        // label after it.
        val t = trend(spend = mapOf(ym(5) to 500L))
        assertEquals(6, t.spend.size)
        assertEquals(0L, t.spend[0])
    }

    @Test
    fun `the window crosses a year boundary`() {
        val jan = Period.of(2026, 1)
        val t = ExpenseTrend.of(
            periods = jan.trailing(ExpenseTrend.LENGTH),
            spendByPeriod = mapOf(ym(8, year = 2025) to 800L, ym(1) to 100L),
            limitsByPeriod = emptyMap(),
        )
        assertArrayEquals(longArrayOf(800, 0, 0, 0, 0, 100), t.spend)
    }

    // --- the reference line ---------------------------------------------------

    @Test
    fun `the reference is per period, not one flat line`() {
        // Limits are set per period (FR-BUD-01). A single line would become a
        // fiction the moment the user changed one.
        val t = trend(limits = mapOf(ym(3) to 1_000L, ym(8) to 2_000L))
        assertArrayEquals(longArrayOf(1_000, 0, 0, 0, 0, 2_000), t.reference)
    }

    @Test
    fun `a period over its budget is flagged`() {
        val t = trend(
            spend = mapOf(ym(8) to 2_500L),
            limits = mapOf(ym(8) to 2_000L),
        )
        assertTrue(t.isOver(5))
    }

    @Test
    fun `spending exactly the reference is not over it`() {
        val t = trend(spend = mapOf(ym(8) to 2_000L), limits = mapOf(ym(8) to 2_000L))
        assertFalse(t.isOver(5))
    }

    @Test
    fun `a period with no budget can never be over`() {
        // A zero reference means no limit was set, not a limit of nothing.
        val t = trend(spend = mapOf(ym(8) to 9_000L))
        assertFalse(t.isOver(5))
    }

    // --- the whole ------------------------------------------------------------

    @Test
    fun `the total is the sum of the six`() {
        val t = trend(spend = mapOf(ym(3) to 100L, ym(4) to 200L, ym(8) to 300L))
        assertEquals(Money(600L), t.total)
    }

    @Test
    fun `six empty months report themselves empty`() {
        // The screen drops the section entirely rather than drawing a flat line
        // along the floor — 05 §5.4's absent-not-empty rule.
        assertTrue(trend().isEmpty)
        assertFalse(trend(spend = mapOf(ym(8) to 1L)).isEmpty)
    }

    @Test
    fun `two trends over the same data are equal`() {
        // The arrays would otherwise compare by identity, which is the defect
        // `IncomeSummary` had to be hand-written to avoid.
        val a = trend(spend = mapOf(ym(8) to 300L), limits = mapOf(ym(8) to 200L))
        val b = trend(spend = mapOf(ym(8) to 300L), limits = mapOf(ym(8) to 200L))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `trends over different data are not equal`() {
        val a = trend(spend = mapOf(ym(8) to 300L))
        val b = trend(spend = mapOf(ym(8) to 400L))
        assertFalse(a == b)
    }
}
