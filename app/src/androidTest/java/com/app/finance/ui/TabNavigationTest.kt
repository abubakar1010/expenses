package com.app.finance.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the bottom bar does — §27.
 *
 * `AppNav.kt` had no test of any kind, and the two defects that hid there are
 * both about the *shape of the back stack* rather than about any screen, so
 * this drives the real [navigateTop] against a graph carrying the real
 * [Route] paths and stand-ins for the detail routes. Nothing here needs a
 * database, because nothing here is about what a screen shows.
 *
 * The defects, both from `popUpTo(saveState = true)` saving whatever it popped
 * as one stack keyed by the deepest entry:
 *
 *  - **A tab reopened the detail route it was left from.** Ledger → People,
 *    then Ledger, restored `[ledger, people]` and landed on People — and stayed
 *    stuck there, since leaving the tab saved the pair again.
 *  - **A tab reopened a detail route belonging to another tab.** On an
 *    `!inclusive` pop the popUpTo *target* is mapped to that same saved stack
 *    when it has no mapping yet, so on a fresh launch Dashboard → Settings →
 *    Ledger → Dashboard opened Settings from the **Dashboard** tab.
 */
@RunWith(AndroidJUnit4::class)
class TabNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var nav: NavHostController

    /**
     * The real four tabs, plus two detail routes.
     *
     * Two, not one: the second defect only appears once a detail route has been
     * visited from a tab *other* than the one being returned to, and one route
     * cannot be in two places.
     */
    private fun graph() {
        compose.setContent {
            nav = rememberNavController()
            NavHost(navController = nav, startDestination = Route.Dashboard.path) {
                Route.entries.forEach { route ->
                    composable(route.path) { Label(route.path) }
                }
                composable(SETTINGS) { Label(SETTINGS) }
                composable(PEOPLE) { Label(PEOPLE) }
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun Label(text: String) = Text(text)

    private fun tap(route: Route) = compose.runOnIdle { nav.navigateTop(route) }

    private fun open(route: String) = compose.runOnIdle { nav.navigate(route) }

    private fun here(): String? = compose.runOnIdle { nav.currentDestination?.route }

    private fun stack(): List<String?> = compose.runOnIdle {
        nav.currentBackStack.value
            .map { it.destination.route }
            .filter { it != null && it != nav.graph.route }
    }

    // --- the defect --------------------------------------------------------

    @Test
    fun tapping_the_tab_a_detail_route_hangs_off_returns_to_that_tab() {
        graph()
        tap(Route.Ledger)
        open(PEOPLE)

        tap(Route.Ledger)

        assertEquals(Route.Ledger.path, here())
    }

    @Test
    fun a_tab_left_through_a_detail_route_does_not_reopen_it_later() {
        // The sticky half: leaving People by a *different* tab used to save the
        // pair, so the Ledger tab reopened People on every later visit.
        graph()
        tap(Route.Ledger)
        open(PEOPLE)
        tap(Route.Income)

        tap(Route.Ledger)

        assertEquals(Route.Ledger.path, here())
    }

    @Test
    fun a_detail_route_off_the_start_destination_does_not_come_back_under_a_tab() {
        // Dashboard is the start destination, which is the case that also
        // poisons the popUpTo target. Fresh graph, exactly as a first launch.
        graph()
        open(SETTINGS)
        tap(Route.Ledger)

        tap(Route.Dashboard)

        assertEquals(Route.Dashboard.path, here())
    }

    @Test
    fun a_detail_route_is_gone_from_the_back_stack_after_a_tab_switch() {
        // Not merely off-screen: still on the stack, the system back gesture
        // would walk back into it from a tab it does not belong to.
        graph()
        tap(Route.Budget)
        open(SETTINGS)

        tap(Route.Income)

        assertEquals(listOf(Route.Dashboard.path, Route.Income.path), stack())
    }

    @Test
    fun two_stacked_detail_routes_are_both_left() {
        // Settings → Backup is the only two-deep chain in the app.
        graph()
        open(SETTINGS)
        open(PEOPLE)

        tap(Route.Ledger)

        assertEquals(listOf(Route.Dashboard.path, Route.Ledger.path), stack())
    }

    // --- what the fix must not cost ----------------------------------------

    @Test
    fun a_tab_switch_still_replaces_rather_than_stacks() {
        graph()
        tap(Route.Ledger)
        tap(Route.Income)
        tap(Route.Budget)

        // Every tab sits directly on the start destination; four taps do not
        // make four entries to unwind.
        assertEquals(listOf(Route.Dashboard.path, Route.Budget.path), stack())
    }

    @Test
    fun re_tapping_the_current_tab_does_nothing() {
        graph()
        tap(Route.Ledger)

        tap(Route.Ledger)

        assertEquals(listOf(Route.Dashboard.path, Route.Ledger.path), stack())
    }

    @Test
    fun system_back_from_a_detail_route_still_returns_to_the_tab_it_was_opened_from() {
        graph()
        tap(Route.Income)
        open(PEOPLE)

        compose.runOnIdle { nav.popBackStack() }

        assertEquals(Route.Income.path, here())
    }

    // --- what the bottom bar is told ---------------------------------------

    @Test
    fun a_detail_route_lights_no_tab() {
        // `fromPath` used to answer Dashboard for anything it did not know,
        // which lit the Dashboard tab on all seven detail routes — including
        // Settings, which is reached from the Dashboard's own header.
        assertNull(Route.fromPath(SETTINGS))
        assertNull(Route.fromPath(null))
        assertNull(Route.fromPath("nonsense"))
    }

    @Test
    fun each_tab_path_still_resolves_to_its_own_tab() {
        Route.entries.forEach { route ->
            assertEquals(route, Route.fromPath(route.path))
        }
    }

    private companion object {
        /** Stands in for the four detail routes reached from a tab's header. */
        const val PEOPLE = "people"

        /** Stands in for the three reached from Settings, and Settings itself. */
        const val SETTINGS = "settings"
    }
}
