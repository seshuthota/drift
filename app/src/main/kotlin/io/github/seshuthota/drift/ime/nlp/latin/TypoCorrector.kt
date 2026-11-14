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
import kotlin.math.ln

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
 * Candidates are ranked by `-EDIT_WEIGHT * cost + ln(1 + count) / FREQ_DIVISOR + lmWeight * lmLogProb`, where count
 * is the dictionary's raw frequency and lmLogProb an optional next-word log-probability.
 */
class TypoCorrector private constructor(
    private val wordList: Array<String>,
    private val wordFreq: IntArray,
    private val keyCenters: Map<Char, Pair<Float, Float>>,
) {
    /** Builds the corrector from word counts. Empty words are ignored. */
    constructor(words: Map<String, Int>, keyCenters: Map<Char, Pair<Float, Float>> = QWERTY_CENTERS) : this(
        words.keys.filter { it.isNotEmpty() }.sorted().toTypedArray(),
        keyCenters,
        words,
    )

    private constructor(
        sorted: Array<String>,
        keyCenters: Map<Char, Pair<Float, Float>>,
        words: Map<String, Int>,
    ) : this(sorted, IntArray(sorted.size) { words.getValue(sorted[it]) }, keyCenters)

    data class Correction(val word: String, val cost: Float, val score: Float)

    // Trie as parallel arrays; children of a node are a linked list (firstChild / nextSibling) sorted by char.
    private val nodeChar: CharArray
    private val firstChild: IntArray
    private val nextSibling: IntArray
    private val nodeWord: IntArray // index into [wordList] or -1

    private val keyX = FloatArray(128) { Float.NaN }
    private val keyY = FloatArray(128) { Float.NaN }

    init {
        for ((c, xy) in keyCenters) {
            if (c.code < 128) {
                keyX[c.code] = xy.first
                keyY[c.code] = xy.second
            }
        }
        // Upper bound on nodes: one per character plus the root. Trimmed to the real count below.
        val capacity = wordList.sumOf { it.length } + 1
        val chars = CharArray(capacity)
        val first = IntArray(capacity) { -1 }
        val next = IntArray(capacity) { -1 }
        val word = IntArray(capacity) { -1 }
        var count = 1
        for ((index, w) in wordList.withIndex()) {
            var node = 0
            for (c in w) {
                var child = first[node]
                var last = -1
                while (child != -1 && chars[child] != c) {
                    last = child
                    child = next[child]
                }
                if (child == -1) {
                    child = count++
                    chars[child] = c
                    if (last == -1) first[node] = child else next[last] = child
                }
                node = child
            }
            word[node] = index
        }
        nodeChar = chars.copyOf(count)
        firstChild = first.copyOf(count)
        nextSibling = next.copyOf(count)
        nodeWord = word.copyOf(count)
    }

    /** Every word, sorted. A read-only view of the corrector's own array, not a copy. */
    val words: List<String> get() = wordList.asList()

    /** The highest count of any word, or 0 when empty. */
    val maxFrequency: Int = wordFreq.maxOrNull() ?: 0

    /**
     * A corrector that also knows the words of [extra] with their counts, or this one when all of them are already
     * known. Words already present keep their count. Rebuilds the trie once, however many words are added.
     */
    fun withWords(extra: Map<String, Int>): TypoCorrector {
        val added = extra.keys.filter { it.isNotEmpty() && !contains(it) }.sorted()
        if (added.isEmpty()) return this
        val size = wordList.size + added.size
        val newWords = arrayOfNulls<String>(size)
        val newFreq = IntArray(size)
        var i = 0
        var j = 0
        for (k in 0 until size) {
            if (j >= added.size || (i < wordList.size && wordList[i] < added[j])) {
                newWords[k] = wordList[i]
                newFreq[k] = wordFreq[i++]
            } else {
                newWords[k] = added[j]
                newFreq[k] = extra.getValue(added[j++])
            }
        }
        @Suppress("UNCHECKED_CAST")
        return TypoCorrector(newWords as Array<String>, newFreq, keyCenters)
    }

    val size: Int get() = wordList.size

    /**
     * Up to [limit] dictionary words starting with [prefix], most frequent first, skipping [exclude]. The words
     * with a prefix form one run of the sorted list, so this is a binary search plus that run.
     */
    fun completions(prefix: String, limit: Int, exclude: Set<String> = emptySet()): List<String> {
        if (limit <= 0) return emptyList()
        val found = wordList.binarySearch(prefix)
        var i = if (found >= 0) found else -(found + 1)
        val matches = ArrayList<Int>()
        while (i < wordList.size && wordList[i].startsWith(prefix)) {
            if (wordList[i] !in exclude) matches.add(i)
            i++
        }
        return matches.sortedByDescending { wordFreq[it] }.take(limit).map { wordList[it] }
    }

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
                var score = -EDIT_WEIGHT * cost + frequencyTerm(wordFreq[w])
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

    /**
     * Score of [word] as a completion of the typed text, comparable to [Correction.score]: frequency and language
     * model as for a correction, minus [COMPLETION_PENALTY] for guessing the rest of the word.
     */
    fun completionScore(word: String, lmLogProb: ((String) -> Float?)? = null, lmWeight: Float = LM_WEIGHT): Float {
        var score = frequencyTerm(frequencyOf(word)) - COMPLETION_PENALTY
        if (lmLogProb != null) score += lmWeight * (lmLogProb(word) ?: -LM_FLOOR)
        return score
    }

    /**
     * Whether the suggestion bar should lead with [best], the top correction for [typed] (which must score above
     * [typed] itself), instead of the best of [completions].
     * - [typed] is a dictionary word: only if [best] is [typed] with apostrophes added (`dont` → `don't`) or [typed]
     *   is a likely misspelling ([isLikelyMisspelling]).
     * - otherwise: if [best] scores above every completion (`thw` → `the` rather than `thwarted`).
     */
    fun shouldLeadWithCorrection(
        typed: String,
        best: Correction?,
        completions: List<String>,
        lmLogProb: ((String) -> Float?)? = null,
        isCommonWord: ((String) -> Boolean)? = null,
    ): Boolean {
        if (best == null) return false
        if (contains(typed)) {
            return best.word.replace("'", "") == typed ||
                (isCommonWord != null && isLikelyMisspelling(typed, best, isCommonWord))
        }
        val bestCompletion = completions.filter { it != typed }.maxOfOrNull { completionScore(it, lmLogProb) }
            ?: return true
        return best.score > bestCompletion
    }

    /**
     * A dictionary word is treated as a likely misspelling of [best] when it is not a common word, [best] is, and
     * [best] beats it on edit cost plus log-frequency (no language model) by more than [MISSPELLING_MARGIN].
     * Autocorrect does not use this: the cleaned word list no longer contains the junk it used to catch, and the
     * same margin also matches rare real words.
     */
    fun isLikelyMisspelling(typed: String, best: Correction, isCommonWord: (String) -> Boolean): Boolean {
        if (best.word == typed || !contains(typed) || isCommonWord(typed) || !isCommonWord(best.word)) return false
        val margin = -EDIT_WEIGHT * best.cost +
            frequencyTerm(frequencyOf(best.word)) - frequencyTerm(frequencyOf(typed))
        return margin > MISSPELLING_MARGIN
    }

    /**
     * Whether [typed] may be replaced by the top of [ranked] (the result of [correct] for [typed] with the same
     * [lmLogProb]) without asking, e.g. when a word separator is typed.
     * - The correction only adds apostrophes (`dont` → `don't`): it must beat the runner-up, or the typed word
     *   when that word is in the dictionary, by [APOSTROPHE_AUTOCORRECT_MARGIN]. That keeps `ill`, `id`, `were`, `its`.
     * - [typed] is not a dictionary word: the best correction must beat the next one by [AUTOCORRECT_MARGIN].
     * Other dictionary words are never replaced. Rare real words that remain (`pout`) are left alone.
     */
    fun isAutoCorrectable(
        typed: String,
        ranked: List<Correction>,
        lmLogProb: ((String) -> Float?)? = null,
    ): Boolean {
        val best = ranked.firstOrNull() ?: return false
        if (best.word == typed) return false
        val second = ranked.drop(1).firstOrNull { it.word != typed }
        val margin = if (second != null) best.score - second.score else Float.POSITIVE_INFINITY
        if (best.word.replace("'", "") == typed) {
            if (!contains(typed)) {
                return typed.length >= 2 && margin >= APOSTROPHE_AUTOCORRECT_MARGIN
            }
            val typedScore = completionScore(typed, lmLogProb) + COMPLETION_PENALTY
            return best.score - typedScore >= APOSTROPHE_AUTOCORRECT_MARGIN
        }
        if (!contains(typed)) return typed.length >= 2 && margin >= AUTOCORRECT_MARGIN
        return false
    }

    private fun frequencyTerm(count: Int): Float = ln(1f + count.toFloat()) / FREQ_DIVISOR

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

        /**
         * Scales `ln(1 + count)`. 2 puts the term around 1–8, matching the spread the edit weights were tuned
         * against when frequencies were the old 128–255 values divided by 16.
         */
        const val FREQ_DIVISOR = 2f
        const val LM_WEIGHT = 0.4f
        const val LM_FLOOR = 10f
        const val COMPLETION_PENALTY = 1.0f
        const val MISSPELLING_MARGIN = 2.5f
        const val AUTOCORRECT_MARGIN = 2.5f
        const val APOSTROPHE_AUTOCORRECT_MARGIN = 0.8f
    }
}

/** Ordering of the suggestion bar while a word is being typed. Pure Kotlin. */
object TypingSuggestions {
    /**
     * Merges typo [corrections] (best first) and [completions] (words starting with the typed text, best first)
     * into at most [maxCount] distinct words. When [leadWithCorrection] (the typed text is neither a word nor the
     * start of one) corrections come first; otherwise the user may be mid-word or typed a real word, so the best
     * completion leads and the two lists alternate.
     */
    fun merge(
        corrections: List<String>,
        completions: List<String>,
        leadWithCorrection: Boolean,
        maxCount: Int,
    ): List<String> {
        val out = LinkedHashSet<String>()
        val first = if (leadWithCorrection) corrections else completions
        val second = if (leadWithCorrection) completions else corrections
        if (leadWithCorrection) {
            for (w in first) if (out.size < maxCount) out.add(w)
            for (w in second) if (out.size < maxCount) out.add(w)
        } else {
            for (i in 0 until maxOf(first.size, second.size)) {
                first.getOrNull(i)?.let { if (out.size < maxCount) out.add(it) }
                second.getOrNull(i)?.let { if (out.size < maxCount) out.add(it) }
            }
        }
        return out.toList()
    }
}
