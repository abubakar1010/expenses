package com.app.finance.ui.feature.entry

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.finance.R
import com.app.finance.core.money.Money
import com.app.finance.di.AppContainer
import com.app.finance.di.viewModelFactory
import com.app.finance.domain.model.PaymentMethod
import com.app.finance.ui.common.KhataChip
import com.app.finance.ui.common.MoneyText
import com.app.finance.ui.common.NumericKeypad
import com.app.finance.ui.theme.KhataTheme
import com.app.finance.ui.theme.Radius
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space
import java.time.LocalDate

/**
 * Quick Add — 05-ui-ux-guide.md §5.6. The most-used screen in the app.
 *
 * Four decisions create the speed, and all four are visible in this layout:
 *
 * - **A custom keypad, always up.** No system IME, so no 150–300 ms inflation
 *   at exactly the wrong moment.
 * - **Recent categories as chips, not a dropdown.** Spending is habitual; six
 *   chips turn selection from *tap, scroll, find, tap* into one tap.
 * - **Date, method and note are an inline sentence**, not labelled fields. They
 *   read as *Today · Cash · Add note* and each is tappable. A form with five
 *   labelled inputs would be correct, and would also be slow.
 * - **Save is a real button in the thumb zone**, full width at the base.
 *
 * Cancel is a swipe down on the sheet, not a button competing for that space.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddSheet(
    container: AppContainer,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val vm: QuickAddViewModel = viewModel(
        factory = viewModelFactory {
            QuickAddViewModel(
                container.expenseRepo,
                container.categoryRepo,
                container.appMetaRepo,
                container.clock,
            )
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colors = KhataTheme.colors
    val savedMessage = stringResource(R.string.expense_saved)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
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
            AmountField(state)

            Chips(state, vm::selectCategory)

            InlineSentence(
                date = state.date,
                method = state.method,
                note = state.note,
                onMethod = vm::setMethod,
            )

            state.error?.let { error ->
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
                onClick = { vm.save { onSaved(savedMessage) } },
                enabled = state.canSave,
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
                // "A control says what happens" — the button and the snackbar
                // use the same verb (§9).
                Text(stringResource(R.string.save_expense), style = KhataTheme.type.body)
            }

            NumericKeypad(onKey = vm::onKey)
        }
    }
}

/**
 * The live figure. Underline only, `indigo` when there is input — 05 §6 sets
 * input fields as an underline rather than a filled box.
 */
@Composable
private fun AmountField(state: QuickAddUiState) {
    val colors = KhataTheme.colors
    val amount = state.amount

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter)
            .padding(top = Space.s2, bottom = Space.s3)
            .drawBehind {
                drawLine(
                    color = if (state.input.isEmpty()) colors.rule else colors.indigo,
                    start = Offset(0f, size.height + UNDERLINE_GAP),
                    end = Offset(size.width, size.height + UNDERLINE_GAP),
                    strokeWidth = 2f,
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        if (amount == null) {
            Text(
                text = "${Money.SYMBOL}0",
                style = KhataTheme.type.heroFigure.copy(fontSize = 40.sp),
                color = colors.inkSoft,
                textAlign = TextAlign.Start,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = "Amount, empty"
                },
            )
        } else {
            MoneyText(
                money = amount,
                style = KhataTheme.type.heroFigure.copy(fontSize = 40.sp),
            )
        }
    }
}

/** Six chips derived from `app_meta` last-used data — one tap to categorise. */
@Composable
private fun Chips(state: QuickAddUiState, onSelect: (Long) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Space.gutter),
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        items(state.chips, key = { it.id }) { category ->
            KhataChip(
                label = category.name,
                selected = category.id == state.selectedCategoryId,
                onClick = { onSelect(category.id) },
            )
        }
    }
}

/**
 * *Today · Cash · Add note* — pre-filled, tappable, and deliberately not a form.
 */
@Composable
private fun InlineSentence(
    date: LocalDate,
    method: PaymentMethod,
    note: String?,
    onMethod: (PaymentMethod) -> Unit,
) {
    val colors = KhataTheme.colors
    val today = LocalDate.now()

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter, vertical = Space.s1),
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SentencePart(if (date == today) stringResource(R.string.today) else date.toString())
        Dot()
        // Cycles rather than opening a picker: five options, and the common
        // case is already correct from the last-used default.
        SentencePart(
            text = method.label,
            onClick = {
                val next = PaymentMethod.entries[(method.ordinal + 1) % PaymentMethod.entries.size]
                onMethod(next)
            },
        )
        Dot()
        SentencePart(note?.takeIf { it.isNotBlank() } ?: stringResource(R.string.add_note))
    }
}

@Composable
private fun SentencePart(text: String, onClick: (() -> Unit)? = null) {
    Text(
        text = text,
        style = KhataTheme.type.body,
        color = KhataTheme.colors.inkSoft,
        modifier = if (onClick != null) {
            Modifier
                .clickable(onClick = onClick)
                .padding(vertical = Space.s2)
        } else {
            Modifier.padding(vertical = Space.s2)
        },
    )
}

/** The separator between the sentence's clauses. */
@Composable
private fun Dot() {
    Box(
        Modifier
            .size(3.dp)
            .background(KhataTheme.colors.rule, CircleShape),
    )
}

private const val UNDERLINE_GAP = 8f
