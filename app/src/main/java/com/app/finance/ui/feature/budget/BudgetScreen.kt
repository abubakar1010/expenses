package com.app.finance.ui.feature.budget

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalResources
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
import com.app.finance.domain.model.BudgetState
import com.app.finance.domain.usecase.BudgetAlert
import com.app.finance.domain.usecase.BudgetGroup
import com.app.finance.domain.usecase.BudgetLeaf
import com.app.finance.ui.common.BudgetBar
import com.app.finance.ui.common.EmptyState
import com.app.finance.ui.common.KhataChip
import com.app.finance.ui.common.LeaderDots
import com.app.finance.ui.common.MoneyText
import com.app.finance.ui.common.PeriodSwitcher
import com.app.finance.ui.common.AlertRow
import com.app.finance.ui.common.SectionHeader
import com.app.finance.ui.common.rememberJavaLocale
import com.app.finance.ui.theme.KhataTheme
import com.app.finance.ui.theme.Radius
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Spending limits — FR-BUD-01 … FR-BUD-08.
 *
 * The screen has three parts, in the order the user needs them:
 *
 * 1. the period switcher, because every figure below is scoped to it
 * 2. **needs attention**, present only when something is — 05 §5.4: "An empty
 *    state here would train the user to ignore the region. Sections that have
 *    nothing to say are absent, not empty."
 * 3. the groups, ordered by actionability rather than size: variable first,
 *    fixed last, "because rent is not a decision"
 */
