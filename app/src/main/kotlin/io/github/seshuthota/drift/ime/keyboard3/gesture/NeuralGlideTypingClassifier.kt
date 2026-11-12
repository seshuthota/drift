/*
 * Copyright (C) 2026 The Drift Contributors
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

package io.github.seshuthota.drift.ime.keyboard3.gesture

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer
import java.util.Arrays
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Runs the dataset-trained CTC swipe encoder (see `ml/REPORT.md`) and decodes with a
 * lexicon-constrained CTC prefix beam search over the active subtype's word list.
 *
 * All state is guarded by the caller's lock (see [GlideTypingManager]); no Android UI APIs are
 * touched. The ONNX session is created lazily off the main thread.
 */
internal class NeuralGlideTypingClassifier internal constructor(
    private val modelBytes: () -> ByteArray,
) : GlideTypingClassifier {
    constructor(context: Context) : this({
        context.assets.open(MODEL_ASSET).use { it.readBytes() }
    })
    private companion object {
        const val TAG = "DriftGlide"
        const val ALPHABET = "abcdefghijklmnopqrstuvwxyz"
        const val BLANK = 26
        const val CLASS_COUNT = 27
        const val POINTS = 64
        const val FRAMES = 32
        const val BEAM_WIDTH = 120
        const val TOP_CHARS = 12
        /**
         * Weight of [io.github.seshuthota.drift.ime.nlp.latin.LatinLanguageProvider.getFrequencyForWord], a 0–1
         * log-frequency (`ln(1 + count) / ln(1 + maxCount)`). 6.87 is `0.4 * ln(1 + maxCount)` for the shipping
         * list, the dev-tuned prior `0.4 * ln(1 + count)` from `ml/PLAN.md`.
         */
        const val FREQUENCY_WEIGHT = 6.87
        const val MIN_CHAR_PROB = 1e-7
        const val MODEL_ASSET = "swipe/ctc_swipe.onnx"
        const val MAX_WORD_LEN = 26
        const val CTC_MAX_TARGET = 32
        const val APOSTROPHE = '\''
    }

    private val environment by lazy { OrtEnvironment.getEnvironment() }
    private var session: OrtSession? = null
    private var sessionFailed = false

    private var keys: List<GlideKey> = emptyList()
    private var frequencyOf: (String) -> Double = { 0.0 }
    private var trie: SwipeTrie? = null

    /**
     * Key-sequence forms that map to more than the plain word, e.g. `dont` -> [`don't`, `dont`] and
     * `im` -> [`I'm`, `im`]. Plain words that are only spelled one way are not stored here.
     */
    private var surfaceForms: Map<String, List<String>> = emptyMap()
    private var contextScorer: ((String) -> Double)? = null

    override fun setContextScorer(scorer: ((String) -> Double)?) {
        contextScorer = scorer
    }
    private val points = ArrayList<GesturePoint>(256)
    private val pointsLock = Any()

    override val ready: Boolean
        get() = keys.isNotEmpty() && trie != null

    override fun setWordData(words: List<String>, frequencyOf: (String) -> Double) {
        Log.i(TAG, "setWordData words=${words.size}")
        this.frequencyOf = frequencyOf
        val trie = SwipeTrie()
        val plainWords = HashSet<String>(words.size * 2)
        for (word in words) {
            if (isUsableWord(word)) {
                trie.insert(word)
                plainWords.add(word)
            }
        }
        // Apostrophes are not on the letter keys, so a contraction is glided as its letters alone.
        val surfaceForms = HashMap<String, MutableList<String>>()
        for (word in words) {
            if (!word.contains(APOSTROPHE)) {
                continue
            }
            val keyForm = word.replace(APOSTROPHE.toString(), "")
            if (!isUsableWord(keyForm)) {
                continue
            }
            trie.insert(keyForm)
            val forms = surfaceForms.getOrPut(keyForm) {
                if (keyForm in plainWords) mutableListOf(keyForm) else mutableListOf()
            }
            forms.add(displayForm(word))
        }
        this.surfaceForms = surfaceForms
        this.trie = trie
        // Warm the session up while the caller is still on a background thread.
        ensureSession()
    }

    override fun setLayout(keys: List<GlideKey>) {
        this.keys = keys
    }

    override fun addGesturePoint(point: GesturePoint) {
        synchronized(pointsLock) {
            if (points.isNotEmpty()) {
                val last = points[points.size - 1]
                val dx = point.x - last.x
                val dy = point.y - last.y
                if (dx * dx + dy * dy < 1e-6f) {
                    return
                }
            }
            points.add(point)
        }
    }

    override fun clear() {
        synchronized(pointsLock) { points.clear() }
    }

    override fun snapshotPoints(): List<GesturePoint> =
        synchronized(pointsLock) { points.toList() }

    override fun getSuggestions(maxSuggestionCount: Int): List<String> =
        getSuggestionsFor(snapshotPoints(), maxSuggestionCount)

    override fun getSuggestionsFor(points: List<GesturePoint>, maxSuggestionCount: Int): List<String> {
        val trie = trie ?: return emptyList()
        if (points.size < 2) {
            return emptyList()
        }
        val session = ensureSession() ?: return emptyList()
        val features = featurize(points) ?: return emptyList()
        val startedAt = System.nanoTime()
        val logProbs = runModel(session, features) ?: return emptyList()
        val result = beamSearch(
            logProbs,
            trie,
            maxSuggestionCount,
            startX = features[0],
            startY = features[POINTS],
            endX = features[POINTS - 1],
            endY = features[POINTS * 2 - 1],
        )
        if (result.isNotEmpty()) {
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            Log.d(TAG, "decode ${elapsedMs}ms -> ${result.take(3)}")
        }
        return result
    }

    private fun ensureSession(): OrtSession? {
        session?.let { return it }
        if (sessionFailed) {
            return null
        }
        return try {
            val bytes = modelBytes()
            val options = OrtSession.SessionOptions()
            val created = environment.createSession(bytes, options)
            session = created
            Log.i(TAG, "ONNX session ready (${bytes.size} bytes)")
            created
        } catch (t: Throwable) {
            sessionFailed = true
            Log.w(TAG, "ONNX session failed", t)
            null
        }
    }

    private fun runModel(session: OrtSession, features: FloatArray): FloatArray? {
        return try {
            val tensor = OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(features),
                longArrayOf(1, 2, POINTS.toLong()),
            )
            tensor.use {
                session.run(mapOf("features" to it)).use { result ->
                    val output = result.get(0) as OnnxTensor
                    val buffer = output.floatBuffer
                    val values = FloatArray(buffer.remaining())
                    buffer.get(values)
                    values
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ONNX inference failed", t)
            null
        }
    }

    /** Resamples the collected path to 64 arc-length points in the [0,1] keyboard box. */
    private fun featurize(points: List<GesturePoint>): FloatArray? {
        val count = points.size
        if (count < 2) {
            return null
        }
        val bounds = letterBounds() ?: return null
        val minX = bounds[0]
        val maxX = bounds[1]
        val minY = bounds[2]
        val maxY = bounds[3]
        val width = maxX - minX
        val height = maxY - minY
        if (width <= 0f || height <= 0f) {
            return null
        }
        val xs = FloatArray(count)
        val ys = FloatArray(count)
        var n = 0
        var prevX = Float.NaN
        var prevY = Float.NaN
        for (point in points) {
            val x = ((point.x - minX) / width).coerceIn(0f, 1f)
            val y = ((point.y - minY) / height).coerceIn(0f, 1f)
            if (n > 0 && x == prevX && y == prevY) {
                continue
            }
            xs[n] = x
            ys[n] = y
            prevX = x
            prevY = y
            n++
        }
        if (n < 2) {
            return null
        }

        val cumulative = FloatArray(n)
        for (i in 1 until n) {
            val dx = xs[i] - xs[i - 1]
            val dy = ys[i] - ys[i - 1]
            cumulative[i] = cumulative[i - 1] + sqrt(dx * dx + dy * dy)
        }
        val total = cumulative[n - 1]
        if (total <= 1e-6f) {
            return null
        }

        val features = FloatArray(2 * POINTS)
        var segment = 0
        for (i in 0 until POINTS) {
            val target = total * i / (POINTS - 1).toFloat()
            while (segment < n - 2 && cumulative[segment + 1] < target) {
                segment++
            }
            val span = cumulative[segment + 1] - cumulative[segment]
            val t = if (span > 1e-9f) (target - cumulative[segment]) / span else 0f
            features[i] = xs[segment] + (xs[segment + 1] - xs[segment]) * t
            features[POINTS + i] = ys[segment] + (ys[segment + 1] - ys[segment]) * t
        }
        return features
    }

    /** [minX, maxX, minY, maxY] covering the a-z key rectangles of the active layout. */
    private fun letterBounds(): FloatArray? {
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var found = false
        for (key in keys) {
            if (key.code !in 'a'.code..'z'.code || key.width <= 0f || key.height <= 0f) {
                continue
            }
            minX = minOf(minX, key.centerX - key.width / 2f)
            maxX = maxOf(maxX, key.centerX + key.width / 2f)
            minY = minOf(minY, key.centerY - key.height / 2f)
            maxY = maxOf(maxY, key.centerY + key.height / 2f)
            found = true
        }
        return if (found) floatArrayOf(minX, maxX, minY, maxY) else null
    }

    private fun beamSearch(
        logProbs: FloatArray,
        trie: SwipeTrie,
        maxCount: Int,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
    ): List<String> {
        var beams = HashMap<String, DoubleArray>(256)
        beams[""] = doubleArrayOf(1.0, 0.0)

        val charIndex = IntArray(TOP_CHARS)
        val charProb = DoubleArray(TOP_CHARS)
        val next = HashMap<String, DoubleArray>(512)

        for (frame in 0 until FRAMES) {
            val base = frame * CLASS_COUNT
            var candidateCount = 0
            for (c in 0 until BLANK) {
                val p = exp(logProbs[base + c].toDouble())
                if (p < MIN_CHAR_PROB) {
                    continue
                }
                var slot = candidateCount
                while (slot > 0 && charProb[slot - 1] < p) {
                    if (slot < TOP_CHARS) {
                        charProb[slot] = charProb[slot - 1]
                        charIndex[slot] = charIndex[slot - 1]
                    }
                    slot--
                }
                if (slot < TOP_CHARS) {
                    charProb[slot] = p
                    charIndex[slot] = c
                    if (candidateCount < TOP_CHARS) {
                        candidateCount++
                    }
                }
            }
            val blankProb = exp(logProbs[base + BLANK].toDouble())

            next.clear()
            for ((prefix, state) in beams) {
                val pBlank = state[0]
                val pNonBlank = state[1]
                val total = pBlank + pNonBlank
                val lastChar = if (prefix.isEmpty()) null else prefix[prefix.length - 1]

                next.getOrPut(prefix) { DoubleArray(2) }[0] += total * blankProb

                for (k in 0 until candidateCount) {
                    val p = charProb[k]
                    val ch = ALPHABET[charIndex[k]]
                    if (lastChar != null && ch == lastChar) {
                        next.getOrPut(prefix) { DoubleArray(2) }[1] += pNonBlank * p
                        val extended = prefix + ch
                        if (trie.contains(extended)) {
                            next.getOrPut(extended) { DoubleArray(2) }[1] += pBlank * p
                        }
                    } else {
                        val extended = prefix + ch
                        if (!trie.contains(extended)) {
                            continue
                        }
                        next.getOrPut(extended) { DoubleArray(2) }[1] += total * p
                    }
                }
            }

            beams = if (next.size <= BEAM_WIDTH) {
                HashMap(next)
            } else {
                val pruned = HashMap<String, DoubleArray>(BEAM_WIDTH * 2)
                for (entry in next.entries.sortedByDescending { it.value[0] + it.value[1] }.take(BEAM_WIDTH)) {
                    pruned[entry.key] = entry.value
                }
                pruned
            }
        }

        // Every spelling of every decoded key sequence is scored on its own, so the frequency prior and the
        // context scorer can choose between e.g. `its` and `it's` that share one swipe.
        val scorer = contextScorer
        val scored = ArrayList<Pair<String, Double>>(beams.size)
        for ((prefix, state) in beams) {
            if (!trie.isWord(prefix)) {
                continue
            }
            val probability = state[0] + state[1]
            if (probability <= 0.0) {
                continue
            }
            val acoustic = ln(probability)
            val endpoints = endpointPenalty(prefix, startX, startY, endX, endY)
            for (form in surfaceForms[prefix] ?: listOf(prefix)) {
                val score = acoustic + FREQUENCY_WEIGHT * frequencyOf(form.lowercase()) -
                    GLIDE_ENDPOINT_WEIGHT * endpoints + (scorer?.invoke(form) ?: 0.0)
                scored.add(form to score)
            }
        }
        scored.sortByDescending { it.second }
        val result = LinkedHashSet<String>()
        for ((form, _) in scored) {
            result.add(form)
            if (result.size >= maxCount) {
                break
            }
        }
        return result.toList()
    }

    /** Squared distance from the stroke ends to the first and last letters, in the 0–1 letter box. */
    private fun endpointPenalty(letters: String, startX: Float, startY: Float, endX: Float, endY: Float): Float {
        if (letters.length < 2) {
            return 0f
        }
        val bounds = letterBounds() ?: return 0f
        val first = normalizedCenter(letters[0].code, bounds) ?: return 0f
        val last = normalizedCenter(letters[letters.lastIndex].code, bounds) ?: return 0f
        return glideEndpointPenalty(startX, startY, endX, endY, first.first, first.second, last.first, last.second)
    }

    private fun normalizedCenter(code: Int, bounds: FloatArray): Pair<Float, Float>? {
        val key = keys.firstOrNull { it.code == code } ?: return null
        val width = bounds[1] - bounds[0]
        val height = bounds[3] - bounds[2]
        if (width <= 0f || height <= 0f) {
            return null
        }
        val x = ((key.centerX - bounds[0]) / width).coerceIn(0f, 1f)
        val y = ((key.centerY - bounds[2]) / height).coerceIn(0f, 1f)
        return x to y
    }

    /** `i'm` -> `I'm`: the pronoun is capitalized; every other contraction keeps the dictionary spelling. */
    private fun displayForm(word: String): String {
        return if (word.startsWith("i$APOSTROPHE")) "I" + word.substring(1) else word
    }

    private fun isUsableWord(word: String): Boolean {
        if (word.length < 2 || word.length > MAX_WORD_LEN) {
            return false
        }
        var repeats = 0
        for (i in word.indices) {
            val c = word[i]
            if (c !in 'a'..'z') {
                return false
            }
            if (i > 0 && word[i - 1] == c) {
                repeats++
            }
        }
        return word.length + repeats <= CTC_MAX_TARGET
    }
}

/**
 * Cost of the stroke starting away from the first letter and ending away from the last, in squared
 * keyboard-box distance. 20 lets a straight o→k stroke rank `ok` ahead of `of`: the encoder prefers
 * `of` there by about 1.6 nats, and frequency widens that. On the sealed 4k set this moves top-1
 * from 90.33% to 90.50%, with length 2 unchanged.
 */
internal const val GLIDE_ENDPOINT_WEIGHT = 20.0

/** Squared distance from a stroke's ends to a word's first and last key centers. */
internal fun glideEndpointPenalty(
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    firstX: Float,
    firstY: Float,
    lastX: Float,
    lastY: Float,
): Float {
    val dsx = startX - firstX
    val dsy = startY - firstY
    val dex = endX - lastX
    val dey = endY - lastY
    return dsx * dsx + dsy * dsy + dex * dex + dey * dey
}

/**
 * Fallback chain: neural classifier first, statistical template matcher if the model cannot
 * produce candidates (missing asset, unsupported op, empty result).
 */
internal class HybridGlideTypingClassifier(context: Context) : GlideTypingClassifier {
    private val neural = NeuralGlideTypingClassifier(context)
    private val statistical = StatisticalGlideTypingClassifier()

    override val ready: Boolean
        get() = neural.ready || statistical.ready

    override fun setWordData(words: List<String>, frequencyOf: (String) -> Double) {
        neural.setWordData(words, frequencyOf)
        statistical.setWordData(words, frequencyOf)
    }

    override fun setLayout(keys: List<GlideKey>) {
        neural.setLayout(keys)
        statistical.setLayout(keys)
    }

    override fun setContextScorer(scorer: ((String) -> Double)?) {
        neural.setContextScorer(scorer)
    }

    override fun addGesturePoint(point: GesturePoint) {
        neural.addGesturePoint(point)
        statistical.addGesturePoint(point)
    }

    override fun getSuggestions(maxSuggestionCount: Int): List<String> {
        return getSuggestionsFor(snapshotPoints(), maxSuggestionCount)
    }

    override fun snapshotPoints(): List<GesturePoint> = neural.snapshotPoints()

    override fun getSuggestionsFor(points: List<GesturePoint>, maxSuggestionCount: Int): List<String> {
        val neuralSuggestions = neural.getSuggestionsFor(points, maxSuggestionCount)
        if (neuralSuggestions.isNotEmpty()) {
            return neuralSuggestions
        }
        Log.w("DriftGlide", "neural produced no candidates, using statistical fallback")
        return statistical.getSuggestions(maxSuggestionCount)
    }

    override fun clear() {
        neural.clear()
        statistical.clear()
    }
}

/**
 * Compact prefix trie for the beam search: nodes and edges live in parallel arrays so the whole
 * ~50k-word dictionary costs a few MB instead of a hash set of every prefix string.
 */
internal class SwipeTrie {
    private var firstEdge = IntArray(INITIAL_CAPACITY) { -1 }
    private var terminal = BooleanArray(INITIAL_CAPACITY)
    private var edgeChar = CharArray(INITIAL_CAPACITY)
    private var edgeTo = IntArray(INITIAL_CAPACITY)
    private var edgeNext = IntArray(INITIAL_CAPACITY) { -1 }
    private var nodeCount = 1
    private var edgeCount = 0

    fun insert(word: String) {
        var node = 0
        for (ch in word) {
            var edge = firstEdge[node]
            var child = -1
            while (edge != -1) {
                if (edgeChar[edge] == ch) {
                    child = edgeTo[edge]
                    break
                }
                edge = edgeNext[edge]
            }
            if (child == -1) {
                child = newNode()
                if (edgeCount == edgeChar.size) {
                    growEdges()
                }
                edgeChar[edgeCount] = ch
                edgeTo[edgeCount] = child
                edgeNext[edgeCount] = firstEdge[node]
                firstEdge[node] = edgeCount
                edgeCount++
            }
            node = child
        }
        terminal[node] = true
    }

    fun contains(text: CharSequence): Boolean {
        var node = 0
        for (i in text.indices) {
            node = childOf(node, text[i])
            if (node == -1) {
                return false
            }
        }
        return true
    }

    fun isWord(text: String): Boolean {
        var node = 0
        for (i in text.indices) {
            node = childOf(node, text[i])
            if (node == -1) {
                return false
            }
        }
        return terminal[node]
    }

    private fun childOf(node: Int, ch: Char): Int {
        var edge = firstEdge[node]
        while (edge != -1) {
            if (edgeChar[edge] == ch) {
                return edgeTo[edge]
            }
            edge = edgeNext[edge]
        }
        return -1
    }

    private fun newNode(): Int {
        if (nodeCount == terminal.size) {
            val size = terminal.size * 2
            terminal = terminal.copyOf(size)
            firstEdge = firstEdge.copyOf(size)
            Arrays.fill(firstEdge, nodeCount, size, -1)
        }
        return nodeCount++
    }

    private fun growEdges() {
        val size = edgeChar.size * 2
        edgeChar = edgeChar.copyOf(size)
        edgeTo = edgeTo.copyOf(size)
        edgeNext = edgeNext.copyOf(size)
        Arrays.fill(edgeNext, edgeCount, size, -1)
    }

    private companion object {
        const val INITIAL_CAPACITY = 1 shl 12
    }
}
