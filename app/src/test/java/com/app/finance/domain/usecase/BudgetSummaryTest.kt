package com.app.finance.domain.usecase

import com.app.finance.core.money.Money
import com.app.finance.domain.model.BudgetState
import com.app.finance.domain.model.CategoryNode
import com.app.finance.domain.model.Nature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetSummaryTest {

    private var nextId = 1L

    private fun root(name: String, nature: Nature, children: List<CategoryNode>) = CategoryNode(
        id = nextId++,
        name = name,
        nature = nature,
        isSystem = true,
        isArchived = false,
        children = children,
    )

    private fun leaf(name: String, nature: Nature) = CategoryNode(
        id = nextId++,
        name = name,
        nature = nature,
        isSystem = false,
        isArchived = false,
        children = emptyList(),
    )

    private fun spend(leaf: CategoryNode, parent: CategoryNode, spent: Long, limit: Long) =
        BudgetSummary.LeafSpend(
            id = leaf.id,
            parentId = parent.id,
            name = leaf.name,
            nature = leaf.nature.code,
            limitMinor = Money.ofTaka(limit).paisa,
            spentMinor = Money.ofTaka(spent).paisa,
        )

    // --- FR-BUD-03 ----------------------------------------------------------

    @Test
    fun `a root's limit is the sum of its children, never a stored figure`() {
        // The acceptance criterion: "With children at ৳7,000 and ৳3,000, the
        // root displays ৳10,000 and offers no editable limit field."
        val grocery = leaf("Grocery", Nature.VARIABLE)
        val transport = leaf("Transport", Nature.VARIABLE)
        val variable = root("Variable Expenses", Nature.VARIABLE, listOf(grocery, transport))

        val groups = BudgetSummary.build(
            bars = listOf(
                spend(grocery, variable, spent = 5_600, limit = 7_000),
                spend(transport, variable, spent = 1_000, limit = 3_000),
            ),
            tree = listOf(variable),
        )

        assertEquals(Money.ofTaka(10_000), groups.single().limit)
        assertEquals(Money.ofTaka(6_600), groups.single().spent)
    }

    @Test
    fun `a root with no budgeted children reports no limit`() {
        val grocery = leaf("Grocery", Nature.VARIABLE)
        val variable = root("Variable Expenses", Nature.VARIABLE, listOf(grocery))

        val group = BudgetSummary
            .build(listOf(spend(grocery, variable, spent = 400, limit = 0)), listOf(variable))
            .single()

        assertTrue(group.isUnbudgeted)
        assertEquals(Money.ofTaka(400), group.spent)
    }

    // --- ordering by actionability (05 §5.4) --------------------------------

    @Test
    fun `variable comes first and fixed last, regardless of size`() {
        // "Fixed expenses sit below variable ones, despite being larger,
        // because rent is not a decision."
        val rent = leaf("House Rent", Nature.FIXED)
        val fixed = root("Fixed Expenses", Nature.FIXED, listOf(rent))
        val medical = leaf("Medical", Nature.UNPREDICTABLE)
        val unpredictable = root("Unpredictable", Nature.UNPREDICTABLE, listOf(medical))
        val grocery = leaf("Grocery", Nature.VARIABLE)
        val variable = root("Variable Expenses", Nature.VARIABLE, listOf(grocery))

        val groups = BudgetSummary.build(
            bars = listOf(
                spend(rent, fixed, spent = 18_000, limit = 18_000),
                spend(medical, unpredictable, spent = 2_400, limit = 5_000),
                spend(grocery, variable, spent = 500, limit = 7_000),
            ),
            // Deliberately supplied in the wrong order.
            tree = listOf(fixed, unpredictable, variable),
        )

        assertEquals(
            listOf("Variable Expenses", "Unpredictable", "Fixed Expenses"),
            groups.map { it.name },
        )
    }

    // --- FR-BUD-07 ----------------------------------------------------------

    @Test
    fun `unpredictable leaves are marked unplanned`() {
        val medical = leaf("Medical", Nature.UNPREDICTABLE)
        val unpredictable = root("Unpredictable", Nature.UNPREDICTABLE, listOf(medical))
        val grocery = leaf("Grocery", Nature.VARIABLE)
        val variable = root("Variable Expenses", Nature.VARIABLE, listOf(grocery))

        val groups = BudgetSummary.build(
            bars = listOf(
                spend(medical, unpredictable, spent = 2_400, limit = 5_000),
                spend(grocery, variable, spent = 500, limit = 7_000),
            ),
            tree = listOf(variable, unpredictable),
        )

        assertTrue(groups.first { it.name == "Unpredictable" }.leaves.single().isUnplanned)
        assertFalse(groups.first { it.name == "Variable Expenses" }.leaves.single().isUnplanned)
    }

    // --- shape --------------------------------------------------------------

    @Test
    fun `a root with no leaves is dropped rather than rendered empty`() {
        // 05 §5.4: "Sections that have nothing to say are absent, not empty."
        val grocery = leaf("Grocery", Nature.VARIABLE)
        val variable = root("Variable Expenses", Nature.VARIABLE, listOf(grocery))
        val empty = root("Fixed Expenses", Nature.FIXED, emptyList())

        val groups = BudgetSummary.build(
            bars = listOf(spend(grocery, variable, spent = 100, limit = 1_000)),
            tree = listOf(variable, empty),
        )

        assertEquals(1, groups.size)
        assertEquals("Variable Expenses", groups.single().name)
    }

    @Test
    fun `leaves carry the state their spend implies`() {
        val grocery = leaf("Grocery", Nature.VARIABLE)
        val transport = leaf("Transport", Nature.VARIABLE)
        val dining = leaf("Dining Out", Nature.VARIABLE)
        val variable = root("Variable Expenses", Nature.VARIABLE, listOf(grocery, transport, dining))

        val leaves = BudgetSummary.build(
            bars = listOf(
                spend(grocery, variable, spent = 7_280, limit = 7_000), // 104%
                spend(transport, variable, spent = 2_400, limit = 3_000), // exactly 80%
                spend(dining, variable, spent = 880, limit = 0), // no limit
            ),
            tree = listOf(variable),
        ).single().leaves.associateBy { it.name }

        assertEquals(BudgetState.OVER, leaves.getValue("Grocery").status.state)
        assertEquals(BudgetState.NEAR, leaves.getValue("Transport").status.state)
        assertEquals(BudgetState.UNBUDGETED, leaves.getValue("Dining Out").status.state)
        assertFalse(leaves.getValue("Dining Out").hasLimit)
    }
}
