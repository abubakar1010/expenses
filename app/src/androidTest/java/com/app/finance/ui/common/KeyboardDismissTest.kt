package com.app.finance.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [dismissKeyboardOnOutsideGesture] — the modifier itself, away from any screen.
 *
 * **Focus is the assertion, not the keyboard.** There is no supported way to
 * ask the framework whether the IME is on screen from an instrumented test, and
 * there does not need to be: the IME follows the focused text field, and the
 * defect being fixed here was that nothing ever took that focus away. A field
 * that is no longer focused is a keyboard that is no longer up.
 *
 * The four cases below are the four decisions in the modifier's contract, and
 * two of them are guards on behaviour that must *not* change — a tap into a
 * field and a field's own scroll-into-view. Those are the ones that would turn
 * a fix for a stuck keyboard into a field that cannot be typed in at all.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardDismissTest {

    @get:Rule
    val compose = createComposeRule()

    /** A field, a button and a lot of empty space, under the modifier. */
    @Composable
    private fun Harness(onClick: () -> Unit = {}) {
        var text by remember { mutableStateOf("") }
        Box(Modifier.fillMaxSize().dismissKeyboardOnOutsideGesture()) {
            Column(Modifier.fillMaxSize()) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag(FIELD),
                )
                Text(
                    text = "Button",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clickable(onClick = onClick)
                        .testTag(BUTTON),
                )
                Spacer(Modifier.fillMaxSize().testTag(EMPTY))
            }
        }
    }

    @Test
    fun a_tap_on_empty_space_clears_the_field() {
        compose.setContent { Harness() }

        compose.onNodeWithTag(FIELD).performClick()
        compose.onNodeWithTag(FIELD).assertIsFocused()

        compose.onNodeWithTag(EMPTY).performClick()
        compose.onNodeWithTag(FIELD).assertIsNotFocused()
    }

    @Test
    fun a_tap_into_the_field_does_not_clear_it() {
        // The whole fix rests on `requireUnconsumed = true`: `CoreTextField`
        // consumes the down in the main pass, so the root detector never sees
        // this tap. Without that, tapping a field would raise the keyboard and
        // dismiss it in the same gesture.
        compose.setContent { Harness() }

        compose.onNodeWithTag(FIELD).performClick()
        compose.onNodeWithTag(FIELD).assertIsFocused()

        compose.onNodeWithTag(FIELD).performClick()
        compose.onNodeWithTag(FIELD).assertIsFocused()
    }

    @Test
    fun a_tap_on_a_control_runs_it_and_leaves_the_field_focused() {
        // The documented boundary, pinned so it is a decision rather than an
        // accident: a tap something else handled is not a tap on "somewhere
        // else". In the app the controls that matter here open a sheet, and a
        // sheet takes window focus and the keyboard with it.
        var clicked = false
        compose.setContent { Harness(onClick = { clicked = true }) }

        compose.onNodeWithTag(FIELD).performClick()
        compose.onNodeWithTag(BUTTON).performClick()

        assertTrue(clicked)
        compose.onNodeWithTag(FIELD).assertIsFocused()
    }

    @Test
    fun a_drag_clears_the_field() {
        compose.setContent {
            var text by remember { mutableStateOf("") }
            Box(Modifier.fillMaxSize().dismissKeyboardOnOutsideGesture()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag(LIST)) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag(FIELD),
                    )
                    Spacer(Modifier.height(4000.dp))
                }
            }
        }

        compose.onNodeWithTag(FIELD).performClick()
        compose.onNodeWithTag(FIELD).assertIsFocused()

        compose.onNodeWithTag(LIST).performTouchInput { swipeUp() }
        compose.waitForIdle()

        compose.onNodeWithTag(FIELD).assertIsNotFocused()
    }

    @Test
    fun a_field_scrolled_into_view_by_its_own_focus_keeps_it() {
        // `ContentInViewNode` dispatches the bring-into-view scroll as
        // `NestedScrollSource.UserInput`, exactly like a drag. If the modifier
        // trusted the source alone, focusing a field far down a scrolling
        // screen — Backup's passphrase, Settings' typed confirmation — would
        // scroll it into view and blur it in the same frame. The finger-down
        // check is what separates the two, and this is what would notice it
        // going away.
        val requester = FocusRequester()
        compose.setContent {
            var text by remember { mutableStateOf("") }
            Box(Modifier.fillMaxSize().dismissKeyboardOnOutsideGesture()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Text(
                        text = "Focus it",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clickable { requester.requestFocus() }
                            .testTag(BUTTON),
                    )
                    Spacer(Modifier.height(4000.dp))
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .focusRequester(requester)
                            .testTag(FIELD),
                    )
                }
            }
        }

        compose.onNodeWithTag(BUTTON).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(FIELD).assertIsFocused()
    }

    private companion object {
        const val FIELD = "field"
        const val BUTTON = "button"
        const val EMPTY = "empty"
        const val LIST = "list"
    }
}
