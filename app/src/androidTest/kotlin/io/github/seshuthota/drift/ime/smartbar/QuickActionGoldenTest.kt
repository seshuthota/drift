/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.seshuthota.drift.ime.smartbar

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.seshuthota.drift.DriftApplication
import io.github.seshuthota.drift.R
import io.github.seshuthota.drift.app.DriftPreferenceStore
import io.github.seshuthota.drift.ime.ImeUiMode
import io.github.seshuthota.drift.ime.editor.DriftEditorInfo
import io.github.seshuthota.drift.ime.keyboard3.ImeActions
import io.github.seshuthota.drift.ime.keyboard3.ImeController
import io.github.seshuthota.drift.ime.keyboard3.LocalImeController
import io.github.seshuthota.drift.ime.keyboard3.interaction.LocalInteractionController
import io.github.seshuthota.drift.ime.keyboard3.interaction.NoOpInteractionController
import io.github.seshuthota.drift.ime.keyboard3.interaction.RecordingInputConnection
import io.github.seshuthota.drift.ime.smartbar.quickaction.QuickAction
import io.github.seshuthota.drift.ime.smartbar.quickaction.QuickActionButton
import io.github.seshuthota.drift.ime.window.ImeWindowController
import io.github.seshuthota.drift.ime.window.LocalWindowController
import io.github.seshuthota.drift.lib.compose.ProvideLocalizedResources
import io.github.seshuthota.drift.lib.snygg.SnyggStylesheet
import io.github.seshuthota.drift.lib.snygg.ui.ProvideSnyggTheme
import io.github.seshuthota.drift.lib.snygg.ui.rememberSnyggTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.k3lp.lib.text.K3Descriptor
import org.k3lp.lib.text.asK3String
import java.lang.ref.WeakReference

@RunWith(AndroidJUnit4::class)
class QuickActionGoldenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs by DriftPreferenceStore
    private var autoCorrect = true
    private lateinit var controller: ImeController
    private lateinit var connection: RecordingInputConnection
    private val windowScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val windowController by lazy { ImeWindowController(prefs, windowScope) }

    @Before
    fun setUp() {
        val application = context.applicationContext as DriftApplication
        controller = application.imeController
        autoCorrect = prefs.correction.autoCorrect.get()
        connection = RecordingInputConnection()
        controller.updateStateBlocking {
            handleStartInputView(
                WeakReference(connection),
                DriftEditorInfo.wrap(
                    EditorInfo().apply {
                        inputType = InputType.TYPE_CLASS_TEXT
                        initialSelStart = 0
                        initialSelEnd = 0
                    },
                ),
            )
            emitText("hello".asK3String())
        }
    }

    @After
    fun tearDown() {
        runBlocking { prefs.correction.autoCorrect.set(autoCorrect) }
        controller.updateStateBlocking { emitDescriptor(ImeActions.ShowTextPanel) }
        windowScope.cancel()
    }

    @Test
    fun arrowLeftSendsADpadKeyPress() {
        tap(ImeActions.ArrowLeft)
        composeRule.waitUntil(timeoutMillis = 5_000) { connection.keyEvents.size >= 2 }
        assert(connection.keyEvents.map { it.keyCode }.all { it == KeyEvent.KEYCODE_DPAD_LEFT })
        assert(connection.keyEvents.map { it.action } == listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP))
    }

    @Test
    fun selectAllAsksTheEditorToSelectAll() {
        tap(ImeActions.SelectAll)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            connection.contextMenuActions == listOf(android.R.id.selectAll)
        }
    }

    @Test
    fun undoSendsCtrlZ() {
        tap(ImeActions.Undo)
        composeRule.waitUntil(timeoutMillis = 5_000) { connection.keyEvents.size >= 2 }
        val down = connection.keyEvents.first()
        assert(down.keyCode == KeyEvent.KEYCODE_Z) { "key ${down.keyCode}" }
        assert(down.isCtrlPressed && !down.isShiftPressed)
    }

    @Test
    fun copyIsDisabledWithoutASelection() {
        tap(ImeActions.ClipboardCopy)
        composeRule.waitForIdle()
        assert(connection.contextMenuActions.isEmpty())
        assert(connection.keyEvents.isEmpty())
        assert(connection.text.toString() == "hello") { "text \"${connection.text}\"" }
    }

    @Test
    fun toggleAutocorrectFlipsThePreference() {
        val before = prefs.correction.autoCorrect.get()
        tap(ImeActions.ToggleAutocorrect)
        composeRule.waitUntil(timeoutMillis = 5_000) { prefs.correction.autoCorrect.get() != before }
    }

    @Test
    fun clipboardActionOpensTheClipboardPanel() {
        tap(ImeActions.ShowClipboardPanel)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            controller.snapshotState().flags.imeUiMode == ImeUiMode.CLIPBOARD
        }
    }

    private fun tap(descriptor: K3Descriptor) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalImeController provides controller,
                LocalInteractionController provides NoOpInteractionController,
                LocalWindowController provides windowController,
            ) {
                ProvideLocalizedResources(context, R.string.app_name) {
                    ProvideSnyggTheme(rememberSnyggTheme(STYLESHEET)) {
                        QuickActionButton(
                            action = QuickAction.InsertK3Descriptor(descriptor),
                            modifier = Modifier.size(48.dp).testTag(TAG),
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag(TAG).performTouchInput { click() }
    }

    private companion object {
        const val TAG = "quick-action"

        val STYLESHEET = SnyggStylesheet.v2 {
            "root" {
                background = rgbaColor(255, 255, 255)
                foreground = rgbaColor(0, 0, 0)
                fontSize = fontSize(14.sp)
            }
        }
    }
}
