package com.app.finance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.app.finance.ui.KhataApp
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
 * draws while SQLite is still being opened on an IO thread — which is the
 * difference between hitting the 800 ms budget on eMMC storage and missing it
 * regardless of how well the rest is written.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as FinanceApp).container

        setContent {
            KhataTheme {
                KhataApp(container)
            }
        }
    }
}
