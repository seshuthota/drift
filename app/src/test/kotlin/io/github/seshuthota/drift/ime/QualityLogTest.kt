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

package io.github.seshuthota.drift.ime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.ByteBuffer
import java.nio.ByteOrder

class QualityLogTest : FunSpec({
    test("voice clips are 16-bit mono WAV files with the samples in order") {
        val pcm = shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE)
        val bytes = QualityLog.wav(pcm, 16_000)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        String(bytes, 0, 4) shouldBe "RIFF"
        buffer.getInt(4) shouldBe 36 + pcm.size * 2
        String(bytes, 8, 8) shouldBe "WAVEfmt "
        buffer.getShort(20) shouldBe 1.toShort()
        buffer.getShort(22) shouldBe 1.toShort()
        buffer.getInt(24) shouldBe 16_000
        buffer.getShort(34) shouldBe 16.toShort()
        String(bytes, 36, 4) shouldBe "data"
        buffer.getInt(40) shouldBe pcm.size * 2
        pcm.indices.forEach { buffer.getShort(44 + it * 2) shouldBe pcm[it] }
    }
})
