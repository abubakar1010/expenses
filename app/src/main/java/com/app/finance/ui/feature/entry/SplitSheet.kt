package com.app.finance.ui.feature.entry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.app.finance.R
import com.app.finance.core.money.Money
import com.app.finance.ui.common.DayBookChip
import com.app.finance.ui.common.EmptyState
import com.app.finance.ui.common.LeaderDots
import com.app.finance.ui.common.MoneyText
import com.app.finance.ui.common.SectionHeader
import com.app.finance.ui.common.dismissKeyboardOnOutsideGesture
import com.app.finance.ui.common.editableText
import com.app.finance.ui.theme.DayBookTheme
import com.app.finance.ui.theme.Radius
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space

/**
 * Who shared this expense — FR-SHR-01, FR-SHR-02, FR-SHR-03.
 *
 * **One question first: who paid.** Not two toggles. `trg_payer_excludes_shares`
 * makes "somebody else paid" and "they owe me" mutually exclusive in the
 * database, so a UI offering both independently would let the user build a
 * state that cannot be stored and then meet a raw `RAISE(ABORT)` on save.
 * Answering *you* opens the list of people who owe you; answering *somebody
 * else* replaces it with the single question of who.
 *
 * **The answer lives in [QuickAddUiState], not here.** It used to be a
 * `remember(state.splitMode)` local, and that made *somebody else paid*
 * unreachable from any split that already had people in it: choosing it called
 * `onClear`, clearing moved `splitMode` back to `NONE`, the changed `remember`
 * key rebuilt the flag as `false`, and the sheet snapped back to *I paid*
 * within the same frame. A control whose own state is destroyed by its own
 * effect cannot be operated. Everything this sheet asks is now hoisted, and the
 * only local state left is text being typed.
 *
 * The fourth copy of the `ModalBottomSheet` shell `CategoryPickerSheet`,
 * `MethodPickerSheet` and `NoteSheet` already use — deliberately, since the
 * three of them are the established shape for a secondary picker here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitSheet(
    state: QuickAddUiState,
    onTogglePerson: (Long) -> Unit,
    onPaidBy: (Long) -> Unit,
    onPaidByOther: (Boolean) -> Unit,
    onSplitEvenly: (Boolean) -> Unit,
    onShare: (Long, Money?) -> Unit,
    onClear: () -> Unit,
    onAddPerson: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DayBookTheme.colors
    val theyPaid = state.splitMode == SplitMode.THEY_PAID
    val byAmount = state.splitMode == SplitMode.CUSTOM

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
            ChipRow {
                DayBookChip(
                    label = stringResource(R.string.split_i_paid),
                    selected = !theyPaid,
                    onClick = { onPaidByOther(false) },
                )
                DayBookChip(
                    label = stringResource(R.string.split_they_paid),
                    selected = theyPaid,
                    onClick = { onPaidByOther(true) },
                )
            }

            // FR-SHR-02's second half — "either evenly or by an amount typed
            // per person". Offered only once somebody is in the split, because
            // until then the two styles describe the same empty division, and
            // never on the payer arm, which has no shares to divide.
            if (!theyPaid && state.splitWith.isNotEmpty()) {
                ChipRow {
                    DayBookChip(
                        label = stringResource(R.string.split_evenly),
                        selected = !byAmount,
                        onClick = { onSplitEvenly(true) },
                    )
                    DayBookChip(
                        label = stringResource(R.string.split_by_amount),
                        selected = byAmount,
                        onClick = { onSplitEvenly(false) },
                    )
                }
            }

            if (state.splitCandidates.isEmpty()) {
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
                    byAmount = byAmount,
                    onTogglePerson = onTogglePerson,
                    onPaidBy = onPaidBy,
                    onShare = onShare,
                )
            }

            // What the ledger will actually store, repeated here because this
            // is the sheet where it changes and the entry sheet showing it is
            // behind a modal scrim. It is also the only place an over-allocated
            // split announces itself: `Split.validate` refuses one, and a user
            // who cannot see their share go past zero has no way to know why
            // Save went dead.
            Summary(state)

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

            // Errors surface here, not on the entry sheet underneath. A blank
            // name, and the name of somebody archived, are both refused by
            // `PersonRepository.findOrCreate` — and the sentence explaining it
            // was being drawn on a screen the user could not see.
            state.error?.let { error ->
                Text(
                    text = stringResource(error.messageRes()),
                    style = DayBookTheme.type.caption,
                    color = colors.vermilion,
                    modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s1),
                )
            }

            // `LedgerFilterSheet`'s pair, and the same argument: undoing a
            // choice made across several taps has to cost one. Picking four
            // people and then deciding the dinner was yours meant four more
            // taps to unpick them, one row at a time.
            //
            // Present only while there is something to clear, so an ordinary
            // expense sees the single button the other sheets have.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.gutter, vertical = Space.s2),
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
            ) {
                if (state.splitMode != SplitMode.NONE) {
                    TextButton(
                        onClick = onClear,
                        modifier = Modifier.height(Sizes.minTouchTarget),
                    ) {
                        Text(
                            text = stringResource(R.string.split_not_shared),
                            style = DayBookTheme.type.body,
                            color = colors.inkSoft,
                        )
                    }
                }
                // A real control. This was a bare `Text` with no `clickable` on
                // it: it looked like the button every other sheet here closes
                // with, and did nothing at all.
                Button(
                    onClick = onDismiss,
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
}

/** The sheet's two chip pairs, laid out the same way. */
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.padding(horizontal = Space.gutter, vertical = Space.s1),
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/**
 * Your share, and what it is doing.
 *
 * The figure the expense will store is the one thing on this sheet the user
 * cannot work out by looking: it is the bill less everything they have just
 * handed to other people, and it moves with every tap.
 */
