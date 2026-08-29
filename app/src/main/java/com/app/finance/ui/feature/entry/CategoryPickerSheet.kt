package com.app.finance.ui.feature.entry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.app.finance.R
import com.app.finance.domain.model.CategoryNode
import com.app.finance.ui.common.EmptyState
import com.app.finance.ui.common.SectionHeader
import com.app.finance.ui.theme.DayBookTheme
import com.app.finance.ui.theme.Radius
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space

/**
 * The full category picker, opened by the `More…` chip — 05 §5.6.
 *
 * Six chips cover most days, but a spending pattern is not six categories
 * forever, and without this sheet every category beyond the sixth is
 * unreachable.
 *
 * FR-EXP-04's acceptance criterion is stated here literally: **"Root categories
 * appear as non-selectable group headers in the picker."** Roots are
 * [SectionHeader]s carrying no click handler and no semantics role, so TalkBack
 * announces them as headings rather than offering them as buttons — which is
 * the accessible form of "not selectable", and stronger than merely ignoring
 * the tap.
 *
 * Archived categories are absent (FR-CAT-08): hidden from entry pickers, still
 * present in the ledger rows that reference them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryPickerSheet(
    tree: List<CategoryNode>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DayBookTheme.colors
    // A root with every child archived is an empty group, not a heading with
    // nothing under it.
    val groups = tree.filter { it.activeChildren.isNotEmpty() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.card,
        shape = Radius.sheetTop,
    ) {
        if (groups.isEmpty()) {
            EmptyState(
                message = stringResource(R.string.empty_categories),
                modifier = Modifier.navigationBarsPadding(),
            )
            return@ModalBottomSheet
        }

        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
            item(key = "title") {
                Text(
                    text = stringResource(R.string.choose_category),
                    style = DayBookTheme.type.screenTitle,
                    color = colors.ink,
                    modifier = Modifier.padding(
                        start = Space.gutter,
                        end = Space.gutter,
                        top = Space.s3,
                    ),
                )
            }

            groups.forEach { root ->
                item(key = "root-${root.id}") {
                    // A heading, not a control. No clickable, no Role.Button.
                    SectionHeader(root.name)
                }
                items(
                    count = root.activeChildren.size,
                    key = { i -> root.activeChildren[i].id },
                ) { i ->
                    val leaf = root.activeChildren[i]
                    CategoryRow(
                        category = leaf,
                        selected = leaf.id == selectedId,
                        onClick = { onSelect(leaf.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(
    category: CategoryNode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = DayBookTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Sizes.minTouchTarget)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .drawBehind {
                drawLine(
                    color = colors.rule,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = Sizes.hairline.toPx(),
                )
            }
            .padding(horizontal = Space.gutter, vertical = Space.s3),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = category.name,
            style = DayBookTheme.type.body,
            // Selection is carried by weight and colour together, so it does
            // not rely on colour alone (NFR-USE-05).
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.indigo else colors.ink,
        )
    }
}
