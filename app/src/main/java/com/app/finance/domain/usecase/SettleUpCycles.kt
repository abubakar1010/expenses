package com.app.finance.domain.usecase

import com.app.finance.core.money.Money

/**
 * One thing that moved a person's balance — FR-SHR-05's three terms, in order.
 *
 * [signed] is the event's contribution to the balance exactly as
 * `SettlementDao.observeBalances` sums it: positive means it left them owing
 * you more, negative means less.
 */
data class HistoryEvent(
    val kind: Kind,
    val id: Long,
    /** Epoch day — `spent_on` or `settled_on`. */
    val day: Long,
    /** Epoch millis. Orders two events on the same day by when they were recorded. */
    val createdAt: Long,
    val signed: Money,
) {
    enum class Kind { EXPENSE, SETTLEMENT }
}

/**
 * Everything settled by one settle-up — FR-SHR-06.
 *
 * Closed by the event that brought the balance back to exactly zero, and named
 * by it: [key] stays the same when later entries are added, so a group the user
 * has expanded stays expanded while they go on using the ledger.
 */
data class SettledCycle(
    val key: String,
    val closedOnDay: Long,
    val expenseIds: Set<Long>,
    val settlementIds: Set<Long>,
) {
    val entryCount: Int get() = expenseIds.size + settlementIds.size
}

/** A person's history split into what is settled and what is still open. */
data class SettleUpHistory(
    /** Newest first — the order the ledger lists them in. */
    val cycles: List<SettledCycle>,
    val openExpenseIds: Set<Long>,
    val openSettlementIds: Set<Long>,
) {
    val settledExpenseIds: Set<Long> get() = cycles.flatMapTo(HashSet()) { it.expenseIds }

    val settledSettlementIds: Set<Long> get() = cycles.flatMapTo(HashSet()) { it.settlementIds }

    companion object {
        val EMPTY = SettleUpHistory(emptyList(), emptySet(), emptySet())
    }
}

/**
 * Which of a person's entries a settle-up paid off — FR-SHR-06.
 *
 * **A settlement is not tied to any expense.** It is money that moved, and the
 * balance is a running sum (03 §8a). So "which dinner did this repay?" has no
 * stored answer, and any rule that picks one — oldest first, largest first —
 * is a convention the user never agreed to, and one that would show a dinner
 * as half settled.
 *
 * The rule here makes no such choice: **everything up to the last moment the
 * balance was exactly zero is settled, and everything after it is open.** At
 * zero nobody owes anybody anything, so every entry before that point is
 * accounted for without deciding which paid for which. A partial repayment
 * settles nothing; the balance says what is left, and the entries it is left
 * on stay where the user can see them.
 *
 * Every earlier return to zero closes an earlier cycle, which is what turns
 * this into a history of settle-ups rather than a single line.
 *
 * Two debts that cancel — Rahim owes you ৳500 for lunch, you owe him ৳500 for
 * the cinema — reach zero with no settlement at all, and are settled too. That
 * is not a special case: it is what square means.
 *
 * Pure Kotlin, JVM-tested (NFR-MAIN-01).
 */
object SettleUpCycles {

    fun of(events: List<HistoryEvent>): SettleUpHistory {
        if (events.isEmpty()) return SettleUpHistory.EMPTY

        // Date first, then when it was recorded: a repayment backdated to
        // Tuesday settles what was owed on Tuesday, not what was added on
        // Wednesday. Kind and id only break ties nothing else can, and make the
        // result the same every time.
        val ordered = events.sortedWith(
            compareBy<HistoryEvent>({ it.day }, { it.createdAt }, { it.kind.ordinal }, { it.id }),
        )

        val cycles = ArrayList<SettledCycle>()
        val expenses = LinkedHashSet<Long>()
        val settlements = LinkedHashSet<Long>()
        var running = 0L

        ordered.forEach { event ->
            when (event.kind) {
                HistoryEvent.Kind.EXPENSE -> expenses += event.id
                HistoryEvent.Kind.SETTLEMENT -> settlements += event.id
            }
            running += event.signed.paisa
            if (running == 0L) {
                cycles += SettledCycle(
                    key = "${event.kind.name.lowercase()}-${event.id}",
                    closedOnDay = event.day,
                    expenseIds = expenses.toSet(),
                    settlementIds = settlements.toSet(),
                )
                expenses.clear()
                settlements.clear()
            }
        }

        return SettleUpHistory(
            cycles = cycles.asReversed().toList(),
            openExpenseIds = expenses.toSet(),
            openSettlementIds = settlements.toSet(),
        )
    }
}
