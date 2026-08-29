package com.app.finance.ui.feature.entry

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.finance.R
import com.app.finance.core.money.Money
import com.app.finance.di.AppContainer
import com.app.finance.di.viewModelFactory
import com.app.finance.domain.model.PaymentMethod
import com.app.finance.ui.common.DayBookChip
import com.app.finance.ui.common.MoneyText
import com.app.finance.ui.common.NumericKeypad
import com.app.finance.ui.common.labelRes
import com.app.finance.ui.theme.DayBookTheme
import com.app.finance.ui.theme.Radius
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Quick Add — 05-ui-ux-guide.md §5.6. The most-used screen in the app, and the
 * edit surface for an existing expense (FR-EXP-07).
 *
 * Four decisions create the speed, and all four are visible in this layout:
 *
 * - **A custom keypad, always up.** No system IME, so no 150–300 ms inflation
 *   at exactly the wrong moment.
 * - **Recent categories as chips.** Six chips turn selection from *tap, scroll,
 *   find, tap* into one tap; `More…` opens the full picker for the rest.
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
    editingExpenseId: Long? = null,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
    onDeleted: (Long) -> Unit,
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
    val colors = DayBookTheme.colors
    val savedMessage = stringResource(R.string.expense_saved)

    // Seeds a new entry or loads the row being edited. The ViewModel's owner is
    // the Activity, so without this the sheet would reopen showing whatever was
    // typed last time.
    LaunchedEffect(editingExpenseId) { vm.start(editingExpenseId) }

    ModalBottomSheet(
        onDismissRequest = { vm.reset(); onDismiss() },
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

            Chips(
                state = state,
                onSelect = vm::selectCategory,
                onMore = { vm.openSheet(EntrySheet.CATEGORY) },
            )

            InlineSentence(
                state = state,
                onDate = { vm.openSheet(EntrySheet.DATE) },
                onMethod = { vm.openSheet(EntrySheet.METHOD) },
                onNote = { vm.openSheet(EntrySheet.NOTE) },
            )

            state.error?.let { error ->
                Text(
                    text = stringResource(error.messageRes()),
                    style = DayBookTheme.type.caption,
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
                // "A control says what happens" — this button and the snackbar
                // that follows it use the same verb (§9).
                Text(stringResource(R.string.save_expense), style = DayBookTheme.type.body)
            }

            if (state.isEditing) {
                // A text action, not a second filled button. There is exactly
                // one primary action on this sheet, and delete is not it.
                Text(
                    text = stringResource(R.string.delete_expense),
                    style = DayBookTheme.type.body,
                    color = colors.vermilion,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = Sizes.minTouchTarget)
                        .clickable { vm.delete(onDeleted) }
                        .semantics { role = Role.Button }
                        .padding(vertical = Space.s2),
                )
            }

            NumericKeypad(onKey = vm::onKey)
        }
    }

    when (state.openSheet) {
        EntrySheet.CATEGORY -> CategoryPickerSheet(
            tree = state.tree,
            selectedId = state.selectedCategoryId,
            onSelect = vm::selectCategory,
            onDismiss = vm::dismissSheet,
        )

        EntrySheet.METHOD -> MethodPickerSheet(
            selected = state.method,
            onSelect = vm::setMethod,
            onDismiss = vm::dismissSheet,
        )

        EntrySheet.DATE -> DatePickerSheet(
            date = state.date,
            today = state.today,
            onPick = vm::setDate,
            onDismiss = vm::dismissSheet,
        )

        EntrySheet.NOTE -> NoteSheet(
            note = state.note,
            onDone = { vm.setNote(it); vm.dismissSheet() },
            onDismiss = vm::dismissSheet,
        )

        EntrySheet.NONE -> Unit
    }
}

/**
 * The live figure. Underline only, `indigo` when there is input and `vermilion`
 * on error, per 05 §6's input-field spec.
 */
@Composable
private fun AmountField(state: QuickAddUiState) {
    val colors = DayBookTheme.colors
    val amount = state.amount
    val emptyLabel = stringResource(R.string.amount_empty)

    val underline = when {
        state.error != null -> colors.vermilion
        state.input.isEmpty() -> colors.rule
        else -> colors.indigo
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter)
            .padding(top = Space.s2, bottom = Space.s3)
            .drawBehind {
                drawLine(
                    color = underline,
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
                modifier = Modifier.clearAndSetSemantics { contentDescription = emptyLabel },
            )
        } else {
            MoneyText(
                money = amount,
                style = DayBookTheme.type.heroFigure.copy(fontSize = AMOUNT_SIZE),
            )
        }
        // "Cursor live on open" (§5.6). The keypad is always up, so there is no
        // focus to show — this is what tells the user where the digits land.
        Caret()
    }
}

