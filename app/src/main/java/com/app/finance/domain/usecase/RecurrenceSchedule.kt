package com.app.finance.domain.usecase

import com.app.finance.domain.model.Frequency
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.YearMonth

/**
 * When a recurring rule next falls due — FR-REC-01 and FR-REC-05.
 *
 * Pure `java.time`, no Android and no Room (NFR-MAIN-01), which is what makes
 * the awkward cases below unit tests rather than a month of waiting to find out.
 *
 * **FR-REC-05 is the whole reason this is a calculation rather than a stored
 * date**: "Anchor days beyond the length of a short month MUST clamp to that
 * month's final day."
 *
 * The clamp is easy; what the obvious implementation gets wrong is what happens
 * *next*. A rule anchored on the 31st falls due on 28 February — and then on
 * **31 March**, not the 28th. Storing the clamped date and advancing from it
 * would walk the rule backwards a few days every short month until a rent
 * reminder that used to arrive on the 31st arrives on the 28th for ever. So the
 * anchor is kept as the user set it and clamped only at the moment a date is
 * produced.
 */
object RecurrenceSchedule {

    const val MIN_ANCHOR = 1
    const val MAX_ANCHOR = 31

    /**
     * The first occurrence on or after [from].
     *
     * Used when a rule is created: a rule anchored on the 5th, created on the
     * 12th, is next due on the 5th of next month rather than immediately.
     */
    fun firstDueOnOrAfter(frequency: Frequency, anchor: Int, from: LocalDate): LocalDate {
        val candidate = occurrence(frequency, anchor, from)
        return if (!candidate.isBefore(from)) candidate else next(frequency, anchor, from)
    }

    /**
     * The occurrence strictly after [previous].
     *
     * Strictly, so [com.app.finance.data.repo.RecurringRepository]'s catch-up
     * loop cannot stand still: a step that returned the same day would spin on
     * a rule whose anchor happens to fall on the date being evaluated.
     */
    /**
     * The first occurrence of a rule the user is about to create, on or after
     * [today] — what the editor shows and what `createRule` starts from.
     *
     * [firstDueOnOrAfter] takes the month and the weekday from the date it is
     * handed, and the editor used to hand it today: a yearly rule could only
     * fall in the month it was made in, and a weekly one only on the weekday it
     * was made on (06 §36.4). These are the two choices that were missing.
     *
     * @param month 1..12, read by yearly rules only.
     * @param weekday read by weekly rules only.
     */
    fun firstDue(
        frequency: Frequency,
        anchor: Int,
        month: Int,
        weekday: DayOfWeek,
        today: LocalDate,
    ): LocalDate = when (frequency) {
        Frequency.MONTHLY -> firstDueOnOrAfter(frequency, anchor, today)
        Frequency.WEEKLY -> today.with(TemporalAdjusters.nextOrSame(weekday))
        Frequency.YEARLY -> {
            val thisYear = occurrence(frequency, anchor, LocalDate.of(today.year, month, 1))
            if (!thisYear.isBefore(today)) thisYear
            else occurrence(frequency, anchor, LocalDate.of(today.year + 1, month, 1))
        }
    }

    fun next(frequency: Frequency, anchor: Int, previous: LocalDate): LocalDate = when (frequency) {
        // Weekly ignores the anchor day-of-month entirely — a weekly rule
        // recurs every seventh day from wherever it started, and forcing it
        // onto a day-of-month would make "every Friday" impossible to express.
        Frequency.WEEKLY -> previous.plusDays(WEEK)
        Frequency.MONTHLY -> occurrence(frequency, anchor, previous.plusMonths(1).withDayOfMonth(1))
        Frequency.YEARLY -> occurrence(frequency, anchor, previous.plusYears(1).withDayOfMonth(1))
    }

    /**
     * The anchor rendered into [inMonth]'s month, clamped to its length.
     *
     * 31 in February is the 28th, or the 29th in a leap year; 31 in April is
     * the 30th; 31 in May is the 31st again, which is the part that matters.
     */
    fun occurrence(frequency: Frequency, anchor: Int, inMonth: LocalDate): LocalDate = when (frequency) {
        Frequency.WEEKLY -> inMonth
        else -> {
            val month = YearMonth.of(inMonth.year, inMonth.monthValue)
            month.atDay(anchor.coerceIn(MIN_ANCHOR, month.lengthOfMonth()))
        }
    }

    private const val WEEK = 7L
}
