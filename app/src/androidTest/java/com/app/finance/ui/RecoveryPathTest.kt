package com.app.finance.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.domain.model.ThemeChoice
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The launch path when the database is the thing that is broken.
 *
 * 04 §8 is why this matters more than its size suggests:
 *
 * > "Migration failure | Release builds never fall back to destructive
 * > migration. Failure surfaces a recovery screen offering export of the raw
 * > database file."
 *
 * `RecoveryScreen` is the other half of the decision not to fall back to
 * destructive migration — the only route by which a user whose migration failed
 * gets five years of data off the phone. Anything on the launch path that can
 * throw before it renders takes that route away, and M5's theme read could:
 * `observeTheme` is a Room query on `app_meta`, and an unreadable database
 * makes it throw out of the collecting coroutine and kill the app.
 *
 * Neither an M1-shaped review nor an M5-shaped one would have looked here,
 * which is exactly why the whole-application pass did (§19.1).
 */
@RunWith(AndroidJUnit4::class)
class RecoveryPathTest {

    private lateinit var fx: TestFixture

    @Before fun setUp() { fx = TestFixture() }

    @After fun tearDown() {
        // Closed by the tests themselves; closing twice is a no-op.
        runCatching { fx.close() }
    }

    /** `MainActivity`'s expression, verbatim — the guard under test. */
    private fun themeSource() = fx.settings.observeTheme().catch { emit(ThemeChoice.SYSTEM) }

    @Test
    fun the_theme_reads_normally_when_the_database_is_healthy() = runBlocking {
        fx.settings.setTheme(ThemeChoice.DARK)
        assertEquals(
            ThemeChoice.DARK,
            withTimeout(5_000) { fx.settings.observeTheme().first { it == ThemeChoice.DARK } },
        )
    }

    @Test
    fun an_unreadable_database_costs_the_theme_and_nothing_else() = runBlocking {
        // The condition the recovery screen exists for. Without the `catch`
        // this throws, the exception leaves the composition's coroutine, and
        // the app dies on launch — a crash instead of the one screen that
        // could have saved the data.
        fx.close()

        val emitted = withTimeout(5_000) { themeSource().first() }
        assertEquals(ThemeChoice.SYSTEM, emitted)
    }

    @Test
    fun the_unguarded_flow_really_would_have_thrown() = runBlocking {
        // The negative control. Without it this suite would pass just as well
        // against a flow that never fails, and would be asserting nothing.
        fx.close()

        val failed = runCatching { withTimeout(5_000) { fx.settings.observeTheme().first() } }
        assertTrue(
            "expected the raw flow to fail on a closed database",
            failed.isFailure,
        )
    }

    // --- FR-APP-04 must not become §19.1 again (§20.3) -----------------------

    @Test
    fun an_unreadable_database_fails_the_lock_open_rather_than_shut() = runBlocking {
        // The lock is a second `app_meta` read on the launch path, which is
        // exactly what the theme was when it turned a recoverable database into
        // a crash. It has the same guard, and it has to fail *open*: a user
        // whose migration failed must reach `RecoveryScreen`, and a gate that
        // failed shut would lock them out of the one screen that can save five
        // years of data.
        fx.close()

        val emitted = withTimeout(5_000) {
            fx.settings.observeAppLock().catch { emit(false) }.first()
        }
        assertEquals(false, emitted)
    }

    @Test
    fun the_same_holds_for_the_screenshot_setting() = runBlocking {
        fx.close()

        val emitted = withTimeout(5_000) {
            fx.settings.observeSecureScreen().catch { emit(false) }.first()
        }
        assertEquals(false, emitted)
    }
}
