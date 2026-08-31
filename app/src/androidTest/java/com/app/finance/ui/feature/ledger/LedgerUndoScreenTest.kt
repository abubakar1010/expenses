package com.app.finance.ui.feature.ledger

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.core.money.Money
import com.app.finance.ui.theme.DayBookTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.ViewModelStore
import androidx.compose.runtime.CompositionLocalProvider

/**
 * NFR-USE-03 — "undoable for at least 5 seconds" — through the screen.
 *
 * **`LedgerViewModelTest` cannot cover this, and that is the point.** The
 * defect `§22.3` fixed was not in the queue; it was in the screen's
 * `LaunchedEffect`, which was keyed on the held row itself. Re-keying a
 * `LaunchedEffect` cancels the running one *without executing either branch*,
 * so a second swipe inside the window ran neither the restore nor the release
 * for the first row — and overwrote the slot holding it. That row is already
 * gone from the database; the slot was the only copy left.
 *
 * So this drives the actual gesture against the actual composition, and the
 * assertion is the one the requirement makes: after two swipes inside the
 * window, the *first* row is still recoverable.
 *
 * `§22.10` listed "swipe two ledger rows within five seconds" as a manual
 * check. It is not one any more.
 */
@RunWith(AndroidJUnit4::class)
class LedgerUndoScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var fx: TestFixture


    /**
     * A [ViewModelStore] this test owns, so the screen's ViewModels can be
     * cleared *before* the database closes.
     *
     * `viewModel()` inside `LedgerScreen` would otherwise resolve against the
     * host activity's store, which outlives `@After` — and `LedgerViewModel`
     * holds a collector on `observePendingExpenses` for as long as its scope
     * lives. Closing the pool under it throws on a Room executor thread, and
     * the instrumentation attributes that to whichever test runs *next*. This
     * suite did exactly that to itself on its first run; §21.9 I is the same
     * failure in the backup suite, and CLAUDE.md warns about the shape.
     */
    private val vmStore = ViewModelStore()

    private val storeOwner = object : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore get() = vmStore
    }

    @Before fun setUp() { fx = TestFixture() }

    @After
    fun tearDown() {
        // Order matters: cancel the collectors, then let the pool drain.
        vmStore.clear()
        fx.closeAfterDraining()
    }

    private fun seed(taka: Long, category: String, note: String) = runBlocking {
        fx.expenses.insert(
            amount = Money.ofTaka(taka),
            categoryId = fx.leafId(category),
            spentOn = fx.today,
            note = note,
        )
    }

    private fun ledgerCount() = fx.db.openHelper.writableDatabase
        .query("SELECT COUNT(*) FROM expense WHERE status = 0")
        .use { if (it.moveToFirst()) it.getInt(0) else -1 }

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides storeOwner) {
            DayBookTheme {
                val host = remember { SnackbarHostState() }
                Column {
                    // The host has to be in the tree: the undo action is a
                    // snackbar button, and a screen-level test that could not
                    // press it would be testing the queue a second time rather
                    // than the effect that drains it.
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
    fun a_swipe_deletes_the_row_and_offers_it_back() {
        // The baseline, so a failure below is about the *second* swipe rather
        // than about the gesture not landing at all.
        seed(300, "Grocery", "first")
        show()
        compose.waitUntil(TIMEOUT) { exists("first") }

        compose.onNodeWithText("first").performTouchInput { swipeLeft() }

        compose.waitUntil(TIMEOUT) { exists("Undo") }
        assertEquals("the row should be gone from the ledger", 0, ledgerCount())

        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(TIMEOUT) { ledgerCount() == 1 }
    }

    @Test
    fun a_second_swipe_inside_the_window_does_not_take_the_first_row_away() {
        // The defect. Both rows are deleted inside the five seconds; the queue
        // holds them head-first, and the first one's snackbar keeps its turn.
        // Under the single slot the first row's only surviving copy was
        // overwritten here and could not be recovered by any action.
        seed(300, "Grocery", "first")
        seed(400, "Grocery", "second")
        show()
        compose.waitUntil(TIMEOUT) { exists("first") && exists("second") }

        compose.onNodeWithText("first").performTouchInput { swipeLeft() }
        compose.waitUntil(TIMEOUT) { ledgerCount() == 1 }
        compose.onNodeWithText("second").performTouchInput { swipeLeft() }
        compose.waitUntil(TIMEOUT) { ledgerCount() == 0 }

        // The snackbar on screen is still the *first* row's — appending to the
        // queue must not disturb the effect that is running — so Undo restores
        // that one.
        compose.waitUntil(TIMEOUT) { exists("Undo") }
        compose.onNodeWithText("Undo").performClick()

        compose.waitUntil(TIMEOUT) { ledgerCount() == 1 }
        compose.waitUntil(TIMEOUT) { exists("first") }
        assertEquals(1, ledgerCount())
    }

    private fun exists(text: String): Boolean =
        compose.onAllNodes(
            androidx.compose.ui.test.hasText(text, substring = true),
        ).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
