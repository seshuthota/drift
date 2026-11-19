/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.seshuthota.drift.ime.keyboard3.gesture

import io.kotest.core.spec.style.FunSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import kotlin.math.ln

class GlidePipelineSimulationTest : FunSpec({
    val path = System.getenv("DRIFT_FUTO_RAW")
    if (path.isNullOrBlank() || !File(path).exists()) {
        test("pipeline simulation (skipped: set DRIFT_FUTO_RAW)") { }
    } else {
        simulation(path)
    }
})

private fun FunSpec.simulation(path: String) {
    val maxRows = System.getenv("DRIFT_FUTO_ROWS")?.toIntOrNull() ?: 1000

    val width = 1440f
    val rowHeight = 138f
    val density = 3.375f
    val keyWidth = width / 10f
    val keys = phoneLayout(width, rowHeight)
    val dictionary = loadDictionary()
    val modelBytes = File("src/main/assets/swipe/ctc_swipe.onnx").readBytes()
    val classifier = NeuralGlideTypingClassifier { modelBytes }.apply {
        setWordData(dictionary.keys.toList(), logFrequency(dictionary))
        setLayout(keys)
    }

    fun config(spacing: Float) = GestureConfig(
        swipeDistancePx = 32f * density,
        swipeVelocityPxPerMs = 1900f * density / 1000f,
        glideEnabled = true,
        glideMinVelocityPxPerMs = 0.10f * density,
        glideMaxDurationMillis = 500L,
        glideMinPointSpacingFraction = spacing,
    )

    fun decode(points: List<GesturePoint>): List<String> {
        classifier.clear()
        points.forEach { classifier.addGesturePoint(it) }
        return classifier.getSuggestions(8)
    }

    val rows = File(path).useLines { lines ->
        lines.mapNotNull { line -> parseRow(line, width, rowHeight * 3f) }.take(maxRows).toList()
    }

    class Tally {
        var total = 0
        var glides = 0
        var swipes = 0
        var taps = 0
        var top1 = 0
        var top3 = 0
        val misses = mutableListOf<String>()
        fun line(name: String): String {
            val t = total.toDouble()
            return "%-34s n=%d glide=%.3f swipe=%.3f tap=%.3f top1=%.4f top3=%.4f".format(
                name, total, glides / t, swipes / t, taps / t, top1 / t, top3 / t,
            )
        }
    }

    fun score(tally: Tally, word: String, suggestions: List<String>) {
        if (suggestions.firstOrNull() == word) tally.top1++
        if (word in suggestions.take(3)) tally.top3++
    }

    fun recognize(samples: List<GesturePoint>, cfg: GestureConfig): Pair<List<GesturePoint>, GestureOutcome> {
        val recognizer = GestureRecognizer()
        val first = samples.first()
        recognizer.onDown(1L, first.x, first.y, first.timeMillis, cfg, keyWidth, glideEligible = true, swipeEligible = true)
        val streamed = mutableListOf<GesturePoint>()
        for (sample in samples.subList(1, samples.size - 1)) {
            for (signal in recognizer.onMove(1L, sample.x, sample.y, sample.timeMillis)) {
                if (signal is GestureSignal.GlidePoint) streamed += GesturePoint(signal.x, signal.y, 0L)
            }
        }
        val last = samples.last()
        return streamed to recognizer.onUp(1L, last.x, last.y, last.timeMillis)
    }

    test("raw traces through the recognizer at phone scale") {
        val direct = Tally()
        val variants = linkedMapOf(
            "shipping (streamed, spacing .25)" to Tally(),
            "release point kept (spacing .25)" to Tally(),
            "release point kept (spacing .10)" to Tally(),
            "120 Hz, release kept (spacing .25)" to Tally(),
        )
        for ((word, samples) in rows) {
            direct.total++
            direct.glides++
            score(direct, word, decode(samples))

            for ((name, tally) in variants) {
                val spacing = if ("spacing .10" in name) 0.10f else 0.25f
                val input = if (name.startsWith("120 Hz")) resample(samples, 1000f / 120f) else samples
                val (streamed, outcome) = recognize(input, config(spacing))
                tally.total++
                when (outcome) {
                    is GestureOutcome.Glide -> {
                        tally.glides++
                        val points = if (name.startsWith("shipping") && streamed.isNotEmpty()) streamed else outcome.points
                        val suggestions = decode(points)
                        score(tally, word, suggestions)
                        if (suggestions.firstOrNull() != word && tally.misses.size < 15) {
                            tally.misses += "$word->${suggestions.take(3)}"
                        }
                    }
                    is GestureOutcome.Swipe -> tally.swipes++
                    GestureOutcome.Tap -> tally.taps++
                }
            }
        }
        println("GlidePipelineSimulation rows=${rows.size}")
        println("GlidePipelineSimulation " + direct.line("direct (training path)"))
        for ((name, tally) in variants) {
            println("GlidePipelineSimulation " + tally.line(name))
        }
        for ((name, tally) in variants) {
            println("GlidePipelineSimulation misses[$name]: ${tally.misses.joinToString(" ")}")
        }
    }
}

