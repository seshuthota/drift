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

class SharedActionsExpansionTest : FunSpec({
    context("while typing") {
        test("expands when nothing is suggested") {
            shouldExpandSharedActions(emptyList<Any>(), emptyList<Any>(), isSelection = false) shouldBe true
            shouldExpandSharedActions(null, null, isSelection = false) shouldBe true
        }

        test("collapses to show candidates") {
            shouldExpandSharedActions(listOf("hello"), null, isSelection = false) shouldBe false
        }

        test("collapses to show inline autofill suggestions") {
            shouldExpandSharedActions(null, listOf("autofill"), isSelection = false) shouldBe false
        }
    }

    context("with a selection") {
        test("expands even when candidates exist") {
            shouldExpandSharedActions(listOf("hello"), listOf("autofill"), isSelection = true) shouldBe true
        }
    }
})
