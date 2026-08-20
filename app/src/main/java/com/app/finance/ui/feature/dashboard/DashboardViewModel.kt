package com.app.finance.ui.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.finance.core.money.Money
import com.app.finance.core.time.Period
import com.app.finance.data.db.dao.BudgetBarRow
import com.app.finance.data.db.dao.CategoryCellRow
import com.app.finance.data.db.dao.DayTotal
import com.app.finance.data.db.dao.ExpenseWithCategory
import com.app.finance.data.db.dao.IncomeCellRow
import com.app.finance.data.db.dao.PeriodTotal
import com.app.finance.data.repo.CategoryRepository
import com.app.finance.data.repo.DashboardRepository
import com.app.finance.domain.model.CategoryNode
import com.app.finance.domain.model.IncomeKind
import com.app.finance.domain.usecase.BudgetAlert
import com.app.finance.domain.usecase.BudgetAlerts
import com.app.finance.domain.usecase.BudgetGroup
import com.app.finance.domain.usecase.BudgetSummary
import com.app.finance.domain.usecase.BurnProjection
import com.app.finance.domain.usecase.BurnRate
import com.app.finance.domain.usecase.CategoryCell
import com.app.finance.domain.usecase.CategoryDelta
import com.app.finance.domain.usecase.CategoryDeltas
import com.app.finance.domain.usecase.ExpenseTrend
import com.app.finance.domain.usecase.NetPosition
import com.app.finance.domain.usecase.SafeToSpend
import com.app.finance.domain.usecase.SpendMix
import com.app.finance.domain.usecase.SpendSlice
import com.app.finance.domain.usecase.StableCoverage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate

/**
 * The month ribbon's data — 05 §5.5.
 *
 * A holder rather than two fields on the state, because [dailyTotals] is an
 * array: a data class holding one compares by identity and every assertion
 * about the state quietly stops working. The same lesson `IncomeSummary`
 * carries.
 */
data class RibbonData(
    val dailyTotals: LongArray,
    /** Zero-based index of today, or -1 when viewing another month. */
    val todayIndex: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RibbonData) return false
        return todayIndex == other.todayIndex && dailyTotals.contentEquals(other.dailyTotals)
    }

    override fun hashCode(): Int = 31 * dailyTotals.contentHashCode() + todayIndex
}

data class DashboardUiState(
    val period: Period,
    val today: LocalDate,
    val groups: List<BudgetGroup> = emptyList(),
    val alerts: List<BudgetAlert> = emptyList(),
    val safeToSpend: SafeToSpend? = null,
    val net: NetPosition = NetPosition(Money.ZERO, Money.ZERO),
    /** FR-AN-06. Null when there is no spending to cover — see [StableCoverage]. */
    val coverage: Int? = null,
    val ribbon: RibbonData = EMPTY_RIBBON,
    val projections: List<BurnProjection> = emptyList(),
    val deltas: List<CategoryDelta> = emptyList(),
    val mix: List<SpendSlice> = emptyList(),
    val largest: List<ExpenseWithCategory> = emptyList(),
    val trend: ExpenseTrend? = null,
    /** FR-AN-10 — over a trailing twelve periods, never a shorter window. */
    val averageIncome: Money = Money.ZERO,
    val initialLoad: Boolean = true,
) {
    /**
     * Nothing recorded and nothing planned.
     *
     * Deliberately not "nothing spent": a user who has set limits but not spent
     * yet has a perfectly good safe-to-spend figure, and that is the most
     * useful the screen ever is. Only when there is no spending, no income and
     * no limit anywhere is there genuinely nothing to render.
     */
    val isEmpty: Boolean
        get() = !initialLoad &&
            net.expenses.isZero &&
            net.income.isZero &&
            groups.none { !it.isUnbudgeted }

    companion object {
        val EMPTY_RIBBON = RibbonData(LongArray(0), -1)
    }
}

/**
 * The dashboard — FR-AN-01 … FR-AN-10.
 *
 * 05 §5.4 calls it "one screen answering the questions that change behaviour",
 * and every figure on it is derived, nothing cached: the bars come from the
 * rollup query, the groups and their totals from pure functions over that, and
 * safe-to-spend, the burn projections and the spend mix from those. So saving
 * an expense anywhere in the app moves the hero figure without a refresh, for
 * the same reason the ledger updates — Room's invalidation tracker is the whole
 * mechanism (04 §5.1).
 *
 * **Nine flows, every one of them bounded.** Eight read only rollups, `budget`
 * and `category`, so their row counts scale with the number of leaf categories
 * rather than with history; the ninth is `LIMIT 5` on an indexed period. That
 * is 03 §5.1's argument for NFR-PERF-04, and `DashboardScaleTest` asserts it
 * rather than trusting it.
 *
 * They are folded in three groups rather than one `combine`, because `combine`
 * is typed only to five — and grouping them by what they answer keeps each
 * fold readable.
 */
