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

import kotlin.math.abs

/**
 * Gain for streamed audio, chunk by chunk. Some phones deliver speech around -45 dBFS on the voice-recognition
 * source; this lifts it so the loudest recent sample sits near [targetPeak] of full scale, at most [maxGain] times.
 *
 * The tracked peak jumps up at once (so a loud chunk is never clipped) and decays over about a second, so the gain
 * does not pump up in every short pause between words.
 */
class AutoGain(
    private val maxGain: Float = 16f,
    private val targetPeak: Float = 0.5f,
    private val decayPerChunk: Float = 0.95f,
) {
    private var envelope = 0f

    var gain: Float = 1f
        private set

    fun process(pcm: ShortArray): ShortArray {
        var peak = 0
        for (sample in pcm) peak = maxOf(peak, abs(sample.toInt()))
        envelope = maxOf(peak.toFloat(), envelope * decayPerChunk)
        val target = targetPeak * Short.MAX_VALUE
        gain = if (envelope <= 0f) maxGain else (target / envelope).coerceIn(1f, maxGain)
        if (gain <= 1.01f) return pcm
        return ShortArray(pcm.size) { i ->
            (pcm[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}
