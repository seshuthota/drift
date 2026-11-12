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

package io.github.seshuthota.drift.ime.keyboard3.interaction

import android.os.Bundle
import android.os.Handler
import android.text.InputType
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.seshuthota.drift.DriftApplication
import io.github.seshuthota.drift.app.DriftPreferenceStore
import io.github.seshuthota.drift.ime.editor.DriftEditorInfo
import io.github.seshuthota.drift.ime.keyboard3.ImeActions
import io.github.seshuthota.drift.ime.keyboard3.ImeController
import io.github.seshuthota.drift.ime.keyboard3.ImeLayerIds
import io.github.seshuthota.drift.ime.keyboard3.gesture.GestureConfig
import io.github.seshuthota.drift.ime.keyboard3.gesture.GesturePoint
import io.github.seshuthota.drift.ime.keyboard3.gesture.GlideKey
import io.github.seshuthota.drift.ime.keyboard3.gesture.GlideTypingManager
import io.github.seshuthota.drift.ime.keyboard3.hint.LongPressKeyHintPlacement
import io.github.seshuthota.drift.ime.keyboard3.touch.TouchKey
import io.github.seshuthota.drift.ime.keyboard3.touch.TouchKeyboard
import io.github.seshuthota.drift.ime.keyboard3.touch.TouchLayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.k3lp.lib.meta.source.SourceFileRef
import org.k3lp.lib.text.K3StringOrDescriptor
import org.k3lp.lib.text.asK3String
import org.k3lp.model.key.K3Key
import org.k3lp.model.key.K3KeyId
import java.lang.ref.WeakReference
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.time.Duration.Companion.milliseconds

/**
 * End-to-end golden traces: pointer samples go through [PointerTracker], the real [ImeController]
 * and [io.github.seshuthota.drift.ime.keyboard3.ImeEditor] into an [android.view.inputmethod.InputConnection].
 * Run on a device:
 *
 *     CLASS=io.github.seshuthota.drift.ime.keyboard3.interaction.PointerTextGoldenTest
 *     ./gradlew :app:connectedDebugAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.class=$CLASS
 */