@Composable
private fun Caret() {
    Box(
        Modifier
            .padding(start = 2.dp)
            .size(width = 2.dp, height = 34.dp)
            .background(DayBookTheme.colors.indigo)
            .clearAndSetSemantics {},
    )
}

/** Six recent chips plus `More…` — 05 §5.6. */
@Composable
private fun Chips(
    state: QuickAddUiState,
    onSelect: (Long) -> Unit,
    onMore: () -> Unit,
) {
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Space.gutter),
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        items(state.chips, key = { it.id }) { category ->
            DayBookChip(
                label = category.name,
                selected = category.id == state.selectedCategoryId,
                onClick = { onSelect(category.id) },
            )
        }
        item(key = "more") {
            DayBookChip(
                label = stringResource(R.string.more_categories),
                selected = false,
                onClick = onMore,
            )
        }
    }
}

/** *Today · Cash · Add note* — pre-filled, tappable, deliberately not a form. */
@Composable
private fun InlineSentence(
    state: QuickAddUiState,
    onDate: () -> Unit,
    onMethod: () -> Unit,
    onNote: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter, vertical = Space.s1),
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SentencePart(state.date.relativeLabel(state.today), onDate)
        Dot()
        SentencePart(stringResource(state.method.labelRes()), onMethod)
        Dot()
        SentencePart(
            text = state.note?.takeIf { it.isNotBlank() } ?: stringResource(R.string.add_note),
            onClick = onNote,
        )
    }
}

@Composable
private fun SentencePart(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = DayBookTheme.type.body,
        color = DayBookTheme.colors.inkSoft,
        modifier = Modifier
            .defaultMinSize(minHeight = Sizes.minTouchTarget)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(vertical = Space.s2),
    )
}

@Composable
private fun Dot() {
    Box(
        Modifier
            .size(3.dp)
            .background(DayBookTheme.colors.rule, CircleShape)
            .clearAndSetSemantics {},
    )
}

/** FR-EXP-05. A list, not a cycle — *Other* was six taps away. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MethodPickerSheet(
    selected: PaymentMethod,
    onSelect: (PaymentMethod) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DayBookTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.card,
        shape = Radius.sheetTop,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = Space.s3)) {
            Text(
                text = stringResource(R.string.choose_method),
                style = DayBookTheme.type.screenTitle,
                color = colors.ink,
                modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s3),
            )
            PaymentMethod.SELECTABLE.forEach { method ->
                val isSelected = method == selected
                Text(
                    text = stringResource(method.labelRes()),
                    style = DayBookTheme.type.body,
                    color = if (isSelected) colors.indigo else colors.ink,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = Sizes.minTouchTarget)
                        .clickable { onSelect(method) }
                        .semantics { role = Role.Button }
                        .padding(horizontal = Space.gutter, vertical = Space.s3),
                )
            }
        }
    }
}

/**
 * FR-EXP-02. Dates after today are not selectable — a future-dated row posts
 * straight into the period rollup and would inflate spending that has not
 * happened yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerSheet(
    date: LocalDate,
    today: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val todayMillis = remember(today) { today.toEpochDay() * MILLIS_PER_DAY }
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = date.toEpochDay() * MILLIS_PER_DAY,
        selectableDates = remember(todayMillis) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayMillis
                override fun isSelectableYear(year: Int) = year <= today.year
            }
        },
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    pickerState.selectedDateMillis?.let {
                        onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    } ?: onDismiss()
                },
            ) { Text(stringResource(R.string.done)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    ) {
        DatePicker(state = pickerState)
    }
}

/**
 * FR-EXP-05's optional note. The system IME is correct here — this is the one
 * field in the flow that takes words, and it is off the hot path.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteSheet(
    note: String?,
    onDone: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DayBookTheme.colors
    var text by remember { mutableStateOf(note.orEmpty()) }
    val focus = remember { FocusRequester() }
    val hint = stringResource(R.string.note_hint)

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
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { onDone(text) },
                ),
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

/** *Today* / *Yesterday* read faster than a date; anything older gets the date. */
@Composable
private fun LocalDate.relativeLabel(today: LocalDate): String = when (this) {
    today -> stringResource(R.string.today)
    today.minusDays(1) -> stringResource(R.string.yesterday)
    else -> remember(this) { format(DATE_FORMAT) }
}

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
private const val MILLIS_PER_DAY = 86_400_000L
private const val UNDERLINE_GAP = 8f
private val AMOUNT_SIZE = 40.sp

/**
 * The SRS sets no note length. This bounds it so a pasted paragraph cannot
 * break the ledger row's single-line layout at 320 dp.
 */
private const val NOTE_MAX = 120
