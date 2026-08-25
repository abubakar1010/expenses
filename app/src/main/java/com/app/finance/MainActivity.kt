package com.app.finance

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.finance.domain.model.ThemeChoice
import kotlinx.coroutines.flow.catch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.app.finance.ui.KhataApp
import com.app.finance.ui.lock.LocalLockController
import com.app.finance.ui.lock.LockController
import com.app.finance.ui.lock.LockScreen
import com.app.finance.ui.RecoveryScreen
import com.app.finance.ui.WelcomeScreen
import com.app.finance.ui.theme.KhataTheme

/**
 * The only Activity in the application.
 *
 * 04-system-architecture.md §2.2: a single Activity and no Fragments, so there
 * is exactly one inflation pass on cold start. Everything else is a Compose
 * route or a bottom sheet.
 *
 * **It is a `FragmentActivity` and still inflates no fragment.** FR-APP-04's
 * gate delegates to the OS through `androidx.biometric`, whose `BiometricPrompt`
 * takes a `FragmentActivity` and nothing else; the alternative,
 * `KeyguardManager.createConfirmDeviceCredentialIntent`, has been deprecated
 * since API 29 and shows a PIN sheet where a fingerprint would do. What §2.2 is
 * protecting is the 800 ms cold-start budget, and the cost of that base class is
 * a `FragmentController` on the startup path rather than a layout inflation —
 * so it was settled by measuring it rather than by preference (§20.6).
 *
 * §6: this method must not wait on the database. The container is already
 * constructed and entirely lazy, so `setContent` returns and the first frame
 * draws while SQLite is still opening on an IO thread — which is the difference
 * between hitting the 800 ms budget on eMMC storage and missing it regardless
 * of how well the rest is written.
 */
/**
 * What the app shows before it shows anything of the ledger.
 *
 * Five states rather than five screens, because what is being decided is an
 * *order* and the order is the part that has consequences.
 */
internal enum class RootScreen {
    /** 04 §8 — the database will not open. */
    RECOVERY,

    /** A setting the decision depends on has not arrived yet. */
    LOADING,

    /** FR-APP-04's gate. */
    LOCK,

    /** FR-DAT-10's restore offer, on a ledger that is still empty. */
    WELCOME,

    /** The ledger. */
    APP,
}

/**
 * The order in which the launch gates apply.
 *
 * A pure function, and that is the point. This was a `when` inside
 * `setContent`, unreachable by any test: nothing in the suite composes
 * `MainActivity`, and the obvious way to try — break the database and assert
 * the recovery screen wins — cannot work, because the same broken database
 * makes `observeAppLock` catch and emit `false`, so the branch being tested for
 * precedence is not even in the running.
 *
 * The three orderings that matter, each with a reason that was written down
 * long before anything checked it:
 *
 *  1. **Recovery before the lock.** §19.1's defect was a setting read that ran
 *     ahead of the recovery path. The lock is a second such read, and a gate
 *     that failed *shut* would lock a user out of the one screen that can
 *     rescue five years of data. So the lock is never consulted when the
 *     database is broken — not even to decide whether to wait for it.
 *  2. **Nothing before a setting it depends on.** A `null` is "not read yet",
 *     not "off". Defaulting to unlocked for a frame or two is exactly what a
 *     lock must not do, so an unresolved setting shows a blank instead.
 *  3. **The lock before the welcome offer.** FR-DAT-10 puts a restore behind
 *     the gate, because a backup file is the whole ledger and the gate that
 *     protects the ledger should protect the door it can be replaced through.
 */
