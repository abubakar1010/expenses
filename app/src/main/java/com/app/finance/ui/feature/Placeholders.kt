package com.app.finance.ui.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.app.finance.R
import com.app.finance.ui.theme.KhataTheme
import com.app.finance.ui.theme.Space

/**
 * M4. The dashboard carries safe-to-spend, the month ribbon, the net strip,
 * "needs attention", and the budget groups — with the 300 ms render target of
 * NFR-PERF-04 applying to this screen specifically.
 *
 * [com.app.finance.ui.common.MonthRibbon] and
 * [com.app.finance.ui.common.BudgetBar] are already built and tested; this
 * screen is their assembly plus the four `RollupDao` flows combined through the
 * domain use cases.
 */
@Composable
fun DashboardScreen(modifier: Modifier = Modifier) =
    Placeholder(stringResource(R.string.coming_dashboard), "Dashboard", modifier)

@Composable
internal fun Placeholder(message: String, title: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = Space.gutter),
        verticalArrangement = Arrangement.spacedBy(Space.s3, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = KhataTheme.type.screenTitle, color = KhataTheme.colors.ink)
        Text(
            text = message,
            style = KhataTheme.type.body,
            color = KhataTheme.colors.inkSoft,
            textAlign = TextAlign.Center,
        )
    }
}
