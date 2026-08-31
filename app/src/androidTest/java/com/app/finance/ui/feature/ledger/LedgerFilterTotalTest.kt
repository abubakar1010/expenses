package com.app.finance.ui.feature.ledger

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
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
 * FR-EXP-11's header, on screen — "the total for everything the filter
 * matched, not the total for one day".
 *
 * §24.6 recorded the gap this closes: the state behind the header is covered
 * six ways — repository, ViewModel, and pagination — and **the composition was
 * covered not at all.** No ledger screen test applied a filter, so
 * `showsFilteredTotal` was false throughout every one of them and the header
 * never rendered. The same shape as the split-sheet dead end in §25.7: every
 * layer underneath passing while the screen showed the user nothing.
 *
 * The search field is the filter used here rather than the sheet, because a
 * query is a filter — `isDefault` is false the moment one is typed — and it
 * reaches the same header through the same `applyFilters`. What is under test
 * is the header, not the sheet.
 *
 * **Two things about the header only rendering it reveals**, and both are why
 * asserting on `LedgerUiState` was never going to be enough:
 *
 * 1. `SectionHeader` renders its text uppercased, so the node reads
 *    `3 MATCHES`. A case-sensitive search for "matches" finds nothing, and a
 *    case-sensitive `assertDoesNotExist` for it passes whether the header is
 *    there or not.
 * 2. The total carries `ClearAndSetSemantics` and is announced as **words** —
 *    `six hundred taka in total`, never "৳600". That is NFR-A11Y-04 working,
 *    and it means the figure has to be asserted through the description that
 *    replaced it.
 */
@RunWith(AndroidJUnit4::class)
class LedgerFilterTotalTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var fx: TestFixture

    /**
     * A store this test owns, cleared before the database closes.
     *
     * `viewModel()` inside `LedgerScreen` would otherwise resolve against the
     * host activity's store, which outlives `@After`; closing the pool under a
     * live collector throws on a Room executor and lands on whichever test runs
     * next (§21.9 I).
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

    private fun seed(taka: Long, note: String) = runBlocking {
        fx.expenses.insert(
            amount = Money.ofTaka(taka),
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

    /**
     * Waits for [text] to reach the composition, then asserts it.
     *
     * `waitForIdle()` settles composition and layout and waits for no Room flow
     * to emit; a filter change also restarts paging from page one. Asserting
     * straight after the keystroke would be asserting a race (§26.6).
     */
    private fun awaitText(text: String) {
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    /** The total is spoken, not written; see the class comment. */
    private fun awaitSpokenTotal(words: String) {
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithContentDescription(words, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The only text field on the ledger, and unambiguous unlike its content. */
    private fun searchField() = compose.onNode(hasSetTextAction())

    private fun search(query: String) = searchField().performTextInput(query)

    /** True while any `N MATCHES` header is on screen. */
    private fun headerShown(): Boolean =
        compose.onAllNodesWithText("MATCH", substring = true, ignoreCase = true)
            .fetchSemanticsNodes().isNotEmpty()

    @Test
    fun an_unfiltered_ledger_shows_no_total_header() {
        // `showsFilteredTotal` requires a non-default filter, and the reason is
        // editorial rather than technical: an unfiltered ledger already has the
        // month's total on the dashboard, and a second one at the top of every
        // list is noise.
        //
        // `ignoreCase` is load-bearing. The header renders uppercased, so a
        // case-sensitive absence check for "match" passes even when the header
        // is right there — the assertion would hold whatever the screen did.
        seed(100, "rice")
        seed(250, "dal")

        show()
        awaitText("dal")

        compose.onNodeWithText("match", substring = true, ignoreCase = true)
            .assertDoesNotExist()
    }

    @Test
    fun a_filter_says_what_it_comes_to() {
        seed(100, "rice")
        seed(200, "more rice")
        seed(300, "rice again")
        seed(999, "dal")

        show()
        awaitText("dal")
        search("rice")

        // Three of the four rows, and the total is theirs alone — the ৳999 the
        // filter excluded must not be in it.
        awaitText("3 MATCHES")
        awaitSpokenTotal("six hundred taka")
    }

    @Test
    fun one_match_is_not_pluralised() {
        // The header is a plural resource, and a bare "%d matches" reading
        // "1 matches" is the sort of thing that survives every state-level test
        // ever written for it.
        seed(100, "rice")
        seed(999, "dal")

        show()
        awaitText("dal")
        search("rice")

        awaitText("1 MATCH")
    }

    @Test
    fun the_total_covers_every_match_and_not_the_loaded_page() {
        // **The requirement itself.** `PAGE_SIZE` is 50, so 60 matching rows
        // means the list holds 50 and the header must still speak for 60. A
        // header summing what is on screen would say fifty matches and five
        // hundred taka, and would be wrong in exactly the way FR-EXP-11 exists
        // to prevent.
        repeat(60) { seed(10, "rice $it") }
        seed(999, "dal")

        show()
        awaitText("dal")
        search("rice")

        awaitText("60 MATCHES")
        awaitSpokenTotal("six hundred taka")
    }

    @Test
    fun clearing_the_filter_takes_the_header_with_it() {
        // The other direction. The header is derived state, so a filter cleared
        // must remove it rather than leave a stale total above an unfiltered
        // list — which is the worst of the available failures, because a stale
        // total looks authoritative.
        seed(100, "rice")
        seed(999, "dal")

        show()
        awaitText("dal")
        search("rice")
        awaitText("1 MATCH")

        searchField().performTextClearance()

        compose.waitUntil(WAIT_MS) { !headerShown() }
        awaitText("dal")
    }
}

private const val WAIT_MS = 10_000L
