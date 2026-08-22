package com.app.finance.ui.feature.budget

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.core.money.Money
import com.app.finance.core.time.Period
import com.app.finance.ui.theme.KhataTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode

/**
 * The budget screen as rendered — 05 §3.3, §5.4, §10.
 *
 * NFR-USE-05 is the requirement most of this exists for: "Budget states MUST be
 * distinguishable without colour: state is carried by colour **and** fill
 * treatment **and** text." Colour and fill are pixels and are checked by eye
 * against a greyscale capture; the *text* half is assertable, and it is the
 * signal that survives every accessibility setting, so it is the one tested
 * here — every state must put its condition into words.
 */
@RunWith(AndroidJUnit4::class)
class BudgetScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var fx: TestFixture
    private val aug = Period(202608)
    private val jul = Period(202607)

    @Before fun setUp() { fx = TestFixture() }
    @After fun tearDown() = fx.closeAfterDraining()

    private fun spend(taka: Long, category: String, day: Int = 3, period: Period = aug) = runBlocking<Unit> {
        fx.expenses.insert(
            amount = Money.ofTaka(taka),
            categoryId = fx.leafId(category),
            spentOn = LocalDate.of(period.ym / 100, period.ym % 100, day),
        )
    }

    private fun limit(taka: Long, category: String, period: Period = aug) = runBlocking<Unit> {
        fx.budgets.setLimit(fx.leafId(category), period, Money.ofTaka(taka))
    }

    private fun show(
        period: Period = aug,
        fontScale: Float = 1f,
        onPeriodChange: (Period) -> Unit = {},
        onManageCategories: () -> Unit = {},
    ) {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                KhataTheme {
                    BudgetScreen(
                        container = fx.container,
                        period = period,
                        onPeriodChange = onPeriodChange,
                        snackbarHostState = SnackbarHostState(),
                        onManageCategories = onManageCategories,
                    )
                }
            }
        }
        compose.waitForIdle()
        // `waitForIdle` returns when *Compose* is idle, which is before the
        // ViewModel's flows have landed — the skeleton is still up. Waiting for
        // a group header that always exists (three roots, thirteen leaves are
        // seeded) settles the screen, and stops a test that asserts nothing
        // about content from tearing the database down under a query still in
        // flight.
        awaitText("VARIABLE EXPENSES")
    }

    private fun awaitText(text: String, substring: Boolean = false) =
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text, substring = substring)
                .fetchSemanticsNodes().isNotEmpty()
        }

    // --- NFR-USE-05: every state says what it is -----------------------------

    @Test
    fun an_under_budget_leaf_states_what_is_left_and_the_percentage() = runBlocking<Unit> {
        limit(1_000, "Grocery")
        spend(300, "Grocery")
        show()

        awaitText("৳700 left")
        compose.onNodeWithText("৳700 left").assertIsDisplayed()
        // FR-BUD-05 wants the limit on the row too, not only the percentage —
        // "30%" alone leaves "30% of what?" unanswered, and the group total
        // above is a sum across every leaf in the root, not this one's limit.
        compose.onNodeWithText("30% of ৳1,000").assertIsDisplayed()
    }

    @Test
    fun an_approaching_leaf_still_says_left_but_crosses_the_threshold() = runBlocking<Unit> {
        // FR-BUD-06's acceptance figures: ৳5,600 of ৳7,000 is exactly 80%.
        limit(7_000, "Grocery")
        spend(5_600, "Grocery")
        show()

        awaitText("৳1,400 left")
        compose.onNodeWithText("80% of ৳7,000").assertIsDisplayed()
    }

    @Test
    fun an_over_budget_leaf_says_over_and_shows_a_percentage_above_a_hundred() = runBlocking<Unit> {
        // The percentage is deliberately unclamped — a bar that pins at 100%
        // while the text says "over" is two signals disagreeing.
        limit(1_000, "Grocery")
        spend(1_040, "Grocery")
        show()

        awaitText("৳40 over")
        compose.onNodeWithText("104% of ৳1,000").assertIsDisplayed()
    }

    @Test
    fun spending_exactly_the_limit_says_so_instead_of_zero_over() = runBlocking<Unit> {
        // FR-BUD-06 puts the over-budget state at >= 100%, so this row is OVER —
        // but the overspend is zero, and "৳0 over" is not something anyone acts
        // on. The state is unchanged; only the sentence for it is.
        limit(1_000, "Grocery")
        spend(1_000, "Grocery")
        show()

        awaitText("Limit reached")
        compose.onNodeWithText("100% of ৳1,000").assertIsDisplayed()
        val tree = compose.onRoot().printToString(maxDepth = Int.MAX_VALUE)
        assertTrue("no row may read a zero overspend:\n$tree", !tree.contains("৳0 over"))
    }

    @Test
    fun an_unbudgeted_leaf_states_the_absence_and_offers_the_fix_in_the_same_line() = runBlocking<Unit> {
        // §9's "No limit set. Set one" — the row that would otherwise be the
        // empty state's call to action.
        spend(880, "Dining Out")
        show()

        awaitText("No limit set")
        compose.onNodeWithText("Dining Out").performScrollTo().assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Set one").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun an_unpredictable_leaf_never_says_left() = runBlocking<Unit> {
        // FR-BUD-07 — "a buffer, not a plan. Under-spending it is a win, not an
        // unused allocation." So it reads "৳2,400 of ৳5,000", and no percentage.
        limit(5_000, "Medical")
        spend(2_400, "Medical")
        show()

        awaitText("৳2,400 of ৳5,000")
        // Twice, and correctly so: the group header totals its one leaf, and
        // the leaf states the same figures. Both must read "of", not "left".
        assertEquals(
            2,
            compose.onAllNodesWithText("৳2,400 of ৳5,000").fetchSemanticsNodes().size,
        )

        val tree = compose.onRoot().printToString(maxDepth = Int.MAX_VALUE)
        assertTrue(
            "an unpredictable category must not be told what it has left:\n$tree",
            !tree.contains("৳2,600 left"),
        )
    }

    // --- the needs-attention block ------------------------------------------

    @Test
    fun the_needs_attention_block_is_absent_when_nothing_needs_attention() = runBlocking<Unit> {
        // 05 §5.4 — "An empty state here would train the user to ignore the
        // region. Sections that have nothing to say are absent, not empty."
        limit(10_000, "Grocery")
        spend(500, "Grocery")
        show()

        awaitText("৳9,500 left")
        assertEquals(
            0,
            compose.onAllNodesWithText("NEEDS ATTENTION").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun the_needs_attention_block_appears_with_the_worst_problem_first() = runBlocking<Unit> {
        limit(1_000, "Grocery")
        limit(1_000, "Transport")
        spend(1_400, "Grocery")
        spend(850, "Transport")
        show()

        awaitText("NEEDS ATTENTION")
        compose.onNodeWithText("NEEDS ATTENTION").assertIsDisplayed()
        // The approaching line carries the time left: ৳150 with six days to go
        // is a different situation from ৳150 with one.
        val days = aug.daysRemainingInclusive(fx.today)
        compose.onNodeWithText("৳150 left · $days days to go").assertIsDisplayed()
    }

    // --- group headers -------------------------------------------------------

    @Test
    fun a_group_total_is_never_abbreviated() = runBlocking<Unit> {
        // §5.4's mock writes "18k"; §4.3 forbids it outright — "Never abbreviate
        // to 1.2k. In a ledger, precision is the product."
        limit(12_000, "Grocery")
        limit(6_000, "Transport")
        spend(7_280, "Grocery")
        spend(5_120, "Transport")
        show()

        awaitText("৳12,400 of ৳18,000")
        compose.onNodeWithText("৳12,400 of ৳18,000").assertIsDisplayed()

        val tree = compose.onRoot().printToString(maxDepth = Int.MAX_VALUE)
        assertTrue("found an abbreviated figure:\n$tree", !tree.contains("18k"))
    }

    @Test
    fun variable_expenses_are_listed_above_fixed_ones() = runBlocking<Unit> {
        // "Fixed expenses sit below variable ones, despite being larger,
        // because rent is not a decision."
        spend(500, "Grocery")
        spend(12_000, "House Rent")
        show()

        awaitText("VARIABLE EXPENSES")

        // The screen is a `LazyColumn`, so "FIXED EXPENSES" is not composed
        // until it is scrolled to — and comparing two `indexOf`s in a printed
        // tree silently passed a `-1` for the header that was never there. It
        // held until money figures got a few pixels taller and pushed the
        // section past the fold, which is a test failing for a reason that has
        // nothing to do with what it asserts. The rule itself is proven by
        // `BudgetViewModelTest` and `BudgetSummaryTest`; what a screen test can
        // add is that the rendering follows it.
        compose.onNode(hasScrollAction())
            .performScrollToNode(hasText("FIXED EXPENSES", ignoreCase = true))
        awaitText("FIXED EXPENSES")

        val variable = compose.onAllNodesWithText("VARIABLE EXPENSES").fetchSemanticsNodes()
        val fixed = compose.onAllNodesWithText("FIXED EXPENSES").fetchSemanticsNodes()
        assertTrue("fixed expenses must be reachable", fixed.isNotEmpty())
        assertTrue(
            "ordering must be by actionability, not by amount",
            // Either variable scrolled off the top to get here — which is what
            // "above" means in a scrolling list — or both are on screen and
            // variable sits higher.
            variable.isEmpty() ||
                variable.first().positionInRoot.y < fixed.first().positionInRoot.y,
        )
    }

    // --- §10, accessibility --------------------------------------------------

    @Test
    fun the_period_arrows_are_named_and_clear_the_48dp_target() = runBlocking<Unit> {
        // §10 lists "period arrows" by name in the touch-target row. The glyph
        // is 24 dp, which is exactly how this requirement usually gets missed.
        show()
        listOf("Previous month", "Next month").forEach { name ->
            compose.onNodeWithContentDescription(name)
                .assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp)
        }
    }

    @Test
    fun the_arrows_move_the_period_in_both_directions() = runBlocking<Unit> {
        var seen: Period? = null
        show(onPeriodChange = { seen = it })

        compose.onNodeWithContentDescription("Previous month").performClick()
        assertEquals(jul, seen)

        compose.onNodeWithContentDescription("Next month").performClick()
        assertEquals(aug.next(), seen)
    }

    @Test
    fun a_budget_row_announces_every_figure_as_words() = runBlocking<Unit> {
        // §10 — "the single most common accessibility failure in finance apps".
        // The row is one merged node, so the whole sentence is one announcement.
        limit(1_000, "Grocery")
        spend(300, "Grocery")
        show()

        awaitText("৳700 left")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription(
                "Grocery, three hundred taka, 30% of one thousand taka, " +
                    "seven hundred taka left",
            ).fetchSemanticsNodes().isNotEmpty()
        }

        val tree = compose.onRoot().printToString(maxDepth = Int.MAX_VALUE)
        assertTrue(
            "the currency string must not be what is announced:\n$tree",
            !tree.contains("ContentDescription = 'Grocery, ৳300"),
        )
    }

    @Test
    fun an_alert_announces_its_figure_as_words_too() = runBlocking<Unit> {
        limit(1_000, "Grocery")
        spend(1_400, "Grocery")
        show()

        awaitText("NEEDS ATTENTION")
        compose.onNodeWithContentDescription("Grocery, four hundred taka over").assertIsDisplayed()
    }

    @Test
    fun the_screen_holds_together_at_the_largest_supported_font_scale() = runBlocking<Unit> {
        // NFR-COMP-04 — 0.85x to 1.3x. The two-line row with a bar between is
        // the tightest layout in the app.
        limit(1_000, "Grocery")
        spend(1_040, "Grocery")
        show(fontScale = 1.3f)

        awaitText("৳40 over")
        // Two matches at this point — the needs-attention line and the row —
        // which is the correct behaviour, so this asserts the row is reachable
        // rather than that the phrase is unique.
        assertTrue(compose.onAllNodesWithText("৳40 over").fetchSemanticsNodes().size >= 2)
        compose.onNodeWithText("104% of ৳1,000").assertIsDisplayed()
    }

    @Test
    fun the_screen_holds_together_at_the_smallest_supported_font_scale() = runBlocking<Unit> {
        limit(1_000, "Grocery")
        spend(300, "Grocery")
        show(fontScale = 0.85f)

        awaitText("৳700 left")
        compose.onNodeWithText("৳700 left").assertIsDisplayed()
    }

    // --- the limit sheet -----------------------------------------------------

    @Test
    fun tapping_a_row_opens_the_keypad_and_saving_moves_the_bar() = runBlocking<Unit> {
        // FR-BUD-01 end to end, through the same keypad the entry sheet uses so
        // setting a limit feels like entering an amount.
        spend(600, "Grocery")
        show()

        awaitText("No limit set")
        compose.onNodeWithText("Grocery").performScrollTo().performClick()

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Save limit").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("1").performClick()
        compose.onNodeWithContentDescription("0").performClick()
        compose.onNodeWithContentDescription("0").performClick()
        compose.onNodeWithContentDescription("0").performClick()
        compose.onNodeWithText("Save limit").performClick()

        compose.waitUntil(5_000) {
            runBlocking { fx.budgets.limitFor(fx.leafId("Grocery"), aug) != null }
        }
        assertEquals(Money.ofTaka(1_000), fx.budgets.limitFor(fx.leafId("Grocery"), aug))
        awaitText("৳400 left")
    }

    @Test
    fun the_sheet_offers_clear_limit_as_the_only_route_back_to_unbudgeted() = runBlocking<Unit> {
        limit(1_000, "Grocery")
        spend(600, "Grocery")
        show()

        awaitText("৳400 left")
        compose.onNodeWithText("Grocery").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Clear limit").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Clear limit").performClick()

        compose.waitUntil(5_000) {
            runBlocking { fx.budgets.limitFor(fx.leafId("Grocery"), aug) == null }
        }
        awaitText("No limit set")
    }

    // --- FR-BUD-04 -----------------------------------------------------------

    @Test
    fun the_copy_affordance_states_why_it_is_unavailable() = runBlocking<Unit> {
        // 05 §8 — a disabled control with no reason is a dead end.
        show()
        awaitText("No limits set last month")
        compose.onNodeWithText("No limits set last month").assertIsDisplayed()
    }

    @Test
    fun copying_last_months_limits_fills_the_gaps_from_the_screen() = runBlocking<Unit> {
        limit(8_000, "Grocery", period = jul)
        limit(3_000, "Transport", period = jul)
        show()

        awaitText("Copy last month")
        compose.onNodeWithText("Copy last month").performClick()

        compose.waitUntil(5_000) {
            runBlocking { fx.budgets.limitFor(fx.leafId("Transport"), aug) != null }
        }
        assertEquals(Money.ofTaka(8_000), fx.budgets.limitFor(fx.leafId("Grocery"), aug))
        awaitText("৳8,000 left")
    }

    // --- navigation ----------------------------------------------------------

    @Test
    fun the_categories_action_is_present_and_reachable() = runBlocking<Unit> {
        // The category manager is a detail route off this screen — the four nav
        // slots are taken and there is no Settings screen yet.
        var opened = false
        show(onManageCategories = { opened = true })
        compose.onNodeWithText("Categories").performClick()
        assertTrue(opened)
    }
}
