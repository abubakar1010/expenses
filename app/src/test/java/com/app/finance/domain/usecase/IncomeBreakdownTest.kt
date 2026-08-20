package com.app.finance.domain.usecase

import com.app.finance.core.money.Money
import com.app.finance.core.time.Period
import com.app.finance.domain.model.IncomeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-IE-06 — the per-source breakdown, and the one line of its acceptance
 * criterion that the obvious implementation fails:
 *
 * > "Percentages sum to 100 ± 0.1 after rounding."
 *
 * Rounding each share on its own does not do that. Three equal sources each
 * round to 33 and the column reads 99; six equal ones read 96. That is not a
 * corner case — near-equal sources are an ordinary shape for this user's
 * income — and a breakdown whose percentages visibly fail to add up undermines
 * every other figure on the screen.
 */
class IncomeBreakdownTest {

    private val year = (1..12).map { Period.of(2026, it) }

    private fun cell(month: Int, id: Long, name: String, taka: Long, stable: Boolean = false) =
        IncomeCell(
            periodYm = Period.of(2026, month).ym,
            sourceId = id,
            sourceName = name,
            kind = if (stable) IncomeKind.STABLE.code else IncomeKind.VARIABLE.code,
            totalMinor = taka * 100,
        )

    private fun build(vararg cells: IncomeCell, sources: Set<Long> = emptySet()) =
        fold(cells.toList(), sources)

    /**
     * The ordinary case: the window and the trend cover the same span, which is
     * true in Year scope and only there.
     */
    private fun fold(cells: List<IncomeCell>, sources: Set<Long> = emptySet()) =
        IncomeBreakdown.build(cells, cells, sources, year)

    // --- the figures 05 §5.7 draws -------------------------------------------

    @Test
    fun `the guide's own example, with its arithmetic corrected`() {
        // 05 §5.7's mock: Salary ৳3,60,000 62%, Real estate ৳1,44,000 25%,
        // Farming ৳80,000 14%. The amounts are right — they total ৳5,84,000 —
        // but the percentages sum to **101**, so the mock cannot be the target.
        //
        // The exact shares are 61.64, 24.66 and 13.70. Naive rounding gives the
        // mock's 62/25/14; largest remainder gives 61/25/14, because Salary has
        // the *smallest* fractional part of the three and is the one that
        // yields the point. That is the trade the method makes: Salary is 0.64
        // from exact instead of 0.36, and in return the column adds up — which
        // is the acceptance criterion, and the thing a reader can check.
        //
        // This is the second place the HTML mock's arithmetic does not hold;
        // §4.3's "never abbreviate to 1.2k" against §5.4's "18k" was the first.
        val summary = build(
            cell(1, 1, "Salary", 360_000, stable = true),
            cell(6, 2, "Real estate", 144_000),
            cell(9, 3, "Farming", 80_000),
        )

        assertEquals(Money.ofTaka(584_000), summary.total)
        assertEquals(Money.ofTaka(360_000), summary.stableTotal)
        assertEquals(listOf("Salary", "Real estate", "Farming"), summary.shares.map { it.name })
        assertEquals(listOf(61, 25, 14), summary.shares.map { it.share })
        assertEquals(100, summary.shares.sumOf { it.share })
    }

    @Test
    fun `sources are ordered largest first`() {
        // 03 §5.2's `ORDER BY r.total_minor DESC` — the question a breakdown
        // answers is "where does it come from", so the biggest answer is first.
        val summary = build(
            cell(1, 1, "Small", 100),
            cell(1, 2, "Large", 900),
            cell(1, 3, "Middle", 500),
        )
        assertEquals(listOf("Large", "Middle", "Small"), summary.shares.map { it.name })
    }

    // --- the apportionment ---------------------------------------------------

    @Test
    fun `three equal sources still sum to a hundred`() {
        // The everyday failure: 33 + 33 + 33 = 99.
        val summary = build(
            cell(1, 1, "A", 100),
            cell(1, 2, "B", 100),
            cell(1, 3, "C", 100),
        )
        assertEquals(100, summary.shares.sumOf { it.share })
        assertEquals(listOf(34, 33, 33), summary.shares.map { it.share })
    }

