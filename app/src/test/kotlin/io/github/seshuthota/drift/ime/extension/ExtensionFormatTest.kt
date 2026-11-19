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

package io.github.seshuthota.drift.ime.extension

import io.github.seshuthota.drift.lib.validate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class ExtensionFormatTest : FunSpec({
    context("extension id") {
        test("accepts dotted lowercase package names") {
            for (id in listOf("a", "dev.example.theme", "io.github.seshuthota.drift", "a1.b2_c", "a.1b")) {
                validate(ExtensionValidation.MetaId, id).isValid() shouldBe true
            }
        }

        test("rejects anything a package name cannot contain") {
            for (id in listOf("", " ", "1abc", "Abc", "a..b", "a.", ".a", "a-b", "a b", "a.b.", "a/b")) {
                validate(ExtensionValidation.MetaId, id).isInvalid() shouldBe true
            }
        }
    }

    context("maintainer string") {
        test("name only") {
            ExtensionMaintainer.from("Jane Doe") shouldBe ExtensionMaintainer("Jane Doe")
        }

        test("name with email") {
            ExtensionMaintainer.from("Jane Doe <jane@example.com>") shouldBe
                ExtensionMaintainer("Jane Doe", email = "jane@example.com")
        }

        test("name with url") {
            ExtensionMaintainer.from("Jane Doe (https://example.com)") shouldBe
                ExtensionMaintainer("Jane Doe", url = "https://example.com")
        }

        test("name with both, in that order") {
            ExtensionMaintainer.from("Jane Doe <jane@example.com> (https://example.com)") shouldBe
                ExtensionMaintainer("Jane Doe", email = "jane@example.com", url = "https://example.com")
        }

        test("round-trips the documented format") {
            for (text in listOf(
                "Jane Doe",
                "Jane Doe <jane@example.com>",
                "Jane Doe (https://example.com)",
                "Jane Doe <jane@example.com> (https://example.com)",
            )) {
                ExtensionMaintainer.from(text)?.toString() shouldBe text
            }
        }

        test("rejects a value with no name in front") {
            ExtensionMaintainer.from("").shouldBeNull()
            ExtensionMaintainer.from("   ").shouldBeNull()
            ExtensionMaintainer.from("<jane@example.com>").shouldBeNull()
            ExtensionMaintainer.from("(https://example.com)").shouldBeNull()
        }

        test("unparseable values survive as a raw name") {
            ExtensionMaintainer.fromOrTakeRaw("<broken") shouldBe ExtensionMaintainer("<broken")
        }
    }
})
