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

package io.github.seshuthota.drift.ime.keyboard3.gesture

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe

class GlideEndpointPenaltyTest : FunSpec({
    val o = 0.84375f to 0.1766f
    val k = 0.7998f to 0.5149f
    val n = 0.6860f to 0.8828f
    val f = 0.3972f to 0.5371f

    fun rank(acoustic: Double, frequency: Double, penalty: Float): Double {
        return acoustic + frequency - GLIDE_ENDPOINT_WEIGHT * penalty
    }

    test("a straight stroke from o to k ranks ok ahead of of and on") {
        val ok = glideEndpointPenalty(o.first, o.second, k.first, k.second, o.first, o.second, k.first, k.second)
        val of = glideEndpointPenalty(o.first, o.second, k.first, k.second, o.first, o.second, f.first, f.second)
        val on = glideEndpointPenalty(o.first, o.second, k.first, k.second, o.first, o.second, n.first, n.second)
        ok shouldBeLessThan 0.001f
        of shouldBeGreaterThan on
        val okScore = rank(-2.14, 5.15, ok)
        val ofScore = rank(-0.53, 6.40, of)
        val onScore = rank(-2.16, 6.16, on)
        (okScore > ofScore) shouldBe true
        (okScore > onScore) shouldBe true
    }

    test("a straight stroke from o to f still ranks of first") {
        val of = glideEndpointPenalty(o.first, o.second, f.first, f.second, o.first, o.second, f.first, f.second)
        val on = glideEndpointPenalty(o.first, o.second, f.first, f.second, o.first, o.second, n.first, n.second)
        val ofScore = rank(0.0, 6.40, of)
        val onScore = rank(-10.30, 6.16, on)
        (ofScore > onScore) shouldBe true
    }
})
