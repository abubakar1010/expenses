package com.app.finance.ui.feature.income

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithText
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
import com.app.finance.core.time.Period
import com.app.finance.domain.model.IncomeKind
import com.app.finance.ui.theme.DayBookTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The income screen as rendered — 05 §5.7 and §10.
 *
 * Two things here are pixels and are checked by eye against a greyscale
 * capture: the filled-versus-hollow source dot, and the twelve bars. What is
 * assertable is that neither is the *only* carrier of its meaning — the dot's
 * word is in the spoken description, and the bars' total is too. That is the
 * same NFR-USE-05 argument the budget screen's suite makes.
 */
@RunWith(AndroidJUnit4::class)
class IncomeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var fx: TestFixture
    private val aug = Period(202608)

    @Before fun setUp() { fx = TestFixture() }
    @After fun tearDown() = fx.closeAfterDraining()

    private fun earn(
        taka: Long,
        source: String,
        month: Int = 8,
        day: Int = 1,
        year: Int = 2026,
    ) = runBlocking<Unit> {
        fx.income.saveEntry(Money.ofTaka(taka), source, LocalDate.of(year, month, day))
    }

    private fun spend(taka: Long, category: String = "Grocery", month: Int = 8) = runBlocking<Unit> {
        fx.expenses.insert(
            amount = Money.ofTaka(taka),
            categoryId = fx.leafId(category),
            spentOn = LocalDate.of(2026, month, 2),
        )
    }

    private fun show(
        period: Period = aug,
        fontScale: Float = 1f,
        onPeriodChange: (Period) -> Unit = {},
        onManageSources: () -> Unit = {},
    ) {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                DayBookTheme {
                    IncomeScreen(
                        container = fx.container,
                        period = period,
                        onPeriodChange = onPeriodChange,
                        snackbarHostState = SnackbarHostState(),
                        onManageSources = onManageSources,
                    )
                }
            }
        }
        compose.waitForIdle()
        // `waitForIdle` returns when *Compose* is idle, which is before the
        // ViewModel's flows have landed and the skeleton is still up. The scope
        // chips are present in every state, so waiting on one settles the
        // screen and stops a test that asserts nothing about content from
        // tearing the database down under a query still in flight.
        awaitText("Year")
    }

    private fun awaitText(text: String, substring: Boolean = false) =
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text, substring = substring)
                .fetchSemanticsNodes().isNotEmpty()
        }

    private fun awaitDescription(text: String) {
        scrollTo(hasContentDescription(text))
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Money is asserted through the **spoken description**, for two reasons
     * that both bit this class.
     *
     * The figures carry `clearAndSetSemantics {}` so TalkBack reads each one
     * once, as words (05 §10), which leaves them out of the semantics tree as
     * text entirely — `onNodeWithText("৳30,000")` cannot match a hero figure
     * no matter what the number is.
     *
     * And the expected string was **locale-dependent and unpinned**. These
     * tests hard-coded South Asian grouping — `৳1,10,000` — which is right on
     * a bn-BD phone and wrong on the en-GB one they were run on, where
     * FR-APP-05's "grouping per the device locale" correctly produces
     * `৳110,000`. Deriving the expectation from the same [Money] the fixture
     * inserted keeps the assertion about the amount rather than about the
     * phone it happens to be running on.
     */
    private fun awaitSpoken(money: Money) {
        val words = money.spokenForm()
        scrollTo(hasContentDescription(words, substring = true))
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription(words, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The screen is a `LazyColumn`: below the fold is not composed at all. */
    private fun scrollTo(matcher: SemanticsMatcher) = runCatching {
        compose.onNode(hasScrollAction()).performScrollToNode(matcher)
    }

    // --- 05 §5.7: the year is the default ------------------------------------

    @Test
    fun the_screen_opens_on_the_year_and_says_so() = runBlocking<Unit> {
        // The single most important accommodation on this screen. A farming
        // month showing ৳0 is alarming and meaningless in isolation.
        earn(30_000, "Salary", month = 8)
        earn(80_000, "Farming", month = 2)
        show()

        awaitText("EARNED THIS YEAR")
        compose.onNodeWithText("EARNED THIS YEAR").assertIsDisplayed()
        // Both months are inside the total; neither alone is.
        awaitSpoken(Money.ofTaka(110_000))
    }

    @Test
    fun the_year_label_is_the_year_and_the_month_label_is_the_month() = runBlocking<Unit> {
        earn(30_000, "Salary")
        show()

        awaitText("2026")
        compose.onNodeWithText("Month").performClick()
        awaitText("EARNED THIS MONTH")
    }

    @Test
    fun switching_to_the_month_narrows_the_figures() = runBlocking<Unit> {
        earn(30_000, "Salary", month = 8)
        earn(80_000, "Farming", month = 2)
        show()

        awaitSpoken(Money.ofTaka(110_000))
        compose.onNodeWithText("Month").performClick()
        awaitSpoken(Money.ofTaka(30_000))
    }

    // --- §10: figures as words ------------------------------------------------

    @Test
    fun the_hero_figure_is_announced_as_words() = runBlocking<Unit> {
        // "The single most common accessibility failure in finance apps" — a
        // raw currency string read character by character.
        earn(30_000, "Salary")
        show()

        awaitDescription("EARNED THIS YEAR, thirty thousand taka")
        compose.onNodeWithContentDescription("EARNED THIS YEAR, thirty thousand taka")
            .assertIsDisplayed()

        val tree = compose.onRoot().printToString(maxDepth = Int.MAX_VALUE)
        assertTrue(
            "the currency string must not be what is announced:\n$tree",
            !tree.contains("ContentDescription = 'EARNED THIS YEAR, ৳30,000"),
        )
    }

    @Test
    fun a_source_row_announces_its_amount_its_share_and_its_kind() = runBlocking<Unit> {
        // The filled/hollow dot is a shape, and a shape has no text. The word
        // is what carries the same meaning to a screen reader — 05 §5.7's
        // reason for choosing a shape at all was greyscale, not silence.
        earn(30_000, "Salary")
        show()

        awaitDescription("Salary, thirty thousand taka, 100% of the total, Stable")
        compose.onNodeWithContentDescription("Salary, thirty thousand taka, 100% of the total, Stable")
            .assertIsDisplayed()
    }

    @Test
    fun a_variable_source_says_variable() = runBlocking<Unit> {
        // Inline creation defaults to Variable, so this is also the announced
        // form of the state a new source lands in.
        earn(80_000, "Farming")
        show()

        awaitDescription("Farming, eighty thousand taka, 100% of the total, Variable")
    }

    @Test
    fun the_trend_is_one_stop_rather_than_twelve() = runBlocking<Unit> {
        // Twelve bars would be twelve stops in the traversal for information
        // the hero figure already states. `MonthRibbon` makes the same call.
        earn(30_000, "Salary", month = 3)
        show()

        awaitDescription("Income by month, thirty thousand taka in total")
    }

    // --- FR-IE-06 -------------------------------------------------------------

    @Test
    fun the_breakdown_percentages_are_shown_and_sum_to_a_hundred() = runBlocking<Unit> {
        earn(100, "A")
        earn(100, "B")
        earn(100, "C")
        show()

        awaitText("34%")
        // 33 + 33 + 33 would be 99. Largest-remainder apportionment gives one
        // source the leftover point, which is what the criterion requires.
        compose.onNodeWithText("34%").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("33%").fetchSemanticsNodes().size == 2)
    }

    // --- 05 §5.7's closing line ----------------------------------------------

    @Test
    fun the_coverage_line_appears_once_there_is_spending_to_cover() = runBlocking<Unit> {
        runBlocking { fx.income.createSource("Wages", IncomeKind.STABLE) }
        earn(40_000, "Wages")
        spend(80_000)
        show()

        awaitText("Stable income covers 50% of your spending this year.")
    }

    @Test
    fun the_coverage_line_is_absent_rather_than_zero_when_nothing_was_spent() = runBlocking<Unit> {
        // 05 §5.4: "Sections that have nothing to say are absent, not empty."
        // Coverage of nothing is not a number, and printing 100% would be a
        // confident lie.
        earn(30_000, "Salary")
        show()

        awaitText("EARNED THIS YEAR")
        val tree = compose.onRoot().printToString(maxDepth = Int.MAX_VALUE)
        assertTrue("no coverage line may be shown:\n$tree", !tree.contains("Stable income covers"))
    }

    // --- empty and entry -----------------------------------------------------

    @Test
    fun an_empty_year_invites_rather_than_reports() = runBlocking<Unit> {
        show()
        awaitText("Nothing recorded here yet", substring = true)
        // Two of them: the empty state's invitation and the screen's own
        // action. Both are meant to be there, so this asserts the first
        // rather than that there is only one.
        compose.onAllNodesWithText("Add income")[0].assertIsDisplayed()
    }

    @Test
    fun the_add_action_opens_the_entry_sheet() = runBlocking<Unit> {
        // NFR-USE-01 keeps the FAB on expense entry from every screen, so
        // income entry lives in the header — and it has to actually be there.
        earn(30_000, "Salary")
        show()

        awaitText("EARNED THIS YEAR")
        compose.onAllNodesWithText("Add income")[0].performClick()
        awaitText("Where did it come from?")
    }

    @Test
    fun the_entries_section_lists_what_was_recorded() = runBlocking<Unit> {
        earn(30_000, "Salary", month = 8, day = 12)
        show()

        awaitText("ENTRIES")
        awaitText("12 Aug 2026")
    }

    @Test
    fun the_sources_action_is_present_and_reachable() = runBlocking<Unit> {
        var opened = false
        earn(30_000, "Salary")
        show(onManageSources = { opened = true })

        awaitText("EARNED THIS YEAR")
        compose.onNodeWithText("Sources").performClick()
        assertTrue(opened)
    }

    // --- NFR-USE-04, NFR-COMP-04 ---------------------------------------------

    @Test
    fun the_period_arrows_are_named_and_meet_the_touch_target() = runBlocking<Unit> {
        // §10 names "period arrows" explicitly, and NFR-USE-04 puts every
        // target at 48 dp. In year scope they say "year", not "month".
        earn(30_000, "Salary")
        show()

        compose.onNodeWithContentDescription("Previous year")
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Next year")
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun stepping_the_year_writes_back_to_the_shared_period() = runBlocking<Unit> {
        // The period is owned above the NavHost. A year step keeps the month
        // and moves twelve, so Budget and Dashboard land on the same month of
        // the previous year rather than somewhere of their own.
        var reported: Period? = null
        earn(30_000, "Salary")
        show(onPeriodChange = { reported = it })

        awaitText("EARNED THIS YEAR")
        compose.onNodeWithContentDescription("Previous year").performClick()
        assertTrue("expected 202508, got $reported", reported == Period(202508))
    }

    @Test
    fun the_screen_holds_together_at_the_largest_supported_font_scale() = runBlocking<Unit> {
        // NFR-COMP-04 — 0.85x to 1.3x. The source row is the tightest layout
        // here: name, figure, percentage and dot on one line.
        earn(360_000, "Salary")
        earn(144_000, "Real estate")
        show(fontScale = 1.3f)

        awaitText("EARNED THIS YEAR")
        awaitSpoken(Money.ofTaka(504_000))
        compose.onAllNodesWithText("Real estate")[0].assertIsDisplayed()
    }

    @Test
    fun the_screen_holds_together_at_the_smallest_supported_font_scale() = runBlocking<Unit> {
        earn(30_000, "Salary")
        show(fontScale = 0.85f)

        awaitSpoken(Money.ofTaka(30_000))
    }

    // --- 05 §9's zero-income month -------------------------------------------

    @Test
    fun an_empty_month_reframes_to_the_year_rather_than_reporting_a_failure() = runBlocking<Unit> {
        // 05 §9: "Nothing recorded in August. Your year is at ৳5,84,000" — the
        // guide singles this line out because it "refuses to render an empty
        // month as a failure, and immediately reframes to the unit that is
        // meaningful for this user". A farming August at ৳0 is precisely the
        // situation the year-first default exists for.
        earn(80_000, "Farming", month = 2)
        show()

        awaitText("EARNED THIS YEAR")
        compose.onNodeWithText("Month").performClick()

        awaitText("Nothing recorded in August 2026", substring = true)
        compose.onNodeWithText("৳80,000", substring = true).assertIsDisplayed()
    }

    @Test
    fun an_empty_month_with_nothing_behind_it_keeps_the_invitation() = runBlocking<Unit> {
        // Nothing to reframe to. "Your year is at ৳0" would be a worse sentence
        // than the invitation it replaced.
        show()
        awaitText("Nothing recorded here yet", substring = true)
        compose.onNodeWithText("Month").performClick()

        awaitText("Nothing recorded here yet", substring = true)
        val tree = compose.onRoot().printToString(maxDepth = Int.MAX_VALUE)
        assertTrue("no year to reframe to:\n$tree", !tree.contains("Your year is at"))
    }

    // --- the range arrows -----------------------------------------------------

    @Test
    fun the_arrows_are_named_for_the_range_when_the_scope_is_one() = runBlocking<Unit> {
        // They mean something different here — they shift the range rather than
        // stepping the shared period — so they must not keep announcing
        // "Previous month", which was untrue in two ways at once.
        earn(30_000, "Salary")
        show()

        awaitText("EARNED THIS YEAR")
        compose.onNodeWithText("Range").performClick()

        awaitDescription("Previous range")
        compose.onNodeWithContentDescription("Previous range").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Next range").assertIsDisplayed()
    }

    @Test
    fun stepping_in_range_scope_leaves_the_shared_period_alone() = runBlocking<Unit> {
        // The defect this replaces: the arrows moved Budget and Dashboard while
        // this screen held perfectly still.
        var reported: Period? = null
        earn(30_000, "Salary")
        show(onPeriodChange = { reported = it })

        awaitText("EARNED THIS YEAR")
        compose.onNodeWithText("Range").performClick()
        awaitDescription("Previous range")
        compose.onNodeWithContentDescription("Previous range").performClick()

        compose.waitForIdle()
        assertTrue("the shared period must not move, got $reported", reported == null)
    }
}
