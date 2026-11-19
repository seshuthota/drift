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
 * Captures mono PCM16 at 16 kHz and hands every ~100 ms chunk to [onAudio] as it is read. Nothing is buffered and
 * nothing is written to disk.
 *
 * [stop] blocks until the capture thread has finished (at most about one read buffer, 100 ms).
 */
class AudioRecorder(private val context: Context) {
    private val _amplitude = MutableStateFlow(0f)

    /** Recent level, 0..1 on a decibel scale. Updated about every 100 ms while recording, reset to 0 on stop. */
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    /** Called from the capture thread when the capture ends without [stop], e.g. another app took the microphone. */
    @Volatile
    var onInterrupted: (() -> Unit)? = null

    /** Called from the capture thread with each ~100 ms chunk. Must not block or call [stop]. */
    @Volatile
    var onAudio: ((pcm: ShortArray) -> Unit)? = null

    @Volatile
    private var recording = false

    val isRecording: Boolean get() = recording

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Starts capturing from [audioSource] (a `MediaRecorder.AudioSource` value). Returns false when the permission is
     * missing or the microphone is busy.
     */
    fun start(audioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION): Boolean {
        if (recording || !hasPermission()) return false
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return false
        val audioRecord = try {
            AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
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
        record = audioRecord
        recording = true
        return try {
            audioRecord.startRecording()
            thread = Thread({ readLoop(audioRecord) }, "drift-asr-recorder").apply { start() }
            true
        } catch (_: Exception) {
            recording = false
            audioRecord.release()
            record = null
            false
        }
    }

    /** Stops the capture. Synchronized: the IME thread may cancel while a stop is running. Safe to call twice. */
    @Synchronized
    fun stop() {
        val audioRecord = record ?: return
        recording = false
        record = null
        try {
            audioRecord.stop()
        } catch (_: Exception) {
            // A stopped or uninitialised record throws.
        }
        thread?.let { runCatching { it.join(1_000) } }
        thread = null
        runCatching { audioRecord.release() }
        _amplitude.value = 0f
    }

    private fun readLoop(audioRecord: AudioRecord) {
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
            _amplitude.value = ((db - METER_FLOOR_DB) / (METER_CEIL_DB - METER_FLOOR_DB)).coerceIn(0f, 1f)
            peakDb = maxOf(peakDb, db)
            if (++peakChunks == LEVEL_LOG_CHUNKS) {
                flogInfo { "voice: level peak %.1f dBFS".format(peakDb) }
                peakDb = -120f
                peakChunks = 0
            }
            onAudio?.invoke(chunk)
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

fun Context.hasRecordAudioPermission(): Boolean {
    return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
}
