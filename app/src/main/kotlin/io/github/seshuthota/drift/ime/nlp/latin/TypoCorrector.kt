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

import kotlin.math.hypot

/**
 * Typing-error correction over a word list. Pure Kotlin.
 *
 * Candidates are dictionary words within a keyboard-aware Damerau-Levenshtein distance of the typed word, found by
 * a depth-first walk of a compact trie that keeps one DP row per depth and prunes once a row exceeds the budget.
 * Edit costs reflect how people mistype on a touch keyboard:
 * - substituting a neighbouring key is cheap, a far key costs a full edit;
 * - a missed apostrophe (`dont` → `don't`) is almost free, a missed doubled letter is cheap;
 * - an extra key next to (or equal to) the previous one is cheap (fat-finger double hit);
 * - swapping two adjacent letters is one cheap edit;
 * - anything that changes the first letter costs extra, since people rarely get it wrong.
 *
 * Candidates are ranked by `-EDIT_WEIGHT * cost + frequency / FREQ_DIVISOR + lmWeight * lmLogProb`, where frequency
 * is the app dictionary's 0–255 value and lmLogProb an optional next-word log-probability.
 */
class TypoCorrector(words: Map<String, Int>, keyCenters: Map<Char, Pair<Float, Float>> = QWERTY_CENTERS) {
    data class Correction(val word: String, val cost: Float, val score: Float)

    // Trie as parallel arrays; children of a node are a linked list (firstChild / nextSibling) sorted by char.
    private val nodeChar: CharArray
    private val firstChild: IntArray
    private val nextSibling: IntArray
    private val nodeWord: IntArray // index into [wordList] or -1
    private val wordList: Array<String>
    private val wordFreq: IntArray

    private val keyX = FloatArray(128) { Float.NaN }
    private val keyY = FloatArray(128) { Float.NaN }

    init {
        for ((c, xy) in keyCenters) {
            if (c.code < 128) {
                keyX[c.code] = xy.first
                keyY[c.code] = xy.second
            }
        }
        val sorted = words.keys.filter { it.isNotEmpty() }.sorted()
        wordList = sorted.toTypedArray()
        wordFreq = IntArray(sorted.size) { words.getValue(sorted[it]) }
        val chars = ArrayList<Char>()
        val first = ArrayList<Int>()
        val next = ArrayList<Int>()
        val word = ArrayList<Int>()
        fun newNode(c: Char): Int {
            chars.add(c); first.add(-1); next.add(-1); word.add(-1)
            return chars.size - 1
        }
        newNode('\u0000')
        for ((index, w) in sorted.withIndex()) {
            var node = 0
            for (c in w) {
                var child = first[node]
                var last = -1
                while (child != -1 && chars[child] != c) {
                    last = child
                    child = next[child]
                }
                if (child == -1) {
                    child = newNode(c)
                    if (last == -1) first[node] = child else next[last] = child
                }
                node = child
            }
            word[node] = index
        }
        nodeChar = chars.toCharArray()
        firstChild = first.toIntArray()
        nextSibling = next.toIntArray()
        nodeWord = word.toIntArray()
    }

    val size: Int get() = wordList.size

    fun contains(word: String): Boolean = find(word) >= 0

    fun frequencyOf(word: String): Int = find(word).let { if (it >= 0) wordFreq[it] else 0 }

    /** True if some dictionary word longer than [prefix] starts with it. */
    fun hasCompletion(prefix: String): Boolean = findNode(prefix).let { it >= 0 && firstChild[it] != -1 }

    private fun find(word: String): Int = findNode(word).let { if (it >= 0) nodeWord[it] else -1 }

    private fun findNode(word: String): Int {
        var node = 0
        for (c in word) {
            var child = firstChild[node]
            while (child != -1 && nodeChar[child] != c) child = nextSibling[child]
            if (child == -1) return -1
            node = child
        }
        return node
    }

    /**
     * Up to [maxCount] corrections for the lowercase [typed] word, best first. The typed word itself is included
     * (cost 0) if it is in the dictionary. [lmLogProb] returns a next-word log-probability or null if unknown.
     */
    fun correct(
        typed: String,
        maxCount: Int,
        lmLogProb: ((String) -> Float?)? = null,
        lmWeight: Float = LM_WEIGHT,
    ): List<Correction> {
        val n = typed.length
        if (n == 0 || maxCount <= 0) return emptyList()
        val budget = maxCostFor(n)
        val t = typed.toCharArray()
        // Extra-key cost per typed position (independent of the dictionary word).
        val extra = FloatArray(n) { j -> extraCost(t, j) }
        val maxDepth = n + (budget / MIN_OMIT_COST).toInt() + 1
        val rows = Array(maxDepth + 1) { FloatArray(n + 1) }
        val row0 = rows[0]
        row0[0] = 0f
        for (j in 1..n) row0[j] = row0[j - 1] + extra[j - 1]
        val path = CharArray(maxDepth + 1)
        val results = ArrayList<Correction>()

        fun visit(node: Int, depth: Int) {
            val c = nodeChar[node]
            path[depth] = c
            val prev = rows[depth - 1]
            val row = rows[depth]
            val pc = if (depth >= 2) path[depth - 1] else '\u0000'
            val omit = omitCost(c, pc, depth)
            row[0] = prev[0] + omit
            var rowMin = row[0]
            for (j in 1..n) {
                val tj = t[j - 1]
                var best = prev[j - 1] + (if (tj == c) 0f else subCost(c, tj, depth))
                val o = prev[j] + omit
                if (o < best) best = o
                val e = row[j - 1] + extra[j - 1]
                if (e < best) best = e
                if (depth >= 2 && j >= 2 && c == t[j - 2] && pc == tj && c != pc) {
                    val tr = rows[depth - 2][j - 2] + TRANSPOSE_COST
                    if (tr < best) best = tr
                }
                row[j] = best
                if (best < rowMin) rowMin = best
            }
            val w = nodeWord[node]
            if (w >= 0 && row[n] <= budget) {
                val cost = row[n]
                val word = wordList[w]
                var score = -EDIT_WEIGHT * cost + wordFreq[w] / FREQ_DIVISOR
                if (lmLogProb != null) score += lmWeight * (lmLogProb(word) ?: -LM_FLOOR)
                results.add(Correction(word, cost, score))
            }
            if (rowMin > budget || depth >= maxDepth) return
            var child = firstChild[node]
            while (child != -1) {
                visit(child, depth + 1)
                child = nextSibling[child]
            }
        }

        var child = firstChild[0]
        while (child != -1) {
            visit(child, 1)
            child = nextSibling[child]
        }
        results.sortByDescending { it.score }
        return if (results.size > maxCount) results.subList(0, maxCount).toList() else results
    }

