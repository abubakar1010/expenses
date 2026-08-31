package com.app.finance.ui.feature.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.awaitState
import com.app.finance.core.money.Money
import com.app.finance.domain.model.SaveOutcome
import com.app.finance.domain.model.Split
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Balances — FR-SHR-05 — and the settlements that move them, FR-SHR-04.
 *
 * The reconciliation matters most here: every figure the screen renders has to
 * equal a direct sum over `expense_share`, `expense` and `settlement`
 * (NFR-REL-02). Nothing is backed by a rollup, so the query is the only place
 * it can go wrong.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PeopleViewModelTest {

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

    private fun vm(): PeopleViewModel = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                PeopleViewModel(fx.people, fx.settlements) as T
        },
    )["vm${seq++}", PeopleViewModel::class.java]

    private suspend fun person(name: String): Long =
        (fx.people.findOrCreate(name) as SaveOutcome.Saved).id

    private fun scalar(sql: String): Long =
        fx.db.openHelper.writableDatabase.query(sql)
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }

    /** You paid; [personIds] owe you their parts of [taka]. */
    private suspend fun sharedExpense(taka: Long, personIds: List<Long>) {
        val (yours, split) = Split.evenly(Money.ofTaka(taka), personIds)
        fx.expenses.insert(yours, fx.leafId("Grocery"), fx.today, split = split)
    }

    // --- the balance ----------------------------------------------------------

    @Test
    fun somebody_who_owes_you_appears_on_the_owed_side() = runBlocking {
        val rahim = person("Rahim")
        sharedExpense(1_000, listOf(rahim))

        val state = vm().state.awaitState { it.owedToYou.isNotEmpty() }

        assertEquals(1, state.owedToYou.size)
        assertEquals(Money.ofTaka(500).paisa, state.owedToYou.single().balanceMinor)
        assertTrue(state.youOwe.isEmpty())
    }

    @Test
    fun somebody_who_paid_for_you_appears_on_the_other_side() = runBlocking {
        // FR-SHR-03's case: no money left your wallet, so you owe them your
        // share. The sign is what tells the two apart.
        val rahim = person("Rahim")
        fx.expenses.insert(
            Money.ofTaka(250), fx.leafId("Grocery"), fx.today,
            split = Split.TheyPaid(rahim),
        )

        val state = vm().state.awaitState { it.youOwe.isNotEmpty() }

        assertEquals(Money.ofTaka(-250).paisa, state.youOwe.single().balanceMinor)
        assertTrue(state.owedToYou.isEmpty())
    }

    @Test
    fun the_two_directions_net_off_against_each_other() = runBlocking {
        // One balance per person, not one per direction. You bought lunch, they
        // bought dinner, and what is left is the only figure worth showing.
        val rahim = person("Rahim")
        sharedExpense(1_000, listOf(rahim)) // Rahim owes you 500
        fx.expenses.insert(
            Money.ofTaka(300), fx.leafId("Grocery"), fx.today,
            split = Split.TheyPaid(rahim), // you owe Rahim 300
        )

        val state = vm().state.awaitState { it.owedToYou.isNotEmpty() }
        assertEquals(Money.ofTaka(200).paisa, state.owedToYou.single().balanceMinor)
    }

    @Test
    fun every_balance_equals_a_direct_sum_over_the_ledger() = runBlocking {
        // NFR-REL-02, on the one figure this screen exists to show.
        val rahim = person("Rahim")
        sharedExpense(900, listOf(rahim))
        sharedExpense(1_500, listOf(rahim))
        fx.settlements.record(rahim, Money.ofTaka(200), fx.today)

        val state = vm().state.awaitState { it.owedToYou.isNotEmpty() }

        val direct = scalar(
            "SELECT IFNULL((SELECT SUM(s.share_minor) FROM expense_share s " +
                "JOIN expense e ON e.id = s.expense_id " +
                "WHERE s.person_id = $rahim AND e.status = 0), 0) " +
                "- IFNULL((SELECT SUM(amount_minor) FROM expense " +
                "WHERE payer_person_id = $rahim AND status = 0), 0) " +
                "- IFNULL((SELECT SUM(amount_minor) FROM settlement " +
                "WHERE person_id = $rahim), 0)",
        )
        assertEquals(direct, state.owedToYou.single().balanceMinor)
    }

    @Test
    fun somebody_square_is_in_neither_list() = runBlocking {
        // A settled account is not something to act on, and listing it would
        // bury the names that are.
        val rahim = person("Rahim")
        sharedExpense(1_000, listOf(rahim))
        fx.settlements.record(rahim, Money.ofTaka(500), fx.today)

        val state = vm().state.awaitState { it.settled.isNotEmpty() }
        assertTrue(state.owedToYou.isEmpty())
        assertTrue(state.youOwe.isEmpty())
        assertEquals(1, state.settled.size)
    }

    @Test
    fun the_section_totals_are_what_the_rows_add_up_to() = runBlocking {
        val rahim = person("Rahim")
        val karim = person("Karim")
        sharedExpense(1_000, listOf(rahim, karim)) // each owes ~333.34/333.33

        val state = vm().state.awaitState { it.owedToYou.size == 2 }
        assertEquals(
            state.owedToYou.sumOf { it.balanceMinor },
            state.totalOwedToYou.paisa,
        )
    }

    // --- settlements (FR-SHR-04) ----------------------------------------------

    @Test
    fun a_settlement_reaches_no_rollup() = runBlocking {
        // The reason `settlement` is its own table. A repayment counted as
        // income would lift the savings rate every time somebody paid you back.
        val rahim = person("Rahim")
        sharedExpense(1_000, listOf(rahim))
        val before = scalar("SELECT IFNULL(SUM(total_minor), 0) FROM rollup_expense_month")

        fx.settlements.record(rahim, Money.ofTaka(500), fx.today)

        assertEquals(before, scalar("SELECT IFNULL(SUM(total_minor), 0) FROM rollup_expense_month"))
        assertEquals(0L, scalar("SELECT IFNULL(SUM(total_minor), 0) FROM rollup_income_month"))
    }

    @Test
    fun a_loan_with_no_expense_behind_it_creates_a_balance() = runBlocking {
        // "I lent Rahim ৳500." Nothing was consumed, so there is no expense —
        // and charging a category for it would be the mistake this table exists
        // to avoid.
        val rahim = person("Rahim")
        fx.settlements.record(rahim, Money.ofTaka(-500), fx.today)

        val state = vm().state.awaitState { it.owedToYou.isNotEmpty() }
        assertEquals(Money.ofTaka(500).paisa, state.owedToYou.single().balanceMinor)
        assertEquals(0L, scalar("SELECT COUNT(*) FROM expense"))
    }

    @Test
    fun a_settlement_dated_in_the_future_is_refused() = runBlocking {
        val rahim = person("Rahim")
        val outcome = fx.settlements.record(rahim, Money.ofTaka(100), fx.today.plusDays(1))
        assertTrue("a future settlement was accepted: $outcome", outcome is SaveOutcome.Rejected)
        assertEquals(0L, scalar("SELECT COUNT(*) FROM settlement"))
    }

    @Test
    fun undoing_a_settlement_puts_the_balance_back() = runBlocking {
        val rahim = person("Rahim")
        sharedExpense(1_000, listOf(rahim))
        val settlementId =
            (fx.settlements.record(rahim, Money.ofTaka(500), fx.today) as SaveOutcome.Saved).id

        val vm = vm()
        vm.state.awaitState { it.settled.isNotEmpty() }

        vm.deleteSettlement(settlementId)
        val removed = vm.state.awaitState { it.owedToYou.isNotEmpty() }
        assertEquals(Money.ofTaka(500).paisa, removed.owedToYou.single().balanceMinor)

        vm.undo(removed.undoQueue.first().id)
        val restored = vm.state.awaitState { it.settled.isNotEmpty() }
        assertTrue(restored.owedToYou.isEmpty())
    }

    // --- the editor -----------------------------------------------------------

    @Test
    fun adding_a_person_who_already_exists_does_not_create_a_second() = runBlocking {
        person("Rahim")
        val vm = vm()
        vm.addPerson()
        vm.setEditorName(" rahim ")
        vm.submitEditor()

        vm.state.awaitState { it.editor == null }
        assertEquals(1L, scalar("SELECT COUNT(*) FROM person"))
    }

    @Test
    fun renaming_onto_an_existing_name_shows_the_error_in_the_editor() = runBlocking {
        // Field-level, not a snackbar: the user is looking at the thing that
        // was wrong, and merging two people would merge two balances silently.
        person("Rahim")
        val karim = person("Karim")
        sharedExpense(1_000, listOf(karim))

        val vm = vm()
        vm.state.awaitState { it.balances.isNotEmpty() }
        vm.rename(vm.state.value.balances.first { it.personId == karim })
        vm.setEditorName("Rahim")
        vm.submitEditor()

        val state = vm.state.awaitState { it.editor?.error != null }
        assertEquals(
            com.app.finance.domain.model.EntryError.DUPLICATE_NAME,
            state.editor?.error,
        )
    }
}
