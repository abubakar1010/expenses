package com.app.finance

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.setContent
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
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
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

            KhataTheme(
                darkTheme = when (theme) {
                    ThemeChoice.SYSTEM -> isSystemInDarkTheme()
                    ThemeChoice.LIGHT -> false
                    ThemeChoice.DARK -> true
                },
            ) {
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
                    when {
                        // **First, and deliberately.** §19.1's defect was a
                        // setting read that ran ahead of the recovery path; the
                        // lock is a second one, and putting it in front of
                        // `RecoveryScreen` would lock a user out of the only
                        // screen that can rescue their data.
                        databaseFailed -> RecoveryScreen()

                        // The setting has not arrived yet. A themed blank is
                        // what the splash already looks like, and it lasts one
                        // `app_meta` read.
                        lockEnabled == null -> Box(Modifier.fillMaxSize())

                        lockEnabled == true && lockController.locked ->
                            LockScreen(onUnlocked = lockController::unlock)

                        else -> KhataApp(container)
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "Khata"
    }
}
