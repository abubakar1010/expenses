package com.app.finance.domain.model

import com.app.finance.core.time.Period
import java.time.LocalDate

/**
 * What stretch of time the income screen is showing — FR-IE-04.
 *
 * > "The system MUST compute and display: period total, year total, and total
 * > for an arbitrary user-selected date range."
 *
 * Three scopes, and **the year is the default**. 05-ui-ux-guide.md §5.7 is
 * emphatic that this is not an inconsistency to be tidied away:
 *
 * > "The income screen defaults to a yearly view while every other screen
 * > defaults to monthly. This is the single most important UX accommodation for
 * > this user's situation: a farming month showing ৳0 is alarming and
 * > meaningless in isolation. The year is the honest unit for this income; the
 * > month is the honest unit for spending."
 *
 * [Year] and [Month] carry a [Period] rather than a bare year, because the
 * period is owned above the `NavHost` and shared with Budget and Dashboard —
 * stepping the income year to 2025 puts the whole app on the same month of
 * 2025 rather than inventing a second notion of "when".
 *
 * Pure Kotlin over [Period] and `java.time`, which is JVM rather than Android,
 * so this stays inside the `domain/` purity rule NFR-MAIN-01 sets and the build
 * enforces.
 */
sealed interface IncomeScope {

    /** The twelve months of the calendar year containing [period]. The default. */
    @JvmInline
    value class Year(val period: Period) : IncomeScope

    /** One month, for the times the user does want to see a single one. */
    @JvmInline
    value class Month(val period: Period) : IncomeScope

    /**
     * An arbitrary span — FR-IE-04's third total and the reason [IncomeWindow]
     * has two shapes at all.
     */
    data class Range(val from: LocalDate, val to: LocalDate) : IncomeScope

    /**
     * Which query answers this scope.
     *
     * 03-database-design.md §5.3: "Ranges that do not align to month boundaries
     * cannot use rollups and fall back to the ledger." So [Year] and [Month]
     * resolve to whole periods and are served by `rollup_income_month`, while
     * [Range] resolves to epoch days and is served by `ix_income_entry_date`.
     * That split is the whole reason this type exists rather than a pair of
     * nullable date fields on the UI state.
     */
    val window: IncomeWindow
        get() = when (this) {
            is Year -> IncomeWindow.Periods(
                start = Period.of(period.year, 1),
                end = Period.of(period.year, 12),
            )
            is Month -> IncomeWindow.Periods(period, period)
            is Range -> IncomeWindow.Days(from.toEpochDay(), to.toEpochDay())
        }

    /**
     * The window the **trend** is read over, which is not the window the hero
     * total is read over unless the scope is [Year].
     *
     * FR-IE-07 asks for twelve months of income; [window] asks for whatever the
     * user is currently totalling. In [Month] scope those differ by eleven
     * months, and folding one read into the other's periods draws eleven
     * guaranteed-zero bars — a chart claiming the user earned nothing all year,
     * on the one screen built around the shape of the year being the
     * information. So the bars get their own read.
     *
     * Always whole periods, so the trend is always rollup-backed even when the
     * scope is a [Range] the ledger has to answer.
     */
    val trendWindow: IncomeWindow.Periods
        get() = trendPeriods().let { IncomeWindow.Periods(it.first(), it.last()) }

    /**
     * The twelve bars — FR-IE-07, "a 12-month income trend ending at the
     * currently selected period".
     *
     * For [Year] that is January through December, which is exactly the
     * `J F M A M J J A S O N D` axis 05 §5.7 draws. For [Month] it is the
     * trailing twelve ending at the selection, which is the requirement's
     * literal wording. For [Range] it ends at the period containing the last
     * day, because that is the only end the range defines.
     *
     * One rule, both readings — the mock and the requirement disagree only if
     * "the currently selected period" is assumed to be a month.
     */
    fun trendPeriods(): List<Period> = when (this) {
        is Year -> Period.of(period.year, 12).trailing(TREND_LENGTH)
        is Month -> period.trailing(TREND_LENGTH)
        is Range -> Period.from(to).trailing(TREND_LENGTH)
    }

    companion object {
        const val TREND_LENGTH = 12

        /** What the screen opens on, for whatever month the app is currently on. */
        fun default(period: Period): IncomeScope = Year(period)
    }
}

/** The resolved bounds of an [IncomeScope] — whole periods, or epoch days. */
sealed interface IncomeWindow {

    /** Rollup-backed: `period_ym BETWEEN start AND end`. */
    data class Periods(val start: Period, val end: Period) : IncomeWindow

    /** Ledger-backed: `earned_on BETWEEN from AND to`, both inclusive. */
    data class Days(val from: Long, val to: Long) : IncomeWindow
}
