package com.app.finance.domain.model

/**
 * Why a write was refused.
 *
 * 04-system-architecture.md §8: a repository maps `SQLiteConstraintException`
 * to a typed domain error with an actionable message; a raw exception is never
 * surfaced to the user. This enum is that typed error — it carries no strings,
 * because the copy lives in `strings.xml` where it can be translated into
 * Bengali.
 */
enum class EntryError {
    /** FR-EXP-06 — zero is meaningless; negative is a refund and allowed. */
    ZERO_AMOUNT,

    /** FR-EXP-04 — only leaf categories may carry an expense. */
    NOT_A_LEAF_CATEGORY,

    /** FR-CAT-08 — archived categories are hidden from entry pickers. */
    CATEGORY_ARCHIVED,

    CATEGORY_NOT_FOUND,

    /** FR-IS-02 / FR-CAT-07 — the normalised name is already taken here. */
    DUPLICATE_NAME,

    BLANK_NAME,

    /** FR-CAT-05 — the tree is capped at two levels. */
    CATEGORY_TOO_DEEP,

    /**
     * FR-EXP-02 permits any date the user chooses, but a future one would post
     * straight into the period rollup and inflate spending that has not
     * happened. Pending status is the mechanism for future money, and it
     * arrives with recurring rules at P1.
     */
    FUTURE_DATE,

    /** FR-BUD-03 — limits attach to leaves; a root's limit is the sum of them. */
    BUDGET_ON_NON_LEAF,

    /**
     * A limit of zero. FR-BUD-08 permits it and the column's `CHECK` allows it,
     * but the dashboard query reads a missing row as `IFNULL(limit_minor, 0)`,
     * so a stored zero is indistinguishable from no budget at all — and the
     * percentage would divide by it. Clearing the limit is how a leaf becomes
     * unbudgeted.
     */
    ZERO_LIMIT,

    /**
     * FR-IE-03 — "Income amounts MUST be greater than zero", with the
     * acceptance criterion naming "0 or negative input". One rule covers both,
     * which is why this is not [ZERO_AMOUNT]: an expense of −৳340 is a refund
     * and perfectly legal (FR-EXP-06), while income has no refund case and the
     * column's `CHECK (amount_minor > 0)` says so.
     */
    NON_POSITIVE_INCOME,

    /**
     * The income counterpart of [CATEGORY_NOT_FOUND].
     *
     * A separate constant because the copy is what the user reads: routing a
     * missing *source* through the category error prints "Pick a category" on
     * a screen that has no categories on it.
     */
    SOURCE_NOT_FOUND,

    /**
     * FR-IS-05 — a source that has entries may not be deleted.
     *
     * `ON DELETE RESTRICT` on `income_entry.source_id` is the enforcement; this
     * is the typed form of it. The acceptance criterion asks for the delete to
     * be "disabled with an explanatory message offering Archive instead", so
     * this copy is what the manager shows beside a disabled control rather than
     * what it reports after a failure.
     */
    SOURCE_HAS_ENTRIES,

    /**
     * A source a repeating entry still points at.
     *
     * `recurring_rule.source_id` is `ON DELETE RESTRICT` just as
     * `income_entry.source_id` is, so this blocks a delete for the same
     * reason [SOURCE_HAS_ENTRIES] does — but it needs its own copy, because
     * "this source has entries" is not true and the user would go looking
     * for entries that are not there.
     */
    SOURCE_HAS_RULES,

    /** A constraint fired that the layer above did not anticipate. */
    CONSTRAINT_VIOLATION,
}

sealed interface SaveOutcome {
    @JvmInline
    value class Saved(val id: Long) : SaveOutcome

    @JvmInline
    value class Rejected(val error: EntryError) : SaveOutcome
}
