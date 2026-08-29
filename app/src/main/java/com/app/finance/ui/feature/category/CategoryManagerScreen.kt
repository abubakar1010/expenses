package com.app.finance.ui.feature.category

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.finance.R
import com.app.finance.di.AppContainer
import com.app.finance.di.viewModelFactory
import com.app.finance.domain.model.CategoryNode
import com.app.finance.ui.common.SectionHeader
import com.app.finance.ui.common.MoveActions
import com.app.finance.ui.common.Reorder
import com.app.finance.ui.common.rememberJavaLocale
import com.app.finance.ui.theme.DayBookTheme
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space
import com.app.finance.ui.common.offerUndo
import kotlinx.coroutines.launch

/**
 * The category manager — FR-CAT-03 … FR-CAT-10.
 *
 * `04` §7 describes this screen in nine words and no anatomy exists for it
 * anywhere, so it is assembled from the component system: a `SectionHeader` per
 * root, ledger-style rows for children, and bottom sheets for the two editors.
 *
 * The organising idea is that constraints are **shown, not discovered**. A
 * system root offers no archive action at all; a child offers no "add
 * subcategory"; nothing anywhere offers delete, because archiving is the only
 * removal the product has.
 */
@Composable
fun CategoryManagerScreen(
    container: AppContainer,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
) {
    val vm: CategoryManagerViewModel = viewModel(
        factory = viewModelFactory { CategoryManagerViewModel(container.categoryRepo) },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val locale = rememberJavaLocale()

    val archivedTemplate = stringResource(R.string.category_archived)
    val restoredTemplate = stringResource(R.string.category_restored)
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
                text = stringResource(R.string.categories_title),
                style = DayBookTheme.type.screenTitle,
                color = DayBookTheme.colors.ink,
            )
            TextAction(stringResource(R.string.add_group), onClick = vm::addRoot)
        }

        LazyColumn(Modifier.fillMaxSize()) {
            state.active.forEach { root ->
                item(key = "root-${root.id}") {
                    RootHeader(
                        root = root,
                        onAddChild = { vm.addChild(root) },
                        onRename = { vm.rename(root) },
                        onArchive = {
                            vm.archive(root) { name, changed ->
                                scope.launch {
                                    snackbarHostState.offerUndo(
                                        String.format(locale, archivedTemplate, name),
                                        undoLabel,
                                    ) { vm.undoArchive(changed) }
                                }
                            }
                        },
                    )
                }
                itemsIndexed(
                    root.activeChildren,
                    key = { _, child -> "child-${child.id}" },
                ) { index, child ->
                    CategoryRow(
                        category = child,
                        // FR-CAT-11 is "reordering categories **within their
                        // parent**", so it is offered on children and not on
                        // roots, which have no parent to be within. That also
                        // keeps the root header from carrying five controls on
                        // one line at 1.3x font scale.
                        reorder = Reorder(
                            canMoveUp = index > 0,
                            canMoveDown = index < root.activeChildren.lastIndex,
                            onMoveUp = { vm.move(child, up = true) },
                            onMoveDown = { vm.move(child, up = false) },
                        ),
                        onRename = { vm.rename(child) },
                        trailingAction = stringResource(R.string.archive_category),
                        onTrailing = {
                            vm.archive(child) { name, changed ->
                                scope.launch {
                                    snackbarHostState.offerUndo(
                                        String.format(locale, archivedTemplate, name),
                                        undoLabel,
                                    ) { vm.undoArchive(changed) }
                                }
                            }
                        },
                    )
                }
            }

            // FR-CAT-08 — archived categories are hidden from *entry pickers*,
            // not from the place you go to manage them. They are listed apart
            // with the action that brings them back.
            if (state.archived.isNotEmpty()) {
                item(key = "archived-header") {
                    SectionHeader(stringResource(R.string.archived_section))
                }
                items(state.archived, key = { "archived-${it.node.id}" }) { entry ->
                    CategoryRow(
                        category = entry.node,
                        onRename = null,
                        // Absent, not disabled, for a child whose root is also
                        // archived: restoring the root is what brings it back.
                        trailingAction = stringResource(R.string.restore_category)
                            .takeIf { entry.restorable },
                        onTrailing = {
                            vm.restore(entry.node) { name ->
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        String.format(locale, restoredTemplate, name),
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    state.editor?.let { editor ->
        CategoryEditorSheet(
            editor = editor,
            onName = vm::setName,
            onNature = vm::setNature,
            onSubmit = { vm.submit {} },
            onDismiss = vm::dismissEditor,
        )
    }
}

/**
 * A root. Renameable always (FR-CAT-03), archivable only when it is not a
 * system root — and then the action is absent rather than disabled, because
 * "Delete and archive actions are absent for `is_system = 1` roots".
 */
@Composable
private fun RootHeader(
    root: CategoryNode,
    onAddChild: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
) {
    SectionHeader(
        text = root.name,
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                // FR-CAT-05: adding a child is offered on roots only. A child
                // never shows this, so a third level cannot be attempted.
                TextAction(stringResource(R.string.add_category), onClick = onAddChild)
                TextAction(stringResource(R.string.rename_category), onClick = onRename)
                if (!root.isSystem) {
                    TextAction(
                        text = stringResource(R.string.archive_category),
                        onClick = onArchive,
                        destructive = true,
                    )
                }
            }
        },
    )
}

@Composable
private fun CategoryRow(
    category: CategoryNode,
    onRename: (() -> Unit)?,
    trailingAction: String?,
    onTrailing: () -> Unit,
    reorder: Reorder? = null,
) {
    val colors = DayBookTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Sizes.rowPlain)
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
    ) {
        Text(
            text = category.name,
            style = DayBookTheme.type.body,
            color = if (category.isArchived) colors.inkSoft else colors.ink,
        )
        Box(Modifier.weight(1f))
        // `FlowRow`, because reorder added two more controls to a line that
        // already held two. NFR-COMP-04 asks for no clipping at 320 dp and
        // 1.3x, and four controls plus a category name do not fit on one line
        // there — so they wrap rather than run off the edge.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Space.s3),
            verticalArrangement = Arrangement.Center,
        ) {
            reorder?.let { MoveActions(category.name, it) }
            onRename?.let {
                TextAction(stringResource(R.string.rename_category), onClick = it)
            }
            trailingAction?.let { TextAction(it, onClick = onTrailing) }
        }
    }
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