@RunWith(AndroidJUnit4::class)
class PointerTextGoldenTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs by DriftPreferenceStore
    private lateinit var controller: ImeController
    private lateinit var tracker: PointerTracker
    private lateinit var connection: RecordingInputConnection

    @Before
    fun setUp() {
        val application = context.applicationContext as DriftApplication
        controller = application.imeController
        if (!preloaded) {
            runBlocking {
                application.nlpManager.value.ensureSuggestionProviderPreloaded(
                    application.subtypeManager.value.activeSubtype,
                )
            }
            preloaded = true
        }
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
        }
        tracker = PointerTracker(
            touchKeyboard = keyboard(),
            imeController = controller,
            interactionController = NoOpInteraction,
            showSimplePopups = false,
            peekDistanceSqMin = 0.01f,
            gestureConfig = GestureConfig(
                swipeDistancePx = 200f,
                swipeVelocityPxPerMs = 1f,
                glideEnabled = false,
                glideMinVelocityPxPerMs = 1f,
                glideMaxDurationMillis = 1_000L,
            ),
            gestureListener = null,
        )
    }

    @Test
    fun tappingLettersCommitsText() {
        "hello".forEach(::tap)
        assertEquals("hello", connection.text.toString())
    }

    @Test
    fun spaceSeparatesWords() {
        "hello".forEach(::tap)
        tap(' ')
        "world".forEach(::tap)
        assertEquals("hello world", connection.text.toString())
    }

    @Test
    fun backspaceDeletesThePreviousCharacter() {
        "abc".forEach(::tap)
        tap(BACKSPACE)
        assertEquals("ab", connection.text.toString())
    }

    @Test
    fun doubleSpaceBecomesPeriod() {
        assumeTrue(prefs.correction.doubleSpacePeriod.get())
        "abc".forEach(::tap)
        tap(' ')
        tap(' ')
        assertEquals("abc. ", connection.text.toString())
    }

    @Test
    fun rawEditorReceivesTailOnlyCommits() {
        bindRawEditor()
        "ab".forEach(::tap)
        assertEquals(listOf("a", "b"), connection.commits)
        tap(BACKSPACE)
        assertEquals("\u007F", connection.commits.last())
    }

    @Test
    fun rawEditorReceivesGlideCommit() {
        assumeTrue(prefs.glide.enabled.get())
        bindRawEditor()
        runGlide("the")
        assertTrue(connection.commits.joinToString("").contains("the"))
    }

    @Test
    fun glideDiagnosticsRecordsACommit() {
        assumeTrue(prefs.glide.enabled.get())
        val directory = context.getExternalFilesDir("diagnostics")
        val file = directory?.resolve("glide.jsonl")
        val original = prefs.devtools.glideDiagnostics.get()
        try {
            file?.delete()
            runBlocking { prefs.devtools.glideDiagnostics.set(true) }
            runGlide("the")
            assertTrue(file != null && file.length() > 0)
        } finally {
            runBlocking { prefs.devtools.glideDiagnostics.set(original) }
            file?.delete()
        }
    }

    @Test
    fun autocorrectsTypoBeforeSpace() {
        assumeTrue(prefs.correction.autoCorrect.get())
        "teh".forEach(::tap)
        tap(' ')
        assertEquals("the ", connection.text.toString())
    }

    @Test
    fun glideCommitsTheDecodedWord() {
        assumeTrue(prefs.glide.enabled.get())
        runGlide("the")
        assertEquals("the ", connection.text.toString())
    }

    private fun bindRawEditor() {
        connection = RecordingInputConnection()
        controller.updateStateBlocking {
            handleStartInputView(
                WeakReference(connection),
                DriftEditorInfo.wrap(
                    EditorInfo().apply {
                        inputType = InputType.TYPE_NULL
                        initialSelStart = 0
                        initialSelEnd = 0
                    },
                ),
            )
        }
    }

    /** Runs one glide through the app's manager; prepareWords() is async, so retry while it warms up. */
    private fun runGlide(word: String) {
        val application = context.applicationContext as DriftApplication
        val subtype = application.subtypeManager.value.activeSubtype
        val manager = GlideTypingManager(context) { controller }
        manager.setLayout(glideLayout(), subtype)
        val points = glidePoints(word)
        for (attempt in 1..4) {
            manager.onGlideStart()
            points.forEach(manager::onGlidePoint)
            manager.onGlideComplete(points)
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                if (connection.commits.isNotEmpty()) return
                Thread.sleep(50)
            }
        }
    }

    private fun tap(char: Char) {
        val key = keyboardKeys.getValue(char)
        val center = Offset(
            (key.hitbox.left + key.hitbox.right) / 2f * SIZE.width,
            (key.hitbox.top + key.hitbox.bottom) / 2f * SIZE.height,
        )
        val down = change(pressed = true, previousPressed = false, position = center, time = 100)
        val up = change(pressed = false, previousPressed = true, position = center, time = 150)
        runBlocking {
            tracker.handleDown(down, SIZE)
            tracker.handleUp(up, SIZE)
        }
        // BaseInputConnection does not call back into the IME, so mirror the selection update the
        // system would send after each commit.
        controller.updateStateBlocking {
            handleUpdateSelection(org.k3lp.runtime.K3TextRange(connection.text.length, connection.text.length))
        }
    }

    private fun change(
        pressed: Boolean,
        previousPressed: Boolean,
        position: Offset,
        time: Long,
    ): PointerInputChange = PointerInputChange(
        id = PointerId(0),
        uptimeMillis = time,
        position = position,
        pressed = pressed,
        pressure = 1f,
        previousUptimeMillis = time - 16,
        previousPosition = position,
        previousPressed = previousPressed,
        isInitiallyConsumed = false,
    )

    /** Letter geometry shared by the touch keys and the glide trace; staggered like the real QWERTY. */
    private fun letterRects(): Map<Char, Rect> {
        val rows = listOf("qwertyuiop" to 0.5f, "asdfghjkl" to 1.0f, "zxcvbnm" to 2.0f)
        return buildMap {
            rows.forEachIndexed { row, (letters, offset) ->
                letters.forEachIndexed { index, char ->
                    val left = (index + offset) / 10f
                    val top = row / 4f
                    put(char, Rect(left, top, left + 0.1f, top + 0.25f))
                }
            }
        }
    }

    private fun keyboard(): TouchKeyboard {
        val keys = mutableListOf<TouchKey>()
        for ((char, bounds) in letterRects()) {
            keys += touchKey(bounds, char.toString().asK3String(), "key_$char")
            keyboardKeys[char] = keys.last()
        }
        val space = Rect(0.1f, 0.75f, 0.7f, 1f)
        keys += touchKey(space, " ".asK3String(), "key_space")
        keyboardKeys[' '] = keys.last()
        val backspace = Rect(0.75f, 0.75f, 0.95f, 1f)
        keys += touchKey(backspace, ImeActions.Backspace, "key_backspace")
        keyboardKeys[BACKSPACE] = keys.last()
        return TouchKeyboard(
            layers = mapOf(ImeLayerIds.Base to TouchLayer(keys)),
            rowCount = 4,
            minDeviceWidthMm = 0,
        )
    }

    private fun glideLayout(): List<GlideKey> = letterRects().map { (char, bounds) ->
        GlideKey(
            code = char.code,
            centerX = (bounds.left + bounds.right) / 2f,
            centerY = (bounds.top + bounds.bottom) / 2f,
            width = bounds.width,
            height = bounds.height,
        )
    }

    private fun glidePoints(word: String): List<GesturePoint> {
        val rects = letterRects()
        fun center(char: Char): Offset = rects.getValue(char).let {
            Offset((it.left + it.right) / 2f, (it.top + it.bottom) / 2f)
        }

        val points = mutableListOf<GesturePoint>()
        var time = 0L
        var previous = center(word[0])
        points += GesturePoint(previous.x, previous.y, time)
        for (letter in word.drop(1)) {
            val target = center(letter)
            val deltaX = target.x - previous.x
            val deltaY = target.y - previous.y
            val length = hypot(deltaX, deltaY)
            val normalX = -deltaY / length
            val normalY = deltaX / length
            for (step in 1..8) {
                val fraction = step / 8f
                val bow = sin(fraction * PI.toFloat()) * 0.03f * length
                time += 16
                points += GesturePoint(
                    previous.x + deltaX * fraction + normalX * bow,
                    previous.y + deltaY * fraction + normalY * bow,
                    time,
                )
            }
            previous = target
        }
        return points
    }

    private fun touchKey(bounds: Rect, output: K3StringOrDescriptor, id: String): TouchKey {
        return TouchKey(
            bounds = bounds,
            hitbox = bounds,
            display = output,
            attrs = K3Key(id = K3KeyId(id), output = output, origin = TestSourceFileRef),
            flick = null,
            isShiftKey = false,
            isRepeatable = false,
            isSuitableForSpaceBarDisplayOverride = false,
            isSuitableForSimplePopup = false,
            isSuitableForExtendedPopup = false,
            extendedPopupKeys = emptyList(),
            longPressKeyHint = null,
            longPressKeyHintPlacement = LongPressKeyHintPlacement.TOP_END,
            shouldOverrideDisplayWithMultiTapKeys = false,
            shouldHighlightPendingMultiTapKey = false,
            multiTapKeys = emptyList(),
        )
    }

    private object TestSourceFileRef : SourceFileRef {
        override fun toString() = "PointerTextGoldenTest"
    }

    private object NoOpInteraction : InteractionController {
        override val activeSystemTimingOptions: StateFlow<InteractionTimingOptions> =
            MutableStateFlow(TIMING)
        override val activeTimingOptions: StateFlow<InteractionTimingOptions> =
            MutableStateFlow(TIMING)
        override val activeFeedbackOptions: StateFlow<InteractionFeedbackOptions> =
            MutableStateFlow(InteractionFeedbackOptions.Fallback)

        override fun performAudioFeedback(kind: InteractionKind, output: K3StringOrDescriptor?) = Unit
        override fun performHapticFeedback(kind: InteractionKind, output: K3StringOrDescriptor?) = Unit

        override suspend fun showToast(
            text: String,
            type: InteractionController.ToastType,
        ): InteractionController.ToastHandle = object : InteractionController.ToastHandle {
            override suspend fun hide() = Unit
        }
    }

    private companion object {
        val SIZE = IntSize(1000, 1000)
        const val BACKSPACE = '\b'
        val keyboardKeys = HashMap<Char, TouchKey>()
        var preloaded = false
        val TIMING = InteractionTimingOptions(
            keyRepeatTimeout = 400.milliseconds,
            keyRepeatDelay = 50.milliseconds,
            longPressTimeout = 400.milliseconds,
            multiTapTimeout = 300.milliseconds,
            multiPressTimeout = 100.milliseconds,
            doubleTapTimeout = 300.milliseconds,
        )
    }
}

