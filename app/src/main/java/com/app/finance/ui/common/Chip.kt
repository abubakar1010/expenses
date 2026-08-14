package com.app.finance.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.app.finance.ui.theme.KhataTheme
import com.app.finance.ui.theme.Motion
import com.app.finance.ui.theme.Radius
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space
import com.app.finance.ui.theme.khataTween

/**
 * 05-ui-ux-guide.md §6: 32 dp tall, `card` background, 1 dp `rule` border;
 * selected is an `indigo` fill with `card` text and the 16 dp radius.
 *
 * The visible chip is 32 dp but the **touch target is padded to 48 dp**, which
 * accessibility §10 requires explicitly for chips. Shrinking the target to the
 * ink is the most common way this control fails an Accessibility Scanner pass.
 *
 * Selection animates at 80 ms linear — short enough to feel instant, long
 * enough not to flicker.
 */
@Composable
fun KhataChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KhataTheme.colors

    val background by animateColorAsState(
        targetValue = if (selected) colors.indigo else colors.card,
        animationSpec = khataTween(Motion.CHIP, Motion.Linear),
        label = "chipBackground",
    )
    val content by animateColorAsState(
        targetValue = if (selected) colors.card else colors.ink,
        animationSpec = khataTween(Motion.CHIP, Motion.Linear),
        label = "chipContent",
    )
    val shape = RoundedCornerShape(if (selected) Radius.pill else Radius.input)

    Box(
        modifier
            // The 48 dp target wraps the 32 dp visual; the extra height is
            // transparent and overlaps its neighbours' spacing rather than
            // pushing the layout apart.
            .defaultMinSize(minHeight = Sizes.minTouchTarget)
            .selectable(selected = selected, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .defaultMinSize(minHeight = Sizes.chip)
                .clip(shape)
                .background(background)
                .border(Sizes.hairline, if (selected) background else colors.rule, shape)
                .padding(horizontal = Space.s3, vertical = Space.s2),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = KhataTheme.type.body,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 05 §6 and §9: one line stating the situation, one control that starts the
 * fix. **No illustration** — illustrations cost APK size and say nothing.
 *
 * The copy is an invitation, never a report: "Nothing logged today. Tap + to
 * add your first expense," not "No transactions found."
 */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter, vertical = Space.s5),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Text(
            text = message,
            style = KhataTheme.type.body,
            color = KhataTheme.colors.inkSoft,
            textAlign = TextAlign.Center,
        )
        action?.invoke()
    }
}

/** A plain horizontal rule, for the few places not covered by a row separator. */
@Composable
fun KhataRule(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 0.dp)
            .background(KhataTheme.colors.rule)
            .defaultMinSize(minHeight = Sizes.hairline),
    )
}
