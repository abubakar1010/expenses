package com.app.finance.domain.usecase

import com.app.finance.core.money.Money
import com.app.finance.core.time.Period
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Category deltas — FR-AN-05, the one metric that reports a change. */
class CategoryDeltasTest {

    private val aug = Period.of(2026, 8)

    private fun cell(month: Int, id: Long, name: String, taka: Long, year: Int = 2026) =
        CategoryCell(
            periodYm = Period.of(year, month).ym,
            categoryId = id,
            name = name,
            totalMinor = taka * 100,
        )

    // --- the calculation ------------------------------------------------------

    @Test
    fun `the baseline is the mean of the three periods before`() {
        val deltas = CategoryDeltas.top(
            cells = listOf(
                cell(5, 1, "Grocery", 6_000),
                cell(6, 1, "Grocery", 7_000),
                cell(7, 1, "Grocery", 8_000),
                cell(8, 1, "Grocery", 10_000),
            ),
            current = aug,
        )
        assertEquals(Money.ofTaka(7_000), deltas.single().baseline)
        assertEquals(Money.ofTaka(3_000), deltas.single().increase)
    }

    @Test
    fun `the mean always divides by three, even with periods missing`() {
        // A category first bought last month would otherwise show a baseline
        // equal to that one month and read as flat. The honest comparison is
        // against a quarter in which it mostly did not exist.
        val deltas = CategoryDeltas.top(
            cells = listOf(
                cell(7, 1, "New Clothes", 3_000),
                cell(8, 1, "New Clothes", 4_000),
            ),
            current = aug,
        )
        assertEquals(Money.ofTaka(1_000), deltas.single().baseline)
        assertEquals(Money.ofTaka(3_000), deltas.single().increase)
    }

    @Test
    fun `a category new this month is measured against nothing`() {
        val deltas = CategoryDeltas.top(cells = listOf(cell(8, 1, "Repairs", 9_000)), current = aug)
        assertEquals(Money.ZERO, deltas.single().baseline)
        assertEquals(Money.ofTaka(9_000), deltas.single().increase)
    }

    @Test
    fun `periods outside the window are ignored`() {
        // April is four months back and must not dilute the mean.
        val deltas = CategoryDeltas.top(
            cells = listOf(
                cell(4, 1, "Grocery", 90_000),
                cell(5, 1, "Grocery", 6_000),
                cell(6, 1, "Grocery", 6_000),
                cell(7, 1, "Grocery", 6_000),
                cell(8, 1, "Grocery", 9_000),
            ),
            current = aug,
        )
        assertEquals(Money.ofTaka(6_000), deltas.single().baseline)
    }

    @Test
    fun `the window crosses a year boundary`() {
        val jan = Period.of(2026, 1)
        val deltas = CategoryDeltas.top(
            cells = listOf(
                cell(10, 1, "Gifts", 1_000, year = 2025),
                cell(11, 1, "Gifts", 1_000, year = 2025),
                cell(12, 1, "Gifts", 1_000, year = 2025),
                cell(1, 1, "Gifts", 5_000, year = 2026),
            ),
            current = jan,
        )
        assertEquals(Money.ofTaka(1_000), deltas.single().baseline)
        assertEquals(Money.ofTaka(4_000), deltas.single().increase)
    }

    // --- what is reported -----------------------------------------------------

    @Test
    fun `only increases appear`() {
        // FR-AN-05 says "sorted descending by absolute increase"; PRD §6.4
        // settles the ambiguity — "sorted by largest increase" — and a category
        // that fell is not something to act on.
        val deltas = CategoryDeltas.top(
            cells = listOf(
                cell(5, 1, "Transport", 9_000),
                cell(6, 1, "Transport", 9_000),
                cell(7, 1, "Transport", 9_000),
                cell(8, 1, "Transport", 2_000),
                cell(8, 2, "Grocery", 5_000),
            ),
            current = aug,
        )
        assertEquals(listOf("Grocery"), deltas.map { it.name })
    }

    @Test
    fun `an unchanged category is not a change`() {
        val deltas = CategoryDeltas.top(
            cells = (5..8).map { cell(it, 1, "Utilities", 3_000) },
            current = aug,
        )
        assertTrue(deltas.isEmpty())
    }

    @Test
    fun `largest increase first`() {
        val deltas = CategoryDeltas.top(
            cells = listOf(
                cell(8, 1, "Small", 1_000),
                cell(8, 2, "Large", 9_000),
                cell(8, 3, "Middle", 5_000),
            ),
            current = aug,
        )
        assertEquals(listOf("Large", "Middle", "Small"), deltas.map { it.name })
    }

    @Test
    fun `only five survive`() {
        val deltas = CategoryDeltas.top(
            cells = (1..9).map { cell(8, it.toLong(), "C$it", it * 1_000L) },
            current = aug,
        )
        assertEquals(CategoryDeltas.TOP_N, deltas.size)
        assertEquals(listOf("C9", "C8", "C7", "C6", "C5"), deltas.map { it.name })
    }

    @Test
    fun `equal increases order by name, so the list is stable`() {
        val deltas = CategoryDeltas.top(
            cells = listOf(
                cell(8, 1, "Zinc", 2_000),
                cell(8, 2, "Apple", 2_000),
            ),
            current = aug,
        )
        assertEquals(listOf("Apple", "Zinc"), deltas.map { it.name })
    }

    @Test
    fun `several buckets for one category in one period are summed`() {
        // The DAO groups by (period, category), so this should not happen — but
        // a fold that silently kept only the last row would be a quiet
        // under-report, which is the class of bug this whole test layer exists
        // to catch.
        val deltas = CategoryDeltas.top(
            cells = listOf(cell(8, 1, "Grocery", 3_000), cell(8, 1, "Grocery", 4_000)),
            current = aug,
        )
        assertEquals(Money.ofTaka(7_000), deltas.single().current)
    }

    @Test
    fun `nothing recorded anywhere is an empty list, not a crash`() {
        assertTrue(CategoryDeltas.top(cells = emptyList(), current = aug).isEmpty())
    }
}
