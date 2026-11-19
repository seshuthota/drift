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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class QualityLogContractTest : FunSpec({
    val source = File("src/main/kotlin/io/github/seshuthota/drift/ime/QualityLog.kt").readText()
    val analyze = File("../ml/quality/analyze.py").readText()

    test("every event the analyser switches on is one QualityLog emits") {
        val emitted = Regex("\\.put\\(\"e\", \"([a-z_]+)\"\\)")
            .findAll(source).map { it.groupValues[1] }.toSet()
        val analysed = Regex("e\\[\"e\"\\] == \"([a-z_]+)\"")
            .findAll(analyze).map { it.groupValues[1] }.toSet()

        emitted.size shouldBe 8
        (analysed - emitted).shouldBeEmpty()
    }

    test("wav writes a 16-bit mono RIFF/WAVE container") {
        val pcm = shortArrayOf(0, 1, -1, 32767, -32768)
        val bytes = QualityLog.wav(pcm, 16_000)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        bytes.size shouldBe 44 + pcm.size * 2
        String(bytes, 0, 4) shouldBe "RIFF"
        buffer.getInt(4) shouldBe 36 + pcm.size * 2
        String(bytes, 8, 4) shouldBe "WAVE"
        String(bytes, 12, 4) shouldBe "fmt "
        buffer.getInt(16) shouldBe 16
        buffer.getShort(20) shouldBe 1
        buffer.getShort(22) shouldBe 1
        buffer.getInt(24) shouldBe 16_000
        buffer.getInt(28) shouldBe 32_000
        buffer.getShort(32) shouldBe 2
        buffer.getShort(34) shouldBe 16
        String(bytes, 36, 4) shouldBe "data"
        buffer.getInt(40) shouldBe pcm.size * 2
        for (i in pcm.indices) {
            buffer.getShort(44 + i * 2) shouldBe pcm[i]
        }
    }
})
