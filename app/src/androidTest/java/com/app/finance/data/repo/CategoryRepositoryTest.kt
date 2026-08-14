package com.app.finance.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.core.money.Money
import com.app.finance.domain.model.EntryError
import com.app.finance.domain.model.Nature
import com.app.finance.domain.model.SaveOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The category rules of FR-CAT-*, exercised through the repository rather than
 * through raw SQL — the triggers are already covered by `SchemaAssertionsTest`;
 * what is untested is whether the repository maps their failures to the typed
 * errors the UI relies on (04 §8).
 */
@RunWith(AndroidJUnit4::class)
class CategoryRepositoryTest {

    private lateinit var fx: TestFixture

    @Before fun setUp() { fx = TestFixture() }
    @After fun tearDown() = fx.close()

    @Test
    fun the_tree_is_two_levels_with_three_seeded_roots() = runBlocking {
        val tree = fx.categories.observeTree().first()
        assertEquals(3, tree.size)
        assertEquals(
            listOf("Fixed Expenses", "Variable Expenses", "Unpredictable Expenses"),
            tree.map { it.name },
        )
        assertTrue("roots must be system", tree.all { it.isSystem })
        assertTrue("depth is capped at two", tree.all { r -> r.children.all { it.children.isEmpty() } })
    }

    @Test
    fun a_new_subcategory_inherits_its_roots_nature() = runBlocking {
        // FR-CAT-06 — inherited, never overridden. The repository passes the
        // parent's nature and the trigger enforces it regardless.
        val unpredictable = fx.rootId("Unpredictable Expenses")
        val outcome = fx.categories.createSubcategory(unpredictable, "Vet")
        assertTrue(outcome is SaveOutcome.Saved)

        val created = fx.categories.byId((outcome as SaveOutcome.Saved).id)!!
        assertEquals(Nature.UNPREDICTABLE.code, created.nature)
    }

    @Test
    fun a_duplicate_name_under_the_same_parent_is_a_typed_error() = runBlocking {
        val variable = fx.rootId("Variable Expenses")
        // FR-CAT-07 is on the normalised key, so case and spacing must collide.
        val outcome = fx.categories.createSubcategory(variable, "  grocery ")
        assertEquals(SaveOutcome.Rejected(EntryError.DUPLICATE_NAME), outcome)
    }

    @Test
    fun the_same_leaf_name_under_two_roots_is_allowed() = runBlocking {
        // "Two roots MAY each have a child named Misc."
        assertTrue(
            fx.categories.createSubcategory(fx.rootId("Fixed Expenses"), "Misc")
                is SaveOutcome.Saved,
        )
        assertTrue(
            fx.categories.createSubcategory(fx.rootId("Variable Expenses"), "Misc")
                is SaveOutcome.Saved,
        )
    }

    @Test
    fun a_third_level_is_a_typed_error_not_a_crash() = runBlocking {
        val grocery = fx.leafId("Grocery")
        assertEquals(
            SaveOutcome.Rejected(EntryError.CATEGORY_TOO_DEEP),
            fx.categories.createSubcategory(grocery, "Rice"),
        )
    }

    @Test
    fun a_blank_name_is_rejected_before_it_reaches_the_database() = runBlocking {
        assertEquals(
            SaveOutcome.Rejected(EntryError.BLANK_NAME),
            fx.categories.createSubcategory(fx.rootId("Fixed Expenses"), "   "),
        )
    }

    @Test
    fun archiving_a_root_archives_its_descendants() = runBlocking {
        // FR-CAT-09. Enforced in the repository inside one transaction, because
        // no trigger in the schema does it.
        val outcome = fx.categories.createRoot("Travel", Nature.VARIABLE)
        val travel = (outcome as SaveOutcome.Saved).id
        fx.categories.createSubcategory(travel, "Bus")
        fx.categories.createSubcategory(travel, "Train")

        fx.categories.setArchived(travel, archived = true)

        val tree = fx.categories.observeTree().first()
        val node = tree.first { it.id == travel }
        assertTrue(node.isArchived)
        assertTrue("children follow the root", node.children.all { it.isArchived })
        assertTrue("and none remain selectable", node.activeChildren.isEmpty())
    }

    @Test
    fun a_system_root_cannot_be_archived() = runBlocking {
        // FR-CAT-03 — renameable, never deletable or archivable.
        val fixed = fx.rootId("Fixed Expenses")
        assertEquals(
            SaveOutcome.Rejected(EntryError.CONSTRAINT_VIOLATION),
            fx.categories.setArchived(fixed, archived = true),
        )
        assertFalse(fx.categories.byId(fixed)!!.isArchived)
    }

    @Test
    fun a_system_root_can_be_renamed() = runBlocking {
        val fixed = fx.rootId("Fixed Expenses")
        assertTrue(fx.categories.rename(fixed, "Committed") is SaveOutcome.Saved)
        assertEquals("Committed", fx.categories.byId(fixed)!!.name)
    }

    @Test
    fun an_archived_leaf_leaves_the_picker_but_stays_in_the_ledger() = runBlocking {
        // FR-CAT-08 — the exact wording is "hidden from entry pickers and MUST
        // remain present ... in the ledger rows that reference them".
        val grocery = fx.leafId("Grocery")
        fx.expenses.insert(Money.ofTaka(120), grocery)
        fx.categories.setArchived(grocery, archived = true)

        val selectable = fx.categories.observeSelectableLeaves().first()
        assertFalse(selectable.any { it.id == grocery })

        val ledger = fx.expenses.firstPage()
        assertTrue(ledger.any { it.categoryName == "Grocery" })
    }

    @Test
    fun a_category_referenced_by_an_expense_cannot_be_deleted() = runBlocking {
        // FR-CAT-10, enforced by ON DELETE RESTRICT — the reason there is no
        // delete method on this repository at all.
        val grocery = fx.leafId("Grocery")
        fx.expenses.insert(Money.ofTaka(50), grocery)

        val threw = runCatching {
            fx.db.openHelper.writableDatabase.execSQL("DELETE FROM category WHERE id = $grocery")
        }.isFailure
        assertTrue("deleting a referenced category must fail", threw)
    }
}
