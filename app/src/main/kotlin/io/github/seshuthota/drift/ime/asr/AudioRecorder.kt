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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * Captures mono PCM16 at 16 kHz, the sample rate Whisper expects.
 *
 * [stop] blocks until the capture thread has finished (at most about one read buffer, 100 ms), so call it from a
 * background coroutine. Samples are held in memory only; nothing is written to disk.
 */
class AudioRecorder(private val context: Context) {
    data class Config(
        val maxDurationMs: Long = 30_000,
        val sampleRate: Int = SAMPLE_RATE,
    )

    private val _amplitude = MutableStateFlow(0f)

    /** Recent RMS level, 0..1. Updated about every 100 ms while recording, reset to 0 on stop. */
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val lock = Any()
    private val chunks = ArrayDeque<ShortArray>()
    private var recordedSamples = 0
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var config = Config()

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

    /** Stops the capture and returns everything recorded, or null when nothing was captured. */
    fun stop(): ShortArray? {
        if (!recording) return null
        recording = false
        val audioRecord = record
        record = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
            // A stopped or uninitialised record throws; the buffer below is still valid.
        }
        thread?.let { runCatching { it.join(1_000) } }
        thread = null
        runCatching { audioRecord?.release() }
        _amplitude.value = 0f
        return synchronized(lock) {
            if (recordedSamples == 0) {
                null
            } else {
                val out = ShortArray(recordedSamples)
                var offset = 0
                for (chunk in chunks) {
                    chunk.copyInto(out, offset)
                    offset += chunk.size
                }
                chunks.clear()
                recordedSamples = 0
                out
            }
        }
    }

    /** Stops the capture and drops the buffer. */
    fun cancel() {
        stop()
    }

    private fun readLoop(audioRecord: AudioRecord, config: Config) {
        val buffer = ShortArray(READ_SAMPLES)
        val maxSamples = (config.maxDurationMs * config.sampleRate / 1_000L).toInt().coerceAtLeast(READ_SAMPLES)
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
            _amplitude.value = sqrt(sum / chunk.size).toFloat().coerceIn(0f, 1f)
            val full = synchronized(lock) {
                chunks.addLast(chunk)
                recordedSamples += read
                recordedSamples >= maxSamples
            }
            if (full) break
        }
        recording = false
        _amplitude.value = 0f
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val READ_SAMPLES = 1_600
    }
}

/** True when the microphone runtime permission is granted. */
fun Context.hasRecordAudioPermission(): Boolean {
    return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
}
