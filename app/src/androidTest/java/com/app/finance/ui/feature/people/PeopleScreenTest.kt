package com.app.finance.ui.feature.people

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.core.money.Money
import com.app.finance.domain.model.SaveOutcome
import com.app.finance.domain.model.Split
import com.app.finance.ui.theme.DayBookTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The People screen, rendered — FR-SHR-05.
 *
 * §25.5 recorded that the state behind this screen was covered six ways and the
 * composition not at all. This is that gap: what the two sections actually show,
 * and that a settled account appears in neither.
 */
@RunWith(AndroidJUnit4::class)
class PeopleScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var fx: TestFixture

    /**
     * A store this test owns, cleared before the database closes.
     *
     * `viewModel()` inside `PeopleScreen` would otherwise resolve against the
     * host activity's store, which outlives `@After` — and `PeopleViewModel`
     * collects balances for as long as its scope lives. Closing the pool under
     * it throws on a Room executor and lands on whichever test runs *next*.
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

    private fun person(name: String): Long = runBlocking {
        (fx.people.findOrCreate(name) as SaveOutcome.Saved).id
    }

    /** You paid; [ids] owe you their parts of [taka]. */
    private fun shared(taka: Long, ids: List<Long>) = runBlocking {
        val (yours, split) = Split.evenly(Money.ofTaka(taka), ids)
        fx.expenses.insert(yours, fx.leafId("Grocery"), fx.today, split = split)
    }

    /**
     * Waits for [text] to reach the composition, then asserts it.
     *
     * `waitForIdle()` in [show] settles composition and layout; it does not wait
     * for a Room flow to **emit**, and everything on this screen arrives that
     * way. An assertion made straight after `show()` is therefore asserting a
     * race — which is exactly how `an_empty_list_invites_rather_than_reports`
     * failed once inside a 682-test run and passed every time it was run alone
     * (§26.6). The same shape, and the same cause, as §21.9 J.
     */
    private fun awaitText(
        text: String,
        substring: Boolean = false,
        ignoreCase: Boolean = false,
    ) {
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithText(text, substring = substring, ignoreCase = ignoreCase)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text, substring = substring, ignoreCase = ignoreCase)
            .assertIsDisplayed()
    }

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides storeOwner) {
                DayBookTheme {
                    val host = remember { SnackbarHostState() }
                    Column {
                        SnackbarHost(host)
                        PeopleScreen(
                            container = fx.container,
                            snackbarHostState = host,
                            onBack = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun somebody_who_owes_you_is_listed_under_the_owed_heading() {
        val rahim = person("Rahim")
        shared(1_000, listOf(rahim))

        show()

        awaitText("They owe you", ignoreCase = true)
        awaitText("Rahim")
    }

    @Test
    fun the_two_directions_are_headed_separately() {
        // The screen's whole job: which way the money points, at a glance.
        val rahim = person("Rahim")
        val karim = person("Karim")
        shared(1_000, listOf(rahim))
        runBlocking {
            fx.expenses.insert(
                Money.ofTaka(250), fx.leafId("Grocery"), fx.today,
                split = Split.TheyPaid(karim),
            )
        }

        show()

        awaitText("They owe you", ignoreCase = true)
        awaitText("You owe", ignoreCase = true)
        awaitText("Rahim")
        awaitText("Karim")
    }

    @Test
    fun an_empty_list_invites_rather_than_reports() {
        // 05 §9: "Empty states are invitations, not reports."
        show()
        awaitText("Nobody yet", substring = true)
    }

    @Test
    fun somebody_square_is_under_neither_direction() {
        // FR-SHR-05. A settled account is not something to act on, and listing
        // it among the names that are is how the two that matter get buried.
        val rahim = person("Rahim")
        shared(1_000, listOf(rahim))
        runBlocking { fx.settlements.record(rahim, Money.ofTaka(500), fx.today) }

        show()

        awaitText("Settled up", ignoreCase = true)
        compose.onNodeWithText("They owe you", ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithText("You owe", ignoreCase = true).assertDoesNotExist()
    }
}

private const val WAIT_MS = 5_000L