    @Test
    fun `six equal sources still sum to a hundred`() {
        // 16 × 6 = 96. Four sources take the leftover point.
        val summary = fold(
            (1L..6L).map { cell(1, it, "S$it", 100) },
        )
        assertEquals(100, summary.shares.sumOf { it.share })
        assertEquals(listOf(17, 17, 17, 17, 16, 16), summary.shares.map { it.share })
    }

    @Test
    fun `a long tail of ones still sums to a hundred`() {
        val summary = fold(
            listOf(cell(1, 99, "Big", 9_100)) + (1L..9L).map { cell(1, it, "Tail$it", 100) },
        )
        assertEquals(100, summary.shares.sumOf { it.share })
        assertEquals(91, summary.shares.first().share)
    }

    @Test
    fun `seven awkward sources still sum to a hundred`() {
        // Deliberately ugly amounts so every remainder is different.
        val amounts = listOf(1_237L, 894L, 663L, 421L, 318L, 199L, 77L)
        val summary = fold(
            amounts.mapIndexed { i, taka -> cell(1, i + 1L, "S$i", taka) },
        )
        assertEquals(100, summary.shares.sumOf { it.share })
    }

    @Test
    fun `one source takes the whole hundred`() {
        val summary = build(cell(1, 1, "Salary", 30_000, stable = true))
        assertEquals(listOf(100), summary.shares.map { it.share })
    }

    @Test
    fun `no income at all is an empty summary rather than a division by zero`() {
        val summary = build()
        assertEquals(Money.ZERO, summary.total)
        assertEquals(Money.ZERO, summary.stableTotal)
        assertTrue(summary.isEmpty)
        assertTrue(summary.trend.all { it == 0L })
    }

    @Test
    fun `no source is more than one point from its exact share`() {
        val amounts = listOf(5_003L, 3_001L, 1_999L, 997L, 503L)
        val total = amounts.sum()
        val summary = fold(
            amounts.mapIndexed { i, taka -> cell(1, i + 1L, "S$i", taka) },
        )
        summary.shares.forEachIndexed { i, share ->
            val exact = amounts[i] * 100.0 / total
            assertTrue(
                "share ${share.share} is more than a point off $exact",
                kotlin.math.abs(share.share - exact) < 1.0,
            )
        }
    }

    // --- folding -------------------------------------------------------------

    @Test
    fun `one source across many months is one row`() {
        // FR-IE-02 — unlimited entries per source per period, and across them.
        val summary = build(
            cell(1, 1, "Salary", 30_000, stable = true),
            cell(2, 1, "Salary", 30_000, stable = true),
            cell(3, 1, "Salary", 30_000, stable = true),
        )
        assertEquals(1, summary.shares.size)
        assertEquals(Money.ofTaka(90_000), summary.shares.single().total)
    }

    @Test
    fun `the trend aligns to the periods it was given`() {
        // FR-IE-07's twelve bars, indexed by the position of their period.
        val summary = build(
            cell(1, 1, "Salary", 100),
            cell(6, 2, "Farming", 800),
            cell(12, 1, "Salary", 200),
        )
        assertEquals(100_00L, summary.trend[0])
        assertEquals(800_00L, summary.trend[5])
        assertEquals(200_00L, summary.trend[11])
        assertEquals(0L, summary.trend[3])
        assertEquals(summary.total.paisa, summary.trend.sum())
    }

    @Test
    fun `a cell outside the trend window still counts toward the total`() {
        // The window and the bars are the same span for Year and Month, but a
        // custom range ending mid-month is wider than its twelve bars. The
        // total must not silently drop what the chart cannot show.
        val summary = fold(
            listOf(
                IncomeCell(Period.of(2024, 5).ym, 1, "Old", IncomeKind.VARIABLE.code, 500_00),
                cell(3, 1, "Old", 700),
            ),
        )
        assertEquals(Money.ofTaka(1_200), summary.total)
        assertEquals(700_00L, summary.trend.sum())
    }

    // --- FR-IE-05 ------------------------------------------------------------

