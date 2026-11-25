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
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking

/**
 * Opt-in: runs the real model on the desktop ONNX Runtime. Set `PARAKEET_MODEL_DIR` (the five files of the registry
 * row) and `PARAKEET_PCM` (16 kHz mono signed 16-bit little-endian raw audio) to enable it.
 */
class ParakeetEngineModelTest : FunSpec({
    val dir = System.getenv("PARAKEET_MODEL_DIR")
    val pcmPath = System.getenv("PARAKEET_PCM")
    test("transcribes a clip").config(enabled = dir != null && pcmPath != null) {
        val spec = AsrModelRegistry.spec(AsrModelRegistry.FLUID_SPEECH_PRO_ID)!!
        val bytes = ByteBuffer.wrap(File(pcmPath!!).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val pcm = ShortArray(bytes.remaining() / 2) { bytes.short }
        val engine = ParakeetEngine()
        runBlocking { engine.load(InstalledAsrModel(spec, File(dir!!))) }
        val text = engine.transcribeAll(pcm)
        println("PARAKEET TRANSCRIPT: $text")
        engine.start()
        engine.addAudio(pcm)
        println("PARAKEET STREAM FINAL: ${engine.stop()}")
        engine.close()
    }
})
