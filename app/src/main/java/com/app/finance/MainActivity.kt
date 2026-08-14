package com.app.finance

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.app.finance.ui.KhataApp
import com.app.finance.ui.RecoveryScreen
import com.app.finance.ui.theme.KhataTheme

/**
 * The only Activity in the application.
 *
 * 04-system-architecture.md §2.2: a single Activity and no Fragments, so there
 * is exactly one inflation pass on cold start. Everything else is a Compose
 * route or a bottom sheet.
 *
 * §6: this method must not wait on the database. The container is already
 * constructed and entirely lazy, so `setContent` returns and the first frame
 * draws while SQLite is still opening on an IO thread — which is the difference
 * between hitting the 800 ms budget on eMMC storage and missing it regardless
 * of how well the rest is written.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as FinanceApp).container

        setContent {
            KhataTheme {
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
                            if (BuildConfig.DEBUG && !container.assertRollupsReconcile()) {
                                // NFR-REL-02. Loud, and debug-only: a mismatch
                                // means a trigger is wrong or something wrote
                                // around them, and either way every figure the
                                // app shows is suspect.
                                Log.e(TAG, "ROLLUP DRIFT: aggregates do not match the ledger")
                            }
                        }
                }

                if (databaseFailed) RecoveryScreen() else KhataApp(container)
            }
        }
    }

    private companion object {
        const val TAG = "Khata"
    }
}
