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

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.random.Random

class TypoCorrectorTest : FunSpec({
    val dict = loadDictionary()
    val corrector = TypoCorrector(dict)

    fun top(typed: String, n: Int = 3) = corrector.correct(typed, n).map { it.word }

    context("lookup") {
        test("dictionary words are found, others are not") {
            corrector.contains("the") shouldBe true
            corrector.contains("don't") shouldBe true
            corrector.contains("thw") shouldBe false
            corrector.size shouldBe dict.size
        }

        test("the word list is sorted and counts match the dictionary") {
            corrector.words shouldBe dict.keys.filter { it.isNotEmpty() }.sorted()
            corrector.frequencyOf("the") shouldBe dict.getValue("the")
            corrector.maxFrequency shouldBe dict.values.max()
        }
    }

    context("completions") {
        test("prefix completions are the most frequent words with that prefix") {
            val expected = dict.entries.filter { it.key.startsWith("hel") }
                .sortedByDescending { it.value }.take(3).map { it.key }
            corrector.completions("hel", 3) shouldBe expected
        }

        test("excluded words are skipped and an unknown prefix gives nothing") {
            val first = corrector.completions("hel", 1).single()
            corrector.completions("hel", 1, setOf(first)).single() shouldNotBe first
            corrector.completions("qqzx", 5) shouldBe emptyList()
        }
    }

    context("adding words") {
        test("withWords adds unknown words in sorted position with their count") {
            val updated = corrector.withWords(mapOf("zzdrift" to 100, "aadrift" to 50))
            updated.size shouldBe corrector.size + 2
            updated.contains("zzdrift") shouldBe true
            updated.frequencyOf("aadrift") shouldBe 50
            updated.words shouldBe updated.words.sorted()
            updated.contains("the") shouldBe true
            corrector.contains("zzdrift") shouldBe false
        }

        test("withWords keeps the same instance and counts when nothing is new") {
            corrector.withWords(mapOf("the" to 1)) shouldBeSameInstanceAs corrector
            corrector.withWords(emptyMap()) shouldBeSameInstanceAs corrector
        }

        test("a learned word can be corrected towards") {
            val updated = corrector.withWords(mapOf("drifty" to 1_000_000))
            updated.correct("drifyt", 3).map { it.word } shouldContain "drifty"
        }
    }

    context("common typing errors") {
        test("neighbouring key") { top("thw") shouldContain "the" }
        test("missed apostrophe") { top("dont", 1) shouldBe listOf("don't") }
        test("swapped letters") { top("teh") shouldContain "the" }
        test("missed doubled letter") { top("ocasion", 1) shouldBe listOf("occasion") }
        test("extra neighbouring key") { top("helllo") shouldContain "hello" }
        test("a correctly typed word ranks itself first") { top("house", 1) shouldBe listOf("house") }
    }

    context("suggestion order") {
        val corrections = listOf("the", "then")
        val completions = listOf("thwart", "thwarted")
        test("a word or word in progress leads with a completion, then alternates") {
            TypingSuggestions.merge(corrections, completions, leadWithCorrection = false, maxCount = 3) shouldBe
                listOf("thwart", "the", "thwarted")
        }
        test("a typo leads with corrections and drops duplicates") {
            TypingSuggestions.merge(corrections, listOf("then", "x"), leadWithCorrection = true, maxCount = 3) shouldBe
                listOf("the", "then", "x")
        }
        test("no corrections leaves completions alone") {
            TypingSuggestions.merge(emptyList(), completions, leadWithCorrection = false, maxCount = 3) shouldBe
                completions
        }
    }

    context("leading suggestion") {
        fun leads(typed: String, completions: List<String>) =
            corrector.shouldLeadWithCorrection(typed, corrector.correct(typed, 4).firstOrNull { it.word != typed }, completions)
        test("a typo leads with its correction over rare completions") {
            leads("thw", listOf("thwart", "thwarted")) shouldBe true
        }
        test("a word in progress leads with a common completion") {
            leads("hel", listOf("help", "hello")) shouldBe false
        }
        test("a missing apostrophe leads with the contraction") {
            leads("dont", listOf("dont")) shouldBe true
        }
        val common = loadVocab().toHashSet()
        test("an unknown misspelling leads with the intended word") {
            for (typo in listOf("recieve", "teh", "untill", "thier")) {
                val best = corrector.correct(typo, 4).first { it.word != typo }
                corrector.shouldLeadWithCorrection(typo, best, listOf(typo), isCommonWord = { it in common }) shouldBe true
            }
        }
        test("a common word is never a misspelling") {
            val best = corrector.correct("form", 4).first { it.word != "form" }
            corrector.isLikelyMisspelling("form", best) { it in common } shouldBe false
        }
        test("a real word is not displaced by a different word") {
            leads("masking", listOf("masking")) shouldBe false
        }
    }

    context("autocorrect") {
        fun auto(typed: String) = corrector.isAutoCorrectable(typed, corrector.correct(typed, 5))
        test("clear typos are corrected") {
            for (typo in listOf("thw", "becuase", "dont", "didnt", "recieve", "teh")) {
                withClue(typo) { auto(typo) shouldBe true }
            }
        }
        test("real words and ambiguous typos are left alone") {
            for (word in listOf("ill", "id", "were", "its", "well", "house", "masking", "pout", "hime")) {
                withClue(word) { auto(word) shouldBe false }
            }
        }
        test("words two or more edits from the correction are left alone") {
            // Each of these was autocorrected and undone on the phone (quality log, 2026-11-25).
            for (word in listOf("ofcourse", "opencode", "homelab", "vasthu", "saraswait", "elsebwe")) {
                withClue(word) { auto(word) shouldBe false }
            }
        }
        test("edit distance counts an adjacent swap as one edit") {
            TypoCorrector.editDistance("teh", "the") shouldBe 1
            TypoCorrector.editDistance("adress", "address") shouldBe 1
            TypoCorrector.editDistance("ofcourse", "course") shouldBe 2
            TypoCorrector.editDistance("homelab", "homeland") shouldBe 2
            TypoCorrector.editDistance("same", "same") shouldBe 0
        }
    }

    context("removed misspellings") {
        test("a spelling the word list dropped corrects to the real word") {
            val words = corrector.correct("teh", 4).map { it.word }
            words.first() shouldBe "the"
            (words.contains("teh")) shouldBe false
        }
    }

    context("language model") {
        test("context breaks a tie between equally close words") {
            val home = corrector.correct("hime", 2, lmLogProb = { w: String -> if (w == "home") -1f else -8f })
            home.first().word shouldBe "home"
        }
    }

    context("synthetic benchmark") {
        val words = loadVocab().filter { it in dict && it.length >= 3 && it.all { c -> c in 'a'..'z' || c == '\'' } }
        fun run(seed: Int, count: Int): Pair<Double, Double> {
            val random = Random(seed)
            var top1 = 0
            var top3 = 0
            var total = 0
            while (total < count) {
                val word = words[zipfIndex(random, words.size)]
                val typed = typo(word, random) ?: continue
                if (typed in dict) continue
                total++
                val result = top(typed)
                if (result.firstOrNull() == word) top1++
                if (word in result) top3++
            }
            return top1.toDouble() / count to top3.toDouble() / count
        }

        test("one error per word") {
            val (top1, top3) = run(seed = 7, count = 3000)
            println("TypoCorrector synthetic seed 7: top1=%.4f top3=%.4f".format(top1, top3))
            top1 shouldBeGreaterThanOrEqual 0.87
            top3 shouldBeGreaterThanOrEqual 0.95
        }

        test("correction is fast enough for every keystroke") {
            val random = Random(11)
            val inputs = List(500) { typo(words[zipfIndex(random, words.size)], random) ?: "word" }
            inputs.forEach { top(it) }
            val start = System.nanoTime()
            inputs.forEach { top(it) }
            val perWordMicros = (System.nanoTime() - start) / 1000 / inputs.size
            println("TypoCorrector mean latency: $perWordMicros µs/word")
            perWordMicros shouldBeLessThan 5_000L
        }
    }
})

