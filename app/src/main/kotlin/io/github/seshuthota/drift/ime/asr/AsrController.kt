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
import io.github.seshuthota.drift.app.DriftPreferenceModel
import io.github.seshuthota.drift.ime.keyboard3.ImeController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.k3lp.lib.text.asK3String

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
 * single text commit. The mic action only ever calls [toggle] or [cancel], so the IME UI has no audio logic.
 */
class AsrController(
    context: Context,
    private val modelManager: AsrModelManager,
    private val prefs: DriftPreferenceModel,
    private val imeControllerProvider: () -> ImeController?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val recorder = AudioRecorder(context.applicationContext)
    private val engine = WhisperCppEngine()

    private val _state = MutableStateFlow<AsrState>(AsrState.Idle)
    val state: StateFlow<AsrState> = _state.asStateFlow()

    /** Microphone level, 0..1. Reset to 0 when not recording. */
    val amplitude: StateFlow<Float> = recorder.amplitude

    private var stopJob: Job? = null

    /** Mic action entry point: record while idle, transcribe while recording, ignore while transcribing. */
    fun toggle() {
        when (_state.value) {
            AsrState.Recording -> stopAndTranscribe()
            AsrState.Transcribing -> Unit
            AsrState.Idle -> start()
            is AsrState.Error -> {
                _state.value = AsrState.Idle
                start()
            }
        }
    }

    fun start() {
        if (_state.value == AsrState.Recording || _state.value == AsrState.Transcribing) return
        if (!recorder.hasPermission()) {
            _state.value = AsrState.Error(AsrError.MissingPermission)
            return
        }
        val spec = selectedSpec()
        if (spec == null || modelManager.installed(spec) == null) {
            _state.value = AsrState.Error(AsrError.MissingModel)
            return
        }
        val maxDurationMs = prefs.voice.maxDurationSeconds.get().coerceIn(MIN_DURATION_S, MAX_DURATION_S) * 1_000L
        if (!recorder.start(AudioRecorder.Config(maxDurationMs = maxDurationMs))) {
            _state.value = AsrState.Error(AsrError.MicrophoneBusy)
            return
        }
        _state.value = AsrState.Recording
        stopJob?.cancel()
        stopJob = scope.launch {
            delay(maxDurationMs)
            stopAndTranscribe()
        }
    }

    fun stopAndTranscribe() {
        if (_state.value != AsrState.Recording) return
        stopJob?.cancel()
        stopJob = null
        _state.value = AsrState.Transcribing
        scope.launch {
            val pcm = withContext(Dispatchers.IO) { recorder.stop() }
            if (pcm == null || pcm.size < MIN_SAMPLES) {
                _state.value = AsrState.Idle
                return@launch
            }
            try {
                val spec = selectedSpec() ?: throw IllegalStateException("no voice model selected")
                val installed = modelManager.installed(spec) ?: throw IllegalStateException("voice model is not installed")
                engine.load(installed)
                val result = engine.transcribe(pcm, AudioRecorder.SAMPLE_RATE)
                commit(result.text)
                _state.value = AsrState.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = AsrState.Error(AsrError.Failure(e.message ?: e.javaClass.simpleName))
            } finally {
                if (!prefs.voice.keepModelLoaded.get()) {
                    engine.close()
                }
            }
        }
    }

    /** Drops the recording without committing it. Called on field or window changes. */
    fun cancel() {
        stopJob?.cancel()
        stopJob = null
        if (recorder.isRecording) {
            recorder.cancel()
        }
        if (_state.value != AsrState.Idle) {
            _state.value = AsrState.Idle
        }
    }

    fun dismissError() {
        if (_state.value is AsrState.Error) {
            _state.value = AsrState.Idle
        }
    }

    fun release() {
        cancel()
        engine.close()
    }

    private fun selectedSpec(): AsrModelSpec? {
        return modelManager.spec(prefs.voice.modelId.get()) ?: modelManager.models().firstOrNull()
    }

    private fun commit(text: String) {
        val cleaned = text.replace('\n', ' ').trim()
        if (cleaned.isEmpty()) return
        val controller = imeControllerProvider() ?: return
        controller.updateStateBlocking {
            if (state.editor.getTextBeforeCursor(1).lastOrNull()?.isLetterOrDigit() == true) {
                emitText(" ".asK3String())
            }
            emitText(cleaned.asK3String())
        }
    }

    companion object {
        private const val MIN_DURATION_S = 5
        private const val MAX_DURATION_S = 120
        private const val MIN_SAMPLES = AudioRecorder.SAMPLE_RATE / 3
    }
}
