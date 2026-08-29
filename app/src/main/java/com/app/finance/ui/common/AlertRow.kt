package com.app.finance.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.app.finance.R
import com.app.finance.domain.usecase.BudgetAlert
import com.app.finance.ui.theme.DayBookTheme
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space

/**
 * One line of "needs attention" — PRD §6.2's "alerts at 80% and 100% of a
 * subcategory limit".
 *
 * **A list on a screen, never a notification.** 05 §8: "The app never nags.
 * Budget warnings appear on the dashboard when the user looks. No push
 * notifications in v1 — there is no background service, and an app that scolds
 * you about spending gets uninstalled."
 *
 * Shared by the dashboard and the budget screen, which is why it lives here.
 * 05 §5.4 puts the block on the dashboard; it stays on the budget screen too
 * because that is where an alert can be acted on — every row there leads to the
 * limit that produced it. One is where a problem is noticed, the other is where
 * it is fixed, and rendering them from one component is what keeps the two
 * saying the same sentence.
 *
 * @param onClick optional. The dashboard's rows lead to the budget screen; the
 *   budget screen's are already there.
 */
@Composable
fun AlertRow(
    alert: BudgetAlert,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = DayBookTheme.colors
    val locale = rememberJavaLocale()

    val figure = if (alert.isOver) alert.overspend else alert.remaining
    // Spent exactly to the limit: over-budget by FR-BUD-06's threshold, but by
    // zero taka, and "৳0 over" is not a sentence anyone acts on.
    val atLimit = alert.isOver && alert.overspend.isZero
    val text = when {
        atLimit -> stringResource(R.string.limit_reached)
        alert.isOver -> stringResource(R.string.amount_over, figure.format(locale))
        else -> pluralStringResource(
            R.plurals.left_with_days,
            alert.daysRemaining,
            figure.format(locale),
            alert.daysRemaining,
        )
    }
    // The same sentence with the figure as words — §10: "৳1,250 announced as
    // 'one thousand two hundred fifty taka'". Built from the same resources as
    // the visible text so the two cannot drift apart, and so the announcement
    // stays translatable.
    val spoken = "${alert.name}, " + when {
        atLimit -> stringResource(R.string.limit_reached)
        alert.isOver -> stringResource(R.string.amount_over, figure.spokenForm(locale))
        else -> stringResource(R.string.amount_left, figure.spokenForm(locale))
    }

    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Sizes.rowPlain)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .drawBehind {
                drawLine(
                    color = colors.rule,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = Sizes.hairline.toPx(),
                )
            }
            .padding(horizontal = Space.gutter, vertical = Space.s2)
            // Merged, not cleared. `contentDescription` already wins over the
            // merged text for the announcement, and clearing would strip the
            // words out of the semantics tree altogether — which costs
            // Select-to-Speak and every other service that reads text rather
            // than descriptions, for no gain.
            .semantics(mergeDescendants = true) {
                contentDescription = spoken
                if (onClick != null) role = Role.Button
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(alert.name, style = DayBookTheme.type.body, color = colors.ink)
        Text(
            text = text,
            style = DayBookTheme.type.body,
            // Over is the correction ink; approaching is amber. Never colour
            // alone — the words say it too.
            color = if (alert.isOver) colors.vermilion else colors.amber,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
