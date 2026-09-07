package com.app.finance.ui.feature.budget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.app.finance.R
import com.app.finance.core.money.Money
import com.app.finance.ui.common.KeypadKey
import com.app.finance.ui.common.MoneyText
import com.app.finance.ui.common.NumericKeypad
import com.app.finance.ui.feature.entry.messageRes
import com.app.finance.ui.theme.DayBookTheme
import com.app.finance.ui.theme.Radius
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space

/**
 * Setting a leaf's limit for one period — FR-BUD-01.
 *
 * The same [NumericKeypad] the Quick Add sheet uses, for the same reasons:
 * entering money should feel identical wherever it happens, the pad is instant
 * where the system IME is not, and it sits in the thumb arc. Reusing it also
 * means the 48 dp key targets and spoken key names are already right.
 *
 * `Clear limit` is a text action rather than a second button. There is one
 * primary action here, and removing a limit is not it — but it is the *only*
 * way back to the unbudgeted state, since a limit of zero is refused.
 *
 * FR-BUD-09's note is one tappable line reading *Add note*, or the note itself
 * once there is one — Quick Add's inline sentence, reduced to its single
 * relevant part. It is not a labelled field: a limit with no note is the
 * ordinary case, and a form row sitting empty above the keypad would make the
 * common path look unfinished. Tapping it opens [BudgetNoteSheet] over this
 * one, the way Quick Add reaches its own note editor — the system IME belongs
 * to words, and it must not displace the numeric pad underneath.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LimitSheet(
    editor: LimitEditor,
    onKey: (KeypadKey) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onOpenNote: () -> Unit,
    onNoteDone: (String?) -> Unit,
    onNoteDismiss: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DayBookTheme.colors
    val amount = editor.amount

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
                .padding(bottom = Space.s3),
        ) {
            Text(
                text = stringResource(R.string.set_limit),
                style = DayBookTheme.type.sectionHeader,
                color = colors.inkSoft,
                modifier = Modifier.padding(
                    start = Space.gutter,
                    end = Space.gutter,
                    top = Space.s3,
                ),
            )
            Text(
                text = editor.categoryName,
                style = DayBookTheme.type.screenTitle,
                color = colors.ink,
                modifier = Modifier.padding(horizontal = Space.gutter),
            )

            // Underline only, `indigo` when there is input and `vermilion` on
            // error — 05 §6's input-field spec, the same treatment the amount
            // field in Quick Add uses.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.gutter)
                    .padding(top = Space.s3, bottom = Space.s3)
                    .drawBehind {
                        drawLine(
                            color = when {
                                editor.error != null -> colors.vermilion
                                editor.input.isEmpty() -> colors.rule
                                else -> colors.indigo
                            },
                            start = Offset(0f, size.height + UNDERLINE_GAP),
                            end = Offset(size.width, size.height + UNDERLINE_GAP),
                            strokeWidth = 2f,
                        )
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (amount == null) {
                    Text(
                        text = "${Money.SYMBOL}0",
                        style = DayBookTheme.type.heroFigure.copy(fontSize = AMOUNT_SIZE),
                        color = colors.inkSoft,
                        modifier = Modifier.clearAndSetSemantics {
                            contentDescription = ""
                        },
                    )
                } else {
                    MoneyText(
                        money = amount,
                        style = DayBookTheme.type.heroFigure.copy(fontSize = AMOUNT_SIZE),
                    )
                }
            }

            editor.error?.let { error ->
                Text(
                    text = stringResource(error.messageRes()),
                    style = DayBookTheme.type.caption,
                    color = colors.vermilion,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.gutter, vertical = Space.s1),
                )
            }

            // Reads *Add note*, or the note. Never both, and never beside a
            // label: the text is its own accessible name, exactly as the
            // parts of Quick Add's sentence are.
            Text(
                text = editor.note?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.add_note),
                style = DayBookTheme.type.body,
                color = colors.inkSoft,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.gutter)
                    .defaultMinSize(minHeight = Sizes.minTouchTarget)
                    .clickable(onClick = onOpenNote)
                    .semantics { role = Role.Button }
                    .padding(vertical = Space.s2),
            )

            Button(
                onClick = onSave,
                enabled = editor.canSave,
                shape = RoundedCornerShape(Radius.input),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.indigo,
                    contentColor = colors.card,
                    disabledContainerColor = colors.rule,
                    disabledContentColor = colors.inkSoft,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.gutter, vertical = Space.s2)
                    .height(Sizes.minTouchTarget),
            ) {
                Text(stringResource(R.string.save_limit), style = DayBookTheme.type.body)
            }

            if (editor.existing != null) {
                Text(
                    text = stringResource(R.string.clear_limit),
                    style = DayBookTheme.type.body,
                    color = colors.vermilion,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = Sizes.minTouchTarget)
                        .clickable(onClick = onClear)
                        .semantics { role = Role.Button }
                        .padding(vertical = Space.s2),
                )
            }

            NumericKeypad(onKey = onKey)
        }
    }

    // A sibling rather than a child, which is how Quick Add hosts its own
    // pickers: a sheet is its own window, so one composed inside another
    // would be clipped by it.
    if (editor.noteOpen) {
        BudgetNoteSheet(note = editor.note, onDone = onNoteDone, onDismiss = onNoteDismiss)
    }
}

/**
 * FR-BUD-09's note editor.
 *
 * A near-twin of Quick Add's `NoteSheet` rather than a shared composable, and
 * deliberately so: the two differ in hint and in length, they sit in different
 * features, and the abstraction uniting them would take both of those as
 * parameters — which is the whole of the sharing, for what is otherwise a
 * themed `BasicTextField`.
 *
 * The draft is local state, published on Done. A note abandoned by swiping the
 * sheet away leaves the budget as it was.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BudgetNoteSheet(
    note: String?,
    onDone: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DayBookTheme.colors
    var text by remember { mutableStateOf(note.orEmpty()) }
    val focus = remember { FocusRequester() }
    val hint = stringResource(R.string.budget_note_hint)

    LaunchedEffect(Unit) { focus.requestFocus() }

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
                .padding(Space.gutter),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            BasicTextField(
                value = text,
                onValueChange = { if (it.length <= NOTE_MAX) text = it },
                singleLine = true,
                textStyle = DayBookTheme.type.body.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.indigo),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone(text) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .defaultMinSize(minHeight = Sizes.minTouchTarget)
                    .drawBehind {
                        drawLine(
                            color = colors.indigo,
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
            Button(
                onClick = { onDone(text) },
                shape = RoundedCornerShape(Radius.input),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.indigo,
                    contentColor = colors.card,
                ),
                modifier = Modifier.fillMaxWidth().height(Sizes.minTouchTarget),
            ) { Text(stringResource(R.string.done), style = DayBookTheme.type.body) }
        }
    }
}

private const val UNDERLINE_GAP = 8f
private val AMOUNT_SIZE = 40.sp
/**
 * Shorter than the ledger's 120 (`QuickAddSheet`). A budget note is a reason,
 * not a description of a purchase — but the same argument bounds it: a pasted
 * paragraph should not be storable where only a line is ever shown.
 */
private const val NOTE_MAX = 80

