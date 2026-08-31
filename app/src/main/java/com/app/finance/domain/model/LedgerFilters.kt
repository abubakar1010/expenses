package com.app.finance.domain.model

import com.app.finance.core.money.Money
import java.time.LocalDate

/**
 * What the ledger is currently showing — FR-EXP-08.
 *
 * > "The ledger MUST be filterable by date range, root, leaf, and payment
 * > method, and searchable by note substring and by exact amount."
 *
 * Note the asymmetry in the search half: **substring** for notes, **exact
 * match** for amounts. Typing `250` should find a note reading "250g rice" and
 * an expense of exactly ৳250, but not one of ৳1,250 — a ledger search that
 * matched amounts loosely would return noise precisely when the user is trying
 * to find one specific transaction.
 *
 * Pure Kotlin: `java.time` is JVM, not Android, so this stays inside the
 * `domain/` purity rule NFR-MAIN-01 sets and the build enforces.
 */
data class LedgerFilters(
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    /** A root selects every leaf beneath it; resolution happens in the repo. */
    val rootId: Long? = null,
    val leafId: Long? = null,
    val method: PaymentMethod? = null,
    /**
     * Expenses this person shares in or paid for — FR-SHR-06.
     *
     * Matches from both sides, because "things I did with Rahim" means the
     * dinner he owes me for *and* the one he paid for. A filter that caught
     * only one would silently answer half the question.
     */
    val personId: Long? = null,
    val query: String = "",
) {
    /** Shown on the filter control so hidden rows are never a surprise. */
    val activeCount: Int
        get() = listOf(
            from != null || to != null,
            rootId != null,
            leafId != null,
            method != null,
            personId != null,
        ).count { it }

    val hasQuery: Boolean get() = query.isNotBlank()

    val isDefault: Boolean get() = activeCount == 0 && !hasQuery

    /**
     * The amount half of the search. Returns null when the query is not a
     * number, in which case only the note substring applies.
     */
    val exactAmount: Money?
        get() = if (query.isBlank()) null else Money.parseOrNull(query)

    companion object {
        val NONE = LedgerFilters()
    }
}
