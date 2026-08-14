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

    /** A constraint fired that the layer above did not anticipate. */
    CONSTRAINT_VIOLATION,
}

sealed interface SaveOutcome {
    @JvmInline
    value class Saved(val id: Long) : SaveOutcome

    @JvmInline
    value class Rejected(val error: EntryError) : SaveOutcome
}
