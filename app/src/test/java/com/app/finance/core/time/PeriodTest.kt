package com.app.finance.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 04-system-architecture.md §9 names the edge cases this suite must cover:
 * December→January rollover, leap-year February, anchor day 31 in a 30-day
 * month, and the last day of a month for safe-to-spend division.
 */
class PeriodTest {

    @Test
    fun `december rolls over into january of the next year`() {
        assertEquals(Period(202701), Period(202612).next())
    }

    @Test
    fun `january rolls back into december of the previous year`() {
        assertEquals(Period(202512), Period(202601).prev())
    }

    @Test
    fun `plusMonths crosses several year boundaries`() {
        assertEquals(Period(202708), Period(202608).plusMonths(12))
        assertEquals(Period(202502), Period(202608).minusMonths(18))
    }

    @Test
    fun `leap year february has twenty nine days`() {
        assertEquals(29, Period(202402).daysInMonth())
        assertEquals(28, Period(202602).daysInMonth())
        // 2100 is divisible by 100 but not 400, so it is not a leap year.
        assertEquals(28, Period(210002).daysInMonth())
    }

    @Test
    fun `thirty day months report thirty days`() {
        // The recurring-rule anchor day of 31 has to clamp against this.
        assertEquals(30, Period(202609).daysInMonth())
        assertEquals(31, Period(202608).daysInMonth())
    }

    @Test
    fun `day range covers the whole month inclusively`() {
        val range = Period(202608).dayRange()
        assertEquals(LocalDate.of(2026, 8, 1).toEpochDay(), range.first)
        assertEquals(LocalDate.of(2026, 8, 31).toEpochDay(), range.last)
        assertEquals(31, range.last - range.first + 1)
    }

    @Test
    fun `contains discriminates the boundaries`() {
        val august = Period(202608)
        assertTrue(august.contains(LocalDate.of(2026, 8, 31).toEpochDay()))
        assertFalse(august.contains(LocalDate.of(2026, 9, 1).toEpochDay()))
        assertFalse(august.contains(LocalDate.of(2026, 7, 31).toEpochDay()))
    }

    // --- safe-to-spend divisor (01-PRD.md §6.4) -----------------------------

    @Test
    fun `last day of the month leaves one day remaining not zero`() {
        // The divisor is inclusive of today; a zero here would divide by zero
        // on the 31st, which is exactly when the figure matters most.
        assertEquals(1, Period(202608).daysRemainingInclusive(LocalDate.of(2026, 8, 31)))
    }

    @Test
    fun `first day of the month leaves the whole month remaining`() {
        assertEquals(31, Period(202608).daysRemainingInclusive(LocalDate.of(2026, 8, 1)))
    }

    @Test
    fun `a future period is treated as entirely remaining`() {
        assertEquals(30, Period(202609).daysRemainingInclusive(LocalDate.of(2026, 8, 15)))
    }

    @Test
    fun `a past period has nothing remaining`() {
        assertEquals(0, Period(202607).daysRemainingInclusive(LocalDate.of(2026, 8, 15)))
    }

    @Test
    fun `days elapsed counts today and complements days remaining`() {
        val august = Period(202608)
        val today = LocalDate.of(2026, 8, 15)
        assertEquals(15, august.daysElapsedInclusive(today))
        // Both are inclusive of today, so they overlap by exactly one day.
        assertEquals(
            august.daysInMonth() + 1,
            august.daysElapsedInclusive(today) + august.daysRemainingInclusive(today),
        )
    }

    // --- construction and trailing series -----------------------------------

    @Test
    fun `trailing returns n periods oldest first ending at this one`() {
        assertEquals(
            listOf(Period(202606), Period(202607), Period(202608)),
            Period(202608).trailing(3),
        )
    }

    @Test
    fun `trailing twelve crosses the year boundary`() {
        // FR-AN-10 requires the average to span a trailing twelve periods.
        val series = Period(202603).trailing(12)
        assertEquals(12, series.size)
        assertEquals(Period(202504), series.first())
        assertEquals(Period(202603), series.last())
    }

    @Test
    fun `from a date and from an epoch day agree`() {
        val date = LocalDate.of(2026, 8, 13)
        assertEquals(Period(202608), Period.from(date))
        assertEquals(Period(202608), Period.fromEpochDay(date.toEpochDay()))
    }

    @Test
    fun `now reads the injected clock`() {
        val clock = Clock.fixed(Instant.parse("2026-08-13T10:15:00Z"), ZoneOffset.UTC)
        assertEquals(Period(202608), Period.now(clock))
    }

    @Test
    fun `invalid packed values are rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) { Period(202613) }
        assertThrows(IllegalArgumentException::class.java) { Period(202600) }
    }

    @Test
    fun `periods order chronologically`() {
        assertTrue(Period(202512) < Period(202601))
    }
}
