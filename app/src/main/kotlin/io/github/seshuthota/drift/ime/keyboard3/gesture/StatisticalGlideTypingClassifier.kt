/*
 * Copyright (C) 2020-2026 The FlorisBoard Contributors
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

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A key as the glide classifier needs it: a character code and the key centre/size in the same
 * coordinate space as the gesture points. Coordinates are normalized to the keyboard box (0..1).
 */
data class GlideKey(
    val code: Int,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
)

/**
 * Turns raw pointer samples into word candidates. Implementations must be safe to call from a
 * background thread and must not touch Android APIs.
 */
interface GlideTypingClassifier {
    val ready: Boolean

    /** Word corpus for the active subtype, plus a lazily-consulted frequency lookup. */
    fun setWordData(words: List<String>, frequencyOf: (String) -> Double)

    /** Key geometry of the active layout, in normalized keyboard coordinates. */
    fun setLayout(keys: List<GlideKey>)

    fun addGesturePoint(point: GesturePoint)

    fun getSuggestions(maxSuggestionCount: Int): List<String>

    /**
     * Optional extra score per candidate word (e.g. a weighted next-word language-model log-probability given
     * the text before the cursor). Implementations that cannot use it ignore it.
     */
    fun setContextScorer(scorer: ((String) -> Double)?) {}

    fun clear()
}

/**
 * Classifies gestures by comparing them with an "ideal gesture".
 *
 * Check out Étienne Desticourt's excellent write up at
 * https://github.com/AnySoftKeyboard/AnySoftKeyboard/pull/1870
 */
class StatisticalGlideTypingClassifier : GlideTypingClassifier {
    private var keysByCharacter: HashMap<Int, GlideKey> = HashMap()
    private var keys: List<GlideKey> = emptyList()
    private var words: List<String> = emptyList()
    private var frequencyOf: (String) -> Double = { 0.0 }

    private var pruner: Pruner? = null
    private var minKeyWidth: Float = 0f

    private val gesture = Gesture()

    override val ready: Boolean
        get() = keys.isNotEmpty() && words.isNotEmpty()

    override fun setWordData(words: List<String>, frequencyOf: (String) -> Double) {
        this.words = words
        this.frequencyOf = frequencyOf
        this.pruner = null
    }

    override fun setLayout(keys: List<GlideKey>) {
        this.keys = keys
        this.keysByCharacter = HashMap(keys.size)
        keys.forEach { keysByCharacter[it.code] = it }
        this.minKeyWidth = keys.minOfOrNull { min(it.width, it.height) } ?: 0f
        this.pruner = null
    }

    override fun addGesturePoint(point: GesturePoint) {
        if (gesture.isEmpty) {
            gesture.addPoint(point.x, point.y)
            return
        }
        val dx = gesture.getLastX() - point.x
        val dy = gesture.getLastY() - point.y
        val threshold = minKeyWidth / 4f
        if (dx * dx + dy * dy > threshold * threshold) {
            gesture.addPoint(point.x, point.y)
        }
    }

    override fun getSuggestions(maxSuggestionCount: Int): List<String> {
        if (!ready || gesture.isEmpty) return emptyList()
        val localPruner = pruner ?: Pruner(PRUNING_LENGTH_THRESHOLD, START_END_KEY_WINDOW, words, keysByCharacter).also { pruner = it }
        val key = keys.firstOrNull() ?: return emptyList()
        val radius = min(key.width, key.height)

        val candidates = localPruner.pruneByExtremities(gesture, keys)
        val userGesture = gesture.resample(SAMPLING_POINTS)
        val normalizedUserGesture = userGesture.normalizeByBoxSide()
        val lengthPruned = localPruner.pruneByLength(gesture, candidates, keysByCharacter, keys, radius)

        val locationStd = LOCATION_STD * radius
        val bestCost = HashMap<String, Float>()
        for ((word, idealGestures) in lengthPruned) {
            val frequencyCost = ln(1.0 + 255.0 * frequencyOf(word)).toFloat()
            for (idealGesture in idealGestures) {
                val wordGesture = idealGesture.resample(SAMPLING_POINTS)
                val normalizedWordGesture = wordGesture.normalizeByBoxSide()
                val shapeDistance = calcShapeDistance(normalizedWordGesture, normalizedUserGesture)
                val locationDistance = calcLocationDistance(wordGesture, userGesture)
                // Negative log likelihood: gaussian fit costs plus a weighted word prior.
                val shapeCost = shapeDistance * shapeDistance / (2f * SHAPE_STD * SHAPE_STD)
                val locationCost = locationDistance * locationDistance / (2f * locationStd * locationStd)
                val cost = shapeCost + locationCost + FREQUENCY_WEIGHT * frequencyCost
                if (cost.isFinite()) {
                    bestCost.merge(word, cost, ::minOf)
                }
            }
        }

        return bestCost.entries
            .sortedWith(compareBy({ it.value }, { it.key }))
            .take(maxSuggestionCount)
            .map { it.key }
    }

