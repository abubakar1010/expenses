package com.app.finance.ui.feature.income

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
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
import com.app.finance.domain.model.IncomeScope
import com.app.finance.domain.usecase.IncomeSourceShare
import com.app.finance.ui.common.EmptyState
import com.app.finance.ui.common.KhataChip
import com.app.finance.ui.common.KhataIcons
import com.app.finance.ui.common.LedgerRow
import com.app.finance.ui.common.MoneyText
import com.app.finance.ui.common.SectionHeader
import com.app.finance.ui.common.YearBars
import com.app.finance.ui.common.monthInitials
import com.app.finance.ui.common.rememberJavaLocale
import com.app.finance.ui.theme.KhataTheme
import com.app.finance.ui.theme.Radius
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Income — FR-IS-01 … FR-IS-06, FR-IE-01 … FR-IE-08.
 *
 * **The screen that must respect lumpy income.** 05 §5.7 is unusually direct
 * about why it breaks the app's own conventions:
 *
 * > "The income screen defaults to a yearly view while every other screen
 * > defaults to monthly. This is the single most important UX accommodation for
 * > this user's situation: a farming month showing ৳0 is alarming and
 * > meaningless in isolation. The year is the honest unit for this income; the
 * > month is the honest unit for spending. The app should not pretend otherwise
 * > for the sake of consistency."
 *
 * Five parts, in the order the user needs them: the scope, the hero figure, the
 * twelve bars, where the money came from, and what that covers.
 *
 * The FAB is not used here. NFR-USE-01 requires expense entry to be one tap from
 * *every* primary screen and 05 §6 allows exactly one FAB in the app, so income
 * entry takes a header action instead — the same shape as "Categories" on the
 * budget screen.
 */
