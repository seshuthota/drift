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

package io.github.seshuthota.drift.ime.nlp.latin

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class NwpTokenizerGoldenTest : FunSpec({
    val golden = Json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/nwp/tokenize_golden.json")) {
            "missing nwp/tokenize_golden.json"
        }.use { it.reader().readText() },
    ).jsonArray

    test("tokens match ml/nwp/tokenizer.py for every golden case") {
        for (case in golden) {
            val input = case.jsonObject.getValue("in").jsonPrimitive.content
            val expected = case.jsonObject.getValue("tokens").jsonArray.map { it.jsonPrimitive.content }
            NwpText.tokens(input, truncated = false) shouldBe expected
        }
    }
})
