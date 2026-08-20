package com.app.finance.core.time

import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A calendar month, encoded as the integer `YYYYMM` — August 2026 is `202608`.
 *
 * 03-database-design.md §1 calls the denormalised `period_ym` column "the single
 * highest-leverage decision for dashboard latency": it turns every monthly
 * filter from a `strftime()` scan into an indexed equality test. This type is
 * that column's in-memory counterpart, and compiles to a bare `Int`.
 *
 * 04-system-architecture.md §4.2: period arithmetic is the second most common
 * source of bugs after money — particularly December→January rollover and the
 * 31st in a 30-day month. Centralising it here, rather than scattering
 * `Calendar` calls, removes the whole bug class.
 *
 * `java.time` is used directly with no desugaring: minSdk is 26, which is
 * exactly where it became part of the platform.
 */
@JvmInline
value class Period(val ym: Int) : Comparable<Period> {

    init {
        require(ym / 100 in 1..9999 && ym % 100 in 1..12) {
            "not a valid YYYYMM period: $ym"
        }
    }

    val year: Int get() = ym / 100
    val month: Int get() = ym % 100

    override fun compareTo(other: Period): Int = ym.compareTo(other.ym)

    fun prev(): Period = minusMonths(1)

    fun next(): Period = plusMonths(1)

    /**
     * Rollover is delegated to [YearMonth] rather than done with arithmetic on
     * the packed integer, because `202612 + 1` is `202613`, not `202701`.
     */
    fun plusMonths(n: Int): Period = of(toYearMonth().plusMonths(n.toLong()))

    fun minusMonths(n: Int): Period = plusMonths(-n)

    fun daysInMonth(): Int = toYearMonth().lengthOfMonth()

    fun firstDay(): LocalDate = toYearMonth().atDay(1)

    fun lastDay(): LocalDate = toYearMonth().atEndOfMonth()

    /** Inclusive epoch-day bounds, matching the `spent_on` / `earned_on` columns. */
    fun dayRange(): LongRange = firstDay().toEpochDay()..lastDay().toEpochDay()

    fun toYearMonth(): YearMonth = YearMonth.of(year, month)

    /** The `n` periods ending at this one, oldest first. */
    fun trailing(n: Int): List<Period> {
        require(n > 0) { "trailing count must be positive" }
        return (n - 1 downTo 0).map { minusMonths(it) }
    }

    /**
     * Days remaining in the period as of [today], counting today itself.
     *
     * The inclusive count is required by the safe-to-spend formula
     * (01-PRD.md §6.4): on the last day of the month the divisor is 1, not 0.
     * Returns the full month length when [today] falls before this period and
     * zero when it falls after, so the caller never has to special-case a
     * historical month.
     */
    fun daysRemainingInclusive(today: LocalDate): Int = when {
        today < firstDay() -> daysInMonth()
        today > lastDay() -> 0
        else -> lastDay().dayOfMonth - today.dayOfMonth + 1
    }

    /** Days already elapsed, counting today — the burn-rate denominator. */
    fun daysElapsedInclusive(today: LocalDate): Int = when {
        today < firstDay() -> 0
        today > lastDay() -> daysInMonth()
        else -> today.dayOfMonth
    }

    fun contains(epochDay: Long): Boolean = epochDay in dayRange()

    /**
     * `August 2026` — what the period switcher shows.
     *
     * Takes a [Locale] rather than reading the default, for the same reason
     * `Money.format` does: the month name is user-facing text, and a composable
     * that reads the locale non-observably keeps rendering the old language
     * after a change. Pure `java.time`, so this stays inside `core/`.
     */
    fun label(locale: Locale = Locale.getDefault()): String =
        toYearMonth().format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))

    override fun toString(): String = ym.toString()

    companion object {
        fun of(year: Int, month: Int): Period = Period(year * 100 + month)

        fun of(yearMonth: YearMonth): Period = of(yearMonth.year, yearMonth.monthValue)

        fun from(date: LocalDate): Period = of(date.year, date.monthValue)

        fun fromEpochDay(epochDay: Long): Period = from(LocalDate.ofEpochDay(epochDay))

        /**
         * [Clock] is passed rather than read from the system, so tests can pin
         * "today" — every date-sensitive metric in the app depends on it.
         */
        fun now(clock: Clock): Period = from(LocalDate.now(clock))
    }
}
