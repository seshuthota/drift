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

import java.io.File

/** One file an [AsrModelSpec] needs. [sha256] is lowercase hex; [bytes] is the exact size. */
data class AsrModelFile(
    val name: String,
    val url: String,
    val sha256: String,
    val bytes: Long,
)

data class AsrModelSpec(
    val id: String,
    val displayName: String,
    val engineId: String,
    /** Language tag the model is limited to, or null when it is multilingual. */
    val languageTag: String?,
    val license: String,
    val attribution: String,
    val files: List<AsrModelFile>,
    /**
     * True when the engine produces text while audio arrives, so it can be the live model. A model that only decodes
     * a finished recording is used for the finishing pass instead.
     */
    val streamsLive: Boolean = true,
) {
    val totalBytes: Long get() = files.sumOf { it.bytes }

    val languages: String get() = languageTag ?: "multilingual"
}

/** A model whose files are on disk. [directory] holds every file listed in [spec]. */
data class InstalledAsrModel(val spec: AsrModelSpec, val directory: File) {
    fun file(name: String): File = File(directory, name)
}
