package com.app.finance.ui.feature.ledger

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
import com.app.finance.domain.model.CategoryNode
import com.app.finance.domain.model.LedgerFilters
import com.app.finance.ui.common.DayBookChip
import com.app.finance.ui.common.SectionHeader
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
 * FR-EXP-08 — date range, root, leaf and payment method.
 *
 * Everything is a chip rather than a dropdown, for the same reason the entry
 * sheet uses chips: the whole set is visible and one tap wide, and a dropdown
 * would hide the current selection behind an extra interaction.
 *
 * Choosing a root and choosing a leaf are mutually exclusive — a leaf is
 * already inside exactly one root, so allowing both would let the user build a
 * filter that can only ever return nothing.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LedgerFilterSheet(
    current: LedgerFilters,
    tree: List<CategoryNode>,
    /** Leaf ids with expenses in the loaded ledger — see [leaves]. */
    present: Set<Long>,
    today: LocalDate,
    onApply: (LedgerFilters) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DayBookTheme.colors
    var draft by remember(current) { mutableStateOf(current) }
    var pickingFrom by remember { mutableStateOf(false) }
    var pickingTo by remember { mutableStateOf(false) }

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
        ) {
            SectionHeader(stringResource(R.string.filter_any_date))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.gutter),
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
            ) {
                DayBookChip(
                    label = draft.from?.format(DATE) ?: stringResource(R.string.filter_from),
                    selected = draft.from != null,
                    onClick = { pickingFrom = true },
                )
                DayBookChip(
                    label = draft.to?.format(DATE) ?: stringResource(R.string.filter_to),
                    selected = draft.to != null,
                    onClick = { pickingTo = true },
                )
            }

            SectionHeader(stringResource(R.string.filter_any_category))
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = Space.gutter),
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
                verticalArrangement = Arrangement.spacedBy(Space.s1),
            ) {
                DayBookChip(
                    label = stringResource(R.string.filter_any_category),
                    selected = draft.rootId == null && draft.leafId == null,
                    onClick = { draft = draft.copy(rootId = null, leafId = null) },
                )
                tree.forEach { root ->
                    DayBookChip(
                        label = root.name,
                        selected = draft.rootId == root.id,
                        // Selecting a root clears any leaf: a leaf already sits
                        // inside one root, so both together can only return
                        // nothing.
                        onClick = { draft = draft.copy(rootId = root.id, leafId = null) },
                    )
                }
            }

            // Active leaves **plus any leaf with expenses on screen**.
            //
            // FR-CAT-08 keeps an archived category out of *entry pickers*; a
            // filter is not one, and the same requirement leaves it "visible
            // in historical reports". Archive Grocery and a year of grocery
            // rows stay in the ledger with "Grocery" printed on each of them
            // — unfilterable, on a screen FR-EXP-08 says must be filterable
            // by leaf. The same "active ∪ present" shape
            // `IncomeUiState.filterSources` uses, so both filters answer the
            // question the same way.
            val leaves = remember(tree, present, draft.rootId) {
                val active = if (draft.rootId != null) {
                    tree.firstOrNull { it.id == draft.rootId }?.activeChildren.orEmpty()
                } else {
                    tree.flatMap { it.activeChildren }
                }
                val seen = active.mapTo(HashSet()) { it.id }
                val extra = tree
                    .filter { draft.rootId == null || it.id == draft.rootId }
                    .flatMap { it.children }
                    .filter { it.id in present && it.id !in seen }
                active + extra
            }
            if (leaves.isNotEmpty()) {
                FlowRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.gutter)
                        .padding(top = Space.s2),
                    horizontalArrangement = Arrangement.spacedBy(Space.s2),
                    verticalArrangement = Arrangement.spacedBy(Space.s1),
                ) {
                    leaves.forEach { leaf ->
                        DayBookChip(
                            label = leaf.name,
                            selected = draft.leafId == leaf.id,
                            onClick = {
                                draft = if (draft.leafId == leaf.id) {
                                    draft.copy(leafId = null)
                                } else {
                                    draft.copy(leafId = leaf.id, rootId = null)
                                }
                            },
                        )
                    }
                }
            }

            SectionHeader(stringResource(R.string.filter_any_method))
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = Space.gutter),
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
                verticalArrangement = Arrangement.spacedBy(Space.s1),
            ) {
                FILTERABLE_METHODS.forEach { method ->
                    DayBookChip(
                        label = method?.let { stringResource(it.labelRes()) }
                            ?: stringResource(R.string.filter_any_method),
                        selected = draft.method == method,
                        onClick = { draft = draft.copy(method = method) },
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.gutter)
                    .padding(top = Space.s4),
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
            ) {
                TextButton(
                    onClick = onClear,
                    modifier = Modifier.height(Sizes.minTouchTarget),
                ) {
                    Text(
                        stringResource(R.string.clear_filters),
                        style = DayBookTheme.type.body,
                        color = colors.inkSoft,
                    )
                }
                Button(
                    onClick = { onApply(draft) },
                    shape = RoundedCornerShape(Radius.input),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.indigo,
                        contentColor = colors.card,
                    ),
                    modifier = Modifier.fillMaxWidth().height(Sizes.minTouchTarget),
                ) {
                    Text(stringResource(R.string.apply_filters), style = DayBookTheme.type.body)
                }
            }
        }
    }

    if (pickingFrom) {
        RangeDatePicker(
            initial = draft.from ?: today,
            today = today,
            onPick = { draft = draft.copy(from = it); pickingFrom = false },
            onDismiss = { pickingFrom = false },
        )
    }
    if (pickingTo) {
        RangeDatePicker(
            initial = draft.to ?: today,
            today = today,
            onPick = { draft = draft.copy(to = it); pickingTo = false },
            onDismiss = { pickingTo = false },
        )
    }
}

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
        selectableDates = remember(todayMillis) {
            object : SelectableDates {
                // There are no expenses after today, so offering later dates
                // would only ever produce an empty result.
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayMillis
                override fun isSelectableYear(year: Int) = year <= today.year
            }
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis?.let {
                    onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                } ?: onDismiss()
            }) { Text(stringResource(R.string.done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) {
        DatePicker(state = pickerState)
    }
}

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
private const val MILLIS_PER_DAY = 86_400_000L
