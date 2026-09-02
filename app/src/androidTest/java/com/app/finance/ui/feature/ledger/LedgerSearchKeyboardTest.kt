package com.app.finance.ui.feature.ledger

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.core.money.Money
import com.app.finance.ui.theme.DayBookTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The ledger's search field lets go of the keyboard.
 *
 * FR-EXP-08's field is declared `ImeAction.Search`, and `KeyboardActionRunner`
 * has **no default for Search** — `Next` moves focus, `Done` hides the IME, and
 * everything else falls through `else -> false`. So the magnifier key on the
 * keyboard did nothing whatsoever on the one field in the app a user is most
 * likely to type into and then walk away from. Nothing else on the screen took
 * the focus back either, which is the defect
 * [com.app.finance.ui.common.dismissKeyboardOnOutsideGesture] fixes; this is
 * the half that belongs to the field.
 *
 * Focus is the assertion for the reason given on `KeyboardDismissTest`: the IME
 * follows the focused field, and there is no way to interrogate the IME itself.
 *
 * The tap-away and scroll-away halves are not testable here, because the
 * modifier that provides them is applied once at the root of `DayBookApp` —
 * above this screen, not inside it. `KeyboardDismissTest` covers the mechanism.
 */
@RunWith(AndroidJUnit4::class)
class LedgerSearchKeyboardTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var fx: TestFixture

    /** Cleared before the pool closes — §21.9 I, as in every screen test here. */
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

    private fun seed(note: String) = runBlocking {
        fx.expenses.insert(
            amount = Money.ofTaka(100),
            categoryId = fx.leafId("Grocery"),
            spentOn = fx.today,
            note = note,
        )
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

    /** The only text field on the ledger. */
    private fun searchField() = compose.onNode(hasSetTextAction())

    private fun awaitText(text: String) {
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun the_search_key_lets_go_of_the_field() {
        seed("rice")
        show()
        awaitText("rice")

        searchField().performClick()
        searchField().assertIsFocused()

        searchField().performTextInput("ri")
        searchField().performImeAction()

        searchField().assertIsNotFocused()
    }

    @Test
    fun the_search_key_leaves_the_query_alone() {
        // "Done typing" and "clear the filter" are different things, and the
        // query is already applied on every keystroke. A search action that
        // also reset the field would throw away the filter at the moment the
        // user finished expressing it.
        seed("rice")
        seed("dal")
        show()
        awaitText("dal")

        searchField().performTextInput("rice")
        awaitText("rice")
        searchField().performImeAction()

        compose.onAllNodesWithText("dal").fetchSemanticsNodes().let {
            assert(it.isEmpty()) { "the filter should survive the search action" }
        }
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
