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
import android.os.SystemClock
import io.github.seshuthota.drift.app.DriftPreferenceModel
import io.github.seshuthota.drift.subtypeManager
import io.github.seshuthota.drift.ime.keyboard3.ImeController
import io.github.seshuthota.drift.lib.devtools.flogInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.github.seshuthota.drift.ime.keyboard3.ImeEditor
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sqrt

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
 * Owns the microphone and the engine for IME voice input: start/stop, model resolution, transcription and the
 * text commits. The mic action only ever calls [toggle] or [cancel], so the IME UI has no audio logic.
 *
 * Dictation is live: while a piece of speech is still being spoken, it is re-transcribed about every second with a
 * fast, rougher setting and shown in the field as composing (underlined) text. When the piece ends at a pause, or
 * dictation stops, it is transcribed once more at full quality and committed in place of the preview.
 *
 * Threading: [toggle], [start], [stopAndTranscribe], [cancel] and [release] are called on the main thread, and every
 * other change to the current session is posted there too, so a session is only ever replaced on one thread.
 * Engine calls go through [engineLock]: a cancelled transcription keeps running in native code until it returns,
 * and the lock keeps the model from being freed under it.
 */
class AsrController(
    context: Context,
    private val modelManager: AsrModelManager,
    private val prefs: DriftPreferenceModel,
    private val imeControllerProvider: () -> ImeController?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val appContext = context.applicationContext
    private val recorder = AudioRecorder(appContext)
    private val engine = WhisperCppEngine()
    private val engineLock = Mutex()

    private val _state = MutableStateFlow<AsrState>(AsrState.Idle)
    val state: StateFlow<AsrState> = _state.asStateFlow()

    /** Microphone level, 0..1. Reset to 0 when not recording. */
    val amplitude: StateFlow<Float> = recorder.amplitude

    /** True while a finished piece of audio is being turned into text. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** One dictation: its queue of finished audio pieces, the worker that transcribes them and its preview. */
    private class Session(val startedAtMs: Long, val language: String?) {
        val queue = Channel<ShortArray>(Channel.UNLIMITED)
        var worker: Job? = null

        /** End of what this dictation committed, the prompt for the next piece. Written on main, read by worker. */
        @Volatile
        var typedTail: String = ""

        /** Space put before the current piece, fixed when its first text appears. Main thread only. */
        var piecePrefix: String? = null

        /** The field a live preview is showing in, so it can still be removed after the field changed. Main only. */
        var previewEditor: ImeEditor? = null

        /** Size of the audio the last preview was made from. Worker only. */
        var previewSamples = 0
    }

    /** The current dictation. Written on the main thread only; read by the capture thread. */
    @Volatile
    private var session: Session? = null

    init {
        recorder.onSegment = { pcm, speechMs ->
            flogInfo { "voice: piece of ${durationMs(pcm)}ms, ${speechMs}ms speech, ${levelDbLabel(pcm)} dBFS" }
            session?.queue?.trySend(pcm)
        }
        recorder.onInterrupted = {
            flogInfo { "voice: capture was interrupted" }
            scope.launch(Dispatchers.Main) {
                if (session != null) fail(AsrError.MicrophoneBusy)
            }
        }
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

    /**
     * Starts continuous dictation. The microphone stays open and whenever a few seconds of speech end in a pause,
     * that piece is transcribed and typed into the field, until [stopAndTranscribe] or [cancel] is called.
     */
    fun start() {
        if (_state.value == AsrState.Recording || _state.value == AsrState.Transcribing) return
        if (!recorder.hasPermission()) {
            _state.value = AsrState.Error(AsrError.MissingPermission)
            return
        }
        val spec = selectedSpec()
        val installed = spec?.let { modelManager.installed(it) }
        if (installed == null) {
            _state.value = AsrState.Error(AsrError.MissingModel)
            return
        }
        val current = Session(SystemClock.elapsedRealtime(), keyboardLanguage())
        session = current
        if (!recorder.start(AudioRecorder.Config(cutter = SegmentCutter()))) {
            session = null
            _state.value = AsrState.Error(AsrError.MicrophoneBusy)
            return
        }
        _state.value = AsrState.Recording
        flogInfo { "voice: dictation started with ${installed.spec.id}, language ${current.language ?: "auto"}" }
        current.worker = scope.launch { transcribeAll(current, installed) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun transcribeAll(current: Session, installed: InstalledAsrModel) {
        try {
            engineLock.withLock { engine.load(installed) }
            while (true) {
                // Finished pieces take priority; when none arrives for a moment, refresh the live preview.
                val next: ChannelResult<ShortArray>? = select {
                    current.queue.onReceiveCatching { it }
                    onTimeout(PREVIEW_INTERVAL_MS) { null }
                }
                if (next == null) {
                    preview(current)
                    continue
                }
                val pcm = next.getOrNull() ?: break
                finishPiece(current, pcm)
            }
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

    /** Transcribes the piece still being spoken, fast, and shows it as composing text. */
    private suspend fun preview(current: Session) {
        if (_state.value != AsrState.Recording) return
        val raw = recorder.snapshot() ?: return
        if (raw.size - current.previewSamples < MIN_PREVIEW_GROWTH) return
        val pcm = trimSilence(raw, recorder.speechThresholdDb ?: SegmentCutter.MIN_SPEECH_DB) ?: return
        // Under about a second of speech Whisper Tiny guesses wildly; the user would see noise, not words.
        if (pcm.size < MIN_PREVIEW_SAMPLES) return
        current.previewSamples = raw.size
        val result = engineLock.withLock {
            engine.transcribe(boosted(pcm), AudioRecorder.SAMPLE_RATE, current.typedTail, true, current.language)
        }
        val text = stripNonSpeech(result.text)
        flogInfo { "voice: preview of ${durationMs(pcm)}ms in ${result.inferenceMs}ms -> \"$text\"" }
        if (text.isEmpty()) return
        onMain(current) {
            val editor = editor() ?: return@onMain
            if (!editor.supportsLivePreview) return@onMain
            val prefix = current.piecePrefix ?: spacePrefix(editor).also { current.piecePrefix = it }
            editor.setLivePreview(prefix + text)
            current.previewEditor = editor
        }
    }

    /** Transcribes a finished piece at full quality and commits it in place of its preview. */
    private suspend fun finishPiece(current: Session, raw: ShortArray) {
        current.previewSamples = 0
        val pcm = trimSilence(raw, recorder.speechThresholdDb ?: SegmentCutter.MIN_SPEECH_DB)
        if (pcm == null || pcm.size < MIN_SAMPLES) {
            onMain(current) { clearPreview(current) }
            return
        }
        if (session === current) _busy.value = true
        try {
            val result = engineLock.withLock {
                engine.transcribe(boosted(pcm), AudioRecorder.SAMPLE_RATE, current.typedTail, false, current.language)
            }
            val text = stripNonSpeech(result.text)
            flogInfo {
                "voice: ${durationMs(raw)}ms piece, ${durationMs(pcm)}ms after trim, in ${result.inferenceMs}ms -> \"$text\""
            }
            onMain(current) {
                val editor = current.previewEditor ?: editor()
                if (editor == null || text.isEmpty()) {
                    clearPreview(current)
                    return@onMain
                }
                val prefix = current.piecePrefix ?: spacePrefix(editor)
                editor.commitLiveText(prefix + text)
                current.typedTail = (current.typedTail + prefix + text).takeLast(MAX_PROMPT_CHARS)
                current.piecePrefix = null
                current.previewEditor = null
            }
        } finally {
            if (session === current) _busy.value = false
        }
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
        current.piecePrefix = null
    }

    /** The keyboard's language, e.g. `en` for English (US), so a multilingual model does not guess per word. */
    private fun keyboardLanguage(): String? {
        return runCatching { appContext.subtypeManager().value.activeSubtype.primaryLocale.language }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
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

    /** Ends dictation: the audio still buffered is transcribed and typed, then voice input returns to idle. */
    fun stopAndTranscribe(reason: String = "button") {
        val current = session ?: return
        if (_state.value != AsrState.Recording) return
        val elapsed = SystemClock.elapsedRealtime() - current.startedAtMs
        flogInfo { "voice: stop requested by $reason after ${elapsed}ms" }
        // A tap landing right after the panel opens is the finger that opened it, not a request to stop.
        if (elapsed < MIN_RECORDING_MS) return
        _state.value = AsrState.Transcribing
        // Stopped here, on the main thread, so a later cancel()/start() can never stop a newer recording.
        val rest = recorder.stop()
        if (rest != null) {
            flogInfo { "voice: last piece of ${durationMs(rest)}ms, ${levelDbLabel(rest)} dBFS" }
            current.queue.trySend(rest)
        }
        current.queue.close()
        scope.launch(Dispatchers.Main) {
            current.worker?.join()
            if (session !== current) return@launch
            session = null
            if (_state.value == AsrState.Transcribing) _state.value = AsrState.Idle
            if (!prefs.voice.keepModelLoaded.get()) closeEngineWhenIdle()
        }
    }

    /** Drops what is still unprocessed without typing it. Called on field or window changes and by the close button. */
    fun cancel() {
        // Always stop: the capture loop may have ended on its own and the record still needs releasing.
        recorder.cancel()
        teardown()
        if (_state.value != AsrState.Idle) {
            _state.value = AsrState.Idle
        }
    }

    private fun fail(reason: AsrError) {
        recorder.cancel()
        teardown()
        _state.value = AsrState.Error(reason)
    }

    private fun teardown() {
        val current = session ?: return
        clearPreview(current)
        session = null
        current.queue.close()
        current.worker?.cancel()
        _busy.value = false
    }

    fun dismissError() {
        if (_state.value is AsrState.Error) {
            _state.value = AsrState.Idle
        }
    }

    fun release() {
        cancel()
        scope.launch { engineLock.withLock { engine.close() } }
    }

    private fun closeEngineWhenIdle() {
        scope.launch {
            engineLock.withLock {
                if (session == null) engine.close()
            }
        }
    }

    private fun selectedSpec(): AsrModelSpec? {
        return modelManager.spec(prefs.voice.modelId.get()) ?: modelManager.models().firstOrNull()
    }

    companion object {
        private const val MIN_RECORDING_MS = 700L
        private const val MIN_SAMPLES = AudioRecorder.SAMPLE_RATE / 3
        private const val MAX_PROMPT_CHARS = 200
        private const val PREVIEW_INTERVAL_MS = 400L

        /** Live previews start once there is at least 1.2 s of audio around the speech. */
        private const val MIN_PREVIEW_SAMPLES = AudioRecorder.SAMPLE_RATE * 6 / 5

        /** A preview is only redone once at least this much new audio arrived (0.5 s). */
        private const val MIN_PREVIEW_GROWTH = AudioRecorder.SAMPLE_RATE / 2
    }
}

private const val TRIM_WINDOW = AudioRecorder.SAMPLE_RATE / 10
private const val TRIM_PADDING = AudioRecorder.SAMPLE_RATE * 3 / 10

/**
 * Cuts the silence before the first and after the last 100 ms window louder than [thresholdDb], keeping 300 ms
 * around the speech. A short word inside seconds of silence is otherwise often rated as non-speech by Whisper.
 * Returns null when no window is loud enough.
 */
internal fun trimSilence(pcm: ShortArray, thresholdDb: Float): ShortArray? {
    var first = -1
    var last = -1
    var start = 0
    while (start < pcm.size) {
        val end = minOf(start + TRIM_WINDOW, pcm.size)
        var sum = 0.0
        for (i in start until end) {
            val value = pcm[i].toDouble() / Short.MAX_VALUE
            sum += value * value
        }
        val rms = sqrt(sum / (end - start))
        val db = if (rms <= 1e-6) -120.0 else 20 * log10(rms)
        if (db > thresholdDb) {
            if (first < 0) first = start
            last = end
        }
        start = end
    }
    if (first < 0) return null
    val from = maxOf(0, first - TRIM_PADDING)
    val to = minOf(pcm.size, last + TRIM_PADDING)
    return if (from == 0 && to == pcm.size) pcm else pcm.copyOfRange(from, to)
}

private const val BOOST_TARGET_PEAK = 0.7f * Short.MAX_VALUE
private const val BOOST_MAX_GAIN = 16f

/**
 * Raises quiet audio so its loudest sample sits near 70 % of full scale, at most [BOOST_MAX_GAIN] times. Some
 * phones deliver speech around -45 dBFS on the voice-recognition source, which Whisper often hears as silence.
 */
internal fun boosted(pcm: ShortArray): ShortArray {
    var peak = 0
    for (sample in pcm) peak = maxOf(peak, abs(sample.toInt()))
    if (peak == 0) return pcm
    val gain = min(BOOST_MAX_GAIN, BOOST_TARGET_PEAK / peak)
    if (gain <= 1.05f) return pcm
    return ShortArray(pcm.size) { i ->
        (pcm[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}

private fun durationMs(pcm: ShortArray): Long = pcm.size * 1_000L / AudioRecorder.SAMPLE_RATE

/** RMS level of [pcm] in dBFS, for the log, so quiet-microphone problems are visible. */
private fun levelDbLabel(pcm: ShortArray): String {
    if (pcm.isEmpty()) return "-inf"
    var sum = 0.0
    for (sample in pcm) {
        val value = sample.toDouble() / Short.MAX_VALUE
        sum += value * value
    }
    val rms = sqrt(sum / pcm.size)
    return if (rms <= 1e-6) "-120" else "%.1f".format(20 * log10(rms))
}

private val NonSpeechTag = Regex("""\s*(\[[^\]]*]|\([^)]*\)|\*[^*]*\*)\s*""")

/** Whisper writes silence and noise as tags such as `[BLANK_AUDIO]`, `(music)` or `*cough*`; they are not text. */
internal fun stripNonSpeech(text: String): String {
    return text.replace('\n', ' ').replace(NonSpeechTag, " ").trim()
}
