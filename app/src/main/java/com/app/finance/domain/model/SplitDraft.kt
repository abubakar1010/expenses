package com.app.finance.domain.model

import com.app.finance.core.money.Money

/**
 * How the bill is divided — FR-SHR-02, FR-SHR-03.
 *
 * One enum rather than independent flags, because [Split]'s two arms are
 * mutually exclusive in the database and a sheet must not offer a state that
 * cannot be stored.
 */
enum class SplitMode { NONE, EVEN, CUSTOM, THEY_PAID }

/**
 * A split while it is still being chosen, and every way it can change.
 *
 * The split sheet's state, lifted out of `QuickAddUiState` and
 * `QuickAddViewModel` so a repeating entry (FR-REC-06) asks the same questions
 * and gets the same answers as a one-off expense. Two copies of these
 * transitions is how the §29 defects would come back one screen over: every
 * one of them — the payer arm that could not be chosen, the membership lost
 * when switching styles, the person added but not selected — lived in exactly
 * this logic.
 *
 * **The mode is stored and the [Split] derived**, never the computed share
 * amounts. An even split depends on the bill, and the bill is still being
 * typed; stored amounts would go stale on the next digit, invisibly.
 *
 * Pure Kotlin, so the transitions are JVM-tested on their own (NFR-MAIN-01).
 */
data class SplitDraft(
    val mode: SplitMode = SplitMode.NONE,
    /**
     * Who the bill is divided between — for [SplitMode.EVEN] **and**
     * [SplitMode.CUSTOM].
     *
     * One list for both, because *who is in the split* and *what each of them
     * owes* are separate questions: with membership living in [customOwed],
     * clearing a hand-typed amount removed the person from the sheet, and
     * switching style lost the selection entirely.
     */
    val members: List<Long> = emptyList(),
    /**
     * Hand-typed amounts, for [SplitMode.CUSTOM]. Sparse: somebody in the split
     * whose amount is still blank has no entry, and [split] leaves them out.
     */
    val customOwed: List<Split.Owed> = emptyList(),
    /**
     * Who paid, for [SplitMode.THEY_PAID]. Null while the arm is chosen but the
     * person is not — *somebody else paid* is asked before *who*.
     */
    val payerId: Long? = null,
) {

    /**
     * The split this draft describes against [bill].
     *
     * Recomputed on every call, which is the point: an even division of a bill
     * that is still being entered cannot be stored without going stale.
     */
    fun split(bill: Money?): Split = when (mode) {
        SplitMode.NONE -> Split.NONE
        SplitMode.EVEN ->
            if (members.isEmpty()) Split.NONE
            else bill?.let { Split.evenly(it, members).second } ?: Split.NONE
        // Driven by `members`, so a person whose amount has not been typed yet
        // is absent rather than present with a zero share that
        // `CHECK (share_minor > 0)` would refuse.
        SplitMode.CUSTOM -> {
            val owed = members
                .mapNotNull { id -> customOwed.firstOrNull { it.personId == id } }
                .filter { it.amount.paisa > 0L }
            if (owed.isEmpty()) Split.NONE else Split.YouPaid(owed)
        }
        SplitMode.THEY_PAID -> payerId?.let { Split.TheyPaid(it) } ?: Split.NONE
    }

    /**
     * What will actually be stored — the bill less what others owe.
     *
     * Works for both arms: somebody else paying leaves no `owed` rows, so the
     * figure typed is already your share.
     */
    fun yourShare(bill: Money?): Money? =
        bill?.let { Money(it.paisa - split(it).owed.sumOf { o -> o.amount.paisa }) }

    /** What [personId] currently owes, blank hand-typed amounts included. */
    fun owedBy(personId: Long, bill: Money?): Money? = when (mode) {
        SplitMode.CUSTOM -> customOwed.firstOrNull { it.personId == personId }?.amount
        else -> split(bill).owed.firstOrNull { it.personId == personId }?.amount
    }

    /** Everybody this draft names, whichever arm it is on. */
    val participants: Set<Long>
        get() = buildSet {
            addAll(members)
            addAll(customOwed.map { it.personId })
            payerId?.let(::add)
        }

    // --- transitions ---------------------------------------------------------

    /**
     * Answers *who paid* — FR-SHR-03's one question, not two toggles.
     *
     * Switching arms discards the other arm's selection, because
     * `trg_payer_excludes_shares` makes them exclusive and a stale list is what
     * put leader dots beside people the split no longer named. Re-choosing the
     * arm already chosen changes nothing.
     */
    fun paidByOther(theyPaid: Boolean): SplitDraft = when {
        (mode == SplitMode.THEY_PAID) == theyPaid -> this
        theyPaid -> SplitDraft(mode = SplitMode.THEY_PAID)
        else -> NONE
    }

    /**
     * Evenly, or an amount typed per person — FR-SHR-02's two halves.
     *
     * Switching to *by amount* seeds each person with the even share they
     * already had, so the style is a starting point rather than a blank form;
     * switching back drops the typed figures, which is what "evenly" means.
     */
    fun evenly(evenly: Boolean, bill: Money?): SplitDraft = when {
        mode == SplitMode.THEY_PAID || members.isEmpty() -> this
        evenly -> copy(mode = SplitMode.EVEN, customOwed = emptyList())
        else -> copy(
            mode = SplitMode.CUSTOM,
            customOwed = customOwed.ifEmpty { split(bill).owed },
        )
    }

    /**
     * Adds or removes one person — the row tap.
     *
     * Membership only. It never changes the style, so a hand-typed split does
     * not silently become an even one because somebody joined it late.
     */
    fun toggle(personId: Long): SplitDraft {
        val next = members.toMutableList()
        val removed = next.remove(personId)
        if (!removed) next += personId
        return copy(
            mode = when {
                next.isEmpty() -> SplitMode.NONE
                mode == SplitMode.CUSTOM -> SplitMode.CUSTOM
                else -> SplitMode.EVEN
            },
            members = next,
            customOwed = if (removed) customOwed.filterNot { it.personId == personId } else customOwed,
            payerId = null,
        )
    }

    /** One person's hand-typed share. Null clears it without unpicking them. */
    fun share(personId: Long, amount: Money?): SplitDraft {
        val rest = customOwed.filterNot { it.personId == personId }
        return copy(
            mode = SplitMode.CUSTOM,
            customOwed = if (amount == null) rest else rest + Split.Owed(personId, amount),
        )
    }

    /**
     * Somebody else paid. Choosing the payer already chosen unpicks them and
     * keeps the arm: every other row is a toggle, and a single-select that
     * cannot be undone is a trap.
     */
    fun paidBy(personId: Long): SplitDraft = SplitDraft(
        mode = SplitMode.THEY_PAID,
        payerId = personId.takeIf { it != payerId },
    )

    /**
     * Somebody just added by name — FR-SHR-01's inline creation, finishing the
     * job it started.
     *
     * Nobody types a name into a split sheet in order not to split with that
     * person, so they are selected: as the payer on that arm, as a member on
     * the other.
     */
    fun including(personId: Long): SplitDraft = when {
        mode == SplitMode.THEY_PAID -> copy(payerId = personId)
        personId in members -> this
        else -> copy(
            mode = if (mode == SplitMode.CUSTOM) SplitMode.CUSTOM else SplitMode.EVEN,
            members = members + personId,
            payerId = null,
        )
    }

    companion object {
        /** Not shared. */
        val NONE = SplitDraft()
    }
}
