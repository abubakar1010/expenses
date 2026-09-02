package com.app.finance.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.finance.TestFixture
import com.app.finance.ui.feature.backup.BackupScreen
import com.app.finance.ui.feature.category.CategoryManagerScreen
import com.app.finance.ui.feature.income.SourceManagerScreen
import com.app.finance.ui.feature.people.PeopleScreen
import com.app.finance.ui.feature.reports.ReportsScreen
import com.app.finance.ui.feature.settings.RecurringScreen
import com.app.finance.ui.feature.settings.SettingsScreen
import com.app.finance.ui.theme.DayBookTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every detail route can be left by a control on the screen — §27.
 *
 * All seven rendered a bare title and carried nothing but `BackHandler`. A
 * system gesture is not an affordance: it is invisible, it belongs to the OS
 * rather than to the app, and on the four routes hanging off a tab it was the
 * only exit that worked at all, because the bottom bar walked straight back in.
 *
 * One test per screen rather than one over [com.app.finance.ui.common.DetailHeader],
 * because the component being right is not the claim — the claim is that every
 * screen uses it, and that is exactly what a component test cannot make. The
 * 48 dp assertion (accessibility §10) rides along on each, since a header
 * squeezed by a long title and a trailing action is where a target shrinks.
 */
@RunWith(AndroidJUnit4::class)
class DetailBackControlTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var fx: TestFixture

    /**
     * A store this test owns, cleared before the database closes — §23.3.
     * `viewModel()` inside each screen would otherwise resolve against the host
     * activity's store, whose collectors outlive `@After` and throw on a Room
     * executor, landing on whichever test runs next.
     */
    private val vmStore = ViewModelStore()

    private val storeOwner = object : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore get() = vmStore
    }

    @Before fun setUp() { fx = TestFixture() }

    @After
    fun tearDown() {
        vmStore.clear()
        fx.closeAfterDraining()
    }

    /**
     * Renders [screen], then asserts the back control is there, is big enough
     * to hit, and does what it says.
     *
     * `waitUntil` rather than a bare assertion after `waitForIdle()`: settling
     * composition is not the same as a Room flow having emitted, and three of
     * these screens draw their first frame before their data arrives.
     */
    private fun assertLeavable(screen: @Composable (onBack: () -> Unit) -> Unit) {
        var backs = 0
        compose.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides storeOwner) {
                DayBookTheme {
                    val host = remember { SnackbarHostState() }
                    Column {
                        SnackbarHost(host)
                        CompositionLocalProvider(LocalSnackbarHost provides host) {
                            screen { backs++ }
                        }
                    }
                }
            }
        }
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithContentDescription(BACK).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithContentDescription(BACK)
            .assertIsDisplayed()
            // §10's touch minimum, on a control whose glyph is 24 dp.
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .performClick()

        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test
    fun categories_can_be_left() = assertLeavable { onBack ->
        CategoryManagerScreen(fx.container, LocalSnackbarHost.current, onBack)
    }

    @Test
    fun income_sources_can_be_left() = assertLeavable { onBack ->
        SourceManagerScreen(fx.container, LocalSnackbarHost.current, onBack)
    }

    @Test
    fun people_can_be_left() = assertLeavable { onBack ->
        PeopleScreen(fx.container, LocalSnackbarHost.current, onBack)
    }

    @Test
    fun repeating_entries_can_be_left() = assertLeavable { onBack ->
        RecurringScreen(fx.container, LocalSnackbarHost.current, onBack)
    }

    @Test
    fun settings_can_be_left() = assertLeavable { onBack ->
        SettingsScreen(
            container = fx.container,
            snackbarHostState = LocalSnackbarHost.current,
            onManageRecurring = {},
            onOpenReports = {},
            onOpenBackup = {},
            onBack = onBack,
        )
    }

    @Test
    fun backup_can_be_left() = assertLeavable { onBack ->
        BackupScreen(fx.container, LocalSnackbarHost.current, onBack)
    }

    @Test
    fun reports_can_be_left() = assertLeavable { onBack ->
        ReportsScreen(fx.container, onBack)
    }

    private companion object {
        /** `R.string.back`, as the user hears it. */
        const val BACK = "Back"

        const val WAIT_MS = 5_000L
    }
}

/** Saves threading a host through every one of the seven lambdas above. */
private val LocalSnackbarHost = androidx.compose.runtime.compositionLocalOf {
    SnackbarHostState()
}
