package com.app.finance.domain.model

import com.app.finance.core.time.Period
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Which query answers which scope, and which twelve bars go with it.
 *
 * The two rules under test are the ones the documents state and the code has to
 * reconcile: 03 §5.3 ("ranges that do not align to month boundaries cannot use
 * rollups") decides the window, and FR-IE-07 ("a 12-month income trend ending at
 * the currently selected period") decides the bars.
 */
class IncomeScopeTest {

    private val aug = Period.of(2026, 8)

    // --- windows -------------------------------------------------------------

    @Test
    fun `a year resolves to its twelve whole periods`() {
        val window = IncomeScope.Year(aug).window as IncomeWindow.Periods
        assertEquals(Period.of(2026, 1), window.start)
        assertEquals(Period.of(2026, 12), window.end)
    }

    @Test
    fun `a month resolves to itself`() {
        val window = IncomeScope.Month(aug).window as IncomeWindow.Periods
        assertEquals(aug, window.start)
        assertEquals(aug, window.end)
    }

    @Test
    fun `a range resolves to epoch days, not periods`() {
        // The whole reason the type has two shapes: this one cannot be served
        // by the rollup, so it must not resolve to periods.
        val from = LocalDate.of(2026, 4, 15)
        val to = LocalDate.of(2026, 6, 10)
        val window = IncomeScope.Range(from, to).window as IncomeWindow.Days
        assertEquals(from.toEpochDay(), window.from)
        assertEquals(to.toEpochDay(), window.to)
    }

    @Test
    fun `a range that happens to align to months still uses the ledger`() {
        // Not an optimisation opportunity worth taking: the user asked for a
        // range, and one code path answering ranges is easier to reason about
        // than two that agree only most of the time.
        val window = IncomeScope.Range(
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 12, 31),
        ).window
        assert(window is IncomeWindow.Days)
    }

    // --- the twelve bars -----------------------------------------------------

    @Test
    fun `a year's bars are January to December — the guide's axis`() {
        // 05 §5.7 labels them `J F M A M J J A S O N D`, which is what "the
        // twelve periods ending at the selected year" produces.
        val bars = IncomeScope.Year(aug).trendPeriods()
        assertEquals(12, bars.size)
        assertEquals(Period.of(2026, 1), bars.first())
        assertEquals(Period.of(2026, 12), bars.last())
    }

    @Test
    fun `a month's bars are the trailing twelve — the requirement's wording`() {
        // FR-IE-07: "ending at the currently selected period".
        val bars = IncomeScope.Month(aug).trendPeriods()
        assertEquals(12, bars.size)
        assertEquals(Period.of(2025, 9), bars.first())
        assertEquals(aug, bars.last())
    }

    @Test
    fun `the trailing twelve cross a year boundary correctly`() {
        // 202601 minus eleven months is 202502, not 202590. Period arithmetic
        // delegates to YearMonth for exactly this reason.
        val bars = IncomeScope.Month(Period.of(2026, 1)).trendPeriods()
        assertEquals(Period.of(2025, 2), bars.first())
        assertEquals(Period.of(2026, 1), bars.last())
        assertEquals(bars, bars.sorted())
    }

    @Test
    fun `a range's bars end at the period containing its last day`() {
        val bars = IncomeScope.Range(
            LocalDate.of(2025, 11, 3),
            LocalDate.of(2026, 3, 20),
        ).trendPeriods()
        assertEquals(Period.of(2026, 3), bars.last())
        assertEquals(Period.of(2025, 4), bars.first())
    }

    @Test
    fun `every scope produces exactly twelve bars`() {
        // The chart is a fixed twelve columns; a scope that produced eleven or
        // thirteen would silently misalign every label.
        listOf(
            IncomeScope.Year(aug),
            IncomeScope.Month(aug),
            IncomeScope.Range(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2)),
        ).forEach { assertEquals(IncomeScope.TREND_LENGTH, it.trendPeriods().size) }
    }

    @Test
    fun `the default scope is the year`() {
        // 05 §5.7 — "the income screen defaults to a yearly view while every
        // other screen defaults to monthly", and it is emphatic that this is
        // not an inconsistency to tidy away.
        assertEquals(IncomeScope.Year(aug), IncomeScope.default(aug))
    }

    // --- the trend window (D1) -----------------------------------------------

    @Test
    fun `the trend window is twelve whole periods in every scope`() {
        // FR-IE-07 asks for twelve months of income. The *display* window is
        // one month in Month scope and a span of days in Range scope, and
        // folding either into twelve periods draws eleven guaranteed-zero bars.
        listOf(
            IncomeScope.Year(aug),
            IncomeScope.Month(aug),
            IncomeScope.Range(LocalDate.of(2026, 6, 15), LocalDate.of(2026, 8, 10)),
        ).forEach { scope ->
            val trend = scope.trendWindow
            assertEquals(scope.trendPeriods().first(), trend.start)
            assertEquals(scope.trendPeriods().last(), trend.end)
        }
    }

    @Test
    fun `only the year scope reads the same window twice`() {
        // Which is why the ViewModel can skip the second subscription there,
        // and why this defect was invisible in the default scope.
        assertEquals(IncomeScope.Year(aug).window, IncomeScope.Year(aug).trendWindow)
        assertNotEquals(IncomeScope.Month(aug).window, IncomeScope.Month(aug).trendWindow)

        val range = IncomeScope.Range(LocalDate.of(2026, 6, 15), LocalDate.of(2026, 8, 10))
        assertNotEquals(range.window, range.trendWindow as IncomeWindow)
    }

    @Test
    fun `a range reads its trend from whole months, never from days`() {
        // 03 §5.3 sends the range's own total to the ledger. The bars are
        // months either way, so they stay on the rollup.
        val range = IncomeScope.Range(LocalDate.of(2026, 6, 15), LocalDate.of(2026, 8, 10))
        assertEquals(IncomeWindow.Periods(Period.of(2025, 9), Period.of(2026, 8)), range.trendWindow)
    }

    @Test
    fun `a month's trend window ends at the month and reaches back a year`() {
        val trend = IncomeScope.Month(Period.of(2026, 1)).trendWindow
        assertEquals(Period.of(2025, 2), trend.start)
        assertEquals(Period.of(2026, 1), trend.end)
    }
}
