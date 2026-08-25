package com.app.finance.domain.usecase

import com.app.finance.core.money.Money
import com.app.finance.domain.model.BudgetStatus
import com.app.finance.domain.model.Nature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fixed / variable / unpredictable split — FR-AN-07. */
class SpendMixTest {

    private var nextId = 1L

    private fun group(nature: Nature, vararg spends: Long) = BudgetGroup(
        id = nextId++,
        name = nature.name,
        nature = nature,
        leaves = spends.map { spent ->
            BudgetLeaf(
                id = nextId++,
                name = "leaf",
                nature = nature,
                status = BudgetStatus.of(Money.ofTaka(spent), Money.ZERO),
            )
        },
    )

    @Test
    fun `the shares sum to exactly a hundred`() {
        val mix = SpendMix.of(
            listOf(
                group(Nature.VARIABLE, 10_000),
                group(Nature.UNPREDICTABLE, 10_000),
                group(Nature.FIXED, 10_000),
            ),
        )
        assertEquals(100, mix.sumOf { it.share })
        // 33 + 33 + 33 would be 99. Variable takes the leftover because it is
        // first, which is also the order 05 §5.4 argues for.
        assertEquals(listOf(34, 33, 33), mix.map { it.share })
    }

    @Test
    fun `ordered by actionability, not by amount`() {
        // 05 §5.4: "Fixed expenses sit below variable ones, despite being
        // larger, because rent is not a decision."
        val mix = SpendMix.of(
            listOf(
                group(Nature.FIXED, 30_000),
                group(Nature.UNPREDICTABLE, 5_000),
                group(Nature.VARIABLE, 12_000),
            ),
        )
        assertEquals(
            listOf(Nature.VARIABLE, Nature.UNPREDICTABLE, Nature.FIXED),
            mix.map { it.nature },
        )
    }

    @Test
    fun `several groups of one nature are summed together`() {
        val mix = SpendMix.of(
            listOf(group(Nature.VARIABLE, 6_000), group(Nature.VARIABLE, 4_000)),
        )
        assertEquals(Money.ofTaka(10_000), mix.single().total)
        assertEquals(100, mix.single().share)
    }

    @Test
    fun `leaves inside a group are summed`() {
        val mix = SpendMix.of(listOf(group(Nature.VARIABLE, 3_000, 4_000, 5_000)))
        assertEquals(Money.ofTaka(12_000), mix.single().total)
    }

    @Test
    fun `a nature with no spending is absent, not zero`() {
        // 05 §5.4 — "sections that have nothing to say are absent, not empty",
        // and a row reading "Unpredictable ৳0 0%" is a row that says nothing.
        val mix = SpendMix.of(
            listOf(group(Nature.VARIABLE, 8_000), group(Nature.FIXED, 2_000)),
        )
        assertEquals(listOf(Nature.VARIABLE, Nature.FIXED), mix.map { it.nature })
        assertEquals(listOf(80, 20), mix.map { it.share })
    }

    @Test
    fun `a group that exists but spent nothing does not appear`() {
        val mix = SpendMix.of(
            listOf(group(Nature.VARIABLE, 8_000), group(Nature.UNPREDICTABLE, 0)),
        )
        assertEquals(1, mix.size)
        assertEquals(100, mix.single().share)
    }

    @Test
    fun `nothing spent anywhere is an empty mix`() {
        assertTrue(SpendMix.of(listOf(group(Nature.VARIABLE, 0))).isEmpty())
        assertTrue(SpendMix.of(emptyList()).isEmpty())
    }

    @Test
    fun `05's own example`() {
        // Variable ৳12,400, fixed ৳18,000 — the two group totals in §5.4's mock.
        val mix = SpendMix.of(
            listOf(group(Nature.VARIABLE, 12_400), group(Nature.FIXED, 18_000)),
        )
        assertEquals(100, mix.sumOf { it.share })
        assertEquals(41, mix.first().share)
        assertEquals(59, mix.last().share)
    }

    @Test
    fun `an archived leaf's spending still counts, because it really happened`() {
        // The other half of A2. Safe-to-spend and the burn projection drop an
        // archived leaf because they are about what happens next; the mix and
        // the group total keep it because they are about what happened.
        val archived = BudgetGroup(
            id = 99,
            name = "Variable",
            nature = Nature.VARIABLE,
            leaves = listOf(
                BudgetLeaf(
                    id = 98,
                    name = "Grocery",
                    nature = Nature.VARIABLE,
                    status = BudgetStatus.of(Money.ofTaka(6_000), Money.ofTaka(18_000)),
                    isArchived = true,
                ),
            ),
        )
        val mix = SpendMix.of(listOf(archived, group(Nature.FIXED, 4_000)))

        assertEquals(Money.ofTaka(6_000), mix.first().total)
        assertEquals(100, mix.sumOf { it.share })
    }

