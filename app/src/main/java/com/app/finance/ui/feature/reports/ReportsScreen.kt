package com.app.finance.ui.feature.reports

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.finance.R
import com.app.finance.core.money.Money
import com.app.finance.di.AppContainer
import com.app.finance.di.viewModelFactory
import com.app.finance.domain.model.Nature
import com.app.finance.domain.usecase.SpendSlice
import com.app.finance.ui.common.DetailHeader
import com.app.finance.ui.common.DayBookChip
import com.app.finance.ui.common.LedgerRow
import com.app.finance.ui.common.MoneyText
import com.app.finance.ui.common.SectionHeader
import com.app.finance.ui.common.rememberJavaLocale
import com.app.finance.ui.theme.DayBookTheme
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Reports — 04 §7's "Custom date range, fixed/variable split, top expenses".
 *
 * The last screen in the inventory, and the only one no `FR-*` requires: PRD §7
 * lists it at no priority tier, which is why it stayed deferred through M4 and
 * M5 while everything with a requirement number was built.
 *
 * It exists to answer questions the dashboard structurally cannot, because the
 * dashboard is built on per-month rollups and these questions do not align to
 * months: what did the wedding fortnight cost, what has this year come to so
 * far, where did the money go between two dates the user chooses. 03 §5.3
 * anticipated exactly this screen when it carved out its exception to the
 * rollup strategy.
 */
@Composable
fun ReportsScreen(container: AppContainer, onBack: () -> Unit) {
    val vm: ReportsViewModel = viewModel(
        factory = viewModelFactory {
            ReportsViewModel(reports = container.reportsRepo, clock = container.clock)
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val locale = rememberJavaLocale()
    var picking by remember { mutableStateOf<Endpoint?>(null) }

    BackHandler(onBack = onBack)

    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "title") {
            DetailHeader(
                title = stringResource(R.string.reports_title),
                onBack = onBack,
            )
        }

        item(key = "presets") {
            FlowRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.gutter, vertical = Space.s1),
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
                verticalArrangement = Arrangement.spacedBy(Space.s1),
            ) {
                PRESETS.forEach { preset ->
                    DayBookChip(
                        label = stringResource(preset.label),
                        selected = state.range.preset == preset.value,
                        onClick = { vm.setPreset(preset.value) },
                    )
                }
            }
        }

        item(key = "range") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.gutter, vertical = Space.s2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
            ) {
                DateField(
                    label = stringResource(R.string.range_from),
                    date = state.range.from,
                    locale = locale,
                    onClick = { picking = Endpoint.FROM },
                    modifier = Modifier.weight(1f),
                )
                DateField(
                    label = stringResource(R.string.range_to),
                    date = state.range.to,
                    locale = locale,
                    onClick = { picking = Endpoint.TO },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item(key = "total") {
            val spoken = stringResource(R.string.reports_total) + ", " + state.total.spokenForm(locale)
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.gutter)
                    .padding(top = Space.s3, bottom = Space.s2)
                    .semantics(mergeDescendants = true) { contentDescription = spoken },
            ) {
                Text(
                    text = stringResource(R.string.reports_total),
                    style = DayBookTheme.type.sectionHeader,
                    color = DayBookTheme.colors.inkSoft,
                )
                MoneyText(
                    money = state.total,
                    style = DayBookTheme.type.heroFigure,
                    // The sentence above already carries the figure as words;
                    // left alone TalkBack would read the amount twice (05 §10).
                    modifier = Modifier.clearAndSetSemantics {},
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.reports_transaction_count,
                        state.count,
                        state.count,
                    ),
                    style = DayBookTheme.type.caption,
                    color = DayBookTheme.colors.inkSoft,
                )
            }
        }

        if (state.isEmpty) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.reports_empty),
                    style = DayBookTheme.type.body,
                    color = DayBookTheme.colors.inkSoft,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.gutter, vertical = Space.s4),
                )
            }
        }

        if (state.mix.isNotEmpty()) {
            item(key = "mix-header") { SectionHeader(stringResource(R.string.where_it_goes)) }
            items(state.mix, key = { "mix-${it.nature.code}" }) { slice -> MixRow(slice) }
            // 05 §5.3 — see the dashboard's copy of this. The report reads an
            // arbitrary range straight from the ledger, so it meets the same
            // case more often than the dashboard does.
            if (!state.mixExcluded.isZero) {
                item(key = "mix-excludes") {
                    Text(
                        text = stringResource(
                            R.string.mix_excludes,
                            state.mixExcluded.format(locale),
                        ),
                        style = DayBookTheme.type.caption,
                        color = DayBookTheme.colors.inkSoft,
                        modifier = Modifier.padding(
                            horizontal = Space.gutter,
                            vertical = Space.s2,
                        ),
                    )
                }
            }
        }

        if (state.largest.isNotEmpty()) {
            item(key = "largest-header") {
                SectionHeader(stringResource(R.string.largest_expenses))
            }
            items(state.largest, key = { "largest-${it.expense.id}" }) { row ->
                LedgerRow(
                    label = row.categoryName,
                    amount = Money(row.expense.amountMinor),
                    secondary = remember(row.expense.spentOn, locale) {
                        LocalDate.ofEpochDay(row.expense.spentOn).format(dayFormat(locale))
                    },
                    trailing = row.expense.note,
                )
            }
        }

        item(key = "tail") { Box(Modifier.height(Space.s5)) }
    }

    picking?.let { endpoint ->
        RangeDatePicker(
            initial = if (endpoint == Endpoint.FROM) state.range.from else state.range.to,
            onDismiss = { picking = null },
            onPick = { day ->
                if (endpoint == Endpoint.FROM) vm.setFrom(day) else vm.setTo(day)
                picking = null
            },
        )
    }
}

