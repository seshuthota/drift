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
 * JNI surface of `lib/native/src/main/rust/src/asr.rs`.
 *
 * [nativeLoad] and [nativeTranscribe] throw [IllegalStateException] with the whisper.cpp error text on failure.
 * A handle is an opaque pointer; [nativeFree] must be called exactly once per successful load.
 */
internal object WhisperNative {
    @JvmStatic external fun nativeLoad(modelPath: String): Long

    @JvmStatic external fun nativeTranscribe(handle: Long, pcm: ShortArray, languageTag: String): String?

    @JvmStatic external fun nativeFree(handle: Long)
}
