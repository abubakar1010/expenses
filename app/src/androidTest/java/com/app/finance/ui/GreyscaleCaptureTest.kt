package com.app.finance.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Environment
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.finance.TestFixture
import com.app.finance.core.money.Money
import com.app.finance.core.time.Period
import com.app.finance.ui.feature.budget.BudgetScreen
import com.app.finance.ui.feature.dashboard.DashboardScreen
import com.app.finance.ui.feature.income.IncomeScreen
import com.app.finance.ui.feature.ledger.LedgerScreen
import com.app.finance.ui.feature.reports.ReportsScreen
import com.app.finance.ui.theme.KhataTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/**
 * NFR-USE-05 — "state is never conveyed by colour alone".
 *
 * The requirement has been asserted about since M2 and never *checked*, because
 * checking it means looking at the screens with the colour taken out. This
 * renders each surface, desaturates it by luminance, and writes a PNG that a
 * person reads: over-budget must still say over, a pending row must still be
 * marked, and a falling trend must still be legible as falling.
 *
 * Doing it here rather than with `adb screencap` makes it repeatable and pins
 * the data — a capture of whatever happened to be on the phone that day proves
 * nothing about the states the requirement is about. The fixture deliberately
 * puts one leaf over its limit, one under, and a pending entry on the ledger.
 *
 * Files land in the app's external files directory:
 *
 * ```
 * adb shell ls /sdcard/Android/data/com.app.finance.debug/files/greyscale
 * ```
 */
@RunWith(AndroidJUnit4::class)
class GreyscaleCaptureTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var fx: TestFixture
    private val aug = Period(202608)

    @Before fun setUp() { fx = TestFixture() }
    @After fun tearDown() = fx.closeAfterDraining()

    /**
     * Every state the requirement is actually about, in one database: a leaf
     * over its limit, a leaf comfortably under, an unbudgeted leaf, income, and
     * a month of history so the trend has a direction.
     */
    private fun seedStates() = runBlocking {
        fx.budgets.setLimit(fx.leafId("Grocery"), aug, Money.ofTaka(8_000))
        fx.budgets.setLimit(fx.leafId("Transport"), aug, Money.ofTaka(4_000))

        // Over.
        fx.expenses.insert(Money.ofTaka(9_500), fx.leafId("Grocery"), LocalDate.of(2026, 8, 4))
        // Under.
        fx.expenses.insert(Money.ofTaka(1_200), fx.leafId("Transport"), LocalDate.of(2026, 8, 6))
        // Unbudgeted, and a refund so the ledger has a negative row.
        fx.expenses.insert(Money.ofTaka(2_000), fx.leafId("Medical"), LocalDate.of(2026, 8, 9))
        fx.expenses.insert(Money.ofTaka(-450), fx.leafId("Grocery"), LocalDate.of(2026, 8, 11))

        fx.income.saveEntry(Money.ofTaka(42_000), "Salary", LocalDate.of(2026, 8, 1))

        // Two earlier months, so the six-month trend slopes.
        fx.expenses.insert(Money.ofTaka(5_000), fx.leafId("Grocery"), LocalDate.of(2026, 7, 4))
        fx.expenses.insert(Money.ofTaka(3_000), fx.leafId("Grocery"), LocalDate.of(2026, 6, 4))

        // A pending row. "Waiting to be confirmed" is exactly the kind of state
        // a design reaches for a colour to express, so it has to be *in* the
        // capture rather than described in a comment.
        //
        // Set directly rather than generated from a rule: a rule created today
        // has its first due date in the future, so `evaluate()` correctly
        // produces nothing. How a pending row comes about is
        // `RecurringRepositoryTest`'s subject; what this needs is the row.
        fx.expenses.insert(Money.ofTaka(15_000), fx.leafId("House Rent"), LocalDate.of(2026, 8, 1))
        fx.db.openHelper.writableDatabase.execSQL(
            "UPDATE expense SET status = 1 WHERE amount_minor = 1500000",
        )
    }

    private fun capture(name: String, content: @Composable () -> Unit) {
        compose.setContent { KhataTheme { content() } }
        compose.waitForIdle()
        // The flows land after Compose goes idle; this is the same wait every
        // screen test makes, without asserting on any particular string.
        Thread.sleep(1_500)
        compose.waitForIdle()

        val shot = compose.onRoot().captureToImage().asAndroidBitmap()
        write(name, shot.desaturated())
    }

    /**
     * Rec. 709 luminance. A naive average would flatter the palette: indigo and
     * vermilion average to similar values but differ in perceived lightness, so
     * averaging would hide exactly the failure this is looking for.
     */
    private fun Bitmap.desaturated(): Bitmap {
        val out = createBitmap(width, height)
        val row = IntArray(width)
        for (y in 0 until height) {
            getPixels(row, 0, width, 0, y, width, 1)
            for (x in row.indices) {
                val p = row[x]
                val l = (
                    0.2126 * Color.red(p) + 0.7152 * Color.green(p) + 0.0722 * Color.blue(p)
                    ).toInt().coerceIn(0, 255)
                row[x] = Color.argb(Color.alpha(p), l, l, l)
            }
            out.setPixels(row, 0, width, 0, y, width, 1)
        }
        return out
    }

    private fun createBitmap(w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    /**
     * Written to AGP's `additionalTestOutputDir` when the run provides one.
     *
     * The app's own external files directory looked like the obvious place and
     * is the wrong one: `connectedDebugAndroidTest` uninstalls the app when it
     * finishes, which takes that directory with it, so the captures existed
     * only until the run that made them ended. Anything under
     * `additionalTestOutputDir` is pulled to
     * `build/outputs/connected_android_test_additional_output/` before teardown.
     */
    private fun write(name: String, bitmap: Bitmap) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pulled = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val dir = if (pulled != null) {
            File(pulled, "greyscale")
        } else {
            File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "greyscale")
        }
        dir.mkdirs()
        val file = File(dir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "wrote ${file.absolutePath} (${bitmap.width}x${bitmap.height})")
    }

    @Test
    fun dashboard() {
        seedStates()
        capture("1-dashboard") {
            DashboardScreen(
                container = fx.container,
                period = aug,
                onPeriodChange = {},
                onOpenBudget = {},
                onOpenSettings = {},
            )
        }
    }

    @Test
    fun budget() {
        seedStates()
        capture("2-budget") {
            BudgetScreen(
                container = fx.container,
                period = aug,
                onPeriodChange = {},
                snackbarHostState = SnackbarHostState(),
                onManageCategories = {},
            )
        }
    }

    @Test
    fun ledger() {
        seedStates()
        capture("3-ledger") {
            LedgerScreen(
                container = fx.container,
                snackbarHostState = SnackbarHostState(),
                onEdit = {},
                onAdd = {},
            )
        }
    }

    @Test
    fun income() {
        seedStates()
        capture("4-income") {
            IncomeScreen(
                container = fx.container,
                period = aug,
                onPeriodChange = {},
                snackbarHostState = SnackbarHostState(),
                onManageSources = {},
            )
        }
    }

    @Test
    fun reports() {
        seedStates()
        capture("5-reports") {
            ReportsScreen(container = fx.container, onBack = {})
        }
    }

    private companion object {
        const val TAG = "KhataGreyscale"
    }
}
