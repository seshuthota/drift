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

package io.github.seshuthota.drift.ime.keyboard3

import io.github.seshuthota.drift.ime.keyboard3.ImeController.Companion.isSurroundingTextTrustworthy
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.k3lp.runtime.K3SurroundingText
import org.k3lp.runtime.K3TextRange

class SurroundingTextTrustTest : FunSpec({
    fun text(before: String, selected: String = "", after: String = "") =
        K3SurroundingText(textBefore = before, textSelected = selected, textAfter = after)

    test("plain text that ends at the cursor is trusted") {
        isSurroundingTextTrustworthy(K3TextRange(5, 5), text("hello")) shouldBe true
        isSurroundingTextTrustworthy(K3TextRange(80, 80), text("only the last fifty characters")) shouldBe true
        isSurroundingTextTrustworthy(K3TextRange(0, 0), text("", after = "rest")) shouldBe true
        isSurroundingTextTrustworthy(K3TextRange(2, 5), text("he", selected = "llo")) shouldBe true
    }

    test("ChatGPT's link chip, read as on-screen text, is not trusted") {
        // Stored text: "https://example.com/abc" (23 chars), cursor reported at 23.
        val chip = "⁦￼ https://example.com/a…⁩"
        isSurroundingTextTrustworthy(K3TextRange(23, 23), text(chip)) shouldBe false
    }

    test("text longer than the cursor position is not trusted") {
        isSurroundingTextTrustworthy(K3TextRange(3, 3), text("hello")) shouldBe false
        isSurroundingTextTrustworthy(K3TextRange(2, 3), text("he", selected = "llo")) shouldBe false
    }

    test("an object placeholder anywhere around the cursor is not trusted") {
        // A long link shown truncated is shorter on screen than stored, so only the placeholder gives it away.
        isSurroundingTextTrustworthy(K3TextRange(60, 60), text("￼ https://a.co…")) shouldBe false
        isSurroundingTextTrustworthy(K3TextRange(0, 0), text("", after = "￼ link")) shouldBe false
    }

    test("an unknown cursor position leaves the text trusted") {
        isSurroundingTextTrustworthy(K3TextRange(-1, -1), text("")) shouldBe true
    }
})
