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
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class StatisticalGlideTypingClassifierTest : FunSpec({
    // A tiny three-key keyboard: "a" at (0.1, 0.1), "b" at (0.5, 0.5), "c" at (0.9, 0.1).
    val keys = listOf(
        GlideKey(code = 'a'.code, centerX = 0.1f, centerY = 0.1f, width = 0.2f, height = 1f),
        GlideKey(code = 'b'.code, centerX = 0.5f, centerY = 0.5f, width = 0.2f, height = 1f),
        GlideKey(code = 'c'.code, centerX = 0.9f, centerY = 0.1f, width = 0.2f, height = 1f),
    )

    fun classifier(words: List<String>): StatisticalGlideTypingClassifier {
        return StatisticalGlideTypingClassifier().apply {
            setWordData(words) { 0.0 }
            setLayout(keys)
        }
    }

    fun StatisticalGlideTypingClassifier.glideAlong(points: List<Pair<Float, Float>>) {
        points.forEachIndexed { index, (x, y) -> addGesturePoint(GesturePoint(x, y, index.toLong())) }
    }

    test("a gesture through the key centers resolves the matching word") {
        val classifier = classifier(listOf("abc", "acc"))
        classifier.glideAlong(listOf(0.1f to 0.1f, 0.3f to 0.3f, 0.5f to 0.5f, 0.7f to 0.3f, 0.9f to 0.1f))

        val suggestions = classifier.getSuggestions(2)

        suggestions shouldHaveSize 2
        suggestions.first() shouldBe "abc"
    }

    test("a bowed gesture still resolves the closest path") {
        val classifier = classifier(listOf("ab", "ac"))
        classifier.glideAlong(listOf(0.1f to 0.5f, 0.5f to 0.62f, 0.9f to 0.5f))

        classifier.getSuggestions(2).first() shouldBe "ac"
    }

    test("clearing removes the gesture but keeps the words") {
        val classifier = classifier(listOf("abc"))
        classifier.glideAlong(listOf(0.1f to 0.1f, 0.5f to 0.5f, 0.9f to 0.1f))
        classifier.getSuggestions(1) shouldBe listOf("abc")

        classifier.clear()

        classifier.getSuggestions(1) shouldBe emptyList()
    }

    test("repeated letters stay reachable through loop gestures") {
        val classifier = classifier(listOf("aaa", "abc"))
        classifier.glideAlong(listOf(0.1f to 0.1f, 0.2f to 0.05f, 0.05f to 0.2f, 0.2f to 0.05f, 0.05f to 0.2f))

        classifier.getSuggestions(2).shouldContain("aaa")
    }
})
