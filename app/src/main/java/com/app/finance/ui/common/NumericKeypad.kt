package com.app.finance.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.app.finance.core.money.Money
import com.app.finance.ui.theme.KhataTheme
import com.app.finance.ui.theme.Space

/** What a key press means to the amount being typed. */
sealed interface KeypadKey {
    @JvmInline
    value class Digit(val value: Char) : KeypadKey

    data object DoubleZero : KeypadKey
    data object Decimal : KeypadKey
    data object Backspace : KeypadKey

    /** Toggles the sign — how a refund is entered (FR-EXP-06). */
    data object Negate : KeypadKey
}

/**
 * A custom numeric keypad, always present — 05-ui-ux-guide.md §5.6.
 *
 * One of the four decisions that create the sub-five-second entry this product
 * is built around. The system IME costs an inflation and an animation on open,
 * and on low-end devices that is a visible 150–300 ms delay at exactly the
 * wrong moment. A Compose keypad is instant, always up, and — the part that
 * matters most — sits entirely inside the thumb arc, which the system
 * keyboard's position is not ours to choose.
 *
 * Layout follows the visual spec sheet:
 * ```
 *   1  2  3  ⌫
 *   4  5  6  00
 *   7  8  9  .
 *   0 (wide)  −
 * ```
 */
@Composable
fun NumericKeypad(
    onKey: (KeypadKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Space.s2),
        verticalArrangement = Arrangement.spacedBy(Space.s1),
    ) {
        KeyRow {
            Digit('1', onKey); Digit('2', onKey); Digit('3', onKey)
            Key("⌫", "Delete last digit") { onKey(KeypadKey.Backspace) }
        }
        KeyRow {
            Digit('4', onKey); Digit('5', onKey); Digit('6', onKey)
            Key("00", "Double zero") { onKey(KeypadKey.DoubleZero) }
        }
        KeyRow {
            Digit('7', onKey); Digit('8', onKey); Digit('9', onKey)
            Key(".", "Decimal point") { onKey(KeypadKey.Decimal) }
        }
        KeyRow {
            // 0 spans two columns, matching the spec sheet — after the digits
            // above it, it is the most pressed key on the pad.
            Digit('0', onKey, weight = 2f)
            Key(Money.MINUS, "Make this a refund") { onKey(KeypadKey.Negate) }
            Box(Modifier.weight(1f))
        }
    }
}

@Composable
private fun KeyRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.s1),
        content = content,
    )
}

@Composable
private fun RowScope.Digit(digit: Char, onKey: (KeypadKey) -> Unit, weight: Float = 1f) {
    Key(digit.toString(), digit.toString(), weight) { onKey(KeypadKey.Digit(digit)) }
}

@Composable
private fun RowScope.Key(
    label: String,
    description: String,
    weight: Float = 1f,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .weight(weight)
            .height(KEY_HEIGHT)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            // Digits set in Plex Mono, so the pad matches the figure above it;
            // a proportional face here would read as a different control.
            style = KhataTheme.type.sectionFigure,
            color = KhataTheme.colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/**
 * Exactly the 48 dp accessibility minimum, not more.
 *
 * The whole sheet — amount, chips, sentence, Save, and four key rows — has to
 * fit above the navigation bar on a 320 x 569 dp screen without scrolling,
 * because a keypad you have to scroll to reach is not "always up". 56 dp keys
 * overflowed that budget by one row.
 */
private val KEY_HEIGHT = 48.dp
