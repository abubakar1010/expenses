package com.app.finance.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 04 §7's theme setting, and the one question every use site has to ask it.
 *
 * The reason this is a method with a test rather than a `when` at each call
 * site: there were two call sites and they disagreed. The colours resolved
 * [ThemeChoice]; the status- and navigation-bar icons did not resolve it at
 * all, because `enableEdgeToEdge()` was called once with no arguments and its
 * default `detectDarkMode` reads `Configuration.isNightModeActive` — the
 * *phone's* setting. Choosing Dark on a light-mode phone therefore drew dark
 * icons on a dark bar, which is invisible, and `uiMode` sits in the activity's
 * `configChanges` so nothing recreated it to settle the difference either.
 */
class ThemeChoiceTest {

    @Test
    fun an_explicit_choice_ignores_what_the_phone_is_set_to() {
        assertTrue(ThemeChoice.DARK.isDark(systemDark = false))
        assertTrue(ThemeChoice.DARK.isDark(systemDark = true))
        assertFalse(ThemeChoice.LIGHT.isDark(systemDark = true))
        assertFalse(ThemeChoice.LIGHT.isDark(systemDark = false))
    }

    @Test
    fun system_follows_the_phone_in_both_directions() {
        assertTrue(ThemeChoice.SYSTEM.isDark(systemDark = true))
        assertFalse(ThemeChoice.SYSTEM.isDark(systemDark = false))
    }

    @Test
    fun every_choice_answers_and_none_is_left_to_a_default() {
        // A `when` with an `else` would have let a fourth choice added later
        // silently mean "light". There is no `else`, and this asserts the
        // enum is fully covered rather than trusting that it stays so.
        assertEquals(3, ThemeChoice.entries.size)
        ThemeChoice.entries.forEach { choice ->
            // Neither call may throw, and SYSTEM is the only one whose answer
            // depends on the argument.
            val followsPhone = choice.isDark(true) != choice.isDark(false)
            assertEquals("only SYSTEM may follow the phone", choice == ThemeChoice.SYSTEM, followsPhone)
        }
    }

    @Test
    fun an_unreadable_stored_value_falls_back_to_following_the_phone() {
        // The setting lives in `app_meta` as a string, so anything can be in
        // there — including nothing, on first launch.
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.fromStored(null))
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.fromStored("midnight"))
        assertEquals(ThemeChoice.DARK, ThemeChoice.fromStored("dark"))
    }
}
