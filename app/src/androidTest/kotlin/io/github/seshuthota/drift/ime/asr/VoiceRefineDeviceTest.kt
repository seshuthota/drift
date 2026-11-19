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

package io.github.seshuthota.drift.ime.asr

import android.text.InputType
import android.util.Log
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.seshuthota.drift.DriftApplication
import io.github.seshuthota.drift.ime.editor.DriftEditorInfo
import io.github.seshuthota.drift.ime.keyboard3.ImeEditor
import io.github.seshuthota.drift.ime.keyboard3.interaction.RecordingInputConnection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class VoiceRefineDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as DriftApplication

    @Test
    fun streamingAndOnePassBothTranscribeTheClip(): Unit = runBlocking {
        val manager = application.asrModelManager.value
        val spec = AsrModelRegistry.default()
        val installed = manager.installed(spec) ?: manager.install(spec).getOrThrow()
        val pcm = readWav("voice/two_sentences.wav")
        val engine = MoonshineEngine()
        try {
            engine.load(installed)
            engine.setContext("Meeting notes for Priyanka")
            engine.start()
            val finals = mutableListOf<String>()
            for (chunk in pcm.asList().chunked(1_600)) {
                engine.addAudio(chunk.toShortArray()).filterIsInstance<StreamEvent.Final>().mapTo(finals) { it.text }
            }
            engine.stop().filterIsInstance<StreamEvent.Final>().mapTo(finals) { it.text }
            val streamed = normalizeTranscript(finals.joinToString(" "))

            val startedAt = System.nanoTime()
            val refined = normalizeTranscript(engine.transcribeAll(pcm))
            val refineMs = (System.nanoTime() - startedAt) / 1_000_000
            Log.i(TAG, "streamed: \"$streamed\"")
            Log.i(TAG, "one-pass (${refineMs}ms): \"$refined\"")

            for (text in listOf(streamed, refined)) {
                val lower = text.lowercase()
                assertTrue("\"$text\"", "report" in lower && "friday" in lower && "monday" in lower)
            }
            engine.start()
            engine.addAudio(pcm.copyOf(16_000))
            engine.stop()
        } finally {
            engine.close()
        }
    }

    @Test
    fun theSwapOnlyHappensWhileTheDictatedTextIsBeforeTheCursor() {
        val connection = RecordingInputConnection()
        val editor = editorFor(connection, InputType.TYPE_CLASS_TEXT)
        connection.commitText("Hi. send the report to pre anka", 1)
        assertTrue(editor.replaceLiveText(" send the report to pre anka", " send the report to Priyanka"))
        assertEquals("Hi. send the report to Priyanka", connection.text.toString())

        connection.commitText(" thanks", 1)
        assertFalse(editor.replaceLiveText(" send the report to Priyanka", " anything"))
        assertEquals("Hi. send the report to Priyanka thanks", connection.text.toString())
    }

    @Test
    fun rawEditorsAreNeverSwapped() {
        val connection = RecordingInputConnection(raw = true)
        val editor = editorFor(connection, InputType.TYPE_NULL)
        connection.commitText("ls -la", 1)
        assertFalse(editor.replaceLiveText("ls -la", "ls -l"))
    }

    private fun editorFor(connection: RecordingInputConnection, inputType: Int): ImeEditor {
        val controller = application.imeController
        controller.updateStateBlocking {
            handleStartInputView(
                WeakReference(connection),
                DriftEditorInfo.wrap(
                    EditorInfo().apply {
                        this.inputType = inputType
                        initialSelStart = 0
                        initialSelEnd = 0
                    },
                ),
            )
        }
        return controller.snapshotState().editor
    }

    private fun readWav(path: String): ShortArray {
        val bytes = instrumentation.context.assets.open(path).use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = buffer.getInt(offset + 4)
            if (id == "data") {
                val samples = ShortArray(size / 2)
                buffer.position(offset + 8)
                buffer.asShortBuffer().get(samples)
                return samples
            }
            offset += 8 + size
        }
        error("no data chunk in $path")
    }

    private companion object {
        const val TAG = "VoiceRefineDeviceTest"
    }
}