/** Minimal editor behind the IME: enough of [InputConnection] for text, composing and selection. */
private class RecordingInputConnection : InputConnection {
    val text = SpannableStringBuilder()
    val commits = mutableListOf<String>()
    private var selStart = 0
    private var selEnd = 0
    private var composingStart = -1
    private var composingEnd = -1

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence =
        text.subSequence((selStart - n).coerceAtLeast(0), selStart)

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence =
        text.subSequence(selEnd, (selEnd + n).coerceAtMost(text.length))

    override fun getSelectedText(flags: Int): CharSequence =
        if (selStart != selEnd) text.subSequence(minOf(selStart, selEnd), maxOf(selStart, selEnd)) else ""

    override fun getCursorCapsMode(reqModes: Int): Int = 0

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val start = (minOf(selStart, selEnd) - beforeLength).coerceAtLeast(0)
        val end = (maxOf(selStart, selEnd) + afterLength).coerceAtMost(text.length)
        if (start < end) text.delete(start, end)
        selStart = start
        selEnd = start
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
        deleteSurroundingText(beforeLength, afterLength)

    override fun setComposingText(t: CharSequence, newCursorPosition: Int): Boolean {
        replaceComposing(t, keepComposing = true)
        moveCursor(newCursorPosition)
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        composingStart = start.coerceIn(0, text.length)
        composingEnd = end.coerceIn(composingStart, text.length)
        return true
    }

