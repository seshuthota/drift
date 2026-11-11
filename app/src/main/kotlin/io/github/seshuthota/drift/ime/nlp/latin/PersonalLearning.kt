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

/**
 * Decides when a word the user typed should join the on-device lexicon.
 *
 * An unknown word is learned after [REPEATS] commits. A word restored by undoing autocorrect
 * is learned on that undo. Counts live for the process; the dictionary entry is what persists.
 */
internal class PersonalLearning {
    private val counts = HashMap<String, Int>()

    /**
     * Returns the normalized word once it should be saved, or null while it is still being counted.
     * [key] is already the result of [keyOf].
     */
    fun observe(key: String, immediate: Boolean): String? {
        if (immediate) {
            counts.remove(key)
            return key
        }
        val next = (counts[key] ?: 0) + 1
        if (next >= REPEATS) {
            counts.remove(key)
            return key
        }
        counts[key] = next
        return null
    }

    companion object {
        const val REPEATS = 3

        private val WORD = Regex("[a-z]+(?:'[a-z]+)*")

        /** Lowercase letters with optional internal apostrophes, or null when the text is not a learnable word. */
        fun keyOf(raw: String): String? {
            val normalized = NwpText.normalize(raw).trim('\'')
            if (normalized.length !in 2..32 || !WORD.matches(normalized)) return null
            return normalized
        }
    }
}
