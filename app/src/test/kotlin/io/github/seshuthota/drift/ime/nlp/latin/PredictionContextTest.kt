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

class PredictionContextTest : FunSpec({
    test("an empty field has no prediction context") {
        hasPredictionContext("") shouldBe false
    }

    test("whitespace and new lines alone are no context") {
        hasPredictionContext("  \n ") shouldBe false
    }

    test("a typed letter or word is context") {
        hasPredictionContext("H") shouldBe true
        hasPredictionContext("Hello ") shouldBe true
    }
})