    override fun finishComposingText(): Boolean {
        composingStart = -1
        composingEnd = -1
        return true
    }

    override fun commitText(t: CharSequence, newCursorPosition: Int): Boolean {
        commits += t.toString()
        replaceComposing(t, keepComposing = false)
        moveCursor(newCursorPosition)
        return true
    }

    override fun commitCompletion(completion: CompletionInfo?): Boolean = false
    override fun commitCorrection(correction: CorrectionInfo?): Boolean = false

    override fun setSelection(start: Int, end: Int): Boolean {
        selStart = start.coerceIn(0, text.length)
        selEnd = end.coerceIn(0, text.length)
        return true
    }

    override fun performEditorAction(editorAction: Int): Boolean = false
    override fun performContextMenuAction(id: Int): Boolean = false
    override fun beginBatchEdit(): Boolean = true
    override fun endBatchEdit(): Boolean = true
    override fun sendKeyEvent(event: KeyEvent?): Boolean = false
    override fun clearMetaKeyStates(states: Int): Boolean = false
    override fun reportFullscreenMode(enabled: Boolean): Boolean = false
    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = false
    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false
    override fun getHandler(): Handler? = null
    override fun closeConnection() = Unit
    override fun commitContent(contentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean = false

    private fun replaceComposing(t: CharSequence, keepComposing: Boolean) {
        val start = if (composingStart >= 0) composingStart else minOf(selStart, selEnd)
        val end = if (composingEnd >= 0) composingEnd else maxOf(selStart, selEnd)
        text.replace(start, end, t)
        if (keepComposing) {
            composingStart = start
            composingEnd = start + t.length
        } else {
            composingStart = -1
            composingEnd = -1
        }
        selStart = start + t.length
        selEnd = selStart
    }

    private fun moveCursor(newCursorPosition: Int) {
        val position = (selStart + (newCursorPosition - 1).coerceAtLeast(0)).coerceIn(0, text.length)
        selStart = position
        selEnd = position
    }
}
