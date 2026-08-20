package com.app.finance.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.app.finance.R
import com.app.finance.core.time.Period
import com.app.finance.ui.theme.KhataTheme
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space

/**
 * `‹  August 2026  ›` — 05-ui-ux-guide.md §5.4.
 *
 * Every budgeted figure is period-scoped (FR-BUD-01, -02, -03, -05), so this is
 * the control that decides what the whole screen is about.
 *
 * The arrows are 48 dp targets around 24 dp glyphs. §10 lists "period arrows"
 * by name in the touch-target row, which is not an accident — a chevron drawn
 * at its icon size is the classic way that requirement gets missed.
 *
 * There is no forward limit. A future month has no expenses, but it can carry
 * limits: setting next month's budget before it starts is the ordinary way to
 * use this screen, and `copyFromPreviousPeriod` exists precisely for that.
 */
@Composable
fun PeriodSwitcher(
    period: Period,
    onChange: (Period) -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val locale = rememberJavaLocale()
    val label = remember(period, locale) { period.label(locale) }

    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Space.s2, vertical = Space.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Arrow(
            icon = KhataIcons.ChevronLeft,
            description = stringResource(R.string.previous_period),
            onClick = { onChange(period.prev()) },
        )

        Text(
            text = label,
            style = KhataTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
            color = KhataTheme.colors.ink,
            modifier = Modifier.padding(horizontal = Space.s1),
        )

        Arrow(
            icon = KhataIcons.ChevronRight,
            description = stringResource(R.string.next_period),
            onClick = { onChange(period.next()) },
        )

        Box(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
private fun Arrow(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .sizeIn(minWidth = Sizes.minTouchTarget, minHeight = Sizes.minTouchTarget)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = description
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            // The Box carries the description; a second one on the icon would
            // make TalkBack announce the control twice.
            contentDescription = null,
            tint = KhataTheme.colors.inkSoft,
            modifier = Modifier
                .size(24.dp)
                .clearAndSetSemantics {},
        )
    }
}