    override fun clear() {
        gesture.clear()
    }

    private fun calcLocationDistance(gesture1: Gesture, gesture2: Gesture): Float {
        var totalDistance = 0.0f
        for (i in 0 until SAMPLING_POINTS) {
            val distance = abs(gesture1.getX(i) - gesture2.getX(i)) + abs(gesture1.getY(i) - gesture2.getY(i))
            totalDistance += distance
        }
        return totalDistance / SAMPLING_POINTS / 2
    }

    private fun calcShapeDistance(gesture1: Gesture, gesture2: Gesture): Float {
        var totalDistance = 0.0f
        for (i in 0 until SAMPLING_POINTS) {
            totalDistance += Gesture.distance(
                gesture1.getX(i),
                gesture1.getY(i),
                gesture2.getX(i),
                gesture2.getY(i),
            )
        }
        return totalDistance
    }

    private class Pruner(
        private val lengthThreshold: Double,
        private val startEndWindow: Int,
        words: List<String>,
        keysByCharacter: Map<Int, GlideKey>,
    ) {
        /** Fast access to words by their first and last character. */
        private val wordTree = HashMap<Pair<Int, Int>, ArrayList<String>>()

        init {
            for (word in words) {
                val keyPair = getFirstKeyLastKey(word, keysByCharacter) ?: continue
                wordTree.getOrPut(keyPair) { arrayListOf() }.add(word)
            }
        }

        fun pruneByExtremities(userGesture: Gesture, keys: List<GlideKey>): ArrayList<String> {
            val remainingWords = ArrayList<String>()
            val startKeys = findNClosestKeys(userGesture.getFirstX(), userGesture.getFirstY(), startEndWindow, keys)
            val endKeys = findNClosestKeys(userGesture.getLastX(), userGesture.getLastY(), startEndWindow, keys)
            for (startKey in startKeys) {
                for (endKey in endKeys) {
                    wordTree[Pair(startKey, endKey)]?.let { remainingWords.addAll(it) }
                }
            }
            return remainingWords
        }

        fun pruneByLength(
            userGesture: Gesture,
            words: ArrayList<String>,
            keysByCharacter: Map<Int, GlideKey>,
            keys: List<GlideKey>,
            radius: Float,
        ): List<Pair<String, List<Gesture>>> {
            val key = keys.firstOrNull() ?: return emptyList()
            val userLength = userGesture.getLength()
            val remainingWords = ArrayList<Pair<String, List<Gesture>>>()
            for (word in words) {
                val idealGestures = Gesture.generateIdealGestures(word, keysByCharacter)
                for (idealGesture in idealGestures) {
                    if (abs(userLength - idealGesture.getLength()) < lengthThreshold * radius) {
                        remainingWords.add(word to idealGestures)
                        break
                    }
                }
            }
            return remainingWords
        }

        private companion object {
            fun getFirstKeyLastKey(word: String, keysByCharacter: Map<Int, GlideKey>): Pair<Int, Int>? {
                if (word.isEmpty()) return null
                val firstCode = baseCodeOf(word[0]) ?: return null
                val lastCode = baseCodeOf(word[word.length - 1]) ?: return null
                if (!keysByCharacter.containsKey(firstCode) || !keysByCharacter.containsKey(lastCode)) {
                    return null
                }
                return firstCode to lastCode
            }

            fun baseCodeOf(char: Char): Int? {
                val lower = char.lowercaseChar()
                val base = Normalizer.normalize(lower.toString(), Normalizer.Form.NFD).firstOrNull() ?: return null
                return base.code
            }

            fun findNClosestKeys(x: Float, y: Float, n: Int, keys: List<GlideKey>): List<Int> {
                return keys
                    .sortedBy { Gesture.distance(it.centerX, it.centerY, x, y) }
                    .take(n)
                    .map { it.code }
            }
        }
    }

