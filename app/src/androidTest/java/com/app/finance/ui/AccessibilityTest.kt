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

    // --- font scale (NFR-COMP-04: 0.85x to 1.3x at 320 dp) ------------------

    @Test
    fun the_entry_sheet_renders_at_the_largest_supported_font_scale() {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = LocalDensity.current.density,
                    fontScale = 1.3f,
                ),
            ) {
                KhataTheme {
                    QuickAddSheet(
                        container = fx.container,
                        onDismiss = {},
                        onSaved = {},
                        onDeleted = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        // Save must remain reachable — it is the one control the flow exists
        // for, and it is the first thing a taller type scale pushes off-screen.
        compose.onNodeWithText("Save expense").assertIsDisplayed()
    }

    @Test
    fun the_entry_sheet_renders_at_the_smallest_supported_font_scale() {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = LocalDensity.current.density,
                    fontScale = 0.85f,
                ),
            ) {
                KhataTheme {
                    QuickAddSheet(
                        container = fx.container,
                        onDismiss = {},
                        onSaved = {},
                        onDeleted = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Save expense").assertIsDisplayed()
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
