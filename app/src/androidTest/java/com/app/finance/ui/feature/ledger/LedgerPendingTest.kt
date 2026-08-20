package com.app.finance.ui.feature.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.awaitState
import com.app.finance.core.money.Money
import com.app.finance.domain.model.Frequency
import com.app.finance.domain.model.RuleTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * FR-REC-02's one-tap confirmation, where it lives — atop the ledger.
 *
 * The section is above the day groups **and above the empty states**, which is
 * the case worth pinning: a first-run user whose only rows are unconfirmed
 * would otherwise read "nothing logged yet" with two entries waiting just out
 * of sight.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LedgerPendingTest {

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

    private fun vm(): LedgerViewModel = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                LedgerViewModel(fx.expenses, fx.categories, fx.recurring, fx.clock) as T
        },
    )["vm${seq++}", LedgerViewModel::class.java]

    private suspend fun generateRent(months: Int = 1) {
        fx.recurring.createRule(
            target = RuleTarget.EXPENSE,
            targetId = fx.leafId("House Rent"),
            amount = Money.ofTaka(15_000),
            frequency = Frequency.MONTHLY,
            anchorDay = 1,
            startingFrom = LocalDate.of(2026, 9 - months, 1),
        )
        fx.recurring.evaluate(LocalDate.of(2026, 8, 14))
    }

    private fun scalar(sql: String): Long =
        fx.db.openHelper.writableDatabase.query(sql)
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }

    // --- absent, not empty ----------------------------------------------------

    @Test
    fun there_is_no_section_when_nothing_is_pending() = runBlocking {
        val state = vm().state.awaitState { !it.initialLoad }
        assertEquals(0, state.pendingCount)
        assertTrue(state.pendingExpenses.isEmpty())
        assertTrue(state.pendingIncome.isEmpty())
    }

    @Test
    fun a_generated_entry_appears_with_its_category_name() = runBlocking {
        generateRent()
        val state = vm().state.awaitState { it.pendingCount == 1 }
        assertEquals("House Rent", state.pendingExpenses.single().categoryName)
        assertEquals(15_000_00L, state.pendingExpenses.single().expense.amountMinor)
    }

    @Test
    fun a_pending_entry_is_not_in_the_ledger_below_it() = runBlocking {
        // Every other read filters `status = 0`, and none of them changed.
        generateRent()
        val state = vm().state.awaitState { it.pendingCount == 1 }
        assertTrue("the day groups are empty", state.days.isEmpty())
        assertTrue("and the ledger reports itself empty", state.isEmpty)
    }

    @Test
    fun the_section_shows_even_when_the_ledger_is_otherwise_empty() = runBlocking {
        // The trap: the empty state and the pending section are siblings, not
        // alternatives. A first-run user with two unconfirmed entries must see
        // them, not an invitation to add their first expense.
        generateRent()
        val state = vm().state.awaitState { it.pendingCount == 1 }
        assertTrue("the empty state is showing", state.isEmpty)
        assertEquals("and so is the section", 1, state.pendingCount)
    }

    @Test
    fun several_missed_months_each_get_a_row() = runBlocking {
        // FR-REC-04 — "each individually confirmable".
        generateRent(months = 3)
        val state = vm().state.awaitState { it.pendingCount == 3 }
        assertEquals(3, state.pendingExpenses.size)
    }

    // --- the two answers ------------------------------------------------------

    @Test
    fun confirming_moves_the_row_into_the_ledger_and_the_figures() = runBlocking {
        generateRent()
        val vm = vm()
        val pending = vm.state.awaitState { it.pendingCount == 1 }.pendingExpenses.single()

        vm.confirmExpense(pending.expense.id)

        val after = vm.state.awaitState { it.pendingCount == 0 && it.days.isNotEmpty() }
        assertEquals(0, after.pendingCount)
        assertFalse(after.isEmpty)
        assertEquals(
            "the trigger folded it in",
            15_000_00L,
            scalar("SELECT IFNULL(SUM(total_minor), 0) FROM rollup_expense_month"),
        )
    }

    @Test
    fun dismissing_removes_it_from_everywhere() = runBlocking {
        generateRent()
        val vm = vm()
        val pending = vm.state.awaitState { it.pendingCount == 1 }.pendingExpenses.single()

        vm.dismissExpense(pending.expense.id)

        val after = vm.state.awaitState { it.pendingCount == 0 }
        assertTrue("still empty below", after.days.isEmpty())
        assertEquals("and nothing was posted", 0, scalar("SELECT COUNT(*) FROM expense"))
    }

    @Test
    fun confirming_one_leaves_the_others_waiting() = runBlocking {
        generateRent(months = 3)
        val vm = vm()
        val rows = vm.state.awaitState { it.pendingCount == 3 }.pendingExpenses

        vm.confirmExpense(rows.first().expense.id)

        assertEquals(2, vm.state.awaitState { it.pendingCount == 2 }.pendingCount)
    }

    // --- income ---------------------------------------------------------------

    @Test
    fun a_pending_income_entry_appears_with_its_source_name() = runBlocking {
        val salary = fx.db.incomeDao().observeAllSources().first().first { it.name == "Salary" }
        fx.recurring.createRule(
            target = RuleTarget.INCOME,
            targetId = salary.id,
            amount = Money.ofTaka(30_000),
            frequency = Frequency.MONTHLY,
            anchorDay = 1,
            startingFrom = LocalDate.of(2026, 8, 1),
        )
        fx.recurring.evaluate(LocalDate.of(2026, 8, 14))

        val state = vm().state.awaitState { it.pendingIncome.isNotEmpty() }
        assertEquals("Salary", state.pendingIncome.single().sourceName)
        assertEquals(
            "and it is in no income figure yet",
            0L,
            scalar("SELECT IFNULL(SUM(total_minor), 0) FROM rollup_income_month"),
        )
    }
}
