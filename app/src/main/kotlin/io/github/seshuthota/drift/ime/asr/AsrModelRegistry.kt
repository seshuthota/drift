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

/**
 * Built-in ASR model catalog. Rows are data; testing a new Whisper size or a different model family only adds a
 * row (and a [AsrModelSpec.engineId] that an engine in the factory implements).
 *
 * Checksums and sizes are the LFS metadata of `ggerganov/whisper.cpp` on Hugging Face, which is also the download
 * host. Weights: MIT (OpenAI Whisper), GGML conversion by ggerganov/whisper.cpp.
 */
object AsrModelRegistry {
    const val WHISPER_CPP = "whisper.cpp"

    const val DEFAULT_MODEL_ID = "whisper-tiny-q5_1"
    const val ENGLISH_MODEL_ID = "whisper-tiny-en-q5_1"

    private const val HOST = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"
    private const val LICENSE = "MIT"
    private const val ATTRIBUTION = "OpenAI Whisper (MIT), GGML conversion by ggerganov/whisper.cpp"

    /**
     * Multilingual first because Drift is used outside English; [ENGLISH_MODEL_ID] is one tap away for users who
     * want the better English accuracy of the `.en` weights.
     */
    val models: List<AsrModelSpec> = listOf(
        AsrModelSpec(
            id = DEFAULT_MODEL_ID,
            displayName = "Whisper Tiny q5_1",
            engineId = WHISPER_CPP,
            languageTag = null,
            license = LICENSE,
            attribution = ATTRIBUTION,
            files = listOf(
                AsrModelFile(
                    name = "ggml-tiny-q5_1.bin",
                    url = HOST + "ggml-tiny-q5_1.bin",
                    sha256 = "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7",
                    bytes = 32_152_673,
                ),
            ),
        ),
        AsrModelSpec(
            id = ENGLISH_MODEL_ID,
            displayName = "Whisper Tiny English q5_1",
            engineId = WHISPER_CPP,
            languageTag = "en",
            license = LICENSE,
            attribution = ATTRIBUTION,
            files = listOf(
                AsrModelFile(
                    name = "ggml-tiny.en-q5_1.bin",
                    url = HOST + "ggml-tiny.en-q5_1.bin",
                    sha256 = "c77c5766f1cef09b6b7d47f21b546cbddd4157886b3b5d6d4f709e91e66c7c2b",
                    bytes = 32_166_155,
                ),
            ),
        ),
    )

    fun spec(id: String): AsrModelSpec? = models.firstOrNull { it.id == id }

    fun default(): AsrModelSpec = spec(DEFAULT_MODEL_ID)!!

    /** Rows that [engineId] can run, in registry order. */
    fun forEngine(engineId: String): List<AsrModelSpec> = models.filter { it.engineId == engineId }
}