    // --- what the slices leave out (05 §5.3) ---------------------------------

    @Test
    fun `nothing is excluded when every nature has spending`() {
        // The ordinary case, and the one that decides whether the caption is
        // noise: it must be silent here, on every screen, always.
        val groups = listOf(
            group(Nature.VARIABLE, 10_000),
            group(Nature.UNPREDICTABLE, 2_000),
            group(Nature.FIXED, 15_000),
        )

        assertEquals(Money.ZERO, SpendMix.excludedFrom(groups))
    }

    @Test
    fun `nothing is excluded when a nature simply has no spending`() {
        // Absent is not the same as negative. A nature with no rows at all is
        // dropped by 05 §5.4's rule and leaves nothing outside the total.
        val groups = listOf(group(Nature.VARIABLE, 10_000))

        assertTrue(SpendMix.of(groups).size == 1)
        assertEquals(Money.ZERO, SpendMix.excludedFrom(groups))
    }

    @Test
    fun `a nature whose refunds outweigh its spending is what the caption is for`() {
        // FR-EXP-06 makes a negative expense a refund, so a nature's net for a
        // period can be below zero. It cannot be drawn as a slice — a pie has
        // no negative width — so it is dropped, and the percentages are then of
        // a smaller number than the hero figure beside them.
        val groups = listOf(
            group(Nature.VARIABLE, 5_000),
            group(Nature.UNPREDICTABLE, -1_000),
        )

        val mix = SpendMix.of(groups)
        assertEquals("the refunded nature must not be drawn", 1, mix.size)
        assertEquals(Nature.VARIABLE, mix.single().nature)
        assertEquals(100, mix.single().share)

        // And this is the gap the caption names: ৳5,000 of slices above a
        // ৳4,000 total.
        assertEquals(Money.ofTaka(1_000), SpendMix.excludedFrom(groups))
    }

    @Test
    fun `a nature at exactly zero is excluded but contributes nothing to say`() {
        // The boundary. `total.paisa > 0` drops it, so it is not a slice — and
        // its magnitude is zero, so the caption stays silent. A caption reading
        // "৳0 of refunds sits outside them" would be worse than none.
        val groups = listOf(
            group(Nature.VARIABLE, 5_000),
            group(Nature.UNPREDICTABLE, 1_000, -1_000),
        )

        assertEquals(1, SpendMix.of(groups).size)
        assertEquals(Money.ZERO, SpendMix.excludedFrom(groups))
    }

    @Test
    fun `every excluded nature is counted, not just the first`() {
        val groups = listOf(
            group(Nature.VARIABLE, 5_000),
            group(Nature.UNPREDICTABLE, -1_000),
            group(Nature.FIXED, -250),
        )

        assertEquals(Money.ofTaka(1_250), SpendMix.excludedFrom(groups))
    }

    @Test
    fun `both entry points agree about what was left out`() {
        // `of` folds groups into per-nature totals and `ofTotals` is handed
        // them directly; the two must not be able to disagree, which is why
        // they share one fold.
        val groups = listOf(
            group(Nature.VARIABLE, 5_000),
            group(Nature.UNPREDICTABLE, -1_000),
        )
        val totals = mapOf(
            Nature.VARIABLE to Money.ofTaka(5_000),
            Nature.UNPREDICTABLE to Money.ofTaka(-1_000),
        )

        assertEquals(SpendMix.excludedFrom(groups), SpendMix.excludedFrom(totals))
        assertEquals(SpendMix.of(groups), SpendMix.ofTotals(totals))
    }

    @Test
    fun `the slices plus what was excluded account for the whole period`() {
        // The reconciliation the caption is promising. Displayed spend plus the
        // refunds held outside it equals the net total the screen prints above.
        val groups = listOf(
            group(Nature.VARIABLE, 5_000),
            group(Nature.UNPREDICTABLE, -1_000),
        )

        val displayed = SpendMix.of(groups).fold(Money.ZERO) { acc, s -> acc + s.total }
        val net = displayed - SpendMix.excludedFrom(groups)

        assertEquals(Money.ofTaka(4_000), net)
    }
}
