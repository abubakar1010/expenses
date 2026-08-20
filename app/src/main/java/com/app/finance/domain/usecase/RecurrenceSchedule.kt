package com.app.finance.domain.usecase

import com.app.finance.domain.model.Frequency
import java.time.LocalDate
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
