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
 * A speech-to-text runtime that produces text while audio arrives. It finds sentence ("line") boundaries itself,
 * so the caller only feeds audio and shows events. Everything model- or runtime-specific stays behind this
 * interface; a second engine is one more implementation.
 *
 * Not thread-safe: one caller feeds one stream at a time. [addAudio] and [stop] may run inference on the calling
 * thread, so call them off the main thread.
 */
interface StreamingAsrEngine {
    /** Stable engine id. Matches [AsrModelSpec.engineId]. */
    val id: String

    /** Loads the files of [model]; a no-op when it is already loaded. */
    suspend fun load(model: InstalledAsrModel)

    /** Starts a new stream. Any line of a previous stream is forgotten. */
    fun start()

    /** Feeds 16 kHz mono PCM16 and returns the events it produced, in order. */
    fun addAudio(pcm: ShortArray): List<StreamEvent>

    /** Ends the stream and returns the remaining events; every open line ends with a [StreamEvent.Final]. */
    fun stop(): List<StreamEvent>

    /** Releases the model. Safe to call twice. */
    fun close()
}

/** Text for one line of a stream. A line's [Partial]s may change freely; its [Final] is what gets typed. */
sealed interface StreamEvent {
    val lineId: Long
    val text: String

    data class Partial(override val lineId: Long, override val text: String) : StreamEvent

    data class Final(override val lineId: Long, override val text: String) : StreamEvent
}
