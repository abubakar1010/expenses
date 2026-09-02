package com.app.finance.ui.feature.entry

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.finance.core.money.Money
import com.app.finance.data.repo.AppMetaRepository
import com.app.finance.data.repo.CategoryRepository
import com.app.finance.data.db.entity.PersonEntity
import com.app.finance.data.repo.ExpenseRepository
import com.app.finance.data.repo.PersonRepository
import com.app.finance.domain.model.CategoryNode
import com.app.finance.domain.model.EntryError
import com.app.finance.domain.model.PaymentMethod
import com.app.finance.domain.model.SaveOutcome
import com.app.finance.domain.model.Split
import com.app.finance.ui.common.KeypadKey
import com.app.finance.ui.common.editableText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate

/** Which of the sheet's secondary pickers is open, if any. */
enum class EntrySheet { NONE, CATEGORY, METHOD, DATE, NOTE, SPLIT }

/**
 * How the bill is divided — FR-SHR-02, FR-SHR-03.
 *
 * One enum rather than independent flags, because [Split]'s two arms are
 * mutually exclusive in the database and the sheet must not offer a state that
 * cannot be stored.
 */
enum class SplitMode { NONE, EVEN, CUSTOM, THEY_PAID }

data class QuickAddUiState(
    /**
     * Raw keypad input, not yet money — "12", "12.", "12.5" are all valid
     * intermediate states that [Money.parseOrNull] refuses.
     */
    val input: String = "",
    val negative: Boolean = false,
    /** The full two-level tree, for the `More…` picker. */
    val tree: List<CategoryNode> = emptyList(),
    /** The six most-recently-used leaves (05 §5.6). */
    val chips: List<CategoryNode> = emptyList(),
    val allLeaves: List<CategoryNode> = emptyList(),
    val selectedCategoryId: Long? = null,
    val date: LocalDate,
    val today: LocalDate,
    val method: PaymentMethod = PaymentMethod.DEFAULT,
    val note: String? = null,
    val error: EntryError? = null,
    val saving: Boolean = false,
    val openSheet: EntrySheet = EntrySheet.NONE,
    /**
     * True once [QuickAddViewModel.start] has finished seeding the form.
     *
     * The category tree and the chip row arrive from their own flow and can
     * settle first, so without this there is a window where the sheet shows a
     * chip selection but still the *default* payment method rather than the
     * last-used one.
     */
    val seeded: Boolean = false,
    /** Null for a new entry; the row being edited otherwise (FR-EXP-07). */
    val editingId: Long? = null,
    /**
     * How this expense is shared — FR-SHR-02, FR-SHR-03.
     *
     * The **mode** is stored and the [split] derived from it, rather than
     * storing computed share amounts. An even split depends on the bill, and
     * the bill is still being typed: storing the amounts would leave them stale
     * the moment another digit landed, and the staleness would be invisible
     * until the ledger disagreed with the receipt.
     *
     * All four fields are defaulted, which is what keeps them out of
     * [QuickAddViewModel.reset] — it rebuilds this state from scratch
     * preserving only the tree, the chips, the method and the people, so a
     * defaulted field cannot leak into the next entry the way `editingId` once
     * did. Those four are the reference data the sheet is *drawn from*; the
     * defaulted ones are the entry itself.
     */
    val splitMode: SplitMode = SplitMode.NONE,
    /**
     * Who it is divided between — for [SplitMode.EVEN] **and**
     * [SplitMode.CUSTOM].
     *
     * One list for both, because *who is in the split* and *what each of them
     * owes* are separate questions and conflating them was a defect: with the
     * membership living in [customOwed], clearing a hand-typed amount removed
     * the person from the sheet, and switching between the two styles lost the
     * selection entirely.
     */
    val splitWith: List<Long> = emptyList(),
    /**
     * Hand-typed amounts, for [SplitMode.CUSTOM] — FR-SHR-02's second half.
     *
     * A sparse companion to [splitWith], not a replacement for it: a person
     * who is in the split but whose amount is still blank has no entry here,
     * and [split] leaves them out until they do.
     */
    val customOwed: List<Split.Owed> = emptyList(),
    /**
     * Who paid, for [SplitMode.THEY_PAID].
     *
     * Null while the mode is chosen but the person is not. That is a real
     * state and it has to be representable: *somebody else paid* is the first
     * half of the question and *who* is the second, and the sheet asks them in
     * that order.
     */
    val payerId: Long? = null,
    /**
     * Everybody on file, archived included — see [splitCandidates].
     *
     * Not `observeActive()`, which is what this used to bind to. An archived
     * person can still be the payer of an expense being edited, and resolving
     * [payer] against an active-only list turned that row's `Rahim paid` back
     * into an unqualified `Split` while `state.split` still said otherwise.
     */
    val people: List<PersonEntity> = emptyList(),
) {
    val isEditing: Boolean get() = editingId != null

    /**
     * What the amount field holds.
     *
     * **The bill, not your share.** You type what left your wallet, because
     * that is what the receipt says and doing the division in your head is the
     * work this feature exists to remove. What the ledger stores is
     * [yourShare].
     */
    val amount: Money?
        get() = Money.parseOrNull(input)?.let { if (negative) -it.absoluteValue else it }

    /**
     * The split, derived from [splitMode] and the bill currently typed.
     *
     * Recomputed on every keystroke, which is the point — an even division of a
     * bill that is still being entered cannot be stored without going stale.
     */
    val split: Split
        get() = when (splitMode) {
            SplitMode.NONE -> Split.NONE
            SplitMode.EVEN ->
                if (splitWith.isEmpty()) Split.NONE
                else amount?.let { Split.evenly(it, splitWith).second } ?: Split.NONE
            // Driven by `splitWith`, so a person whose amount has not been
            // typed yet is simply absent from the split rather than present
            // with a zero share that `CHECK (share_minor > 0)` would refuse.
            SplitMode.CUSTOM -> {
                val owed = splitWith
                    .mapNotNull { id -> customOwed.firstOrNull { it.personId == id } }
                    .filter { it.amount.paisa > 0L }
                if (owed.isEmpty()) Split.NONE else Split.YouPaid(owed)
            }
            SplitMode.THEY_PAID ->
                payerId?.let { Split.TheyPaid(it) } ?: Split.NONE
        }

    /**
     * Who the sheet may offer — FR-CAT-08's rule, applied to people.
     *
     * Active people, plus anybody the split already names. The exception is
     * what keeps editing honest: archiving somebody does not unpick the
     * expenses they are in, so reopening one must still show them rather than
     * quietly dropping a share the save would then rewrite.
     */
    val splitCandidates: List<PersonEntity>
        get() = people.filter { !it.isArchived || it.id in participants }

    /** Everybody this split names, whichever arm it is on. */
    val participants: Set<Long>
        get() = buildSet {
            addAll(splitWith)
            addAll(customOwed.map { it.personId })
            payerId?.let(::add)
        }

    /** What each person in the split currently owes, blank ones included. */
    fun owedBy(personId: Long): Money? = when (splitMode) {
        SplitMode.CUSTOM -> customOwed.firstOrNull { it.personId == personId }?.amount
        else -> split.owed.firstOrNull { it.personId == personId }?.amount
    }

    /**
     * What the expense will actually store — the bill less what others owe.
     *
     * Works for both arms: somebody else paying leaves no `owed` rows, so the
     * figure you typed is already your share.
     */
    val yourShare: Money?
        get() = amount?.let { bill -> Money(bill.paisa - split.owed.sumOf { it.amount.paisa }) }

    val selectedCategory: CategoryNode?
        get() = allLeaves.firstOrNull { it.id == selectedCategoryId }

    /** The person who paid, when it was not you. */
    val payer: PersonEntity?
        get() = split.payerPersonId?.let { id -> people.firstOrNull { it.id == id } }

    /**
     * The save button is enabled only when the write would actually succeed.
     *
     * Tested on [yourShare] rather than [amount]: a bill entirely accounted for
     * by other people leaves you nothing, and `CHECK (amount_minor <> 0)`
     * refuses that row — correctly, because paying wholly on somebody's behalf
     * is a loan rather than something you consumed.
     *
     * And on [Split.validate] as well, which is the same rule read from the
     * other side. `yourShare != 0` alone let an *over*-allocated split through
     * — ৳600 and ৳500 typed against a ৳1,000 bill leaves −৳100, which is
     * non-zero and which `CHECK (amount_minor <> 0)` accepts as a refund. The
     * button now goes dead where the write would be refused, which is what
     * this property claims to mean.
     */
    val canSave: Boolean
        get() = !saving && selectedCategoryId != null &&
            yourShare?.let { !it.isZero && split.validate(it) == null } == true
}

