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
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.seshuthota.drift.DriftApplication
import io.github.seshuthota.drift.app.DriftPreferenceStore
import io.github.seshuthota.drift.ime.editor.DriftEditorInfo
import io.github.seshuthota.drift.ime.keyboard3.ImeController
import io.github.seshuthota.drift.ime.keyboard3.LocalImeController
import io.github.seshuthota.drift.ime.keyboard3.interaction.LocalInteractionController
import io.github.seshuthota.drift.ime.keyboard3.interaction.NoOpInteractionController
import io.github.seshuthota.drift.ime.keyboard3.interaction.RecordingInputConnection
import io.github.seshuthota.drift.ime.nlp.WordSuggestionCandidate
import io.github.seshuthota.drift.lib.snygg.SnyggStylesheet
import io.github.seshuthota.drift.lib.snygg.ui.ProvideSnyggTheme
import io.github.seshuthota.drift.lib.snygg.ui.rememberSnyggTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.k3lp.lib.text.asK3String
import java.lang.ref.WeakReference

/**
 * Rendered suggestion bar: [CandidatesRow] composes inside the real theme locals and a tap on a candidate goes
 * through [ImeController] into the editor. Run on a device:
 *
 *     CLASS=io.github.seshuthota.drift.ime.smartbar.CandidatesRowGoldenTest
 *     ./gradlew :app:connectedDebugAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.class=$CLASS
 */
@RunWith(AndroidJUnit4::class)
class CandidatesRowGoldenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs by DriftPreferenceStore
    private var clipboardSuggestionEnabled = true
    private lateinit var controller: ImeController
    private lateinit var connection: RecordingInputConnection

    @Before
    fun setUp() {
        val application = context.applicationContext as DriftApplication
        controller = application.imeController
        // The clipboard provider outranks directly injected candidates when the clipboard holds text.
        clipboardSuggestionEnabled = prefs.clipboard.suggestionEnabled.get()
        runBlocking { prefs.clipboard.suggestionEnabled.set(false) }
        connection = RecordingInputConnection()
        controller.updateStateBlocking {
            handleStartInputView(
                WeakReference(connection),
                DriftEditorInfo.wrap(
                    EditorInfo().apply {
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
                        initialSelStart = 0
                        initialSelEnd = 0
                    },
                ),
            )
            emitText("hel".asK3String())
        }
        application.nlpManager.value.suggestDirectly(
            listOf(
                WordSuggestionCandidate(text = "hello"),
                WordSuggestionCandidate(text = "help"),
            ),
        )
    }

    @After
    fun tearDown() {
        runBlocking { prefs.clipboard.suggestionEnabled.set(clipboardSuggestionEnabled) }
    }

    @Test
    fun theRowShowsTheCandidatesTheProviderOffered() {
        setCandidatesRow()
        composeRule.onNodeWithText("hello").assertIsDisplayed()
        composeRule.onNodeWithText("help").assertIsDisplayed()
    }

    @Test
    fun tappingACandidateCommitsIt() {
        setCandidatesRow()
        composeRule.onNodeWithText("help").performTouchInput { click() }
        composeRule.waitUntil(timeoutMillis = 5_000) { connection.text.toString() == "help " }
    }

    private fun setCandidatesRow() {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalImeController provides controller,
                LocalInteractionController provides NoOpInteractionController,
            ) {
                ProvideSnyggTheme(rememberSnyggTheme(CANDIDATE_STYLESHEET)) {
                    CandidatesRow()
                }
            }
        }
    }

    private companion object {
        val CANDIDATE_STYLESHEET = SnyggStylesheet.v2 {
            "root" {
                background = rgbaColor(255, 255, 255)
                foreground = rgbaColor(0, 0, 0)
                fontSize = fontSize(14.sp)
                padding = padding(4.dp)
            }
        }
    }
}