@Composable
fun IncomeScreen(
    container: AppContainer,
    period: Period,
    onPeriodChange: (Period) -> Unit,
    snackbarHostState: SnackbarHostState,
    onManageSources: () -> Unit,
) {
    val vm: IncomeViewModel = viewModel(
        factory = viewModelFactory {
            IncomeViewModel(container.incomeRepo, container.clock, initialPeriod = period)
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val locale = rememberJavaLocale()

    LaunchedEffect(period) { vm.setPeriod(period) }

    val deletedMessage = stringResource(R.string.income_deleted)
    val savedMessage = stringResource(R.string.income_saved)
    val undoLabel = stringResource(R.string.undo)

    Column(Modifier.fillMaxSize()) {
        IncomeHeader(
            state = state,
            onStep = { forward ->
                // A year step keeps the month and moves twelve; a month step
                // moves one. Either way it writes back to the period owned above
                // the NavHost, so Budget and Dashboard follow.
                //
                // A range is absolute, so stepping the shared period would move
                // those two screens while this one held still — a control that
                // looks dead and acts elsewhere. It shifts the range instead,
                // and Range does not write back.
                if (state.scopeKind == ScopeKind.RANGE) {
                    vm.stepRange(forward)
                } else {
                    val delta = if (state.scopeKind == ScopeKind.YEAR) MONTHS_IN_YEAR else 1
                    onPeriodChange(period.plusMonths(if (forward) delta else -delta))
                }
            },
            onScope = vm::setScopeKind,
            onAdd = vm::addEntry,
            onManageSources = onManageSources,
        )

        when {
            state.initialLoad -> IncomeSkeleton()

            // The empty state has to carry the way *out* of whatever produced
            // it. Filtering to a source with no income in the window empties
            // the list, and the filter control lives inside the branch below —
            // so without this the user is stuck looking at an invitation to add
            // income they have already added. The same trap the budget screen's
            // first-run empty state fell into (§13 of the log).
            state.isEmpty -> EmptyState(
                message = when {
                    state.activeFilterCount > 0 -> stringResource(R.string.empty_income_filtered)
                    // 05 §9 — "Nothing recorded in August. Your year is at
                    // ৳5,84,000". The guide singles this line out: it "refuses
                    // to render an empty month as a failure, and immediately
                    // reframes to the unit that is meaningful for this user".
                    // A farming month at ৳0 is the case the whole screen is
                    // built around, and the generic invitation reports it as
                    // the failure it is not.
                    state.showsEmptyMonthReframe -> stringResource(
                        R.string.empty_income_month,
                        state.period.label(locale),
                        state.yearTotal.format(locale),
                    )
                    else -> stringResource(R.string.empty_income)
                },
                modifier = Modifier.fillMaxSize(),
                action = {
                    if (state.activeFilterCount > 0) {
                        KhataChip(
                            label = stringResource(R.string.clear_filters),
                            selected = false,
                            onClick = vm::clearFilters,
                        )
                    } else {
                        KhataChip(
                            label = stringResource(R.string.add_income),
                            selected = false,
                            onClick = vm::addEntry,
                        )
                    }
                },
            )

            else -> LazyColumn(Modifier.fillMaxSize()) {
                item(key = "hero") { HeroTotal(state, locale) }

                item(key = "trend") {
                    Box(Modifier.padding(horizontal = Space.gutter, vertical = Space.s2)) {
                        YearBars(
                            monthlyTotals = state.summary.trend,
                            labels = remember(state.trendPeriods, locale) {
                                monthInitials(locale, state.trendPeriods.map { it.month })
                            },
                            locale = locale,
                        )
                    }
                }

                items(state.summary.shares, key = { "share-${it.sourceId}" }) { share ->
                    SourceRow(
                        share = share,
                        selected = share.sourceId in state.sourceIds,
                        onClick = { vm.toggleSource(share.sourceId) },
                    )
                }

                // 05 §5.4: "Sections that have nothing to say are absent, not
                // empty." Coverage of nothing is not a number.
                state.coverage?.let { percent ->
                    item(key = "coverage") { CoverageLine(percent, state.scopeKind) }
                }

                item(key = "entries-header") {
                    SectionHeader(
                        text = stringResource(R.string.income_entries),
                        trailing = {
                            FilterAction(state.activeFilterCount, vm::openFilters)
                        },
                    )
                }

                items(state.entries, key = { "entry-${it.entry.id}" }) { row ->
                    LedgerRow(
                        label = row.sourceName,
                        amount = Money(row.entry.amountMinor),
                        secondary = remember(row.entry.earnedOn, locale) {
                            LocalDate.ofEpochDay(row.entry.earnedOn).format(dayFormat(locale))
                        },
                        trailing = row.entry.note,
                        onClick = { vm.editEntry(row) },
                    )
                }
            }
        }
    }

    state.editor?.let { editor ->
        IncomeEntrySheet(
            editor = editor,
            sources = state.sources,
            onKey = vm::onKey,
            onSourceName = vm::setSourceName,
            onDate = vm::setDate,
            onNote = vm::setNote,
            onOpenSheet = vm::openSheet,
            onSave = {
                vm.saveEntry { scope.launch { snackbarHostState.showSnackbar(savedMessage) } }
            },
            onDelete = {
                editor.editingId?.let { id ->
                    vm.deleteEntry(id) {
                        scope.launch {
                            snackbarHostState.offerUndo(deletedMessage, undoLabel) {
                                vm.undoDelete()
                            }
                            vm.clearUndo()
                        }
                    }
                }
            },
            onDismiss = vm::dismissEditor,
        )
    }

    if (state.filterSheetOpen) {
        IncomeFilterSheet(
            state = state,
            onSources = vm::setSources,
            onRange = vm::setRange,
            onScope = vm::setScopeKind,
            onClear = vm::clearFilters,
            onDismiss = vm::dismissFilters,
        )
    }
}

/**
 * `‹ 2026 ›   Year ▾   Add income`.
 *
 * Not [com.app.finance.ui.common.PeriodSwitcher]: that renders a month label
 * and this must show a year, a month or a range depending on the scope. The
 * arrows keep its 48 dp targets and its spoken names, which §10 calls out
 * explicitly ("period arrows").
 */
@Composable
private fun IncomeHeader(
    state: IncomeUiState,
    onStep: (Boolean) -> Unit,
    onScope: (ScopeKind) -> Unit,
    onAdd: () -> Unit,
    onManageSources: () -> Unit,
) {
    val colors = KhataTheme.colors
    val locale = rememberJavaLocale()
    val label = when (val scope = state.scope) {
        // Latin digits regardless of locale — 05 §4.4, and the bundled Plex
        // subset covers no others.
        is IncomeScope.Year -> scope.period.year.toString()
        is IncomeScope.Month -> scope.period.label(locale)
        is IncomeScope.Range -> {
            val format = dayFormat(locale)
            "${scope.from.format(format)} – ${scope.to.format(format)}"
        }
    }

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.s2, vertical = Space.s1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Present in all three scopes, but they do not all mean the same
            // thing: Year and Month step the shared period, Range shifts the
            // range by its own length. The spoken name says which.
            Arrow(
                forward = false,
                description = stringResource(
                    when (state.scopeKind) {
                        ScopeKind.YEAR -> R.string.previous_year
                        ScopeKind.MONTH -> R.string.previous_period
                        ScopeKind.RANGE -> R.string.previous_range
                    },
                ),
                onClick = { onStep(false) },
            )
            Text(
                text = label,
                style = KhataTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
                color = colors.ink,
                modifier = Modifier.padding(horizontal = Space.s1),
            )
            Arrow(
                forward = true,
                description = stringResource(
                    when (state.scopeKind) {
                        ScopeKind.YEAR -> R.string.next_year
                        ScopeKind.MONTH -> R.string.next_period
                        ScopeKind.RANGE -> R.string.next_range
                    },
                ),
                onClick = { onStep(true) },
            )

            Box(Modifier.weight(1f))

            TextAction(stringResource(R.string.manage_sources), onManageSources)
            TextAction(stringResource(R.string.add_income), onAdd)
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.gutter, vertical = Space.s1),
            horizontalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            KhataChip(
                label = stringResource(R.string.scope_year),
                selected = state.scopeKind == ScopeKind.YEAR,
                onClick = { onScope(ScopeKind.YEAR) },
            )
            KhataChip(
                label = stringResource(R.string.scope_month),
                selected = state.scopeKind == ScopeKind.MONTH,
                onClick = { onScope(ScopeKind.MONTH) },
            )
            KhataChip(
                label = stringResource(R.string.scope_range),
                selected = state.scopeKind == ScopeKind.RANGE,
                onClick = { onScope(ScopeKind.RANGE) },
            )
        }
    }
}

