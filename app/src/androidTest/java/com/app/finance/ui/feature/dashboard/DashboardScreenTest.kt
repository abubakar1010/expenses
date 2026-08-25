package com.app.finance.ui.feature.dashboard

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.SemanticsMatcher
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
import com.app.finance.ui.theme.KhataTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import androidx.compose.ui.test.assertCountEquals

/**
 * The dashboard as rendered — 05 §5.4 and §10.
 *
 * Two of the mock's three notes are assertions rather than opinions, and they
 * are what most of this suite is about: the hero is a decision rather than a
 * balance, and a section with nothing to say is **absent, not empty** — "an
 * empty state here would train the user to ignore the region".
 */
@RunWith(AndroidJUnit4::class)
class DashboardScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var fx: TestFixture
    private val aug = Period(202608)

    @Before fun setUp() { fx = TestFixture() }
    @After fun tearDown() = fx.closeAfterDraining()

    private fun spend(taka: Long, category: String, day: Int = 3, month: Int = 8) =
        runBlocking<Unit> {
            fx.expenses.insert(
                amount = Money.ofTaka(taka),
                categoryId = fx.leafId(category),
                spentOn = LocalDate.of(2026, month, day),
            )
        }

    private fun limit(taka: Long, category: String) = runBlocking<Unit> {
        fx.budgets.setLimit(fx.leafId(category), aug, Money.ofTaka(taka))
    }

    private fun earn(taka: Long, source: String = "Salary") = runBlocking<Unit> {
        fx.income.saveEntry(Money.ofTaka(taka), source, LocalDate.of(2026, 8, 1))
    }

    private fun show(
        period: Period = aug,
        fontScale: Float = 1f,
        onPeriodChange: (Period) -> Unit = {},
        onOpenBudget: () -> Unit = {},
    ) {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                KhataTheme {
                    DashboardScreen(
                        container = fx.container,
                        period = period,
                        onPeriodChange = onPeriodChange,
                        onOpenBudget = onOpenBudget,
                        onOpenSettings = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        // `waitForIdle` returns when *Compose* is idle, which is before the
        // nine flows have landed and the skeleton is still up. The period label
        // is present in every state, so waiting on it settles the screen.
        //
        // The label of the period this was *called with*, not August: `show`
        // takes a period and two tests pass a different one, so a hard-coded
        // "August 2026" made them wait five seconds for a month they had
        // deliberately navigated away from.
        awaitText(period.label())
    }

    private fun awaitText(
        text: String,
        substring: Boolean = false,
        ignoreCase: Boolean = false,
    ) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(text, substring = substring, ignoreCase = ignoreCase)
            .fetchSemanticsNodes().isNotEmpty()
    }

    /**
     * `ignoreCase` for section headers, because `SectionHeader` uppercases for
     * presentation and what these tests mean to assert is that the section is
     * on screen, not how it is cased.
     *
     * **And it scrolls first.** The dashboard is a `LazyColumn`, so a section
     * below the fold is never composed and is therefore absent from the
     * semantics tree entirely — indistinguishable, to a test, from a section
     * that does not exist. Whether a given section fits depends on how much
     * the fixture put above it, which is why this bit some tests and not
     * others.
     */
    private fun awaitHeader(text: String) {
        scrollTo(hasText(text, ignoreCase = true))
        awaitText(text, ignoreCase = true)
    }

    /** Best-effort: absent is left for the assertion itself to report. */
    private fun scrollTo(matcher: SemanticsMatcher) = runCatching {
        compose.onNode(hasScrollAction()).performScrollToNode(matcher)
    }

    /**
     * Money figures are asserted through the **description**, not the text.
     *
     * The hero and every ledger amount carry `clearAndSetSemantics {}` so
     * TalkBack reads the figure once, as words, instead of twice — once as a
     * sentence and again character by character. That is deliberate (05 §10),
     * so a figure is simply not present in the semantics tree as text and an
     * `onNodeWithText("৳500")` can never match it. Asserting the spoken form
     * checks the same number *and* the accessibility requirement with it.
     */
    private fun awaitSpoken(words: String) {
        scrollTo(hasContentDescription(words, substring = true))
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription(words, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitDescription(text: String) {
        scrollTo(hasContentDescription(text))
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun tree() = compose.onRoot().printToString(maxDepth = Int.MAX_VALUE)

    // --- FR-AN-01: the hero ---------------------------------------------------

    @Test
    fun the_hero_is_a_decision_rather_than_a_balance() = runBlocking<Unit> {
        // 05 §5.4: "Most finance apps put total balance or total spent at the
        // top. Neither answers a question the user has at a shop counter."
        limit(18_000, "Grocery")
        spend(9_000, "Grocery")
        show()

        awaitText("SAFE TO SPEND TODAY")
        compose.onNodeWithText("SAFE TO SPEND TODAY").assertIsDisplayed()
        // ৳9,000 left over the 18 days remaining from 14 August.
        awaitSpoken("five hundred taka")
    }

    @Test
    fun the_hero_figure_is_announced_as_words() = runBlocking<Unit> {
        // §10 — a raw currency string read character by character is "the
        // single most common accessibility failure in finance apps".
        limit(18_000, "Grocery")
        spend(9_000, "Grocery")
        show()

        awaitDescription("SAFE TO SPEND TODAY, five hundred taka")
        assertTrue(
            "the currency string must not be what is announced:\n${tree()}",
            !tree().contains("ContentDescription = 'SAFE TO SPEND TODAY, ৳500"),
        )
    }

    @Test
    fun a_negative_remainder_renders_as_zero_with_the_reason_beside_it() = runBlocking<Unit> {
        // FR-AN-01 in as many words: "the value MUST render as zero with an
        // over-budget indicator". A bare zero would read as "nothing left"
        // rather than "past the limit, by this much".
        limit(5_000, "Grocery")
        spend(9_000, "Grocery")
        show()

        awaitSpoken("zero taka")
        awaitText("৳4,000 over", substring = true)
    }

    @Test
    fun a_finished_month_reports_a_balance_rather_than_a_daily_allowance() = runBlocking<Unit> {
        runBlocking { fx.budgets.setLimit(fx.leafId("Grocery"), Period(202606), Money.ofTaka(5_000)) }
        show(period = Period(202606))

        awaitText("LEFT IN THIS MONTH")
        compose.onNodeWithText("LEFT IN THIS MONTH").assertIsDisplayed()
    }

    // --- FR-AN-02, FR-AN-03 ---------------------------------------------------

    @Test
    fun the_net_strip_carries_earned_spent_and_the_savings_rate() = runBlocking<Unit> {
        earn(48_000)
        spend(31_600, "Grocery")
        show()

        awaitText("Earned ৳48,000")
        awaitText("Spent ৳31,600")
        awaitText("Net +৳16,400 · saving 34%")
    }

    @Test
    fun a_month_with_no_income_shows_the_net_without_a_rate() = runBlocking<Unit> {
        spend(4_000, "Grocery")
        show()

        awaitText("Net −৳4,000")
        assertTrue("no rate may be shown:\n${tree()}", !tree().contains("saving"))
    }

    // --- absent, not empty ----------------------------------------------------

    @Test
    fun sections_with_nothing_to_say_are_absent() = runBlocking<Unit> {
        // The mock's second note: "An empty state here would train the user to
        // ignore the region." Six sections obey it; this checks four at once on
        // a month with spending but no alerts, changes or coverage.
        spend(1_000, "Grocery")
        show()

        awaitText("SAFE TO SPEND TODAY")
        val rendered = tree()
        assertTrue("no alert block:\n$rendered", !rendered.contains("Needs attention"))
        assertTrue("no pace block:\n$rendered", !rendered.contains("On pace to overspend"))
        assertTrue("no changes block:\n$rendered", !rendered.contains("Biggest changes"))
    }

    @Test
    fun the_coverage_line_is_absent_when_there_is_nothing_to_cover() = runBlocking<Unit> {
        // `StableCoverage` returns null rather than 100% for a month with no
        // spending: a ratio with no denominator is not a figure with a value.
        // Zero stable income against *real* spending is a different case and
        // does report — 0% is an answer, and an alarming one.
        earn(20_000)
        show()

        awaitText("Earned ৳20,000")
        assertTrue("no coverage line:\n${tree()}", !tree().contains("Stable income covers"))
    }

    @Test
    fun needs_attention_appears_only_when_something_needs_attention() = runBlocking<Unit> {
        limit(5_000, "Grocery")
        spend(6_000, "Grocery")
        show()

        awaitHeader("Needs attention")
        compose.onNodeWithText("Needs attention", ignoreCase = true).assertIsDisplayed()
        awaitText("৳1,000 over")
    }

    @Test
    fun an_empty_month_invites_rather_than_reporting() = runBlocking<Unit> {
        show()
        awaitText("Nothing logged this month", substring = true)
    }

    // --- FR-AN-04 … FR-AN-09 --------------------------------------------------

    @Test
    fun a_category_on_pace_to_overspend_gets_its_own_section() = runBlocking<Unit> {
        limit(18_000, "Grocery")
        spend(12_000, "Grocery")
        show()

        awaitHeader("On pace to overspend")
        awaitText("limit ৳18,000", substring = true)
    }

    @Test
    fun the_biggest_change_names_the_increase_not_just_the_level() = runBlocking<Unit> {
        (5..7).forEach { spend(6_000, "Grocery", month = it) }
        spend(10_000, "Grocery")
        show()

        awaitHeader("Biggest changes")
        awaitText("৳4,000 more than usual")
    }

    @Test
    fun the_spend_mix_names_each_nature_and_its_share() = runBlocking<Unit> {
        spend(12_000, "Grocery")
        spend(18_000, "House Rent")
        show()

        awaitHeader("Where it goes")
        awaitText("Variable")
        awaitText("40%")
        awaitText("60%")
    }

    @Test
    fun the_largest_expenses_are_listed() = runBlocking<Unit> {
        spend(7_000, "Grocery")
        spend(300, "Transport")
        show()

        awaitHeader("Largest expenses")
        awaitSpoken("seven thousand taka")
    }

    @Test
    fun the_trend_is_one_stop_rather_than_six() = runBlocking<Unit> {
        // Six points would be six stops in the traversal for information the
        // section already states. `MonthRibbon` and `YearBars` make the same call.
        spend(1_000, "Grocery")
        show()

        awaitHeader("Six-month trend")
        awaitDescription("Six months of spending, one thousand taka in total, none over budget")
    }

    @Test
    fun a_month_over_its_budget_is_called_out_in_the_trend_description() = runBlocking<Unit> {
        // NFR-USE-05 — the vermilion point is one signal; this is the one that
        // survives having no eyes at all.
        limit(1_000, "Grocery")
        spend(3_000, "Grocery")
        show()

        awaitHeader("Six-month trend")
        awaitDescription("Six months of spending, three thousand taka in total, 1 month over budget")
    }

    @Test
    fun the_ribbon_is_one_stop_and_carries_the_period_total() = runBlocking<Unit> {
        spend(1_240, "Grocery")
        show()

        awaitDescription("Daily spending for the period, one thousand two hundred forty taka in total")
    }

    @Test
    fun the_twelve_month_income_average_names_its_window() = runBlocking<Unit> {
        // FR-AN-10's rationale is the window, so the copy states it: a figure
        // labelled "monthly average" over a shorter one is "wrong in both
        // directions" for income that arrives in bursts.
        earn(120_000)
        show()

        awaitText("Monthly average income over 12 months: ৳10,000")
    }

    // --- navigation and NFR-COMP-04 -------------------------------------------

    @Test
    fun the_period_arrows_are_named_and_meet_the_touch_target() = runBlocking<Unit> {
        spend(1_000, "Grocery")
        show()

        compose.onNodeWithContentDescription("Previous month").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Next month").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun stepping_the_period_reports_it_to_the_owner_above_the_nav_host() = runBlocking<Unit> {
        var reported: Period? = null
        spend(1_000, "Grocery")
        show(onPeriodChange = { reported = it })

        compose.onNodeWithContentDescription("Previous month").performClick()
        assertTrue("expected 202607, got $reported", reported == Period(202607))
    }

    @Test
    fun an_actionable_row_leads_to_the_budget_screen() = runBlocking<Unit> {
        // Everything actionable here is about a limit, and the limit editor is
        // one tab away.
        var opened = false
        limit(5_000, "Grocery")
        spend(6_000, "Grocery")
        show(onOpenBudget = { opened = true })

        awaitHeader("Needs attention")
        // Twice on the screen: the alert, and the budget row underneath it.
        // The first is the alert.
        compose.onAllNodesWithText("Grocery")[0].performClick()
        assertTrue(opened)
    }

    @Test
    fun the_screen_holds_together_at_the_largest_supported_font_scale() = runBlocking<Unit> {
        // NFR-COMP-04 — 0.85x to 1.3x. The net strip is the tightest layout
        // here: two figures on one line, then a third with a percentage.
        earn(48_000)
        limit(18_000, "Grocery")
        spend(31_600, "Grocery")
        show(fontScale = 1.3f)

        awaitText("SAFE TO SPEND TODAY")
        awaitText("Earned ৳48,000")
        compose.onNodeWithText("Earned ৳48,000").assertIsDisplayed()
    }

    @Test
    fun the_screen_holds_together_at_the_smallest_supported_font_scale() = runBlocking<Unit> {
        limit(18_000, "Grocery")
        spend(9_000, "Grocery")
        show(fontScale = 0.85f)

        awaitSpoken("five hundred taka")
    }

    // --- absent, not empty, applied to rows (A5) ------------------------------

    @Test
    fun a_leaf_with_neither_spend_nor_a_limit_is_absent() = runBlocking<Unit> {
        // Thirteen ৳0 rows on a fresh install pushed FR-AN-04 through FR-AN-09
        // below the fold. 05 §5.4's rule is about anything with nothing to say.
        spend(1_000, "Grocery")
        show()

        awaitText("Grocery")
        val rendered = tree()
        assertTrue("Grocery has spend:\n$rendered", rendered.contains("Grocery"))
        assertTrue("Mobile Recharge has nothing:\n$rendered", !rendered.contains("Mobile Recharge"))
        assertTrue("nor does Household:\n$rendered", !rendered.contains("Household"))
    }

    @Test
    fun a_leaf_with_a_limit_and_no_spend_is_still_shown() = runBlocking<Unit> {
        // A limit is something to say: it is the plan, and the row reports
        // progress against it even at zero.
        limit(18_000, "Grocery")
        spend(500, "Transport")
        show()

        awaitText("Grocery")
        compose.onAllNodesWithText("Grocery")[0].assertIsDisplayed()
    }

    @Test
    fun a_group_with_no_surviving_leaf_takes_its_header_with_it() = runBlocking<Unit> {
        // A header over nothing is the same empty region the rule is about.
        spend(1_000, "Grocery")
        show()

        awaitText("Grocery")
        val rendered = tree()
        assertTrue("no fixed group:\n$rendered", !rendered.contains("Fixed Expenses"))
        assertTrue("no unpredictable group:\n$rendered", !rendered.contains("Unpredictable Expenses"))
    }
    // --- 05 §5.3: the mix says what its percentages are of --------------------

    @Test
    fun the_mix_carries_no_caption_when_it_reconciles_with_the_total() {
        // The ordinary case, and the one that decides whether the caption is
        // noise. If it appears here it appears on every screen every month, and
        // a line that is always there is a line nobody reads on the day it
        // matters.
        spend(9_500, "Grocery")
        spend(2_000, "Medical")
        show()

        // Scroll first, for the reason `awaitHeader` documents: the dashboard
        // is a `LazyColumn`, so a section below the fold is never composed and
        // is absent from the semantics tree — which is exactly what a test
        // asserting *absence* must not confuse it with.
        awaitHeader("Where it goes")
        scrollTo(hasText(CAPTION, substring = true))

        compose.onAllNodesWithText(CAPTION, substring = true).assertCountEquals(0)
    }

    @Test
    fun a_nature_whose_refunds_outweigh_its_spending_is_named_under_the_mix() {
        // FR-EXP-06 makes a negative expense a refund, so a nature's net can be
        // below zero for a period. It cannot be drawn as a slice — a pie has no
        // negative width — so the percentages are of a smaller number than the
        // figure above them. §22.10 recorded that as an open framing question;
        // 05 §5.4 is the answer and this is it on screen.
        spend(5_000, "Grocery")
        spend(-1_000, "Medical")
        show()

        awaitHeader("Where it goes")
        scrollTo(hasText(CAPTION, substring = true))

        // The share itself is not asserted here, and cannot be: `MixRow` draws
        // through `LedgerRow`, which carries `clearAndSetSemantics {}` so
        // TalkBack reads the figure once as words (05 §10) — so "100%" is not
        // in the tree as text at all. `SpendMixTest` owns the arithmetic. What
        // this test is for is the sentence underneath it.
        awaitText(CAPTION, substring = true)
    }

    private companion object {
        /** The distinctive half of `mix_excludes`, so the figure can vary. */
        const val CAPTION = "sits outside them"
    }

}
