package com.app.finance.ui.feature.income

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.finance.R
import com.app.finance.data.db.dao.SourceWithCount
import com.app.finance.di.AppContainer
import com.app.finance.di.viewModelFactory
import com.app.finance.domain.model.IncomeKind
import com.app.finance.ui.common.SectionHeader
import com.app.finance.ui.common.MoveActions
import com.app.finance.ui.common.Reorder
import com.app.finance.ui.common.rememberJavaLocale
import com.app.finance.ui.theme.KhataTheme
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space
import com.app.finance.ui.common.offerUndo
import kotlinx.coroutines.launch

/**
 * Income sources — FR-IS-01 … FR-IS-06.
 *
 * `04` §7 lists this as its own screen ("Income Source Editor: create, rename,
 * set kind, archive"), so it is a detail route off Income exactly as the
 * category manager is off Budget, and assembled from the same components.
 *
 * **This is the one screen that shows a constraint by disabling rather than by
 * absence.** The category manager's rule — an action that cannot be taken is
 * not offered — is right there because no category can *ever* be deleted, so an
 * absent action teaches a true rule. Here a source can be deleted, just not one
 * with entries, and hiding the action would teach a false one. FR-IS-05's
 * acceptance criterion asks for exactly this: "disabled with an explanatory
 * message offering Archive instead".
 */
@Composable
fun SourceManagerScreen(
    container: AppContainer,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
) {
    val vm: SourceManagerViewModel = viewModel(
        factory = viewModelFactory { SourceManagerViewModel(container.incomeRepo) },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val locale = rememberJavaLocale()

    val archivedTemplate = stringResource(R.string.source_archived)
    val restoredTemplate = stringResource(R.string.source_restored)
    val deletedTemplate = stringResource(R.string.source_deleted)
    val undoLabel = stringResource(R.string.undo)

    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.gutter, vertical = Space.s2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.sources_title),
                style = KhataTheme.type.screenTitle,
                color = KhataTheme.colors.ink,
            )
            TextAction(stringResource(R.string.add_source), onClick = vm::add)
        }

        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(
                state.active,
                key = { _, row -> "source-${row.source.id}" },
            ) { index, row ->
                SourceManagerRow(
                    row = row,
                    // FR-IS-07 — a flat list, so every active source can move.
                    reorder = Reorder(
                        canMoveUp = index > 0,
                        canMoveDown = index < state.active.lastIndex,
                        onMoveUp = { vm.move(row, up = true) },
                        onMoveDown = { vm.move(row, up = false) },
                    ),
                    onRename = { vm.rename(row) },
                    onArchive = {
                        vm.setArchived(row, archived = true) { name ->
                            scope.launch {
                                snackbarHostState.offerUndo(
                                    String.format(locale, archivedTemplate, name),
                                    undoLabel,
                                ) { vm.setArchived(row, archived = false) {} }
                            }
                        }
                    },
                    onDelete = {
                        vm.delete(row) { name, source ->
                            scope.launch {
                                snackbarHostState.offerUndo(
                                    String.format(locale, deletedTemplate, name),
                                    undoLabel,
                                ) { vm.undoDelete(source) }
                            }
                        }
                    },
                )
            }

            // FR-IS-04 — archived sources are excluded from *entry pickers*,
            // not from the place you go to manage them, and they stay in every
            // historical breakdown.
            if (state.archived.isNotEmpty()) {
                item(key = "archived-header") {
                    SectionHeader(stringResource(R.string.archived_section))
                }
                items(state.archived, key = { "archived-${it.source.id}" }) { row ->
                    SourceManagerRow(
                        row = row,
                        onRename = null,
                        onRestore = {
                            vm.setArchived(row, archived = false) { name ->
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        String.format(locale, restoredTemplate, name),
                                    )
                                }
                            }
                        },
                        onDelete = {
                            vm.delete(row) { name, source ->
                                scope.launch {
                                    snackbarHostState.offerUndo(
                                        String.format(locale, deletedTemplate, name),
                                        undoLabel,
                                    ) { vm.undoDelete(source) }
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    state.editor?.let { editor ->
        SourceEditorSheet(
            editor = editor,
            onName = vm::setName,
            onKind = vm::setKind,
            onSubmit = { vm.submit {} },
            onDismiss = vm::dismissEditor,
        )
    }
}

/**
 * `Salary · Stable · 14 entries` with its actions.
 *
 * The entry count is not decoration: it is the reason the delete is live or
 * not, so it is shown next to the control it governs rather than left for the
 * user to infer from a disabled state with no cause.
 */
@Composable
private fun SourceManagerRow(
    row: SourceWithCount,
    onRename: (() -> Unit)?,
    onArchive: (() -> Unit)? = null,
    onRestore: (() -> Unit)? = null,
    onDelete: () -> Unit,
    reorder: Reorder? = null,
) {
    val colors = KhataTheme.colors
    val kindLabel = stringResource(
        if (row.source.kind == IncomeKind.STABLE.code) R.string.kind_stable
        else R.string.kind_variable,
    )
    val countLabel = pluralStringResource(
        R.plurals.source_entry_count,
        row.entryCount,
        row.entryCount,
    )

    Column(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Sizes.rowWithBar)
            .drawBehind {
                drawLine(
                    color = colors.rule,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = Sizes.hairline.toPx(),
                )
            }
            .padding(horizontal = Space.gutter, vertical = Space.s2),
        verticalArrangement = Arrangement.spacedBy(Space.s1),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.source.name,
                style = KhataTheme.type.body,
                color = if (row.source.isArchived) colors.inkSoft else colors.ink,
            )
            Box(Modifier.weight(1f))
            Text(
                text = "$kindLabel · $countLabel",
                style = KhataTheme.type.caption,
                color = colors.inkSoft,
            )
        }

        // `FlowRow` for the same reason the category rows use one: this line
        // already carried three controls and reorder makes five, which do not
        // fit at 320 dp and 1.3x (NFR-COMP-04).
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Space.s3),
            verticalArrangement = Arrangement.Center,
        ) {
            reorder?.let { MoveActions(row.source.name, it) }
            onRename?.let { TextAction(stringResource(R.string.rename_source), onClick = it) }
            onArchive?.let {
                TextAction(stringResource(R.string.archive_source), onClick = it, destructive = true)
            }
            onRestore?.let { TextAction(stringResource(R.string.restore_source), onClick = it) }

            // FR-IS-05 / FR-IS-06. Present either way; live only when nothing
            // holds it. Two things can: entries, and a repeating entry — both
            // `ON DELETE RESTRICT`, and each gets its own sentence, because
            // "this source has entries" would send the user looking for
            // entries that are not there. The reason is spoken as well as
            // shown: a disabled control with no announced cause is worse than
            // an absent one.
            DeleteAction(
                enabled = row.entryCount == 0 && row.ruleCount == 0,
                reason = stringResource(
                    if (row.entryCount > 0) R.string.error_source_has_entries
                    else R.string.error_source_has_rules,
                ),
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun DeleteAction(enabled: Boolean, reason: String, onClick: () -> Unit) {
    val colors = KhataTheme.colors
    val label = stringResource(R.string.delete_source)

    Text(
        text = label,
        style = KhataTheme.type.caption,
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

/** A text control with a 48 dp target — there are no icon buttons here. */
@Composable
private fun TextAction(
    text: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    Text(
        text = text,
        style = KhataTheme.type.caption,
        color = if (destructive) KhataTheme.colors.vermilion else KhataTheme.colors.indigo,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .defaultMinSize(minHeight = Sizes.minTouchTarget)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(vertical = Space.s2),
    )
}

