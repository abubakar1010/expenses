package com.app.finance.ui.feature.ledger

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
 * The ledger's third line — FR-SHR-02, FR-SHR-03.
 *
 * Without it a ৳250 dinner the user remembers paying ৳1,000 for reads as simply
 * wrong. The line only appears on shared rows, and the absence on every other
 * one is what keeps the 56 dp height and NFR-PERF-05's scroll untouched — so it
 * is asserted in both directions.
 */
@RunWith(AndroidJUnit4::class)
class LedgerSplitRowTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var fx: TestFixture

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

    /**
     * Waits for [text] to reach the composition, then asserts it.
     *
     * `waitForIdle()` in [show] settles composition and layout but does not
     * wait for a Room flow to **emit**, and every row on this screen arrives
     * that way. `PeopleScreenTest` had the identical shape and failed once
     * inside a 682-test run while passing alone every time (§26.6).
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
                        LedgerScreen(
                            container = fx.container,
                            snackbarHostState = host,
                            onEdit = {},
                            onAdd = {},
                            onOpenPeople = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun a_shared_row_says_what_the_bill_was() {
        // ৳1,000 two ways: the figure is ৳500 and the line says what it is
        // half of.
        val rahim = person("Rahim")
        runBlocking {
            val (yours, split) = Split.evenly(Money.ofTaka(1_000), listOf(rahim))
            fx.expenses.insert(yours, fx.leafId("Grocery"), fx.today, split = split)
        }

        show()

        awaitText("of ", substring = true)
    }

    @Test
    fun a_row_somebody_else_paid_names_them() {
        val rahim = person("Rahim")
        runBlocking {
            fx.expenses.insert(
                Money.ofTaka(250), fx.leafId("Grocery"), fx.today,
                split = Split.TheyPaid(rahim),
            )
        }

        show()

        awaitText("Rahim paid")
    }

    @Test
    fun an_ordinary_row_carries_no_third_line() {
        // The half that protects the scroll: unshared rows are the overwhelming
        // majority and must be exactly what they were.
        runBlocking {
            fx.expenses.insert(Money.ofTaka(250), fx.leafId("Grocery"), fx.today, note = "rice")
        }

        show()

        awaitText("rice")
        compose.onNodeWithText("of ", substring = true).assertDoesNotExist()
        compose.onNodeWithText("paid", substring = true).assertDoesNotExist()
    }
}

private const val WAIT_MS = 5_000L
