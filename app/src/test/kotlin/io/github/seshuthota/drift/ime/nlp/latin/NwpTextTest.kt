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
import java.io.File

class NwpTextTest : FunSpec({
    // The shipped model vocabulary (unit tests run with the :app module as working directory).
    val vocab = File("src/main/assets/nwp/vocab.txt").readLines().filter { it.isNotEmpty() }
    val idOf = vocab.withIndex().filter { it.index >= NwpText.N_SPECIAL }.associate { it.value to it.index }
    fun ids(text: String, truncated: Boolean = false, max: Int = 64) =
        NwpText.contextIds(text, { idOf[it] }, truncated, max).toList()

    context("contextIds matches ml/nwp/prepare.py") {
        // Expected ids generated with prepare.tokenize() (minus the trailing <s> it adds at document end).
        test("commas, sentence ends and contractions") {
            ids("Hello, how are you? I'm fine") shouldBe listOf(1, 3841, 2, 68, 19, 13, 1, 151, 969)
        }
        test("curly apostrophe, digits, ellipsis, newline, spaced comma") {
            ids("so  it’s 2day...\nOK , then") shouldBe listOf(1, 45, 72, 0, 1, 1918, 2, 93)
        }
        test("trailing period becomes a boundary") {
            ids("the end.") shouldBe listOf(1, 3, 192, 1)
        }
    }

    context("contextIds edge cases") {
        test("empty field is a sentence start") {
            ids("") shouldBe listOf(NwpText.BOS)
        }
        test("truncated text drops the first (possibly cut) token and the leading boundary") {
            ids("llo how are you", truncated = true) shouldBe listOf(68, 19, 13)
        }
        test("keeps only the last maxTokens ids") {
            ids("the end", max = 1) shouldBe listOf(192)
        }
        test("unknown words map to unk") {
            ids("zzqx") shouldBe listOf(NwpText.BOS, NwpText.UNK)
        }
    }

    context("partialWord") {
        test("letters and apostrophes before the cursor") {
            NwpText.partialWord("I said don") shouldBe "don"
            NwpText.partialWord("I said don'") shouldBe "don'"
        }
        test("nothing after a space or punctuation") {
            NwpText.partialWord("hello ") shouldBe ""
            NwpText.partialWord("hello.") shouldBe ""
        }
    }

    context("isInlineAutocomplete") {
        test("browser autocompletion after a partially typed word") {
            NwpText.isInlineAutocomplete("this is", textSelected = " the end", textAfter = "") shouldBe true
            NwpText.isInlineAutocomplete("goo", textSelected = "gle.com", textAfter = "") shouldBe true
        }
        test("a long-pressed word is a user selection") {
            NwpText.isInlineAutocomplete("this is ", textSelected = "great", textAfter = "") shouldBe false
        }
        test("a selection with text after it is a user selection") {
            NwpText.isInlineAutocomplete("this is", textSelected = " gre", textAfter = "at") shouldBe false
        }
        test("no selection is not an autocompletion") {
            NwpText.isInlineAutocomplete("this is", textSelected = "", textAfter = "") shouldBe false
        }
    }

    context("isSentenceStart") {
        test("field start and after sentence punctuation") {
            NwpText.isSentenceStart("") shouldBe true
            NwpText.isSentenceStart("Done. ") shouldBe true
            NwpText.isSentenceStart("Really?") shouldBe true
            NwpText.isSentenceStart("line\n") shouldBe true
        }
        test("mid-sentence") {
            NwpText.isSentenceStart("I want ") shouldBe false
            NwpText.isSentenceStart("well, ") shouldBe false
        }
    }

    context("displayForm") {
        test("pronoun I is always capitalized") {
            NwpText.displayForm("i'm", prefix = "", sentenceStart = false) shouldBe "I'm"
            NwpText.displayForm("i", prefix = "", sentenceStart = false) shouldBe "I"
        }
        test("follows the case of the typed prefix") {
            NwpText.displayForm("hello", prefix = "He", sentenceStart = false) shouldBe "Hello"
            NwpText.displayForm("hello", prefix = "HE", sentenceStart = false) shouldBe "HELLO"
            NwpText.displayForm("hello", prefix = "he", sentenceStart = true) shouldBe "hello"
        }
        test("capitalizes predictions at a sentence start") {
            NwpText.displayForm("the", prefix = "", sentenceStart = true) shouldBe "The"
            NwpText.displayForm("the", prefix = "", sentenceStart = false) shouldBe "the"
        }
    }
})
