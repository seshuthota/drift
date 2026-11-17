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
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

/** Learns [text] word by word, as the keyboard does when each word is committed. */
private fun PersonalNgram.learnText(text: String) {
    for (match in Regex("""[a-z']+""").findAll(text)) {
        learn(text.substring(0, match.range.first), truncated = false, word = match.value)
    }
}

private fun sample() = PersonalNgram(file = null).apply {
    learnText("on my way home. see you soon")
    learnText("on my way now, see you there")
    learnText("i am on my way")
}

class PersonalNgramTest : FunSpec({
    test("probabilities match the Python model that was evaluated (ml/nwp/personal_eval.py)") {
        val model = sample()
        fun p(context: String, word: String) = model.prob(model.historyOf(context, truncated = false), word)
        p("i am on my", "way") shouldBe (0.9473684211 plusOrMinus 1e-9)
        p("see you", "soon") shouldBe (0.3881578947 plusOrMinus 1e-9)
        p("see you", "there") shouldBe (0.3881578947 plusOrMinus 1e-9)
        p("hello", "way") shouldBe (0.1578947368 plusOrMinus 1e-9)
        p("", "on") shouldBe (0.4371643394 plusOrMinus 1e-9)
    }

    test("history stays inside the sentence and skips commas") {
        val model = PersonalNgram(file = null)
        model.historyOf("Hello there, how", truncated = false) shouldBe PersonalNgram.History("there", "how")
        model.historyOf("Done. ", truncated = false) shouldBe PersonalNgram.History("<s>", "<s>")
        model.historyOf("", truncated = false) shouldBe PersonalNgram.History("<s>", "<s>")
        model.historyOf("cut off text", truncated = true) shouldBe PersonalNgram.History("off", "text")
    }

    test("candidates follow the context, most frequent first, and respect the prefix") {
        val model = sample()
        val history = model.historyOf("see you", truncated = false)
        model.candidates(history, "", 5) shouldContainExactly listOf("soon", "there")
        model.candidates(history, "th", 5) shouldContainExactly listOf("there")
    }

    test("the weight grows with evidence for the previous word and is zero before learning") {
        val empty = PersonalNgram(file = null)
        empty.alpha(empty.historyOf("on my", truncated = false)) shouldBe 0.0
        val model = sample()
        val strong = model.alpha(model.historyOf("on my", truncated = false))
        strong shouldBe (PersonalNgram.BETA * 3 / (3 + PersonalNgram.K) plusOrMinus 1e-9)
        strong shouldBeGreaterThan model.alpha(model.historyOf("hello", truncated = false))
    }

    test("unlearning a corrected word takes it back completely") {
        val model = PersonalNgram(file = null)
        model.learn("see you ", truncated = false, word = "soon")
        model.learn("see you ", truncated = false, word = "son")
        model.unlearn("see you ", truncated = false, word = "son")
        model.countOf("son") shouldBe 0
        model.candidates(model.historyOf("see you ", truncated = false), "", 5) shouldContainExactly listOf("soon")
        model.unlearn("see you ", truncated = false, word = "never")
        model.countOf("soon") shouldBe 1
    }

    test("words with digits or symbols are not learned") {
        val model = PersonalNgram(file = null)
        model.learn("", truncated = false, word = "r2d2")
        model.learn("", truncated = false, word = "@you")
        model.countOf("r2d2") shouldBe 0
        model.alpha(model.historyOf("", truncated = false)) shouldBe 0.0
    }

    test("the table survives a save and reload") {
        val dir = Files.createTempDirectory("ngram").toFile()
        val file = File(dir, "personal/ngram.tsv")
        sample().let { model ->
            val written = PersonalNgram(file)
            written.learnText("on my way home. see you soon")
            written.learnText("on my way now, see you there")
            written.learnText("i am on my way")
            written.flush()
            val reloaded = PersonalNgram(file)
            for ((context, word) in listOf("i am on my" to "way", "see you" to "soon", "" to "on")) {
                reloaded.prob(reloaded.historyOf(context, false), word) shouldBe
                    (model.prob(model.historyOf(context, false), word) plusOrMinus 1e-12)
            }
        }
        dir.deleteRecursively()
    }

    test("nothing is read, learned or written while storage is locked") {
        val dir = Files.createTempDirectory("ngram").toFile()
        val file = File(dir, "ngram.tsv")
        PersonalNgram(file).apply {
            learn("see you ", truncated = false, word = "soon")
            flush()
        }
        var unlocked = false
        val model = PersonalNgram(file) { unlocked }
        model.learn("see you ", truncated = false, word = "later")
        model.countOf("soon") shouldBe 0
        model.flush()
        unlocked = true
        model.countOf("soon") shouldBe 1
        model.countOf("later") shouldBe 0
        dir.deleteRecursively()
    }
})
