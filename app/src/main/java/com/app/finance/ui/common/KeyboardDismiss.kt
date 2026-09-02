package com.app.finance.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager

/**
 * Tap away from a field, or scroll, and the software keyboard goes away.
 *
 * Compose does not do this on its own, and nothing else in the app did it
 * either: a `BasicTextField` keeps focus until something takes it away, the IME
 * follows focus, and neither a tap on empty space nor a drag of a list is
 * "something". Every field in the app was therefore a one-way door — the
 * keyboard opened on the first tap and stayed up until the screen or sheet
 * closed. The ledger's search field was the worst of them, because its
 * `ImeAction.Search` has no default behaviour at all (`KeyboardActionRunner`
 * runs `else -> false` for Search), so even the IME's own action key did
 * nothing.
 *
 * This is one modifier rather than a rule per field because the gesture that
 * dismisses a keyboard never lands on the field — it lands on whatever the user
 * looked at next. It belongs to the surface, so it is applied once at the root
 * of the app (`DayBookApp`) and once per bottom sheet that can raise an IME.
 * **Sheets need their own** — a `ModalBottomSheet` is a separate window with its
 * own composition, so a modifier on the app root is not above it.
 *
 * Two gestures, and each is narrowed deliberately.
 *
 * **A tap that nothing else claimed.** The detector runs in the main pass with
 * `requireUnconsumed = true`, so a child that handled the tap has already
 * excluded it: tapping *into* a field cannot dismiss the keyboard it is about to
 * raise (`CoreTextField`'s `detectTapAndPress` consumes the down), and neither
 * can pressing a button or a ledger row. What is left is the empty space
 * between them, which is exactly "somewhere else". A control that opens a sheet
 * is not a case this misses: the sheet's window takes focus and the IME goes
 * with it.
 *
 * **A vertical drag with a finger on the glass.** Both halves of that are
 * load-bearing:
 *
 * - *With a finger down*, because `ContentInViewNode` — the bring-into-view
 *   scroll that keeps a newly focused field visible — dispatches with
 *   `NestedScrollSource.UserInput` too, and is indistinguishable from a drag by
 *   source alone. Without the check, focusing a field inside a scrolling screen
 *   (Backup, Settings) would scroll it into view and immediately blur it again.
 * - *Vertical*, because a single-line `BasicTextField` is wrapped in a
 *   horizontal `Modifier.scrollable`, so dragging inside the field to move the
 *   cursor dispatches nested scroll of its own. Every scroll container in the
 *   app that a user drags past a field is vertical; every field is `singleLine`.
 *   A multi-line field added later would need this reconsidered.
 */
@Composable
fun Modifier.dismissKeyboardOnOutsideGesture(): Modifier {
    val focusManager = LocalFocusManager.current
    val gesture = remember { GestureState() }
    val scroll = remember(focusManager) { ScrollDismissesKeyboard(focusManager, gesture) }

    return this
        .nestedScroll(scroll)
        .pointerInput(gesture) {
            // The initial pass, and `requireUnconsumed = false`: the scroll this
            // press is about to start is dispatched by a child that will have
            // consumed the down long before the main pass reaches here.
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                gesture.pressed = true
                gesture.dismissed = false
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                    } while (event.changes.any { it.pressed })
                } finally {
                    gesture.pressed = false
                }
            }
        }
        .pointerInput(focusManager) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = true)
                // Null means the gesture was cancelled or became somebody
                // else's — a drag, most often — and the scroll half above owns
                // that case.
                if (waitForUpOrCancellation() != null) focusManager.clearFocus()
            }
        }
}

/**
 * Shared between the two halves above, and deliberately *not* Compose state:
 * it is written from a pointer coroutine and read from a scroll callback, both
 * on the main thread, and a snapshot read inside `onPreScroll` would be
 * recorded by whatever observer happens to be open there.
 */
private class GestureState {
    /** A finger is on the glass. */
    var pressed = false

    /** This gesture has already dismissed the keyboard; once is enough. */
    var dismissed = false
}

private class ScrollDismissesKeyboard(
    private val focusManager: FocusManager,
    private val gesture: GestureState,
) : NestedScrollConnection {

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (
            source == NestedScrollSource.UserInput &&
            gesture.pressed &&
            !gesture.dismissed &&
            available.y != 0f
        ) {
            gesture.dismissed = true
            focusManager.clearFocus()
        }
        // Consumes nothing. The scroll it was told about is not its business.
        return Offset.Zero
    }
}
