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

package io.github.seshuthota.drift.ime.nlp.latin

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.LongBuffer
import kotlin.math.exp
import kotlin.math.ln

/**
 * On-device next-word language model (tiny word-level LSTM, see `ml/nwp/README.md`). Given the text before the
 * cursor it returns log-probabilities over its 20k-word vocabulary. Thread-safe; the ONNX session is created lazily.
 */
class NextWordPredictor(private val context: Context) {
    companion object {
        private const val TAG = "DriftNwp"
        private const val MODEL_ASSET = "nwp/nwp_lstm.int8.onnx"
        private const val VOCAB_ASSET = "nwp/vocab.txt"
        const val CONTEXT_TOKENS = 16
    }

    data class Prediction(val word: String, val logProb: Float)

    private val lock = Any()
    private var session: OrtSession? = null
    private var failed = false
    private var vocab: Array<String> = emptyArray()
    private var idOf: HashMap<String, Int> = HashMap()

    val vocabulary: Array<String>
        get() = vocab

    /** Loads the model and vocabulary; returns false if they are unavailable. Call off the main thread. */
    fun ensureLoaded(): Boolean = synchronized(lock) {
        if (session != null) return true
        if (failed) return false
        return try {
            val words = context.assets.open(VOCAB_ASSET).bufferedReader().readLines().filter { it.isNotEmpty() }
            val map = HashMap<String, Int>(words.size * 2)
            words.forEachIndexed { i, w -> if (i >= NwpText.N_SPECIAL) map[w] = i }
            val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }
            session = OrtEnvironment.getEnvironment().createSession(bytes, options)
            vocab = words.toTypedArray()
            idOf = map
            Log.i(TAG, "next-word model ready (${bytes.size} bytes, ${words.size} words)")
            true
        } catch (t: Throwable) {
            failed = true
            Log.w(TAG, "next-word model failed to load", t)
            false
        }
    }

    fun idOf(word: String): Int? = idOf[word]

    /**
     * Log-probabilities (natural log, softmax over the vocabulary) of the word following [textBefore], or null if
     * the model is unavailable. [truncated] = [textBefore] does not start at the beginning of the field.
     */
    fun nextWordLogProbs(textBefore: CharSequence, truncated: Boolean): FloatArray? {
        if (!ensureLoaded()) return null
        val ids = NwpText.contextIds(textBefore, { idOf[it] }, truncated, CONTEXT_TOKENS)
        val logits = synchronized(lock) {
            val session = session ?: return null
            try {
                val env = OrtEnvironment.getEnvironment()
                val input = LongBuffer.wrap(LongArray(ids.size) { ids[it].toLong() })
                OnnxTensor.createTensor(env, input, longArrayOf(1, ids.size.toLong())).use { tensor ->
                    session.run(mapOf("ids" to tensor)).use { result ->
                        val buffer = (result.get(0) as OnnxTensor).floatBuffer
                        FloatArray(buffer.remaining()).also { buffer.get(it) }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "next-word inference failed", t)
                return null
            }
        }
        // In-place log-softmax.
        var max = Float.NEGATIVE_INFINITY
        for (v in logits) if (v > max) max = v
        var sum = 0.0
        for (v in logits) sum += exp((v - max).toDouble())
        val logZ = max + ln(sum).toFloat()
        for (i in logits.indices) logits[i] -= logZ
        return logits
    }

    /**
     * Top [maxCount] vocabulary words after [textBefore]; if [prefix] is non-empty only words starting with it
     * (case-insensitive) are considered. Special tokens are never returned.
     */
    fun predict(
        textBefore: CharSequence,
        truncated: Boolean,
        prefix: String,
        maxCount: Int,
        allowed: (String) -> Boolean = { true },
    ): List<Prediction> {
        val logProbs = nextWordLogProbs(textBefore, truncated) ?: return emptyList()
        return predict(logProbs, prefix, maxCount, allowed)
    }

    /** As [predict], over log-probabilities already computed by [nextWordLogProbs]. */
    fun predict(
        logProbs: FloatArray,
        prefix: String,
        maxCount: Int,
        allowed: (String) -> Boolean = { true },
    ): List<Prediction> {
        val p = NwpText.normalize(prefix)
        val best = ArrayList<Prediction>(maxCount + 1)
        for (i in NwpText.N_SPECIAL until logProbs.size.coerceAtMost(vocab.size)) {
            val lp = logProbs[i]
            if (best.size == maxCount && lp <= best.last().logProb) continue
            val word = vocab[i]
            if (!allowed(word)) continue
            if (p.isNotEmpty() && !word.startsWith(p)) continue
            var at = best.size
            while (at > 0 && best[at - 1].logProb < lp) at--
            best.add(at, Prediction(word, lp))
            if (best.size > maxCount) best.removeAt(best.size - 1)
        }
        return best
    }
}
