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
     * Three ways it can be wrong, and they are different mistakes:
     *
     *  - the same person listed twice, which `ux_share_expense_person` refuses
     *    and which is a mistake rather than a second debt
     *  - a share of zero or less, which `CHECK (share_minor > 0)` would refuse
     *    and which describes somebody who should not be on the list at all
     *  - the shares already accounting for the whole bill, leaving [yourShare]
     *    zero or negative
     *
     * The last one used to be unchecked, and the bill is `yourShare + owed`
     * with no stored total, so nothing downstream could catch it: typing
     * ৳600 and ৳500 against a ৳1,000 dinner stored `amount_minor = -10000`
     * and the rollups took a ৳100 *credit* for a meal that was eaten.
     * `CHECK (amount_minor <> 0)` lets a negative through on purpose —
     * FR-EXP-06's refund is one — so this is the only layer that can refuse
     * it. [SplitAllocator.isBalanced] is where the rule already lived,
     * tested and unused.
     */
    fun validate(yourShare: Money): EntryError? {
        if (owed.map { it.personId }.toSet().size != owed.size) {
            return EntryError.SPLIT_DOES_NOT_BALANCE
        }
        if (owed.isEmpty()) return null
        val amounts = owed.map { it.amount }
        val bill = Money(yourShare.paisa + amounts.sumOf { it.paisa })
        return if (SplitAllocator.isBalanced(bill, amounts)) null
        else EntryError.SPLIT_DOES_NOT_BALANCE
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
