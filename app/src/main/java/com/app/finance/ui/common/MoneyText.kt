package com.app.finance.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.intl.Locale as ComposeLocale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.app.finance.core.money.Money
import com.app.finance.ui.theme.DayBookTheme
import java.util.Locale

/**
 * Renders an amount the way 05-ui-ux-guide.md §4.3 and §10 require.
 *
 * Three things this does that a plain `Text(money.format())` would not:
 *
 * 1. **The ৳ is a separate span** at 0.7em in `ink-soft`, so the glyph does not
 *    compete with the digits it labels.
 * 2. **Negatives take `vermilion`** and a true minus sign (U+2212), never a
 *    hyphen.
 * 3. **TalkBack reads the amount as words** — "one thousand two hundred fifty
 *    taka", not "৳1250" spelled character by character. §10 calls this "the
 *    single most common accessibility failure in finance apps", so it is built
 *    into the component rather than left to each call site to remember.
 */
@Composable
fun MoneyText(
    money: Money,
    modifier: Modifier = Modifier,
    style: TextStyle = DayBookTheme.type.rowFigure,
    color: Color? = null,
    showSymbol: Boolean = true,
    /**
     * Appended to the spoken form, for figures whose meaning is not obvious in
     * isolation — a day subtotal reads "three hundred forty taka spent" rather
     * than leaving TalkBack to announce a bare number after the date.
     */
    spokenSuffix: String = "",
) {
    val colors = DayBookTheme.colors
    // Compose's own locale, not `LocalConfiguration.locales` — the latter is
    // read in a way recomposition does not track, so grouping and the spoken
    // form would keep the old locale after a language change.
    val locale = rememberJavaLocale()

    // Negative means a refund, and refunds read as corrections — the red pen.
    val figureColor = color ?: if (money.isNegative) colors.vermilion else colors.ink

    val text = remember(money, locale, showSymbol, figureColor, colors.inkSoft) {
        buildMoneyString(
            money = money,
            locale = locale,
            showSymbol = showSymbol,
            figureColor = figureColor,
            symbolColor = colors.inkSoft,
        )
    }

    val spoken = remember(money, locale, spokenSuffix) {
        (money.spokenForm(locale) + " " + spokenSuffix).trim()
    }

    Text(
        text = text,
        style = style,
        modifier = modifier.clearAndSetSemantics { contentDescription = spoken },
    )
}

/**
 * The device locale as a `java.util.Locale`, read observably.
 *
 * `Money` and `Period` both live in `core/` and take a `java.util.Locale`,
 * because they must stay free of Compose as well as of Android. Compose's own
 * `Locale.current` is the observable read, so this bridges the two and
 * re-derives only when the locale actually changes.
 */
@Composable
fun rememberJavaLocale(): Locale {
    val composeLocale = ComposeLocale.current
    return remember(composeLocale) { Locale.forLanguageTag(composeLocale.toLanguageTag()) }
}

/*
 * The ৳ used to carry its own `fontSize` — 0.7x the figure — so it read as
 * subordinate to the number. It does not any more, and the reason is measured
 * rather than aesthetic.
 *
 * A `SpanStyle` that changes `fontSize` mid-string makes Compose mis-measure
 * the line on API 35: `Text("৳12,250")` in one style lays out on a single line
 * 504 px wide, and the identical string with a smaller span on the symbol
 * becomes **two lines** — 480 px wide and twice the height. At hero size that
 * put the ৳ alone on the first line and the digits on the second, so
 * `SAFE TO SPEND TODAY` — the one number the whole dashboard exists to show —
 * rendered broken in half. §20.7 found it in the greyscale pass, and shrinking
 * the text to fit only turned the wrap into a dropped digit, which is worse:
 * 05 §4.3 is that "in a ledger, precision is the product".
 *
 * The symbol still reads as subordinate, through colour: `inkSoft` against the
 * figure's `ink`. That is one signal rather than two, which NFR-USE-05 permits
 * here because the symbol carries no *state* — it is the same on every figure
 * in the app, so nothing is being distinguished by it.
 */

private fun buildMoneyString(
    money: Money,
    locale: Locale,
    showSymbol: Boolean,
    figureColor: Color,
    symbolColor: Color,
): AnnotatedString = buildAnnotatedString {
    if (money.isNegative) {
        withStyle(SpanStyle(color = figureColor)) { append(Money.MINUS) }
    }
    if (showSymbol) {
        withStyle(SpanStyle(color = symbolColor)) {
            append(Money.SYMBOL)
        }
    }
    withStyle(SpanStyle(color = figureColor)) {
        append(money.formatFigure(locale))
    }
}

/**
 * A stored amount rendered back into the keypad's raw text form.
 *
 * The inverse of [Money.parseOrNull], and shared because three screens need it:
 * the entry sheet reopening an expense, the budget sheet reopening a limit, and
 * the settle-up sheet pre-filling a balance. It was written out twice before
 * the third asked for it.
 *
 * Not `format()`: that groups digits and prefixes the symbol, and feeding
 * "৳1,250" back into a numeric keypad is not a round trip.
 */
fun Money.editableText(): String {
    val whole = paisa / 100
    val fraction = (paisa % 100).toInt()
    return if (fraction == 0) whole.toString()
    else "$whole.${fraction.toString().padStart(2, '0')}"
}
