package com.app.finance.ui.feature.dashboard

import androidx.compose.foundation.background
import com.app.finance.ui.theme.Sizes
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.finance.R
import com.app.finance.core.money.Money
import com.app.finance.core.time.Period
import com.app.finance.di.AppContainer
import com.app.finance.di.viewModelFactory
import com.app.finance.domain.model.Nature
import com.app.finance.domain.usecase.BudgetGroup
import com.app.finance.domain.usecase.BurnProjection
import com.app.finance.domain.usecase.CategoryDelta
import com.app.finance.domain.usecase.SpendSlice
import com.app.finance.ui.common.AlertRow
import com.app.finance.ui.common.EmptyState
import com.app.finance.ui.common.LedgerRow
import com.app.finance.ui.common.MoneyText
import com.app.finance.ui.common.MonthRibbon
import com.app.finance.ui.common.PeriodSwitcher
import com.app.finance.ui.common.SectionHeader
import com.app.finance.ui.common.TrendLine
import com.app.finance.ui.common.monthInitials
import com.app.finance.ui.common.rememberJavaLocale
import com.app.finance.ui.theme.KhataTheme
import com.app.finance.ui.theme.Radius
import com.app.finance.ui.theme.Space
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The dashboard — FR-AN-01 … FR-AN-10.
 *
 * 05 §5.4 is the layout, and its three notes are the reasoning:
 *
 * > **The hero is a decision, not a balance.** "Most finance apps put total
 * > balance or total spent at the top. Neither answers a question the user has
 * > at a shop counter. Safe-to-spend does."
 * >
 * > **"Needs attention" only appears when something needs attention.** "An
 * > empty state here would train the user to ignore the region. Sections that
 * > have nothing to say are absent, not empty."
 * >
 * > **Fixed expenses sit below variable ones**, despite being larger, "because
 * > rent is not a decision. Ordering by actionability rather than by amount is
 * > the whole point of separating the categories in the first place."
 *
 * The third is already `BudgetSummary`'s ordering. The second governs six
 * separate sections here, each of which disappears rather than rendering a
 * zero — which is also why this screen has no fixed height and why nothing on
 * it is a card.
 *
 * The mock's ⚙ is not drawn. Settings is M5's, because it is the screen export
 * and import live on, and a gear that opens nothing tells the user the app is
 * unfinished every time they look at it.
 */
