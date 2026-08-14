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
import com.app.finance.ui.theme.KhataTheme
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
    style: TextStyle = KhataTheme.type.rowFigure,
    color: Color? = null,
    showSymbol: Boolean = true,
    /**
     * Appended to the spoken form, for figures whose meaning is not obvious in
     * isolation — a day subtotal reads "three hundred forty taka spent" rather
     * than leaving TalkBack to announce a bare number after the date.
     */
    spokenSuffix: String = "",
) {
    val colors = KhataTheme.colors
    // Compose's own locale, not `LocalConfiguration.locales` — the latter is
    // read in a way recomposition does not track, so grouping and the spoken
    // form would keep the old locale after a language change.
    val locale = rememberJavaLocale()

    // Negative means a refund, and refunds read as corrections — the red pen.
    val figureColor = color ?: if (money.isNegative) colors.vermilion else colors.ink

    val text = remember(money, locale, showSymbol, figureColor, colors.inkSoft, style.fontSize) {
        buildMoneyString(
            money = money,
            locale = locale,
            showSymbol = showSymbol,
            figureColor = figureColor,
            symbolColor = colors.inkSoft,
            symbolSize = style.fontSize.scaleBy(KHATA_SYMBOL_SCALE),
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
 * `Money` lives in `core/` and takes a `java.util.Locale`, because it must stay
 * free of Compose as well as of Android. Compose's own `Locale.current` is the
 * observable read, so this bridges the two and re-derives only when the locale
 * actually changes.
 */
@Composable
private fun rememberJavaLocale(): Locale {
    val composeLocale = ComposeLocale.current
    return remember(composeLocale) { Locale.forLanguageTag(composeLocale.toLanguageTag()) }
}

private const val KHATA_SYMBOL_SCALE = 0.7f

private fun TextUnit.scaleBy(factor: Float): TextUnit =
    if (isSpecified) (value * factor).sp else 12.sp

private fun buildMoneyString(
    money: Money,
    locale: Locale,
    showSymbol: Boolean,
    figureColor: Color,
    symbolColor: Color,
    symbolSize: TextUnit,
): AnnotatedString = buildAnnotatedString {
    if (money.isNegative) {
        withStyle(SpanStyle(color = figureColor)) { append(Money.MINUS) }
    }
    if (showSymbol) {
        withStyle(SpanStyle(color = symbolColor, fontSize = symbolSize)) {
            append(Money.SYMBOL)
        }
    }
    withStyle(SpanStyle(color = figureColor)) {
        append(money.formatFigure(locale))
    }
}
