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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class TrimSilenceTest : FunSpec({
    val rate = AudioRecorder.SAMPLE_RATE

    /** [quietMs] of near-silence, [loudMs] of a -30 dBFS square wave, then [tailMs] of near-silence. */
    fun audio(quietMs: Int, loudMs: Int, tailMs: Int): ShortArray {
        val quiet = ShortArray(quietMs * rate / 1000) { 3 }
        val loud = ShortArray(loudMs * rate / 1000) { if (it % 2 == 0) 1_036 else -1_036 }
        val tail = ShortArray(tailMs * rate / 1000) { 3 }
        return quiet + loud + tail
    }

    test("keeps 300 ms of padding around the speech") {
        val out = trimSilence(audio(3_000, 500, 4_000), -50f)!!
        out.size shouldBe (300 + 500 + 300) * rate / 1000
    }

    test("returns null when nothing is above the threshold") {
        trimSilence(audio(2_000, 0, 0), -50f).shouldBeNull()
    }

    test("does not pad past the ends of the audio") {
        val pcm = audio(100, 500, 100)
        trimSilence(pcm, -50f)!!.size shouldBe pcm.size
    }
})
