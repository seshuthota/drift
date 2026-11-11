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

package io.github.seshuthota.drift.ime.keyboard3.gesture

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ln

/**
 * Measures the shipped glide decoder on the device: ONNX session creation, trie construction and
 * per-glide decode latency. Run it on the target phone with
 *
 *     ./gradlew :app:connectedDebugAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.class=io.github.seshuthota.drift.ime.keyboard3.gesture.GlideDecodeLatencyTest
 *
 * Debug builds are not minified, so treat the numbers as an upper bound for release.
 */
@RunWith(AndroidJUnit4::class)
class GlideDecodeLatencyTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun decodeLatency() {
        val counts = loadDictionary()
        val keys = qwertyLayout()
        val frequency = logFrequency(counts)

        val coldStart = System.nanoTime()
        val classifier = NeuralGlideTypingClassifier(context)
        classifier.setWordData(counts.keys.toList(), frequency)
        classifier.setLayout(keys)
        val coldStartMs = (System.nanoTime() - coldStart) / 1_000_000.0

        val results = mutableListOf<String>()
        val tops = mutableListOf<List<String>>()
        results += "coldStartMs=%.1f words=%d".format(coldStartMs, counts.size)
        for (word in listOf("the", "world", "keyboard")) {
            val points = syntheticGesture(word, keys)
            repeat(5) {
                classifier.clear()
                points.forEach(classifier::addGesturePoint)
                classifier.getSuggestions(8)
            }
            val times = LongArray(30)
            var top: List<String> = emptyList()
            for (run in times.indices) {
                classifier.clear()
                points.forEach(classifier::addGesturePoint)
                val startedAt = System.nanoTime()
                top = classifier.getSuggestions(8)
                times[run] = System.nanoTime() - startedAt
            }
            times.sort()
            val mean = times.average() / 1_000_000.0
            val p50 = times[times.size / 2] / 1_000_000.0
            val p90 = times[(times.size * 9) / 10] / 1_000_000.0
            val line = "word=%s mean=%.1fms p50=%.1fms p90=%.1fms top=%s"
                .format(word, mean, p50, p90, top.take(3))
            results += line
            tops += top
            Log.i(TAG, line)
        }
        results.forEach { println("GlideDecodeLatency $it") }
        assertTrue("decoder returned no candidates", tops.all { it.isNotEmpty() })
    }

    private fun loadDictionary(): Map<String, Int> {
        val raw = context.assets.open("ime/dict/data.json").bufferedReader().use { it.readText() }
        val json = JSONObject(raw)
        return buildMap {
            for (key in json.keys()) put(key, json.getInt(key))
        }
    }

    private fun logFrequency(dict: Map<String, Int>): (String) -> Double {
        val denominator = ln(1.0 + (dict.values.maxOrNull() ?: 0)).coerceAtLeast(1.0)
        return { word ->
            val count = dict[word] ?: 0
            if (count <= 0) 0.0 else ln(1.0 + count) / denominator
        }
    }

    private fun qwertyLayout(): List<GlideKey> {
        val keys = mutableListOf<GlideKey>()
        val rows = listOf(
            Triple("qwertyuiop", 0.5f, 1f / 6f),
            Triple("asdfghjkl", 1.0f, 0.5f),
            Triple("zxcvbnm", 2.0f, 5f / 6f),
        )
        for ((letters, offset, y) in rows) {
            letters.forEachIndexed { i, ch ->
                keys += GlideKey(
                    code = ch.code,
                    centerX = (i + offset) / 10f,
                    centerY = y,
                    width = 0.1f,
                    height = 1f / 3f,
                )
            }
        }
        return keys
    }

    /** Straight segments through the key centers, 8 samples each, plus a deterministic bow. */
    private fun syntheticGesture(word: String, keys: List<GlideKey>): List<GesturePoint> {
        val centers = keys.associate { it.code.toChar() to (it.centerX to it.centerY) }
        val points = mutableListOf<GesturePoint>()
        var time = 0L
        var previous = centers.getValue(word[0])
        points += GesturePoint(previous.first, previous.second, time)
        for (letter in word.drop(1)) {
            val target = centers.getValue(letter)
            val dx = target.first - previous.first
            val dy = target.second - previous.second
            val length = kotlin.math.sqrt(dx * dx + dy * dy)
            val normalX = -dy / length
            val normalY = dx / length
            for (step in 1..8) {
                val fraction = step / 8f
                val bow = kotlin.math.sin(fraction * Math.PI.toFloat()) * 0.03f * length
                time += 16
                points += GesturePoint(
                    previous.first + dx * fraction + normalX * bow,
                    previous.second + dy * fraction + normalY * bow,
                    time,
                )
            }
            previous = target
        }
        return points
    }

    private companion object {
        const val TAG = "DriftGlideBench"
    }
}
