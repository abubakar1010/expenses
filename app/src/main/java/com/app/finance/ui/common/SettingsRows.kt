package com.app.finance.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.app.finance.R
import com.app.finance.ui.theme.DayBookTheme
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space

/**
 * The two rows every settings-shaped screen is built from.
 *
 * Extracted from `SettingsScreen` when the Backup screen needed the same two,
 * rather than copied -- 05 §6 makes the component list the design, and a second
 * `ActionRow` with its own idea of what 48 dp means is how a component list
 * stops being one.
 */
/**
 * A setting that is on or off.
 *
 * The state is written as a word as well as drawn as a switch, because
 * NFR-USE-05 says state is "never conveyed by colour alone" and a switch is
 * position and colour. It is also what makes the toggles assertable in a test
 * without reading pixels.
 */
@Composable
fun ToggleRow(
    title: String,
    hint: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val colors = DayBookTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Sizes.minTouchTarget)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = Space.gutter, vertical = Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = DayBookTheme.type.body,
                color = if (enabled) colors.ink else colors.inkSoft,
            )
            Text(hint, style = DayBookTheme.type.caption, color = colors.inkSoft)
        }
        Text(
            text = stringResource(if (checked) R.string.toggle_on else R.string.toggle_off),
            style = DayBookTheme.type.caption,
            color = colors.inkSoft,
            modifier = Modifier.padding(end = Space.s2),
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
        )
    }
}

/** A title, an explanation of what it will do, and a 48 dp target. */
@Composable
fun ActionRow(
    title: String,
    hint: String?,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = DayBookTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Sizes.rowPlain)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { role = Role.Button }
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
        Text(
            text = title,
            style = DayBookTheme.type.body,
            fontWeight = if (destructive) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                !enabled -> colors.inkSoft
                destructive -> colors.vermilion
                else -> colors.ink
            },
        )
        // 05 §9 — "a control says what happens". Every row here does something
        // the user cannot easily undo, so each says what before it is tapped.
        if (hint != null) {
            Text(hint, style = DayBookTheme.type.caption, color = colors.inkSoft)
        }
    }
}

