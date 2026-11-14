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

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import io.github.seshuthota.drift.lib.devtools.flogInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Captures mono PCM16 at 16 kHz, the sample rate Whisper expects.
 *
 * [stop] blocks until the capture thread has finished (at most about one read buffer, 100 ms), so call it from a
 * background coroutine. Samples are held in memory only; nothing is written to disk.
 */
class AudioRecorder(private val context: Context) {
    data class Config(
        val sampleRate: Int = SAMPLE_RATE,
        /** When set, the capture is handed out in pieces at the cutter's boundaries while it keeps running. */
        val cutter: SegmentCutter? = null,
    )

    private val _amplitude = MutableStateFlow(0f)

    /** Recent level, 0..1 on a decibel scale. Updated about every 100 ms while recording, reset to 0 on stop. */
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val lock = Any()
    private val chunks = ArrayDeque<ShortArray>()
    private var recordedSamples = 0
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var config = Config()

    /** Called from the capture thread when the capture ends without [stop], e.g. another app took the microphone. */
    @Volatile
    var onInterrupted: (() -> Unit)? = null

    /**
     * Called from the capture thread with each finished piece and how many ms of it were speech. Must not block or
     * call [stop].
     */
    @Volatile
    var onSegment: ((pcm: ShortArray, speechMs: Long) -> Unit)? = null

    @Volatile
    private var recording = false

    val isRecording: Boolean get() = recording

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Starts capturing. Returns false when the permission is missing or the microphone is busy. */
    fun start(config: Config = Config()): Boolean {
        if (recording || !hasPermission()) return false
        val sampleRate = config.sampleRate
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return false
        val audioRecord = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(minBuffer * 2)
                .build()
        } catch (_: Exception) {
            return false
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            return false
        }
        synchronized(lock) {
            chunks.clear()
            recordedSamples = 0
        }
        this.config = config
        record = audioRecord
        recording = true
        return try {
            audioRecord.startRecording()
            thread = Thread({ readLoop(audioRecord, config) }, "drift-asr-recorder").apply { start() }
            true
        } catch (_: Exception) {
            recording = false
            audioRecord.release()
            record = null
            false
        }
    }

    /**
     * Stops the capture and returns what was recorded since the last piece, or null when nothing is left or, with a
     * cutter, when what is left holds no speech. Synchronized: the IME thread may cancel while a stop is running.
     */
    @Synchronized
    fun stop(): ShortArray? {
        // The capture loop may already have ended (the microphone was taken away); the buffer is still ours.
        val audioRecord = record ?: return null
        recording = false
        record = null
        try {
            audioRecord.stop()
        } catch (_: Exception) {
            // A stopped or uninitialised record throws; the buffer below is still valid.
        }
        thread?.let { runCatching { it.join(1_000) } }
        thread = null
        runCatching { audioRecord.release() }
        _amplitude.value = 0f
        val rest = synchronized(lock) { takeBuffered() }
        val cutter = config.cutter
        if (cutter == null || cutter.hasPendingSpeech) return rest
        if (rest != null) {
            flogInfo { "voice: dropped last ${rest.size * 1_000L / config.sampleRate}ms, only ${cutter.pendingSpeechMs}ms speech" }
        }
        return null
    }

    /** Stops the capture and drops the buffer. */
    fun cancel() {
        stop()
    }

    /**
     * Copy of the audio buffered since the last piece, without consuming it, for a live preview. Null when not
     * recording, or when a cutter is set and the buffer holds no speech yet.
     */
    fun snapshot(): ShortArray? {
        if (!recording) return null
        val cutter = config.cutter
        if (cutter != null && !cutter.hasPendingSpeech) return null
        return synchronized(lock) {
            if (recordedSamples == 0) return null
            val out = ShortArray(recordedSamples)
            var offset = 0
            for (chunk in chunks) {
                chunk.copyInto(out, offset)
                offset += chunk.size
            }
            out
        }
    }

    /** The cutter's current speech threshold in dBFS, or null without a cutter. */
    val speechThresholdDb: Float? get() = config.cutter?.thresholdDb

    /** Returns and clears everything buffered, or null when empty. Caller holds [lock]. */
    private fun takeBuffered(): ShortArray? {
        if (recordedSamples == 0) return null
        val out = ShortArray(recordedSamples)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        chunks.clear()
        recordedSamples = 0
        return out
    }

    private fun readLoop(audioRecord: AudioRecord, config: Config) {
        val buffer = ShortArray(READ_SAMPLES)
        var peakDb = -120f
        var peakChunks = 0
        while (recording) {
            val read = try {
                audioRecord.read(buffer, 0, buffer.size)
            } catch (_: Exception) {
                break
            }
            if (read <= 0) break
            val chunk = buffer.copyOf(read)
            var sum = 0.0
            for (sample in chunk) {
                val value = sample.toDouble() / Short.MAX_VALUE
                sum += value * value
            }
            val rms = sqrt(sum / chunk.size).toFloat()
            val db = if (rms <= 1e-6f) -120f else 20f * log10(rms)
            // Speech sits around -40..-15 dBFS, so raw RMS (0.01..0.2) would leave the level meter nearly flat.
            _amplitude.value = ((db - METER_FLOOR_DB) / (METER_CEIL_DB - METER_FLOOR_DB)).coerceIn(0f, 1f)
            val cutter = config.cutter
            val action = cutter?.onChunk(db, read * 1_000L / config.sampleRate) ?: SegmentCutter.Action.KEEP
            peakDb = maxOf(peakDb, db)
            if (++peakChunks == LEVEL_LOG_CHUNKS) {
                val threshold = cutter?.thresholdDb
                flogInfo { "voice: level peak %.1f dBFS, speech threshold %.1f".format(peakDb, threshold ?: Float.NaN) }
                peakDb = -120f
                peakChunks = 0
            }
            if (action == SegmentCutter.Action.DROP && (cutter?.lastSpeechMs ?: 0L) > 0L) {
                flogInfo { "voice: dropped ${cutter?.lastSpeechMs}ms speech as too little" }
            }
            val segment = synchronized(lock) {
                chunks.addLast(chunk)
                recordedSamples += read
                when (action) {
                    SegmentCutter.Action.KEEP -> null
                    SegmentCutter.Action.CUT -> takeBuffered()
                    SegmentCutter.Action.DROP -> {
                        takeBuffered()
                        null
                    }
                }
            }
            if (segment != null) onSegment?.invoke(segment, cutter?.lastSpeechMs ?: 0L)
        }
        val interrupted = recording
        recording = false
        _amplitude.value = 0f
        if (interrupted) onInterrupted?.invoke()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val READ_SAMPLES = 1_600
        private const val LEVEL_LOG_CHUNKS = 10
        private const val METER_FLOOR_DB = -55f
        private const val METER_CEIL_DB = -15f
    }
}

/** True when the microphone runtime permission is granted. */
fun Context.hasRecordAudioPermission(): Boolean {
    return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
}
