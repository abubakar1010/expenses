package com.app.finance.domain.model

import com.app.finance.core.money.Money
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * FR-BUD-06: "The system MUST surface a warning state at ≥80% and an
 * over-budget state at ≥100% of a leaf limit."
 *
 * Both boundaries are inclusive, which the requirement's own acceptance
 * criterion pins down: "Spend of ৳5,600 against a ৳7,000 limit renders the
 * warning state; ৳7,000 renders over-budget." Those two figures are the first
 * two tests below, verbatim.
 */
class BudgetStatusTest {

    private fun state(spent: Long, limit: Long) =
        BudgetStatus.of(Money.ofTaka(spent), Money.ofTaka(limit)).state

    // --- the acceptance criterion, literally --------------------------------

    @Test
    fun `5,600 against 7,000 is the warning state`() {
        assertEquals(BudgetState.NEAR, state(5_600, 7_000))
    }

    @Test
    fun `7,000 against 7,000 is over budget, not warning`() {
        assertEquals(BudgetState.OVER, state(7_000, 7_000))
    }

    // --- boundaries ---------------------------------------------------------

    @Test
    fun `just under eighty percent is still under`() {
        // 5,599 / 7,000 = 79.99%
        assertEquals(BudgetState.UNDER, state(5_599, 7_000))
    }

    @Test
    fun `exactly eighty percent is near`() {
        assertEquals(BudgetState.NEAR, state(80, 100))
    }

    @Test
    fun `just under the limit is near, not over`() {
        assertEquals(BudgetState.NEAR, state(6_999, 7_000))
    }

    @Test
    fun `past the limit is over`() {
        assertEquals(BudgetState.OVER, state(7_280, 7_000))
    }

    @Test
    fun `at exactly the limit the state is over but the overspend is nothing`() {
        // Both halves matter. FR-BUD-06 puts the boundary at >= 100%, so the
        // state must be OVER — and the excess is genuinely zero, which is why
        // the screen has a sentence of its own for this row rather than letting
        // the general copy print "0 over". The calculation is right; it is the
        // wording built on top of it that needed the case.
        val status = BudgetStatus.of(Money.ofTaka(7_000), Money.ofTaka(7_000))
        assertEquals(BudgetState.OVER, status.state)
        assertEquals(Money.ZERO, status.overspend)
        assertEquals(Money.ZERO, status.remaining)
        assertEquals(100, status.percentConsumed)
    }

    @Test
    fun `no limit is unbudgeted whatever the spend`() {
        assertEquals(BudgetState.UNBUDGETED, state(0, 0))
        assertEquals(BudgetState.UNBUDGETED, state(9_999, 0))
    }

    // --- the figures the row prints -----------------------------------------

    @Test
    fun `remaining is what is left and never negative`() {
        assertEquals(
            Money.ofTaka(1_400),
            BudgetStatus.of(Money.ofTaka(5_600), Money.ofTaka(7_000)).remaining,
        )
        // Past the limit, "remaining" is zero rather than a negative number —
        // the overspend is reported separately so the copy can say "৳280 over"
        // instead of "−৳280 left".
        assertEquals(
            Money.ZERO,
            BudgetStatus.of(Money.ofTaka(7_280), Money.ofTaka(7_000)).remaining,
        )
    }

    @Test
    fun `overspend is the excess and is zero while under`() {
        assertEquals(
            Money.ofTaka(280),
            BudgetStatus.of(Money.ofTaka(7_280), Money.ofTaka(7_000)).overspend,
        )
        assertEquals(
            Money.ZERO,
            BudgetStatus.of(Money.ofTaka(100), Money.ofTaka(7_000)).overspend,
        )
    }

    @Test
    fun `fraction is clamped so an over-budget bar never runs off the end`() {
        assertEquals(0.8f, BudgetStatus.of(Money.ofTaka(80), Money.ofTaka(100)).fraction, 0.001f)
        // Overspend is shown by colour, the rule above and the label — not by a
        // bar wider than its track.
        assertEquals(1f, BudgetStatus.of(Money.ofTaka(300), Money.ofTaka(100)).fraction, 0.001f)
        assertEquals(0f, BudgetStatus.of(Money.ofTaka(50), Money.ZERO).fraction, 0.001f)
    }

    @Test
    fun `a refund can pull a category back under its limit`() {
        // FR-EXP-06 refunds are negative expenses, so a rollup total can fall.
        // The state must follow it back down rather than latching.
        assertEquals(BudgetState.OVER, state(7_280, 7_000))
        assertEquals(BudgetState.NEAR, state(6_000, 7_000))
    }

    @Test
    fun `a net negative spend is under, not an error`() {
        // A month whose only entries are refunds. Nothing has been spent, so
        // nothing is at risk.
        val status = BudgetStatus.of(Money.ofTaka(-500), Money.ofTaka(7_000))
        assertEquals(BudgetState.UNDER, status.state)
        assertEquals(0f, status.fraction, 0.001f)
    }

    @Test
    fun a_month_whose_refunds_outweigh_its_spending_does_not_gain_budget() {
        // FR-EXP-06 makes a negative expense a refund, so a category's net for
        // a period can be below zero. `limit - spent` is then *larger* than the
        // limit, and the row read "৳1,200 left" against a ৳1,000 budget — money
        // the user was never granted, printed beside a bar drawn at 0%.
        // `fraction` and `percentConsumed` both clamped; this did not.
        val status = BudgetStatus.of(spent = Money.ofTaka(-200), limit = Money.ofTaka(1_000))

        assertEquals(Money.ofTaka(1_000), status.remaining)
        assertEquals(0f, status.fraction, 0f)
        assertEquals(0, status.percentConsumed)
        assertEquals(Money.ZERO, status.overspend)
    }

    @Test
    fun remaining_and_overspend_still_partition_the_ordinary_cases() {
        // The clamp must not have moved anything that was already right.
        val under = BudgetStatus.of(spent = Money.ofTaka(300), limit = Money.ofTaka(1_000))
        assertEquals(Money.ofTaka(700), under.remaining)
        assertEquals(Money.ZERO, under.overspend)

        val over = BudgetStatus.of(spent = Money.ofTaka(1_280), limit = Money.ofTaka(1_000))
        assertEquals(Money.ZERO, over.remaining)
        assertEquals(Money.ofTaka(280), over.overspend)
    }
}
