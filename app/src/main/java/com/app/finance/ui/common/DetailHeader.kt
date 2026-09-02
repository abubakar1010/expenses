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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.app.finance.R
import com.app.finance.ui.theme.DayBookTheme
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space

/**
 * The header every detail route wears: a way back, the screen's name, and the
 * one action that screen offers, if it has one.
 *
 * **The way back is the point.** All seven detail routes — Categories, Income
 * sources, Settings, Repeating entries, Reports, Backup, People — rendered a
 * bare title and relied on `BackHandler` alone. A system gesture is not a
 * control: it is off-screen furniture the app does not own, it is invisible,
 * and on the four screens reached from a tab it was the *only* exit that worked
 * (§27). 05 §9's "a control says what happens" has nothing to say about a
 * control that is not there.
 *
 * One component rather than seven copies, for the reason [ActionRow] was
 * extracted: 05 §6 makes the component list the design, and a second header
 * with its own idea of where the gutter is is how a component list stops being
 * one.
 *
 * The arrow is an icon-only control, so it is named — accessibility §10 calls
 * those out specifically, and it is built the way [PeriodSwitcher]'s arrows are:
 * a 48 dp target around a 24 dp glyph, the description on the target rather
 * than on the icon so TalkBack announces it once. With no start gutter on the
 * row, the centred glyph's ink lands at 16 dp — the same left edge every other
 * screen's title has.
 */
@Composable
fun DetailHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = DayBookTheme.colors
    val backLabel = stringResource(R.string.back)

    Row(
        modifier
            .fillMaxWidth()
            // No start gutter: the 48 dp target supplies it, and the glyph
            // centred inside lands on the same 16 dp the titles used to.
            .padding(end = Space.gutter, top = Space.s2, bottom = Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .sizeIn(minWidth = Sizes.minTouchTarget, minHeight = Sizes.minTouchTarget)
                .clickable(onClick = onBack)
                .semantics {
                    role = Role.Button
                    contentDescription = backLabel
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = DayBookIcons.ArrowLeft,
                // The Box carries the description; a second one here would make
                // TalkBack announce the control twice.
                contentDescription = null,
                tint = colors.ink,
                modifier = Modifier
                    .size(Sizes.navIcon)
                    .clearAndSetSemantics {},
            )
        }

        // `weight`, not intrinsic width: at 1.3x font scale on a 320 dp phone
        // (NFR-COMP-03) "Repeating entries" and "Add rule" do not both fit on
        // one line, and a title that takes what it likes pushes the action off
        // the screen instead of wrapping.
        Text(
            text = title,
            style = DayBookTheme.type.screenTitle,
            color = colors.ink,
            modifier = Modifier
                .weight(1f)
                .padding(end = Space.s2),
        )

        trailing?.invoke()
    }
}
