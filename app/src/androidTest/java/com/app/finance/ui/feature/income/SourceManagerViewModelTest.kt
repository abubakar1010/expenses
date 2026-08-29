package com.app.finance.ui.feature.income

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.awaitState
import com.app.finance.core.money.Money
import com.app.finance.domain.model.EntryError
import com.app.finance.domain.model.IncomeKind
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
import java.time.LocalDate

/**
 * The source manager — FR-IS-01 … FR-IS-06 through the UI layer.
 *
 * The delete is what this suite is really about. It is the only one in the
 * application, and the only place the category manager's "constraints are shown
 * by absence" rule is deliberately inverted, so both halves of FR-IS-05/06 get
 * pinned: the count that governs the control, and the refusal underneath it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SourceManagerViewModelTest {

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

    private fun vm(): SourceManagerViewModel = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SourceManagerViewModel(fx.income) as T
        },
    )["vm${seq++}", SourceManagerViewModel::class.java]

    private fun SourceManagerUiState.row(name: String) =
        sources.firstOrNull { it.source.name == name }

    // --- FR-IS-01, FR-IS-02 --------------------------------------------------

    @Test
    fun a_source_is_created_with_a_name_and_a_kind() = runBlocking {
        val vm = vm()
        vm.state.awaitState { !it.loading }

        vm.add()
        vm.setName("Real estate")
        vm.setKind(IncomeKind.STABLE)
        vm.submit {}

        val state = vm.state.awaitState { it.row("Real estate") != null }
        assertEquals(IncomeKind.STABLE.code, state.row("Real estate")!!.source.kind)
    }

    @Test
    fun a_new_source_defaults_to_variable() = runBlocking {
        // Calling something stable when it is not overstates the coverage
        // figure, and understating is the safe direction.
        val vm = vm()
        vm.state.awaitState { !it.loading }
        vm.add()
        assertEquals(IncomeKind.VARIABLE, (vm.state.value.editor as SourceEditor.New).kind)
    }

    @Test
    fun a_duplicate_name_surfaces_under_the_field() = runBlocking {
        // FR-IS-02 through the manager rather than through inline creation. The
        // unique index makes it impossible; this asserts the user reads a
        // sentence rather than an exception.
        val vm = vm()
        vm.state.awaitState { !it.loading }

        vm.add()
        vm.setName("  SALARY ")
        vm.submit {}

        val state = vm.state.awaitState { it.editor?.error != null }
        assertEquals(EntryError.DUPLICATE_NAME, state.editor!!.error)
        assertEquals("and nothing was created", 1, state.sources.size)
    }

    @Test
    fun renaming_can_also_change_the_kind() = runBlocking {
        // Unlike a category's nature, which FR-CAT-06 makes inherited and
        // un-overridable, a source's kind is its own — and a source that turns
        // out to arrive on a rhythm should be reclassifiable without losing its
        // history.
        val vm = vm()
        val state = vm.state.awaitState { it.row("Salary") != null }

        vm.rename(state.row("Salary")!!)
        vm.setName("Monthly salary")
        vm.setKind(IncomeKind.VARIABLE)
        vm.submit {}

        val after = vm.state.awaitState { it.row("Monthly salary") != null }
        assertEquals(IncomeKind.VARIABLE.code, after.row("Monthly salary")!!.source.kind)
    }

    // --- FR-IS-04 -------------------------------------------------------------

    @Test
    fun archiving_moves_a_source_to_its_own_section() = runBlocking {
        val vm = vm()
        val state = vm.state.awaitState { it.row("Salary") != null }
        vm.setArchived(state.row("Salary")!!, archived = true) {}

        val after = vm.state.awaitState { it.archived.any { r -> r.source.name == "Salary" } }
        assertFalse(after.active.any { it.source.name == "Salary" })
        assertFalse(
            "and out of the picker the entry sheet reads",
            fx.income.observeActiveSources().first().any { it.name == "Salary" },
        )
    }

    @Test
    fun archiving_is_undoable() = runBlocking {
        val vm = vm()
        val state = vm.state.awaitState { it.row("Salary") != null }
        val row = state.row("Salary")!!

        vm.setArchived(row, archived = true) {}
        vm.state.awaitState { it.archived.any { r -> r.source.name == "Salary" } }

        vm.setArchived(row, archived = false) {}
        val after = vm.state.awaitState { it.active.any { r -> r.source.name == "Salary" } }
        assertTrue(after.archived.isEmpty())
    }

    // --- FR-IS-05, FR-IS-06 ---------------------------------------------------

    @Test
    fun the_entry_count_is_what_governs_the_delete() = runBlocking {
        // The manager reads this count to decide whether the control is live.
        // FR-IS-05's criterion is a *disabled* action with a reason, so the
        // count has to reach the row rather than being checked on tap.
        fx.income.saveEntry(Money.ofTaka(30_000), "Salary", LocalDate.of(2026, 8, 1))
        fx.income.createSource("Consulting", IncomeKind.VARIABLE)

        val state = vm().state.awaitState { it.row("Consulting") != null && !it.loading }
        assertEquals(1, state.row("Salary")!!.entryCount)
        assertEquals(0, state.row("Consulting")!!.entryCount)
    }

    @Test
    fun a_source_with_no_entries_is_really_deleted() = runBlocking {
        // FR-IS-06 — and the only row in DayBook that ever leaves the database.
        fx.income.createSource("Consulting", IncomeKind.VARIABLE)

        val vm = vm()
        val state = vm.state.awaitState { it.row("Consulting") != null }
        vm.delete(state.row("Consulting")!!) { _, _ -> }

        val after = vm.state.awaitState { it.row("Consulting") == null }
        assertNull(after.row("Consulting"))
    }

    @Test
    fun a_delete_is_undoable_and_restores_the_same_row() = runBlocking {
        fx.income.createSource("Consulting", IncomeKind.STABLE)

        val vm = vm()
        val state = vm.state.awaitState { it.row("Consulting") != null }
        val uuid = state.row("Consulting")!!.source.uuid

        var restored: com.app.finance.data.db.entity.IncomeSourceEntity? = null
        vm.delete(state.row("Consulting")!!) { _, source -> restored = source }
        vm.state.awaitState { it.row("Consulting") == null }

        vm.undoDelete(restored!!)
        val after = vm.state.awaitState { it.row("Consulting") != null }
        assertEquals(uuid, after.row("Consulting")!!.source.uuid)
        assertEquals(IncomeKind.STABLE.code, after.row("Consulting")!!.source.kind)
    }

    @Test
    fun a_source_with_entries_is_refused_beneath_the_screen_too() = runBlocking {
        // FR-IS-05. The manager disables the control rather than offering it,
        // but the guard the screen relies on has to be real on its own — this
        // asserts the refusal directly rather than through the ViewModel,
        // because "nothing happened" is not a state a flow can be awaited on.
        fx.income.saveEntry(Money.ofTaka(30_000), "Salary", LocalDate.of(2026, 8, 1))
        val salary = vm().state.awaitState { it.row("Salary")?.entryCount == 1 }
            .row("Salary")!!.source.id

        assertEquals(
            com.app.finance.data.repo.DeleteSourceOutcome.Rejected(EntryError.SOURCE_HAS_ENTRIES),
            fx.income.deleteSource(salary),
        )
        assertTrue(fx.income.sourceById(salary) != null)
    }

    @Test
    fun deleting_becomes_possible_once_the_last_entry_is_gone() = runBlocking {
        val saved = fx.income.saveEntry(
            Money.ofTaka(1_000),
            "Consulting",
            LocalDate.of(2026, 8, 1),
        ) as com.app.finance.domain.model.SaveOutcome.Saved

        val vm = vm()
        vm.state.awaitState { it.row("Consulting")?.entryCount == 1 }

        fx.income.deleteEntry(saved.id)
        val state = vm.state.awaitState { it.row("Consulting")?.entryCount == 0 }

        vm.delete(state.row("Consulting")!!) { _, _ -> }
        assertNull(vm.state.awaitState { it.row("Consulting") == null }.row("Consulting"))
    }

    @Test
    fun a_rename_that_is_refused_leaves_the_kind_alone_too() = runBlocking {
        // The editor submits name and kind together now. A duplicate name has
        // to take the kind down with it, or the sheet reports a failure and the
        // source is quietly reclassified anyway.
        fx.income.createSource("Consulting", IncomeKind.VARIABLE)

        val vm = vm()
        val state = vm.state.awaitState { it.row("Consulting") != null }

        vm.rename(state.row("Consulting")!!)
        vm.setName("  salary ")
        vm.setKind(IncomeKind.STABLE)
        vm.submit {}

        val after = vm.state.awaitState { it.editor?.error != null }
        assertEquals(EntryError.DUPLICATE_NAME, after.editor!!.error)
        assertEquals("Consulting", after.row("Consulting")!!.source.name)
        assertEquals(IncomeKind.VARIABLE.code, after.row("Consulting")!!.source.kind)
    }
}