@Composable
fun BudgetScreen(
    container: AppContainer,
    period: Period,
    onPeriodChange: (Period) -> Unit,
    snackbarHostState: SnackbarHostState,
    onManageCategories: () -> Unit,
) {
    val vm: BudgetViewModel = viewModel(
        factory = viewModelFactory {
            BudgetViewModel(
                container.budgetRepo,
                container.categoryRepo,
                container.clock,
                initialPeriod = period,
            )
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // The period is owned above the NavHost so Budget, Dashboard and Income all
    // agree on it; this keeps the ViewModel in step when it changes.
    LaunchedEffect(period) { vm.setPeriod(period) }

    // Resolved through `Resources` rather than `pluralStringResource` because
    // the count is only known inside the callback, which is not composable.
    // `LocalResources`, not `LocalContext.current.resources`: the latter is not
    // configuration-aware, so a locale or font-scale change would leave this
    // holding the old one.
    val resources = LocalResources.current
    val undoLabel = stringResource(R.string.undo)

    // Declared once and passed to both chips. The affordance appears in two
    // branches of the layout and the behaviour must not be able to differ.
    val copyLastMonth: () -> Unit = {
        vm.copyFromLastMonth { count, added ->
            scope.launch {
                snackbarHostState.offerUndo(
                    message = resources.getQuantityString(R.plurals.copied_limits, count, count),
                    undoLabel = undoLabel,
                    onUndo = { vm.undoCopy(added) },
                )
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        PeriodSwitcher(
            period = period,
            onChange = onPeriodChange,
            trailing = {
                Text(
                    text = stringResource(R.string.manage_categories),
                    style = KhataTheme.type.body,
                    color = KhataTheme.colors.indigo,
                    modifier = Modifier
                        .defaultMinSize(minHeight = Sizes.minTouchTarget)
                        .clickable(onClick = onManageCategories)
                        .semantics { role = Role.Button }
                        .padding(horizontal = Space.s2, vertical = Space.s2),
                )
            },
        )

        when {
            state.initialLoad -> BudgetSkeleton()

            state.isEmpty -> EmptyState(
                message = stringResource(R.string.empty_budgets),
                modifier = Modifier.fillMaxSize(),
                action = { CopyLastMonthChip(state.copyableCount, copyLastMonth) },
            )

            else -> LazyColumn(Modifier.fillMaxSize()) {
                if (state.alerts.isNotEmpty()) {
                    item(key = "alerts-header") {
                        SectionHeader(stringResource(R.string.needs_attention))
                    }
                    items(state.alerts, key = { "alert-${it.categoryId}" }) { alert ->
                        AlertRow(alert)
                    }
                }

                item(key = "copy") {
                    Box(Modifier.padding(horizontal = Space.gutter, vertical = Space.s2)) {
                        CopyLastMonthChip(state.copyableCount, copyLastMonth)
                    }
                }

                state.groups.forEach { group ->
                    item(key = "group-${group.id}") { GroupHeader(group) }
                    items(group.leaves, key = { "leaf-${it.id}" }) { leaf ->
                        LimitRow(
                            leaf = leaf,
                            onClick = { vm.editLimit(leaf.id, leaf.name) },
                        )
                    }
                }
            }
        }
    }

    state.editor?.let { editor ->
        val savedMessage = stringResource(R.string.limit_saved)
        val clearedMessage = stringResource(R.string.limit_cleared)
        LimitSheet(
            editor = editor,
            onKey = vm::onKey,
            onSave = {
                vm.saveLimit { scope.launch { snackbarHostState.showSnackbar(savedMessage) } }
            },
            onClear = {
                vm.clearLimit { categoryId, removed ->
                    scope.launch {
                        snackbarHostState.offerUndo(clearedMessage, undoLabel) {
                            vm.undoClear(categoryId, removed)
                        }
                    }
                }
            },
            onDismiss = vm::dismissEditor,
        )
    }
}

/** FR-BUD-04. Disabled with the reason shown when last month has nothing. */
@Composable
private fun CopyLastMonthChip(copyableCount: Int, onCopy: () -> Unit) {
    if (copyableCount == 0) {
        Text(
            text = stringResource(R.string.nothing_to_copy),
            style = KhataTheme.type.caption,
            color = KhataTheme.colors.inkSoft,
        )
    } else {
        KhataChip(
            label = stringResource(R.string.copy_last_month),
            selected = false,
            onClick = onCopy,
        )
    }
}

/** `VARIABLE EXPENSES    ৳12,400 of ৳18,000` — the root total, never stored. */
@Composable
private fun GroupHeader(group: BudgetGroup) {
    val locale = rememberJavaLocale()
    SectionHeader(
        text = group.name,
        trailing = {
            Text(
                // Unabbreviated. §5.4's mock writes "18k", which §4.3 forbids:
                // "Never abbreviate to 1.2k. In a ledger, precision is the
                // product."
                text = if (group.isUnbudgeted) {
                    group.spent.format(locale)
                } else {
                    stringResource(
                        R.string.group_total,
                        group.spent.format(locale),
                        group.limit.format(locale),
                    )
                },
                style = KhataTheme.type.caption,
                color = KhataTheme.colors.inkSoft,
            )
        },
    )
}

/**
 * A leaf: label, spend, bar, and the text half of the state.
 *
 * FR-BUD-05 wants **spent, limit, remaining and percentage** for every budgeted
 * category, and all four are on this row: spent is the figure on the right,
 * remaining (or the overspend) is the caption opposite, and the percentage
 * carries the limit with it — `104% of ৳9,000`.
 *
 * The limit used to live only in the group total above, which is a sum across
 * every leaf in the root and therefore not this leaf's limit at all. Pairing it
 * with the percentage is what makes the percentage legible anyway: 104% of what
 * is the question a bare figure invites.
 */
@Composable
private fun LimitRow(
    leaf: BudgetLeaf,
    onClick: () -> Unit,
) {
    val colors = KhataTheme.colors
    val locale = rememberJavaLocale()
    val status = leaf.status

    // Over budget by exactly nothing — see `limit_reached`.
    val atLimit = status.state == BudgetState.OVER && status.overspend.isZero

    val stateText = when {
        !leaf.hasLimit -> stringResource(R.string.no_limit_set)
        // FR-BUD-07: an unpredictable category never says "left". Spending a
        // buffer is not consuming an allocation.
        leaf.isUnplanned -> stringResource(
            R.string.amount_of_limit,
            status.spent.format(locale),
            status.limit.format(locale),
        )
        atLimit -> stringResource(R.string.limit_reached)
        status.state == BudgetState.OVER ->
            stringResource(R.string.amount_over, status.overspend.format(locale))
        else -> stringResource(R.string.amount_left, status.remaining.format(locale))
    }
    // The unplanned row already reads "৳2,400 of ৳5,000", so it has the limit;
    // adding it here too would print the same figure twice on one row.
    val percent = when {
        !leaf.hasLimit -> null
        leaf.isUnplanned -> null
        else -> stringResource(
            R.string.percent_of_limit,
            status.percentConsumed,
            status.limit.format(locale),
        )
    }

    // The visible text carries "৳300 left"; the announced one carries "three
    // hundred taka left". Same resources, figures as words — §10.
    val spokenState = when {
        !leaf.hasLimit -> stringResource(R.string.no_limit_set)
        leaf.isUnplanned -> stringResource(
            R.string.amount_of_limit,
            status.spent.spokenForm(locale),
            status.limit.spokenForm(locale),
        )
        atLimit -> stringResource(R.string.limit_reached)
        status.state == BudgetState.OVER ->
            stringResource(R.string.amount_over, status.overspend.spokenForm(locale))
        else -> stringResource(R.string.amount_left, status.remaining.spokenForm(locale))
    }
    // The limit is spoken as well as shown: FR-BUD-05 is not satisfied for a
    // TalkBack user by a figure that only exists visually.
    val spokenLimit = if (leaf.hasLimit && !leaf.isUnplanned) {
        stringResource(
            R.string.percent_of_limit,
            status.percentConsumed,
            status.limit.spokenForm(locale),
        ) + ", "
    } else {
        ""
    }
    val spoken =
        "${leaf.name}, ${status.spent.spokenForm(locale)}, $spokenLimit$spokenState"

    Column(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Sizes.rowWithBar)
            .clickable(onClick = onClick)
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
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = leaf.name, style = KhataTheme.type.body, color = colors.ink)
            // §6 lists leader dots in the ledger-row specification, and this is
            // that row's 72 dp with-bar variant.
            LeaderDots(Modifier.weight(1f))
            // The one child that *is* cleared: `MoneyText` carries its own
            // spoken description, and the row's sentence already contains the
            // same figure. Left alone it would merge into a second
            // contentDescription and TalkBack would say the amount twice.
            MoneyText(money = status.spent, modifier = Modifier.clearAndSetSemantics {})
        }

        Box(Modifier.padding(top = Space.s2)) {
            BudgetBar(status = status, unplanned = leaf.isUnplanned)
        }

        Row(
            Modifier.fillMaxWidth().padding(top = Space.s1),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = percent.orEmpty(),
                style = KhataTheme.type.caption,
                color = colors.inkSoft,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                Text(
                    text = stateText,
                    style = KhataTheme.type.caption,
                    color = when {
                        !leaf.hasLimit -> colors.inkSoft
                        status.state == BudgetState.OVER -> colors.vermilion
                        status.state == BudgetState.NEAR && !leaf.isUnplanned -> colors.amber
                        else -> colors.inkSoft
                    },
                )
                if (!leaf.hasLimit) {
                    // §9's "No limit set. Set one" — the second half is the
                    // control, so it takes the action colour.
                    Text(
                        text = stringResource(R.string.set_one),
                        style = KhataTheme.type.caption,
                        color = colors.indigo,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** Structure first, figures when they arrive (§8). Never animated. */
@Composable
private fun BudgetSkeleton() {
    val colors = KhataTheme.colors
    Column(Modifier.fillMaxSize().clearAndSetSemantics {}) {
        repeat(SKELETON_ROWS) { index ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(Sizes.rowWithBar)
                    .padding(horizontal = Space.gutter, vertical = Space.s2),
                verticalArrangement = Arrangement.spacedBy(Space.s2),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(if (index % 2 == 0) 0.42f else 0.3f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(Radius.bar))
                        .background(colors.rule),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(Sizes.bar)
                        .clip(RoundedCornerShape(Radius.bar))
                        .background(colors.rule),
                )
            }
        }
    }
}

/**
 * NFR-USE-03 — "undoable for at least 5 seconds". Same mechanism the ledger
 * uses: Material offers ~4 s or ~10 s, so the window is enforced by cancelling
 * an indefinite snackbar at exactly five.
 */
private suspend fun SnackbarHostState.offerUndo(
    message: String,
    undoLabel: String?,
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

private const val SKELETON_ROWS = 6
private const val UNDO_WINDOW_MS = 5_000L
