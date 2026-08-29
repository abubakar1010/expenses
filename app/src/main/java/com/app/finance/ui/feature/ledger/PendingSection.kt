package com.app.finance.ui.feature.ledger

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.app.finance.R
import com.app.finance.core.money.Money
import com.app.finance.ui.common.MoneyText
import com.app.finance.ui.common.rememberJavaLocale
import com.app.finance.ui.theme.DayBookTheme
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One entry a recurring rule generated, waiting for FR-REC-02's one tap.
 *
 * ```
 *   House Rent   14 Aug     ৳15,000   Confirm  Dismiss
 * ```
 *
 * It sits at the top of the ledger because a pending entry is a transaction
 * that has not happened yet, and the ledger is where transactions live. It is
 * **not** in any figure on any screen: `status = 1` is excluded by every rollup
 * trigger and by every read in the app except this one, so a rule that fired
 * for rent the user has not paid cannot move a budget bar.
 *
 * Two actions, because there are exactly two answers. **Confirm** flips the
 * status, and `trg_rollup_exp_upd` folds the entry into every aggregate at that
 * moment — no application code touches a rollup. **Dismiss** deletes it, because
 * PRD §6.5's whole worry is entries "that didn't actually happen", and the right
 * home for one of those is nowhere.
 */
@Composable
fun PendingRow(
    label: String,
    amount: Money,
    date: LocalDate,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DayBookTheme.colors
    val locale = rememberJavaLocale()
    val confirm = stringResource(R.string.confirm_entry)
    val dismiss = stringResource(R.string.dismiss_entry)
    val spoken = "$label, ${amount.spokenForm(locale)}, ${date.format(dayFormat(locale))}"

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
        Row(
            Modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = DayBookTheme.type.body, color = colors.ink)
            Box(Modifier.weight(1f))
            // `inkSoft` rather than `ink`: it is a figure that is not yet true,
            // and the words beside it say so as well (NFR-USE-05).
            MoneyText(amount, color = colors.inkSoft)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            Text(
                text = date.format(dayFormat(locale)),
                style = DayBookTheme.type.caption,
                color = colors.inkSoft,
                modifier = Modifier.weight(1f),
            )
            Action(confirm, colors.moss, onConfirm)
            Action(dismiss, colors.inkSoft, onDismiss)
        }
    }
}

@Composable
private fun Action(text: String, colour: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        text = text,
        style = DayBookTheme.type.caption,
        color = colour,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .defaultMinSize(minHeight = Sizes.minTouchTarget)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(vertical = Space.s2),
    )
}

private fun dayFormat(locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM", locale)
