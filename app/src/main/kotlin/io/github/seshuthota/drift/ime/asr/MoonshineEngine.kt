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

import ai.moonshine.voice.JNI
import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriptEvent
import io.github.seshuthota.drift.lib.devtools.flogInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.function.Consumer

/**
 * Moonshine Streaming via the `moonshine-voice` Android library: a streaming encoder with 80 ms lookahead that
 * reports text for the current line while it is spoken and ends lines at pauses on its own.
 */
class MoonshineEngine : StreamingAsrEngine {
    override val id: String = AsrModelRegistry.MOONSHINE

    private var transcriber: Transcriber? = null
    private var loadedModelId: String? = null
    private var running = false

    /** Events collected by the listener during one [addAudio] or [stop] call. */
    private val pending = ArrayList<StreamEvent>()
    private var pendingError: Throwable? = null

    private val listener = Consumer<TranscriptEvent> { event ->
        val next = when (event) {
            is TranscriptEvent.LineTextChanged -> StreamEvent.Partial(event.line.id, event.line.text.orEmpty())
            is TranscriptEvent.LineCompleted -> StreamEvent.Final(event.line.id, event.line.text.orEmpty())
            is TranscriptEvent.Error -> {
                // Never throw from the listener: it may be called back from native code.
                synchronized(pending) { pendingError = event.cause ?: IllegalStateException("moonshine failed") }
                null
            }
            else -> null
        }
        if (next != null) synchronized(pending) { pending += next }
    }

    override suspend fun load(model: InstalledAsrModel) = withContext(Dispatchers.IO) {
        if (transcriber != null && loadedModelId == model.spec.id) return@withContext
        close()
        val instance = Transcriber()
        try {
            instance.loadFromFiles(model.directory.absolutePath, archOf(model.spec))
            instance.addListener(listener)
        } catch (e: Throwable) {
            runCatching { instance.close() }
            throw IllegalStateException("moonshine could not load ${model.spec.id}: ${e.message}", e)
        }
        transcriber = instance
        loadedModelId = model.spec.id
    }

    override fun start() {
        val instance = checkNotNull(transcriber) { "no moonshine model is loaded" }
        // A cancelled dictation never reached stop(); end its stream so its lines do not leak into this one.
        if (running) runCatching { instance.stop() }
        synchronized(pending) {
            pending.clear()
            pendingError = null
        }
        instance.start()
        running = true
    }

    override fun addAudio(pcm: ShortArray): List<StreamEvent> {
        val instance = checkNotNull(transcriber) { "no moonshine model is loaded" }
        val samples = FloatArray(pcm.size) { pcm[it] / 32_768f }
        instance.addAudio(samples, AudioRecorder.SAMPLE_RATE)
        return drain()
    }

    override fun stop(): List<StreamEvent> {
        if (running) transcriber?.stop()
        running = false
        return drain()
    }

    override fun setContext(text: String) {
        val instance = checkNotNull(transcriber) { "no moonshine model is loaded" }
        // Key-term biasing is a hint: a model or text it cannot use must not stop the dictation.
        runCatching { instance.setContext(text) }.onFailure { flogInfo { "voice: context not applied: ${it.message}" } }
    }

    override fun transcribeAll(pcm: ShortArray): String {
        val instance = checkNotNull(transcriber) { "no moonshine model is loaded" }
        check(!running) { "a stream is still running" }
        val samples = FloatArray(pcm.size) { pcm[it] / 32_768f }
        val transcript = instance.transcribeWithoutStreaming(samples, AudioRecorder.SAMPLE_RATE)
        // The listener may have been called for the one-pass lines too; they are not part of any stream.
        drain()
        return transcript.lines.orEmpty().joinToString(" ") { it.text.orEmpty().trim() }.trim()
    }

    override fun close() {
        val instance = transcriber ?: return
        transcriber = null
        loadedModelId = null
        running = false
        runCatching { instance.removeAllListeners() }
        runCatching { instance.close() }
    }

    /** Returns and clears the collected events, or throws the error the stream reported. */
    private fun drain(): List<StreamEvent> = synchronized(pending) {
        val error = pendingError
        pendingError = null
        val out = pending.toList()
        pending.clear()
        if (error != null) throw IllegalStateException("moonshine failed: ${error.message}", error)
        out
    }

    private fun archOf(spec: AsrModelSpec): Int = when {
        "medium" in spec.id -> JNI.MOONSHINE_MODEL_ARCH_MEDIUM_STREAMING
        "small" in spec.id -> JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING
        "base" in spec.id -> JNI.MOONSHINE_MODEL_ARCH_BASE_STREAMING
        else -> JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING
    }
}
