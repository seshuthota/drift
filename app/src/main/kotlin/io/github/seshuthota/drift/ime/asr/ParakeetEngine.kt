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

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import io.github.seshuthota.drift.lib.devtools.flogInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import kotlin.math.abs

/**
 * Parakeet TDT (FastConformer encoder, token-and-duration transducer) through ONNX Runtime, for Fluid Speech Pro.
 *
 * It decodes whole recordings, so nothing is typed while the user speaks: [addAudio] only buffers and [stop] returns
 * the text as one [StreamEvent.Final]. Recordings over [MAX_SEGMENT_SECONDS] are cut at quiet points, because the
 * model is only validated on clips up to 30 seconds.
 */
class ParakeetEngine : StreamingAsrEngine {
    override val id: String = AsrModelRegistry.PARAKEET

    private val frontend = ParakeetFrontend()
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private var vocab: Array<String> = emptyArray()
    private var loadedModelId: String? = null

    private var buffer = ShortArray(0)
    private var size = 0
    private var running = false

    override suspend fun load(model: InstalledAsrModel) = withContext(Dispatchers.IO) {
        if (encoder != null && loadedModelId == model.spec.id) return@withContext
        close()
        val env = OrtEnvironment.getEnvironment()
        try {
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }
            vocab = readVocab(model)
            encoder = env.createSession(model.file(ENCODER_FILE).absolutePath, options)
            decoder = env.createSession(model.file(DECODER_FILE).absolutePath, options)
            loadedModelId = model.spec.id
        } catch (e: Throwable) {
            close()
            throw IllegalStateException("parakeet could not load ${model.spec.id}: ${e.message}", e)
        }
    }

    override fun start() {
        check(encoder != null) { "no parakeet model is loaded" }
        size = 0
        running = true
    }

    override fun addAudio(pcm: ShortArray): List<StreamEvent> {
        check(encoder != null) { "no parakeet model is loaded" }
        val count = minOf(pcm.size, MAX_BUFFER_SAMPLES - size)
        if (count <= 0) return emptyList()
        if (size + count > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + count, 16_000))
        System.arraycopy(pcm, 0, buffer, size, count)
        size += count
        return emptyList()
    }

    override fun stop(): List<StreamEvent> {
        if (!running) return emptyList()
        running = false
        val text = transcribeAll(buffer.copyOf(size))
        size = 0
        return if (text.isEmpty()) emptyList() else listOf(StreamEvent.Final(0, text))
    }

    override fun setContext(text: String) = Unit

    override fun transcribeAll(pcm: ShortArray): String {
        check(encoder != null) { "no parakeet model is loaded" }
        if (pcm.isEmpty()) return ""
        val samples = FloatArray(pcm.size) { pcm[it] / 32_768f }
        return segmentBounds(samples).joinToString(" ") { (from, to) ->
            transcribeSegment(samples.copyOfRange(from, to))
        }.trim()
    }

    override fun close() {
        runCatching { encoder?.close() }
        runCatching { decoder?.close() }
        encoder = null
        decoder = null
        loadedModelId = null
        running = false
        size = 0
        buffer = ShortArray(0)
    }

    private fun transcribeSegment(samples: FloatArray): String {
        val (features, frames) = frontend.features(samples) ?: return ""
        val env = OrtEnvironment.getEnvironment()
        val startedAt = System.nanoTime()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(features), longArrayOf(1, ParakeetFrontend.N_MELS.toLong(), frames.toLong())).use { audio ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(frames.toLong())), longArrayOf(1)).use { length ->
                encoder!!.run(mapOf("audio_signal" to audio, "length" to length)).use { result ->
                    val out = result[0] as OnnxTensor
                    val shape = out.info.shape // [1, 1024, T]
                    val encodedFrames = shape[2].toInt()
                    val encodedLength = ((result[1] as OnnxTensor).longBuffer.get(0)).toInt().coerceIn(0, encodedFrames)
                    val encoded = FloatArray(ENCODER_DIM * encodedFrames)
                    out.floatBuffer.get(encoded)
                    val encodeMs = (System.nanoTime() - startedAt) / 1_000_000
                    val tokens = decodeTokens(encoded, encodedFrames, encodedLength)
                    flogInfo { "voice: parakeet encode ${encodeMs}ms, ${tokens.size} tokens over $encodedLength frames" }
                    return tokensToText(tokens)
                }
            }
        }
    }

    /** Greedy TDT decoding; [encoded] is `[ENCODER_DIM][frames]` row-major. */
    private fun decodeTokens(encoded: FloatArray, frames: Int, length: Int): List<Int> {
        val env = OrtEnvironment.getEnvironment()
        val session = decoder!!
        val vocabSize = vocab.size
        val blank = vocabSize - 1
        val tokens = ArrayList<Int>()
        var state1 = FloatArray(STATE_SIZE)
        var state2 = FloatArray(STATE_SIZE)
        var previous = blank
        var emitted = 0
        var t = 0
        val frame = FloatArray(ENCODER_DIM)
        val stateShape = longArrayOf(2, 1, STATE_WIDTH)
        while (t < length) {
            for (d in 0 until ENCODER_DIM) frame[d] = encoded[d * frames + t]
            OnnxTensor.createTensor(env, FloatBuffer.wrap(frame), longArrayOf(1, ENCODER_DIM.toLong(), 1)).use { enc ->
                OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(previous)), longArrayOf(1, 1)).use { target ->
                    OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(1)), longArrayOf(1)).use { targetLength ->
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(state1), stateShape).use { s1 ->
                            OnnxTensor.createTensor(env, FloatBuffer.wrap(state2), stateShape).use { s2 ->
                                val inputs = mapOf(
                                    "encoder_outputs" to enc,
                                    "targets" to target,
                                    "target_length" to targetLength,
                                    "input_states_1" to s1,
                                    "input_states_2" to s2,
                                )
                                session.run(inputs).use { result ->
                                    val logits = FloatArray(vocabSize + DURATIONS)
                                    (result[0] as OnnxTensor).floatBuffer.get(logits)
                                    val token = argmax(logits, 0, vocabSize)
                                    val step = argmax(logits, vocabSize, vocabSize + DURATIONS) - vocabSize
                                    if (token != blank) {
                                        tokens += token
                                        previous = token
                                        state1 = FloatArray(STATE_SIZE).also { (result[2] as OnnxTensor).floatBuffer.get(it) }
                                        state2 = FloatArray(STATE_SIZE).also { (result[3] as OnnxTensor).floatBuffer.get(it) }
                                        emitted++
                                    }
                                    if (step > 0) {
                                        t += step
                                        emitted = 0
                                    } else if (token == blank || emitted >= MAX_TOKENS_PER_FRAME) {
                                        t += 1
                                        emitted = 0
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return tokens
    }

    private fun tokensToText(tokens: List<Int>): String {
        val sb = StringBuilder()
        for (token in tokens) {
            val piece = vocab.getOrNull(token) ?: continue
            if (piece.startsWith("<") && piece.endsWith(">")) continue
            sb.append(piece.replace('▁', ' '))
        }
        return sb.toString().trim()
    }

    private fun readVocab(model: InstalledAsrModel): Array<String> {
        val pieces = ArrayList<String>()
        model.file(VOCAB_FILE).forEachLine { line ->
            if (line.isEmpty()) return@forEachLine
            val split = line.lastIndexOf(' ')
            val id = line.substring(split + 1).toIntOrNull() ?: return@forEachLine
            val piece = line.substring(0, split)
            while (pieces.size <= id) pieces += ""
            pieces[id] = piece
        }
        check(pieces.size > 1) { "vocabulary is empty" }
        return pieces.toTypedArray()
    }

    private fun argmax(values: FloatArray, from: Int, to: Int): Int {
        var best = from
        for (i in from + 1 until to) if (values[i] > values[best]) best = i
        return best
    }

    companion object {
        const val ENCODER_FILE = "encoder-model.int8.onnx"
        const val DECODER_FILE = "decoder_joint-model.int8.onnx"
        const val VOCAB_FILE = "vocab.txt"

        private const val ENCODER_DIM = 1024
        private const val STATE_WIDTH = 640L
        private const val STATE_SIZE = (2 * STATE_WIDTH).toInt()
        private const val DURATIONS = 5
        private const val MAX_TOKENS_PER_FRAME = 10

        /** Longest span decoded at once, and the window in which a longer recording is cut. */
        const val MAX_SEGMENT_SECONDS = 30
        private const val SEARCH_SECONDS = 5

        /** Audio past this is dropped; three minutes is already a long wait for a 0.6B model on a phone. */
        private const val MAX_BUFFER_SAMPLES = ParakeetFrontend.SAMPLE_RATE * 180

        /**
         * Splits [samples] into spans of at most [MAX_SEGMENT_SECONDS], each cut at the quietest 100 ms in the last
         * [SEARCH_SECONDS] seconds of its window so words are not split.
         */
        fun segmentBounds(samples: FloatArray): List<Pair<Int, Int>> {
            val rate = ParakeetFrontend.SAMPLE_RATE
            val maxLen = MAX_SEGMENT_SECONDS * rate
            val block = rate / 10
            val out = ArrayList<Pair<Int, Int>>()
            var start = 0
            while (samples.size - start > maxLen) {
                val searchFrom = start + maxLen - SEARCH_SECONDS * rate
                var bestAt = start + maxLen
                var bestEnergy = Float.MAX_VALUE
                var at = searchFrom
                while (at + block <= start + maxLen) {
                    var energy = 0f
                    for (i in at until at + block) energy += abs(samples[i])
                    if (energy < bestEnergy) {
                        bestEnergy = energy
                        bestAt = at + block / 2
                    }
                    at += block
                }
                out += start to bestAt
                start = bestAt
            }
            out += start to samples.size
            return out
        }
    }
}
