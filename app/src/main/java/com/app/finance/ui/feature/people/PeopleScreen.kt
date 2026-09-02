package com.app.finance.ui.feature.people

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.finance.R
import com.app.finance.core.money.Money
import com.app.finance.data.db.dao.PersonBalanceRow
import com.app.finance.di.AppContainer
import com.app.finance.di.viewModelFactory
import com.app.finance.ui.common.DetailHeader
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
    val locale = rememberJavaLocale()
    val today = java.time.LocalDate.now(container.clock)

    // Hoisted: a composable cannot be called inside `scope.launch`.
    val settlementRemoved = stringResource(R.string.settlement_removed)
    val archivedTemplate = stringResource(R.string.person_archived)
    val restoredTemplate = stringResource(R.string.person_restored)
    val deletedTemplate = stringResource(R.string.person_deleted)
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
        DetailHeader(
            title = stringResource(R.string.people_title),
            onBack = onBack,
            trailing = { TextAction(stringResource(R.string.add_person), vm::addPerson) },
        )

        if (state.isEmpty) {
            EmptyState(
                message = stringResource(R.string.empty_people),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            PeopleList(
                state = state,
                onSettle = vm::settleUp,
                onRename = vm::rename,
                onArchive = { row ->
                    vm.setArchived(row.personId, archived = true) {
                        scope.launch {
                            snackbarHostState.offerUndo(
                                String.format(locale, archivedTemplate, row.personName),
                                undoLabel,
                            ) { vm.setArchived(row.personId, archived = false) }
                        }
                    }
                },
                onRestore = { row ->
                    vm.setArchived(row.personId, archived = false) {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                String.format(locale, restoredTemplate, row.personName),
                            )
                        }
                    }
                },
                onDelete = { row ->
                    vm.delete(row.personId, onDeleted = { person ->
                        scope.launch {
                            snackbarHostState.offerUndo(
                                String.format(locale, deletedTemplate, person.name),
                                undoLabel,
                            ) { vm.undoDelete(person) }
                        }
                    })
                },
            )
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
    onArchive: (PersonBalanceRow) -> Unit,
    onRestore: (PersonBalanceRow) -> Unit,
    onDelete: (PersonBalanceRow) -> Unit,
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
                PersonRow(row, onSettle, onRename, onArchive, onRestore, onDelete)
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
                PersonRow(row, onSettle, onRename, onArchive, onRestore, onDelete)
            }
        }

        if (state.settled.isNotEmpty()) {
            item(key = "settled-header") {
                SectionHeader(text = stringResource(R.string.all_settled))
            }
            items(state.settled, key = { "settled-${it.personId}" }) { row ->
                PersonRow(row, onSettle, onRename, onArchive, onRestore, onDelete)
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

/**
 * One person, their balance, and what can be done about them — FR-SHR-01.
 *
 * The four actions under the row are `SourceManagerScreen`'s, for the reason
 * that screen's own comment gives and this one's header already claimed: a
 * person *can* be deleted, just not one who appears in an expense, so hiding
 * the control would teach a false rule. They were claimed and never drawn —
 * rename, archive, restore and delete were all implemented in
 * [PeopleViewModel] and reachable from nothing, and the only thing this screen
 * could do to a name was open the settle sheet.
 */
@Composable
private fun PersonRow(
    row: PersonBalanceRow,
    onSettle: (PersonBalanceRow) -> Unit,
    onRename: (PersonBalanceRow) -> Unit,
    onArchive: (PersonBalanceRow) -> Unit,
    onRestore: (PersonBalanceRow) -> Unit,
    onDelete: (PersonBalanceRow) -> Unit,
) {
    Column {
        LedgerRow(
            label = row.personName,
            // Signed, and `MoneyText` already renders a negative in `vermilion`
            // with a true minus and speaks it as words — so the direction reads
            // correctly with no extra work here.
            amount = Money(row.balanceMinor),
            // No direction caption. The section header above already says which way
            // this points, and the sign and colour say it again — a row reading
            // "You owe" under a heading reading "You owe" is one repetition too
            // many, and it made "You owe" ambiguous to anything looking for it.
            secondary = if (row.balanceMinor == 0L) stringResource(R.string.square) else null,
            trailing = if (row.archived) stringResource(R.string.archived) else null,
            onClick = { onSettle(row) },
        )

        // `FlowRow` for the reason the source rows use one: four controls do
        // not fit at 320 dp and 1.3x (NFR-COMP-04).
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Space.s3),
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = Space.gutter),
        ) {
            RowAction(stringResource(R.string.settle_up)) { onSettle(row) }
            RowAction(stringResource(R.string.rename_person)) { onRename(row) }
            if (row.archived) {
                RowAction(stringResource(R.string.restore_person)) { onRestore(row) }
            } else {
                RowAction(
                    stringResource(R.string.archive_person),
                    destructive = true,
                ) { onArchive(row) }
            }

            // FR-SHR-01's delete. Present either way; live only for somebody
            // nothing references — the repository re-checks and the foreign
            // keys are `ON DELETE RESTRICT` behind that, so the disabled state
            // is a courtesy rather than the enforcement. The reason is spoken
            // as well as shown: a disabled control with no announced cause is
            // worse than an absent one.
            DeleteAction(
                enabled = !row.hasHistory,
                reason = stringResource(R.string.error_person_has_history),
                onClick = { onDelete(row) },
            )
        }
    }
}

@Composable
private fun DeleteAction(enabled: Boolean, reason: String, onClick: () -> Unit) {
    val colors = DayBookTheme.colors
    val label = stringResource(R.string.delete_person)

    Text(
        text = label,
        style = DayBookTheme.type.caption,
        color = if (enabled) colors.vermilion else colors.rule,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .defaultMinSize(minHeight = Sizes.minTouchTarget)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics {
                role = Role.Button
                if (!enabled) {
                    disabled()
                    contentDescription = "$label. $reason"
                }
            }
            .padding(vertical = Space.s2),
    )
}

/** A row-level text control with a 48 dp target — `SourceManagerScreen`'s. */
@Composable
private fun RowAction(text: String, destructive: Boolean = false, onClick: () -> Unit) {
    Text(
        text = text,
        style = DayBookTheme.type.caption,
        color = if (destructive) DayBookTheme.colors.vermilion else DayBookTheme.colors.indigo,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .defaultMinSize(minHeight = Sizes.minTouchTarget)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(vertical = Space.s2),
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