private val QWERTY_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

private fun neighbours(c: Char): List<Char> {
    val centers = TypoCorrector.QWERTY_CENTERS
    val (x, y) = centers[c] ?: return emptyList()
    return centers.filter { (k, v) -> k != c && kotlin.math.hypot(v.first - x, v.second - y) <= 1.2f }.keys.toList()
}

private fun typo(word: String, random: Random): String? {
    val roll = random.nextDouble()
    val i = random.nextInt(word.length)
    val c = word[i]
    return when {
        roll < 0.08 -> if ('\'' in word) word.replace("'", "") else null
        roll < 0.58 -> neighbours(c).takeIf { it.isNotEmpty() }?.let { word.replaceRange(i, i + 1, it.random(random).toString()) }
        roll < 0.73 -> if (word.length > 3) word.removeRange(i, i + 1) else null
        roll < 0.88 -> {
            val extra = (neighbours(c) + c).random(random)
            word.substring(0, i + 1) + extra + word.substring(i + 1)
        }
        else -> if (i + 1 < word.length && word[i] != word[i + 1]) {
            word.substring(0, i) + word[i + 1] + word[i] + word.substring(i + 2)
        } else {
            null
        }
    }?.takeIf { it != word && it.all { ch -> ch == '\'' || QWERTY_ROWS.any { r -> ch in r } } }
}

private fun zipfIndex(random: Random, n: Int): Int {
    val rank = Math.pow(n.toDouble(), random.nextDouble()).toInt()
    return (rank - 1).coerceIn(0, n - 1)
}

private fun loadDictionary(): Map<String, Int> {
    val raw = File("src/main/assets/ime/dict/data.json").readText()
    return Json.parseToJsonElement(raw).jsonObject.mapValues { it.value.jsonPrimitive.int }
}

private fun loadVocab(): List<String> =
    File("src/main/assets/nwp/vocab.txt").readLines().drop(NwpText.N_SPECIAL).filter { it.isNotEmpty() }
