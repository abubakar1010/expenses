package com.app.finance.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.app.finance.core.money.Money
import com.app.finance.domain.model.BudgetStatus
import com.app.finance.ui.theme.DayBookTheme
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space

/**
 * The core repeated component, used across four screens — 05 §5.3.
 *
 * It uses the rule as its structure: no card, no shadow, no elevation. Fewer
 * elevated surfaces also means less overdraw, which the frame budget
 * appreciates.
 *
 * 56 dp plain, 72 dp with a budget bar. Both clear the 48 dp touch minimum.
 */
@Composable
fun LedgerRow(
    label: String,
    amount: Money,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    trailing: String? = null,
    status: BudgetStatus? = null,
    showLeaderDots: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val colors = DayBookTheme.colors
    val type = DayBookTheme.type

    Column(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            // `clickable` alone exposes the action but not the role, so this
            // row announced itself differently from every other tappable
            // surface in the app — on four screens, since the ledger, the
            // dashboard, the income screen and the largest-expenses list all
            // use it.
            .then(
                if (onClick != null) Modifier.semantics { role = Role.Button } else Modifier,
            )
            .defaultMinSize(minHeight = if (status != null) Sizes.rowWithBar else Sizes.rowPlain)
            .drawBehind {
                // The hairline separator. This single line is what replaces the
                // Material card for the whole app.
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
                text = label,
                style = type.body,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (showLeaderDots) LeaderDots(Modifier.weight(1f)) else Box(Modifier.weight(1f))

            MoneyText(amount, style = type.rowFigure)
        }

        if (secondary != null || trailing != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = Space.s1),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = secondary.orEmpty(),
                    style = type.caption,
                    color = colors.inkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (trailing != null) {
                    Text(trailing, style = type.caption, color = colors.inkSoft)
                }
            }
        }

        if (status != null) {
            Box(Modifier.padding(top = Space.s2)) { BudgetBar(status) }
        }
    }
}

/**
 * The dotted gap between a label and its figure — `05` §5.3.
 *
 * "The direct borrowing from a printed ledger — they carry the eye across the
 * gap, which is exactly the job they do on a paper page." §6 lists them in the
 * ledger-row specification, so every row that puts a label on the left and a
 * figure on the right gets them, including the budget screen's.
 *
 * One dashed `drawLine`, no layout cost.
 */
@Composable
fun LeaderDots(modifier: Modifier = Modifier) {
    val rule = DayBookTheme.colors.rule
    Box(
        modifier
            .height(Sizes.hairline * 2)
            .padding(horizontal = Space.s2)
            .drawBehind {
                drawLine(
                    color = rule,
                    start = Offset(0f, size.height / 2f),
                    end = Offset(size.width, size.height / 2f),
                    strokeWidth = LEADER_STROKE,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(LEADER_DOT, LEADER_GAP)),
                )
            },
    )
}

/**
 * `SECTION HEADER` — tracked and uppercase, like a printed column heading.
 *
 * The case conversion takes the composition's locale. `uppercase()` with no
 * argument is `Locale.ROOT`, which turns Turkish "i" into "I" rather than
 * "İ" — the same defect C6 fixed for `String.format` (§19.6), in a call that
 * sweep did not think to look at because it was hunting format strings.
 */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(
                start = Space.gutter,
                end = Space.gutter,
                top = Space.s4,
                bottom = Space.s2,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text.uppercase(rememberJavaLocale()),
            style = DayBookTheme.type.sectionHeader,
            color = DayBookTheme.colors.inkSoft,
        )
        trailing?.invoke()
    }
}

private const val LEADER_STROKE = 1.5f
private const val LEADER_DOT = 1.5f
private const val LEADER_GAP = 4f