    @Test
    fun `a source subset filters the total, the shares and the bars together`() {
        val summary = build(
            cell(1, 1, "Salary", 30_000, stable = true),
            cell(6, 2, "Farming", 70_000),
            sources = setOf(2L),
        )
        assertEquals(Money.ofTaka(70_000), summary.total)
        assertEquals(Money.ZERO, summary.stableTotal)
        assertEquals(listOf("Farming"), summary.shares.map { it.name })
        // And the filtered subset is still 100% of itself.
        assertEquals(100, summary.shares.sumOf { it.share })
        assertEquals(0L, summary.trend[0])
        assertEquals(70_000_00L, summary.trend[5])
    }

    @Test
    fun `an empty subset means every source, not none`() {
        val summary = build(
            cell(1, 1, "Salary", 30_000),
            cell(1, 2, "Farming", 10_000),
            sources = emptySet(),
        )
        assertEquals(Money.ofTaka(40_000), summary.total)
        assertEquals(2, summary.shares.size)
    }

    @Test
    fun `the stable subtotal counts only stable sources`() {
        val summary = build(
            cell(1, 1, "Salary", 30_000, stable = true),
            cell(1, 2, "Pension", 10_000, stable = true),
            cell(6, 3, "Farming", 60_000),
        )
        assertEquals(Money.ofTaka(100_000), summary.total)
        assertEquals(Money.ofTaka(40_000), summary.stableTotal)
    }

    // --- the trend reads its own window (D1) ---------------------------------

    @Test
    fun `the bars come from the trend cells and the total from the window cells`() {
        // The Month-scope shape: the hero total is one month, the chart is the
        // trailing twelve. Reusing one read for both is what drew eleven
        // guaranteed-zero bars, on the one screen whose whole point is that the
        // shape of the year is the information.
        val summary = IncomeBreakdown.build(
            cells = listOf(cell(8, 1, "Salary", 30_000)),
            trendCells = listOf(
                cell(2, 2, "Farming", 80_000),
                cell(6, 2, "Farming", 50_000),
                cell(8, 1, "Salary", 30_000),
            ),
            sourceIds = emptySet(),
            trendPeriods = year,
        )

        assertEquals("the hero total is the window alone", Money.ofTaka(30_000), summary.total)
        assertEquals("February", 80_000_00L, summary.trend[1])
        assertEquals("June", 50_000_00L, summary.trend[5])
        assertEquals("August", 30_000_00L, summary.trend[7])
        assertEquals("and the breakdown is the window's, not the trend's", 1, summary.shares.size)
    }

    @Test
    fun `a source subset narrows the bars as well as the total`() {
        val summary = IncomeBreakdown.build(
            cells = listOf(cell(8, 1, "Salary", 30_000), cell(8, 2, "Farming", 10_000)),
            trendCells = listOf(cell(2, 2, "Farming", 80_000), cell(8, 1, "Salary", 30_000)),
            sourceIds = setOf(2L),
            trendPeriods = year,
        )
        assertEquals(Money.ofTaka(10_000), summary.total)
        assertEquals("Salary's August bar is filtered out too", 0L, summary.trend[7])
        assertEquals(80_000_00L, summary.trend[1])
    }

    // --- what the filter sheet can offer (D5) --------------------------------

    @Test
    fun `every source in the window is reported, filtered or not`() {
        // The filter sheet binds to these. Taken before the subset filter on
        // purpose: narrowing to one source must not delete the others from the
        // control that widens it again.
        val summary = IncomeBreakdown.build(
            cells = listOf(
                cell(8, 1, "Salary", 30_000),
                cell(6, 2, "Farming", 80_000),
                cell(9, 3, "Property", 15_000),
            ),
            trendCells = emptyList(),
            sourceIds = setOf(2L),
            trendPeriods = year,
        )
        assertEquals(listOf("Farming"), summary.shares.map { it.name })
        assertEquals(
            listOf("Farming", "Property", "Salary"),
            summary.presentSources.map { it.name },
        )
    }

    @Test
    fun `a source appearing in several months is reported once`() {
        val summary = build(cell(1, 1, "Salary", 100), cell(2, 1, "Salary", 100))
        assertEquals(listOf(1L), summary.presentSources.map { it.id })
    }
}
