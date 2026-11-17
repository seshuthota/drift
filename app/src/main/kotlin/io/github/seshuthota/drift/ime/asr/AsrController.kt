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

import android.content.Context
import android.media.MediaRecorder
import android.os.SystemClock
import io.github.seshuthota.drift.app.DriftPreferenceModel
import io.github.seshuthota.drift.ime.QualityLog
import io.github.seshuthota.drift.ime.keyboard3.ImeController
import io.github.seshuthota.drift.ime.keyboard3.ImeEditor
import io.github.seshuthota.drift.lib.devtools.flogInfo
import io.github.seshuthota.drift.libnative.purgeNativeMemory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.log10

/** Observable state of the local voice input flow. */
sealed interface AsrState {
    data object Idle : AsrState
    data object Recording : AsrState
    data object Transcribing : AsrState
    data class Error(val reason: AsrError) : AsrState
}

/** Why voice input stopped; the overlay maps these to strings. */
sealed interface AsrError {
    data object MissingPermission : AsrError
    data object MissingModel : AsrError
    data object MicrophoneBusy : AsrError
    data class Failure(val message: String) : AsrError
}

/**
 * Owns the microphone and the engine for IME voice input: start/stop, model resolution, and the text commits. The
 * mic action only ever calls [toggle] or [cancel], so the IME UI has no audio logic.
 *
 * Dictation is live: every captured chunk is fed to a [StreamingAsrEngine], the text of the line being spoken is
 * shown in the field as composing (underlined) text, and it is committed when the engine ends the line at a pause
 * or dictation stops.
 *
 * Threading: [toggle], [start], [stopAndTranscribe], [cancel] and [release] are called on the main thread, and every
 * other change to the current session is posted there too, so a session is only ever replaced on one thread.
 * Engine calls go through [engineLock], so the model is never freed while a call is still running.
 */
