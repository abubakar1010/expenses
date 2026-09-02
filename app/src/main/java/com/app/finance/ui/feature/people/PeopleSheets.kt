package com.app.finance.ui.feature.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import com.app.finance.core.money.Money
import com.app.finance.ui.common.DayBookChip
import com.app.finance.ui.common.KeypadKey
import com.app.finance.ui.common.MoneyText
import com.app.finance.ui.common.NumericKeypad
import com.app.finance.ui.common.dismissKeyboardOnOutsideGesture
import com.app.finance.ui.feature.entry.messageRes
import com.app.finance.ui.theme.DayBookTheme
import com.app.finance.ui.theme.Radius
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space

/** Adding or renaming somebody — FR-SHR-01. `CategoryEditorSheet`'s shape. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonEditorSheet(
    editor: PersonEditor,
    onName: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DayBookTheme.colors
    val focus = remember { FocusRequester() }
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
                // A sheet is its own window, so the app root's dismissal does not
                // reach in here.
                .dismissKeyboardOnOutsideGesture()
                .padding(horizontal = Space.gutter),
        ) {
            Text(
                text = stringResource(
                    if (editor is PersonEditor.Rename) R.string.rename_person
                    else R.string.add_person,
                ),
                style = DayBookTheme.type.screenTitle,
                color = colors.ink,
                modifier = Modifier.padding(vertical = Space.s2),
            )

            BasicTextField(
                value = editor.name,
                onValueChange = { if (it.length <= NAME_MAX) onName(it) },
                singleLine = true,
                textStyle = DayBookTheme.type.body.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.indigo),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .height(Sizes.minTouchTarget)
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
            )

            // Field-level, beneath the input — not a snackbar. The user is
            // looking at the thing that was wrong.
            editor.error?.let {
                Text(
                    text = stringResource(it.messageRes()),
                    style = DayBookTheme.type.caption,
                    color = colors.vermilion,
                    modifier = Modifier.padding(top = Space.s1),
                )
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Space.s3)
                    .height(Sizes.minTouchTarget),
            ) {
                Text(stringResource(R.string.done), style = DayBookTheme.type.body)
            }
        }
    }
}

/**
 * Recording money moving — FR-SHR-04.
 *
 * The same sheet records a repayment and a loan made outright, because they are
 * the same row with the sign reversed. Direction is a control rather than being
 * inferred, since "I lent Rahim ৳500" and "Rahim paid me ৳500" are both things
 * that happen to a person who currently owes you nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettleSheet(
    editor: SettleEditor,
    onAmount: (String) -> Unit,
    onDirection: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DayBookTheme.colors

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
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = editor.personName,
                style = DayBookTheme.type.screenTitle,
                color = colors.ink,
                modifier = Modifier.padding(vertical = Space.s2),
            )

            MoneyText(
                money = editor.amount ?: Money.ZERO,
                style = DayBookTheme.type.heroFigure,
                color = colors.ink,
            )

            Row(
                Modifier.padding(vertical = Space.s2),
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
            ) {
                DayBookChip(
                    label = stringResource(R.string.they_paid_me),
                    selected = editor.theyPay,
                    onClick = { onDirection(true) },
                )
                DayBookChip(
                    label = stringResource(R.string.i_paid_them),
                    selected = !editor.theyPay,
                    onClick = { onDirection(false) },
                )
            }

            editor.error?.let {
                Text(
                    text = stringResource(it.messageRes()),
                    style = DayBookTheme.type.caption,
                    color = colors.vermilion,
                    modifier = Modifier.padding(horizontal = Space.gutter),
                )
            }

            Button(
                onClick = onSubmit,
                enabled = editor.amount != null,
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
                Text(stringResource(R.string.record_settlement), style = DayBookTheme.type.body)
            }

            // Every amount in this app goes through the same keypad.
            NumericKeypad(onKey = { key -> onAmount(editor.input.apply(key)) })
        }
    }
}

/** The keypad's edit, applied to the raw text. Mirrors the entry sheet's. */
private fun String.apply(key: KeypadKey): String = when (key) {
    is KeypadKey.Digit -> if (this == "0") key.value.toString() else this + key.value
    KeypadKey.DoubleZero -> if (isEmpty() || this == "0") this else this + "00"
    KeypadKey.Decimal -> if (isEmpty() || contains('.')) this else "$this."
    KeypadKey.Backspace -> dropLast(1)
    // A settlement's direction is a control on this sheet, so the keypad's
    // sign key would be a second way to say the same thing — and the two could
    // disagree.
    KeypadKey.Negate -> this
}

private const val NAME_MAX = 40
