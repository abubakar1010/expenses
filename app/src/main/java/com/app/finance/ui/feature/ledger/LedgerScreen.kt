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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
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
import com.app.finance.ui.common.DayBookChip
import com.app.finance.ui.common.LedgerRow
import com.app.finance.data.db.dao.ExpenseWithCategory
import com.app.finance.ui.common.MoneyText
import com.app.finance.ui.common.rememberJavaLocale
import com.app.finance.ui.common.SectionHeader
import com.app.finance.ui.common.labelRes
import com.app.finance.ui.theme.DayBookTheme
import com.app.finance.ui.theme.Radius
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space
import com.app.finance.ui.common.offerUndo
import kotlinx.coroutines.launch
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
    onOpenPeople: () -> Unit,
) {
    val vm: LedgerViewModel = viewModel(
        factory = viewModelFactory {
            LedgerViewModel(
                repo = container.expenseRepo,
                categories = container.categoryRepo,
                recurring = container.recurringRepo,
                people = container.personRepo,
                settlements = container.settlementRepo,
                clock = container.clock,
            )
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

    val scope = rememberCoroutineScope()
    val deletedMessage = stringResource(R.string.expense_deleted)
    val dismissedMessage = stringResource(R.string.entry_dismissed)
    val confirmedMessage = stringResource(R.string.entry_confirmed)
    val undoLabel = stringResource(R.string.undo)

    // NFR-USE-03: "undoable for at least 5 seconds", one action at a time.
    //
    // Keyed on the head's id and not on the head itself, which is what makes
    // the queue behind it worth having: a second swipe appends without
    // disturbing the effect that is running, so the first entry keeps its whole
    // window instead of losing it — and its only surviving copy — to a
    // cancellation that ran neither branch. Dismissing a pending entry is a
    // delete too, and it shares the queue rather than racing it for the host's
    // mutex; there the usual escape hatch is closed as well, because the rule
    // has already advanced past that due date and will never generate it again.
    val nextUndo = state.undoQueue.firstOrNull()
    LaunchedEffect(nextUndo?.id) {
        val item = nextUndo ?: return@LaunchedEffect
        snackbarHostState.offerUndo(
            message = when (item.payload) {
                is LedgerUndo.Deleted -> deletedMessage
                is LedgerUndo.Dismissed -> dismissedMessage
            },
            undoLabel = undoLabel,
            onUndo = { vm.undo(item.id) },
            onExpired = { vm.dropUndo(item.id) },
        )
    }

    Column(Modifier.fillMaxSize()) {
        SearchBar(
            query = state.filters.query,
            activeFilters = state.filters.activeCount,
            onQuery = vm::setQuery,
            onFilters = vm::openFilters,
            onPeople = onOpenPeople,
        )

        // FR-EXP-11. Above the list rather than under it: the answer to "how
        // much is this filter worth" should not require scrolling to the end of
        // what the filter matched.
        if (state.showsFilteredTotal) {
            // FR-SHR-06. Filtered to a person the header answers a different
            // question — "what does this come to between us" — because "your
            // share of things you did with Rahim" is not what picking a name
            // asks.
            val person = state.filters.personId
                ?.let { id -> state.people.firstOrNull { it.id == id } }
            if (person != null) {
                PersonBalanceHeader(name = person.name, balance = state.personBalance)
            } else {
                FilterTotal(total = state.filteredTotal, count = state.filteredCount)
            }
        }

        // FR-REC-02, above the day groups and above the empty states.
        //
        // Outside the `when` deliberately: a first-run user whose only rows are
        // unconfirmed would otherwise read "nothing logged yet" with two
        // entries waiting for them just out of sight. Absent when empty, like
        // every other section in the app.
        if (state.pendingCount > 0) {
            PendingEntries(
                state = state,
                onConfirmExpense = {
                    vm.confirmExpense(it)
                    scope.launch { snackbarHostState.showSnackbar(confirmedMessage) }
                },
                onDismissExpense = vm::dismissExpense,
                onConfirmIncome = {
                    vm.confirmIncome(it)
                    scope.launch { snackbarHostState.showSnackbar(confirmedMessage) }
                },
                onDismissIncome = vm::dismissIncome,
            )
        }

        when {
            // 05 §8: "A skeleton for 80 ms is better than a spinner for 300 ms,
            // and far better than a blank screen."
            state.initialLoad -> LedgerSkeleton()

            state.isFilteredEmpty -> EmptyState(
                message = stringResource(R.string.empty_search),
                modifier = Modifier.fillMaxSize(),
                action = {
                    DayBookChip(
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
                    DayBookChip(
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
                        SwipeableRow(onDelete = { vm.delete(row.expense.id) }) {
                            LedgerRow(
                                label = row.categoryName,
                                amount = Money(row.expense.amountMinor),
                                secondary = row.expense.note,
                                trailing = stringResource(
                                    PaymentMethod.fromCode(row.expense.paymentMethod).labelRes(),
                                ),
                                split = row.splitLine(),
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
            present = state.categoriesPresent,
            people = state.people,
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
    onPeople: () -> Unit,
) {
    val colors = DayBookTheme.colors
    var text by rememberSaveable(query) { mutableStateOf(query) }
    val hint = stringResource(R.string.search_ledger)
    val focusManager = LocalFocusManager.current

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
            textStyle = DayBookTheme.type.body.copy(color = colors.ink),
            cursorBrush = SolidColor(colors.indigo),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            // `ImeAction.Search` has no default action — `KeyboardActionRunner`
            // falls through to `else -> false` for it — so without this the
            // magnifier key on the IME did nothing at all, on the one field in
            // the app most likely to be typed into and then left. The query is
            // already applied on every keystroke, so "search" here means only
            // "I am done typing".
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
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
                        Text(hint, style = DayBookTheme.type.body, color = colors.inkSoft)
                    }
                    inner()
                }
            },
        )
        DayBookChip(
            // The count is on the control so a filtered ledger never looks like
            // an empty one.
            label = if (activeFilters == 0) {
                stringResource(R.string.filter)
            } else {
                pluralStringResource(R.plurals.filters_active, activeFilters, activeFilters)
            },
            selected = activeFilters > 0,
            onClick = onFilters,
        )
        // FR-SHR-05, reached from the screen that owns the data — the same
        // arrangement as Categories on Budget and Income sources on Income.
        DayBookChip(
            label = stringResource(R.string.manage_people),
            selected = false,
            onClick = onPeople,
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
    content: @Composable () -> Unit,
) {
    val colors = DayBookTheme.colors
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

    // Only paint the ground while a swipe is actually under way. A
    // `backgroundContent` that draws unconditionally sits behind every row
    // forever, and since the ledger row is deliberately transparent — the rule
    // is its structure, not a card — the red showed through at rest on every
    // row in the list.
    val swiping = dismissState.targetValue != SwipeToDismissBoxValue.Settled ||
        dismissState.progress !in SETTLED_RANGE

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(if (swiping) colors.vermilion else Color.Transparent)
                    .padding(horizontal = Space.gutter)
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (swiping) {
                    Text(
                        text = stringResource(R.string.delete_expense),
                        style = DayBookTheme.type.caption,
                        color = colors.card,
                    )
                }
            }
        },
        // The row itself must be opaque, or the ground shows through it during
        // the swipe instead of sliding out from under it.
        content = {
            Box(Modifier.background(colors.paper)) { content() }
        },
    )
}

/**
 * `12 MATCHES` with what they come to on the right — FR-EXP-11.
 *
 * Shares [SectionHeader] with the day groups on purpose. It is the same kind of
 * statement — a heading and the money under it — and giving the filtered total
 * its own card would make the ledger's most transient figure its loudest.
 *
 * The count is not decoration. FR-EXP-10 means the rows on screen are only the
 * pages scrolled so far, so an amount alone would invite the reader to check it
 * against what they can see and find it too large. Saying what it counts is
 * what makes it legible.
 */
@Composable
private fun FilterTotal(total: Money, count: Int) {
    SectionHeader(
        text = pluralStringResource(R.plurals.filter_match_count, count, count),
        trailing = {
            MoneyText(
                money = total,
                style = DayBookTheme.type.caption,
                color = DayBookTheme.colors.ink,
                spokenSuffix = stringResource(R.string.filter_total, ""),
            )
        },
    )
}

/**
 * What the filtered person's balance comes to — FR-SHR-06.
 *
 * Replaces FR-EXP-11's total rather than joining it, because two money figures
 * side by side that mean different things is worse than the wrong one alone.
 */
@Composable
private fun PersonBalanceHeader(name: String, balance: Money) {
    SectionHeader(
        text = name,
        trailing = {
            MoneyText(
                money = balance,
                style = DayBookTheme.type.caption,
                // Signed, so `MoneyText` already renders what you owe in
                // vermilion with a true minus.
                color = null,
                spokenSuffix = stringResource(
                    if (balance.isNegative) R.string.you_owe_them else R.string.owes_you,
                ),
            )
        },
    )
}

/**
 * The third line on a shared row — FR-SHR-02, FR-SHR-03. Null on every other.
 *
 * Without it the ledger shows ৳250 for a dinner the user remembers paying
 * ৳1,000 for, which reads as simply wrong. Saying what the figure is a share
 * *of* is what makes the stored number legible.
 */
@Composable
private fun ExpenseWithCategory.splitLine(): String? {
    if (!isShared) return null
    val locale = rememberJavaLocale()
    payerName?.let { return stringResource(R.string.split_paid_by, it) }
    return stringResource(
        R.string.split_of_bill,
        Money(billMinor).format(locale),
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
                style = DayBookTheme.type.caption,
                color = DayBookTheme.colors.inkSoft,
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
    val colors = DayBookTheme.colors
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
        Text(loading, style = DayBookTheme.type.caption, color = colors.paper)
    }
}

private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM")

/** Rows, not pixels — one screenful of lead time at a 56 dp row height. */
private const val PREFETCH_DISTANCE = 10
private const val SKELETON_ROWS = 8

/** NFR-USE-03 — "at least 5 seconds". */

/** `progress` sits at 0 or 1 when a row is at rest, and between while swiping. */
private val SETTLED_RANGE = 0.999f..1.001f

/**
 * FR-REC-02's confirmations — the rows a recurring rule generated.
 *
 * A plain `Column` rather than part of the `LazyColumn`: the list below is
 * paged and keyed on expense ids, and threading a second, differently-typed
 * source through it would mean either a shared key space or a discriminator on
 * every row. There are never many of these — one per rule per missed period.
 */
@Composable
private fun PendingEntries(
    state: LedgerUiState,
    onConfirmExpense: (Long) -> Unit,
    onDismissExpense: (Long) -> Unit,
    onConfirmIncome: (Long) -> Unit,
    onDismissIncome: (Long) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(
            text = stringResource(R.string.waiting_to_confirm),
            trailing = {
                Text(
                    text = state.pendingCount.toString(),
                    style = DayBookTheme.type.caption,
                    color = DayBookTheme.colors.inkSoft,
                )
            },
        )
        state.pendingExpenses.forEach { row ->
            PendingRow(
                label = row.categoryName,
                amount = Money(row.expense.amountMinor),
                date = LocalDate.ofEpochDay(row.expense.spentOn),
                onConfirm = { onConfirmExpense(row.expense.id) },
                onDismiss = { onDismissExpense(row.expense.id) },
            )
        }
        state.pendingIncome.forEach { row ->
            PendingRow(
                label = row.sourceName,
                amount = Money(row.entry.amountMinor),
                date = LocalDate.ofEpochDay(row.entry.earnedOn),
                onConfirm = { onConfirmIncome(row.entry.id) },
                onDismiss = { onDismissIncome(row.entry.id) },
            )
        }
    }
}
