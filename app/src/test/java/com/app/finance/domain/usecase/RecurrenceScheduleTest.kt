package com.app.finance.domain.usecase

import com.app.finance.domain.model.Frequency
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * When a rule falls due — FR-REC-01 and FR-REC-05.
 *
 * The clamp is the reason this is a calculation rather than a stored date, and
 * `the_anchor_survives_a_short_month` is the test that says so: getting the
 * clamp right and the *recovery* wrong is the failure mode, and it takes two
 * months to notice on a device.
 */
class RecurrenceScheduleTest {

    private fun date(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d)

    // --- monthly --------------------------------------------------------------

    @Test
    fun `a monthly rule lands on its anchor each month`() {
        val first = RecurrenceSchedule.next(Frequency.MONTHLY, anchor = 5, date(2026, 8, 5))
        assertEquals(date(2026, 9, 5), first)
        assertEquals(
            date(2026, 10, 5),
            RecurrenceSchedule.next(Frequency.MONTHLY, 5, first),
        )
    }

    @Test
    fun `an anchor past the end of the month clamps to its last day`() {
        // FR-REC-05, in as many words: "anchor days beyond the length of a short
        // month MUST clamp to that month's final day".
        assertEquals(
            date(2026, 2, 28),
            RecurrenceSchedule.next(Frequency.MONTHLY, anchor = 31, previous = date(2026, 1, 31)),
        )
        assertEquals(
            date(2026, 4, 30),
            RecurrenceSchedule.next(Frequency.MONTHLY, anchor = 31, previous = date(2026, 3, 31)),
        )
    }

    @Test
    fun `the anchor survives a short month`() {
        // The part the obvious implementation gets wrong. February clamps to the
        // 28th; March must go back to the 31st, not stay at the 28th and walk
        // the rule backwards a few days every short month for ever.
        val february = RecurrenceSchedule.next(Frequency.MONTHLY, 31, date(2026, 1, 31))
        assertEquals(date(2026, 2, 28), february)

        val march = RecurrenceSchedule.next(Frequency.MONTHLY, 31, february)
        assertEquals("March has 31 days and the anchor is still 31", date(2026, 3, 31), march)
    }

    @Test
    fun `a leap February takes the twenty-ninth`() {
        assertEquals(
            date(2028, 2, 29),
            RecurrenceSchedule.next(Frequency.MONTHLY, 31, date(2028, 1, 31)),
        )
    }

    @Test
    fun `the year rolls over`() {
        assertEquals(
            date(2027, 1, 15),
            RecurrenceSchedule.next(Frequency.MONTHLY, 15, date(2026, 12, 15)),
        )
    }

    // --- weekly ---------------------------------------------------------------

    @Test
    fun `a weekly rule ignores the anchor entirely`() {
        // "Every Friday" is not a day of the month, so a weekly rule recurs
        // every seventh day from wherever it started.
        assertEquals(
            date(2026, 8, 21),
            RecurrenceSchedule.next(Frequency.WEEKLY, anchor = 1, previous = date(2026, 8, 14)),
        )
        assertEquals(
            date(2026, 8, 21),
            RecurrenceSchedule.next(Frequency.WEEKLY, anchor = 31, previous = date(2026, 8, 14)),
        )
    }

    @Test
    fun `a weekly rule keeps its weekday across a month boundary`() {
        val start = date(2026, 8, 28)
        val next = RecurrenceSchedule.next(Frequency.WEEKLY, 1, start)
        assertEquals(date(2026, 9, 4), next)
        assertEquals(start.dayOfWeek, next.dayOfWeek)
    }

    // --- yearly ---------------------------------------------------------------

    @Test
    fun `a yearly rule keeps its month and day`() {
        assertEquals(
            date(2027, 8, 14),
            RecurrenceSchedule.next(Frequency.YEARLY, anchor = 14, previous = date(2026, 8, 14)),
        )
    }

    @Test
    fun `a yearly rule anchored on the twenty-ninth clamps in a common year`() {
        assertEquals(
            date(2025, 2, 28),
            RecurrenceSchedule.next(Frequency.YEARLY, anchor = 29, previous = date(2024, 2, 29)),
        )
    }

    // --- the first occurrence -------------------------------------------------

    @Test
    fun `a rule created before its anchor is due this month`() {
        assertEquals(
            date(2026, 8, 25),
            RecurrenceSchedule.firstDueOnOrAfter(Frequency.MONTHLY, 25, date(2026, 8, 14)),
        )
    }

    @Test
    fun `a rule created after its anchor waits for next month`() {
        // Creating a rule for rent on the 20th must not immediately generate
        // this month's rent, which the user has already paid and recorded.
        assertEquals(
            date(2026, 9, 5),
            RecurrenceSchedule.firstDueOnOrAfter(Frequency.MONTHLY, 5, date(2026, 8, 14)),
        )
    }

    @Test
    fun `a rule created on its anchor is due today`() {
        assertEquals(
            date(2026, 8, 14),
            RecurrenceSchedule.firstDueOnOrAfter(Frequency.MONTHLY, 14, date(2026, 8, 14)),
        )
    }

    @Test
    fun `a rule anchored on the thirty-first created in February is due that February`() {
        assertEquals(
            date(2026, 2, 28),
            RecurrenceSchedule.firstDueOnOrAfter(Frequency.MONTHLY, 31, date(2026, 2, 1)),
        )
    }

    // --- the property the catch-up loop depends on ----------------------------

    @Test
    fun `next always moves strictly forward`() {
        // `RecurringRepository.evaluate` loops on this. A frequency and anchor
        // combination that returned the same day would fill the ledger.
        val start = date(2026, 1, 31)
        Frequency.entries.forEach { frequency ->
            (1..31).forEach { anchor ->
                val next = RecurrenceSchedule.next(frequency, anchor, start)
                assert(next.isAfter(start)) {
                    "$frequency with anchor $anchor did not advance from $start"
                }
            }
        }
    }
}
