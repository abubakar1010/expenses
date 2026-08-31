package com.app.finance.ui.feature.people

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.finance.R
import com.app.finance.core.money.Money
import com.app.finance.data.db.dao.PersonBalanceRow
import com.app.finance.di.AppContainer
import com.app.finance.di.viewModelFactory
import com.app.finance.ui.common.EmptyState
import com.app.finance.ui.common.LedgerRow
import com.app.finance.ui.common.MoneyText
import com.app.finance.ui.common.SectionHeader
import com.app.finance.ui.common.offerUndo
import com.app.finance.ui.common.rememberJavaLocale
import com.app.finance.ui.theme.DayBookTheme
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space
import kotlinx.coroutines.launch

/**
 * Who owes you, and whom you owe — FR-SHR-05.
 *
 * Two sections in one `LazyColumn`, the idiom both manager screens already use.
 * **Somebody square appears in neither**: a balance of zero is a settled
 * account, and listing it would bury the two names that need acting on among a
 * dozen that do not.
 *
 * Modelled on `SourceManagerScreen` rather than `CategoryManagerScreen`,
 * because a person can be deleted — just not one who appears in an expense. So
 * the control is present and disabled with a spoken reason rather than absent,
 * which is the exception 05 §9 and that screen already document.
 */
@Composable
fun PeopleScreen(
    container: AppContainer,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
) {
    val vm: PeopleViewModel = viewModel(
        factory = viewModelFactory {
            PeopleViewModel(container.personRepo, container.settlementRepo)
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val today = java.time.LocalDate.now(container.clock)

    // Hoisted: a composable cannot be called inside `scope.launch`.
    val settlementRemoved = stringResource(R.string.settlement_removed)
    val undoLabel = stringResource(R.string.undo)

    BackHandler(onBack = onBack)

    // NFR-USE-03. Keyed on the head's id, never the object — the queue is what
    // keeps a second deletion inside the window from cancelling the first
    // effect without running either branch.
    val nextUndo = state.undoQueue.firstOrNull()
    LaunchedEffect(nextUndo?.id) {
        val item = nextUndo ?: return@LaunchedEffect
        snackbarHostState.offerUndo(
            message = settlementRemoved,
            undoLabel = undoLabel,
            onUndo = { vm.undo(item.id) },
            onExpired = { vm.dropUndo(item.id) },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.gutter, vertical = Space.s2),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.people_title),
                style = DayBookTheme.type.screenTitle,
                color = DayBookTheme.colors.ink,
            )
            TextAction(stringResource(R.string.add_person), vm::addPerson)
        }

        if (state.isEmpty) {
            EmptyState(
                message = stringResource(R.string.empty_people),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            PeopleList(state = state, onSettle = vm::settleUp, onRename = vm::rename)
        }
    }

    state.editor?.let { editor ->
        PersonEditorSheet(
            editor = editor,
            onName = vm::setEditorName,
            onSubmit = vm::submitEditor,
            onDismiss = vm::dismissEditor,
        )
    }

    state.settle?.let { settle ->
        SettleSheet(
            editor = settle,
            onAmount = vm::setSettleAmount,
            onDirection = vm::setSettleDirection,
            onSubmit = { vm.submitSettle(today) },
            onDismiss = vm::dismissSettle,
        )
    }
}

@Composable
private fun PeopleList(
    state: PeopleUiState,
    onSettle: (PersonBalanceRow) -> Unit,
    onRename: (PersonBalanceRow) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (state.owedToYou.isNotEmpty()) {
            item(key = "owed-header") {
                SectionHeader(
                    text = stringResource(R.string.owed_to_you),
                    trailing = { SectionTotal(state.totalOwedToYou) },
                )
            }
            items(state.owedToYou, key = { "owed-${it.personId}" }) { row ->
                PersonRow(row, onSettle, onRename)
            }
        }

        if (state.youOwe.isNotEmpty()) {
            item(key = "owe-header") {
                SectionHeader(
                    text = stringResource(R.string.you_owe),
                    trailing = { SectionTotal(state.totalYouOwe) },
                )
            }
            items(state.youOwe, key = { "owe-${it.personId}" }) { row ->
                PersonRow(row, onSettle, onRename)
            }
        }

        if (state.settled.isNotEmpty()) {
            item(key = "settled-header") {
                SectionHeader(text = stringResource(R.string.all_settled))
            }
            items(state.settled, key = { "settled-${it.personId}" }) { row ->
                PersonRow(row, onSettle, onRename)
            }
        }
    }
}

@Composable
private fun SectionTotal(money: Money) {
    MoneyText(
        money = money,
        style = DayBookTheme.type.caption,
        color = DayBookTheme.colors.inkSoft,
    )
}

@Composable
private fun PersonRow(
    row: PersonBalanceRow,
    onSettle: (PersonBalanceRow) -> Unit,
    onRename: (PersonBalanceRow) -> Unit,
) {
    val locale = rememberJavaLocale()
    LedgerRow(
        label = row.personName,
        // Signed, and `MoneyText` already renders a negative in `vermilion`
        // with a true minus and speaks it as words — so the direction reads
        // correctly with no extra work here.
        amount = Money(row.balanceMinor),
        secondary = when {
            row.balanceMinor > 0 -> stringResource(R.string.owes_you)
            row.balanceMinor < 0 -> stringResource(R.string.you_owe_them)
            else -> stringResource(R.string.square)
        },
        trailing = if (row.archived) stringResource(R.string.archived) else null,
        onClick = { onSettle(row) },
    )
}

/**
 * The house style: every manager screen declares its own, rather than sharing
 * one. Four screens do it this way already.
 */
@Composable
private fun TextAction(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = DayBookTheme.type.body,
        color = DayBookTheme.colors.indigo,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .defaultMinSize(minWidth = Sizes.minTouchTarget, minHeight = Sizes.minTouchTarget)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = Space.s1),
    )
}
