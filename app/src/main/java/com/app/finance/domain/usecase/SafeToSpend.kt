package com.app.finance.domain.usecase

import com.app.finance.core.money.Money

/**
 * The dashboard's hero figure — FR-AN-01.
 *
 * > "**safe to spend today** = (remaining limits of variable + unpredictable
 * > leaves) ÷ (days remaining in period, inclusive of today). When the
 * > numerator is negative the value MUST render as zero with an over-budget
 * > indicator."
 *
 * 05 §5.4 explains why this and not a balance: *"Most finance apps put total
 * balance or total spent at the top. Neither answers a question the user has at
 * a shop counter. Safe-to-spend does."* PRD §6.4 says the same in one line —
 * it "converts a monthly abstraction into a decision at the shop counter".
 *
 * @property remaining the **signed** numerator, which is what makes the
 *   requirement's negative case reachable at all.
 * @property perDay `null` when the period has no days left to spend in.
 */
data class SafeToSpend(
    val remaining: Money,
    val perDay: Money?,
    val daysRemaining: Int,
) {
    /** FR-AN-01's over-budget indicator. */
    val isOver: Boolean get() = remaining.isNegative

    companion object {

        /**
         * @param daysRemaining from `Period.daysRemainingInclusive`, which
         *   counts today. On the last day of the month the divisor is 1, not 0.
         *
         * Three exclusions and one insistence, each load-bearing:
         *
         * - **Fixed leaves.** `Nature.isDiscretionary` is the predicate. Rent is
         *   not a decision the user makes at a shop counter, and including its
         *   unspent limit would inflate today's figure with money that is
         *   already committed.
         * - **Unbudgeted leaves.** No limit means no remainder to divide.
         * - **Archived leaves.** FR-CAT-08 keeps them on the screen while they
         *   carry spend, and that same requirement is why they must not be
         *   counted here: an archived category is out of the entry picker, so
         *   whatever is left of its limit is money that cannot be spent.
         *   Archive Grocery on the 14th with ৳16,000 unspent and this figure
         *   would otherwise still be offering it.
         * - Nothing else. In particular the sum is **signed**: an overspent
         *   Grocery eats into what is left in Transport, because that is what
         *   actually happened to the money. FR-AN-01's negative case only
         *   arises at all if the arithmetic works this way, and clamping each
         *   leaf at zero first would make the requirement's own instruction
         *   unreachable.
         */
        fun of(groups: List<BudgetGroup>, daysRemaining: Int): SafeToSpend {
            val remaining = groups
                .asSequence()
                .flatMap { it.leaves.asSequence() }
                .filter { it.nature.isDiscretionary && it.hasLimit && !it.isArchived }
                .fold(Money.ZERO) { acc, leaf ->
                    acc + (leaf.status.limit - leaf.status.spent)
                }

            return SafeToSpend(
                remaining = remaining,
                perDay = when {
                    // A period already finished has no "today" to spend in, and
                    // a per-day figure over zero days is not a large number, it
                    // is not a number. The screen shows what is left instead.
                    daysRemaining <= 0 -> null
                    // FR-AN-01: "when the numerator is negative the value MUST
                    // render as zero with an over-budget indicator".
                    remaining.isNegative -> Money.ZERO
                    else -> remaining.divideBy(daysRemaining)
                },
                daysRemaining = daysRemaining,
            )
        }
    }
}
