package com.app.finance.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.ui.theme.KhataTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.ViewModelStore
import androidx.compose.runtime.CompositionLocalProvider

/**
 * FR-DAT-10's first-launch offer, and the ordering `§22.3` fixed.
 *
 * "Start fresh" did not stick. The screen ran
 *
 * ```kotlin
 * scope.launch { container.settingsRepo.setOnboarded() }
 * onDone()
 * ```
 *
 * and `onDone()` flips the latch, removes this screen from the composition and
 * cancels the very scope the write is running in — a coin toss the write
 * usually lost. The gate then came back on **every** launch until the first
 * expense happened to satisfy `observeNeedsWelcome` some other way, and a fresh
 * install that was left alone asked forever.
 *
 * `AppStateRepositoryTest` covers the repository half. What is pinned here is
 * the half that was actually broken: the *order*. `onDone` must not fire until
 * the flag is already written, because that callback is what tears the scope
 * down.
 *
 * `§22.10` listed relaunching a fresh install as one of five manual checks.
 * This is that check, without the relaunch — the flag surviving is the whole of
 * what a relaunch would show.
 */
@RunWith(AndroidJUnit4::class)
class WelcomeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var fx: TestFixture


    /**
     * A [ViewModelStore] this test owns, so the screen's ViewModels can be
     * cleared *before* the database closes.
     *
     * `viewModel()` inside `WelcomeScreen` would otherwise resolve against the
     * host activity's store, which outlives `@After` — and `BackupViewModel`
     * launches two collectors over Room flows that live as long as its scope
     * does. Closing the pool under it throws on a Room executor thread, and
     * the instrumentation attributes that to whichever test runs *next*. §21.9 I is the
     * same failure in the backup suite, and CLAUDE.md warns about the shape.
     */
    private val vmStore = ViewModelStore()

    private val storeOwner = object : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore get() = vmStore
    }

    @Before fun setUp() { fx = TestFixture() }

    @After
    fun tearDown() {
        vmStore.clear()
        fx.closeAfterDraining()
    }

    @Test
    fun the_gate_is_offered_while_the_ledger_is_empty() {
        // The precondition, so the test below cannot pass by the screen simply
        // never rendering.
        assertTrue(runBlocking { fx.settings.observeNeedsWelcome().first() })

        show()

        compose.onNodeWithText("Start fresh").assertExists()
        compose.onNodeWithText("Restore from a backup").assertExists()
    }

    @Test
    fun start_fresh_writes_the_flag_before_it_dismisses_the_screen() {
        // The defect, stated as an ordering. `onDone` is the callback that
        // cancels the scope, so anything still in flight when it fires is lost
        // — which is why asserting "the flag is eventually written" would not
        // have caught this. What has to be true is that it is *already*
        // written at the moment the screen is told to go away.
        val flagAtDismissal = AtomicBoolean(false)
        show(onDone = {
            flagAtDismissal.set(runBlocking { !fx.settings.observeNeedsWelcome().first() })
        })

        compose.onNodeWithText("Start fresh").performClick()
        compose.waitUntil(TIMEOUT_MS) { dismissed }

        assertTrue(
            "onDone fired while the onboarding flag was still unwritten",
            flagAtDismissal.get(),
        )
    }

    @Test
    fun the_gate_does_not_return_once_it_has_been_answered() {
        // What a relaunch would show, asserted directly on the condition the
        // relaunch would re-evaluate.
        show()
        compose.onNodeWithText("Start fresh").performClick()
        compose.waitUntil(TIMEOUT_MS) { dismissed }

        assertFalse(runBlocking { fx.settings.observeNeedsWelcome().first() })
    }

    // --- helpers --------------------------------------------------------------

    @Volatile private var dismissed = false

    private fun show(onDone: () -> Unit = {}) {
        compose.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides storeOwner) {
            KhataTheme {
                WelcomeScreen(
                    container = fx.container,
                    onDone = {
                        onDone()
                        dismissed = true
                    },
                )
            }
            }
        }
        compose.waitForIdle()
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
