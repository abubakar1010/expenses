package com.app.finance.ui.feature.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.awaitState
import com.app.finance.core.money.Money
import com.app.finance.domain.model.CategoryNode
import com.app.finance.domain.model.EntryError
import com.app.finance.domain.model.Nature
import com.app.finance.domain.model.SaveOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FR-CAT-03 … FR-CAT-10 through the layer the screen actually calls.
 *
 * The rules themselves are enforced by triggers, indices and
 * `CategoryRepository`, and `SchemaAssertionsTest` and `CategoryRepositoryTest`
 * already prove that. What is asserted here is the part the manager screen is
 * responsible for: that the state it renders from carries enough information to
 * make a forbidden action *absent* rather than merely rejected, and that the
 * typed errors land on the field the user is looking at.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CategoryManagerViewModelTest {

    private lateinit var fx: TestFixture
    private val store = ViewModelStore()
    private var seq = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        fx = TestFixture()
    }

    @After
    fun tearDown() {
        store.clear()
        fx.closeAfterDraining()
        Dispatchers.resetMain()
    }

    private fun vm(): CategoryManagerViewModel = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CategoryManagerViewModel(fx.categories) as T
        },
    )["vm${seq++}", CategoryManagerViewModel::class.java]

    private fun CategoryManagerUiState.root(name: String): CategoryNode =
        tree.first { it.name == name }

    private suspend fun typeAndSubmit(vm: CategoryManagerViewModel, name: String) {
        vm.setName(name)
        vm.submit {}
    }

    // --- the tree the screen renders from -----------------------------------

    @Test
    fun the_seeded_tree_arrives_with_its_system_roots_flagged() = runBlocking {
        // FR-CAT-03's acceptance criterion is that archive is *absent* on these,
        // which the screen can only honour if the flag reaches it.
        val state = vm().state.awaitState { !it.loading }
        assertEquals(3, state.active.size)
        assertTrue(state.active.all { it.isSystem })
        assertEquals(13, state.active.sumOf { it.children.size })
        assertTrue(state.archived.isEmpty())
    }

    // --- FR-CAT-04, FR-CAT-05, FR-CAT-06 ------------------------------------

    @Test
    fun a_new_root_takes_the_nature_it_was_given() = runBlocking {
        val vm = vm()
        vm.state.awaitState { !it.loading }
        vm.addRoot()
        vm.setNature(Nature.UNPREDICTABLE)
        typeAndSubmit(vm, "Emergencies")

        val created = vm.state.awaitState { it.tree.any { n -> n.name == "Emergencies" } }
            .root("Emergencies")
        assertEquals(Nature.UNPREDICTABLE, created.nature)
        assertFalse("a user root is not a system root", created.isSystem)
    }

    @Test
    fun a_child_inherits_its_parents_nature_and_is_never_asked_for_one() = runBlocking {
        // FR-CAT-06. The editor for a child has no nature field at all, so the
        // only thing that could set it is the trigger — and it does.
        val vm = vm()
        val fixed = vm.state.awaitState { !it.loading }.root("Fixed Expenses")
        vm.addChild(fixed)

        assertTrue(vm.state.value.editor is CategoryEditor.NewChild)
        typeAndSubmit(vm, "Water Bill")

        val child = vm.state.awaitState { it.root("Fixed Expenses").children.any { c -> c.name == "Water Bill" } }
            .root("Fixed Expenses").children.first { it.name == "Water Bill" }
        assertEquals(Nature.FIXED, child.nature)
    }

    @Test
    fun setting_a_nature_on_a_child_editor_is_ignored_rather_than_half_applied() = runBlocking {
        val vm = vm()
        val variable = vm.state.awaitState { !it.loading }.root("Variable Expenses")
        vm.addChild(variable)
        vm.setNature(Nature.FIXED)

        assertTrue("the editor must not mutate into a root editor", vm.state.value.editor is CategoryEditor.NewChild)
        typeAndSubmit(vm, "Snacks")

        val child = vm.state.awaitState { it.root("Variable Expenses").children.any { c -> c.name == "Snacks" } }
            .root("Variable Expenses").children.first { it.name == "Snacks" }
        assertEquals(Nature.VARIABLE, child.nature)
    }

    @Test
    fun a_third_level_can_never_be_reached_through_this_screen() = runBlocking {
        // FR-CAT-05 — the screen offers "add category" on roots only. Should a
        // future caller reach past that, the depth trigger still refuses.
        val vm = vm()
        val grocery = vm.state.awaitState { !it.loading }
            .root("Variable Expenses").children.first { it.name == "Grocery" }
        assertTrue("a leaf must have no children to offer", grocery.children.isEmpty())

        vm.addChild(grocery)
        typeAndSubmit(vm, "Rice")

        assertEquals(
            EntryError.CATEGORY_TOO_DEEP,
            vm.state.awaitState { it.editor?.error != null }.editor!!.error,
        )
    }

    // --- FR-CAT-03, FR-CAT-07 ----------------------------------------------

    @Test
    fun renaming_is_permitted_on_a_system_root_because_it_is_the_one_thing_they_allow() = runBlocking {
        val vm = vm()
        val fixed = vm.state.awaitState { !it.loading }.root("Fixed Expenses")
        vm.rename(fixed)
        typeAndSubmit(vm, "Bills & Rent")

        assertTrue(
            vm.state.awaitState { it.tree.any { n -> n.name == "Bills & Rent" } }
                .root("Bills & Rent").isSystem,
        )
    }

    @Test
    fun a_duplicate_name_surfaces_under_the_field_rather_than_as_an_exception() = runBlocking {
        // FR-CAT-07 — scoped to the parent, and case- and whitespace-insensitive
        // through name_key, which is why "  grocery " collides with "Grocery".
        val vm = vm()
        val variable = vm.state.awaitState { !it.loading }.root("Variable Expenses")
        vm.addChild(variable)
        typeAndSubmit(vm, "  grocery ")

        val editor = vm.state.awaitState { it.editor?.error != null }.editor!!
        assertEquals(EntryError.DUPLICATE_NAME, editor.error)
        assertEquals("the typed text must survive the error", "  grocery ", editor.name)
    }

    @Test
    fun the_same_leaf_name_under_two_different_roots_is_accepted() = runBlocking {
        // "Fixed → Misc" and "Variable → Misc" must coexist.
        val vm = vm()
        val state = vm.state.awaitState { !it.loading }
        vm.addChild(state.root("Fixed Expenses"))
        typeAndSubmit(vm, "Misc")
        vm.state.awaitState { it.root("Fixed Expenses").children.any { c -> c.name == "Misc" } }

        vm.addChild(vm.state.value.root("Variable Expenses"))
        typeAndSubmit(vm, "Misc")

        // Two independent updates land here: the tree arrives on a Room flow and
        // the editor closes in the save coroutine, so a state that has one need
        // not yet have the other. Waiting only for the tree observed the editor
        // still open and failed — under JaCoCo's slower timing, which changed
        // nothing about the behaviour and only widened the window.
        //
        // The predicate settles on *either* outcome so a real failure is still
        // reported as one rather than as a timeout.
        val after = vm.state.awaitState { s ->
            s.editor?.error != null ||
                (s.editor == null && s.root("Variable Expenses").children.any { c -> c.name == "Misc" })
        }
        assertNull("the save raised ${after.editor?.error}", after.editor)
        assertEquals(2, after.tree.sumOf { r -> r.children.count { it.name == "Misc" } })
    }

    @Test
    fun a_blank_name_is_refused() = runBlocking {
        val vm = vm()
        val variable = vm.state.awaitState { !it.loading }.root("Variable Expenses")
        vm.addChild(variable)
        typeAndSubmit(vm, "   ")

        assertEquals(
            EntryError.BLANK_NAME,
            vm.state.awaitState { it.editor?.error != null }.editor!!.error,
        )
    }

    @Test
    fun typing_again_clears_the_error() = runBlocking {
        // An error that outlives the input that caused it reads as broken.
        val vm = vm()
        val variable = vm.state.awaitState { !it.loading }.root("Variable Expenses")
        vm.addChild(variable)
        typeAndSubmit(vm, "Grocery")
        vm.state.awaitState { it.editor?.error != null }

        vm.setName("Groceries")
        assertNull(vm.state.value.editor!!.error)
    }

    // --- FR-CAT-08, FR-CAT-09 ----------------------------------------------

    @Test
    fun archiving_a_leaf_moves_it_to_its_own_section_rather_than_deleting_it() = runBlocking {
        val vm = vm()
        val grocery = vm.state.awaitState { !it.loading }
            .root("Variable Expenses").children.first { it.name == "Grocery" }

        vm.archive(grocery) { _, _ -> }

        val after = vm.state.awaitState { it.archived.any { c -> c.node.name == "Grocery" } }
        assertTrue(after.archived.any { it.node.name == "Grocery" })
        assertFalse(
            "and out of the active list the pickers read",
            after.root("Variable Expenses").activeChildren.any { it.name == "Grocery" },
        )
    }

    @Test
    fun archiving_a_root_takes_its_children_with_it_and_undo_brings_them_all_back() = runBlocking {
        // FR-CAT-09's cascade, and 05 §8's five-second undo. The undo has to
        // restore the children too, or archiving becomes a one-way trip for
        // everything below the root.
        val vm = vm()
        val variable = (fx.categories.createRoot("Travel", Nature.VARIABLE)
            as com.app.finance.domain.model.SaveOutcome.Saved).id
        fx.categories.createSubcategory(variable, "Bus fare")
        fx.categories.createSubcategory(variable, "Rickshaw")

        // Waiting on the *children* rather than on the root: the three writes
        // land as three separate invalidations, so "Travel exists" is true one
        // emission before "Travel has both its children".
        val travel = vm.state
            .awaitState { it.tree.firstOrNull { n -> n.name == "Travel" }?.children?.size == 2 }
            .root("Travel")
        assertEquals(2, travel.children.size)

        var reported: String? = null
        var changed: List<Long> = emptyList()
        vm.archive(travel) { name, ids -> reported = name; changed = ids }

        val archived = vm.state.awaitState { it.tree.first { n -> n.name == "Travel" }.isArchived }
        assertEquals("Travel", reported)
        assertEquals("the root and both children", 3, changed.size)
        assertTrue(archived.tree.first { it.name == "Travel" }.children.all { it.isArchived })
        assertFalse(archived.active.any { it.name == "Travel" })

        vm.undoArchive(changed)

        val restored = vm.state.awaitState { it.active.any { n -> n.name == "Travel" } }
        assertTrue(restored.root("Travel").children.none { it.isArchived })
    }

    @Test
    fun undo_restores_what_the_archive_took_and_not_what_was_already_gone() = runBlocking {
        // The bug this pins: a cascade that restores every child unconditionally
        // un-archives one the user had retired on purpose, weeks earlier. An
        // undo is only an undo if it reverses exactly the action it follows.
        val vm = vm()
        val travel = (fx.categories.createRoot("Travel", Nature.VARIABLE)
            as com.app.finance.domain.model.SaveOutcome.Saved).id
        val bus = (fx.categories.createSubcategory(travel, "Bus fare")
            as com.app.finance.domain.model.SaveOutcome.Saved).id
        fx.categories.createSubcategory(travel, "Rickshaw")

        // Retired deliberately, before the root was touched at all.
        fx.categories.archive(bus)

        val node = vm.state
            .awaitState { s ->
                s.tree.firstOrNull { it.name == "Travel" }
                    ?.children?.count { it.isArchived } == 1
            }
            .root("Travel")

        var changed: List<Long> = emptyList()
        vm.archive(node) { _, ids -> changed = ids }
        vm.state.awaitState { it.tree.first { n -> n.name == "Travel" }.isArchived }

        assertEquals("the root and the one active child, not Bus fare", 2, changed.size)
        assertFalse(bus in changed)

        vm.undoArchive(changed)

        val restored = vm.state.awaitState { it.active.any { n -> n.name == "Travel" } }
        val children = restored.root("Travel").children
        assertTrue("Rickshaw is back", children.first { it.name == "Rickshaw" }.isArchived.not())
        assertTrue("Bus fare stays archived", children.first { it.name == "Bus fare" }.isArchived)
    }

    @Test
    fun a_child_of_an_archived_root_offers_no_restore_of_its_own() = runBlocking {
        // Restoring it alone would put an active leaf under a group that is not
        // there — and the entry picker would offer it. FR-CAT-08/09. The action
        // is absent rather than rejected, as everywhere else on this screen.
        val vm = vm()
        val travel = (fx.categories.createRoot("Travel", Nature.VARIABLE)
            as com.app.finance.domain.model.SaveOutcome.Saved).id
        fx.categories.createSubcategory(travel, "Bus fare")

        val node = vm.state
            .awaitState { it.tree.firstOrNull { n -> n.name == "Travel" }?.children?.size == 1 }
            .root("Travel")
        vm.archive(node) { _, _ -> }

        val after = vm.state.awaitState { s -> s.archived.any { it.node.name == "Bus fare" } }
        assertTrue(
            "the root itself can be restored",
            after.archived.first { it.node.name == "Travel" }.restorable,
        )
        assertFalse(
            "its child cannot, on its own",
            after.archived.first { it.node.name == "Bus fare" }.restorable,
        )

        // And the repository refuses it even if something asked.
        assertEquals(
            SaveOutcome.Rejected(EntryError.CATEGORY_ARCHIVED),
            fx.categories.restore(after.archived.first { it.node.name == "Bus fare" }.node.id),
        )
    }

    @Test
    fun an_archived_category_keeps_its_history() = runBlocking {
        // FR-CAT-08 — hidden from entry pickers, never from the ledger. This is
        // why there is no delete: the expenses would have nowhere to point.
        val grocery = fx.leafId("Grocery")
        fx.expenses.insert(Money.ofTaka(640), grocery, fx.today)

        val vm = vm()
        val node = vm.state.awaitState { !it.loading }
            .root("Variable Expenses").children.first { it.name == "Grocery" }
        vm.archive(node) { _, _ -> }
        vm.state.awaitState { it.archived.any { c -> c.node.name == "Grocery" } }

        assertEquals(1, fx.expenses.firstPage().size)
        assertFalse(
            "and it is gone from the entry picker",
            fx.categories.observeSelectableLeaves().first().any { it.id == grocery },
        )
    }

    @Test
    fun the_repository_exposes_no_delete_at_all() {
        // FR-CAT-10 — "Categories MUST NOT be deletable once referenced." The
        // product's answer is that they are never deletable, so the method does
        // not exist rather than being guarded. A screen cannot offer what has no
        // call site.
        val names = com.app.finance.data.repo.CategoryRepository::class.java.methods.map { it.name }
        assertTrue(
            "found a delete-shaped method: $names",
            names.none { it.startsWith("delete") || it.startsWith("remove") },
        )
    }
}
