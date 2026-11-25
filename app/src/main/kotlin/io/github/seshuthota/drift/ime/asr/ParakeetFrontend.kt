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

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * NeMo's `AudioToMelSpectrogramPreprocessor` as Parakeet TDT 0.6B v3 uses it, for 16 kHz mono input: pre-emphasis
 * 0.97, a symmetric 400-sample Hann window in a 512-point FFT, 160-sample hop, centre-padded by reflection, 128
 * Slaney mel bands, `ln(power + 2^-24)`, then per-band mean/variance normalisation over the whole clip.
 */
class ParakeetFrontend {
    private val window = FloatArray(WIN_LENGTH) { (0.5 - 0.5 * cos(2.0 * PI * it / (WIN_LENGTH - 1))).toFloat() }
    private val cosTable = FloatArray(N_FFT / 2) { cos(2.0 * PI * it / N_FFT).toFloat() }
    private val sinTable = FloatArray(N_FFT / 2) { -sin(2.0 * PI * it / N_FFT).toFloat() }
    private val filters: Array<FloatArray> = melFilterbank()

    /** Number of feature frames for [samples] samples. */
    fun frameCount(samples: Int): Int = samples / HOP + 1

    /** Returns a row-major `[N_MELS][frames]` array of normalised log-mel features, or null for an empty clip. */
    fun features(pcm: FloatArray): Pair<FloatArray, Int>? {
        if (pcm.isEmpty()) return null
        val frames = frameCount(pcm.size)

        val emphasised = FloatArray(pcm.size)
        emphasised[0] = pcm[0]
        for (i in 1 until pcm.size) emphasised[i] = pcm[i] - PREEMPHASIS * pcm[i - 1]

        val pad = N_FFT / 2
        val padded = FloatArray(emphasised.size + 2 * pad)
        for (i in padded.indices) padded[i] = emphasised[reflect(i - pad, emphasised.size)]

        val out = FloatArray(N_MELS * frames)
        val re = FloatArray(N_FFT)
        val im = FloatArray(N_FFT)
        val power = FloatArray(N_FFT / 2 + 1)
        val windowOffset = (N_FFT - WIN_LENGTH) / 2
        for (t in 0 until frames) {
            val start = t * HOP
            re.fill(0f)
            im.fill(0f)
            for (i in 0 until WIN_LENGTH) re[windowOffset + i] = padded[start + windowOffset + i] * window[i]
            fft(re, im)
            for (k in power.indices) power[k] = re[k] * re[k] + im[k] * im[k]
            for (m in 0 until N_MELS) {
                val filter = filters[m]
                var sum = 0f
                for (k in power.indices) sum += filter[k] * power[k]
                out[m * frames + t] = ln(sum + LOG_GUARD)
            }
        }

        for (m in 0 until N_MELS) {
            val base = m * frames
            var mean = 0.0
            for (t in 0 until frames) mean += out[base + t]
            mean /= frames
            var variance = 0.0
            for (t in 0 until frames) variance += (out[base + t] - mean) * (out[base + t] - mean)
            val std = if (frames > 1) sqrt(variance / (frames - 1)) else 0.0
            for (t in 0 until frames) out[base + t] = ((out[base + t] - mean) / (std + NORM_GUARD)).toFloat()
        }
        return out to frames
    }

    /** In-place radix-2 FFT of [N_FFT] points. */
    private fun fft(re: FloatArray, im: FloatArray) {
        var j = 0
        for (i in 1 until N_FFT) {
            var bit = N_FFT shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= N_FFT) {
            val half = len / 2
            val step = N_FFT / len
            var i = 0
            while (i < N_FFT) {
                for (k in 0 until half) {
                    val wr = cosTable[k * step]
                    val wi = sinTable[k * step]
                    val a = i + k
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun reflect(index: Int, size: Int): Int {
        if (size == 1) return 0
        var i = index
        val period = 2 * (size - 1)
        i = ((i % period) + period) % period
        return if (i >= size) period - i else i
    }

    private fun melFilterbank(): Array<FloatArray> {
        val fMax = SAMPLE_RATE / 2.0
        val melMin = hzToMel(0.0)
        val melMax = hzToMel(fMax)
        val hz = DoubleArray(N_MELS + 2) { melToHz(melMin + (melMax - melMin) * it / (N_MELS + 1)) }
        val bins = N_FFT / 2 + 1
        return Array(N_MELS) { m ->
            val enorm = 2.0 / (hz[m + 2] - hz[m])
            FloatArray(bins) { k ->
                val f = k * SAMPLE_RATE.toDouble() / N_FFT
                val lower = (f - hz[m]) / (hz[m + 1] - hz[m])
                val upper = (hz[m + 2] - f) / (hz[m + 2] - hz[m + 1])
                (max(0.0, min(lower, upper)) * enorm).toFloat()
            }
        }
    }

    private fun hzToMel(hz: Double): Double =
        if (hz < MEL_BREAK_HZ) hz / MEL_LINEAR_HZ else MEL_BREAK_MEL + ln(hz / MEL_BREAK_HZ) / MEL_LOG_STEP

    private fun melToHz(mel: Double): Double =
        if (mel < MEL_BREAK_MEL) mel * MEL_LINEAR_HZ else MEL_BREAK_HZ * kotlin.math.exp(MEL_LOG_STEP * (mel - MEL_BREAK_MEL))

    companion object {
        const val SAMPLE_RATE = 16_000
        const val N_MELS = 128
        private const val N_FFT = 512
        private const val WIN_LENGTH = 400
        private const val HOP = 160
        private const val PREEMPHASIS = 0.97f
        private const val LOG_GUARD = 5.960464477539063E-8f // 2^-24
        private const val NORM_GUARD = 1e-5

        private const val MEL_LINEAR_HZ = 200.0 / 3.0
        private const val MEL_BREAK_HZ = 1000.0
        private const val MEL_BREAK_MEL = MEL_BREAK_HZ / MEL_LINEAR_HZ
        private val MEL_LOG_STEP = ln(6.4) / 27.0
    }
}
