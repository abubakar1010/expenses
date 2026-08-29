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
import com.app.finance.ui.theme.DayBookTheme
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
import org.junit.Assert.assertTrue
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsNode

/**
 * NFR-USE-05 — "state is never conveyed by colour alone".
 *
 * The requirement has been asserted about since M2 and never *checked*, because
 * checking it means looking at the screens with the colour taken out. This
 * renders each surface, desaturates it by luminance, and writes a PNG that a
 * person reads: over-budget must still say over, a pending row must still be
 * marked, and a falling trend must still be legible as falling.
 *
 * **The PNGs are the evidence; the assertions are the gate.** For its first two
 * milestones this class had five `@Test`s and not one assertion — both
 * occurrences of the word in the file were inside comments. Every test rendered
 * a screen, wrote a file and called `Log.i`, and §20.7 recorded "It passes".
 * What passed was a person looking at five images, once, and nothing in the
 * suite could ever have failed. So each capture now also asserts the *word* the
 * screen must be saying: the colour is a second channel, and the requirement is
 * that it is never the only one. A label that quietly becomes a coloured dot
 * fails here rather than in somebody's hands.
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

    /**
     * Renders, waits, writes the greyscale PNG, and hands back every word on
     * the screen so the caller can assert on it.
     */
    private fun capture(name: String, content: @Composable () -> Unit): List<String> {
        compose.setContent { DayBookTheme { content() } }
        compose.waitForIdle()
        // The flows land after Compose goes idle; this is the same wait every
        // screen test makes, without asserting on any particular string.
        Thread.sleep(1_500)
        compose.waitForIdle()

        val shot = compose.onRoot().captureToImage().asAndroidBitmap()
        write(name, shot.desaturated())
        return labels(compose.onRoot().fetchSemanticsNode())
    }

    /**
     * Every `Text` and `contentDescription` in the tree, flattened.
     *
     * Both, because the two channels serve different readers and NFR-USE-05
     * covers the visual one: a state that reaches TalkBack through a
     * `contentDescription` but is drawn as a bare coloured dot still fails the
     * requirement. Where the app merges a row into one spoken sentence
     * (`semantics(mergeDescendants = true)`) that sentence is the label, and it
     * is checked for the word.
     */
    private fun labels(node: SemanticsNode): List<String> =
        buildList {
            node.config.getOrNull(SemanticsProperties.Text)
                ?.forEach { add(it.text) }
            node.config.getOrNull(SemanticsProperties.ContentDescription)
                ?.forEach { add(it) }
            node.children.forEach { addAll(labels(it)) }
        }

    /**
     * Asserts the screen says [expected], in words, somewhere.
     *
     * Deliberately a substring match over the whole tree rather than a
     * `onNodeWithText`: what the requirement cares about is that the state is
     * *stated*, not which node states it, and pinning the node would make every
     * layout change a failure of an accessibility test.
     */
    private fun assertSays(screen: String, expected: String, labels: List<String>) {
        assertTrue(
            "$screen conveys this state by colour alone: nothing on it says " +
                "\"$expected\". What it says: $labels",
            labels.any { it.contains(expected, ignoreCase = true) },
        )
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
        val said = capture("1-dashboard") {
            DashboardScreen(
                container = fx.container,
                period = aug,
                onPeriodChange = {},
                onOpenBudget = {},
                onOpenSettings = {},
            )
        }

        // Grocery is ৳9,050 against a ৳8,000 limit, so the "needs attention"
        // block has to *say* over. Vermilion alone is the failure this
        // requirement names, and it is also invisible to the ~8% of men here
        // with a red-green deficiency, which is the reason behind the reason.
        assertSays("The dashboard", "over", said)
        assertSays("The dashboard", "Grocery", said)
    }

    @Test
    fun budget() {
        seedStates()
        val said = capture("2-budget") {
            BudgetScreen(
                container = fx.container,
                period = aug,
                onPeriodChange = {},
                snackbarHostState = SnackbarHostState(),
                onManageCategories = {},
            )
        }

        // Three signals per bar, and only one of them is colour: the fill, the
        // percentage, and the sentence beneath. The sentence is the one being
        // pinned, because a bar and a percentage are both still readable when
        // desaturated while telling you nothing about *which side of the line*
        // you are on.
        assertSays("The budget screen", "over", said)
        // And the leaf that is comfortably under says how much is left, rather
        // than being distinguished only by a greener bar.
        assertSays("The budget screen", "left", said)
    }

    @Test
    fun ledger() {
        seedStates()
        val said = capture("3-ledger") {
            LedgerScreen(
                container = fx.container,
                snackbarHostState = SnackbarHostState(),
                onEdit = {},
                onAdd = {},
            )
        }

        // "Waiting to be confirmed" is exactly the state a design reaches for a
        // tint to express — a faded row, a coloured left edge — and a faded row
        // in greyscale is just a row. It is a heading and a button here.
        assertSays("The ledger", "Waiting to confirm", said)
        assertSays("The ledger", "Confirm", said)
    }

    @Test
    fun income() {
        seedStates()
        val said = capture("4-income") {
            IncomeScreen(
                container = fx.container,
                period = aug,
                onPeriodChange = {},
                snackbarHostState = SnackbarHostState(),
                onManageSources = {},
            )
        }

        // 05 §5.7: the stable/variable mark is "a shape difference, not a
        // colour difference, so it survives both greyscale and
        // colourblindness" — a filled dot against a hollow one. The shape is in
        // the PNG; the word is what can be asserted, and the row's spoken form
        // carries it.
        assertTrue(
            "the income rows name neither kind: $said",
            said.any { it.contains("Stable", true) || it.contains("Variable", true) },
        )
        assertSays("The income screen", "Salary", said)
    }

    @Test
    fun reports() {
        seedStates()
        val said = capture("5-reports") {
            ReportsScreen(container = fx.container, onBack = {})
        }

        // The spend mix is the screen's one genuinely colour-coded element.
        // Each slice is labelled with its nature and its share in words, so the
        // breakdown reads without the palette at all.
        //
        // Variable and Unpredictable, not Fixed: the only fixed-nature row the
        // fixture seeds is the House Rent occurrence, and that one is `status =
        // 1`. A pending row is in no figure anywhere until it is confirmed, so
        // the mix is right to leave it out — asserting "Fixed" here was
        // asserting that the pending exclusion is broken.
        assertSays("The reports screen", "Variable", said)
        assertSays("The reports screen", "Unpredictable", said)
        assertSays("The reports screen", "%", said)
    }

    private companion object {
        const val TAG = "DayBookGreyscale"
    }
}
