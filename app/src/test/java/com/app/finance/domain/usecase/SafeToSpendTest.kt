package com.app.finance.domain.usecase

import com.app.finance.core.money.Money
import com.app.finance.domain.model.BudgetStatus
import com.app.finance.domain.model.Nature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dashboard's hero figure — FR-AN-01.
 *
 * This is the number the user acts on at a shop counter, so it is the one
 * calculation in the app most worth pinning from every direction.
 */
class SafeToSpendTest {

    private var nextId = 1L

    private fun leaf(nature: Nature, limit: Long, spent: Long) = BudgetLeaf(
        id = nextId++,
        name = "leaf${nextId}",
        nature = nature,
        status = BudgetStatus.of(
            spent = Money.ofTaka(spent),
            limit = Money.ofTaka(limit),
        ),
    )

    private fun group(nature: Nature, vararg leaves: BudgetLeaf) =
        BudgetGroup(id = 100L + nextId++, name = "group", nature = nature, leaves = leaves.toList())

    // --- the ordinary case ----------------------------------------------------

    @Test
    fun `what is left, divided by the days left`() {
        val groups = listOf(
            group(Nature.VARIABLE, leaf(Nature.VARIABLE, limit = 18_000, spent = 12_000)),
        )
        val safe = SafeToSpend.of(groups, daysRemaining = 6)

        assertEquals(Money.ofTaka(6_000), safe.remaining)
        assertEquals(Money.ofTaka(1_000), safe.perDay)
        assertFalse(safe.isOver)
    }

    @Test
    fun `unpredictable leaves count toward it and fixed ones do not`() {
        // FR-AN-01 names "variable + unpredictable" exactly, and `Nature
        // .isDiscretionary` is that predicate. Rent's unspent limit is not
        // money available at a shop counter.
        val groups = listOf(
            group(Nature.VARIABLE, leaf(Nature.VARIABLE, limit = 10_000, spent = 4_000)),
            group(Nature.UNPREDICTABLE, leaf(Nature.UNPREDICTABLE, limit = 5_000, spent = 1_000)),
            group(Nature.FIXED, leaf(Nature.FIXED, limit = 20_000, spent = 0)),
        )
        val safe = SafeToSpend.of(groups, daysRemaining = 10)

        assertEquals("6,000 + 4,000, and not a paisa of the rent", Money.ofTaka(10_000), safe.remaining)
        assertEquals(Money.ofTaka(1_000), safe.perDay)
    }

    @Test
    fun `a leaf with no limit contributes nothing`() {
        val groups = listOf(
            group(
                Nature.VARIABLE,
                leaf(Nature.VARIABLE, limit = 6_000, spent = 1_000),
                leaf(Nature.VARIABLE, limit = 0, spent = 5_000),
            ),
        )
        // Only the budgeted leaf has a remainder; the unbudgeted one's spending
        // is real but there is no plan for it to eat into.
        assertEquals(Money.ofTaka(5_000), SafeToSpend.of(groups, 5).remaining)
    }

    // --- the signed numerator, which is the whole point -----------------------

    @Test
    fun `an overspent leaf eats into what is left elsewhere`() {
        // The sum is signed rather than clamped per leaf. Money spent over
        // Grocery is money not available for Transport, and a figure that
        // pretended otherwise would send the user out to spend it twice.
        val groups = listOf(
            group(
                Nature.VARIABLE,
                leaf(Nature.VARIABLE, limit = 5_000, spent = 8_000),
                leaf(Nature.VARIABLE, limit = 10_000, spent = 3_000),
            ),
        )
        assertEquals(Money.ofTaka(4_000), SafeToSpend.of(groups, 4).remaining)
        assertEquals(Money.ofTaka(1_000), SafeToSpend.of(groups, 4).perDay)
    }

    @Test
    fun `a negative numerator renders as zero with the over flag set`() {
        // FR-AN-01, in as many words: "when the numerator is negative the value
        // MUST render as zero with an over-budget indicator". Both halves.
        val groups = listOf(
            group(Nature.VARIABLE, leaf(Nature.VARIABLE, limit = 5_000, spent = 9_000)),
        )
        val safe = SafeToSpend.of(groups, daysRemaining = 8)

        assertTrue(safe.isOver)
        assertEquals(Money.ZERO, safe.perDay)
        assertEquals("and the size of the overshoot survives", Money.ofTaka(-4_000), safe.remaining)
    }