internal fun rootScreen(
    databaseFailed: Boolean,
    lockEnabled: Boolean?,
    locked: Boolean,
    welcomeLatch: Boolean?,
    welcomeDone: Boolean,
): RootScreen = when {
    databaseFailed -> RootScreen.RECOVERY
    lockEnabled == null -> RootScreen.LOADING
    lockEnabled && locked -> RootScreen.LOCK
    welcomeLatch == null -> RootScreen.LOADING
    welcomeLatch && !welcomeDone -> RootScreen.WELCOME
    else -> RootScreen.APP
}

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge has to be established before the first frame, but which
        // *icons* the bars draw cannot be decided yet -- that needs the theme
        // setting, which is a database read §6 keeps off this path. So the
        // window is laid out here and the appearance is applied below, as the
        // read lands, by the same `LaunchedEffect` shape FLAG_SECURE uses.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as FinanceApp).container

        setContent {
            // 04 §7's "theme" setting. Collected here rather than inside the
            // NavHost because it decides the colours the very first frame is
            // painted in; SYSTEM until the read lands, which is also the
            // default, so nothing flickers on a phone that has not chosen.
            //
            // **`catch` is not defensive padding.** This is a Room query, and
            // the one case that matters is the database being the thing that is
            // broken — which is precisely when [RecoveryScreen] has to appear.
            // Without it the flow throws, the exception leaves the collecting
            // coroutine, and the app dies on launch: a user whose migration
            // failed would get a crash instead of the one screen that could
            // save five years of data (04 §8). Losing a theme preference is the
            // right price; losing the recovery path is not.
            // `remember`, because a flow operator applied in composition builds
            // a new flow on every recomposition and resets the collection under
            // it. The container outlives the activity, so there is nothing to
            // key on.
            val themeFlow = remember {
                container.settingsRepo.observeTheme().catch { error ->
                    Log.w(TAG, "theme unreadable; falling back to the system", error)
                    emit(ThemeChoice.SYSTEM)
                }
            }
            val theme by themeFlow.collectAsStateWithLifecycle(initialValue = ThemeChoice.SYSTEM)

            // NFR-SEC-04. `remember` and `catch` for the same reason the theme
            // has them (§19.1): this is another `app_meta` read on the launch
            // path, and an unreadable database must reach the recovery screen
            // rather than take the app down on its way there.
            val secureFlow = remember {
                container.settingsRepo.observeSecureScreen().catch { error ->
                    Log.w(TAG, "secure-screen setting unreadable", error)
                    emit(false)
                }
            }
            val secure by secureFlow.collectAsStateWithLifecycle(initialValue = false)
            LaunchedEffect(secure) {
                // Applied as the setting lands rather than before `setContent`,
                // because reading it first would put a database open on the
                // first-frame path and 04 §6 spends real effort keeping that
                // clear. Nothing is lost by it: the recents thumbnail is
                // captured when the app is *left*, which is long after this.
                if (secure) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            // FR-APP-04. `null` until the read lands — not `false` — because
            // defaulting to "unlocked" would show the ledger for a frame or two
            // before the gate arrived, which is the one thing a lock must not
            // do. The blank surface below is what covers that gap.
            val lockFlow = remember {
                container.settingsRepo.observeAppLock().catch { error ->
                    Log.w(TAG, "app-lock setting unreadable; failing open", error)
                    emit(false)
                }
            }
            val lockEnabled by lockFlow.collectAsStateWithLifecycle(initialValue = null)
            val lockController = remember { LockController() }

            // FR-DAT-10. Read here rather than inside the NavHost because it
            // decides whether there is an app to show at all, and `remember` +
            // `catch` for the reason the three above have them (§19.1): an
            // unreadable database must reach RecoveryScreen rather than take the
            // app down on the way there.
            //
            // It rides the blank the lock already waits through -- both are
            // `app_meta` reads issued together against a database one of them has
            // opened anyway, so this costs no second pause on the launch path.
            val welcomeFlow = remember {
                container.settingsRepo.observeNeedsWelcome().catch { error ->
                    Log.w(TAG, "onboarding state unreadable; assuming not needed", error)
                    emit(false)
                }
            }
            val needsWelcome by welcomeFlow.collectAsStateWithLifecycle(initialValue = null)

            // **Latched, and that is the whole point.** Whether to offer a
            // restore is a property of how this launch started, not of what the
            // ledger holds a moment later — and a restore changes both. Left as
            // a live flow, importing a backup filled the ledger and the gate
            // vanished underneath the screen that was still mid-conversation,
            // taking the "where should backups go now?" question with it.
            var welcomeLatch by rememberSaveable { mutableStateOf<Boolean?>(null) }
            var welcomeDone by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(needsWelcome) {
                if (welcomeLatch == null) welcomeLatch = needsWelcome
            }

            // Locking on background: a gate that only applies at cold start
            // protects nothing, because the app a thief finds is the one still
            // open in recents.
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_STOP -> lockController.onStopped()
                        Lifecycle.Event.ON_START -> lockController.onStarted()
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            // One value, used by both the colours and the system bars. They
            // were resolved separately and drifted: `enableEdgeToEdge()` was
            // called once with no arguments, so the bars followed
            // `isNightModeActive` -- the *phone's* setting -- while everything
            // below followed [ThemeChoice]. Dark app on a light phone meant
            // dark icons on a dark status bar. `uiMode` is in this activity's
            // `configChanges` (04 §2.2, to keep a theme toggle from restarting
            // it), so nothing recreated the activity to resettle it either, and
            // even "Follow the phone" went stale until the next cold start.
            val dark = theme.isDark(isSystemInDarkTheme())
            LaunchedEffect(dark) {
                // Scrims copied from the library's own defaults rather than
                // left transparent: on API 26-28 a three-button navigation bar
                // has no framework contrast enforcement, and transparent there
                // means invisible buttons. Only `detectDarkMode` changes.
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(SCRIM_LIGHT, SCRIM_DARK) { dark },
                )
            }

            KhataTheme(darkTheme = dark) {
                var databaseFailed by remember { mutableStateOf(false) }

                // Runs in parallel with the first frame, never before it. On
                // failure the app is replaced by the recovery screen rather
                // than crashing with no message (04 §8).
                LaunchedEffect(Unit) {
                    container.verifyDatabase()
                        .onFailure { error ->
                            Log.e(TAG, "database unusable", error)
                            databaseFailed = true
                        }
                        .onSuccess {
                            // FR-REC-02 and FR-REC-04 — "missed due dates
                            // accumulated while the app was unopened MUST all be
                            // generated on next launch".
                            //
                            // Here rather than in a WorkManager job, which 04's
                            // stack table anticipated: with no notifications
                            // (05 §12) a background run would produce rows
                            // nobody could see until the app was opened, which
                            // is exactly what this does — without a
                            // ContentProvider on the startup path §6 spent
                            // effort keeping clear. Inside the same
                            // LaunchedEffect that already runs beside the first
                            // frame, so it costs no frames either.
                            runCatching { container.recurringRepo.evaluate() }
                                .onFailure { error -> Log.e(TAG, "rule evaluation failed", error) }

                            // FR-DAT-08 — the automatic backup, here for exactly
                            // the reasons written above for rule evaluation: 04
                            // §6 keeps ContentProviders off the startup path,
                            // which rules out WorkManager's default initialiser,
                            // and 05 §12 has no notification through which a
                            // background run could report anything. NFR-COMP-05
                            // settles it -- "no background work is required for
                            // core function".
                            //
                            // `runIfDue` returns after two `app_meta` reads on
                            // every launch that is not a backup day, so the cost
                            // paid by the common case is a query and a compare.
                            runCatching { container.backupRepo.runIfDue() }
                                .onFailure { error -> Log.w(TAG, "automatic backup failed", error) }

                            if (BuildConfig.DEBUG && !container.assertPeriodsDerived()) {
                                // 03 §4.3. A row filed in a month its own
                                // date does not fall in is invisible to
                                // every other check here, the rollup one
                                // included.
                                Log.e(TAG, "PERIOD DRIFT: period_ym disagrees with the date")
                            }

                            if (BuildConfig.DEBUG && !container.assertRollupsReconcile()) {
                                // NFR-REL-02. Loud, and debug-only: a mismatch
                                // means a trigger is wrong or something wrote
                                // around them, and either way every figure the
                                // app shows is suspect.
                                Log.e(TAG, "ROLLUP DRIFT: aggregates do not match the ledger")
                            }
                        }
                }

                CompositionLocalProvider(LocalLockController provides lockController) {
                    // The ordering lives in [rootScreen], which is a pure
                    // function with a test. It used to live in a `when` right
                    // here, where nothing could reach it: composing
                    // `MainActivity` to check that recovery comes before the
                    // lock does not work, because the broken database that
                    // reaches the recovery branch also breaks the `app_meta`
                    // read the lock setting comes from — `observeAppLock`
                    // catches, emits false, and the assertion passes without
                    // testing anything. §20.3 claimed a test for this ordering
                    // for two milestones; §22.6 corrected that, and this is
                    // what makes the sentence true.
                    when (
                        rootScreen(
                            databaseFailed = databaseFailed,
                            lockEnabled = lockEnabled,
                            locked = lockController.locked,
                            welcomeLatch = welcomeLatch,
                            welcomeDone = welcomeDone,
                        )
                    ) {
                        RootScreen.RECOVERY -> RecoveryScreen()

                        // A themed blank is what the splash already looks like,
                        // and it lasts one `app_meta` read.
                        RootScreen.LOADING -> Box(Modifier.fillMaxSize())

                        RootScreen.LOCK -> LockScreen(onUnlocked = lockController::unlock)

                        RootScreen.WELCOME ->
                            WelcomeScreen(container, onDone = { welcomeDone = true })

                        RootScreen.APP -> KhataApp(container)
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "Khata"

        /**
         * `androidx.activity`'s own `enableEdgeToEdge` defaults, restated
         * because naming `detectDarkMode` means passing the scrims too.
         *
         * They are only ever painted below API 29, where the framework does not
         * enforce navigation-bar contrast itself.
         */
        const val TRANSPARENT = android.graphics.Color.TRANSPARENT
        const val SCRIM_LIGHT = 0xE6FFFFFF.toInt()
        const val SCRIM_DARK = 0x801B1B1B.toInt()
    }
}
