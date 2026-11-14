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
 * Chooses where to cut a continuous recording into pieces for the speech model. It never ends the recording; it
 * only says what to do with the audio buffered so far. Whisper works on bounded windows and is much more accurate
 * on longer pieces, and cutting at a pause avoids splitting a word in two.
 *
 * A piece ends once at least [minSegmentMs] is buffered and the last [pauseMs] were quiet, or unconditionally once
 * [maxSegmentMs] is buffered. A piece with less than [minSpeechMs] of speech is dropped instead of transcribed,
 * because Whisper turns silence into invented text. Silence is also dropped as soon as a pause is complete, so a
 * piece never starts with seconds of dead air.
 *
 * Speech means above the larger of [MIN_SPEECH_DB] and the tracked noise floor plus [MARGIN_DB]. Pure Kotlin so it
 * is testable without a microphone.
 */
class SegmentCutter(
    private val minSegmentMs: Long = 4_000,
    private val maxSegmentMs: Long = 25_000,
    private val pauseMs: Long = 600,
    private val minSpeechMs: Long = 300,
) {
    enum class Action {
        /** Keep buffering. */
        KEEP,

        /** Transcribe everything buffered so far. */
        CUT,

        /** Discard everything buffered so far; it holds no speech. */
        DROP,
    }

    @Volatile
    private var noiseFloorDb = INITIAL_FLOOR_DB
    private var bufferedMs = 0L
    private var quietMs = 0L
    // Read by the transcription thread through hasPendingSpeech for live previews.
    @Volatile
    private var speechMs = 0L


    /** Speech in the piece most recently cut or dropped. */
    var lastSpeechMs: Long = 0
        private set

    /** Whether the audio buffered since the last cut or drop is worth transcribing. */
    val hasPendingSpeech: Boolean get() = speechMs >= minSpeechMs

    /** Speech in the audio buffered since the last cut or drop. */
    val pendingSpeechMs: Long get() = speechMs

    /** Current speech threshold in dBFS, for diagnostics. */
    val thresholdDb: Float get() = maxOf(MIN_SPEECH_DB, noiseFloorDb + MARGIN_DB)

    /** Feeds one chunk of [durationMs] at [levelDb] (dBFS, at most 0) and says what to do with the buffer. */
    fun onChunk(levelDb: Float, durationMs: Long): Action {
        bufferedMs += durationMs
        val threshold = maxOf(MIN_SPEECH_DB, noiseFloorDb + MARGIN_DB)
        if (levelDb > threshold) {
            quietMs = 0
            speechMs += durationMs
            // Rise slowly, so a constantly loud room (fan, traffic) eventually counts as background, not speech.
            noiseFloorDb = minOf(MAX_FLOOR_DB, noiseFloorDb + (levelDb - noiseFloorDb) * FLOOR_RISE)
        } else {
            quietMs += durationMs
            noiseFloorDb += (levelDb - noiseFloorDb) * FLOOR_SMOOTHING
        }
        val action = when {
            quietMs >= pauseMs && !hasPendingSpeech -> Action.DROP
            bufferedMs >= maxSegmentMs -> if (hasPendingSpeech) Action.CUT else Action.DROP
            bufferedMs >= minSegmentMs && quietMs >= pauseMs -> Action.CUT
            else -> Action.KEEP
        }
        if (action != Action.KEEP) {
            lastSpeechMs = speechMs
            bufferedMs = 0
            quietMs = 0
            speechMs = 0
        }
        return action
    }

    companion object {
        const val MIN_SPEECH_DB = -58f
        private const val MARGIN_DB = 10f
        private const val INITIAL_FLOOR_DB = -70f
        private const val MAX_FLOOR_DB = -40f
        private const val FLOOR_SMOOTHING = 0.1f
        private const val FLOOR_RISE = 0.005f
    }
}
