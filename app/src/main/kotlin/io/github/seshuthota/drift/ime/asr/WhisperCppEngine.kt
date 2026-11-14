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

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * First ASR engine: whisper.cpp via the Rust/JNI bridge. One GGML model file, no GPU, greedy decoding with
 * whisper.cpp's own tokenizer and language detection.
 */
class WhisperCppEngine : AsrEngine {
    override val id: String = AsrModelRegistry.WHISPER_CPP

    private var handle = 0L
    private var loadedModelId: String? = null
    private var loadedLanguageTag: String? = null

    override suspend fun load(model: InstalledAsrModel, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        if (handle != 0L && loadedModelId == model.spec.id) return@withContext
        close()
        val file = model.spec.files.firstOrNull() ?: error("model ${model.spec.id} declares no files")
        val modelFile: File = model.file(file.name)
        require(modelFile.isFile) { "model file is missing: ${modelFile.path}" }
        onProgress(0f)
        handle = WhisperNative.nativeLoad(modelFile.absolutePath)
        check(handle != 0L) { "whisper.cpp could not load ${modelFile.name}" }
        loadedModelId = model.spec.id
        loadedLanguageTag = model.spec.languageTag
        onProgress(1f)
    }

    override suspend fun transcribe(
        pcm: ShortArray,
        sampleRate: Int,
        prompt: String,
        partial: Boolean,
        language: String?,
    ): AsrResult = withContext(Dispatchers.Default) {
        require(sampleRate == AudioRecorder.SAMPLE_RATE) {
            "whisper.cpp needs ${AudioRecorder.SAMPLE_RATE} Hz audio, got $sampleRate"
        }
        val currentHandle = handle
        check(currentHandle != 0L) { "no whisper model is loaded" }
        val startedAt = SystemClock.uptimeMillis()
        val text = WhisperNative.nativeTranscribe(currentHandle, pcm, loadedLanguageTag ?: language.orEmpty(), prompt, partial)
        AsrResult(
            text = text.orEmpty().trim(),
            languageTag = loadedLanguageTag ?: language,
            audioMs = pcm.size * 1_000L / sampleRate,
            inferenceMs = SystemClock.uptimeMillis() - startedAt,
        )
    }

    override fun close() {
        val current = handle
        handle = 0L
        loadedModelId = null
        loadedLanguageTag = null
        if (current != 0L) {
            WhisperNative.nativeFree(current)
        }
    }

    val isLoaded: Boolean get() = handle != 0L
}
