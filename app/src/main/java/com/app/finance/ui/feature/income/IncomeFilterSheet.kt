package com.app.finance.ui.feature.income

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.app.finance.R
import com.app.finance.ui.common.DayBookChip
import com.app.finance.ui.common.SectionHeader
import com.app.finance.ui.common.rememberJavaLocale
import com.app.finance.ui.theme.DayBookTheme
import com.app.finance.ui.theme.Radius
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * FR-IE-05 — "filtering income by any subset of sources combined with a date
 * range".
 *
 * The two halves of that sentence are the two halves of this sheet, and they
 * are genuinely combined: the sources apply to whatever window the scope
 * defines, whether that is a year, a month, or the range picked here.
 *
 * Everything is a chip rather than a dropdown, for the same reason the ledger's
 * filter sheet is: the whole set is visible and one tap wide, and a dropdown
 * would hide the current selection behind an extra interaction.
 *
 * Selecting a date **implies the Range scope**. Picking "1 April to 30 June"
 * and then having to remember to switch a separate control before the figures
 * move would be a trap, and the two would disagree in the meantime.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun IncomeFilterSheet(
    state: IncomeUiState,
    onSources: (Set<Long>) -> Unit,
    onRange: (LocalDate, LocalDate) -> Unit,
    onScope: (ScopeKind) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DayBookTheme.colors
    val locale = rememberJavaLocale()
    var picking by remember { mutableStateOf(RangeEnd.NONE) }

    val from = state.rangeFrom ?: state.period.firstDay()
    val to = state.rangeTo ?: state.period.lastDay()

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
                .verticalScroll(rememberScrollState())
                .padding(bottom = Space.s3),
            verticalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            SectionHeader(stringResource(R.string.filter_sources))

            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = Space.gutter),
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
            ) {
                // "Any source" is a chip rather than an absence, so clearing the
                // filter is one tap from inside the sheet that set it.
                DayBookChip(
                    label = stringResource(R.string.filter_any_source),
                    selected = state.sourceIds.isEmpty(),
                    onClick = { onSources(emptySet()) },
                )
                // `filterSources`, not `sources`: an archived source keeps its
                // history (FR-IS-04) and so keeps its breakdown row, and a row
                // the user can tap must be a row they can un-tap from here.
                // FR-IE-05's own example — {Salary, Farming} across a year —
                // is unperformable from a list of active sources alone once
                // Farming has been archived.
                state.filterSources.forEach { source ->
                    DayBookChip(
                        label = source.name,
                        selected = source.id in state.sourceIds,
                        // Multi-select: FR-IE-05 says "any subset", and
                        // {Salary, Farming} is the example it gives.
                        onClick = {
                            onSources(
                                if (source.id in state.sourceIds) state.sourceIds - source.id
                                else state.sourceIds + source.id,
                            )
                        },
                    )
                }
            }

            SectionHeader(stringResource(R.string.choose_scope))

            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = Space.gutter),
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
            ) {
                DayBookChip(
                    label = stringResource(R.string.scope_year),
                    selected = state.scopeKind == ScopeKind.YEAR,
                    onClick = { onScope(ScopeKind.YEAR) },
                )
                DayBookChip(
                    label = stringResource(R.string.scope_month),
                    selected = state.scopeKind == ScopeKind.MONTH,
                    onClick = { onScope(ScopeKind.MONTH) },
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.gutter),
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
            ) {
                DayBookChip(
                    label = stringResource(R.string.filter_from) + " " +
                        from.format(dayFormat(locale)),
                    selected = state.scopeKind == ScopeKind.RANGE,
                    onClick = { picking = RangeEnd.FROM },
                )
                DayBookChip(
                    label = stringResource(R.string.filter_to) + " " +
                        to.format(dayFormat(locale)),
                    selected = state.scopeKind == ScopeKind.RANGE,
                    onClick = { picking = RangeEnd.TO },
                )
            }

            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(Radius.input),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.indigo,
                    contentColor = colors.card,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.gutter, vertical = Space.s2)
                    .height(Sizes.minTouchTarget),
            ) { Text(stringResource(R.string.done), style = DayBookTheme.type.body) }

            TextButton(
                onClick = { onClear(); onDismiss() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.gutter),
            ) { Text(stringResource(R.string.clear_filters), color = colors.inkSoft) }
        }
    }

    if (picking != RangeEnd.NONE) {
        RangeDatePicker(
            initial = if (picking == RangeEnd.FROM) from else to,
            today = state.today,
            onPick = { picked ->
                if (picking == RangeEnd.FROM) onRange(picked, to) else onRange(from, picked)
                picking = RangeEnd.NONE
            },
            onDismiss = { picking = RangeEnd.NONE },
        )
    }
}

private enum class RangeEnd { NONE, FROM, TO }

/**
 * @param today from the injected clock, by way of the ViewModel.
 *   `LocalDate.now()` would read the system clock, which is the one thing this
 *   app never does — the whole test layer runs on a pinned one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeDatePicker(
    initial: LocalDate,
    today: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val todayMillis = remember(today) { today.toEpochDay() * MILLIS_PER_DAY }
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initial.toEpochDay() * MILLIS_PER_DAY,
        // No income can have been earned tomorrow, so a range that reaches into
        // the future can only ever confuse the total it produces.
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

private const val MILLIS_PER_DAY = 86_400_000L
