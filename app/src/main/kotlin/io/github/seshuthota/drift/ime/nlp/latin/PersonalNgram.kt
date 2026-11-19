/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.seshuthota.drift.ime.nlp.latin

import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class PersonalNgram(
    private val file: File?,
    /** False while credential storage is locked (direct boot): nothing is read, learned or written until then. */
    private val storageReady: () -> Boolean = { true },
) {
    companion object {
        private const val TAG = "DriftPersonalNgram"
        const val SENTENCE = "<s>"
        private const val SEP = '\t'
        const val BETA = 0.3
        const val K = 2.0
        private const val NO_CONTEXT_SHARE = 0.1
        private const val MAX_ENTRIES = 60_000
        private const val SAVE_DELAY_SECONDS = 5L
        private val WORD_RE = Regex("""[a-z]+(?:'[a-z]+)?""")
    }

    data class History(val p2: String, val p1: String)

    private val lock = Any()
    private var loaded = false
    private val tri = HashMap<History, HashMap<String, Int>>()
    private val bi = HashMap<String, HashMap<String, Int>>()
    private val uni = HashMap<String, Int>()
    private var total = 0
    private var entries = 0

    private val saver = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "drift-personal-ngram").apply { isDaemon = true }
    }
    private var pendingSave: ScheduledFuture<*>? = null

    fun historyOf(textBefore: CharSequence, truncated: Boolean): History {
        val words = NwpText.tokens(textBefore, truncated).filter { it != "," }
        val p1 = words.lastOrNull() ?: SENTENCE
        val p2 = if (p1 != SENTENCE && words.size >= 2) words[words.size - 2] else SENTENCE
        return History(p2, p1)
    }

    fun learn(textBefore: CharSequence, truncated: Boolean, word: String) =
        change(historyOf(textBefore, truncated), word, +1)

    fun unlearn(textBefore: CharSequence, truncated: Boolean, word: String) =
        change(historyOf(textBefore, truncated), word, -1)

    private fun change(history: History, raw: String, delta: Int) {
        val word = NwpText.normalize(raw)
        if (!WORD_RE.matches(word)) return
        synchronized(lock) {
            if (!ensureLoaded()) return
            val followers = tri.getOrPut(history) { HashMap() }
            val before = followers[word] ?: 0
            if (delta < 0 && before <= 0) return
            val after = before + delta
            if (after > 0) followers[word] = after else followers.remove(word)
            if (followers.isEmpty()) tri.remove(history)
            if (before == 0) entries++ else if (after == 0) entries--
            bump(bi.getOrPut(history.p1) { HashMap() }, word, delta)
            if (bi[history.p1]?.isEmpty() == true) bi.remove(history.p1)
            bump(uni, word, delta)
            total += delta
            if (entries > MAX_ENTRIES) decay()
        }
        scheduleSave()
    }

    private fun bump(map: HashMap<String, Int>, word: String, delta: Int) {
        val next = (map[word] ?: 0) + delta
        if (next > 0) map[word] = next else map.remove(word)
    }

    fun alpha(history: History): Double {
        return synchronized(lock) {
            if (!ensureLoaded() || total <= 0) return 0.0
            val evidence = bi[history.p1]?.values?.sum() ?: 0
            if (evidence == 0) BETA * NO_CONTEXT_SHARE else BETA * evidence / (evidence + K)
        }
    }

    fun prob(history: History, raw: String): Double {
        return synchronized(lock) {
            if (!ensureLoaded() || total <= 0) return 0.0
            val word = NwpText.normalize(raw)
            var p = (uni[word] ?: 0).toDouble() / total
            for (followers in listOf(bi[history.p1], tri[history])) {
                if (followers.isNullOrEmpty()) continue
                val n = followers.values.sum().toDouble()
                val lambda = n / (n + followers.size)
                p = lambda * (followers[word] ?: 0) / n + (1 - lambda) * p
            }
            p
        }
    }

    fun mix(history: History, alpha: Double, word: String, modelProb: Double): Double =
        if (alpha <= 0.0) modelProb else (1 - alpha) * modelProb + alpha * prob(history, word)

    fun candidates(history: History, prefix: String, limit: Int): List<String> {
        return synchronized(lock) {
            if (!ensureLoaded()) return emptyList()
            val p = NwpText.normalize(prefix)
            val out = LinkedHashSet<String>()
            for (followers in listOf(tri[history], bi[history.p1])) {
                followers?.entries?.filter { it.key.startsWith(p) }
                    ?.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    ?.take(limit)?.forEach { out += it.key }
            }
            out.toList()
        }
    }

    fun countOf(raw: String): Int {
        return synchronized(lock) {
            if (!ensureLoaded()) return 0
            uni[NwpText.normalize(raw)] ?: 0
        }
    }

    fun clear() {
        synchronized(lock) {
            tri.clear()
            bi.clear()
            uni.clear()
            total = 0
            entries = 0
            loaded = true
        }
        scheduleSave()
    }

    private fun decay() {
        val old = tri.toMap()
        tri.clear()
        bi.clear()
        uni.clear()
        total = 0
        entries = 0
        for ((history, followers) in old) {
            for ((word, count) in followers) add(history, word, count / 2)
        }
    }

    private fun add(history: History, word: String, count: Int) {
        if (count <= 0) return
        tri.getOrPut(history) { HashMap() }[word] = count
        entries++
        bump(bi.getOrPut(history.p1) { HashMap() }, word, count)
        bump(uni, word, count)
        total += count
    }

    private fun ensureLoaded(): Boolean {
        if (loaded) return true
        if (!storageReady()) return false
        loaded = true
        val source = file ?: return true
        if (!source.exists()) return true
        try {
            source.forEachLine { line ->
                val parts = line.split(SEP)
                if (parts.size == 4) {
                    val count = parts[3].toIntOrNull() ?: return@forEachLine
                    add(History(parts[0], parts[1]), parts[2], count)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not read ${source.name}", t)
        }
        return true
    }

    private fun scheduleSave() {
        val target = file ?: return
        synchronized(lock) {
            if (!loaded) return
            pendingSave?.cancel(false)
            pendingSave = saver.schedule({ save(target) }, SAVE_DELAY_SECONDS, TimeUnit.SECONDS)
        }
    }

    fun flush() {
        val target = file ?: return
        synchronized(lock) {
            if (!loaded) return
            pendingSave?.cancel(false)
        }
        save(target)
    }

    private fun save(target: File) {
        val text = synchronized(lock) {
            buildString {
                for ((history, followers) in tri) {
                    for ((word, count) in followers) {
                        append(history.p2).append(SEP).append(history.p1).append(SEP).append(word).append(SEP)
                            .append(count).append('\n')
                    }
                }
            }
        }
        try {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeText(text)
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not write ${target.name}", t)
        }
    }
}
