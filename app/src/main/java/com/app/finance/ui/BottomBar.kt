package com.app.finance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import com.app.finance.ui.common.KhataIcons
import com.app.finance.ui.theme.KhataTheme
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space

/**
 * Four destinations and a centre-docked FAB — 04 §7, 05 §6.
 *
 * The FAB placement is a usability requirement, not decoration: NFR-USE-06
 * requires one-handed operation on a five-inch display, so the single most
 * frequent action in the app has to sit in the thumb arc. The Material
 * convention — a checkmark in the top app bar — is unreachable one-handed.
 *
 * This is hand-built rather than `NavigationBar` because M3's bar has its own
 * indicator pill, ripple sizing and 80 dp height, all of which would have to be
 * overridden back out; and because the centre FAB slot is not something the
 * component models.
 */
@Composable
fun KhataBottomBar(
    current: Route,
    onSelect: (Route) -> Unit,
    onQuickAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KhataTheme.colors

    Box(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.card)
                .drawBehind {
                    // A hairline instead of an elevation shadow. Elevation is
                    // spent in exactly three places (§6) and every shadow is
                    // overdraw paid on every frame.
                    drawLine(
                        color = colors.rule,
                        start = Offset(0f, 0f),
                        end = Offset(size.width, 0f),
                        strokeWidth = Sizes.hairline.toPx(),
                    )
                }
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(Sizes.navBar),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem(Route.Dashboard, "Dashboard", current, onSelect, Modifier.weight(1f))
            NavItem(Route.Ledger, "Ledger", current, onSelect, Modifier.weight(1f))

            // The FAB's slot in the row. The button itself is drawn above, so
            // it can overlap the bar's top edge.
            Box(Modifier.weight(1f))

            NavItem(Route.Income, "Income", current, onSelect, Modifier.weight(1f))
            NavItem(Route.Budget, "Budget", current, onSelect, Modifier.weight(1f))
        }

        QuickAddFab(
            onClick = onQuickAdd,
            modifier = Modifier
                .align(Alignment.TopCenter)
                // offset, not padding: Compose rejects negative padding, and
                // the FAB has to break the bar's top edge to sit in the arc.
                .offset(y = FAB_OVERLAP),
        )
    }
}

@Composable
private fun NavItem(
    route: Route,
    label: String,
    current: Route,
    onSelect: (Route) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KhataTheme.colors
    val selected = current == route
    val tint = if (selected) colors.indigo else colors.inkSoft

    Column(
        modifier
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = { onSelect(route) },
            )
            .padding(vertical = Space.s1),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = route.icon,
            contentDescription = null, // the label below carries the name
            tint = tint,
            modifier = Modifier.size(Sizes.navIcon),
        )
        Text(
            text = label,
            style = KhataTheme.type.caption.copy(fontSize = 11.sp),
            color = tint,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/**
 * The only FAB in the app. 56 dp, `indigo`, plus glyph.
 *
 * Its content description is "Add expense" rather than "Add" — accessibility
 * §10 calls out icon-only controls specifically, and "Add" alone tells a
 * TalkBack user nothing about what will happen.
 */
@Composable
private fun QuickAddFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = KhataTheme.colors
    val shape: Shape = CircleShape

    Box(
        modifier
            .size(Sizes.fab)
            .shadow(elevation = 6.dp, shape = shape, clip = false)
            .clip(shape)
            .background(colors.indigo)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = "Add expense"
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = KhataIcons.Plus,
            contentDescription = null,
            tint = colors.card,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** Lifts the FAB above the bar's top edge without detaching it from the arc. */
private val FAB_OVERLAP = (-16).dp