/** `EARNED THIS YEAR` / `৳5,84,000` — unabbreviated, and spoken as words. */
@Composable
private fun HeroTotal(state: IncomeUiState, locale: Locale) {
    val caption = stringResource(
        when (state.scopeKind) {
            ScopeKind.YEAR -> R.string.earned_this_year
            ScopeKind.MONTH -> R.string.earned_this_month
            ScopeKind.RANGE -> R.string.earned_in_range
        },
    )
    val spoken = "$caption, ${state.summary.total.spokenForm(locale)}"

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter, vertical = Space.s2)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
    ) {
        Text(caption, style = KhataTheme.type.sectionHeader, color = KhataTheme.colors.inkSoft)
        MoneyText(
            money = state.summary.total,
            style = KhataTheme.type.heroFigure,
            // The row's sentence already carries the figure as words; left
            // alone this would merge a second description and TalkBack would
            // say the amount twice.
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/**
 * `Salary   ৳3,60,000   62%  ●`.
 *
 * The dot is **filled for stable and hollow for variable** — 05 §5.7: "a shape
 * difference, not a colour difference, so it survives both greyscale and
 * colourblindness". The same three-signal reasoning as the budget bars, and the
 * spoken form says the word rather than describing the shape.
 */
@Composable
private fun SourceRow(
    share: IncomeSourceShare,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = KhataTheme.colors
    val locale = rememberJavaLocale()
    val kindLabel = stringResource(if (share.isStable) R.string.kind_stable else R.string.kind_variable)
    val spoken = "${share.name}, ${share.total.spokenForm(locale)}, " +
        stringResource(R.string.share_of_total, share.share) + ", " + kindLabel

    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Sizes.rowPlain)
            .clickable(onClick = onClick)
            .background(if (selected) colors.rule else colors.paper)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = spoken
            }
            .drawBehind {
                drawLine(
                    color = colors.rule,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = Sizes.hairline.toPx(),
                )
            }
            .padding(horizontal = Space.gutter, vertical = Space.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        Text(
            text = share.name,
            style = KhataTheme.type.body,
            color = colors.ink,
            modifier = Modifier.weight(1f),
        )
        MoneyText(share.total, modifier = Modifier.clearAndSetSemantics {})
        Text(
            text = stringResource(R.string.percent_share, share.share),
            style = KhataTheme.type.caption,
            color = colors.inkSoft,
        )
        KindDot(stable = share.isStable)
    }
}

