package com.app.finance.ui.feature.income

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.app.finance.R
import com.app.finance.core.money.Money
import com.app.finance.core.text.NameKey
import com.app.finance.data.db.entity.IncomeSourceEntity
import com.app.finance.ui.common.KeypadKey
import com.app.finance.ui.common.DayBookChip
import com.app.finance.ui.common.MoneyText
import com.app.finance.ui.common.NumericKeypad
import com.app.finance.ui.common.dismissKeyboardOnOutsideGesture
import com.app.finance.ui.common.rememberJavaLocale
import com.app.finance.ui.feature.entry.messageRes
import com.app.finance.ui.theme.DayBookTheme
import com.app.finance.ui.theme.Radius
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Recording income — FR-IE-01, and the whole of FR-IS-03.
 *
 * The same [NumericKeypad] the expense sheet uses, for the same reasons:
 * entering money should feel identical wherever it happens, the pad is instant
 * where the system IME is not, and it sits in the thumb arc.
 *
 * **The source field is the one real difference from Quick Add.** An expense
 * picks a category from a fixed tree; income names a source, and FR-IS-03 says
 * an unrecognised name "MUST create the source inline and attach the entry to
 * it, without a separate navigation step". So this is free text with the active
 * sources as chips above it — one tap for the usual ones, typing for a new one,
 * and no trip to a manager screen either way.
 *
 * The kind is not asked for here. A source created this way is Variable, which
 * [com.app.finance.domain.model.IncomeKind.VARIABLE] documents as the safe
 * direction — classifying something as stable when it is not overstates the
 * coverage figure the screen exists to report. It is changed in the manager.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun IncomeEntrySheet(
    editor: IncomeEditor,
    sources: List<IncomeSourceEntity>,
    onKey: (KeypadKey) -> Unit,
    onSourceName: (String) -> Unit,
    onDate: (LocalDate) -> Unit,
    onNote: (String?) -> Unit,
    onOpenSheet: (IncomeSheet) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DayBookTheme.colors
    val locale = rememberJavaLocale()
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
                // A sheet is its own window, so the app root's dismissal does not
                // reach in here.
                .dismissKeyboardOnOutsideGesture()
                .padding(bottom = Space.s3),
        ) {
            Text(
                text = stringResource(
                    if (editor.isEditing) R.string.edit_income else R.string.add_income,
                ),
                style = DayBookTheme.type.sectionHeader,
                color = colors.inkSoft,
                modifier = Modifier.padding(
                    start = Space.gutter,
                    end = Space.gutter,
                    top = Space.s3,
                ),
            )

            // Underline only, `indigo` when there is input and `vermilion` on
            // error — 05 §6's input-field spec, as everywhere else.
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
                        modifier = Modifier.clearAndSetSemantics { contentDescription = "" },
                    )
                } else {
                    MoneyText(
                        money = amount,
                        style = DayBookTheme.type.heroFigure.copy(fontSize = AMOUNT_SIZE),
                    )
                }
            }

            SourceField(
                typed = editor.sourceName,
                sources = sources,
                onName = onSourceName,
            )

            // `12 Aug 2026 · Add note` — the same inline sentence Quick Add
            // uses. A form with labelled fields would be five taps where this
            // is one, and the values are already correct.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.gutter, vertical = Space.s2),
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SentencePart(
                    text = editor.date.format(dayFormat(locale)),
                    onClick = { onOpenSheet(IncomeSheet.DATE) },
                )
                Text("·", style = DayBookTheme.type.body, color = colors.inkSoft)
                SentencePart(
                    text = editor.note ?: stringResource(R.string.add_note),
                    onClick = { onOpenSheet(IncomeSheet.NOTE) },
                )
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
                Text(stringResource(R.string.save_income), style = DayBookTheme.type.body)
            }

            if (editor.isEditing) {
                // FR-IE-08's delete half. A text action rather than a second
                // button — there is one primary action here and this is not it
                // — and undoable for five seconds rather than confirmed by a
                // dialog (05 §8).
                Text(
                    text = stringResource(R.string.delete_income),
                    style = DayBookTheme.type.body,
                    color = colors.vermilion,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = Sizes.minTouchTarget)
                        .clickable(onClick = onDelete)
                        .semantics { role = Role.Button }
                        .padding(vertical = Space.s2),
                )
            }

            NumericKeypad(onKey = onKey)
        }
    }

    when (editor.openSheet) {
        IncomeSheet.DATE -> DatePickerSheet(
            date = editor.date,
            today = editor.today,
            onPick = onDate,
            onDismiss = { onOpenSheet(IncomeSheet.NONE) },
        )
        IncomeSheet.NOTE -> NoteSheet(
            note = editor.note,
            onDone = onNote,
            onDismiss = { onOpenSheet(IncomeSheet.NONE) },
        )
        IncomeSheet.NONE -> Unit
    }
}

/**
 * FR-IS-03 — chips for what exists, typing for what does not.
 *
 * The chip row is not a shortcut for the text field; it writes into it. That
 * keeps one value to validate and one to save, and it means tapping "Salary"
 * and typing "salary" are literally the same action by the time the repository
 * sees them — which is what `NameKey` guarantees anyway (FR-IS-02).
 *
 * When the typed name matches nothing, the sheet says so before the save
 * rather than after: creating a source is a real consequence and the user
 * should see it coming.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceField(
    typed: String,
    sources: List<IncomeSourceEntity>,
    onName: (String) -> Unit,
) {
    val colors = DayBookTheme.colors
    val hint = stringResource(R.string.income_source_hint)
    val key = remember(typed) { NameKey.of(typed) }
    val isNew = typed.isNotBlank() && sources.none { it.nameKey == key }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = Space.gutter),
        verticalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        BasicTextField(
            value = typed,
            onValueChange = { if (it.length <= NAME_MAX) onName(it) },
            singleLine = true,
            textStyle = DayBookTheme.type.body.copy(color = colors.ink),
            cursorBrush = SolidColor(colors.indigo),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = Sizes.minTouchTarget)
                .drawBehind {
                    drawLine(
                        color = if (typed.isBlank()) colors.rule else colors.indigo,
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = 2f,
                    )
                },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (typed.isEmpty()) {
                        Text(hint, style = DayBookTheme.type.body, color = colors.inkSoft)
                    }
                    inner()
                }
            },
        )

        if (isNew) {
            Text(
                text = stringResource(R.string.new_source, typed.trim()),
                style = DayBookTheme.type.caption,
                color = colors.inkSoft,
            )
        }

        if (sources.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                sources.forEach { source ->
                    DayBookChip(
                        label = source.name,
                        selected = source.nameKey == key,
                        onClick = { onName(source.name) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SentencePart(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = DayBookTheme.type.body,
        color = DayBookTheme.colors.indigo,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .defaultMinSize(minHeight = Sizes.minTouchTarget)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(vertical = Space.s2),
    )
}

/**
 * FR-IE-01's date, defaulting to today and clamped there.
 *
 * A future date would post straight into the period rollup and inflate income
 * that has not arrived — the same argument the expense sheet makes, and the
 * same clamp.
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

/** FR-IE-01's optional note. The system IME, as in Quick Add — this takes words. */
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
                // A sheet is its own window, so the app root's dismissal does not
                // reach in here.
                .dismissKeyboardOnOutsideGesture()
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
private const val MILLIS_PER_DAY = 86_400_000L
private const val NAME_MAX = 40
private const val NOTE_MAX = 60
private val AMOUNT_SIZE = 40.sp
