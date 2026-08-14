package com.app.finance.ui.feature.ledger

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.finance.R
import com.app.finance.core.money.Money
import com.app.finance.di.AppContainer
import com.app.finance.di.viewModelFactory
import com.app.finance.domain.model.PaymentMethod
import com.app.finance.ui.common.EmptyState
import com.app.finance.ui.common.KhataChip
import com.app.finance.ui.common.LedgerRow
import com.app.finance.ui.common.MoneyText
import com.app.finance.ui.common.SectionHeader
import com.app.finance.ui.common.labelRes
import com.app.finance.ui.theme.KhataTheme
import com.app.finance.ui.theme.Radius
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The ledger — grouped by day with per-day subtotals (FR-EXP-09), paged so the
 * full history is never held in memory (FR-EXP-10), filterable and searchable
 * (FR-EXP-08).
 *
 * **Tap a row to edit it; swipe it away to delete.** The destructive action is
 * the one that takes a deliberate gesture, and it is undoable for five seconds
 * (NFR-USE-03). Rows are never animated in (05 §7): a list that animates each
 * item's appearance costs frames on exactly the scroll NFR-PERF-05 measures.
 */
@Composable
fun LedgerScreen(
    container: AppContainer,
    snackbarHostState: SnackbarHostState,
    onEdit: (Long) -> Unit,
    onAdd: () -> Unit,
) {
    val vm: LedgerViewModel = viewModel(
        factory = viewModelFactory {
            LedgerViewModel(container.expenseRepo, container.categoryRepo, container.clock)
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Load the next page a little before the bottom, so the join never shows.
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - PREFETCH_DISTANCE
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { shouldLoadMore }.collect { if (it) vm.loadMore() }
    }

    val deletedMessage = stringResource(R.string.expense_deleted)
    val undoLabel = stringResource(R.string.undo)

    // NFR-USE-03: "undoable for at least 5 seconds". Material offers ~4 s
    // (Short) or ~10 s (Long), so neither is the requirement. Showing it
    // indefinitely and cancelling after exactly the window gives 5 s.
    LaunchedEffect(state.lastDeleted) {
        if (state.lastDeleted == null) return@LaunchedEffect
        val result = withTimeoutOrNull(UNDO_WINDOW_MS) {
            snackbarHostState.showSnackbar(
                message = deletedMessage,
                actionLabel = undoLabel,
                withDismissAction = false,
                duration = SnackbarDuration.Indefinite,
            )
        }
        if (result == SnackbarResult.ActionPerformed) vm.undoDelete() else vm.clearUndo()
    }

    Column(Modifier.fillMaxSize()) {
        SearchBar(
            query = state.filters.query,
            activeFilters = state.filters.activeCount,
            onQuery = vm::setQuery,
            onFilters = vm::openFilters,
        )

        when {
            // 05 §8: "A skeleton for 80 ms is better than a spinner for 300 ms,
            // and far better than a blank screen."
            state.initialLoad -> LedgerSkeleton()

            state.isFilteredEmpty -> EmptyState(
                message = stringResource(R.string.empty_search),
                modifier = Modifier.fillMaxSize(),
                action = {
                    KhataChip(
                        label = stringResource(R.string.clear_filters),
                        selected = false,
                        onClick = vm::clearFilters,
                    )
                },
            )

            state.isEmpty -> EmptyState(
                message = stringResource(R.string.empty_ledger),
                modifier = Modifier.fillMaxSize(),
                action = {
                    KhataChip(
                        label = stringResource(R.string.add_expense),
                        selected = true,
                        onClick = onAdd,
                    )
                },
            )

            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                state.days.forEach { day ->
                    item(key = "header-${day.date.toEpochDay()}") {
                        DayHeader(day, state.today)
                    }
                    items(
                        count = day.rows.size,
                        key = { i -> day.rows[i].expense.id },
                    ) { i ->
                        val row = day.rows[i]
                        SwipeableRow(
                            onDelete = { vm.delete(row.expense.id) },
                            onClick = { onEdit(row.expense.id) },
                        ) {
                            LedgerRow(
                                label = row.categoryName,
                                amount = Money(row.expense.amountMinor),
                                secondary = row.expense.note,
                                trailing = stringResource(
                                    PaymentMethod.fromCode(row.expense.paymentMethod).labelRes(),
                                ),
                                onClick = { onEdit(row.expense.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (state.filterSheetOpen) {
        LedgerFilterSheet(
            current = state.filters,
            tree = state.tree,
            today = state.today,
            onApply = vm::applyFilters,
            onClear = vm::clearFilters,
            onDismiss = vm::dismissFilters,
        )
    }
}

/**
 * Search plus the filter control — FR-EXP-08.
 *
 * One field covers both halves of the requirement: the text is matched as a
 * note substring, and if it parses as a number it is *also* matched against the
 * amount exactly. Two separate inputs would be more literal and slower to use.
 */
@Composable
private fun SearchBar(
    query: String,
    activeFilters: Int,
    onQuery: (String) -> Unit,
    onFilters: () -> Unit,
) {
    val colors = KhataTheme.colors
    var text by rememberSaveable(query) { mutableStateOf(query) }
    val hint = stringResource(R.string.search_ledger)

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter, vertical = Space.s2),
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it; onQuery(it) },
            singleLine = true,
            textStyle = KhataTheme.type.body.copy(color = colors.ink),
            cursorBrush = SolidColor(colors.indigo),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = Sizes.minTouchTarget)
                .drawBehind {
                    drawLine(
                        color = if (text.isEmpty()) colors.rule else colors.indigo,
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = 2f,
                    )
                },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) {
                        Text(hint, style = KhataTheme.type.body, color = colors.inkSoft)
                    }
                    inner()
                }
            },
        )
        KhataChip(
            // The count is on the control so a filtered ledger never looks like
            // an empty one.
            label = if (activeFilters == 0) {
                stringResource(R.string.filter)
            } else {
                stringResource(R.string.filters_active, activeFilters)
            },
            selected = activeFilters > 0,
            onClick = onFilters,
        )
    }
}

/**
 * Swipe either way to delete, with a `vermilion` ground behind the row so the
 * gesture states its consequence before it completes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableRow(
    onDelete: () -> Unit,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = KhataTheme.colors
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) {
                onDelete()
                true
            } else {
                false
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(colors.vermilion)
                    .padding(horizontal = Space.gutter)
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = stringResource(R.string.delete_expense),
                    style = KhataTheme.type.caption,
                    color = colors.card,
                )
            }
        },
        content = { content() },
    )
}

/** `TODAY · FRIDAY 14 AUGUST` with the day's subtotal on the right. */
@Composable
private fun DayHeader(day: LedgerDay, today: LocalDate) {
    val label = remember(day.date) { day.date.format(DAY_FORMAT) }
    val heading = when (day.date) {
        today -> stringResource(R.string.today_prefix, label)
        today.minusDays(1) -> stringResource(R.string.yesterday_prefix, label)
        else -> label
    }
    SectionHeader(
        text = heading,
        trailing = {
            MoneyText(
                money = day.total,
                style = KhataTheme.type.caption,
                color = KhataTheme.colors.inkSoft,
                // "<amount> spent", so the figure is not a bare number to
                // TalkBack when it follows the day heading.
                spokenSuffix = stringResource(R.string.day_total, ""),
            )
        },
    )
}

/**
 * Structure first, figures when they arrive. Deliberately not animated — a
 * shimmer would cost frames on the very first thing drawn after a cold start.
 */
@Composable
private fun LedgerSkeleton() {
    val colors = KhataTheme.colors
    val loading = stringResource(R.string.loading_ledger)
    Column(
        Modifier
            .fillMaxSize()
            .clearAndSetSemantics { },
    ) {
        repeat(SKELETON_ROWS) { index ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(Sizes.rowPlain)
                    .padding(horizontal = Space.gutter, vertical = Space.s2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s3),
            ) {
                Box(
                    Modifier
                        .weight(if (index % 2 == 0) 0.45f else 0.32f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(Radius.bar))
                        .background(colors.rule),
                )
                Box(Modifier.weight(1f))
                Box(
                    Modifier
                        .weight(0.22f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(Radius.bar))
                        .background(colors.rule),
                )
            }
        }
        Text(loading, style = KhataTheme.type.caption, color = colors.paper)
    }
}

private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM")

/** Rows, not pixels — one screenful of lead time at a 56 dp row height. */
private const val PREFETCH_DISTANCE = 10
private const val SKELETON_ROWS = 8

/** NFR-USE-03 — "at least 5 seconds". */
private const val UNDO_WINDOW_MS = 5_000L