/**
 * 05-ui-ux-guide.md §5.6 — three taps, under five seconds.
 *
 * The speed comes from defaults rather than from a faster form: the date is
 * today, the payment method is whatever was used last, and the six chips are
 * the most recently used categories. "The user should be able to log a typical
 * expense without changing a single default."
 *
 * The same sheet edits an existing expense (FR-EXP-07). One component means one
 * set of validation rules, one keypad, and one place to keep accessible.
 *
 * State is mirrored into [SavedStateHandle] so a half-typed expense survives
 * process death — NFR-REL-01 is about committed writes, but FR-APP-03 asks for
 * the in-progress screen too, and losing a typed amount to a background kill is
 * exactly the friction this product exists to remove.
 */
class QuickAddViewModel(
    private val expenses: ExpenseRepository,
    private val categories: CategoryRepository,
    private val meta: AppMetaRepository,
    private val people: PersonRepository,
    private val clock: Clock,
    private val saved: SavedStateHandle = SavedStateHandle(),
    /**
     * Injected so tests can run the whole flow on a single deterministic
     * dispatcher. With a hardcoded `Dispatchers.IO`, cancelling a ViewModel in
     * teardown races the in-flight query it is still running, and the failure
     * lands in whichever test happens to run next.
     */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(
        QuickAddUiState(date = LocalDate.now(clock), today = LocalDate.now(clock)),
    )
    val state: StateFlow<QuickAddUiState> = _state.asStateFlow()

    /** Guards against re-seeding when the sheet recomposes. */
    private var started = false

    init {
        // The tree and the most-recently-used list are both observed, not read
        // once: saving an expense rewrites `app_meta`, and the chip row should
        // reflect that the next time the sheet opens without anyone having to
        // remember to refresh it.
        viewModelScope.launch {
            combine(
                categories.observeTree(),
                meta.observeRecentCategoryIds(),
            ) { tree, recent -> tree to recent }
                .collect { (tree, recent) ->
                    val leaves = tree.flatMap { it.activeChildren }
                    val byId = leaves.associateBy { it.id }
                    // Recently used first, then whatever else fills the six
                    // slots, so the row is never sparse on a fresh install.
                    val chips = (recent.mapNotNull(byId::get) + leaves)
                        .distinctBy { it.id }
                        .take(CHIP_COUNT)

                    _state.update {
                        it.copy(
                            tree = tree,
                            chips = chips,
                            allLeaves = leaves,
                            selectedCategoryId = it.selectedCategoryId?.takeIf(byId::containsKey)
                                ?: chips.firstOrNull()?.id,
                        )
                    }
                }
        }
        // Separate collector rather than a third `combine` arm: the people list
        // has nothing to do with which chips are shown, and folding it in would
        // re-derive the chip row every time somebody was renamed.
        //
        // `observeAll`, and the archived are filtered out by
        // `QuickAddUiState.splitCandidates` instead — which keeps the ones this
        // expense already names. See that property.
        viewModelScope.launch {
            people.observeAll().collect { rows -> _state.update { it.copy(people = rows) } }
        }
    }

    /**
     * Seeds the form. Call on every open.
     *
     * This is also what makes a reused ViewModel safe: the sheet's owner is the
     * Activity, so the instance outlives a dismissal, and without an explicit
     * seed a reopened sheet would still show the last amount typed.
     */
    fun start(expenseId: Long?) {
        if (started) return
        started = true

        viewModelScope.launch {
            val today = LocalDate.now(clock)
            val restored = saved.get<String>(KEY_INPUT)
            val editing = withContext(io) { expenseId?.let { expenses.byId(it) } }
            // The bill, reconstructed from the stored parts — FR-SHR-02. The
            // amount field holds what was typed, and for a shared expense that
            // is not the figure in `amount_minor`.
            val loaded = withContext(io) { expenseId?.let { expenses.splitOf(it) } }
            val lastMethod = withContext(io) { meta.lastPaymentMethod() }
            val lastCategory = withContext(io) { meta.lastCategoryId() }

            _state.update { current ->
                when {
                    // Editing wins over any restored draft: the user asked for
                    // this specific row.
                    editing != null -> current.copy(
                        // The bill when there is one, so reopening a ৳1,000
                        // dinner you paid shows ৳1,000 rather than your ৳250
                        // slice of it.
                        input = (loaded?.bill ?: Money(editing.amountMinor))
                            .absoluteValue.editableText(),
                        negative = editing.amountMinor < 0,
                        selectedCategoryId = editing.categoryId,
                        date = LocalDate.ofEpochDay(editing.spentOn),
                        today = today,
                        method = PaymentMethod.fromCode(editing.paymentMethod),
                        note = editing.note,
                        editingId = editing.id,
                        // Restored as typed amounts rather than as an even
                        // split, because an even division is only recoverable
                        // by guessing: three equal shares might have been
                        // divided evenly or typed by hand, and re-dividing
                        // would silently overwrite the second case.
                        splitMode = when (loaded?.split) {
                            is Split.TheyPaid -> SplitMode.THEY_PAID
                            is Split.YouPaid -> SplitMode.CUSTOM
                            else -> SplitMode.NONE
                        },
                        // Both, not just the amounts. `splitWith` is what the
                        // sheet reads to know who is *in* the split, so
                        // seeding only `customOwed` reopened a shared dinner
                        // with nobody marked and the first tap on a name
                        // rebuilt the split from that one person.
                        splitWith = (loaded?.split as? Split.YouPaid)
                            ?.owed.orEmpty().map { it.personId },
                        customOwed = (loaded?.split as? Split.YouPaid)?.owed.orEmpty(),
                        payerId = (loaded?.split as? Split.TheyPaid)?.personId,
                        seeded = true,
                    )

                    restored != null -> current.copy(
                        input = restored,
                        negative = saved.get<Boolean>(KEY_NEGATIVE) ?: false,
                        selectedCategoryId = saved.get<Long>(KEY_CATEGORY)
                            ?: current.selectedCategoryId,
                        date = saved.get<Long>(KEY_DATE)?.let(LocalDate::ofEpochDay) ?: today,
                        today = today,
                        method = saved.get<Int>(KEY_METHOD)
                            ?.let(PaymentMethod::fromCode) ?: lastMethod,
                        note = saved.get<String>(KEY_NOTE),
                        // The split restores with the rest of the draft, and it
                        // has to: the amount field holds the **bill**, so a
                        // background kill that kept ৳1,000 and lost the three
                        // people it was divided between would file the whole
                        // dinner as your own — a wrong figure the user has no
                        // way to notice, because the number they typed is still
                        // sitting there looking right.
                        splitMode = saved.get<Int>(KEY_SPLIT_MODE)
                            ?.let { SplitMode.entries.getOrNull(it) } ?: SplitMode.NONE,
                        splitWith = saved.get<LongArray>(KEY_SPLIT_WITH)?.toList().orEmpty(),
                        customOwed = restoredOwed(),
                        payerId = saved.get<Long>(KEY_PAYER),
                        seeded = true,
                    )

                    else -> current.copy(
                        // The leaf list arrives from a Flow that may not have
                        // emitted yet. Validating against an empty list here
                        // would silently discard the last-used category on a
                        // cold open and fall back to the first chip — so when
                        // the tree is not loaded the stored id is trusted, and
                        // the collector above drops it if the category turns
                        // out to be archived or gone.
                        selectedCategoryId = lastCategory
                            ?.takeIf { id ->
                                current.allLeaves.isEmpty() ||
                                    current.allLeaves.any { it.id == id }
                            }
                            ?: current.selectedCategoryId,
                        date = today,
                        today = today,
                        method = lastMethod,
                        seeded = true,
                    )
                }
            }
        }
    }

    fun onKey(key: KeypadKey) {
        _state.update { s ->
            when (key) {
                is KeypadKey.Digit -> s.copy(input = s.input.appendDigit(key.value), error = null)
                KeypadKey.DoubleZero ->
                    s.copy(input = s.input.appendDigit('0').appendDigit('0'), error = null)
                KeypadKey.Decimal ->
                    if (s.input.contains('.') || s.input.isEmpty()) s
                    else s.copy(input = s.input + '.', error = null)
                KeypadKey.Backspace -> s.copy(input = s.input.dropLast(1), error = null)
                // A refund is the same entry with the sign flipped, not a
                // different kind of record (FR-EXP-06).
                KeypadKey.Negate -> s.copy(negative = !s.negative, error = null)
            }
        }
        persist()
    }

    fun selectCategory(id: Long) {
        _state.update { it.copy(selectedCategoryId = id, error = null, openSheet = EntrySheet.NONE) }
        persist()
    }

    fun setMethod(method: PaymentMethod) {
        _state.update { it.copy(method = method, openSheet = EntrySheet.NONE) }
        persist()
    }

    /**
     * FR-EXP-02. Future dates are refused: a future-dated row posts straight
     * into the period rollup, so it would inflate this month's spend and deflate
     * safe-to-spend with money that has not left the user's hand. The schema
     * already has the right mechanism for money that has not happened yet —
     * `status = pending` — and that arrives with recurring rules at P1.
     */
    fun setDate(date: LocalDate) {
        if (date.isAfter(_state.value.today)) {
            _state.update { it.copy(error = EntryError.FUTURE_DATE, openSheet = EntrySheet.NONE) }
            return
        }
        _state.update { it.copy(date = date, error = null, openSheet = EntrySheet.NONE) }
        persist()
    }

    fun setNote(note: String?) {
        _state.update { it.copy(note = note?.trim()?.ifBlank { null }) }
        persist()
    }

    fun openSheet(sheet: EntrySheet) = _state.update { it.copy(openSheet = sheet) }

    fun dismissSheet() = _state.update { it.copy(openSheet = EntrySheet.NONE) }

    // --- splitting (FR-SHR-02, FR-SHR-03) ------------------------------------

    /**
     * Answers *who paid* — FR-SHR-03's one question, not two toggles.
     *
     * The sheet used to hold this in a `remember(state.splitMode)` of its own,
     * which made it unanswerable: choosing *somebody else paid* cleared the
     * split, clearing the split moved `splitMode` back to `NONE`, and the
     * changed key rebuilt the local flag as `false` before a finger left the
     * screen. The answer belongs in the state it changes.
     *
     * Switching arms discards the other arm's selection, because
     * `trg_payer_excludes_shares` makes them mutually exclusive and a stale
     * list is what put leader dots beside people the split no longer named.
     * Re-tapping the arm already chosen is a no-op rather than a reset.
     */
    fun setPaidByOther(theyPaid: Boolean) = updateAndPersist {
        val already = it.splitMode == SplitMode.THEY_PAID
        if (already == theyPaid) it
        else if (theyPaid) it.copy(
            splitMode = SplitMode.THEY_PAID,
            payerId = null,
            splitWith = emptyList(),
            customOwed = emptyList(),
            error = null,
        )
        else it.copy(
            splitMode = SplitMode.NONE,
            payerId = null,
            splitWith = emptyList(),
            customOwed = emptyList(),
            error = null,
        )
    }

    /**
     * Evenly, or an amount typed per person — FR-SHR-02's two halves.
     *
     * Switching to *by amount* seeds each person with the even share they
     * already had, so the style is a starting point to adjust rather than a
     * blank form; switching back drops the typed figures, which is what
     * "evenly" means.
     */
    fun setSplitEvenly(evenly: Boolean) = updateAndPersist {
        if (it.splitMode == SplitMode.THEY_PAID || it.splitWith.isEmpty()) it
        else if (evenly) it.copy(splitMode = SplitMode.EVEN, customOwed = emptyList(), error = null)
        else it.copy(
            splitMode = SplitMode.CUSTOM,
            customOwed = it.customOwed.ifEmpty { it.split.owed },
            error = null,
        )
    }

    /**
     * Adds or removes one person from the split — the sheet's row tap.
     *
     * Membership only. It never changes the style, so a hand-typed split does
     * not silently become an even one because somebody was added to it late.
     */
    fun togglePerson(personId: Long) = updateAndPersist {
        val next = it.splitWith.toMutableList()
        val removed = next.remove(personId)
        if (!removed) next += personId
        it.copy(
            splitMode = when {
                next.isEmpty() -> SplitMode.NONE
                it.splitMode == SplitMode.CUSTOM -> SplitMode.CUSTOM
                else -> SplitMode.EVEN
            },
            splitWith = next,
            customOwed = if (removed) it.customOwed.filterNot { o -> o.personId == personId }
            else it.customOwed,
            payerId = null,
            error = null,
        )
    }

    /** One person's hand-typed share. Null clears it without unpicking them. */
    fun setShare(personId: Long, amount: Money?) = updateAndPersist {
        val rest = it.customOwed.filterNot { o -> o.personId == personId }
        it.copy(
            splitMode = SplitMode.CUSTOM,
            customOwed = if (amount == null) rest else rest + Split.Owed(personId, amount),
            error = null,
        )
    }

    /*
     * There is deliberately no `splitEvenly(List<Long>)` or
     * `splitByAmount(List<Owed>)`.
     *
     * Both existed, and after the sheet was rewritten around [togglePerson],
     * [setSplitEvenly] and [setShare] they were second implementations of the
     * same three transitions, reachable only from tests. §20 removed
     * `pageAfter` for exactly that, and a coarse intent nothing calls is worse
     * than merely unused here: it was `splitByAmount` that could put a person
     * in `customOwed` without putting them in `splitWith`, which is the state
     * the sheet cannot draw.
     *
     * The tests drive the three the sheet drives, which is also what makes
     * them tests of the feature rather than of the ViewModel's surface.
     */

    /**
     * Somebody else paid.
     *
     * A mode rather than a flag alongside the shares, because the two cannot
     * coexist: `trg_payer_excludes_shares` refuses the row, so offering both
     * would be offering a state the database has no way to hold.
     *
     * Tapping the payer again unpicks them, keeping the arm. Every other row
     * on this sheet is a toggle, and a single-select that cannot be undone is
     * a trap on a sheet whose only other exit is losing the whole entry.
     */
    fun paidBy(personId: Long) = updateAndPersist {
        it.copy(
            splitMode = SplitMode.THEY_PAID,
            payerId = personId.takeIf { id -> id != it.payerId },
            splitWith = emptyList(),
            customOwed = emptyList(),
            error = null,
        )
    }

    /** Back to an ordinary expense — every arm's selection dropped with it. */
    fun clearSplit() = updateAndPersist {
        it.copy(
            splitMode = SplitMode.NONE,
            splitWith = emptyList(),
            customOwed = emptyList(),
            payerId = null,
            error = null,
        )
    }

    /**
     * Adds somebody without leaving the sheet — FR-SHR-01, FR-IS-03's shape.
     *
     * Idempotent on the name key, so typing a name that already exists finds
     * that person rather than opening a second balance beside them.
     *
     * **And selects them**, which is what makes the control finish the job it
     * started. Without it, adding a name that was already on file wrote no row,
     * so the people flow never re-emitted and the sheet did not move — the user
     * typed a name, pressed Add, and watched nothing happen. Selecting is also
     * simply what was meant: nobody types a name into a split sheet in order
     * not to split with that person.
     */
    fun addPerson(name: String) {
        viewModelScope.launch {
            when (val outcome = withContext(io) { people.findOrCreate(name) }) {
                is SaveOutcome.Saved -> _state.update { s ->
                    if (s.splitMode == SplitMode.THEY_PAID) {
                        s.copy(payerId = outcome.id, error = null)
                    } else if (outcome.id in s.splitWith) {
                        s.copy(error = null)
                    } else {
                        s.copy(
                            splitMode = if (s.splitMode == SplitMode.CUSTOM) SplitMode.CUSTOM
                            else SplitMode.EVEN,
                            splitWith = s.splitWith + outcome.id,
                            payerId = null,
                            error = null,
                        )
                    }
                }
                is SaveOutcome.Rejected -> _state.update { it.copy(error = outcome.error) }
            }
        }
    }

    /** The write path — 04 §5.1 and §8. Everything runs on IO. */
    fun save(onSaved: () -> Unit) {
        val snapshot = _state.value
        // The bill is what was typed; this is what gets stored. For an
        // unshared expense the two are the same figure.
        val amount = snapshot.yourShare
        val categoryId = snapshot.selectedCategoryId

        if (amount == null || amount.isZero) {
            _state.update { it.copy(error = EntryError.ZERO_AMOUNT) }
            return
        }
        if (categoryId == null) {
            _state.update { it.copy(error = EntryError.CATEGORY_NOT_FOUND) }
            return
        }

        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val outcome = withContext(io) {
                val editingId = snapshot.editingId
                if (editingId == null) {
                    expenses.insert(
                        amount = amount,
                        categoryId = categoryId,
                        spentOn = snapshot.date,
                        method = snapshot.method,
                        note = snapshot.note,
                        split = snapshot.split,
                    )
                } else {
                    expenses.update(
                        id = editingId,
                        amount = amount,
                        categoryId = categoryId,
                        spentOn = snapshot.date,
                        method = snapshot.method,
                        note = snapshot.note,
                        split = snapshot.split,
                    )
                }
            }
            when (outcome) {
                is SaveOutcome.Saved -> {
                    // `reset`, not `clearDraft`. The two are not
                    // interchangeable and the difference was a defect.
                    //
                    // `clearDraft` only empties the `SavedStateHandle`; it
                    // leaves `_state` and `started` exactly as they were. But
                    // this ViewModel belongs to the **Activity** (see `start`),
                    // so the instance survives the sheet closing, and the sheet
                    // is closed here by the caller setting its state — which
                    // does not go through `onDismissRequest`, the only other
                    // place `reset` was called from.
                    //
                    // So after a save the instance kept `saving = true` (the
                    // Save button was dead on the next open), kept `started`
                    // (so `start` returned early and never re-seeded), and kept
                    // `editingId` — which sends the *next* entry down the
                    // `update` branch above. Adding an expense after editing one
                    // silently rewrote the edited row.
                    reset()
                    onSaved()
                }
                is SaveOutcome.Rejected ->
                    _state.update { it.copy(saving = false, error = outcome.error) }
            }
        }
    }

    /** Edit mode only. Returns the removed row so the caller can offer Undo. */
    fun delete(onDeleted: (Long) -> Unit) {
        val id = _state.value.editingId ?: return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val removed = runCatching { withContext(io) { expenses.delete(id) } }
            if (removed.isFailure) {
                // Leaving `saving` set would disable the sheet for good, on an
                // instance that outlives this screen.
                _state.update { it.copy(saving = false, error = EntryError.CONSTRAINT_VIOLATION) }
                return@launch
            }
            reset()
            onDeleted(id)
        }
    }

    /**
     * Returns the sheet to the state a fresh open expects.
     *
     * Called on every way out — dismissed, saved, or deleted. Anything that
     * finishes with the sheet must come through here; see the note in `save`
     * for what happens when one path does not.
     *
     * **`people` is carried across, exactly as the tree and the chips are, and
     * for the same reason.** It arrives from a Room flow, and a Room flow
     * re-emits when the *table* changes — nothing about saving an expense
     * changes `person`. So dropping the list here emptied it until somebody
     * was added or renamed, and this ViewModel belongs to the Activity, so the
     * emptying survived the sheet closing. The user saw the split sheet's
     * "Nobody to split with yet" over a table with six people in it, on every
     * open after the first save; typing a name that was already there fixed
     * nothing, because `findOrCreate` found it, wrote no row, and the flow had
     * no reason to emit.
     */
    fun reset() {
        started = false
        clearDraft()
        val today = LocalDate.now(clock)
        _state.update {
            QuickAddUiState(
                tree = it.tree,
                chips = it.chips,
                allLeaves = it.allLeaves,
                selectedCategoryId = it.chips.firstOrNull()?.id,
                date = today,
                today = today,
                method = it.method,
                people = it.people,
            )
        }
    }

    // ------------------------------------------------------------- internals

    /**
     * Every split intent goes through this rather than `_state.update`.
     *
     * Not a stylistic wrapper: the split was the one part of the draft nothing
     * persisted, because each of these functions was an expression body and
     * there was no line to hang a `persist()` call on. One helper is harder to
     * forget than eight call sites.
     */
    private fun updateAndPersist(block: (QuickAddUiState) -> QuickAddUiState) {
        _state.update(block)
        persist()
    }

    private fun persist() {
        val s = _state.value
        saved[KEY_INPUT] = s.input
        saved[KEY_NEGATIVE] = s.negative
        saved[KEY_CATEGORY] = s.selectedCategoryId
        saved[KEY_DATE] = s.date.toEpochDay()
        saved[KEY_METHOD] = s.method.code
        saved[KEY_NOTE] = s.note
        saved[KEY_SPLIT_MODE] = s.splitMode.ordinal
        saved[KEY_SPLIT_WITH] = s.splitWith.toLongArray()
        // Two parallel arrays rather than a serialised list: `SavedStateHandle`
        // is a `Bundle` underneath, and paisa are already `Long`. `Money` is an
        // inline class over exactly this, so nothing is lost in the round trip.
        saved[KEY_OWED_PEOPLE] = s.customOwed.map { it.personId }.toLongArray()
        saved[KEY_OWED_PAISA] = s.customOwed.map { it.amount.paisa }.toLongArray()
        saved[KEY_PAYER] = s.payerId
    }

    /** The hand-typed shares, back out of the two arrays [persist] wrote. */
    private fun restoredOwed(): List<Split.Owed> {
        val ids = saved.get<LongArray>(KEY_OWED_PEOPLE) ?: return emptyList()
        val paisa = saved.get<LongArray>(KEY_OWED_PAISA) ?: return emptyList()
        // Guards a truncated bundle: a pair that does not line up is not a
        // split anybody typed, and half of one is worse than none.
        if (ids.size != paisa.size) return emptyList()
        return ids.mapIndexed { i, id -> Split.Owed(id, Money(paisa[i])) }
    }

    private fun clearDraft() {
        listOf(
            KEY_INPUT, KEY_NEGATIVE, KEY_CATEGORY, KEY_DATE, KEY_METHOD, KEY_NOTE,
            KEY_SPLIT_MODE, KEY_SPLIT_WITH, KEY_OWED_PEOPLE, KEY_OWED_PAISA, KEY_PAYER,
        ).forEach { saved.remove<Any>(it) }
    }

    private fun String.appendDigit(c: Char): String {
        // Two decimal places is all paisa has; further digits are ignored
        // rather than accepted and then silently truncated at parse time.
        val decimals = substringAfter('.', "")
        if (contains('.') && decimals.length >= 2) return this
        if (length >= MAX_DIGITS) return this
        if (this == "0") return c.toString()
        return this + c
    }

    private companion object {
        const val CHIP_COUNT = 6

        /**
         * Ten digits caps an entry at ৳99,999,999.99, which is comfortably
         * inside `Long` paisa and past any plausible household expense. The SRS
         * states no maximum; this exists so a stuck key cannot overflow.
         */
        const val MAX_DIGITS = 10

        const val KEY_INPUT = "qa_input"
        const val KEY_NEGATIVE = "qa_negative"
        const val KEY_CATEGORY = "qa_category"
        const val KEY_DATE = "qa_date"
        const val KEY_METHOD = "qa_method"
        const val KEY_NOTE = "qa_note"
        const val KEY_SPLIT_MODE = "qa_split_mode"
        const val KEY_SPLIT_WITH = "qa_split_with"
        const val KEY_OWED_PEOPLE = "qa_owed_people"
        const val KEY_OWED_PAISA = "qa_owed_paisa"
        const val KEY_PAYER = "qa_payer"
    }
}

