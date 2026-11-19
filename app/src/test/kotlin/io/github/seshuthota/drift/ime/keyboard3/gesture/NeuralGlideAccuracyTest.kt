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

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import kotlin.math.ln

class NeuralGlideAccuracyTest : FunSpec({
    val fixture = loadFixture()
    val dictionary = loadDictionary()
    val keys = qwertyLayout()
    val modelBytes = File("src/main/assets/swipe/ctc_swipe.onnx").readBytes()
    val classifier = NeuralGlideTypingClassifier { modelBytes }.apply {
        setWordData(dictionary.keys.toList(), logFrequency(dictionary))
        setLayout(keys)
    }
    val bounds = letterBounds(keys)

    fun decode(row: Map<String, Any>): List<String> {
        @Suppress("UNCHECKED_CAST")
        val features = row["features"] as List<List<Double>>
        classifier.clear()
        for (i in features[0].indices) {
            classifier.addGesturePoint(
                GesturePoint(
                    x = bounds[0] + features[0][i].toFloat() * bounds[1],
                    y = bounds[2] + features[1][i].toFloat() * bounds[3],
                    timeMillis = i * 8L,
                ),
            )
        }
        return classifier.getSuggestions(8)
    }

    @Suppress("UNCHECKED_CAST")
    val rows = fixture["rows"] as List<Map<String, Any>>

    test("the fixture matches the shipped model") {
        val digest = MessageDigest.getInstance("SHA-256").digest(modelBytes)
        val sha = digest.joinToString("") { "%02x".format(it) }
        sha shouldBe fixture["model_sha256"]
    }

    test("the shipped decoder keeps its fixture accuracy") {
        var top1 = 0
        var top3 = 0
        for (row in rows) {
            val suggestions = decode(row)
            val word = row["word"] as String
            if (suggestions.firstOrNull() == word) top1++
            if (word in suggestions.take(3)) top3++
        }
        val total = rows.size.toDouble()
        println("NeuralGlideAccuracy kotlin: top1=${top1 / total} top3=${top3 / total}")
        top1 / total shouldBeGreaterThanOrEqual 0.90
        top3 / total shouldBeGreaterThanOrEqual 0.97
    }

    test("the Kotlin decoder agrees with the Python evaluation") {
        var sameTop1 = 0
        var sameTop3 = 0
        val mismatches = mutableListOf<String>()
        for (row in rows) {
            @Suppress("UNCHECKED_CAST")
            val expected = row["expected"] as List<String>
            val suggestions = decode(row)
            if (suggestions.firstOrNull() == expected.firstOrNull()) sameTop1++
            if (expected.isNotEmpty() && expected[0] in suggestions.take(3)) sameTop3++
            if (suggestions.firstOrNull() != expected.firstOrNull()) {
                mismatches += "${row["word"]} kotlin=${suggestions.take(3)} python=${expected.take(3)}"
            }
        }
        val total = rows.size.toDouble()
        println("NeuralGlideAccuracy parity: top1=${sameTop1 / total} top3=${sameTop3 / total}")
        withClue(mismatches.take(5).joinToString("; ")) {
            sameTop1 / total shouldBeGreaterThanOrEqual 0.97
            sameTop3 / total shouldBeGreaterThanOrEqual 0.99
        }
    }
})

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

private fun letterBounds(keys: List<GlideKey>): FloatArray {
    var minX = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (key in keys) {
        minX = minOf(minX, key.centerX - key.width / 2f)
        maxX = maxOf(maxX, key.centerX + key.width / 2f)
        minY = minOf(minY, key.centerY - key.height / 2f)
        maxY = maxOf(maxY, key.centerY + key.height / 2f)
    }
    return floatArrayOf(minX, maxX - minX, minY, maxY - minY)
}

private fun loadFixture(): Map<String, Any> {
    val raw = File("src/test/resources/glide/neural_dev200.json").readText()
    val root = Json.parseToJsonElement(raw).jsonObject
    fun values(key: String) = root.getValue(key).jsonPrimitive.content
    return mapOf(
        "model_sha256" to values("model_sha256"),
        "rows" to root.getValue("rows").jsonArray.map { element ->
            val row = element.jsonObject
            mapOf(
                "word" to row.getValue("word").jsonPrimitive.content,
                "features" to row.getValue("features").jsonArray.map { axis ->
                    axis.jsonArray.map { it.jsonPrimitive.content.toDouble() }
                },
                "expected" to row.getValue("expected").jsonArray.map { it.jsonPrimitive.content },
            )
        },
    )
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
