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
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

class BoostedTest : FunSpec({
    test("quiet audio is raised towards 70 percent of full scale") {
        val out = boosted(shortArrayOf(0, 2_000, -2_000))
        out[1].toInt() shouldBeInRange 22_900..22_940
        out[2].toInt() shouldBe -out[1].toInt()
    }

    test("gain is capped for near-silence") {
        boosted(shortArrayOf(10, -10))[0].toInt() shouldBe 160
    }

    test("loud audio and silence are returned unchanged") {
        val loud = shortArrayOf(30_000, -20_000)
        boosted(loud) shouldBeSameInstanceAs loud
        val zero = ShortArray(4)
        boosted(zero) shouldBeSameInstanceAs zero
    }
})