    private fun keyDistance(a: Char, b: Char): Float {
        val ax = if (a.code < 128) keyX[a.code] else Float.NaN
        val bx = if (b.code < 128) keyX[b.code] else Float.NaN
        if (ax.isNaN() || bx.isNaN()) return Float.POSITIVE_INFINITY
        return hypot(ax - bx, keyY[a.code] - keyY[b.code])
    }

    /** Dictionary char [c] at [depth] typed as [typed]. */
    private fun subCost(c: Char, typed: Char, depth: Int): Float {
        val d = keyDistance(c, typed)
        val cost = if (d <= NEAR_KEY_DISTANCE) SUB_NEAR_COST else SUB_FAR_COST
        return if (depth == 1) cost + FIRST_LETTER_PENALTY else cost
    }

    /** Dictionary char [c] (after [pc]) at [depth] was not typed. */
    private fun omitCost(c: Char, pc: Char, depth: Int): Float = when {
        c == '\'' -> APOSTROPHE_OMIT_COST
        c == pc -> DOUBLE_OMIT_COST
        depth == 1 -> OMIT_COST + FIRST_LETTER_PENALTY
        else -> OMIT_COST
    }

    /** Typed char at [j] does not belong to the word. */
    private fun extraCost(t: CharArray, j: Int): Float {
        val c = t[j]
        if (j == 0) return EXTRA_COST + FIRST_LETTER_PENALTY
        val p = t[j - 1]
        val nearPrev = p == c || keyDistance(p, c) <= NEAR_KEY_DISTANCE
        val nearNext = j + 1 < t.size && keyDistance(t[j + 1], c) <= NEAR_KEY_DISTANCE
        return if (nearPrev || nearNext) EXTRA_NEAR_COST else EXTRA_COST
    }

    private fun maxCostFor(length: Int): Float = when {
        length <= 2 -> 0.5f
        length <= 4 -> 1.0f
        length <= 7 -> 1.6f
        else -> 2.1f
    }

    companion object {
        /** Key centers in key widths, standard staggered QWERTY rows. */
        val QWERTY_CENTERS: Map<Char, Pair<Float, Float>> = buildMap {
            val rows = listOf("qwertyuiop" to 0.5f, "asdfghjkl" to 1.0f, "zxcvbnm" to 2.0f)
            for ((y, row) in rows.withIndex()) {
                for ((x, c) in row.first.withIndex()) put(c, (x + row.second) to y.toFloat())
            }
        }

        /** Covers horizontal and diagonal neighbours (distance 1.0 and ~1.12), not keys two apart. */
        const val NEAR_KEY_DISTANCE = 1.2f

        const val SUB_NEAR_COST = 0.55f
        const val SUB_FAR_COST = 1.0f
        const val OMIT_COST = 0.9f
        const val DOUBLE_OMIT_COST = 0.45f
        const val APOSTROPHE_OMIT_COST = 0.1f
        const val EXTRA_COST = 0.9f
        const val EXTRA_NEAR_COST = 0.5f
        const val TRANSPOSE_COST = 0.6f
        const val FIRST_LETTER_PENALTY = 0.4f
        private const val MIN_OMIT_COST = APOSTROPHE_OMIT_COST

        const val EDIT_WEIGHT = 6.0f
        const val FREQ_DIVISOR = 16f
        const val LM_WEIGHT = 0.4f
        const val LM_FLOOR = 10f
    }
}

/** Ordering of the suggestion bar while a word is being typed. Pure Kotlin. */
object TypingSuggestions {
    /**
     * Merges typo [corrections] (best first) and [completions] (words starting with the typed text, best first)
     * into at most [maxCount] distinct words.
     * - The typed word is in the dictionary: completions only, no corrections.
     * - The typed text is no dictionary word but starts one ([isPrefix]): the user is probably mid-word, so the
     *   best completion leads, followed by the best correction, then the rest alternating.
     * - Otherwise it is a typo: corrections lead, completions follow.
     */
    fun merge(
        corrections: List<String>,
        completions: List<String>,
        typedIsWord: Boolean,
        isPrefix: Boolean,
        maxCount: Int,
    ): List<String> {
        val out = LinkedHashSet<String>()
        fun addAll(words: List<String>) {
            for (w in words) {
                if (out.size >= maxCount) return
                out.add(w)
            }
        }
        when {
            typedIsWord -> addAll(completions)
            isPrefix -> {
                val longest = maxOf(corrections.size, completions.size)
                for (i in 0 until longest) {
                    completions.getOrNull(i)?.let { if (out.size < maxCount) out.add(it) }
                    corrections.getOrNull(i)?.let { if (out.size < maxCount) out.add(it) }
                }
            }
            else -> {
                addAll(corrections)
                addAll(completions)
            }
        }
        return out.toList()
    }
}
