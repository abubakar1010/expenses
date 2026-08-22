package com.app.finance.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.app.finance.R
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.app.finance.core.time.Period
import com.app.finance.di.AppContainer
import com.app.finance.ui.common.KhataIcons
import com.app.finance.ui.feature.backup.BackupScreen
import com.app.finance.ui.feature.budget.BudgetScreen
import com.app.finance.ui.feature.category.CategoryManagerScreen
import com.app.finance.ui.feature.dashboard.DashboardScreen
import com.app.finance.ui.feature.entry.QuickAddSheet
import com.app.finance.ui.feature.income.IncomeScreen
import com.app.finance.ui.feature.income.SourceManagerScreen
import com.app.finance.ui.feature.ledger.LedgerScreen
import com.app.finance.ui.feature.settings.RecurringScreen
import com.app.finance.ui.feature.settings.SettingsScreen
import com.app.finance.ui.feature.reports.ReportsScreen
import com.app.finance.ui.theme.KhataTheme
import com.app.finance.ui.theme.Motion
import com.app.finance.ui.theme.khataTween
import kotlinx.coroutines.launch

/** The four bottom-bar destinations. Everything else is a detail route or sheet. */
enum class Route(val path: String, val icon: ImageVector) {
    Dashboard("dashboard", KhataIcons.Dashboard),
    Ledger("ledger", KhataIcons.Ledger),
    Income("income", KhataIcons.Income),
    Budget("budget", KhataIcons.Budget),
    ;

    companion object {
        fun fromPath(path: String?): Route = entries.firstOrNull { it.path == path } ?: Dashboard
    }
}

/**
 * The single `NavHost` — 04 §7.
 *
 * Screen transitions are a 150 ms fade through, with no slide and no shared
 * element (05 §7). That is not minimalism for its own sake: a slide animates
 * layout on every frame of the transition, and on the target hardware the
 * cheapest transition that still signals a change is the correct one.
 */
