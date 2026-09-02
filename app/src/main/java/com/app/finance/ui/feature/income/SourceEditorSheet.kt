package com.app.finance.ui.feature.income

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
import com.app.finance.domain.model.IncomeKind
import com.app.finance.ui.common.DayBookChip
import com.app.finance.ui.common.dismissKeyboardOnOutsideGesture
import com.app.finance.ui.feature.entry.messageRes
import com.app.finance.ui.theme.DayBookTheme
import com.app.finance.ui.theme.Radius
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space

/**
 * Creating or renaming an income source — FR-IS-01, FR-IS-02.
 *
 * The kind is offered in **both** cases, which is the one way this differs from
 * the category editor next door. A category's nature is inherited and
 * un-overridable (FR-CAT-06), so offering it on a child would be a lie; a
 * source's kind is its own, and a source that turns out to arrive on a rhythm
 * should be reclassifiable without deleting and re-entering its history. The
 * stable-coverage figure depends on that classification being right, and
 * FR-IS-01 attaches the kind to the source rather than to the moment it was
 * created.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceEditorSheet(
    editor: SourceEditor,
    onName: (String) -> Unit,
    onKind: (IncomeKind) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DayBookTheme.colors
    val focus = remember { FocusRequester() }
    val hint = stringResource(R.string.source_name_hint)

    LaunchedEffect(Unit) { focus.requestFocus() }

    val title = when (editor) {
        is SourceEditor.New -> stringResource(R.string.add_source)
        is SourceEditor.Rename -> stringResource(R.string.rename_source)
    }
    val kind = when (editor) {
        is SourceEditor.New -> editor.kind
        is SourceEditor.Rename -> editor.kind
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
                // A sheet is its own window, so the app root's dismissal does not
                // reach in here.
                .dismissKeyboardOnOutsideGesture()
                .padding(horizontal = Space.gutter)
                .padding(bottom = Space.s3),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            Text(
                text = title,
                style = DayBookTheme.type.screenTitle,
                color = colors.ink,
                modifier = Modifier.padding(top = Space.s3),
            )

            // Underline only, `vermilion` on error with the message below —
            // 05 §6's input-field spec.
            BasicTextField(
                value = editor.name,
                onValueChange = { if (it.length <= NAME_MAX) onName(it) },
                singleLine = true,
                textStyle = DayBookTheme.type.body.copy(color = colors.ink),
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
                            Text(hint, style = DayBookTheme.type.body, color = colors.inkSoft)
                        }
                        inner()
                    }
                },
            )

            editor.error?.let { error ->
                Text(
                    text = stringResource(error.messageRes()),
                    style = DayBookTheme.type.caption,
                    color = colors.vermilion,
                )
            }

            Text(
                text = stringResource(R.string.choose_kind),
                style = DayBookTheme.type.caption,
                color = colors.inkSoft,
            )
            // The hints do the work. "Stable" and "Variable" are the app's
            // words, not the user's, and this choice decides whether the source
            // counts toward the coverage figure for the rest of its life.
            KindChoice(selected = kind, onSelect = onKind)

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
                Text(stringResource(R.string.save_source), style = DayBookTheme.type.body)
            }
        }
    }
}

@Composable
private fun KindChoice(selected: IncomeKind, onSelect: (IncomeKind) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        IncomeKind.entries.forEach { kind ->
            Column {
                DayBookChip(
                    label = stringResource(kind.labelRes()),
                    selected = kind == selected,
                    onClick = { onSelect(kind) },
                )
                Text(
                    text = stringResource(kind.hintRes()),
                    style = DayBookTheme.type.caption,
                    color = DayBookTheme.colors.inkSoft,
                    modifier = Modifier.padding(start = Space.s2, top = Space.s1),
                )
            }
        }
    }
}

private fun IncomeKind.labelRes() = when (this) {
    IncomeKind.STABLE -> R.string.kind_stable
    IncomeKind.VARIABLE -> R.string.kind_variable
}

private fun IncomeKind.hintRes() = when (this) {
    IncomeKind.STABLE -> R.string.kind_stable_hint
    IncomeKind.VARIABLE -> R.string.kind_variable_hint
}

/** Long enough for a real source name, short enough for a 288 dp row. */
private const val NAME_MAX = 40
