package com.app.finance.ui.feature.income

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.app.finance.R
import com.app.finance.ui.feature.dashboard.Placeholder

/**
 * M3.
 *
 * The one screen that must not default to a month. 05 §5.7: "a farming month
 * showing ৳0 is alarming and meaningless in isolation. The year is the honest
 * unit for this income; the month is the honest unit for spending." Stable and
 * variable sources are distinguished by a filled versus hollow dot — a shape
 * difference, so it survives greyscale and colour blindness.
 *
 * [com.app.finance.data.db.dao.IncomeDao.observeTotalInPeriods] and
 * `observeStableTotalInPeriods` already take a period range rather than a
 * single period, for exactly this reason.
 */
@Composable
fun IncomeScreen(modifier: Modifier = Modifier) =
    Placeholder(stringResource(R.string.coming_income), "Income", modifier)
