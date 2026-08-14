package com.app.finance.ui.feature.ledger

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.finance.R
import com.app.finance.core.money.Money
import com.app.finance.di.AppContainer
import com.app.finance.di.viewModelFactory
import com.app.finance.domain.model.PaymentMethod
import com.app.finance.ui.common.EmptyState
import com.app.finance.ui.common.LedgerRow
import com.app.finance.ui.common.MoneyText
import com.app.finance.ui.common.SectionHeader
import com.app.finance.ui.theme.KhataTheme
import com.app.finance.ui.theme.Space
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The ledger — grouped by day with per-day subtotals (FR-EXP-09), paged so the
 * full history is never held in memory (FR-EXP-10).
 *
 * Rows are never animated in (05 §7): a list that animates each item's
 * appearance costs frames on exactly the scroll NFR-PERF-05 measures at 55 fps.
 */
@Composable
fun LedgerScreen(
    container: AppContainer,
    snackbarHostState: SnackbarHostState,
) {
    val vm: LedgerViewModel = viewModel(
        factory = viewModelFactory { LedgerViewModel(container.expenseRepo) },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    val deletedMessage = stringResource(R.string.expense_deleted)
    val undoLabel = stringResource(R.string.undo)

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

    // Five seconds with Undo, per §6. Every delete gets one.
    LaunchedEffect(state.lastDeleted) {
        if (state.lastDeleted == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = deletedMessage,
            actionLabel = undoLabel,
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) vm.undoDelete() else vm.clearUndo()
    }

    if (state.isEmpty) {
        EmptyState(stringResource(R.string.empty_ledger), Modifier.fillMaxSize())
        return
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        state.days.forEach { day ->
            item(key = "header-${day.date}") {
                DayHeader(day)
            }
            items(count = day.rows.size, key = { i -> day.rows[i].expense.id }) { i ->
                val row = day.rows[i]
                LedgerRow(
                    label = row.categoryName,
                    amount = Money(row.expense.amountMinor),
                    secondary = row.expense.note,
                    trailing = PaymentMethod.fromCode(row.expense.paymentMethod).label,
                    onClick = { vm.delete(row.expense.id) },
                )
            }
        }
    }
}

/** `WEDNESDAY 13 AUGUST` with the day's subtotal on the right. */
@Composable
private fun DayHeader(day: LedgerDay) {
    val label = remember(day.date) { day.date.format(DAY_FORMAT) }
    SectionHeader(
        text = if (day.date == LocalDate.now()) "Today · $label" else label,
        trailing = {
            MoneyText(
                money = day.total,
                style = KhataTheme.type.caption,
                color = KhataTheme.colors.inkSoft,
            )
        },
    )
}

private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM")

/** Rows, not pixels — one screenful of lead time at a 56 dp row height. */
private const val PREFETCH_DISTANCE = 10
