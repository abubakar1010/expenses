package com.app.finance.core.money

import java.util.Locale
import kotlin.math.absoluteValue

/**
 * An amount of Bangladeshi taka, stored as an integer count of paisa.
 *
 * 03-database-design.md §1 and 04-system-architecture.md §4.1. `@JvmInline`
 * compiles this to a bare `Long` at runtime: full type safety, zero allocation
 * in a list rendering hundreds of amounts. It is a compile error to add a
 * [Money] to a raw number.
 *
 * `Double` for money is *prohibited*, not discouraged — see the `noDoubleMoney`
 * check wired into the build. A rounding bug in a ledger is silent and gets
 * discovered months later.
 *
 * ৳1,250.75 is `Money(125075)`.
 */
@JvmInline
value class Money(val paisa: Long) : Comparable<Money> {

    operator fun plus(o: Money) = Money(paisa + o.paisa)

    operator fun minus(o: Money) = Money(paisa - o.paisa)

    operator fun unaryMinus() = Money(-paisa)

    override fun compareTo(other: Money): Int = paisa.compareTo(other.paisa)

    val isZero: Boolean get() = paisa == 0L
    val isNegative: Boolean get() = paisa < 0L

    val absoluteValue: Money get() = Money(paisa.absoluteValue)

    /**
     * Divides into [parts] equal shares, rounding toward zero.
     *
     * Used by safe-to-spend, which divides a remaining limit by the days left
     * in the period. Rounding down is deliberate: telling the user they may
     * spend one paisa more than they actually may is the wrong direction to err
     * in a budgeting app.
     */
    fun divideBy(parts: Int): Money {
        require(parts != 0) { "cannot divide Money by zero parts" }
        return Money(paisa / parts)
    }

    /**
     * The display string — `৳1,250`, `৳1,250.75`, `−৳340`.
     *
     * 05-ui-ux-guide.md §4.3:
     * - decimals hidden when the value is whole taka, which it nearly always is
     * - a true minus sign (U+2212), never a hyphen
     * - thousands grouped per locale; South Asian grouping where that applies
     * - never abbreviated to `1.2k` — in a ledger, precision is the product
     *
     * The UI renders the ৳ at 0.7em in `ink-soft` so the glyph does not compete
     * with the digits; that split needs two spans, so composables build the
     * string from [formatSymbol] and [formatFigure] rather than calling this.
     * This form is for logs, exports, and content descriptions' fallback.
     */
    fun format(locale: Locale = Locale.getDefault()): String {
        val sign = if (isNegative) MINUS else ""
        return "$sign$SYMBOL${formatFigure(locale)}"
    }

    /** The digits alone, grouped and unsigned — no ৳, no minus. */
    fun formatFigure(locale: Locale = Locale.getDefault()): String {
        val abs = paisa.absoluteValue
        val whole = groupDigits((abs / 100).toString(), usesSouthAsianGrouping(locale))
        val fraction = (abs % 100).toInt()
        return if (fraction == 0) whole else "$whole.${fraction.toString().padStart(2, '0')}"
    }

    /**
     * Spoken form for TalkBack — "one thousand two hundred fifty taka".
     *
     * 05-ui-ux-guide.md §10 singles this out: "a raw currency string read
     * character-by-character is unusable, and it is the single most common
     * accessibility failure in finance apps." Every amount on screen gets an
     * explicit `contentDescription` built from this.
     *
     * The numbering scale follows the same rule as the grouping, so what is
     * read aloud matches what is written: `৳5,84,000` reads "five lakh eighty
     * four thousand taka", not "five hundred eighty four thousand".
     */
    fun spokenForm(locale: Locale = Locale.getDefault()): String {
        val whole = paisa.absoluteValue / 100
        val remainder = (paisa.absoluteValue % 100).toInt()

        val scale = if (usesSouthAsianGrouping(locale)) SOUTH_ASIAN_SCALE else WESTERN_SCALE
        val parts = buildList {
            if (isNegative) add("minus")
            add(spellWhole(whole, scale))
            add("taka")
            if (remainder != 0) {
                add(spellWhole(remainder.toLong(), scale))
                add("paisa")
            }
        }
        return parts.joinToString(" ")
    }

    companion object {
        val ZERO = Money(0)

        const val SYMBOL = "৳"

        /** U+2212 MINUS SIGN. A hyphen is a different, shorter glyph. */
        const val MINUS = "−"

        fun ofTaka(taka: Long) = Money(taka * 100)

        /**
         * Parses digits typed on the custom keypad — "1250", "1250.75", "".
         * Returns null on anything unparseable rather than throwing, because
         * the caller is a text field being edited one character at a time.
         */
        fun parseOrNull(input: String): Money? {
            val cleaned = input.trim().replace(MINUS, "-").replace(",", "")
            if (cleaned.isEmpty() || cleaned == "-" || cleaned == ".") return null
            val negative = cleaned.startsWith("-")
            val body = cleaned.removePrefix("-")
            if (body.count { it == '.' } > 1) return null
            if (body.any { !it.isDigit() && it != '.' }) return null

            val takaPart = body.substringBefore('.').ifEmpty { "0" }
            val paisaPart = body.substringAfter('.', "").padEnd(2, '0').take(2)

            val whole = takaPart.toLongOrNull() ?: return null
            val fraction = if (paisaPart.isEmpty()) 0L else paisaPart.toLongOrNull() ?: return null

            val total = whole * 100 + fraction
            return Money(if (negative) -total else total)
        }

        /**
         * 05-ui-ux-guide.md §4.3: "South Asian grouping conventions differ from
         * Western ones for large values — follow the device locale rather than
         * hard-coding a pattern."
         */
        private fun usesSouthAsianGrouping(locale: Locale): Boolean =
            locale.country in SOUTH_ASIAN_COUNTRIES || locale.language in SOUTH_ASIAN_LANGUAGES

        private val SOUTH_ASIAN_COUNTRIES = setOf("BD", "IN", "NP", "PK", "LK")
        private val SOUTH_ASIAN_LANGUAGES = setOf("bn", "hi", "ne", "ur", "ta", "te")

        /**
         * Grouping is done by hand rather than with `DecimalFormat`.
         *
         * `DecimalFormat` supports exactly one grouping size, so it cannot
         * express the South Asian convention of three digits then twos —
         * a `"##,##,##0"` pattern silently degrades to plain groups of three
         * and prints ৳584,000 where ৳5,84,000 is wanted. `android.icu` can do
         * it, but this class lives in `core/` and must stay free of Android
         * imports so it remains JVM-testable in milliseconds.
         *
         * Digits are Latin regardless of locale (05 §4.4): Bangladeshi price
         * tags, pay slips and mobile-money screens overwhelmingly use them, and
         * the bundled Plex subset covers only Latin figures.
         */
        private fun groupDigits(digits: String, southAsian: Boolean): String {
            if (digits.length <= 3) return digits

            val groups = ArrayDeque<String>()
            var head: String

            if (southAsian) {
                // The lowest group is three digits; everything above it is twos.
                groups.addFirst(digits.takeLast(3))
                head = digits.dropLast(3)
                while (head.length > 2) {
                    groups.addFirst(head.takeLast(2))
                    head = head.dropLast(2)
                }
            } else {
                head = digits
                while (head.length > 3) {
                    groups.addFirst(head.takeLast(3))
                    head = head.dropLast(3)
                }
            }
            if (head.isNotEmpty()) groups.addFirst(head)
            return groups.joinToString(",")
        }

        // --- number to words ------------------------------------------------

        private val UNITS = arrayOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
            "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
            "sixteen", "seventeen", "eighteen", "nineteen",
        )
        private val TENS = arrayOf(
            "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy",
            "eighty", "ninety",
        )

        /** Ordered largest first; each is (word, magnitude). */
        private val SOUTH_ASIAN_SCALE = listOf(
            "crore" to 10_000_000L,
            "lakh" to 100_000L,
            "thousand" to 1_000L,
        )
        private val WESTERN_SCALE = listOf(
            "billion" to 1_000_000_000L,
            "million" to 1_000_000L,
            "thousand" to 1_000L,
        )

        private fun spellWhole(value: Long, scale: List<Pair<String, Long>>): String {
            if (value == 0L) return UNITS[0]
            val words = mutableListOf<String>()
            var rest = value

            for ((word, magnitude) in scale) {
                if (rest >= magnitude) {
                    words += spellWhole(rest / magnitude, scale)
                    words += word
                    rest %= magnitude
                }
            }
            if (rest >= 100) {
                words += UNITS[(rest / 100).toInt()]
                words += "hundred"
                rest %= 100
            }
            if (rest >= 20) {
                words += TENS[(rest / 10).toInt()]
                rest %= 10
                if (rest > 0) words += UNITS[rest.toInt()]
            } else if (rest > 0) {
                words += UNITS[rest.toInt()]
            }
            return words.joinToString(" ")
        }
    }
}
