package com.app.finance.ui.lock

import android.content.Context
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.fragment.app.FragmentActivity
import com.app.finance.R
import com.app.finance.ui.theme.KhataTheme
import com.app.finance.ui.theme.Radius
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.Space
import androidx.core.content.ContextCompat

/**
 * FR-APP-04 — "an optional app-lock PIN or biometric gate".
 *
 * **The device's own credential, and no secret of Khata's own.** NFR-SEC-05
 * already settled the question this feature sits on top of:
 *
 * > "Database encryption at rest is out of scope for v1; the rationale — that
 * > it requires bundling a native crypto library at material size and startup
 * > cost, **while the device lock screen already gates access** — is recorded
 * > here deliberately"
 *
 * The database is plaintext in app-private storage. An app-specific PIN would
 * be a second, weaker secret standing in front of data whose real protection is
 * the device lock: it would need a hash, a salt, an attempt limit and a
 * forgotten-PIN path, and it would imply an encryption that is not there.
 * Delegating to the OS satisfies both halves of "PIN or biometric" — the
 * device credential *is* a PIN — and leaves Khata storing nothing it could
 * leak.
 */
enum class LockAvailability {
    /** A credential exists and the gate can be offered. */
    AVAILABLE,

    /** The phone has no PIN, pattern, password or biometric set. */
    NO_CREDENTIAL,

    /** No usable authenticator on this hardware or OS version. */
    UNSUPPORTED,
}

fun lockAvailability(context: Context): LockAvailability =
    when (BiometricManager.from(context).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)) {
        BiometricManager.BIOMETRIC_SUCCESS -> LockAvailability.AVAILABLE
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> LockAvailability.NO_CREDENTIAL
        else -> LockAvailability.UNSUPPORTED
    }

/**
 * Holds "is the app currently locked", and the one flag that keeps it honest.
 *
 * Locking on background is the behaviour, because a gate that only applies at
 * cold start protects nothing — the app a thief finds is the one already open
 * in recents. But two things background the app *on the user's behalf* and must
 * not re-lock it:
 *
 * 1. **The unlock prompt itself.** A device-credential prompt is a separate
 *    activity, so the app stops underneath it. Re-locking there locks the app
 *    under its own prompt, which is the classic way this feature is got wrong.
 * 2. **The file picker.** Export and import hand off to the system document
 *    picker; coming back from choosing a file must not demand a second
 *    authentication for an operation the user started thirty seconds ago.
 *
 * [beginHandoff] and [endHandoff] bracket both, and the bracketing is the whole
 * point.
 *
 * This was one `suppressNextBackground()` boolean, consumed by the first
 * `ON_STOP` of **any** cause — and the two failure modes that gives are the
 * ones that matter. A suppression armed for a picker that never opened sat
 * there until the user pressed Home, swallowed *that* stop, and left the ledger
 * sitting unlocked in recents. And "Send a copy" armed it before a plain
 * `startActivity` to the share sheet, which has no result and therefore no
 * moment at which the flag came down: tap it, pick WhatsApp, and Khata stayed
 * unlocked in the background for as long as the user was gone.
 *
 * A counter cannot be consumed by an unrelated stop, and
 * [rememberHandoffLauncher] is the only way to raise it — so a hand-off that is
 * begun is a hand-off that ends, including when the launch itself throws.
 */
class LockController {
    var locked by mutableStateOf(true)
        private set

    /**
     * How many system activities are currently holding the foreground on the
     * user's behalf. A count and not a flag because Compose will happily let a
     * fast double-tap start two.
     */
    private var handoffs = 0

    fun beginHandoff() {
        handoffs++
    }

    fun endHandoff() {
        if (handoffs > 0) handoffs--
    }

    fun onStopped() {
        if (handoffs == 0) locked = true
    }

    /**
     * Back in the foreground: whatever we handed off to is finished with.
     *
     * A backstop rather than the mechanism — [endHandoff] has normally already
     * run by now. It exists so that a count which somehow leaked cannot leave
     * the app permanently unlockable, which is a worse failure than the one
     * this class is fixing.
     */
    fun onStarted() {
        handoffs = 0
    }

    fun unlock() {
        locked = false
    }

    /** Turning the setting off unlocks: there is nothing left to gate. */
    fun release() {
        handoffs = 0
        locked = false
    }
}

/**
 * Launches a system activity without letting the app-lock fire underneath it.
 *
 * Returns a plain `(I) -> Unit` rather than the launcher, so there is no
 * `launch()` reachable that has not told [LockController] first — the pairing is
 * structural instead of remembered at seven call sites.
 *
 * A launch that throws ends the hand-off immediately. `ActivityNotFoundException`
 * means a phone with no document provider at all, which is rare enough that the
 * old behaviour (crash) was never seen; leaving the count raised on the way past
 * would be worse than the tap doing nothing.
 */
@Composable
fun <I, O> rememberHandoffLauncher(
    contract: ActivityResultContract<I, O>,
    onResult: (O) -> Unit,
): (I) -> Unit {
    val lock = LocalLockController.current
    val launcher = rememberLauncherForActivityResult(contract) { result ->
        lock.endHandoff()
        onResult(result)
    }
    return remember(launcher, lock) {
        { input: I ->
            lock.beginHandoff()
            runCatching { launcher.launch(input) }
                .onFailure { error ->
                    lock.endHandoff()
                    Log.w("Khata", "nothing on this device could handle the request", error)
                }
            Unit
        }
    }
}

val LocalLockController = compositionLocalOf { LockController() }

/**
 * The gate. Deliberately says nothing about the ledger behind it — the point of
 * the setting is that a passer-by learns nothing from the screen.
 */
@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var refused by remember { mutableStateOf(false) }

    val title = stringResource(R.string.lock_title)
    val subtitle = stringResource(R.string.lock_subtitle)

    fun prompt() {
        val host = activity ?: return
        refused = false
        BiometricPrompt(
            host,
            ContextCompat.getMainExecutor(host),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onUnlocked()
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    refused = true
                }
            },
        ).authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                .build(),
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(Space.gutter),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = KhataTheme.type.screenTitle,
            color = KhataTheme.colors.ink,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(if (refused) R.string.lock_retry else R.string.lock_body),
            style = KhataTheme.type.body,
            color = KhataTheme.colors.inkSoft,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Space.s2, bottom = Space.s4),
        )
        Button(
            onClick = ::prompt,
            shape = RoundedCornerShape(Radius.input),
            colors = ButtonDefaults.buttonColors(
                containerColor = KhataTheme.colors.indigo,
                contentColor = KhataTheme.colors.card,
            ),
            modifier = Modifier.fillMaxWidth().height(Sizes.minTouchTarget),
        ) {
            Text(stringResource(R.string.lock_unlock), style = KhataTheme.type.body)
        }
    }

    // Ask once on arrival, so the common case is one tap on the system sheet
    // rather than two. If the user dismisses it the button is still there.
    LaunchedEffect(Unit) { prompt() }
}
