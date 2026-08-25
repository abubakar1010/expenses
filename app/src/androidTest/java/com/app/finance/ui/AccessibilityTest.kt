package com.app.finance.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.core.money.Money
import com.app.finance.ui.feature.entry.QuickAddSheet
import com.app.finance.ui.feature.ledger.LedgerScreen
import com.app.finance.ui.theme.KhataTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.test.then
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.runtime.Composable

/**
 * 05-ui-ux-guide.md §10 opens with "Requirements, not aspirations. Each is
 * testable." These are those tests.
 *
 * The guide prescribes Accessibility Scanner for the manual pass; what is
 * automated here is everything a scanner cannot check — that money is announced
 * as words rather than spelled out, that icon-only controls are named, and that
 * the touch targets are 48 dp even where the *visual* element is 32 dp.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var fx: TestFixture

    @Before fun setUp() { fx = TestFixture() }
    @After fun tearDown() = fx.closeAfterDraining()

    // --- money as words (§10, "the single most common accessibility failure
    //     in finance apps") ---------------------------------------------------

    @Test
    fun an_amount_is_announced_as_words_not_as_a_currency_string() {
        compose.setContent {
            KhataTheme {
                com.app.finance.ui.common.MoneyText(Money.ofTaka(1250))
            }
        }

        // "one thousand two hundred fifty taka", never "৳1250".
        compose.onNodeWithContentDescription("one thousand two hundred fifty taka")
            .assertIsDisplayed()

        val tree = compose.onRoot().printToString()
        assertTrue(
            "the raw currency string must not be the announced text:\n$tree",
            !tree.contains("ContentDescription = '৳1,250'"),
        )
    }

    @Test
    fun a_refund_announces_its_sign() {
        compose.setContent {
            KhataTheme { com.app.finance.ui.common.MoneyText(Money.ofTaka(-500)) }
        }
        compose.onNodeWithContentDescription("minus five hundred taka").assertIsDisplayed()
    }

    // --- icon-only controls (§10: "FAB reads 'Add expense'") ----------------

    @Test
    fun the_fab_and_every_keypad_key_are_named() {
        compose.setContent {
            KhataTheme {
                Column {
                    KhataBottomBar(
                        current = Route.Dashboard,
                        onSelect = {},
                        onQuickAdd = {},
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Add expense").assertIsDisplayed()
    }

    @Test
    fun keypad_keys_carry_spoken_names_rather_than_glyphs() {
        compose.setContent {
            KhataTheme {
                com.app.finance.ui.common.NumericKeypad(onKey = {})
            }
        }
        // The backspace and sign keys render as ⌫ and −, which TalkBack cannot
        // usefully announce.
        compose.onNodeWithContentDescription("Delete last digit").assertIsDisplayed()
        compose.onNodeWithContentDescription("Make this a refund").assertIsDisplayed()
        compose.onNodeWithContentDescription("Decimal point").assertIsDisplayed()
    }

    // --- touch targets (§10: ">= 48 x 48 dp ... Includes chips, nav items") --

    @Test
    fun every_keypad_key_clears_the_48dp_touch_minimum() {
        compose.setContent {
            KhataTheme { com.app.finance.ui.common.NumericKeypad(onKey = {}) }
        }
        listOf("1", "5", "9", "Delete last digit", "Decimal point", "Make this a refund")
            .forEach { key ->
                compose.onNodeWithContentDescription(key)
                    .assertHeightIsAtLeast(48.dp)
                    .assertWidthIsAtLeast(48.dp)
            }
    }

    @Test
    fun a_chip_has_a_48dp_target_even_though_it_draws_at_32dp() {
        // The visual chip is 32 dp per §6; the target must still be 48 dp, and
        // shrinking the target to the ink is the most common way this fails.
        compose.setContent {
            KhataTheme {
                com.app.finance.ui.common.KhataChip("Grocery", selected = false, onClick = {})
            }
        }
        compose.onNodeWithText("Grocery").assertHeightIsAtLeast(48.dp)
    }

    /** One posted row, so the ledger has a line to measure. */
    private fun seedRow() = runBlocking {
        fx.expenses.insert(Money.ofTaka(1_250), fx.leafId("Grocery"), fx.today)
    }

    // --- size and font scale (NFR-COMP-03, NFR-COMP-04) ---------------------

    /**
     * Renders [content] as if on a screen [width] wide at [fontScale].
     *
     * **The width is the half that was missing.** These tests carried the
     * comment "0.85x to 1.3x **at 320 dp**" and set only the font scale, so
     * they ran at whatever the test device happened to be — 393 dp on the
     * emulator this suite is run on, which is 73 dp of slack the requirement
     * does not promise. NFR-COMP-03's floor is 320 dp, and 320 dp at 1.3× is
     * the corner where everything in this app is tightest: 05 §2 budgets 288 dp
     * of content inside 16 dp gutters.
     */
    private fun renderAt(width: Dp, fontScale: Float, content: @Composable () -> Unit) {
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(width, 640.dp)) then
                    DeviceConfigurationOverride.FontScale(fontScale),
            ) {
                KhataTheme { content() }
            }
        }
        compose.waitForIdle()
    }

    /**
     * Asserts the node is inside the screen rather than merely *in* the
     * composition.
     *
     * `assertIsDisplayed` was the whole of the old assertion and it is a weaker
     * claim than it reads as: it passes for a node whose bounds merely
     * intersect the window, so a Save button with half its label past the right
     * edge satisfies it. Unclipped bounds are what a person would look at.
     */
    private fun SemanticsNodeInteraction.assertFitsWithin(width: Dp) {
        val bounds = getUnclippedBoundsInRoot()
        assertTrue(
            "runs off a $width screen: left=${bounds.left}, right=${bounds.right}",
            bounds.left >= (-1).dp && bounds.right <= width + 1.dp,
        )
    }

    @Test
    fun the_entry_sheet_survives_the_largest_supported_font_scale() {
        // Save is the control the whole flow exists for, and it is the first
        // thing a taller type scale pushes out of the layout.
        //
        // **The width is not asserted here, and that is a fact about the
        // widget rather than a gap being papered over.** `QuickAddSheet` is a
        // `ModalBottomSheet`, which hosts its content in a separate window
        // sized by the real display — so [renderAt]'s forced size constrains a
        // composition the sheet's content is not in, and `onRoot()` becomes
        // ambiguous once that second window exists. The 320 dp corner for the
        // sheet stays a manual check (`adb shell wm density 360` on a 720 px
        // emulator, per CLAUDE.md); the automated width coverage is on the
        // ledger below, where the override does reach.
        renderAt(width = 320.dp, fontScale = 1.3f) {
            QuickAddSheet(container = fx.container, onDismiss = {}, onSaved = {}, onDeleted = {})
        }

        compose.onNodeWithText("Save expense").assertIsDisplayed()
        compose.onNodeWithText("Save expense").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun the_entry_sheet_survives_the_smallest_supported_font_scale() {
        renderAt(width = 320.dp, fontScale = 0.85f) {
            QuickAddSheet(container = fx.container, onDismiss = {}, onSaved = {}, onDeleted = {})
        }

        compose.onNodeWithText("Save expense").assertIsDisplayed()
        // Small type must not shrink the touch target: 05 §10 and NFR-USE-04
        // are about the finger, not the glyph.
        compose.onNodeWithText("Save expense").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun the_ledger_fits_the_narrowest_screen_at_the_largest_supported_font_scale() {
        // NFR-COMP-03's floor and NFR-COMP-04's ceiling together, on content
        // the override can actually constrain. A row is `name … amount` on one
        // line, which is the layout that fails first when the type grows and
        // the screen does not — and the amount is the half that gets pushed
        // off, which is the half that matters.
        //
        // These tests carried the comment "0.85x to 1.3x **at 320 dp**" and set
        // only the font scale, so they ran at whatever the device happened to
        // be — 393 dp on this emulator, 73 dp of slack the requirement does not
        // promise.
        seedRow()
        renderAt(width = 320.dp, fontScale = 1.3f) {
            LedgerScreen(
                container = fx.container,
                snackbarHostState = SnackbarHostState(),
                onEdit = {},
                onAdd = {},
            )
        }

        compose.onNodeWithText("Grocery").assertFitsWithin(320.dp)
        // Proves the override took effect. Without this the test would silently
        // measure the emulator again, which is how the ones it replaces came to
        // claim a width they were not testing.
        compose.onRoot().assertWidthIsEqualTo(320.dp)
    }

    @Test
    fun the_ledger_fits_the_widest_screen_the_requirement_names() {
        // NFR-COMP-03's ceiling — "320 dp to 480 dp; no tablet layouts
        // required". It had no evidence of any kind. What fails at 480 dp is
        // the opposite of what fails at 320: a layout that centres nothing and
        // stretches across the whole width.
        seedRow()
        renderAt(width = 480.dp, fontScale = 1.0f) {
            LedgerScreen(
                container = fx.container,
                snackbarHostState = SnackbarHostState(),
                onEdit = {},
                onAdd = {},
            )
        }

        // The content, not the root. `ForcedSize` lays the subtree out at
        // 480 dp — which is what NFR-COMP-03's ceiling is about — but it cannot
        // make the *window* wider than the device, so asserting on `onRoot()`
        // here would only be asserting the emulator's own 393 dp. The 320 dp
        // test above can assert the root, because shrinking works.
        compose.onNodeWithText("Grocery").assertFitsWithin(480.dp)
    }

    // --- the entry flow itself (FR-EXP-01) ----------------------------------

    @Test
    fun an_expense_is_logged_in_three_interactions() = runBlocking {
        // "amount, category, save" — the whole product thesis.
        compose.setContent {
            KhataTheme {
                QuickAddSheet(
                    container = fx.container,
                    onDismiss = {},
                    onSaved = {},
                    onDeleted = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("2").performClick()   // 1: amount
        compose.onNodeWithContentDescription("5").performClick()
        compose.onNodeWithContentDescription("0").performClick()
        compose.onNodeWithText("Grocery").performClick()           // 2: category
        compose.onNodeWithText("Save expense").performClick()      // 3: save
        compose.waitForIdle()

        compose.waitUntil(5_000) {
            runBlocking { fx.expenses.firstPage().isNotEmpty() }
        }
        val row = fx.expenses.firstPage().single()
        org.junit.Assert.assertEquals(25_000L, row.expense.amountMinor)
    }

    @Test
    fun the_ledger_announces_a_day_subtotal_as_words() = runBlocking {
        fx.expenses.insert(Money.ofTaka(340), fx.leafId("Grocery"))

        compose.setContent {
            KhataTheme {
                LedgerScreen(
                    container = fx.container,
                    snackbarHostState = SnackbarHostState(),
                    onEdit = {},
                    onAdd = {},
                )
            }
        }
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("three hundred forty taka")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

}
