package com.app.finance.domain.usecase

import com.app.finance.core.money.Money
import com.app.finance.domain.model.BudgetStatus
import com.app.finance.domain.model.Nature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The burn-rate projection — FR-AN-04.
 *
 * PRD §6.4's whole justification is one clause: it "warns on day 12, not day
 * 30". Every case below is really about whether the warning arrives while it
 * can still change the outcome.
 */
class BurnRateTest {

    private var nextId = 1L

    private fun leaf(
        name: String,
        limit: Long,
        spent: Long,
        nature: Nature = Nature.VARIABLE,
    ) = BudgetLeaf(
        id = nextId++,
        name = name,
        nature = nature,
        status = BudgetStatus.of(Money.ofTaka(spent), Money.ofTaka(limit)),
    )

    private fun groups(vararg leaves: BudgetLeaf) = listOf(
        BudgetGroup(id = 1, name = "group", nature = Nature.VARIABLE, leaves = leaves.toList()),
    )

    // --- the warning ----------------------------------------------------------

    @Test
    fun `a pace that lands over the limit is reported on day twelve`() {
        // ৳8,000 in twelve days projects to ৳20,000 over thirty — against a
        // limit of ৳18,000. Nothing is over yet; that is the point.
        val over = BurnRate.over(
            groups = groups(leaf("Grocery", limit = 18_000, spent = 8_000)),
            daysElapsed = 12,
            daysInPeriod = 30,
        )
        assertEquals(1, over.size)
        assertEquals(Money.ofTaka(20_000), over.single().projected)
        assertEquals(Money.ofTaka(2_000), over.single().overBy)
        assertFalse("nothing has been overspent yet", over.single().isAlreadyOver)
    }

    @Test
    fun `a pace that lands under the limit is not reported at all`() {
        val over = BurnRate.over(
            groups = groups(leaf("Transport", limit = 18_000, spent = 4_000)),
            daysElapsed = 12,
            daysInPeriod = 30,
        )
        assertTrue(over.isEmpty())
    }

    @Test
    fun `a leaf already past its limit is reported and flagged as such`() {
        // The screen filters these out because they are already in "needs
        // attention"; the calculation keeps them because FR-AN-04 asks for
        // every over-pace leaf and this is trivially one.
        val over = BurnRate.over(
            groups = groups(leaf("Grocery", limit = 5_000, spent = 6_000)),
            daysElapsed = 10,
            daysInPeriod = 30,
        )
        assertEquals(1, over.size)
        assertTrue(over.single().isAlreadyOver)
    }

    @Test
    fun `the worst overshoot is first`() {
        val over = BurnRate.over(
            groups = groups(
                leaf("Small", limit = 10_000, spent = 4_000),
                leaf("Large", limit = 10_000, spent = 9_000),
            ),
            daysElapsed = 10,
            daysInPeriod = 30,
        )
        assertEquals(listOf("Large", "Small"), over.map { it.name })
    }

    // --- what is deliberately not projected -----------------------------------

    @Test
    fun `unpredictable leaves are never projected`() {
        // FR-BUD-07, and PRD §6.2's "Unpredictable Expenses is a buffer, not a
        // plan". A single medical bill on day three projects to ten times
        // itself by month end: alarming, arithmetically correct, and meaningless.
        val over = BurnRate.over(
            groups = groups(
                leaf("Medical", limit = 5_000, spent = 4_000, nature = Nature.UNPREDICTABLE),
            ),
            daysElapsed = 3,
            daysInPeriod = 30,
        )
        assertTrue(over.isEmpty())
    }

    @Test
    fun `an unbudgeted leaf has nothing to be projected against`() {
        val over = BurnRate.over(
            groups = groups(leaf("Household", limit = 0, spent = 9_000)),
            daysElapsed = 10,
            daysInPeriod = 30,
        )
        assertTrue(over.isEmpty())
    }

    @Test
    fun `a period that has not started yet has no pace to extrapolate`() {
        // Looking at next month's budget from this one. Returns empty rather
        // than dividing by zero or inventing a projection from no data.
        val over = BurnRate.over(
            groups = groups(leaf("Grocery", limit = 18_000, spent = 0)),
            daysElapsed = 0,
            daysInPeriod = 30,
        )
        assertTrue(over.isEmpty())
    }

    // --- the arithmetic -------------------------------------------------------

    @Test
    fun `day one already projects`() {
        // ৳900 on day one over thirty days is ৳27,000. Aggressive, and correct
        // — the alternative is a screen that says nothing until it is too late.
        val over = BurnRate.over(
            groups = groups(leaf("Dining Out", limit = 6_000, spent = 900)),
            daysElapsed = 1,
            daysInPeriod = 30,
        )
        assertEquals(Money.ofTaka(27_000), over.single().projected)
    }

    @Test
    fun `it multiplies before dividing, so no fraction is lost`() {
        // ৳1,000 in 7 days over 30. Rounding the daily rate to whole paisa
        // first would lose a month's worth of the fraction.
        val over = BurnRate.over(
            groups = groups(leaf("Grocery", limit = 1_000, spent = 1_000)),
            daysElapsed = 7,
            daysInPeriod = 30,
        )
        assertEquals(Money(100_000L * 30 / 7), over.single().projected)
    }

    @Test
    fun `on the last day the projection is what was actually spent`() {
        val over = BurnRate.over(
            groups = groups(leaf("Grocery", limit = 5_000, spent = 7_000)),
            daysElapsed = 30,
            daysInPeriod = 30,
        )
        assertEquals(Money.ofTaka(7_000), over.single().projected)
    }

    @Test
    fun `an archived leaf is never projected`() {
        // "On pace to overspend" for a category the user has just retired is the
        // same nagging FR-BUD-07 forbids, arriving by another route. Nothing
        // more can be spent there.
        val over = BurnRate.over(
            groups = groups(
                leaf("Grocery", limit = 18_000, spent = 12_000).copy(isArchived = true),
            ),
            daysElapsed = 12,
            daysInPeriod = 30,
        )
        assertTrue(over.isEmpty())
    }

    @Test
    fun `archiving one leaf leaves the others projected`() {
        val over = BurnRate.over(
            groups = groups(
                leaf("Grocery", limit = 18_000, spent = 12_000).copy(isArchived = true),
                leaf("Transport", limit = 6_000, spent = 4_000),
            ),
            daysElapsed = 12,
            daysInPeriod = 30,
        )
        assertEquals(listOf("Transport"), over.map { it.name })
    }
}
