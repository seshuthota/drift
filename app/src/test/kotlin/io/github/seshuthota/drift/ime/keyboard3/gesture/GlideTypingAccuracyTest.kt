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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Accuracy benchmark for the statistical glide classifier against the real word corpus
 * (`assets/ime/dict/data.json`). Glides are synthesized from the word corpus with deterministic
 * noise, so differences between runs come only from classifier changes.
 *
 * Seeds and shapes are fixed on purpose: the thresholds below are calibrated to the generated
 * traces and are regression guards, not a claim about real-device accuracy.
 */
class GlideTypingAccuracyTest : FunSpec({
    val dict = loadDictionary()
    val keys = qwertyKeys()
    val centers = keys.associate { it.code.toChar() to (it.centerX to it.centerY) }
    val classifier = StatisticalGlideTypingClassifier().apply {
        setWordData(dict.keys.toList()) { word -> (dict[word] ?: 0) / 255.0 }
        setLayout(keys)
    }
    // Sample the way people actually type: common words are much more likely.
    val commonWords = sampleWeighted(dict, count = 120, seed = 1234)

    fun measure(noise: Float, bowFraction: Float, seed: Int): Triple<Double, Double, Double> {
        val random = Random(seed)
        var top1 = 0
        var top3 = 0
        var top8 = 0
        for (word in commonWords) {
            classifier.clear()
            syntheticGesture(word, centers, random, noise, bowFraction).forEach {
                classifier.addGesturePoint(it)
            }
            val suggestions = classifier.getSuggestions(8)
            if (suggestions.getOrNull(0) == word) top1++
            if (word in suggestions.take(3)) top3++
            if (word in suggestions) top8++
        }
        val total = commonWords.size.toDouble()
        return Triple(top1 / total, top3 / total, top8 / total)
    }

    test("clean glide traces resolve the intended word") {
        val (top1, _, top8) = measure(noise = 0.005f, bowFraction = 0.05f, seed = 42)
        top1 shouldBeGreaterThanOrEqual 0.85
        top8 shouldBeGreaterThanOrEqual 0.98
    }

    test("noisy glide traces keep the intended word highly ranked") {
        val (top1, top3, top8) = measure(noise = 0.015f, bowFraction = 0.12f, seed = 43)
        top1 shouldBeGreaterThanOrEqual 0.65
        top3 shouldBeGreaterThanOrEqual 0.85
        top8 shouldBeGreaterThanOrEqual 0.92
    }
})

private fun qwertyKeys(): List<GlideKey> {
    val keys = mutableListOf<GlideKey>()
    fun add(code: Char, x: Float, y: Float) {
        keys += GlideKey(code.code, x, y, 0.1f, 0.1f)
    }
    val top = "qwertyuiop"
    val home = "asdfghjkl"
    val bottom = "zxcvbnm"
    top.forEachIndexed { i, c -> add(c, (i + 0.5f) / top.length, 0.1f) }
    home.forEachIndexed { i, c -> add(c, (i + 1.0f) / (home.length + 1), 0.2f) }
    bottom.forEachIndexed { i, c -> add(c, (i + 0.5f) / bottom.length, 0.3f) }
    return keys
}

private fun loadDictionary(): Map<String, Int> {
    val raw = File("src/main/assets/ime/dict/data.json").readText()
    return Json.parseToJsonElement(raw).jsonObject.mapValues { it.value.jsonPrimitive.int }
}

private fun sampleWeighted(dict: Map<String, Int>, count: Int, seed: Int): List<String> {
    val candidates = dict.entries.filter { (word, freq) ->
        freq >= 30 && word.length in 2..8 && word.all { it in 'a'..'z' }
    }
    val totalWeight = candidates.sumOf { it.value }
    val random = Random(seed)
    val sample = LinkedHashSet<String>()
    while (sample.size < count) {
        var pick = random.nextDouble(totalWeight.toDouble())
        for ((word, freq) in candidates) {
            pick -= freq
            if (pick <= 0) {
                sample += word
                break
            }
        }
    }
    return sample.toList()
}

private fun syntheticGesture(
    word: String,
    centers: Map<Char, Pair<Float, Float>>,
    random: Random,
    noise: Float,
    bowFraction: Float,
): List<GesturePoint> {
    fun jitter(x: Float, y: Float) =
        (x + random.nextFloat() * noise * 2 - noise) to (y + random.nextFloat() * noise * 2 - noise)

    val points = mutableListOf<GesturePoint>()
    var time = 0L
    val first = centers.getValue(word[0])
    val (startX, startY) = jitter(first.first, first.second)
    points += GesturePoint(startX, startY, time)
    var previousX = startX
    var previousY = startY
    for (letter in word.drop(1)) {
        val (targetX, targetY) = centers.getValue(letter)
        val deltaX = targetX - previousX
        val deltaY = targetY - previousY
        val length = hypot(deltaX, deltaY)
        val steps = (length / 0.005f).toInt().coerceAtLeast(4)
        val normalX = -deltaY / length
        val normalY = deltaX / length
        val bow = random.nextFloat() * bowFraction
        for (step in 1..steps) {
            val fraction = step.toFloat() / steps
            val bowOffset = sin(fraction * PI.toFloat()) * bow * length
            val (x, y) = jitter(
                previousX + deltaX * fraction + normalX * bowOffset,
                previousY + deltaY * fraction + normalY * bowOffset,
            )
            time += 8
            points += GesturePoint(x, y, time)
        }
        previousX = targetX
        previousY = targetY
    }
    return points
}
