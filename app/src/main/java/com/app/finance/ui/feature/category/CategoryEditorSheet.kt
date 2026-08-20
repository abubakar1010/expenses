package com.app.finance.ui.feature.category

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.app.finance.R
import com.app.finance.domain.model.Nature
import com.app.finance.ui.common.KhataChip
import com.app.finance.ui.feature.entry.messageRes
import com.app.finance.ui.theme.KhataTheme
import com.app.finance.ui.theme.Radius
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space

/**
 * Creating or renaming a category.
 *
 * One sheet serves all three cases, and what differs between them is exactly
 * what the requirements say should differ:
 *
 * - **A new root** picks a nature (FR-CAT-04).
 * - **A new child** does not, because FR-CAT-06 makes nature inherited and
 *   un-overridable — offering the field and then ignoring it would be worse
 *   than not offering it.
 * - **A rename** touches the name only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryEditorSheet(
    editor: CategoryEditor,
    onName: (String) -> Unit,
    onNature: (Nature) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KhataTheme.colors
    val focus = remember { FocusRequester() }
    val hint = stringResource(R.string.category_name_hint)

    LaunchedEffect(Unit) { focus.requestFocus() }

    val title = when (editor) {
        is CategoryEditor.NewRoot -> stringResource(R.string.add_group)
        is CategoryEditor.NewChild -> stringResource(R.string.add_to_group, editor.parentName)
        is CategoryEditor.Rename -> stringResource(R.string.rename_category)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.card,
        shape = Radius.sheetTop,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Space.gutter)
                .padding(bottom = Space.s3),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            Text(
                text = title,
                style = KhataTheme.type.screenTitle,
                color = colors.ink,
                modifier = Modifier.padding(top = Space.s3),
            )

            // Underline only, `vermilion` on error with the message below —
            // 05 §6's input-field spec.
            BasicTextField(
                value = editor.name,
                onValueChange = { if (it.length <= NAME_MAX) onName(it) },
                singleLine = true,
                textStyle = KhataTheme.type.body.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.indigo),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .defaultMinSize(minHeight = Sizes.minTouchTarget)
                    .drawBehind {
                        drawLine(
                            color = when {
                                editor.error != null -> colors.vermilion
                                editor.name.isEmpty() -> colors.rule
                                else -> colors.indigo
                            },
                            start = Offset(0f, size.height),
                            end = Offset(size.width, size.height),
                            strokeWidth = 2f,
                        )
                    },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (editor.name.isEmpty()) {
                            Text(hint, style = KhataTheme.type.body, color = colors.inkSoft)
                        }
                        inner()
                    }
                },
            )

            editor.error?.let { error ->
                Text(
                    text = stringResource(error.messageRes()),
                    style = KhataTheme.type.caption,
                    color = colors.vermilion,
                )
            }

            if (editor is CategoryEditor.NewRoot) {
                Text(
                    text = stringResource(R.string.choose_nature),
                    style = KhataTheme.type.caption,
                    color = colors.inkSoft,
                )
                // The hint under each is the whole point: "fixed / variable /
                // unpredictable" are the app's words, not the user's, and the
                // choice decides how the category behaves for the rest of its
                // life (FR-BUD-07, safe-to-spend at M4).
                NatureChoice(selected = editor.nature, onSelect = onNature)
            }

            Button(
                onClick = onSubmit,
                enabled = editor.name.isNotBlank(),
                shape = RoundedCornerShape(Radius.input),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.indigo,
                    contentColor = colors.card,
                    disabledContainerColor = colors.rule,
                    disabledContentColor = colors.inkSoft,
                ),
                modifier = Modifier.fillMaxWidth().height(Sizes.minTouchTarget),
            ) {
                Text(stringResource(R.string.save_category), style = KhataTheme.type.body)
            }
        }
    }
}

@Composable
private fun NatureChoice(selected: Nature, onSelect: (Nature) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        Nature.entries.forEach { nature ->
            Column {
                KhataChip(
                    label = stringResource(nature.labelRes()),
                    selected = nature == selected,
                    onClick = { onSelect(nature) },
                )
                Text(
                    text = stringResource(nature.hintRes()),
                    style = KhataTheme.type.caption,
                    color = KhataTheme.colors.inkSoft,
                    modifier = Modifier.padding(start = Space.s2, top = Space.s1),
                )
            }
        }
    }
}

private fun Nature.labelRes() = when (this) {
    Nature.FIXED -> R.string.nature_fixed
    Nature.VARIABLE -> R.string.nature_variable
    Nature.UNPREDICTABLE -> R.string.nature_unpredictable
}

private fun Nature.hintRes() = when (this) {
    Nature.FIXED -> R.string.nature_fixed_hint
    Nature.VARIABLE -> R.string.nature_variable_hint
    Nature.UNPREDICTABLE -> R.string.nature_unpredictable_hint
}

/** Long enough for a real category name, short enough for a 288 dp row. */
private const val NAME_MAX = 40
