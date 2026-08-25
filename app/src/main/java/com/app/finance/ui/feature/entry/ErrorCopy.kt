package com.app.finance.ui.feature.entry

import androidx.annotation.StringRes
import com.app.finance.R
import com.app.finance.domain.model.EntryError

/**
 * Maps a typed domain error onto the copy the user reads.
 *
 * 05-ui-ux-guide.md §9: errors state the problem and the fix, without apology.
 * Not "Oops! Something went wrong" but "Amount can't be zero. Enter how much
 * you spent." The mapping lives in the UI layer so `domain/` stays free of
 * resources and stays JVM-testable.
 */
@StringRes
fun EntryError.messageRes(): Int = when (this) {
    EntryError.ZERO_AMOUNT -> R.string.error_zero_amount
    EntryError.NOT_A_LEAF_CATEGORY -> R.string.error_not_a_leaf
    EntryError.CATEGORY_ARCHIVED -> R.string.error_category_archived
    EntryError.CATEGORY_NOT_FOUND -> R.string.error_pick_category
    EntryError.DUPLICATE_NAME -> R.string.error_duplicate_name
    EntryError.BLANK_NAME -> R.string.error_blank_name
    EntryError.CATEGORY_TOO_DEEP -> R.string.error_category_too_deep
    EntryError.BUDGET_ON_NON_LEAF -> R.string.error_budget_non_leaf
    EntryError.ZERO_LIMIT -> R.string.error_zero_limit
    EntryError.FUTURE_DATE -> R.string.error_future_date
    EntryError.NON_POSITIVE_INCOME -> R.string.error_non_positive_income
    EntryError.SOURCE_NOT_FOUND -> R.string.error_source_not_found
    EntryError.SOURCE_ARCHIVED -> R.string.error_source_archived
    EntryError.SOURCE_HAS_ENTRIES -> R.string.error_source_has_entries
    EntryError.SOURCE_HAS_RULES -> R.string.error_source_has_rules
    EntryError.CATEGORY_HAS_ENTRIES -> R.string.error_category_has_entries
    EntryError.STORAGE_FULL -> R.string.error_storage_full
    EntryError.STORAGE_FAILED -> R.string.error_storage_failed
    EntryError.CONSTRAINT_VIOLATION -> R.string.error_not_saved
}
