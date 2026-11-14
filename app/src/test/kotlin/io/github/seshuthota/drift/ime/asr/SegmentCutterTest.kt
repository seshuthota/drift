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


package io.github.seshuthota.drift.ime.asr

import io.github.seshuthota.drift.ime.asr.SegmentCutter.Action
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SegmentCutterTest : FunSpec({
    /** Feeds [ms] of [db] in 100 ms chunks and returns each non-KEEP action with the ms (within this call) it happened at. */
    fun SegmentCutter.feed(db: Float, ms: Long): List<Pair<Action, Long>> {
        val out = mutableListOf<Pair<Action, Long>>()
        for (i in 1..(ms / 100)) {
            val action = onChunk(db, 100)
            if (action != Action.KEEP) out += action to i * 100
        }
        return out
    }

    val speech = -25f
    val quiet = -65f

    test("does not cut speech before the minimum length") {
        SegmentCutter().feed(speech, 3_900) shouldBe emptyList()
    }

    test("cuts at a pause once the minimum length is buffered") {
        val c = SegmentCutter()
        c.feed(speech, 4_000) shouldBe emptyList()
        c.feed(quiet, 600) shouldBe listOf(Action.CUT to 600L)
        c.lastSpeechMs shouldBe 4_000L
    }

    test("a pause before the minimum length does not cut speech") {
        val c = SegmentCutter()
        c.feed(speech, 1_000)
        c.feed(quiet, 1_000) shouldBe emptyList()
        c.hasPendingSpeech shouldBe true
    }

    test("continuous speech is cut at the maximum length") {
        SegmentCutter().feed(speech, 50_000).map { it.first } shouldBe listOf(Action.CUT, Action.CUT)
    }

    test("silence is dropped, never cut") {
        val actions = SegmentCutter().feed(quiet, 10_000)
        actions.all { it.first == Action.DROP } shouldBe true
        actions.first().second shouldBe 600L
    }

    test("a click shorter than the minimum speech is dropped with the silence around it") {
        val c = SegmentCutter()
        c.feed(quiet, 300)
        c.feed(speech, 200)
        c.feed(quiet, 600) shouldBe listOf(Action.DROP to 600L)
    }

    test("leading silence is dropped so the piece starts near the speech") {
        val c = SegmentCutter()
        c.feed(quiet, 2_000).map { it.first }.toSet() shouldBe setOf(Action.DROP)
        c.feed(speech, 4_000) shouldBe emptyList()
        c.feed(quiet, 600) shouldBe listOf(Action.CUT to 600L)
    }

    test("a steady loud room is learned as background, so pauses in speech still cut") {
        val c = SegmentCutter()
        // A fan at -40 dBFS: above the initial threshold, so it starts out looking like speech.
        c.feed(-40f, 60_000)
        // Speech well above it, then the fan alone again: that must count as a pause.
        c.feed(-15f, 8_000)
        c.feed(-40f, 1_000).map { it.first } shouldBe listOf(Action.CUT)
    }
})