@Composable
private fun Summary(state: QuickAddUiState) {
    val colors = DayBookTheme.colors
    if (!state.split.isShared) return
    val share = state.yourShare ?: return

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter, vertical = Space.s2),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.your_share),
            style = DayBookTheme.type.caption,
            color = colors.inkSoft,
        )
        MoneyText(
            money = share,
            style = DayBookTheme.type.rowFigure,
            // Vermilion when the shares have eaten the whole bill. That is not
            // a shared expense — paying entirely on somebody's behalf is a loan,
            // which FR-SHR-04's settlement records without pretending anything
            // was consumed — and `Split.validate` refuses it.
            color = if (share.paisa <= 0L && !state.negative) colors.vermilion else colors.ink,
        )
    }
}

/**
 * Type a name, add them, keep splitting — FR-SHR-01.
 *
 * Idempotent on the name key through `PersonRepository.findOrCreate`, so typing
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
    byAmount: Boolean,
    onTogglePerson: (Long) -> Unit,
    onPaidBy: (Long) -> Unit,
    onShare: (Long, Money?) -> Unit,
) {
    val chosen = if (theyPaid) setOfNotNull(state.payerId) else state.splitWith.toSet()

    LazyColumn(Modifier.heightIn(max = 320.dp)) {
        items(state.splitCandidates, key = { it.id }) { person ->
            val selected = person.id in chosen
            PersonSplitRow(
                name = person.name,
                selected = selected,
                caption = when {
                    !selected -> null
                    theyPaid -> stringResource(R.string.split_you_owe_them)
                    else -> stringResource(R.string.split_owes_you)
                },
                // What this person's share comes to, live, as the bill is
                // typed. Zero until the amount is entered, which is honest —
                // an even split of nothing is nothing. On the payer arm the
                // figure is yours, because what you know about a bill somebody
                // else paid is your own part of it.
                amount = when {
                    theyPaid -> if (selected) state.yourShare ?: Money.ZERO else Money.ZERO
                    else -> state.owedBy(person.id) ?: Money.ZERO
                },
                // FR-SHR-02's hand-typed half. Editable only for the people
                // actually in the split, and only in that style.
                editable = byAmount && !theyPaid && selected,
                onAmount = { onShare(person.id, it) },
                onClick = { if (theyPaid) onPaidBy(person.id) else onTogglePerson(person.id) },
            )
        }
    }
}

/**
 * One name, whether they are in, and what they owe — 05 §5.3's row, with a
 * figure that can be typed into.
 *
 * `LedgerRow` did for the read-only half and does not stretch to the other:
 * FR-SHR-02's per-person amount has to be an input, and every ledger row in
 * the app is a label and a rendered figure. The structure is the same —
 * hairline rule, leader dots, 56 dp — so the two read as one component on a
 * screen that shows both.
 */
