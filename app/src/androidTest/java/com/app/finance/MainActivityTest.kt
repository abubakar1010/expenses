package com.app.finance

import android.view.WindowManager
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.domain.model.ThemeChoice
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two things about `MainActivity` that only a real `Window` can answer.
 *
 * Everything else it decides is now a pure function with a JVM test
 * ([rootScreen]), and that is the right instrument for an *ordering*. These two
 * are not orderings — they are effects on the window itself, and there is no
 * level below the composition at which they exist:
 *
 *  - the status- and navigation-bar **icon appearance**, which is a property of
 *    `WindowInsetsController` and not of any value the app holds; and
 *  - whether the lock re-engages on a **genuine** `ON_STOP`/`ON_START` pair,
 *    which `ActivityScenario.moveToState` delivers for real.
 *
 * `§22.10` listed both as manual checks nobody had run. They are not manual any
 * more.
 *
 * The container is swapped for one over an in-memory database before the
 * activity starts. `AppContainer` has documented `databaseOverride` as a test
 * seam since M4; `FinanceApp.installContainer` is the half that was missing.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    private lateinit var fx: TestFixture

    @Before
    fun setUp() {
        fx = TestFixture()
        app().installContainer(fx.container)
    }

    @After
    fun tearDown() {
        fx.closeAfterDraining()
        // Put the real container back, or every test after this one runs
        // against a database that has been closed.
        app().installContainer(com.app.finance.di.AppContainer(app()))
    }

    // --- NFR-USE-05 and 05 §3: the bars follow the app, not the phone ---------

    @Test
    fun choosing_dark_draws_light_status_bar_icons() {
        // §22.3's defect end to end. `enableEdgeToEdge()` was called once with
        // no arguments, so its default `detectDarkMode` read the *phone's*
        // night mode while everything below followed `ThemeChoice` — dark app
        // on a light phone meant dark icons on a dark bar, which is invisible.
        //
        // `isAppearanceLightStatusBars` is the exact flag that was wrong, and
        // it is a property of the window: `ThemeChoice.isDark` being correct
        // says nothing about whether anything applied it.
        setTheme(ThemeChoice.DARK)

        launch { activity ->
            assertFalse(
                "a dark theme must ask for light icons",
                appearanceLightStatusBars(activity),
            )
        }
    }

    @Test
    fun choosing_light_draws_dark_status_bar_icons() {
        // The other direction, because a fix that hard-coded one answer would
        // pass the test above.
        setTheme(ThemeChoice.LIGHT)

        launch { activity ->
            assertTrue(
                "a light theme must ask for dark icons",
                appearanceLightStatusBars(activity),
            )
        }
    }

    @Test
    fun the_bars_follow_the_app_rather_than_the_devices_own_setting() {
        // The claim that makes the two above worth having. Whatever the
        // emulator is set to, the two themes must disagree with each other —
        // if the bars were still following `isNightModeActive` they would come
        // out the same both times.
        setTheme(ThemeChoice.DARK)
        val dark = read { appearanceLightStatusBars(it) }

        setTheme(ThemeChoice.LIGHT)
        val light = read { appearanceLightStatusBars(it) }

        assertTrue("the bars ignored the app's theme entirely", dark != light)
    }

    // --- FR-APP-04: the lock across a real background -------------------------

    @Test
    fun leaving_the_app_and_coming_back_re_engages_the_lock() {
        // `LockControllerTest` pins the state machine; this pins that
        // `MainActivity` actually wires it to the lifecycle. The two are
        // different claims, and §22.3 rewrote the controller without anything
        // checking the wiring.
        //
        // `moveToState(CREATED)` is a real `ON_STOP` and `RESUMED` a real
        // `ON_START` — the same events the observer in `MainActivity` listens
        // for, delivered by the framework rather than called by hand.
        runBlocking { fx.settings.setAppLock(true) }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // Unlock, as the biometric prompt's success callback would.
                activity.lockController.unlock()
                assertFalse(activity.lockController.locked)
            }

            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)

            scenario.onActivity { activity ->
                assertTrue(
                    "the app came back unlocked after a real background",
                    activity.lockController.locked,
                )
            }
        }
    }

    // --- NFR-SEC-04: FLAG_SECURE, asserted on the window ----------------------

    @Test
    fun the_hide_from_screenshots_setting_reaches_the_window() {
        // Asserted nowhere until now. The setting is stored and read, and
        // whether it ever became a window flag was taken on trust.
        runBlocking { fx.settings.setSecureScreen(true) }

        launch { activity ->
            assertTrue(
                "FLAG_SECURE was never applied",
                activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
            )
        }
    }

    @Test
    fun leaving_the_setting_off_leaves_the_window_alone() {
        launch { activity ->
            assertFalse(
                "FLAG_SECURE was applied without being asked for",
                activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
            )
        }
    }

    // --- helpers --------------------------------------------------------------

    private fun app(): FinanceApp = ApplicationProvider.getApplicationContext()

    private fun setTheme(choice: ThemeChoice) = runBlocking { fx.settings.setTheme(choice) }

    /**
     * Launches, waits for the setting to reach the window, and asserts.
     *
     * The wait is not decoration. The theme arrives on a Room flow and the bars
     * are applied by a `LaunchedEffect` keyed on it, so an assertion made the
     * instant the activity resumes is asserting on the default.
     */
    private fun launch(assertion: (MainActivity) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(SETTLE_MS)
            scenario.onActivity(assertion)
        }
    }

    private fun <T> read(value: (MainActivity) -> T): T {
        var captured: T? = null
        launch { captured = value(it) }
        return captured!!
    }

    private fun appearanceLightStatusBars(activity: MainActivity): Boolean =
        WindowInsetsControllerCompat(activity.window, activity.window.decorView)
            .isAppearanceLightStatusBars

    private companion object {
        /** One `app_meta` read plus a frame, with room for a slow emulator. */
        const val SETTLE_MS = 1_500L
    }
}
