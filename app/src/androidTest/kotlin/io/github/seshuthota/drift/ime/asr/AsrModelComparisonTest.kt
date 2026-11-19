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

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.seshuthota.drift.DriftApplication
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class AsrModelComparisonTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as DriftApplication

    @Test
    fun compareModelsOnTheSameClips(): Unit = runBlocking {
        val folder = File(application.filesDir, "asr-compare").apply { mkdirs() }
        val clips = folder.listFiles { file -> file.extension == "wav" }.orEmpty().sortedBy { it.name }
            .map { it.name to readWav(it.readBytes()) } +
            listOf("bundled/two_sentences.wav" to readWav(asset("voice/two_sentences.wav")))
        val manager = application.asrModelManager.value
        val results = JSONArray()
        for (spec in AsrModelRegistry.models) {
            val installed = manager.installed(spec) ?: manager.install(spec).getOrThrow()
            val engine = MoonshineEngine()
            try {
                val heapBefore = Debug.getNativeHeapAllocatedSize()
                val loadStart = System.nanoTime()
                engine.load(installed)
                val loadMs = (System.nanoTime() - loadStart) / 1_000_000
                var peakHeap = Debug.getNativeHeapAllocatedSize()
                // Total process memory too: ONNX Runtime may map model files, which the native heap does not count.
                var peakPssKb = Debug.getPss()
                for ((name, raw) in clips) {
                    val gain = AutoGain()
                    val chunks = raw.asList().chunked(CHUNK).map { gain.process(it.toShortArray()) }
                    val pcm = ShortArray(raw.size).also { out ->
                        var offset = 0
                        for (chunk in chunks) {
                            chunk.copyInto(out, offset)
                            offset += chunk.size
                        }
                    }
                    engine.setContext("")
                    engine.start()
                    val streamStart = System.nanoTime()
                    val finals = mutableListOf<String>()
                    for (chunk in chunks) {
                        engine.addAudio(chunk).filterIsInstance<StreamEvent.Final>()
                            .mapTo(finals) { it.text }
                    }
                    engine.stop().filterIsInstance<StreamEvent.Final>().mapTo(finals) { it.text }
                    val streamMs = (System.nanoTime() - streamStart) / 1_000_000
                    peakHeap = maxOf(peakHeap, Debug.getNativeHeapAllocatedSize())
                    val onePassStart = System.nanoTime()
                    val onePass = normalizeTranscript(engine.transcribeAll(pcm))
                    val onePassMs = (System.nanoTime() - onePassStart) / 1_000_000
                    peakHeap = maxOf(peakHeap, Debug.getNativeHeapAllocatedSize())
                    peakPssKb = maxOf(peakPssKb, Debug.getPss())
                    val streamed = normalizeTranscript(finals.joinToString(" "))
                    val audioMs = pcm.size * 1_000L / AudioRecorder.SAMPLE_RATE
                    Log.i(TAG, "${spec.id} $name stream ${streamMs}ms one-pass ${onePassMs}ms: \"$streamed\" | \"$onePass\"")
                    results.put(
                        JSONObject().put("model", spec.id).put("clip", name).put("audio_ms", audioMs)
                            .put("load_ms", loadMs).put("stream_ms", streamMs).put("one_pass_ms", onePassMs)
                            .put("streamed", streamed).put("one_pass", onePass)
                            .put("native_mb", (peakHeap - heapBefore) / 1_048_576.0)
                            .put("pss_mb", peakPssKb / 1_024.0),
                    )
                }
            } finally {
                engine.close()
            }
        }
        File(folder, "results.json").writeText(results.toString(2))
        assertTrue(results.length() >= AsrModelRegistry.models.size)
    }

    private fun asset(path: String): ByteArray = instrumentation.context.assets.open(path).use { it.readBytes() }

    private fun readWav(bytes: ByteArray): ShortArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = buffer.getInt(offset + 4)
            if (id == "data") {
                val samples = ShortArray(minOf(size, bytes.size - offset - 8) / 2)
                buffer.position(offset + 8)
                buffer.asShortBuffer().get(samples)
                return samples
            }
            offset += 8 + size
        }
        error("no data chunk")
    }

    private companion object {
        const val TAG = "AsrModelComparison"

        const val CHUNK = 1_600
    }
}