    // --- the divisor ----------------------------------------------------------

    @Test
    fun `the last day of the month divides by one, not by zero`() {
        val groups = listOf(
            group(Nature.VARIABLE, leaf(Nature.VARIABLE, limit = 5_000, spent = 3_800)),
        )
        assertEquals(Money.ofTaka(1_200), SafeToSpend.of(groups, daysRemaining = 1).perDay)
    }

    @Test
    fun `a finished period has no per-day figure at all`() {
        // Not zero — zero would read as "you may spend nothing today", which is
        // a different and wrong statement. A past month has a balance, not a
        // daily allowance, and the screen switches its caption accordingly.
        val groups = listOf(
            group(Nature.VARIABLE, leaf(Nature.VARIABLE, limit = 5_000, spent = 1_000)),
        )
        val safe = SafeToSpend.of(groups, daysRemaining = 0)

        assertNull(safe.perDay)
        assertEquals(Money.ofTaka(4_000), safe.remaining)
    }

    @Test
    fun `rounding is downward, never up`() {
        // `Money.divideBy` rounds toward zero on purpose: telling the user they
        // may spend one paisa more than they may is the wrong direction to err.
        val groups = listOf(
            group(Nature.VARIABLE, leaf(Nature.VARIABLE, limit = 1_000, spent = 0)),
        )
        assertEquals(Money(33_333L), SafeToSpend.of(groups, daysRemaining = 3).perDay)
    }

    @Test
    fun `nothing budgeted is zero rather than an error`() {
        val safe = SafeToSpend.of(emptyList(), daysRemaining = 10)
        assertEquals(Money.ZERO, safe.remaining)
        assertEquals(Money.ZERO, safe.perDay)
        assertFalse(safe.isOver)
    }

    @Test
    fun `spending exactly the limit is not over`() {
        // `BudgetState.OVER` fires at the limit (FR-BUD-06), but a numerator of
        // exactly zero is not negative, and FR-AN-01's indicator is about the
        // numerator. "৳0 over" is not a sentence anyone acts on.
        val groups = listOf(
            group(Nature.VARIABLE, leaf(Nature.VARIABLE, limit = 5_000, spent = 5_000)),
        )
        val safe = SafeToSpend.of(groups, daysRemaining = 3)

        assertFalse(safe.isOver)
        assertEquals(Money.ZERO, safe.perDay)
    }

    // --- archived leaves (A2) -------------------------------------------------

    @Test
    fun `an archived leaf's remaining limit is not safe to spend`() {
        // FR-CAT-08 keeps it on the screen while it carries spend, and that same
        // requirement is why it cannot be counted here: the entry picker no
        // longer offers it, so what is left of its limit is money that cannot
        // be spent. Archive Grocery on the 14th and this figure was offering
        // ৳16,000 that no longer existed.
        val groups = listOf(
            group(
                Nature.VARIABLE,
                leaf(Nature.VARIABLE, limit = 18_000, spent = 2_000).copy(isArchived = true),
                leaf(Nature.VARIABLE, limit = 5_000, spent = 1_000),
            ),
        )
        assertEquals(Money.ofTaka(4_000), SafeToSpend.of(groups, daysRemaining = 4).remaining)
        assertEquals(Money.ofTaka(1_000), SafeToSpend.of(groups, 4).perDay)
    }

    @Test
    fun `an archived leaf that overspent does not drag the figure down either`() {
        // Symmetry matters: if its unspent limit is not available, its overspend
        // is not a live claim on what is left elsewhere. Both belong to a
        // category that is closed.
        val groups = listOf(
            group(
                Nature.VARIABLE,
                leaf(Nature.VARIABLE, limit = 5_000, spent = 9_000).copy(isArchived = true),
                leaf(Nature.VARIABLE, limit = 6_000, spent = 0),
            ),
        )
        val safe = SafeToSpend.of(groups, daysRemaining = 6)
        assertEquals(Money.ofTaka(6_000), safe.remaining)
        assertFalse(safe.isOver)
    }
}