class AsrController(
    context: Context,
    private val modelManager: AsrModelManager,
    private val prefs: DriftPreferenceModel,
    private val imeControllerProvider: () -> ImeController?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val recorder = AudioRecorder(context.applicationContext)
    private val engine: StreamingAsrEngine = MoonshineEngine()
    private val engineLock = Mutex()

    private val _state = MutableStateFlow<AsrState>(AsrState.Idle)
    val state: StateFlow<AsrState> = _state.asStateFlow()

    /** Microphone level, 0..1. Reset to 0 when not recording. */
    val amplitude: StateFlow<Float> = recorder.amplitude

    /** One dictation: its queue of audio chunks, the worker that feeds them to the engine, and its preview. */
    private class Session(val startedAtMs: Long, val modelId: String, keepClip: Boolean) {
        val queue = Channel<ShortArray>(Channel.UNLIMITED)
        var worker: Job? = null

        /** Quality-log material: committed lines, per-second peak level and gain, and the raw audio if kept. */
        val finals: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val peakDb: MutableList<Float> = java.util.Collections.synchronizedList(mutableListOf())
        val gain: MutableList<Float> = java.util.Collections.synchronizedList(mutableListOf())
        val clip: ShortPcmBuffer? = if (keepClip) ShortPcmBuffer(MAX_CLIP_SAMPLES) else null
        var logged = false
        var source = "voice_recognition"

        /** Space put before the current line, fixed when its first text appears. Main thread only. */
        var linePrefix: String? = null

        /** The field a live preview is showing in, so it can still be removed after the field changed. Main only. */
        var previewEditor: ImeEditor? = null
    }

    /** The current dictation. Written on the main thread only; read by the capture thread. */
    @Volatile
    private var session: Session? = null

    init {
        recorder.onAudio = { pcm -> session?.queue?.trySend(pcm) }
        recorder.onInterrupted = {
            flogInfo { "voice: capture was interrupted" }
            scope.launch(Dispatchers.Main) {
                if (session != null) fail(AsrError.MicrophoneBusy)
            }
        }
        // Models that are no longer in the registry (e.g. the removed Whisper ones) only take up space.
        scope.launch(Dispatchers.IO) { modelManager.removeUnlisted() }
    }

    /** Mic action entry point: start dictating while idle, finish while dictating, ignore while finishing. */
    fun toggle() {
        when (_state.value) {
            AsrState.Recording -> stopAndTranscribe("toggle")
            AsrState.Transcribing -> Unit
            AsrState.Idle -> start()
            is AsrState.Error -> {
                _state.value = AsrState.Idle
                start()
            }
        }
    }

    /** Starts dictation; text appears while speaking until [stopAndTranscribe] or [cancel] is called. */
    fun start() {
        if (_state.value == AsrState.Recording || _state.value == AsrState.Transcribing) return
        if (!recorder.hasPermission()) {
            _state.value = AsrState.Error(AsrError.MissingPermission)
            return
        }
        val installed = selectedSpec()?.let { modelManager.installed(it) }
        if (installed == null) {
            _state.value = AsrState.Error(AsrError.MissingModel)
            return
        }
        val current = Session(SystemClock.elapsedRealtime(), installed.spec.id, QualityLog.keepVoiceClips())
        session = current
        val plainMic = prefs.voice.plainMicSource.get()
        current.source = if (plainMic) "mic" else "voice_recognition"
        val audioSource = if (plainMic) MediaRecorder.AudioSource.MIC else MediaRecorder.AudioSource.VOICE_RECOGNITION
        if (!recorder.start(audioSource)) {
            session = null
            _state.value = AsrState.Error(AsrError.MicrophoneBusy)
            return
        }
        _state.value = AsrState.Recording
        imeControllerProvider()?.beginExternalEdit()
        flogInfo { "voice: dictation started with ${installed.spec.id}" }
        current.worker = scope.launch { streamAll(current, installed) }
    }

    /** Feeds every captured chunk to the engine as it arrives and shows the engine's events. */
    private suspend fun streamAll(current: Session, installed: InstalledAsrModel) {
        try {
            engineLock.withLock {
                engine.load(installed)
                engine.start()
            }
            val gain = AutoGain()
            var secondPeak = 0
            var secondGain = 0f
            var secondChunks = 0
            for (pcm in current.queue) {
                current.clip?.append(pcm)
                val processed = gain.process(pcm)
                for (sample in pcm) secondPeak = maxOf(secondPeak, abs(sample.toInt()))
                secondGain += gain.gain
                if (++secondChunks == CHUNKS_PER_SECOND) {
                    current.peakDb += if (secondPeak == 0) -120f else 20f * log10(secondPeak / 32_768f)
                    current.gain += secondGain / secondChunks
                    secondPeak = 0
                    secondGain = 0f
                    secondChunks = 0
                }
                val events = engineLock.withLock { engine.addAudio(processed) }
                showStreamEvents(current, events)
            }
            showStreamEvents(current, engineLock.withLock { engine.stop() })
            onMain(current) { clearPreview(current) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            flogInfo { "voice: transcription failed: ${e.message}" }
            scope.launch(Dispatchers.Main) {
                if (session === current) fail(AsrError.Failure(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private suspend fun showStreamEvents(current: Session, events: List<StreamEvent>) {
        if (events.isEmpty()) return
        for (event in events) {
            if (event is StreamEvent.Final) {
                flogInfo { "voice: line ${event.lineId} final -> \"${event.text}\"" }
                current.finals += event.text.replace('\n', ' ').trim()
            }
        }
        onMain(current) {
            for (event in events) {
                val text = event.text.replace('\n', ' ').trim()
                when (event) {
                    is StreamEvent.Partial -> if (text.isNotEmpty()) showPreview(current, text)
                    is StreamEvent.Final -> commitLine(current, text)
                }
            }
        }
    }

    /** Shows [text] as the live preview of the current line. Main thread only. */
    private fun showPreview(current: Session, text: String) {
        val editor = current.previewEditor ?: editor() ?: return
        if (!editor.supportsLivePreview) return
        val prefix = current.linePrefix ?: spacePrefix(editor).also { current.linePrefix = it }
        editor.setLivePreview(prefix + text)
        current.previewEditor = editor
    }

    /** Commits [text] as the final text of the current line, in place of its preview. Main thread only. */
    private fun commitLine(current: Session, text: String) {
        val editor = current.previewEditor ?: editor()
        if (editor == null || text.isEmpty()) {
            clearPreview(current)
            return
        }
        val prefix = current.linePrefix ?: spacePrefix(editor)
        editor.commitLiveText(prefix + text)
        current.linePrefix = null
        current.previewEditor = null
    }

    /** Runs [block] on the main thread if [current] is still the active dictation. */
    private suspend fun onMain(current: Session, block: () -> Unit) {
        withContext(Dispatchers.Main) {
            if (session === current) block()
        }
    }

    /** Removes the live preview from the field, if one is showing. Main thread only. */
    private fun clearPreview(current: Session) {
        current.previewEditor?.setLivePreview("")
        current.previewEditor = null
        current.linePrefix = null
    }

    private fun editor(): ImeEditor? = imeControllerProvider()?.snapshotState()?.editor

    /**
     * A space when the text before the cursor does not already end in whitespace. Also ends any composing word
     * typed before dictation, so the preview is written after it instead of replacing it.
     */
    private fun spacePrefix(editor: ImeEditor): String {
        editor.finishComposing()
        return if (editor.getTextBeforeCursor(1).lastOrNull()?.isWhitespace() == false) " " else ""
    }

    /** Ends dictation: the line still being spoken is finished and typed, then voice input returns to idle. */
    fun stopAndTranscribe(reason: String = "button") {
        val current = session ?: return
        if (_state.value != AsrState.Recording) return
        val elapsed = SystemClock.elapsedRealtime() - current.startedAtMs
        flogInfo { "voice: stop requested by $reason after ${elapsed}ms" }
        // A tap landing right after the panel opens is the finger that opened it, not a request to stop.
        if (elapsed < MIN_RECORDING_MS) return
        _state.value = AsrState.Transcribing
        scope.launch(Dispatchers.Main) {
            // The last word is often still being spoken when the stop is tapped, and the audio still buffered in the
            // recorder would be dropped by an immediate stop, so keep capturing briefly. A cancel() meanwhile wins.
            delay(STOP_TAIL_MS)
            if (session !== current) return@launch
            // Stopped here, on the main thread, so a later cancel()/start() can never stop a newer recording.
            recorder.stop()
            current.queue.close()
            current.worker?.join()
            if (session !== current) return@launch
            logSession(current, "stop:$reason")
            session = null
            imeControllerProvider()?.endExternalEdit()
            if (_state.value == AsrState.Transcribing) _state.value = AsrState.Idle
            if (!prefs.voice.keepModelLoaded.get()) closeEngineWhenIdle()
        }
    }

    /** Drops what is still unprocessed without typing it. Called on field or window changes and by the close button. */
    fun cancel() {
        // Always stop: the capture loop may have ended on its own and the record still needs releasing.
        recorder.stop()
        teardown()
        if (_state.value != AsrState.Idle) {
            _state.value = AsrState.Idle
        }
    }

    private fun fail(reason: AsrError) {
        recorder.stop()
        teardown("fail:${reason.javaClass.simpleName}")
        _state.value = AsrState.Error(reason)
    }

    private fun teardown(ending: String = "cancel") {
        val current = session ?: return
        logSession(current, ending)
        clearPreview(current)
        session = null
        imeControllerProvider()?.endExternalEdit()
        current.queue.close()
        current.worker?.cancel()
        // A cancelled or failed dictation frees the model too; otherwise it stays loaded until the next normal stop.
        if (!prefs.voice.keepModelLoaded.get()) closeEngineWhenIdle()
    }

    /** Writes [current] to the opt-in quality log once. Main thread only. */
    private fun logSession(current: Session, ending: String) {
        if (current.logged) return
        current.logged = true
        if (!QualityLog.isEnabled()) return
        if (imeControllerProvider()?.snapshotState()?.flags?.isIncognitoMode != false) return
        QualityLog.voice(
            model = current.modelId,
            source = current.source,
            durationMs = SystemClock.elapsedRealtime() - current.startedAtMs,
            peakDb = current.peakDb.toList(),
            gain = current.gain.toList(),
            lines = current.finals.toList(),
            ending = ending,
            pcm = current.clip?.toArray(),
            sampleRate = AudioRecorder.SAMPLE_RATE,
        )
    }

    fun dismissError() {
        if (_state.value is AsrState.Error) {
            _state.value = AsrState.Idle
        }
    }

    fun release() {
        cancel()
        scope.launch { engineLock.withLock { closeEngine() } }
    }

    private fun closeEngineWhenIdle() {
        scope.launch {
            engineLock.withLock {
                if (session == null) closeEngine()
            }
        }
    }

    /** Frees the model and hands the freed pages back to the system, which the allocator would otherwise keep. */
    private fun closeEngine() {
        engine.close()
        val purged = purgeNativeMemory()
        flogInfo { "voice: model released, native memory purged=$purged" }
    }

    private fun selectedSpec(): AsrModelSpec? {
        return modelManager.spec(prefs.voice.modelId.get()) ?: modelManager.models().firstOrNull()
    }

    companion object {
        private const val MIN_RECORDING_MS = 700L

        /** How long capture continues after the stop tap. */
        private const val STOP_TAIL_MS = 350L

        /** The recorder delivers ~100 ms chunks. */
        private const val CHUNKS_PER_SECOND = 10

        /** Kept clips are capped at two minutes (3.8 MB). */
        private const val MAX_CLIP_SAMPLES = AudioRecorder.SAMPLE_RATE * 120
    }
}

/** Growable PCM16 buffer with a hard cap; samples past the cap are dropped. Thread-safe. */
private class ShortPcmBuffer(private val maxSamples: Int) {
    private var data = ShortArray(16_000)
    private var size = 0

    @Synchronized
    fun append(pcm: ShortArray) {
        val count = minOf(pcm.size, maxSamples - size)
        if (count <= 0) return
        if (size + count > data.size) data = data.copyOf(minOf(maxSamples, maxOf(data.size * 2, size + count)))
        System.arraycopy(pcm, 0, data, size, count)
        size += count
    }

    @Synchronized
    fun toArray(): ShortArray = data.copyOf(size)
}
