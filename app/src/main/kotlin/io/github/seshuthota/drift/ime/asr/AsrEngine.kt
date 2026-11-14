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
 * A speech-to-text runtime. Everything model- or runtime-specific stays behind this interface, so the controller
 * only deals with PCM samples in and text out and a second engine is one factory entry.
 */
interface AsrEngine {
    /** Stable engine id, e.g. `whisper.cpp`. Matches [AsrModelSpec.engineId]. */
    val id: String

    /** Loads the files of [model]. Called lazily on the first transcription of a loaded model. */
    suspend fun load(model: InstalledAsrModel, onProgress: (Float) -> Unit = {})

    /**
     * Transcribes 16 kHz mono PCM16 [pcm]. Empty strings are a valid result (silence or no speech); callers must
     * not commit anything in that case. [prompt] is the text that came right before this audio, used as context.
     * [partial] marks a live preview that a full transcription replaces later. [language] is the language the
     * user is expected to speak (e.g. the keyboard's), used when the model itself is not tied to one language.
     */
    suspend fun transcribe(
        pcm: ShortArray,
        sampleRate: Int,
        prompt: String = "",
        partial: Boolean = false,
        language: String? = null,
    ): AsrResult

    /** Releases the native model and any buffers. Safe to call twice. */
    fun close()
}

/** What one transcription produced. [text] is already trimmed and must not contain leading or trailing spaces. */
data class AsrResult(
    val text: String,
    val languageTag: String? = null,
    val audioMs: Long = 0,
    val inferenceMs: Long = 0,
)