private enum class Endpoint { FROM, TO }

private class Preset(val value: RangePreset, val label: Int)

private val PRESETS = listOf(
    Preset(RangePreset.THIS_MONTH, R.string.range_this_month),
    Preset(RangePreset.LAST_MONTH, R.string.range_last_month),
    Preset(RangePreset.LAST_THREE_MONTHS, R.string.range_last_three),
    Preset(RangePreset.THIS_YEAR, R.string.range_this_year),
)

/** FR-AN-07's split, over a range instead of a period. */
@Composable
private fun MixRow(slice: SpendSlice) {
    LedgerRow(
        label = stringResource(
            when (slice.nature) {
                Nature.FIXED -> R.string.nature_fixed
                Nature.VARIABLE -> R.string.nature_variable
                Nature.UNPREDICTABLE -> R.string.nature_unpredictable
            },
        ),
        amount = slice.total,
        secondary = stringResource(R.string.percent_share, slice.share),
    )
}

@Composable
private fun DateField(
    label: String,
    date: LocalDate,
    locale: Locale,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val formatted = remember(date, locale) { date.format(fullFormat(locale)) }
    Column(
        modifier
            .defaultMinSize(minHeight = Sizes.minTouchTarget)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = "$label, $formatted"
            }
            .padding(vertical = Space.s1),
    ) {
        Text(label, style = DayBookTheme.type.caption, color = DayBookTheme.colors.inkSoft)
        Text(
            text = formatted,
            style = DayBookTheme.type.body,
            color = DayBookTheme.colors.indigo,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun RangeDatePicker(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { millis ->
                        // The picker speaks UTC millis and the ledger speaks
                        // local days; going through `LocalDate` at UTC is what
                        // keeps a date picked at 11 pm from landing on the day
                        // before.
                        onPick(
                            Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate(),
                        )
                    } ?: onDismiss()
                },
            ) { Text(stringResource(R.string.done)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    ) {
        DatePicker(state = state)
    }
}

private fun dayFormat(locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM", locale)

private fun fullFormat(locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy", locale)
