package com.app.finance.ui.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.finance.R
import com.app.finance.core.money.Money
import com.app.finance.data.db.dao.RuleWithTarget
import com.app.finance.di.AppContainer
import com.app.finance.di.viewModelFactory
import com.app.finance.domain.model.Frequency
import com.app.finance.domain.model.RuleTarget
import com.app.finance.ui.common.EmptyState
import com.app.finance.ui.common.KhataChip
import com.app.finance.ui.common.MoneyText
import com.app.finance.ui.common.NumericKeypad
import com.app.finance.ui.common.SectionHeader
import com.app.finance.ui.common.rememberJavaLocale
import com.app.finance.ui.feature.entry.messageRes
import com.app.finance.ui.theme.KhataTheme
import com.app.finance.ui.theme.Radius
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Repeating entries — FR-REC-01 … FR-REC-05.
 *
 * A detail route off Settings, assembled from the same components as the
 * category and source managers. What a rule produces does not appear here: it
 * appears at the top of the ledger, waiting for one tap, which is where a
 * transaction belongs.
 */
@Composable
fun RecurringScreen(
    container: AppContainer,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
) {
    val vm: RecurringViewModel = viewModel(
        factory = viewModelFactory {
            RecurringViewModel(
                recurring = container.recurringRepo,
                categories = container.categoryRepo,
                income = container.incomeRepo,
            )
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val locale = rememberJavaLocale()
    val deletedTemplate = stringResource(R.string.rule_deleted)
    val undoLabel = stringResource(R.string.undo)

    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.gutter, vertical = Space.s2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.recurring_title),
                style = KhataTheme.type.screenTitle,
                color = KhataTheme.colors.ink,
            )
            TextAction(stringResource(R.string.add_rule), onClick = vm::add)
        }

        if (state.isEmpty) {
            EmptyState(
                message = stringResource(R.string.empty_rules),
                modifier = Modifier.fillMaxSize(),
                action = {
                    KhataChip(
                        label = stringResource(R.string.add_rule),
                        selected = false,
                        onClick = vm::add,
                    )
                },
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.rules, key = { "rule-${it.rule.id}" }) { row ->
                    RuleRow(
                        row = row,
                        locale = locale,
                        onToggleActive = { vm.setActive(row.rule.id, !row.rule.isActive) },
                        onDelete = {
                            vm.delete(row.rule.id) { name, deleted ->
                                scope.launch {
                                    snackbarHostState.offerUndo(
                                        message = String.format(locale, deletedTemplate, name),
                                        undoLabel = undoLabel,
                                    ) { vm.undoDelete(deleted) }
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    state.editor?.let { editor ->
        RuleEditorSheet(
            editor = editor,
            state = state,
            onTarget = vm::setTarget,
            onTargetId = vm::setTargetId,
            onFrequency = vm::setFrequency,
            onAnchorDay = vm::setAnchorDay,
            onAutoPost = vm::setAutoPost,
            onKey = vm::onKey,
            onSave = { vm.submit {} },
            onDismiss = vm::dismissEditor,
        )
    }
}

/** `House Rent · ৳15,000 every month · Next on 1 Sep 2026`. */
@Composable
private fun RuleRow(
    row: RuleWithTarget,
    locale: Locale,
    onToggleActive: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = KhataTheme.colors
    val rule = row.rule
    val cadence = stringResource(
        when (Frequency.fromCode(rule.frequency)) {
            Frequency.MONTHLY -> R.string.frequency_monthly
            Frequency.WEEKLY -> R.string.frequency_weekly
            Frequency.YEARLY -> R.string.frequency_yearly
        },
    )
    val due = remember(rule.nextDueDay, locale) {
        LocalDate.ofEpochDay(rule.nextDueDay).format(dayFormat(locale))
    }

    Column(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Sizes.rowWithBar)
            .drawBehind {
                drawLine(
                    color = colors.rule,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = Sizes.hairline.toPx(),
                )
            }
            .padding(horizontal = Space.gutter, vertical = Space.s2),
        verticalArrangement = Arrangement.spacedBy(Space.s1),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.targetName.orEmpty(),
                style = KhataTheme.type.body,
                // A rule that is not generating is greyed *and* says why on the
                // line below — colour is never the only signal (NFR-USE-05).
                color = if (rule.isActive && !row.targetArchived) colors.ink else colors.inkSoft,
            )
            Box(Modifier.weight(1f))
            MoneyText(Money(rule.amountMinor))
        }
        Text(
            // A rule whose target was archived after it was made generates
            // nothing (FR-CAT-08, FR-IS-04). Saying so is the difference
            // between a rule that is off and one that looks on and is not.
            text = when {
                row.targetArchived ->
                    "$cadence · " + stringResource(R.string.rule_target_archived)
                rule.isActive -> "$cadence · " + stringResource(R.string.rule_next_due, due)
                else -> "$cadence · " + stringResource(R.string.rule_paused)
            },
            style = KhataTheme.type.caption,
            color = colors.inkSoft,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            TextAction(
                text = stringResource(
                    if (rule.isActive) R.string.pause_rule else R.string.resume_rule,
                ),
                onClick = onToggleActive,
            )
            TextAction(
                text = stringResource(R.string.delete_rule),
                onClick = onDelete,
                destructive = true,
            )
        }
    }
}

/**
 * Creating a rule — the same [NumericKeypad] every other amount in the app uses.
 *
 * The `auto_post` switch is a chip rather than a toggle buried in a submenu, and
 * it carries its own warning: PRD §6.5 makes the default the safe one and says
 * why, so the screen says why too rather than leaving the user to find out.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RuleEditorSheet(
    editor: RuleEditor,
    state: RecurringUiState,
    onTarget: (RuleTarget) -> Unit,
    onTargetId: (Long) -> Unit,
    onFrequency: (Frequency) -> Unit,
    onAnchorDay: (Int) -> Unit,
    onAutoPost: (Boolean) -> Unit,
    onKey: (com.app.finance.ui.common.KeypadKey) -> Unit,
    onSave: () -> Unit,
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
                .verticalScroll(rememberScrollState())
                .padding(bottom = Space.s3),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.gutter)
                    .padding(top = Space.s3),
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
            ) {
                KhataChip(
                    label = stringResource(R.string.rule_expense),
                    selected = editor.target == RuleTarget.EXPENSE,
                    onClick = { onTarget(RuleTarget.EXPENSE) },
                )
                KhataChip(
                    label = stringResource(R.string.rule_income),
                    selected = editor.target == RuleTarget.INCOME,
                    onClick = { onTarget(RuleTarget.INCOME) },
                )
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.gutter, vertical = Space.s3),
            ) {
                if (amount == null) {
                    Text(
                        text = "${Money.SYMBOL}0",
                        style = KhataTheme.type.heroFigure,
                        color = colors.inkSoft,
                    )
                } else {
                    MoneyText(amount, style = KhataTheme.type.heroFigure)
                }
            }

            SectionHeader(stringResource(R.string.choose_rule_target))
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = Space.gutter),
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
            ) {
                if (editor.target == RuleTarget.EXPENSE) {
                    state.categories.forEach { category ->
                        KhataChip(
                            label = category.name,
                            selected = editor.targetId == category.id,
                            onClick = { onTargetId(category.id) },
                        )
                    }
                } else {
                    state.sources.forEach { source ->
                        KhataChip(
                            label = source.name,
                            selected = editor.targetId == source.id,
                            onClick = { onTargetId(source.id) },
                        )
                    }
                }
            }

            SectionHeader(stringResource(R.string.choose_frequency))
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = Space.gutter),
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
            ) {
                Frequency.entries.forEach { frequency ->
                    KhataChip(
                        label = stringResource(
                            when (frequency) {
                                Frequency.MONTHLY -> R.string.frequency_monthly
                                Frequency.WEEKLY -> R.string.frequency_weekly
                                Frequency.YEARLY -> R.string.frequency_yearly
                            },
                        ),
                        selected = editor.frequency == frequency,
                        onClick = { onFrequency(frequency) },
                    )
                }
            }

            // Weekly rules recur every seventh day from today; a day of the
            // month would make "every Friday" impossible to say.
            if (editor.frequency != Frequency.WEEKLY) {
                SectionHeader(stringResource(R.string.anchor_day))
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = Space.gutter),
                    horizontalArrangement = Arrangement.spacedBy(Space.s2),
                ) {
                    ANCHOR_CHOICES.forEach { day ->
                        KhataChip(
                            label = day.toString(),
                            selected = editor.anchorDay == day,
                            onClick = { onAnchorDay(day) },
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s3),
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KhataChip(
                    label = stringResource(R.string.auto_post),
                    selected = editor.autoPost,
                    onClick = { onAutoPost(!editor.autoPost) },
                )
            }
            Text(
                text = stringResource(R.string.auto_post_hint),
                style = KhataTheme.type.caption,
                color = colors.inkSoft,
                modifier = Modifier.padding(horizontal = Space.gutter),
            )

            editor.error?.let { error ->
                Text(
                    text = stringResource(error.messageRes()),
                    style = KhataTheme.type.caption,
                    color = colors.vermilion,
                    modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s1),
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
                Text(stringResource(R.string.save_rule), style = KhataTheme.type.body)
            }

            NumericKeypad(onKey = onKey)
        }
    }
}

@Composable
private fun TextAction(text: String, onClick: () -> Unit, destructive: Boolean = false) {
    Text(
        text = text,
        style = KhataTheme.type.caption,
        color = if (destructive) KhataTheme.colors.vermilion else KhataTheme.colors.indigo,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .defaultMinSize(minHeight = Sizes.minTouchTarget)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(vertical = Space.s2),
    )
}

private fun dayFormat(locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy", locale)

/**
 * The days worth offering as chips.
 *
 * Not all 31: thirty-one chips is four rows of noise, and rent, salaries and
 * bills land on round days. **31 is included** because that is the anchor
 * FR-REC-05's clamp exists for, and a user who cannot choose it can never hit
 * the case the requirement is about.
 */
private val ANCHOR_CHOICES = listOf(1, 5, 10, 15, 20, 25, 28, 31)

/**
 * NFR-USE-03 — "at least five seconds", enforced rather than approximated.
 *
 * The same mechanism every other destructive action in the app uses: Material
 * offers roughly four seconds or roughly ten, so neither is the requirement,
 * and an indefinite snackbar cancelled at exactly five is.
 */
private suspend fun SnackbarHostState.offerUndo(
    message: String,
    undoLabel: String,
    onUndo: () -> Unit,
) {
    val result = withTimeoutOrNull(UNDO_WINDOW_MS) {
        showSnackbar(
            message = message,
            actionLabel = undoLabel,
            duration = SnackbarDuration.Indefinite,
        )
    }
    if (result == SnackbarResult.ActionPerformed) onUndo()
}

private const val UNDO_WINDOW_MS = 5_000L
