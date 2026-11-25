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

package io.github.seshuthota.drift.ime.nlp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class GlideRowTest : FunSpec({
    val alternatives = listOf("this", "thus", "his").map { GlideSuggestionCandidate(it, replacesText = "this ") }
    val predictions = listOf("is", "was").map { GlideSuggestionCandidate(it) }

    test("the best differing alternative leads, marked, followed by the predictions") {
        val row = mergeGlideRow(alternatives, predictions)
        row.map { it.text.toString() } shouldBe listOf("thus", "is", "was")
        row[0].icon shouldNotBe null
        (row[0] as GlideSuggestionCandidate).replacesText shouldBe "this "
    }

    test("without predictions the row keeps all alternatives") {
        mergeGlideRow(alternatives, emptyList()) shouldBe alternatives
    }

    test("without a differing alternative only predictions remain") {
        mergeGlideRow(alternatives.take(1), predictions) shouldBe predictions
    }
})
