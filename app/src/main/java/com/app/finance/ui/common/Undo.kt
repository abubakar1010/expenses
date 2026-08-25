package com.app.finance.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.withTimeoutOrNull

/**
 * NFR-USE-03 — "undoable for at least 5 seconds".
 *
 * Material offers roughly four seconds (`Short`) or roughly ten (`Long`), so
 * neither is the requirement; an indefinite snackbar cancelled at exactly five
 * is. Five screens had each written that out, under three different names and
 * behind five private copies of the constant, and they had already drifted:
 * only two of them did anything when the window expired.
 */
const val UNDO_WINDOW_MS = 5_000L

/**
 * One queued undoable action, waiting for its turn at the snackbar.
 *
 * [id] is monotonic per ViewModel and is what a screen keys its effect on —
 * appending a second action must not change it, because that is precisely how
 * the first one used to lose its window.
 */
data class Undoable<T>(val id: Long, val payload: T)

/**
 * Offers [message] with an Undo action for [UNDO_WINDOW_MS], then reports which
 * way it ended.
 *
 * **The dismissal first is not tidying.** `showSnackbar` takes the host's mutex
 * and the wait for it is *inside* the timeout, so a second destructive action
 * taken while the first snackbar is still up would queue behind it and could
 * spend its entire five seconds waiting — the user destroying something, seeing
 * no snackbar at all, and having no way to undo it. Dismissing what is on
 * screen bounds that wait by an exit animation instead of by another action's
 * window.
 *
 * [onExpired] runs when the window closes without a tap. It is not optional in
 * spirit: a caller that holds the deleted row in memory has to release it, and
 * the two ledger sites that forgot are exactly where a row became unrecoverable.
 */
suspend fun SnackbarHostState.offerUndo(
    message: String,
    undoLabel: String?,
    onExpired: () -> Unit = {},
    onUndo: () -> Unit,
) {
    currentSnackbarData?.dismiss()
    val result = withTimeoutOrNull(UNDO_WINDOW_MS) {
        showSnackbar(
            message = message,
            actionLabel = undoLabel,
            withDismissAction = false,
            duration = SnackbarDuration.Indefinite,
        )
    }
    if (result == SnackbarResult.ActionPerformed) onUndo() else onExpired()
}
