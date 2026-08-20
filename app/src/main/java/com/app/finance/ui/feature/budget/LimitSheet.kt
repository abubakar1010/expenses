package com.app.finance.ui.feature.budget

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.app.finance.R
import com.app.finance.core.money.Money
import com.app.finance.ui.common.KeypadKey
import com.app.finance.ui.common.MoneyText
import com.app.finance.ui.common.NumericKeypad
import com.app.finance.ui.feature.entry.messageRes
import com.app.finance.ui.theme.KhataTheme
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LimitSheet(
    editor: LimitEditor,
    onKey: (KeypadKey) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KhataTheme.colors
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
                style = KhataTheme.type.sectionHeader,
                color = colors.inkSoft,
                modifier = Modifier.padding(
                    start = Space.gutter,
                    end = Space.gutter,
                    top = Space.s3,
                ),
            )
            Text(
                text = editor.categoryName,
                style = KhataTheme.type.screenTitle,
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
                        style = KhataTheme.type.heroFigure.copy(fontSize = AMOUNT_SIZE),
                        color = colors.inkSoft,
                        modifier = Modifier.clearAndSetSemantics {
                            contentDescription = ""
                        },
                    )
                } else {
                    MoneyText(
                        money = amount,
                        style = KhataTheme.type.heroFigure.copy(fontSize = AMOUNT_SIZE),
                    )
                }
            }

            editor.error?.let { error ->
                Text(
                    text = stringResource(error.messageRes()),
                    style = KhataTheme.type.caption,
                    color = colors.vermilion,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.gutter, vertical = Space.s1),
                )
            }

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
                Text(stringResource(R.string.save_limit), style = KhataTheme.type.body)
            }

            if (editor.existing != null) {
                Text(
                    text = stringResource(R.string.clear_limit),
                    style = KhataTheme.type.body,
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
}

private const val UNDERLINE_GAP = 8f
private val AMOUNT_SIZE = 40.sp
