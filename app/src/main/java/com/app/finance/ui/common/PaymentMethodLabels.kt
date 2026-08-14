package com.app.finance.ui.common

import androidx.annotation.StringRes
import com.app.finance.R
import com.app.finance.domain.model.PaymentMethod

/**
 * Display names for the payment methods.
 *
 * Kept out of the enum because `domain/` may not import Android — the
 * `architectureCheck` task fails the build on it — and because these are
 * user-facing words that a Bengali build must translate. "bKash" and "Nagad"
 * are proper nouns and stay as they are in every locale; "Cash", "Bank" and
 * "Card" do not.
 */
@StringRes
fun PaymentMethod.labelRes(): Int = when (this) {
    PaymentMethod.CASH -> R.string.method_cash
    PaymentMethod.BKASH -> R.string.method_bkash
    PaymentMethod.NAGAD -> R.string.method_nagad
    PaymentMethod.BANK -> R.string.method_bank
    PaymentMethod.CARD -> R.string.method_card
    PaymentMethod.OTHER -> R.string.method_other
}
