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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream

/**
 * End-to-end check of the Rust/JNI whisper.cpp engine. Needs the model on the device; the test is skipped
 * otherwise:
 *
 *     adb push ggml-tiny-q5_1.bin /sdcard/Android/data/<applicationId>/files/asr-test/
 */
@RunWith(AndroidJUnit4::class)
class WhisperCppEngineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun transcribesTheJfkSample() {
        val directory = File(context.getExternalFilesDir(null), "asr-test")
        val spec = AsrModelRegistry.default()
        val model = File(directory, spec.files.single().name)
        assumeTrue("model missing, push it to ${model.path}", model.isFile)
        val pcm = readPcm16Mono(instrumentation.context.assets.open("jfk.wav"))
        val engine = WhisperCppEngine()
        try {
            runBlocking {
                engine.load(InstalledAsrModel(spec, directory))
                val result = engine.transcribe(pcm, AudioRecorder.SAMPLE_RATE)
                assertTrue("unexpected transcript: '${result.text}'", result.text.contains("fellow Americans", ignoreCase = true))
                assertTrue("inference time was not measured", result.inferenceMs > 0)
                assertTrue("audio length was not measured", result.audioMs in 10_000..12_000)
            }
        } finally {
            engine.close()
        }
    }
}

/** Minimal RIFF reader for the 16 kHz mono PCM16 fixture. */
private fun readPcm16Mono(input: InputStream): ShortArray {
    val bytes = input.use { it.readBytes() }
    fun intAt(offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }
    fun shortAt(offset: Int): Int = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAV file" }
    var offset = 12
    var channels = 0
    var sampleRate = 0
    var bits = 0
    while (offset + 8 <= bytes.size) {
        val id = String(bytes, offset, 4)
        val size = intAt(offset + 4)
        val body = offset + 8
        when (id) {
            "fmt " -> {
                channels = shortAt(body + 2)
                sampleRate = intAt(body + 4)
                bits = shortAt(body + 14)
            }
            "data" -> {
                require(channels == 1 && sampleRate == AudioRecorder.SAMPLE_RATE && bits == 16) {
                    "expected 16 kHz mono PCM16, got $sampleRate Hz / $channels ch / $bits bit"
                }
                val samples = ShortArray(size / 2)
                for (index in samples.indices) {
                    samples[index] = shortAt(body + index * 2).toShort()
                }
                return samples
            }
        }
        offset = body + size + (size and 1)
    }
    error("WAV file has no data chunk")
}
