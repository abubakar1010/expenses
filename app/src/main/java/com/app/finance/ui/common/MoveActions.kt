package com.app.finance.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.app.finance.R
import com.app.finance.ui.theme.DayBookTheme
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space

/**
 * Move-up and move-down, for FR-CAT-11 and FR-IS-07.
 *
 * **Two controls rather than a drag handle.** Neither requirement names a
 * gesture — FR-CAT-11 says "reordering categories within their parent" and
 * 04 §7 says "reorder" — so the choice is about what works here. Compose has
 * no reorderable list, so dragging means hand-rolled gesture and animation
 * code that is hard to test and, more to the point, cannot be operated by
 * TalkBack at all: a screen reader has nothing to drag. Two buttons work by
 * touch, by keyboard and by screen reader without a second implementation of
 * the same feature for each.
 *
 * A control at the end of its range is **absent, not disabled** — FR-CAT-03's
 * rule for the rest of the category manager, applied here too. The slot keeps
 * its width when the control goes, because a row that reflows as items move is
 * a row the user loses their place in.
 *
 * [name] is in each description rather than a bare "Move up", because a screen
 * reader user arrives at these one row at a time and needs to know which row
 * they are on.
 */
data class Reorder(
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val onMoveUp: () -> Unit,
    val onMoveDown: () -> Unit,
)

@Composable
fun MoveActions(name: String, reorder: Reorder) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s1)) {
        Arrow(
            glyph = UP,
            description = stringResource(R.string.move_up_of, name),
            enabled = reorder.canMoveUp,
            onClick = reorder.onMoveUp,
        )
        Arrow(
            glyph = DOWN,
            description = stringResource(R.string.move_down_of, name),
            enabled = reorder.canMoveDown,
            onClick = reorder.onMoveDown,
        )
    }
}

@Composable
private fun Arrow(
    glyph: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    if (!enabled) {
        // The gap the control would have occupied. Not a disabled control:
        // "unavailable" and "there is nothing above this" are different
        // statements, and only the second one is true at the top of a list.
        Box(Modifier.width(Sizes.minTouchTarget))
        return
    }
    Box(
        Modifier
            .width(Sizes.minTouchTarget)
            .defaultMinSize(minHeight = Sizes.minTouchTarget)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = description
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = DayBookTheme.type.body,
            color = DayBookTheme.colors.indigo,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = Space.s2),
        )
    }
}

/** U+2191 and U+2193. Direction is the whole meaning, so they are not translated. */
private const val UP = "↑"
private const val DOWN = "↓"