class DashboardViewModel(
    private val dashboard: DashboardRepository,
    private val categories: CategoryRepository,
    private val clock: Clock,
    initialPeriod: Period,
    @Suppress("unused") private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(
        DashboardUiState(period = initialPeriod, today = LocalDate.now(clock)),
    )
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    private var observeJob: Job? = null

    init {
        observe(initialPeriod)
    }

    /**
     * Re-points every flow at a new period.
     *
     * The old collection is cancelled rather than left running: all nine reads
     * are period-scoped, and a stale collector would race the new one to
     * publish a figure from the month the user just left.
     */
    fun setPeriod(period: Period) {
        if (period == _state.value.period) return
        _state.update { it.copy(period = period, initialLoad = true) }
        observe(period)
    }

    private fun observe(period: Period) {
        val today = LocalDate.now(clock)

        // What the period's budgets say — the groups, and everything derived
        // from them. The expense total comes from here too rather than from a
        // separate scalar, so the net line and the budget rows can never
        // disagree about how much was spent.
        val budgets = combine(
            dashboard.observeBars(period),
            categories.observeTree(),
        ) { bars, tree -> foldBudgets(bars, tree, period, today) }

        // What came in, what went out day by day, and the two "top" lists.
        val figures = combine(
            dashboard.observeIncomeCells(period),
            dashboard.observeDailySpend(period),
            dashboard.observeLargestExpenses(period),
            dashboard.observeTrailingIncome(period),
        ) { income, daily, largest, trailing ->
            Figures(
                income = Money(income.sumOf { it.totalMinor }),
                stableIncome = Money(
                    income.filter { it.kind == IncomeKind.STABLE.code }.sumOf { it.totalMinor },
                ),
                ribbon = foldRibbon(daily, period, today),
                largest = largest,
                averageIncome = Money(trailing / DashboardRepository.TRAILING_AVERAGE),
            )
        }

        // FR-AN-05 and FR-AN-09 — the two reads that look backwards.
        val history = combine(
            dashboard.observeCategoryCells(period),
            dashboard.observeExpenseSeries(period),
            dashboard.observeTotalLimits(period),
        ) { cells, series, limits -> foldHistory(cells, series, limits, period) }

        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            combine(budgets, figures, history) { b, f, h -> Triple(b, f, h) }
                .collect { (b, f, h) ->
                    val net = NetPosition(income = f.income, expenses = b.spent)
                    _state.update {
                        it.copy(
                            period = period,
                            today = today,
                            groups = b.groups,
                            alerts = b.alerts,
                            safeToSpend = b.safeToSpend,
                            projections = b.projections,
                            mix = b.mix,
                            net = net,
                            coverage = StableCoverage.percent(f.stableIncome, b.spent),
                            ribbon = f.ribbon,
                            largest = f.largest,
                            averageIncome = f.averageIncome,
                            deltas = h.deltas,
                            trend = h.trend,
                            initialLoad = false,
                        )
                    }
                }
        }
    }

    // --- the folds -----------------------------------------------------------

    private fun foldBudgets(
        bars: List<BudgetBarRow>,
        tree: List<CategoryNode>,
        period: Period,
        today: LocalDate,
    ): Budgets {
        val groups = BudgetSummary.build(
            bars = bars.map {
                BudgetSummary.LeafSpend(
                    id = it.id,
                    parentId = it.parentId,
                    name = it.name,
                    nature = it.nature,
                    limitMinor = it.limitMinor,
                    spentMinor = it.spentMinor,
                    isArchived = it.isArchived,
                )
            },
            tree = tree,
        )
        return Budgets(
            groups = groups,
            alerts = BudgetAlerts.from(groups, period.daysRemainingInclusive(today)),
            safeToSpend = SafeToSpend.of(groups, period.daysRemainingInclusive(today)),
            projections = BurnRate.over(
                groups = groups,
                daysElapsed = period.daysElapsedInclusive(today),
                daysInPeriod = period.daysInMonth(),
            ),
            mix = SpendMix.of(groups),
            // Every expense sits on a leaf and every leaf is a row here, so
            // this is the period's total spend — read once, with the figures
            // the user can see it broken into.
            spent = groups.fold(Money.ZERO) { acc, g -> acc + g.spent },
        )
    }

    private fun foldRibbon(daily: List<DayTotal>, period: Period, today: LocalDate): RibbonData {
        val first = period.firstDay().toEpochDay()
        val totals = LongArray(period.daysInMonth())
        daily.forEach { row ->
            val index = (row.day - first).toInt()
            if (index in totals.indices) totals[index] += row.total
        }
        return RibbonData(
            dailyTotals = totals,
            // -1 when the user is looking at another month, which is what
            // `MonthRibbon` reads as "draw no today marker".
            todayIndex = if (period.contains(today.toEpochDay())) today.dayOfMonth - 1 else -1,
        )
    }

    private fun foldHistory(
        cells: List<CategoryCellRow>,
        series: List<PeriodTotal>,
        limits: List<PeriodTotal>,
        period: Period,
    ) = History(
        deltas = CategoryDeltas.top(
            cells = cells.map {
                CategoryCell(
                    periodYm = it.periodYm,
                    categoryId = it.categoryId,
                    name = it.name,
                    totalMinor = it.totalMinor,
                )
            },
            current = period,
        ),
        trend = ExpenseTrend.of(
            periods = period.trailing(ExpenseTrend.LENGTH),
            spendByPeriod = series.associate { it.periodYm to it.total },
            limitsByPeriod = limits.associate { it.periodYm to it.total },
        ),
    )

    private data class Budgets(
        val groups: List<BudgetGroup>,
        val alerts: List<BudgetAlert>,
        val safeToSpend: SafeToSpend,
        val projections: List<BurnProjection>,
        val mix: List<SpendSlice>,
        val spent: Money,
    )

    private data class Figures(
        val income: Money,
        val stableIncome: Money,
        val ribbon: RibbonData,
        val largest: List<ExpenseWithCategory>,
        val averageIncome: Money,
    )

    private data class History(
        val deltas: List<CategoryDelta>,
        val trend: ExpenseTrend,
    )
}