@Composable
fun KhataApp(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = Route.fromPath(backStackEntry?.destination?.route)

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // The Quick Add sheet is state, not a route: it must be openable from all
    // four primary screens without pushing a back-stack entry that the system
    // back gesture would then have to unwind twice.
    //
    // NOT_OPEN / NEW_ENTRY / an expense id — one value covers all three states,
    // and `rememberSaveable` carries it through process death (FR-APP-03).
    var sheetTarget by rememberSaveable { mutableLongStateOf(SHEET_CLOSED) }

    // The viewed period is owned here, not in any one screen: Budget needs it
    // now, and Dashboard (M4) and Income (M3) will need the *same* one — a user
    // who steps back to July on one screen has not asked to be on August
    // everywhere else.
    //
    // rememberSaveable survives rotation and process death; app_meta carries it
    // across launches, which is the "period" half of FR-APP-03.
    var periodYm by rememberSaveable { mutableIntStateOf(Period.now(container.clock).ym) }
    val period = remember(periodYm) { Period(periodYm) }

    LaunchedEffect(Unit) {
        // A stored value from a future schema, or a corrupted one, would throw
        // out of Period's init and take the app down on launch for the sake of
        // remembering a month.
        runCatching { container.appMetaRepo.lastViewedPeriod() }
            .getOrNull()
            ?.let { periodYm = it.ym }
    }
    LaunchedEffect(period) {
        runCatching { container.appMetaRepo.setLastViewedPeriod(period) }
    }

    Scaffold(
        containerColor = KhataTheme.colors.paper,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            KhataBottomBar(
                current = current,
                onSelect = { navController.navigateTop(it) },
                onQuickAdd = { sheetTarget = SHEET_NEW },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .background(KhataTheme.colors.paper)
                .padding(padding),
        ) {
            // 05 §7 — a 150 ms fade through, and *no* animation at all when the
            // system animator scale is zero. `khataTween` is what enforces the
            // second half; a raw `tween` would still animate.
            val screenFade = khataTween<Float>(Motion.SCREEN, Motion.FastOutSlowIn)

            NavHost(
                navController = navController,
                startDestination = Route.Dashboard.path,
                enterTransition = { fadeIn(screenFade) },
                exitTransition = { fadeOut(screenFade) },
                popEnterTransition = { fadeIn(screenFade) },
                popExitTransition = { fadeOut(screenFade) },
            ) {
                composable(Route.Dashboard.path) {
                    DashboardScreen(
                        container = container,
                        period = period,
                        onPeriodChange = { periodYm = it.ym },
                        // 05 §5.4's gear, deferred at M4 to the milestone that
                        // owns Settings.
                        onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                        // Every actionable row on the dashboard is about a
                        // limit, and the limit editor is one tab away. Switching
                        // tabs rather than pushing a detail route keeps the back
                        // gesture meaning what it means everywhere else.
                        onOpenBudget = { navController.navigateTop(Route.Budget) },
                    )
                }
                composable(Route.Ledger.path) {
                    LedgerScreen(
                        container = container,
                        snackbarHostState = snackbarHostState,
                        onEdit = { id -> sheetTarget = id },
                        onAdd = { sheetTarget = SHEET_NEW },
                    )
                }
                composable(Route.Income.path) {
                    IncomeScreen(
                        container = container,
                        period = period,
                        onPeriodChange = { periodYm = it.ym },
                        snackbarHostState = snackbarHostState,
                        onManageSources = { navController.navigate(ROUTE_SOURCES) },
                    )
                }
                composable(Route.Budget.path) {
                    BudgetScreen(
                        container = container,
                        period = period,
                        onPeriodChange = { periodYm = it.ym },
                        snackbarHostState = snackbarHostState,
                        onManageCategories = { navController.navigate(ROUTE_CATEGORIES) },
                    )
                }
                composable(ROUTE_CATEGORIES) {
                    CategoryManagerScreen(
                        container = container,
                        snackbarHostState = snackbarHostState,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(ROUTE_SOURCES) {
                    SourceManagerScreen(
                        container = container,
                        snackbarHostState = snackbarHostState,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(ROUTE_SETTINGS) {
                    SettingsScreen(
                        container = container,
                        snackbarHostState = snackbarHostState,
                        onManageRecurring = { navController.navigate(ROUTE_RECURRING) },
                        onOpenReports = { navController.navigate(ROUTE_REPORTS) },
                        onOpenBackup = { navController.navigate(ROUTE_BACKUP) },
                        onBack = { navController.popBackStack() },
                    )
                }
                // Reached from Settings for the same reason Reports is: 04 §7
                // fixes the bottom bar at four destinations.
                composable(ROUTE_BACKUP) {
                    BackupScreen(
                        container = container,
                        snackbarHostState = snackbarHostState,
                        onBack = { navController.popBackStack() },
                    )
                }
                // 04 §7 fixes the bottom bar at four destinations, so
                // Reports is reached from Settings rather than added to it.
                composable(ROUTE_REPORTS) {
                    ReportsScreen(
                        container = container,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(ROUTE_RECURRING) {
                    RecurringScreen(
                        container = container,
                        snackbarHostState = snackbarHostState,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }

    if (sheetTarget != SHEET_CLOSED) {
        val deletedMessage = stringResource(R.string.expense_deleted)
        QuickAddSheet(
            container = container,
            editingExpenseId = sheetTarget.takeIf { it != SHEET_NEW },
            onDismiss = { sheetTarget = SHEET_CLOSED },
            onSaved = { message ->
                sheetTarget = SHEET_CLOSED
                scope.launch { snackbarHostState.showSnackbar(message) }
            },
            onDeleted = {
                sheetTarget = SHEET_CLOSED
                // Deleting from the edit sheet leaves the ledger's undo state
                // untouched, so this snackbar is informational only. Undo lives
                // on the swipe gesture, where the row is still in view.
                scope.launch { snackbarHostState.showSnackbar(deletedMessage) }
            },
        )
    }
}

/** Detail routes, not tabs — the four nav slots are taken. */
private const val ROUTE_CATEGORIES = "categories"
private const val ROUTE_SOURCES = "sources"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_RECURRING = "recurring"
private const val ROUTE_REPORTS = "reports"
private const val ROUTE_BACKUP = "backup"

private const val SHEET_CLOSED = -1L
private const val SHEET_NEW = 0L

/**
 * Switching tabs replaces rather than stacks, and restores the state of the tab
 * being returned to — so scrolling deep into the ledger, glancing at the
 * dashboard and coming back does not drop the user at the top again.
 */
private fun NavHostController.navigateTop(route: Route) {
    if (currentDestination?.route == route.path) return
    navigate(route.path) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

