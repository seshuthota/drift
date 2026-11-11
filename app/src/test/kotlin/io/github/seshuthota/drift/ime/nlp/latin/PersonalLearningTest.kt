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

class PersonalLearningTest : FunSpec({
    test("a word is learned on the third commit, and an undo learns it at once") {
        val learning = PersonalLearning()
        val key = PersonalLearning.keyOf("Sesh")
        key shouldBe "sesh"
        learning.observe(key!!, immediate = false) shouldBe null
        learning.observe(key, immediate = false) shouldBe null
        learning.observe(key, immediate = false) shouldBe "sesh"

        val undone = PersonalLearning()
        undone.observe("teh", immediate = false) shouldBe null
        undone.observe("teh", immediate = true) shouldBe "teh"
    }

    test("numbers, single letters, and surrounding punctuation are not words") {
        PersonalLearning.keyOf("a") shouldBe null
        PersonalLearning.keyOf("2020") shouldBe null
        PersonalLearning.keyOf("hello!") shouldBe null
        PersonalLearning.keyOf("'ok'") shouldBe "ok"
        PersonalLearning.keyOf("don't") shouldBe "don't"
    }
})
