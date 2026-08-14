package com.app.finance.ui.feature.budget

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.app.finance.R
import com.app.finance.ui.feature.dashboard.Placeholder

/**
 * M2.
 *
 * Limits attach to leaves only; a root's figure is the sum of its children,
 * computed at query time and therefore impossible to desynchronise from its
 * parts. [com.app.finance.data.db.dao.BudgetDao.observeRootLimit] does that sum
 * and the leaf-only rule is already enforced by trigger, so this screen is the
 * editing surface rather than the place those rules live.
 */
@Composable
fun BudgetScreen(modifier: Modifier = Modifier) =
    Placeholder(stringResource(R.string.coming_budget), "Budget", modifier)
