package com.app.finance.domain.usecase

/**
 * Integer percentages that total exactly 100.
 *
 * Two requirements need this and they need it for the same reason. FR-IE-06
 * asks that a source breakdown's "percentages sum to 100 ± 0.1 after rounding";
 * FR-AN-07's fixed / variable / unpredictable split is a share of one total and
 * has to add up for the same reason a breakdown does. Rounding each share on
 * its own does not achieve it — three equal parts each round to 33 and the
 * column reads 99, six equal parts read 96 — and near-equal parts are ordinary
 * rather than exotic in both cases.
 *
 * So: every part gets its floor, and the leftover points go to the parts with
 * the largest fractional remainders. The result always totals exactly 100 and
 * no part is ever more than one point from its exact share.
 *
 * Extracted from `IncomeBreakdown` when the spend mix needed it a second time.
 * Pure Kotlin, no Android, no Room (NFR-MAIN-01).
 */
object LargestRemainder {

    /**
     * @param weights the parts, in the order they will be displayed. Ties on
     *   the fractional remainder break by **position**, so a caller that has
     *   already sorted its rows meaningfully — largest first, say — gets the
     *   leftover point handed to the row a reader would expect, and the same
     *   data always produces the same column.
     * @return one whole percent per weight, summing to exactly 100 — or all
     *   zeroes when there is nothing to divide.
     */
    fun percentages(weights: List<Long>): List<Int> {
        val total = weights.sumOf { maxOf(it, 0L) }
        if (total <= 0L) return List(weights.size) { 0 }

        // Integer arithmetic throughout: `weight * 100 / total` is the floor
        // and the modulus is the remainder, with no floating point to round a
        // second time.
        val safe = weights.map { maxOf(it, 0L) }
        val floors = safe.map { (it * 100L) / total }
        val remainders = safe.map { (it * 100L) % total }

        // Each part's fractional loss is strictly under one point, so the
        // leftover can never exceed the number of parts; the coercion is a
        // belt on that rather than a real branch.
        val leftover = (100L - floors.sum()).toInt().coerceIn(0, weights.size)

        val bonus = weights.indices
            .sortedWith(compareByDescending<Int> { remainders[it] }.thenBy { it })
            .take(leftover)
            .toSet()

        return floors.mapIndexed { i, floor -> (floor + if (i in bonus) 1L else 0L).toInt() }
    }
}
