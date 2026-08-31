package com.app.finance.ui.feature.entry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.awaitState
import com.app.finance.core.money.Money
import com.app.finance.domain.model.EntryError
import com.app.finance.domain.model.PaymentMethod
import com.app.finance.domain.model.LedgerFilters
import com.app.finance.domain.model.SaveOutcome
import com.app.finance.domain.model.Split
import com.app.finance.ui.common.KeypadKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The entry ViewModel against a real in-memory database and a pinned clock.
 *
 * Real Room rather than mocked repositories, because the behaviour worth testing
 * — that a save reaches the ledger, that the leaf rule rejects a group, that the
 * last-used defaults come back — is the interaction between the three, and a
 * mock would only confirm the code calls what it was written to call.
 *
 * `runBlocking`, not `runTest`: Room dispatches onto its own executor, so these
 * are integration tests over real threads and real time. `runTest`'s virtual
 * clock would expire every timeout instantly while the database was still
 * answering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class QuickAddViewModelTest {

    private lateinit var fx: TestFixture

    /**
     * ViewModels come from a store so [ViewModelStore.clear] can cancel
     * `viewModelScope` in teardown. This one collects the category tree for as
     * long as it lives; left running it outlives the test and hits a closed
     * connection pool during the next, failing nowhere near its cause.
     */
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

    /** A fresh key each call, so a test that opens the sheet twice gets two. */
    private fun vm(): QuickAddViewModel = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                QuickAddViewModel(fx.expenses, fx.categories, fx.meta, fx.people, fx.clock) as T
        },
    )["vm${seq++}", QuickAddViewModel::class.java]

    /** Started and settled: chips loaded and the form seeded. */
    private suspend fun startedVm(editingId: Long? = null): QuickAddViewModel {
        val vm = vm()
        vm.start(editingId)
        vm.state.awaitState { it.seeded && it.chips.isNotEmpty() }
        return vm
    }

    private suspend fun QuickAddViewModel.saveAndWait() {
        val done = CompletableDeferred<Unit>()
        save { done.complete(Unit) }
        withTimeout(5_000) { done.await() }
    }

    // --- the sheet is reused, and must come back clean -----------------------

    @Test
    fun a_saved_sheet_does_not_still_belong_to_the_row_it_saved() = runBlocking {
        // `vm()` hands out a fresh instance per call, which is what let this
        // through: in the app the sheet's owner is the **Activity**, so one
        // instance serves every open for the life of the process. This test
        // reuses one on purpose.
        val grocery = fx.leafId("Grocery")
        val first = (fx.expenses.insert(Money.ofTaka(500), grocery, fx.today) as SaveOutcome.Saved).id

        val vm = vm()
        vm.start(first)
        vm.state.awaitState { it.seeded && it.editingId == first }
        vm.saveAndWait()

        // Second open, same instance, this time a new entry.
        vm.start(null)
        val reopened = vm.state.awaitState { it.seeded }

        assertNull("the sheet is still editing the row it just saved", reopened.editingId)
        assertFalse("the save button would be dead on reopen", reopened.saving)
    }

    @Test
    fun the_entry_after_an_edit_is_an_insert_and_not_a_rewrite() = runBlocking {
        // The consequence of the above, and the reason it is Tier 1: a stale
        // `editingId` sends `save()` down the `update` branch, so the next
        // "new" expense silently overwrites the last one edited.
        val grocery = fx.leafId("Grocery")
        val first = (fx.expenses.insert(Money.ofTaka(500), grocery, fx.today) as SaveOutcome.Saved).id

        val vm = vm()
        vm.start(first)
        vm.state.awaitState { it.seeded && it.editingId == first }
        vm.saveAndWait()

        vm.start(null)
        vm.state.awaitState { it.seeded }
        "250".forEach { vm.onKey(KeypadKey.Digit(it)) }
        vm.saveAndWait()

        val rows = fx.db.backupDao().allExpenses()
        assertEquals("the second entry replaced the first instead of joining it", 2, rows.size)
        assertEquals(
            "the edited row was rewritten by the entry that followed it",
            Money.ofTaka(500).paisa,
            rows.first { it.id == first }.amountMinor,
        )
    }

    // --- keypad -------------------------------------------------------------

    @Test
    fun digits_accumulate_and_backspace_removes_one() = runBlocking {
        val vm = startedVm()
        "250".forEach { vm.onKey(KeypadKey.Digit(it)) }
        assertEquals(Money.ofTaka(250), vm.state.value.amount)

        vm.onKey(KeypadKey.Backspace)
        assertEquals(Money.ofTaka(25), vm.state.value.amount)
    }

    @Test
    fun paisa_are_capped_at_two_places() = runBlocking {
        val vm = startedVm()
        listOf('1', '2').forEach { vm.onKey(KeypadKey.Digit(it)) }
        vm.onKey(KeypadKey.Decimal)
        listOf('3', '4', '5', '6').forEach { vm.onKey(KeypadKey.Digit(it)) }
        // Extra digits are ignored on entry, not accepted and then silently
        // truncated at parse time.
        assertEquals(Money(1234), vm.state.value.amount)
    }

    @Test
    fun a_leading_decimal_is_refused_and_a_second_one_ignored() = runBlocking {
        val vm = startedVm()
        vm.onKey(KeypadKey.Decimal)
        assertEquals("", vm.state.value.input)

        vm.onKey(KeypadKey.Digit('5'))
        vm.onKey(KeypadKey.Decimal)
        vm.onKey(KeypadKey.Decimal)
        assertEquals("5.", vm.state.value.input)
    }

    @Test
    fun negate_toggles_the_sign_for_a_refund() = runBlocking {
        // FR-EXP-06 — a refund is the same entry with the sign flipped.
        val vm = startedVm()
        listOf('5', '0').forEach { vm.onKey(KeypadKey.Digit(it)) }
        vm.onKey(KeypadKey.Negate)
        assertEquals(Money.ofTaka(-50), vm.state.value.amount)

        vm.onKey(KeypadKey.Negate)
        assertEquals(Money.ofTaka(50), vm.state.value.amount)
    }

    @Test
    fun save_is_disabled_until_a_non_zero_amount_exists() = runBlocking {
        val vm = startedVm()
        assertFalse("no amount yet", vm.state.value.canSave)

        vm.onKey(KeypadKey.Digit('0'))
        assertFalse("zero is not an amount", vm.state.value.canSave)

        vm.onKey(KeypadKey.Digit('5'))
        assertTrue(vm.state.value.canSave)
    }

    // --- defaults (FR-EXP-02/03) --------------------------------------------

    @Test
    fun a_new_entry_defaults_to_today_and_seeds_six_chips() = runBlocking {
        val state = startedVm().state.value

        assertEquals(fx.today, state.date)
        assertEquals(6, state.chips.size)
        assertTrue("a category is pre-selected", state.selectedCategoryId != null)
    }

    @Test
    fun the_last_used_category_and_method_come_back_on_the_next_open() = runBlocking {
        val transport = fx.leafId("Transport")
        fx.expenses.insert(Money.ofTaka(60), transport, method = PaymentMethod.NAGAD)

        val vm = vm()
        vm.start(null)
        // Two independent flows settle here — the seeded form fields and the
        // most-recently-used chip order — so both conditions are awaited.
        // Waiting on only the first reads the chip row a beat too early.
        val state = vm.state.awaitState {
            it.seeded && it.chips.firstOrNull()?.id == transport
        }
        assertEquals(transport, state.selectedCategoryId)

        assertEquals(PaymentMethod.NAGAD, state.method)
    }

    // --- dates (FR-EXP-02) --------------------------------------------------

    @Test
    fun a_past_date_is_accepted() = runBlocking {
        val vm = startedVm()
        vm.setDate(fx.today.minusDays(3))
        assertEquals(fx.today.minusDays(3), vm.state.value.date)
        assertNull(vm.state.value.error)
    }

    @Test
    fun a_future_date_is_refused_with_a_typed_error() = runBlocking {
        // The date would otherwise post into the period rollup and inflate
        // spending that has not happened.
        val vm = startedVm()
        vm.setDate(fx.today.plusDays(1))
        assertEquals(fx.today, vm.state.value.date)
        assertEquals(EntryError.FUTURE_DATE, vm.state.value.error)
    }

    // --- saving -------------------------------------------------------------

    @Test
    fun saving_writes_the_expense_with_the_chosen_category_and_method() = runBlocking {
        val vm = startedVm()
        val grocery = fx.leafId("Grocery")
        vm.selectCategory(grocery)
        "250".forEach { vm.onKey(KeypadKey.Digit(it)) }
        vm.setMethod(PaymentMethod.BKASH)

        vm.saveAndWait()

        val row = fx.expenses.firstPage().single()
        assertEquals(25_000L, row.expense.amountMinor)
        assertEquals(grocery, row.expense.categoryId)
        assertEquals(PaymentMethod.BKASH.code, row.expense.paymentMethod)
    }

    @Test
    fun saving_a_zero_amount_surfaces_the_error_rather_than_writing() = runBlocking {
        val vm = startedVm()
        var saved = false
        vm.save { saved = true }

        assertFalse(saved)
        assertEquals(EntryError.ZERO_AMOUNT, vm.state.value.error)
        assertTrue(fx.expenses.firstPage().isEmpty())
    }

    // --- editing (FR-EXP-07) ------------------------------------------------

    @Test
    fun starting_with_an_id_seeds_the_form_from_that_row() = runBlocking {
        val grocery = fx.leafId("Grocery")
        val id = (
            fx.expenses.insert(
                Money.ofTaka(-320), grocery,
                spentOn = fx.today.minusDays(2),
                method = PaymentMethod.CARD,
                note = "returned the kettle",
            ) as SaveOutcome.Saved
            ).id

        val state = startedVm(editingId = id).state.value

        assertTrue(state.isEditing)
        assertEquals(Money.ofTaka(-320), state.amount)
        assertTrue("the refund sign is restored", state.negative)
        assertEquals(grocery, state.selectedCategoryId)
        assertEquals(fx.today.minusDays(2), state.date)
        assertEquals(PaymentMethod.CARD, state.method)
        assertEquals("returned the kettle", state.note)
    }

    @Test
    fun saving_an_edit_updates_rather_than_inserting() = runBlocking {
        val grocery = fx.leafId("Grocery")
        val id = (fx.expenses.insert(Money.ofTaka(100), grocery) as SaveOutcome.Saved).id

        val vm = startedVm(editingId = id)
        vm.onKey(KeypadKey.Digit('9'))
        vm.saveAndWait()

        val all = fx.expenses.firstPage()
        assertEquals("still one row", 1, all.size)
        assertEquals(id, all.single().expense.id)
    }

    @Test
    fun deleting_from_edit_mode_removes_the_row() = runBlocking {
        val grocery = fx.leafId("Grocery")
        val id = (fx.expenses.insert(Money.ofTaka(100), grocery) as SaveOutcome.Saved).id

        val vm = startedVm(editingId = id)
        val done = CompletableDeferred<Long>()
        vm.delete { done.complete(it) }

        assertEquals(id, withTimeout(5_000) { done.await() })
        assertTrue(fx.expenses.firstPage().isEmpty())
    }

    // --- reset (the reopened-sheet defect) ----------------------------------

    @Test
    fun reset_clears_a_half_typed_amount_so_the_next_open_starts_clean() = runBlocking {
        // The sheet's ViewModel is owned by the Activity and outlives a
        // dismissal, so without this a reopened sheet still showed the last
        // amount typed.
        val vm = startedVm()
        "999".forEach { vm.onKey(KeypadKey.Digit(it)) }
        assertEquals(Money.ofTaka(999), vm.state.value.amount)

        vm.reset()
        vm.start(null)
        val state = vm.state.awaitState { it.seeded && it.chips.isNotEmpty() }

        assertNull(state.amount)
        assertFalse(state.isEditing)
        assertEquals(fx.today, state.date)
    }

    // --- splitting (FR-SHR-02, FR-SHR-03) ------------------------------------

    private suspend fun person(name: String): Long =
        (fx.people.findOrCreate(name) as SaveOutcome.Saved).id

    private suspend fun QuickAddViewModel.type(amount: String) {
        amount.forEach { onKey(KeypadKey.Digit(it)) }
    }

    @Test
    fun the_amount_field_holds_the_bill_and_the_ledger_stores_your_share() = runBlocking {
        // The decision the whole entry flow rests on: you type what left your
        // wallet, and the app does the division. Typing your share instead
        // would mean doing it in your head at the till.
        val vm = startedVm()
        val rahim = person("Rahim")
        val karim = person("Karim")
        vm.state.awaitState { it.people.size == 2 }

        vm.type("1000")
        vm.splitEvenly(listOf(rahim, karim))

        val state = vm.state.awaitState { it.split.owed.size == 2 }
        assertEquals("the field should still show the bill", Money.ofTaka(1_000), state.amount)
        // ৳333.34 — 100,000 paisa three ways is 33,333 remainder 1, and your
        // share is the remainder, so it takes the odd paisa.
        assertEquals("the ledger gets your share", Money(33_334), state.yourShare)
        // ৳1,000 three ways is 33,334 + 33,333 + 33,333 — every paisa placed.
        assertEquals(
            100_000L,
            state.yourShare!!.paisa + state.split.owed.sumOf { it.amount.paisa },
        )
    }

    @Test
    fun an_even_split_follows_the_bill_as_it_is_typed() = runBlocking {
        // The split is derived from the mode, not stored as amounts. Storing
        // them would leave the shares describing a bill of ৳10 the moment a
        // third digit landed, and nothing on screen would say so.
        val vm = startedVm()
        val rahim = person("Rahim")
        vm.state.awaitState { it.people.isNotEmpty() }

        vm.type("10")
        vm.splitEvenly(listOf(rahim))
        assertEquals(Money.ofTaka(5), vm.state.awaitState { it.split.owed.size == 1 }.yourShare)

        vm.type("00") // now ৳1,000
        assertEquals(
            Money.ofTaka(500),
            vm.state.awaitState { it.amount == Money.ofTaka(1_000) }.yourShare,
        )
    }

    @Test
    fun someone_else_paying_and_someone_owing_you_are_one_choice() = runBlocking {
        // trg_payer_excludes_shares makes them mutually exclusive in the
        // database. Choosing one has to clear the other here, or the sheet
        // would let the user build a row that cannot be written.
        val vm = startedVm()
        val rahim = person("Rahim")
        vm.state.awaitState { it.people.isNotEmpty() }

        vm.type("1000")
        vm.splitEvenly(listOf(rahim))
        assertTrue(vm.state.awaitState { it.split.owed.isNotEmpty() }.split is Split.YouPaid)

        vm.paidBy(rahim)
        val state = vm.state.awaitState { it.splitMode == SplitMode.THEY_PAID }
        assertTrue(state.split is Split.TheyPaid)
        assertTrue("shares survived the switch", state.split.owed.isEmpty())
        assertEquals("your share is what you typed", Money.ofTaka(1_000), state.yourShare)
    }

    @Test
    fun a_bill_entirely_owed_by_others_cannot_be_saved() = runBlocking {
        // Paying wholly on somebody's behalf is a loan, not consumption, and
        // `CHECK (amount_minor <> 0)` refuses the row. The Save button has to
        // say so before the database does.
        val vm = startedVm()
        val rahim = person("Rahim")
        vm.state.awaitState { it.people.isNotEmpty() }

        vm.type("500")
        vm.splitByAmount(listOf(Split.Owed(rahim, Money.ofTaka(500))))

        val state = vm.state.awaitState { it.split.owed.size == 1 }
        assertEquals(Money.ZERO, state.yourShare)
        assertFalse("save should be disabled", state.canSave)
    }

    @Test
    fun saving_a_split_writes_your_share_and_the_shares() = runBlocking {
        val vm = startedVm()
        val rahim = person("Rahim")
        vm.state.awaitState { it.people.isNotEmpty() }

        vm.type("1000")
        vm.splitEvenly(listOf(rahim))
        vm.state.awaitState { it.split.owed.size == 1 }
        vm.saveAndWait()

        val saved = fx.expenses.filteredPage(LedgerFilters.NONE).single()
        assertEquals(Money.ofTaka(500), Money(saved.expense.amountMinor))
        assertEquals("the bill should be reconstructable", 100_000L, saved.billMinor)
    }

    @Test
    fun a_split_does_not_leak_into_the_next_entry() = runBlocking {
        // `reset` rebuilds the state from scratch, and the split fields are
        // defaulted precisely so they cannot survive it — the shape of defect
        // that once sent the next new entry down the `update` branch.
        val vm = startedVm()
        val rahim = person("Rahim")
        vm.state.awaitState { it.people.isNotEmpty() }

        vm.type("1000")
        vm.splitEvenly(listOf(rahim))
        vm.state.awaitState { it.split.owed.size == 1 }
        vm.saveAndWait()

        val next = vm.state.awaitState { it.splitMode == SplitMode.NONE }
        assertEquals(Split.NONE, next.split)
        assertTrue(next.splitWith.isEmpty())
        assertNull(next.payerId)
    }

    @Test
    fun reopening_a_shared_expense_shows_the_bill_not_your_slice() = runBlocking {
        // FR-SHR-02. Only your share is stored, so the field has to be filled
        // from the reconstructed bill or the user sees ৳500 for a ৳1,000
        // dinner and "corrects" it.
        val rahim = person("Rahim")
        val (yours, split) = Split.evenly(Money.ofTaka(1_000), listOf(rahim))
        val id = (
            fx.expenses.insert(
                yours, fx.leafId("Grocery"), fx.today, split = split,
            ) as SaveOutcome.Saved
            ).id

        val vm = startedVm(editingId = id)
        val state = vm.state.awaitState { it.editingId == id && it.split.owed.isNotEmpty() }

        assertEquals(Money.ofTaka(1_000), state.amount)
        assertEquals(Money.ofTaka(500), state.yourShare)
        assertEquals(SplitMode.CUSTOM, state.splitMode)
    }

    @Test
    fun adding_a_person_inline_finds_one_that_already_exists() = runBlocking {
        // FR-SHR-01, and FR-IS-03's shape. Typing a name that exists must reach
        // that person's balance, not open a second one beside it.
        val vm = startedVm()
        val rahim = person("Rahim")

        vm.addPerson("  rahim ")
        val state = vm.state.awaitState { it.people.isNotEmpty() }

        assertEquals(1, state.people.size)
        assertEquals(rahim, state.people.single().id)
    }
}
