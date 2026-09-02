package com.app.finance.ui.feature.entry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.app.finance.R
import com.app.finance.ui.common.DayBookChip
import com.app.finance.ui.common.EmptyState
import com.app.finance.ui.common.LedgerRow
import com.app.finance.ui.common.MoneyText
import com.app.finance.ui.common.SectionHeader
import com.app.finance.ui.common.dismissKeyboardOnOutsideGesture
import com.app.finance.ui.theme.DayBookTheme
import com.app.finance.ui.theme.Radius
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space

/**
 * Who shared this expense — FR-SHR-02, FR-SHR-03.
 *
 * **One question first: who paid.** Not two toggles. `trg_payer_excludes_shares`
 * makes "somebody else paid" and "they owe me" mutually exclusive in the
 * database, so a UI offering both independently would let the user build a
 * state that cannot be stored and then meet a raw `RAISE(ABORT)` on save.
 * Answering *you* opens the list of people who owe you; answering *somebody
 * else* replaces it with the single question of who.
 *
 * The fourth copy of the `ModalBottomSheet` shell `CategoryPickerSheet`,
 * `MethodPickerSheet` and `NoteSheet` already use — deliberately, since the
 * three of them are the established shape for a secondary picker here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitSheet(
    state: QuickAddUiState,
    onEvenly: (List<Long>) -> Unit,
    onPaidBy: (Long) -> Unit,
    onClear: () -> Unit,
    onAddPerson: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DayBookTheme.colors

    // Local, because it is a question about this sheet rather than about the
    // expense: the answer only becomes state when a person is chosen.
    var theyPaid by remember(state.splitMode) {
        mutableStateOf(state.splitMode == SplitMode.THEY_PAID)
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
                .padding(bottom = Space.s3),
        ) {
            Text(
                text = stringResource(R.string.split_title),
                style = DayBookTheme.type.screenTitle,
                color = colors.ink,
                modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s2),
            )

            // The exclusive choice, and the only one on this sheet that changes
            // what the rest of it can ask.
            Row(
                Modifier.padding(horizontal = Space.gutter, vertical = Space.s1),
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DayBookChip(
                    label = stringResource(R.string.split_i_paid),
                    selected = !theyPaid,
                    onClick = { theyPaid = false; onClear() },
                )
                DayBookChip(
                    label = stringResource(R.string.split_they_paid),
                    selected = theyPaid,
                    onClick = { theyPaid = true; onClear() },
                )
            }

            if (state.people.isEmpty()) {
                EmptyState(
                    message = stringResource(R.string.split_no_people),
                    modifier = Modifier.padding(Space.gutter),
                )
            } else {
                SectionHeader(
                    text = if (theyPaid) stringResource(R.string.split_who_paid)
                    else stringResource(R.string.split_who_owes),
                )
                PeopleList(
                    state = state,
                    theyPaid = theyPaid,
                    onEvenly = onEvenly,
                    onPaidBy = onPaidBy,
                )
            }

            // FR-SHR-01's inline creation, and it is not optional: without it
            // the empty state above says "add a person to start" on a sheet
            // that offers no way to, so the only route is backing out to the
            // People screen and losing the amount already typed. FR-IS-03 made
            // the same argument for income sources — "without a separate
            // navigation step" — and it is the same argument here.
            //
            // Always present, not only when the list is empty: the person you
            // need is most often the one you have not added yet, and that is as
            // true on the fourth split as on the first.
            AddPersonField(onAdd = onAddPerson)

            Text(
                text = stringResource(R.string.done),
                style = DayBookTheme.type.body,
                color = colors.indigo,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.gutter, vertical = Space.s2)
                    .heightIn(min = Sizes.minTouchTarget),
            )
        }
    }
}

/**
 * Type a name, add them, keep splitting — FR-SHR-01.
 *
 * Idempotent on the name key through [PersonRepository.findOrCreate], so typing
 * a name that already exists selects that person's balance rather than opening
 * a second one beside it. The field clears on submit, because the next thing
 * you do is usually add another.
 */
@Composable
private fun AddPersonField(onAdd: (String) -> Unit) {
    val colors = DayBookTheme.colors
    var name by remember { mutableStateOf("") }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter, vertical = Space.s2),
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = name,
            onValueChange = { if (it.length <= NAME_MAX) name = it },
            singleLine = true,
            textStyle = DayBookTheme.type.body.copy(color = colors.ink),
            cursorBrush = SolidColor(colors.indigo),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { if (name.isNotBlank()) { onAdd(name); name = "" } },
            ),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = Sizes.minTouchTarget)
                .drawBehind {
                    drawLine(
                        color = if (name.isEmpty()) colors.rule else colors.indigo,
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = 2f,
                    )
                },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (name.isEmpty()) {
                        Text(
                            text = stringResource(R.string.add_person_hint),
                            style = DayBookTheme.type.body,
                            color = colors.inkSoft,
                        )
                    }
                    inner()
                }
            },
        )
        DayBookChip(
            label = stringResource(R.string.add),
            selected = name.isNotBlank(),
            onClick = { if (name.isNotBlank()) { onAdd(name); name = "" } },
        )
    }
}

@Composable
private fun PeopleList(
    state: QuickAddUiState,
    theyPaid: Boolean,
    onEvenly: (List<Long>) -> Unit,
    onPaidBy: (Long) -> Unit,
) {
    val chosen = if (theyPaid) setOfNotNull(state.payerId) else state.splitWith.toSet()

    LazyColumn(Modifier.heightIn(max = 320.dp)) {
        items(state.people, key = { it.id }) { person ->
            val owed = state.split.owed.firstOrNull { it.personId == person.id }
            LedgerRow(
                label = person.name,
                // What this person's share comes to, live, as the bill is
                // typed. Zero until the amount is entered, which is honest —
                // an even split of nothing is nothing.
                amount = owed?.amount ?: com.app.finance.core.money.Money.ZERO,
                showLeaderDots = person.id in chosen,
                secondary = if (person.id in chosen) {
                    if (theyPaid) stringResource(R.string.split_paid_this)
                    else stringResource(R.string.split_owes_you)
                } else null,
                onClick = {
                    if (theyPaid) {
                        onPaidBy(person.id)
                    } else {
                        // Toggling within one exclusive mode, so the set is
                        // rebuilt rather than a second mode being entered.
                        val next = state.splitWith.toMutableList()
                        if (!next.remove(person.id)) next += person.id
                        onEvenly(next)
                    }
                },
            )
        }
    }
}

private const val NAME_MAX = 40
