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
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe

class AutoGainTest : FunSpec({
    fun chunk(amplitude: Int) = ShortArray(1_600) { if (it % 2 == 0) amplitude.toShort() else (-amplitude).toShort() }

    test("quiet speech is lifted, capped at the maximum gain") {
        val gain = AutoGain()
        gain.process(chunk(200))
        gain.gain shouldBe 16f
    }

    test("a loud chunk is never clipped") {
        val gain = AutoGain()
        repeat(20) { gain.process(chunk(100)) }
        val out = gain.process(chunk(20_000))
        out.maxOf { it.toInt() } shouldBeLessThanOrEqual Short.MAX_VALUE.toInt()
        out[0].toInt() shouldBe 20_000
    }

    test("gain recovers slowly after a loud word, not in one chunk") {
        val gain = AutoGain()
        gain.process(chunk(8_000))
        val afterLoud = gain.gain
        gain.process(chunk(100))
        gain.gain shouldBeLessThan afterLoud * 1.1f
        repeat(200) { gain.process(chunk(100)) }
        gain.gain shouldBeGreaterThan 10f
    }
})
