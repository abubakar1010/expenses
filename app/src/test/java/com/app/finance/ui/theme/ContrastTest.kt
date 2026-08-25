package com.app.finance.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * NFR-USE-05 — "Text contrast ≥ 4.5:1; state is never conveyed by colour alone".
 *
 * The second clause has a test (`GreyscaleCaptureTest`). The first had none of
 * any kind: the palette was chosen carefully, checked once by hand, and then
 * nothing rechecked it. Contrast is not a property anybody can see slipping —
 * darkening `inkSoft` by a few points to make a label sit better looks like an
 * improvement right up to the moment it drops below the line, and the person
 * who notices is a user reading their ledger in sunlight.
 *
 * Every pair is above the bar today; the lowest is 5.27:1 (light `amber` on
 * `paper`). So this test passes on the day it is written, which is the point —
 * it exists to fail on the day somebody changes a token.
 *
 * The formula is WCAG 2.1's, arithmetic on the sRGB channels, so it needs no
 * device and runs in the JVM suite alongside everything else.
 */
class ContrastTest {

    /**
     * Colours that carry text or figures, against the two they are drawn on.
     *
     * `rule` is deliberately absent: it draws hairlines and dividers, never
     * text, and 05 §3 has it *below* body contrast on purpose — a rule as dark
     * as the ink it separates is a rule that shouts.
     */
    private val foregrounds = listOf(
        "ink" to { c: KhataColors -> c.ink },
        "inkSoft" to { c: KhataColors -> c.inkSoft },
        "indigo" to { c: KhataColors -> c.indigo },
        "vermilion" to { c: KhataColors -> c.vermilion },
        "moss" to { c: KhataColors -> c.moss },
        "amber" to { c: KhataColors -> c.amber },
    )

    private val backgrounds = listOf(
        "paper" to { c: KhataColors -> c.paper },
        "card" to { c: KhataColors -> c.card },
    )

    @Test
    fun every_text_colour_clears_four_point_five_to_one_in_the_light_theme() {
        assertAllPairsClear("light", LightKhataColors)
    }

    @Test
    fun every_text_colour_clears_four_point_five_to_one_in_the_dark_theme() {
        // The dark palette is not an inversion — the accents are lightened and
        // desaturated by hand — so clearing the bar in light says nothing at
        // all about clearing it in dark.
        assertAllPairsClear("dark", DarkKhataColors)
    }

    @Test
    fun a_filled_button_is_legible_in_both_themes() {
        // `card` on an accent, which is how every primary button in the app is
        // coloured. It is text, so NFR-USE-05 applies to it, and it is the one
        // combination where the *background* is the accent rather than the
        // foreground — a change to `vermilion` moves this and the rows above it
        // in opposite directions.
        listOf("light" to LightKhataColors, "dark" to DarkKhataColors).forEach { (theme, c) ->
            listOf("indigo" to c.indigo, "vermilion" to c.vermilion, "moss" to c.moss)
                .forEach { (name, background) ->
                    val ratio = contrast(c.card, background)
                    assertTrue(
                        "$theme: button text on $name is ${"%.2f".format(ratio)}:1, below 4.5:1",
                        ratio >= MINIMUM,
                    )
                }
        }
    }

    @Test
    fun the_formula_agrees_with_the_two_ratios_everybody_knows() {
        // A contrast check that computed something plausible but wrong would
        // pass every assertion above while measuring nothing. Black on white is
        // exactly 21:1 and any colour against itself is exactly 1:1.
        assertEquals(21.0, contrast(Color(0xFF000000), Color(0xFFFFFFFF)), 0.001)
        assertEquals(1.0, contrast(Color(0xFF3F6B4A), Color(0xFF3F6B4A)), 0.001)

        // And a pair that is known to fail must fail: mid-grey on white is
        // 3.54:1, which WCAG allows for large text and this requirement does
        // not.
        assertTrue(contrast(Color(0xFF888888), Color(0xFFFFFFFF)) < MINIMUM)
    }

    // --- internals ------------------------------------------------------------

    private fun assertAllPairsClear(theme: String, colors: KhataColors) {
        val failures = foregrounds.flatMap { (fgName, fg) ->
            backgrounds.mapNotNull { (bgName, bg) ->
                val ratio = contrast(fg(colors), bg(colors))
                if (ratio >= MINIMUM) null else "$theme $fgName on $bgName: ${"%.2f".format(ratio)}:1"
            }
        }
        assertEquals(
            "NFR-USE-05 requires 4.5:1 for text. These pairs are below it:",
            emptyList<String>(),
            failures,
        )
    }

    /** WCAG 2.1's contrast ratio, lighter over darker. */
    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /**
     * WCAG relative luminance.
     *
     * Computed here rather than taken from `Color.luminance()`, which delegates
     * to the colour space and is the thing that would have to be trusted. A
     * dozen lines of arithmetic with two known answers pinned above them is
     * worth more than a dependency in a test whose entire job is to be right.
     */
    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }

    private companion object {
        const val MINIMUM = 4.5
    }
}