private fun resample(samples: List<GesturePoint>, stepMs: Float): List<GesturePoint> {
    val out = mutableListOf(samples.first())
    val start = samples.first().timeMillis
    val end = samples.last().timeMillis
    var t = start + stepMs
    var i = 0
    while (t < end) {
        while (i < samples.size - 2 && samples[i + 1].timeMillis < t) i++
        val a = samples[i]
        val b = samples[i + 1]
        val span = (b.timeMillis - a.timeMillis).coerceAtLeast(1L)
        val f = ((t - a.timeMillis) / span).coerceIn(0f, 1f)
        out += GesturePoint(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, t.toLong())
        t += stepMs
    }
    out += samples.last()
    return out
}

private fun parseRow(line: String, width: Float, height: Float): Pair<String, List<GesturePoint>>? {
    val obj = Json.parseToJsonElement(line).jsonObject
    val word = obj.getValue("word").jsonPrimitive.content.lowercase()
    if (!Regex("^[a-z]{2,26}$").matches(word)) return null
    val data = obj.getValue("data").jsonArray
    if (data.size < 2) return null
    val t0 = data[0].jsonObject.getValue("t").jsonPrimitive.long
    val points = data.map { element ->
        val p = element.jsonObject
        GesturePoint(
            x = p.getValue("x").jsonPrimitive.double.toFloat() * width,
            y = p.getValue("y").jsonPrimitive.double.toFloat() * height,
            timeMillis = 1_000L + p.getValue("t").jsonPrimitive.long - t0,
        )
    }
    return word to points
}

private fun phoneLayout(width: Float, rowHeight: Float): List<GlideKey> {
    val keys = mutableListOf<GlideKey>()
    val rows = listOf(Triple("qwertyuiop", 0.5f, 0), Triple("asdfghjkl", 1.0f, 1), Triple("zxcvbnm", 2.0f, 2))
    for ((letters, offset, row) in rows) {
        letters.forEachIndexed { i, ch ->
            keys += GlideKey(
                code = ch.code,
                centerX = (i + offset) / 10f * width,
                centerY = (row + 0.5f) * rowHeight,
                width = width / 10f,
                height = rowHeight,
            )
        }
    }
    return keys
}

private fun loadDictionary(): Map<String, Int> {
    val raw = File("src/main/assets/ime/dict/data.json").readText()
    return Json.parseToJsonElement(raw).jsonObject.mapValues { it.value.jsonPrimitive.int }
}

private fun logFrequency(dict: Map<String, Int>): (String) -> Double {
    val denominator = ln(1.0 + (dict.values.maxOrNull() ?: 0)).coerceAtLeast(1.0)
    return { word ->
        val count = dict[word] ?: 0
        if (count <= 0) 0.0 else ln(1.0 + count) / denominator
    }
}