@Composable
fun DashboardScreen(
    container: AppContainer,
    period: Period,
    onPeriodChange: (Period) -> Unit,
    onOpenBudget: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: DashboardViewModel = viewModel(
        factory = viewModelFactory {
            DashboardViewModel(
                dashboard = container.dashboardRepo,
                categories = container.categoryRepo,
                clock = container.clock,
                initialPeriod = period,
            )
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val locale = rememberJavaLocale()

    LaunchedEffect(period) { vm.setPeriod(period) }

    // NFR-PERF-04 is "dashboard **fully rendered** ≤ 300 ms", and the screen
    // deliberately draws a skeleton first (05 §8) — so time-to-first-frame
    // measures the skeleton and flatters the number. This is the platform's own
    // way of saying when the content is actually there: it turns the criterion
    // into `timeToFullDisplayMs`, which is what §20.6 reports.
    ReportDrawnWhen { !state.initialLoad }

    Column(modifier.fillMaxSize()) {
        PeriodSwitcher(
            period = state.period,
            onChange = onPeriodChange,
            // 05 §5.4's ⚙. Drawn at last, now that there is a screen behind it.
            trailing = {
                Text(
                    text = stringResource(R.string.open_settings),
                    style = KhataTheme.type.caption,
                    color = KhataTheme.colors.indigo,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .defaultMinSize(minHeight = Sizes.minTouchTarget)
                        .clickable(onClick = onOpenSettings)
                        .semantics { role = Role.Button }
                        .padding(horizontal = Space.s2, vertical = Space.s2),
                )
            },
        )

        when {
            state.initialLoad -> DashboardSkeleton()

            state.isEmpty -> EmptyState(
                message = stringResource(R.string.empty_dashboard),
                modifier = Modifier.fillMaxSize(),
            )

            else -> LazyColumn(Modifier.fillMaxSize()) {
                item(key = "hero") { SafeToSpendHero(state, locale) }
                item(key = "ribbon") { Ribbon(state, locale) }
                item(key = "net") { NetStrip(state, locale) }

                // FR-BUD-05's alerts, rendered by the component the budget
                // screen uses, so the two surfaces cannot say it differently.
                if (state.alerts.isNotEmpty()) {
                    item(key = "attention-header") {
                        SectionHeader(stringResource(R.string.needs_attention))
                    }
                    items(state.alerts, key = { "alert-${it.categoryId}" }) { alert ->
                        AlertRow(alert, onClick = onOpenBudget)
                    }
                }

                // 05 §5.4's "absent, not empty", applied to rows as well as
                // sections: a leaf with neither spend nor a limit has nothing to
                // say, and thirteen of them on a fresh install would push
                // FR-AN-04 through FR-AN-09 below the fold. The budget screen
                // keeps every leaf, because that is where limits get set and
                // each row there carries "Set one".
                state.groups.forEach { group ->
                    val leaves = group.leaves.filter { it.hasLimit || !it.status.spent.isZero }
                    if (leaves.isEmpty()) return@forEach

                    item(key = "group-${group.id}") { GroupHeader(group, locale) }
                    items(leaves, key = { "leaf-${it.id}" }) { leaf ->
                        LedgerRow(
                            label = leaf.name,
                            amount = leaf.status.spent,
                            secondary = if (leaf.hasLimit) {
                                stringResource(
                                    R.string.percent_of_limit,
                                    leaf.status.percentConsumed,
                                    leaf.status.limit.format(locale),
                                )
                            } else {
                                stringResource(R.string.no_limit_set)
                            },
                            onClick = onOpenBudget,
                        )
                    }
                }

                // FR-AN-04. Leaves already over are filtered out: they are two
                // sections above in "needs attention", and a dashboard that
                // says the same thing twice is one the user starts skimming.
                val projected = state.projections.filterNot { it.isAlreadyOver }
                if (projected.isNotEmpty()) {
                    item(key = "pace-header") {
                        SectionHeader(stringResource(R.string.on_pace_header))
                    }
                    items(projected, key = { "pace-${it.categoryId}" }) { row ->
                        ProjectionRow(row, locale, onOpenBudget)
                    }
                }

                if (state.deltas.isNotEmpty()) {
                    item(key = "deltas-header") {
                        SectionHeader(stringResource(R.string.biggest_changes))
                    }
                    items(state.deltas, key = { "delta-${it.categoryId}" }) { delta ->
                        DeltaRow(delta, locale)
                    }
                }

                if (state.mix.isNotEmpty()) {
                    item(key = "mix-header") {
                        SectionHeader(stringResource(R.string.where_it_goes))
                    }
                    items(state.mix, key = { "mix-${it.nature.code}" }) { slice ->
                        MixRow(slice, locale)
                    }
                }

                if (state.largest.isNotEmpty()) {
                    item(key = "largest-header") {
                        SectionHeader(stringResource(R.string.largest_expenses))
                    }
                    items(state.largest, key = { "largest-${it.expense.id}" }) { row ->
                        LedgerRow(
                            label = row.categoryName,
                            amount = Money(row.expense.amountMinor),
                            secondary = remember(row.expense.spentOn, locale) {
                                LocalDate.ofEpochDay(row.expense.spentOn).format(dayFormat(locale))
                            },
                            trailing = row.expense.note,
                        )
                    }
                }

                state.trend?.takeUnless { it.isEmpty }?.let { trend ->
                    item(key = "trend-header") {
                        SectionHeader(stringResource(R.string.six_month_trend))
                    }
                    item(key = "trend") {
                        Box(Modifier.padding(horizontal = Space.gutter, vertical = Space.s2)) {
                            TrendLine(
                                spend = trend.spend,
                                reference = trend.reference,
                                labels = remember(trend.periods, locale) {
                                    monthInitials(locale, trend.periods.map { it.month })
                                },
                                locale = locale,
                            )
                        }
                    }
                }

                // FR-AN-06, on the screen the requirement actually names. The
                // income screen shows it first only because it shipped first.
                state.coverage?.let { percent ->
                    item(key = "coverage") {
                        Caption(stringResource(R.string.stable_coverage_month, percent))
                    }
                }

                // FR-AN-10. The window is in the copy because the window *is*
                // the requirement — a shorter one is "wrong in both directions"
                // for income that arrives in bursts.
                if (state.averageIncome.paisa > 0L) {
                    item(key = "average") {
                        Caption(
                            stringResource(
                                R.string.monthly_average_income,
                                state.averageIncome.format(locale),
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * `SAFE TO SPEND TODAY` / `৳1,240` — FR-AN-01.
 *
 * Unabbreviated (05 §4.3 — "in a ledger, precision is the product") and
 * announced as words (§10), because a raw currency string read character by
 * character is the most common accessibility failure in finance apps.
 */
@Composable
private fun SafeToSpendHero(state: DashboardUiState, locale: Locale) {
    val safe = state.safeToSpend ?: return
    // A finished period has no "today" left to spend in, so a per-day figure
    // there would divide by a day that does not exist. What a past month has to
    // report is what was left of it.
    val figure = safe.perDay ?: maxOf(safe.remaining, Money.ZERO)
    val caption = stringResource(
        if (safe.perDay != null) R.string.safe_to_spend_today else R.string.left_this_period,
    )
    val spoken = "$caption, ${figure.spokenForm(locale)}"

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter)
            .padding(top = Space.s5, bottom = Space.s2)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
    ) {
        Text(caption, style = KhataTheme.type.sectionHeader, color = KhataTheme.colors.inkSoft)
        MoneyText(
            money = figure,
            style = KhataTheme.type.heroFigure,
            // The column's sentence already carries the figure as words; left
            // alone this would merge a second description and TalkBack would
            // read the amount twice.
            modifier = Modifier.clearAndSetSemantics {},
        )
        // FR-AN-01: "when the numerator is negative the value MUST render as
        // zero with an over-budget indicator". The zero above is half of that;
        // this line is the other half, and without it a zero reads as "nothing
        // left" rather than "past the limit, by this much".
        if (safe.isOver) {
            Text(
                text = stringResource(
                    R.string.safe_to_spend_over,
                    safe.remaining.absoluteValue.format(locale),
                ),
                style = KhataTheme.type.caption,
                color = KhataTheme.colors.vermilion,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** 05 §5.5 — "the one element the app should be remembered by". */
@Composable
private fun Ribbon(state: DashboardUiState, locale: Locale) {
    Column(Modifier.padding(horizontal = Space.gutter, vertical = Space.s2)) {
        MonthRibbon(
            dailyTotals = state.ribbon.dailyTotals,
            todayIndex = state.ribbon.todayIndex,
            locale = locale,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = Space.s1)
                // The axis is a legend for the ribbon above, which already
                // carries the whole description.
                .clearAndSetSemantics {},
        ) {
            Text(
                text = stringResource(R.string.ribbon_first_day),
                style = KhataTheme.type.caption,
                color = KhataTheme.colors.inkSoft,
            )
            Box(Modifier.weight(1f))
            if (state.ribbon.todayIndex >= 0) {
                Text(
                    text = stringResource(R.string.ribbon_today),
                    style = KhataTheme.type.caption,
                    color = KhataTheme.colors.vermilion,
                )
            }
            Box(Modifier.weight(1f))
            Text(
                text = state.ribbon.dailyTotals.size.toString(),
                style = KhataTheme.type.caption,
                color = KhataTheme.colors.inkSoft,
            )
        }
    }
}

/** `Earned ৳48,000  Spent ৳31,600` / `Net +৳16,400 · saving 34%` — FR-AN-02, -03. */
@Composable
private fun NetStrip(state: DashboardUiState, locale: Locale) {
    val colors = KhataTheme.colors
    val net = state.net
    val netFigure = if (net.net.isNegative) {
        net.net.format(locale)
    } else {
        stringResource(R.string.positive_prefix, net.net.format(locale))
    }
    val netLine = net.savingsRate
        ?.let { stringResource(R.string.net_with_saving, netFigure, it) }
        ?: stringResource(R.string.net_alone, netFigure)
    val spokenNet = net.net.absoluteValue.spokenForm(locale).let {
        if (net.net.isNegative) "minus $it" else it
    }
    val spokenLine = net.savingsRate
        ?.let { stringResource(R.string.net_with_saving, spokenNet, it) }
        ?: stringResource(R.string.net_alone, spokenNet)
    val spokenEarned = stringResource(R.string.earned_label, net.income.spokenForm(locale))
    val spokenSpent = stringResource(R.string.spent_label, net.expenses.spokenForm(locale))

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter, vertical = Space.s2),
        verticalArrangement = Arrangement.spacedBy(Space.s1),
    ) {
        Row(
            Modifier.semantics(mergeDescendants = true) {
                contentDescription = "$spokenEarned, $spokenSpent"
            },
            horizontalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            Text(
                text = stringResource(R.string.earned_label, net.income.format(locale)),
                style = KhataTheme.type.body,
                color = colors.moss,
            )
            Text(
                text = stringResource(R.string.spent_label, net.expenses.format(locale)),
                style = KhataTheme.type.body,
                color = colors.ink,
            )
        }
        Text(
            text = netLine,
            style = KhataTheme.type.body,
            fontWeight = FontWeight.SemiBold,
            // Colour is not the only signal: the sign is in the figure and the
            // word "Net" is in the sentence either way (NFR-USE-05).
            color = if (net.net.isNegative) colors.vermilion else colors.ink,
            modifier = Modifier.semantics { contentDescription = spokenLine },
        )
    }
}

/** `VARIABLE EXPENSES    ৳12,400 of ৳18,000` — the root total, never stored. */
@Composable
private fun GroupHeader(group: BudgetGroup, locale: Locale) {
    SectionHeader(
        text = group.name,
        trailing = if (group.isUnbudgeted) {
            null
        } else {
            {
                Text(
                    // Unabbreviated. §5.4's mock writes "18k", which §4.3
                    // forbids: "never abbreviate to 1.2k".
                    text = stringResource(
                        R.string.group_total,
                        group.spent.format(locale),
                        group.limit.format(locale),
                    ),
                    style = KhataTheme.type.caption,
                    color = KhataTheme.colors.inkSoft,
                )
            }
        },
    )
}

/**
 * FR-AN-04 — the warning that arrives on day 12 rather than day 30.
 *
 * The figure on the row is the *projection*, not the spend, because the
 * projection is the claim being made; the limit rides along in the caption so
 * the row can be judged without leaving it.
 */
@Composable
private fun ProjectionRow(row: BurnProjection, locale: Locale, onClick: () -> Unit) {
    val spoken = stringResource(
        R.string.projected_spoken,
        row.name,
        row.projected.spokenForm(locale),
        row.limit.spokenForm(locale),
    )
    Box(Modifier.semantics(mergeDescendants = true) { contentDescription = spoken }) {
        LedgerRow(
            label = row.name,
            amount = row.projected,
            secondary = stringResource(
                R.string.projected_by_month_end,
                row.limit.format(locale),
            ),
            onClick = onClick,
        )
    }
}

/** FR-AN-05 — the change, which PRD §6.4 calls "the only actionable part". */
@Composable
private fun DeltaRow(delta: CategoryDelta, locale: Locale) {
    val spoken = stringResource(
        R.string.delta_spoken,
        delta.name,
        delta.current.spokenForm(locale),
        delta.increase.spokenForm(locale),
    )
    Box(Modifier.semantics(mergeDescendants = true) { contentDescription = spoken }) {
        LedgerRow(
            label = delta.name,
            amount = delta.current,
            secondary = stringResource(
                R.string.delta_versus_usual,
                delta.increase.format(locale),
            ),
        )
    }
}

/** FR-AN-07 — "shows how much is actually controllable" (PRD §6.4). */
@Composable
private fun MixRow(slice: SpendSlice, locale: Locale) {
    LedgerRow(
        label = stringResource(
            when (slice.nature) {
                Nature.FIXED -> R.string.nature_fixed
                Nature.VARIABLE -> R.string.nature_variable
                Nature.UNPREDICTABLE -> R.string.nature_unpredictable
            },
        ),
        amount = slice.total,
        secondary = stringResource(R.string.percent_share, slice.share),
    )
}

@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        style = KhataTheme.type.body,
        color = KhataTheme.colors.ink,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter, vertical = Space.s3),
    )
}

/** Structure first, figures when they arrive (05 §8). Never animated. */
@Composable
private fun DashboardSkeleton() {
    val colors = KhataTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Space.gutter, vertical = Space.s3)
            .clearAndSetSemantics {},
        verticalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.5f)
                .height(14.dp)
                .clip(RoundedCornerShape(Radius.bar))
                .background(colors.rule),
        )
        Box(
            Modifier
                .fillMaxWidth(0.6f)
                .height(44.dp)
                .clip(RoundedCornerShape(Radius.bar))
                .background(colors.rule),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .clip(RoundedCornerShape(Radius.bar))
                .background(colors.rule),
        )
        repeat(SKELETON_ROWS) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(Radius.bar))
                    .background(colors.rule),
            )
        }
    }
}

private fun dayFormat(locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM", locale)

private const val SKELETON_ROWS = 5
