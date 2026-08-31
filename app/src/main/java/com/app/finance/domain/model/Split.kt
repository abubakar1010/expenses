package com.app.finance.domain.model

import com.app.finance.core.money.Money
import com.app.finance.domain.usecase.SplitAllocator

/**
 * How an expense is shared, if it is — FR-SHR-02, FR-SHR-03.
 *
 * The two arms are **mutually exclusive**, and that is a database invariant
 * rather than a convention: `trg_payer_excludes_shares` refuses an expense that
 * names a payer while shares exist, because a share means "they owe me" and
 * that is only true when you paid. Modelling it as one value with two arms is
 * what stops the entry sheet offering the impossible combination — two
 * independent switches would hand the user a raw `RAISE(ABORT)`.
 *
 * Pure Kotlin, in `domain/` rather than beside the repository, because the
 * entry sheet builds one and the repository writes it.
 */
sealed interface Split {

    /** Who paid, or null for you. */
    val payerPersonId: Long?

    /** Who owes you, and how much. Empty unless you paid. */
    val owed: List<Owed>

    /** Not shared: you paid, nobody owes you. */
    data object NONE : Split {
        override val payerPersonId: Long? get() = null
        override val owed: List<Owed> get() = emptyList()
    }

    /** You paid the bill; these people owe you their parts. */
    @JvmInline
    value class YouPaid(override val owed: List<Owed>) : Split {
        override val payerPersonId: Long? get() = null
    }

    /**
     * Somebody else paid; you owe them your share.
     *
     * No [owed] rows: if three of you split a bill Rahim paid, the other two
     * owe *Rahim*. That is not your ledger.
     */
    @JvmInline
    value class TheyPaid(val personId: Long) : Split {
        override val payerPersonId: Long? get() = personId
        override val owed: List<Owed> get() = emptyList()
    }

    /** True when this expense involves anybody but you. */
    val isShared: Boolean get() = this != NONE

    /**
     * Whether this split can stand beside [yourShare], the amount the expense
     * will store.
     *
     * The bill is `yourShare + owed`, so there is nothing to cross-check
     * against — what can still be wrong is a share of zero or less, which
     * `CHECK (share_minor > 0)` would refuse and which describes somebody who
     * should not be on the list at all; or the same person listed twice, which
     * `ux_share_expense_person` refuses and which is a mistake rather than a
     * second debt.
     */
    fun validate(yourShare: Money): EntryError? = when {
        owed.any { it.amount.paisa <= 0L } -> EntryError.SPLIT_DOES_NOT_BALANCE
        owed.map { it.personId }.toSet().size != owed.size -> EntryError.SPLIT_DOES_NOT_BALANCE
        else -> null
    }

    /** One person's part of a bill you paid. */
    data class Owed(val personId: Long, val amount: Money)

    /** The reverse of a split, for reopening an expense to edit it. */
    data class Loaded(val split: Split, val bill: Money)

    companion object {
        /**
         * An even split of [bill] between you and [personIds].
         *
         * Goes through [SplitAllocator] rather than dividing here, because the
         * paisa integer division drops is a paisa the bill no longer adds up
         * to — and your share is whatever the others leave, so it absorbs the
         * rounding.
         */
        fun evenly(bill: Money, personIds: List<Long>): Pair<Money, Split> {
            if (personIds.isEmpty()) return bill to NONE
            val parts = SplitAllocator.even(bill, personIds.size + 1)
            val owed = personIds.mapIndexed { i, id -> Owed(id, parts[i + 1]) }
            return SplitAllocator.yourShare(bill, owed.map { it.amount }) to YouPaid(owed)
        }
    }
}