    private class Gesture(
        private val xs: FloatArray = FloatArray(MAX_SIZE),
        private val ys: FloatArray = FloatArray(MAX_SIZE),
        private var size: Int = 0,
    ) {
        val isEmpty: Boolean
            get() = size == 0

        fun addPoint(x: Float, y: Float) {
            if (size >= MAX_SIZE) return
            xs[size] = x
            ys[size] = y
            size += 1
        }

        fun resample(numPoints: Int): Gesture {
            val resampled = Gesture()
            if (size == 0) return resampled
            if (size == 1) {
                repeat(numPoints) { resampled.addPoint(xs[0], ys[0]) }
                return resampled
            }
            val interpointDistance = (getLength() / numPoints).coerceAtLeast(0.00001f)
            resampled.addPoint(xs[0], ys[0])
            var lastX = xs[0]
            var lastY = ys[0]
            var cumulativeError = 0.0f

            for (i in 0 until size - 1) {
                var dx = xs[i + 1] - xs[i]
                var dy = ys[i + 1] - ys[i]
                val norm = sqrt(dx.pow(2.0f) + dy.pow(2.0f))
                if (norm <= 0f) continue
                dx /= norm
                dy /= norm

                var numNewPoints = norm / interpointDistance
                cumulativeError += numNewPoints - numNewPoints.toInt()
                if (cumulativeError > 1) {
                    numNewPoints = numNewPoints.toInt() + cumulativeError.toInt().toFloat()
                    cumulativeError %= 1f
                }
                for (j in 0 until numNewPoints.toInt()) {
                    lastX += dx * interpointDistance
                    lastY += dy * interpointDistance
                    resampled.addPoint(lastX, lastY)
                }
            }
            return resampled
        }

        fun normalizeByBoxSide(): Gesture {
            if (size == 0) return Gesture()
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            for (i in 0 until size) {
                maxX = max(xs[i], maxX)
                maxY = max(ys[i], maxY)
                minX = min(xs[i], minX)
                minY = min(ys[i], minY)
            }
            val longestSide = max(max(maxX - minX, maxY - minY), 0.00001f)
            val centroidX = ((maxX - minX) / 2 + minX) / longestSide
            val centroidY = ((maxY - minY) / 2 + minY) / longestSide

            val normalized = Gesture()
            for (i in 0 until size) {
                normalized.addPoint(xs[i] / longestSide - centroidX, ys[i] / longestSide - centroidY)
            }
            return normalized
        }

        fun getFirstX(): Float = xs.getOrElse(0) { 0f }
        fun getFirstY(): Float = ys.getOrElse(0) { 0f }
        fun getLastX(): Float = xs.getOrElse(size - 1) { 0f }
        fun getLastY(): Float = ys.getOrElse(size - 1) { 0f }

        fun getLength(): Float {
            var length = 0f
            for (i in 1 until size) {
                length += distance(xs[i - 1], ys[i - 1], xs[i], ys[i])
            }
            return length
        }

        fun clear() {
            size = 0
        }

        fun getX(i: Int): Float = xs.getOrElse(i) { 0f }
        fun getY(i: Int): Float = ys.getOrElse(i) { 0f }

        companion object {
            private const val MAX_SIZE = 500

            fun generateIdealGestures(word: String, keysByCharacter: Map<Int, GlideKey>): List<Gesture> {
                val idealGesture = Gesture()
                val idealGestureWithLoops = Gesture()
                var previousLetter = '\u0000'
                var hasLoops = false

                for (c in word) {
                    val lower = c.lowercaseChar()
                    var key = keysByCharacter[lower.code]
                    if (key == null) {
                        val base = Normalizer.normalize(lower.toString(), Normalizer.Form.NFD).firstOrNull()
                        key = base?.let { keysByCharacter[it.code] }
                        if (key == null) continue
                    }

                    if (previousLetter == lower) {
                        // Loops distinguish words with repeated letters, e.g. pool vs poll.
                        idealGestureWithLoops.addPoint(key.centerX + key.width / 4.0f, key.centerY + key.height / 4.0f)
                        idealGestureWithLoops.addPoint(key.centerX + key.width / 4.0f, key.centerY - key.height / 4.0f)
                        idealGestureWithLoops.addPoint(key.centerX - key.width / 4.0f, key.centerY - key.height / 4.0f)
                        idealGestureWithLoops.addPoint(key.centerX - key.width / 4.0f, key.centerY + key.height / 4.0f)
                        hasLoops = true
                        idealGesture.addPoint(key.centerX, key.centerY)
                    } else {
                        idealGesture.addPoint(key.centerX, key.centerY)
                        idealGestureWithLoops.addPoint(key.centerX, key.centerY)
                    }
                    previousLetter = lower
                }
                return if (hasLoops) listOf(idealGesture, idealGestureWithLoops) else listOf(idealGesture)
            }

            fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
                return sqrt((x1 - x2).pow(2) + (y1 - y2).pow(2))
            }
        }
    }

    private companion object {
        /** Allowed gesture length variance, as a factor of the key radius. */
        private const val PRUNING_LENGTH_THRESHOLD = 16.0

        /** How many nearest keys are kept at each end of the gesture during pruning. */
        private const val START_END_KEY_WINDOW = 3

        /** Weight of the word frequency prior relative to the geometric fit costs. */
        private const val FREQUENCY_WEIGHT = 0.1f

        /** Resolution of the resampled gesture. */
        private const val SAMPLING_POINTS = 200

        /** Std. deviation for normalized shapes; independent of keyboard and key size. */
        private const val SHAPE_STD = 22.08f

        /** Std. deviation for locations, as a factor of the key radius. */
        private const val LOCATION_STD = 0.5109f
    }
}
