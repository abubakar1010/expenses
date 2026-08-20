package com.app.finance.ui.feature.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.finance.core.money.Money
import com.app.finance.data.db.dao.ExpenseWithCategory
import com.app.finance.data.repo.ReportsRepository
import com.app.finance.domain.usecase.SpendMix
import com.app.finance.domain.usecase.SpendSlice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.LocalDate

/** The ranges worth one tap. Anything else is [RangePreset.CUSTOM]. */
enum class RangePreset { THIS_MONTH, LAST_MONTH, LAST_THREE_MONTHS, THIS_YEAR, CUSTOM }

data class DateRange(val from: LocalDate, val to: LocalDate, val preset: RangePreset)

data class ReportsUiState(
    val range: DateRange,
    val total: Money = Money.ZERO,
    val count: Int = 0,
    val mix: List<SpendSlice> = emptyList(),
    val largest: List<ExpenseWithCategory> = emptyList(),
    val loading: Boolean = true,
) {
    /** Nothing in the range, as opposed to nothing yet loaded. */
    val isEmpty: Boolean get() = !loading && count == 0
}

/**
 * The Reports screen — 04 §7.
 *
 * The one screen in the app with **no rollups behind it**. Everything here is a
 * bounded scan of `ix_expense_date`, which 03 §5.3 permits precisely because
 * this screen only reads when the user asks it to:
 *
 * > "they are invoked from the reports screen on explicit user action, not on
 * > every dashboard render"
 *
 * So the range is the state, and the three reads hang off it: change the range
 * and `flatMapLatest` drops the queries in flight rather than racing them. A
 * user dragging through presets would otherwise see figures from a range they
 * have already left.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModel(
    private val reports: ReportsRepository,
    private val clock: Clock,
) : ViewModel() {

    private val today: LocalDate get() = LocalDate.now(clock)

    private val range = MutableStateFlow(rangeFor(RangePreset.THIS_MONTH, today))

    val state: StateFlow<ReportsUiState> = range
        .flatMapLatest { r ->
            combine(
                reports.observeTotal(r.from, r.to),
                reports.observeMix(r.from, r.to),
                reports.observeLargest(r.from, r.to),
                reports.observeCount(r.from, r.to),
            ) { total, mix, largest, count ->
                ReportsUiState(
                    range = r,
                    total = total,
                    count = count,
                    mix = SpendMix.ofTotals(mix),
                    largest = largest,
                    loading = false,
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ReportsUiState(range = range.value),
        )

    fun setPreset(preset: RangePreset) {
        if (preset == RangePreset.CUSTOM) return
        range.value = rangeFor(preset, today)
    }

    /**
     * A custom endpoint. Setting one past the other swaps them rather than
     * refusing: a user who picks the end first has not made a mistake, and an
     * empty report with an error under it would be the app being pedantic
     * about the order two dates were tapped in.
     */
    fun setFrom(day: LocalDate) = setCustom(day, range.value.to)

    fun setTo(day: LocalDate) = setCustom(range.value.from, day)

    private fun setCustom(from: LocalDate, to: LocalDate) {
        val (lo, hi) = if (from <= to) from to to else to to from
        range.value = DateRange(lo, hi, RangePreset.CUSTOM)
    }

    private companion object {
        fun rangeFor(preset: RangePreset, today: LocalDate): DateRange = when (preset) {
            RangePreset.THIS_MONTH ->
                DateRange(today.withDayOfMonth(1), today, preset)

            RangePreset.LAST_MONTH -> {
                val first = today.minusMonths(1).withDayOfMonth(1)
                DateRange(first, first.plusMonths(1).minusDays(1), preset)
            }

            // Two whole months plus this one so far, which is what "last three
            // months" means to someone looking at a spending report — not
            // ninety days back from an arbitrary Tuesday.
            RangePreset.LAST_THREE_MONTHS ->
                DateRange(today.minusMonths(2).withDayOfMonth(1), today, preset)

            RangePreset.THIS_YEAR ->
                DateRange(today.withDayOfYear(1), today, preset)

            RangePreset.CUSTOM -> DateRange(today.withDayOfMonth(1), today, preset)
        }
    }
}