@Composable
private fun PersonSplitRow(
    name: String,
    selected: Boolean,
    caption: String?,
    amount: Money,
    editable: Boolean,
    onAmount: (Money?) -> Unit,
    onClick: () -> Unit,
) {
    val colors = DayBookTheme.colors

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // Spoken as a selection rather than only as a button: the row is a
            // toggle, and TalkBack saying "selected" is the only signal a
            // non-sighted user gets that the tap took.
            .semantics { role = Role.Button; this.selected = selected }
            .defaultMinSize(minHeight = Sizes.rowPlain)
            .drawBehind {
                drawLine(
                    color = colors.rule,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = Sizes.hairline.toPx(),
                )
            }
            .padding(horizontal = Space.gutter, vertical = Space.s2),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                style = DayBookTheme.type.body,
                color = if (selected) colors.ink else colors.inkSoft,
            )
            if (selected) LeaderDots(Modifier.weight(1f)) else Box(Modifier.weight(1f))

            if (editable) ShareField(amount = amount, onAmount = onAmount)
            else MoneyText(amount, style = DayBookTheme.type.rowFigure)
        }

        if (caption != null) {
            Text(
                text = caption,
                style = DayBookTheme.type.caption,
                color = colors.inkSoft,
                modifier = Modifier.padding(top = Space.s1),
            )
        }
    }
}

/**
 * One person's hand-typed share — FR-SHR-02.
 *
 * The text is local and the [Money] is hoisted, because "12." is a state the
 * user passes through and `Money.parseOrNull` refuses — the same split between
 * raw input and parsed money the amount field on the entry sheet makes.
 * Unkeyed, so a figure recomputed upstream cannot fight the caret while it is
 * being typed into; leaving this style is what reseeds it, and leaving it
 * discards the typed figures anyway.
 */
@Composable
private fun ShareField(amount: Money, onAmount: (Money?) -> Unit) {
    val colors = DayBookTheme.colors
    var text by remember { mutableStateOf(if (amount.isZero) "" else amount.editableText()) }

    BasicTextField(
        value = text,
        onValueChange = { raw ->
            val cleaned = raw.filter { it.isDigit() || it == '.' }.take(SHARE_MAX)
            if (cleaned.count { it == '.' } <= 1) {
                text = cleaned
                onAmount(Money.parseOrNull(cleaned)?.takeIf { !it.isZero })
            }
        },
        singleLine = true,
        textStyle = DayBookTheme.type.rowFigure.copy(
            color = colors.ink,
            textAlign = TextAlign.End,
        ),
        cursorBrush = SolidColor(colors.indigo),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier
            .width(SHARE_FIELD_WIDTH)
            .heightIn(min = Sizes.minTouchTarget)
            .drawBehind {
                drawLine(
                    // Vermilion while empty: a person in the split owing
                    // nothing is not a valid share, and the row is where that
                    // is fixed.
                    color = if (text.isEmpty()) colors.vermilion else colors.indigo,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 2f,
                )
            },
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterEnd) {
                if (text.isEmpty()) {
                    Text(
                        text = stringResource(R.string.split_share_hint),
                        style = DayBookTheme.type.rowFigure,
                        color = colors.inkSoft,
                    )
                }
                inner()
            }
        },
    )
}

private const val NAME_MAX = 40

/** Nine characters is ৳999,999.99 — a share of a household bill, not a budget. */
private const val SHARE_MAX = 9

private val SHARE_FIELD_WIDTH = 96.dp
