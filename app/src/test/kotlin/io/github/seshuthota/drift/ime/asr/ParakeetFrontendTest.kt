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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.math.sin

class ParakeetFrontendTest : FunSpec({
    val frontend = ParakeetFrontend()

    test("an empty clip has no features") {
        frontend.features(FloatArray(0)) shouldBe null
    }

    test("one second gives 101 frames of 128 bands, each band normalised") {
        val tone = FloatArray(16_000) { (0.3 * sin(2 * PI * 440 * it / 16_000)).toFloat() }
        val (features, frames) = frontend.features(tone)!!
        frames shouldBe 101
        features.size shouldBe 128 * 101
        for (band in listOf(0, 40, 127)) {
            val row = features.copyOfRange(band * frames, (band + 1) * frames)
            row.average().toFloat() shouldBe (0f plusOrMinus 1e-3f)
            val variance = row.sumOf { (it * it).toDouble() } / (frames - 1)
            variance.toFloat() shouldBe (1f plusOrMinus 1e-2f)
        }
    }

    test("a 440 Hz tone puts its energy in the matching low band") {
        val tone = FloatArray(16_000) { (0.3 * sin(2 * PI * 440 * it / 16_000)).toFloat() }
        val (features, frames) = frontend.features(tone)!!
        val mid = frames / 2
        val loudest = (0 until 128).maxByOrNull { features[it * frames + mid] }!!
        // 440 Hz is 6.6 Slaney mel units of 45.3 at 8 kHz, spread over 129 band steps: band 18 or 19.
        (loudest in 17..20) shouldBe true
    }
})

class ParakeetSegmentTest : FunSpec({
    test("short audio is one segment") {
        ParakeetEngine.segmentBounds(FloatArray(16_000 * 20)) shouldBe listOf(0 to 16_000 * 20)
    }

    test("long audio is cut at the quiet gap and covers every sample") {
        val rate = 16_000
        val samples = FloatArray(rate * 70) { 0.5f }
        // A silent 200 ms gap at 27 s, inside the 25..30 s search window.
        for (i in 27 * rate until 27 * rate + rate / 5) samples[i] = 0f
        val bounds = ParakeetEngine.segmentBounds(samples)
        bounds.first().second shouldBeLessThanOrEqual 28 * rate
        (bounds.first().second >= 27 * rate) shouldBe true
        bounds.first().first shouldBe 0
        bounds.last().second shouldBe samples.size
        bounds.zipWithNext().forEach { (a, b) -> a.second shouldBe b.first }
        bounds.forEach { (from, to) -> (to - from) shouldBeLessThanOrEqual 30 * rate }
    }
})
