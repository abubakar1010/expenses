package com.app.finance.domain.usecase

import com.app.finance.core.money.Money

/**
 * Dividing a bill among people without losing a paisa — FR-SHR-02.
 *
 * [Money.divideBy] rounds toward zero and drops the remainder, which is right
 * for the questions it was written for — a daily average is not owed to anybody
 * — and wrong here. ৳1,000 three ways would give three parts of ৳333.33 and
 * quietly mislay one paisa.
 *
 * That paisa has nowhere to hide. The schema stores no total: a bill *is*
 * `expense.amount_minor + SUM(share_minor)`, so the parts define the whole and
 * an allocation that does not add up is not a rounding difference, it is a
 * different bill. [ExpenseRepository] rejects one with
 * `EntryError.SPLIT_DOES_NOT_BALANCE`; this is what keeps it from ever having
 * to.
 *
 * The method is [LargestRemainder]'s, for the same reason and with the same
 * tie-break: every part takes its floor, and the leftover paisa go to the parts
 * with the largest remainders, ties broken by position so the same bill always
 * splits the same way.
 *
 * Pure Kotlin — no Android, no Room (NFR-MAIN-01), and JVM-tested.
 */
object SplitAllocator {

    /**
     * [total] divided [parts] ways, summing to exactly [total].
     *
     * Handles a negative total — a refund on a shared bill is refunded in the
     * same proportions — by allocating the magnitude and restoring the sign,
     * so the leftover paisa still land on the largest remainders rather than
     * on whichever part integer division happened to truncate.
     *
     * @return one [Money] per part, in order. Empty when [parts] is not
     *   positive, which the caller should have prevented.
     */
    fun even(total: Money, parts: Int): List<Money> {
        if (parts <= 0) return emptyList()

        val negative = total.isNegative
        val magnitude = total.absoluteValue.paisa

        val floor = magnitude / parts
        val leftover = (magnitude % parts).toInt()

        // The first `leftover` parts each take one extra paisa. With equal
        // weights every remainder is equal, so LargestRemainder's positional
        // tie-break reduces to exactly this — written out rather than routed
        // through a percentage helper that would divide by 100.
        return List(parts) { i ->
            val paisa = floor + if (i < leftover) 1L else 0L
            Money(if (negative) -paisa else paisa)
        }
    }

    /**
     * What is left for you once the others' shares are taken off the bill.
     *
     * Your share is deliberately the remainder rather than a part in its own
     * right: it is the only figure the ledger stores, so making it absorb the
     * difference is what guarantees the stored parts reconstruct the bill you
     * typed. Any rounding the caller did lands on you, which is also the fair
     * default — you are the one who chose the split.
     */
    fun yourShare(bill: Money, others: List<Money>): Money =
        Money(bill.paisa - others.sumOf { it.paisa })

    /**
     * Whether a set of hand-typed shares can stand beside [bill].
     *
     * Two ways it fails, and they are different mistakes:
     *
     *  - a share is zero or negative — `CHECK (share_minor > 0)` would refuse
     *    it, and "owes me nothing" is a person who should not be on the list
     *  - the others already account for the whole bill, leaving your share zero
     *    or negative. That is not a shared expense at all: paying entirely on
     *    somebody else's behalf is a loan, which FR-SHR-04's settlement records
     *    without pretending you consumed anything.
     */
    fun isBalanced(bill: Money, others: List<Money>): Boolean {
        if (others.any { it.paisa <= 0L }) return false
        return yourShare(bill, others).paisa > 0L
    }
}
