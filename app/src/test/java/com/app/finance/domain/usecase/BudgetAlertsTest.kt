package com.app.finance.domain.usecase

import com.app.finance.core.money.Money
import com.app.finance.domain.model.BudgetState
import com.app.finance.domain.model.BudgetStatus
import com.app.finance.domain.model.Nature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetAlertsTest {

    private var nextId = 1L

    private fun leaf(name: String, spent: Long, limit: Long, nature: Nature = Nature.VARIABLE) =
        BudgetLeaf(
            id = nextId++,
            name = name,
            nature = nature,
            status = BudgetStatus.of(Money.ofTaka(spent), Money.ofTaka(limit)),
        )

    private fun group(nature: Nature, vararg leaves: BudgetLeaf) =
        BudgetGroup(id = nextId++, name = nature.name, nature = nature, leaves = leaves.toList())

    @Test
    fun `only leaves at or past eighty percent appear`() {
        val alerts = BudgetAlerts.from(
            listOf(
                group(
                    Nature.VARIABLE,
                    leaf("Grocery", spent = 7_280, limit = 7_000),   // over
                    leaf("Transport", spent = 2_400, limit = 3_000), // 80%
                    leaf("Dining Out", spent = 100, limit = 3_000),  // well under
                ),
            ),
            daysRemaining = 6,
        )

        assertEquals(listOf("Grocery", "Transport"), alerts.map { it.name })
    }

    @Test
    fun `over budget is listed before approaching`() {
        val alerts = BudgetAlerts.from(
            listOf(
                group(
                    Nature.VARIABLE,
                    leaf("Transport", spent = 2_400, limit = 3_000),
                    leaf("Grocery", spent = 7_280, limit = 7_000),
                ),
            ),
            daysRemaining = 6,
        )

        assertTrue(alerts.first().isOver)
        assertEquals("Grocery", alerts.first().name)
    }

    @Test
    fun `the worst overspend leads`() {
        val alerts = BudgetAlerts.from(
            listOf(
                group(
                    Nature.VARIABLE,
                    leaf("Grocery", spent = 7_280, limit = 7_000),   // ৳280 over
                    leaf("Household", spent = 4_000, limit = 3_000), // ৳1,000 over
                ),
            ),
            daysRemaining = 6,
        )

        assertEquals(listOf("Household", "Grocery"), alerts.map { it.name })
        assertEquals(Money.ofTaka(1_000), alerts.first().overspend)
    }

    @Test
    fun `among approaching leaves the tightest margin leads`() {
        // ৳100 left is more urgent than ৳600 left, whatever the limits are.
        val alerts = BudgetAlerts.from(
            listOf(
                group(
                    Nature.VARIABLE,
                    leaf("Transport", spent = 2_400, limit = 3_000),  // ৳600 left
                    leaf("Recharge", spent = 900, limit = 1_000),     // ৳100 left
                ),
            ),
            daysRemaining = 6,
        )

        assertEquals(listOf("Recharge", "Transport"), alerts.map { it.name })
    }

    // --- FR-BUD-07 ----------------------------------------------------------

    @Test
    fun `an unpredictable leaf never appears merely for approaching`() {
        // "Unpredictable Expenses is a buffer, not a plan. Under-spending it is
        // a win, not an unused allocation." Being 80% through a buffer is not
        // news.
        val alerts = BudgetAlerts.from(
            listOf(
                group(
                    Nature.UNPREDICTABLE,
                    leaf("Medical", spent = 4_000, limit = 5_000, nature = Nature.UNPREDICTABLE),
                ),
            ),
            daysRemaining = 6,
        )

        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `an unpredictable leaf does appear once it is over`() {
        // Suppressing the warning is not the same as hiding an overrun — the
        // user set that limit and has passed it.
        val alerts = BudgetAlerts.from(
            listOf(
                group(
                    Nature.UNPREDICTABLE,
                    leaf("Medical", spent = 6_000, limit = 5_000, nature = Nature.UNPREDICTABLE),
                ),
            ),
            daysRemaining = 6,
        )

        assertEquals(1, alerts.size)
        assertEquals(BudgetState.OVER, alerts.single().state)
    }

    // --- exclusions ---------------------------------------------------------

    @Test
    fun `an unbudgeted leaf is never an alert however much it spends`() {
        // With no limit there is no threshold to cross. "No limit set" belongs
        // in the list below, not in a block that means something is wrong.
        val alerts = BudgetAlerts.from(
            listOf(group(Nature.VARIABLE, leaf("Dining Out", spent = 99_000, limit = 0))),
            daysRemaining = 6,
        )
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `nothing wrong produces an empty list, which the screen renders as absence`() {
        // 05 §5.4: "An empty state here would train the user to ignore the
        // region. Sections that have nothing to say are absent, not empty."
        val alerts = BudgetAlerts.from(
            listOf(group(Nature.VARIABLE, leaf("Grocery", spent = 100, limit = 7_000))),
            daysRemaining = 6,
        )
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `days remaining is carried through for the approaching copy`() {
        // "৳900 left in Grocery — 6 days to go"
        val alerts = BudgetAlerts.from(
            listOf(group(Nature.VARIABLE, leaf("Grocery", spent = 6_100, limit = 7_000))),
            daysRemaining = 6,
        )
        assertEquals(6, alerts.single().daysRemaining)
        assertEquals(Money.ofTaka(900), alerts.single().remaining)
    }
}
