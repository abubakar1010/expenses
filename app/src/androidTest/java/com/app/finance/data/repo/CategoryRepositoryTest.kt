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

        fx.categories.archive(travel)

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
            ArchiveOutcome.Rejected(EntryError.CONSTRAINT_VIOLATION),
            fx.categories.archive(fixed),
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
    fun a_leaf_under_an_archived_root_is_not_selectable_either() = runBlocking {
        // The leaf's own flag is not the whole answer. Archiving a root hides
        // the group; a child that came back on its own would be offered by the
        // entry picker with nothing above it in the manager, which is a state
        // the rest of the app cannot express. FR-CAT-08/09.
        val travel = (fx.categories.createRoot("Travel", Nature.VARIABLE) as SaveOutcome.Saved).id
        val bus = (fx.categories.createSubcategory(travel, "Bus fare") as SaveOutcome.Saved).id
        fx.categories.archive(travel)

        // The guard, first: nothing may restore the child on its own.
        assertEquals(
            SaveOutcome.Rejected(EntryError.CATEGORY_ARCHIVED),
            fx.categories.restore(bus),
        )

        // And the query does not depend on that guard holding — un-archive the
        // child behind the repository's back and it is still not selectable.
        fx.db.categoryDao().setArchived(bus, archived = false, now = 0L)
        assertFalse(fx.categories.observeSelectableLeaves().first().any { it.id == bus })
    }

    @Test
    fun no_subcategory_may_be_added_to_an_archived_root() = runBlocking {
        val travel = (fx.categories.createRoot("Travel", Nature.VARIABLE) as SaveOutcome.Saved).id
        fx.categories.archive(travel)

        assertEquals(
            SaveOutcome.Rejected(EntryError.CATEGORY_ARCHIVED),
            fx.categories.createSubcategory(travel, "Bus fare"),
        )
    }

    @Test
    fun restoring_a_root_leaves_its_children_archived_and_now_restorable() = runBlocking {
        // The Restore action is not the cascade run backwards. After archiving
        // everything, the user may want two of five back — and a root with some
        // children archived is an ordinary state the app already handles.
        val travel = (fx.categories.createRoot("Travel", Nature.VARIABLE) as SaveOutcome.Saved).id
        val bus = (fx.categories.createSubcategory(travel, "Bus fare") as SaveOutcome.Saved).id
        fx.categories.createSubcategory(travel, "Rickshaw")
        fx.categories.archive(travel)

        assertTrue(fx.categories.restore(travel) is SaveOutcome.Saved)

        val node = fx.categories.observeTree().first().first { it.id == travel }
        assertFalse(node.isArchived)
        assertTrue("children stay put", node.children.all { it.isArchived })

        // And now that the group is back, a child may be restored on its own.
        assertTrue(fx.categories.restore(bus) is SaveOutcome.Saved)
        assertTrue(fx.categories.observeSelectableLeaves().first().any { it.id == bus })
    }

    @Test
    fun archive_reports_exactly_the_rows_it_changed() = runBlocking {
        // What makes the snackbar a real undo: a child already archived is not
        // in the list, so undoing does not resurrect it.
        val travel = (fx.categories.createRoot("Travel", Nature.VARIABLE) as SaveOutcome.Saved).id
        val bus = (fx.categories.createSubcategory(travel, "Bus fare") as SaveOutcome.Saved).id
        val rickshaw = (fx.categories.createSubcategory(travel, "Rickshaw") as SaveOutcome.Saved).id
        fx.categories.archive(bus)

        val outcome = fx.categories.archive(travel) as ArchiveOutcome.Archived
        assertEquals(setOf(travel, rickshaw), outcome.changed.toSet())

        fx.categories.restoreAll(outcome.changed)
        val node = fx.categories.observeTree().first().first { it.id == travel }
        assertFalse(node.isArchived)
        assertFalse(node.children.first { it.id == rickshaw }.isArchived)
        assertTrue("the one archived on purpose stays that way",
            node.children.first { it.id == bus }.isArchived)
    }

    @Test
    fun an_archived_leaf_leaves_the_picker_but_stays_in_the_ledger() = runBlocking {
        // FR-CAT-08 — the exact wording is "hidden from entry pickers and MUST
        // remain present ... in the ledger rows that reference them".
        val grocery = fx.leafId("Grocery")
        fx.expenses.insert(Money.ofTaka(120), grocery)
        fx.categories.archive(grocery)

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

    // --- FR-CAT-11: reorder within the parent (§20.2) ------------------------

    private suspend fun leaves(): List<String> =
        fx.categories.observeTree().first()
            .first { it.name == "Variable Expenses" }
            .activeChildren.map { it.name }

    /** Distinct rank count vs row count, for the normalisation claim. */
    private fun rankSpread(): Pair<Int, Int> =
        fx.db.openHelper.writableDatabase.query(
            """
            SELECT COUNT(DISTINCT sort_order), COUNT(*) FROM category
             WHERE parent_id = (SELECT id FROM category WHERE name = 'Variable Expenses')
            """.trimIndent(),
        ).use {
            it.moveToFirst()
            it.getInt(0) to it.getInt(1)
        }

    @Test
    fun a_child_swaps_with_the_sibling_above_it() = runBlocking {
        val before = leaves()
        assertTrue("this fixture needs at least three leaves", before.size >= 3)

        assertTrue(fx.categories.move(fx.leafId(before[1]), up = true))

        val after = leaves()
        assertEquals(before[1], after[0])
        assertEquals(before[0], after[1])
        assertEquals("and nothing below them moved", before.drop(2), after.drop(2))
    }

    @Test
    fun a_child_swaps_with_the_sibling_below_it() = runBlocking {
        val before = leaves()
        assertTrue(fx.categories.move(fx.leafId(before[0]), up = false))

        val after = leaves()
        assertEquals(before[1], after[0])
        assertEquals(before[0], after[1])
    }

    @Test
    fun the_ends_of_the_range_refuse_rather_than_wrap() = runBlocking {
        val before = leaves()

        assertFalse("nothing is above the first", fx.categories.move(fx.leafId(before.first()), up = true))
        assertFalse("nothing is below the last", fx.categories.move(fx.leafId(before.last()), up = false))
        assertEquals("and the order is untouched", before, leaves())
    }

    @Test
    fun a_move_normalises_ranks_the_seed_left_identical() = runBlocking {
        // Nothing wrote `sort_order` between M1 and §20.2, so a real tree can
        // hold a whole run of rows that all say the same thing — and a swap
        // between two rows that both say 0 is not a swap at all. This is the
        // case that makes the repository rewrite every sibling rather than two.
        fx.db.openHelper.writableDatabase.execSQL("UPDATE category SET sort_order = 0")
        val before = leaves()

        assertTrue(fx.categories.move(fx.leafId(before[1]), up = true))

        assertEquals(before[1], leaves().first())
        val (distinct, total) = rankSpread()
        assertEquals("every sibling ends up with a rank of its own", total, distinct)
    }

    @Test
    fun an_archived_sibling_is_not_a_place_to_move_to() = runBlocking {
        // FR-CAT-08 takes it out of the pickers; it is not part of the order
        // the user is arranging either. The last *active* child has nowhere
        // below it even though a row still sits there.
        val before = leaves()
        fx.categories.archive(fx.leafId(before.last()))

        val active = leaves()
        assertEquals(before.size - 1, active.size)
        assertFalse(fx.categories.move(fx.leafId(active.last()), up = false))
    }

    @Test
    fun the_new_order_is_what_the_next_read_sees() = runBlocking {
        // `sort_order` is what every category query in the app orders by, so a
        // move that did not survive a fresh read would be invisible everywhere
        // except the screen that performed it.
        val before = leaves()
        fx.categories.move(fx.leafId(before[2]), up = true)

        val reread = fx.categories.observeTree().first()
            .first { it.name == "Variable Expenses" }.activeChildren.map { it.name }
        assertEquals(before[2], reread[1])
    }
}