/** Filled or hollow — the shape *is* the signal. */
@Composable
private fun KindDot(stable: Boolean) {
    val colors = KhataTheme.colors
    Box(
        Modifier
            .size(DOT_SIZE)
            .clearAndSetSemantics {}
            .drawBehind {
                val radius = size.minDimension / 2f
                val centre = Offset(size.width / 2f, size.height / 2f)
                if (stable) {
                    drawCircle(color = colors.moss, radius = radius, center = centre)
                } else {
                    drawCircle(
                        color = colors.moss,
                        radius = radius - DOT_STROKE / 2f,
                        center = centre,
                        style = Stroke(width = DOT_STROKE),
                    )
                }
            },
    )
}

/**
 * 05 §5.7 — "the insight that matters", and it names the window it covers.
 *
 * The mock's line ends "of your spending this year". The scope decides which
 * window that is, and "your spending" on its own names none of them.
 */
@Composable
private fun CoverageLine(percent: Int, scopeKind: ScopeKind) {
    Text(
        text = stringResource(
            when (scopeKind) {
                ScopeKind.YEAR -> R.string.stable_coverage_year
                ScopeKind.MONTH -> R.string.stable_coverage_month
                ScopeKind.RANGE -> R.string.stable_coverage_range
            },
            percent,
        ),
        style = KhataTheme.type.body,
        color = KhataTheme.colors.ink,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter, vertical = Space.s3),
    )
}

@Composable
private fun FilterAction(activeCount: Int, onClick: () -> Unit) {
    val label = if (activeCount == 0) {
        stringResource(R.string.filter)
    } else {
        pluralStringResource(R.plurals.filters_active, activeCount, activeCount)
    }
    TextAction(label, onClick)
}

@Composable
private fun TextAction(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = KhataTheme.type.caption,
        color = KhataTheme.colors.indigo,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .defaultMinSize(minHeight = Sizes.minTouchTarget)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = Space.s2, vertical = Space.s2),
    )
}

@Composable
private fun Arrow(forward: Boolean, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .sizeIn(minWidth = Sizes.minTouchTarget, minHeight = Sizes.minTouchTarget)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = description
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (forward) KhataIcons.ChevronRight else KhataIcons.ChevronLeft,
            contentDescription = null,
            tint = KhataTheme.colors.inkSoft,
            modifier = Modifier.size(Sizes.navIcon).clearAndSetSemantics {},
        )
    }
}

/** Structure first, figures when they arrive (§8). Never animated. */
@Composable
private fun IncomeSkeleton() {
    val colors = KhataTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Space.gutter, vertical = Space.s2)
            .clearAndSetSemantics {},
        verticalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.55f)
                .height(28.dp)
                .clip(RoundedCornerShape(Radius.bar))
                .background(colors.rule),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
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

/**
 * NFR-USE-03 — "undoable for at least 5 seconds". The same mechanism the ledger
 * and budget screens use: Material offers ~4 s or ~10 s, so the window is
 * enforced by cancelling an indefinite snackbar at exactly five.
 */
private suspend fun SnackbarHostState.offerUndo(
    message: String,
    undoLabel: String,
    onUndo: () -> Unit,
) {
    val result = withTimeoutOrNull(UNDO_WINDOW_MS) {
        showSnackbar(
            message = message,
            actionLabel = undoLabel,
            duration = SnackbarDuration.Indefinite,
        )
    }
    if (result == SnackbarResult.ActionPerformed) onUndo()
}

internal fun dayFormat(locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy", locale)

private const val MONTHS_IN_YEAR = 12
private const val SKELETON_ROWS = 4
private const val UNDO_WINDOW_MS = 5_000L
private val DOT_SIZE = 10.dp
private const val DOT_STROKE = 3f
