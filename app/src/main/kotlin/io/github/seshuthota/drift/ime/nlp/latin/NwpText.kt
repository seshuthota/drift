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
 * Text handling for the next-word model. Pure Kotlin, mirrors `ml/nwp/prepare.py` exactly: lowercase, curly
 * apostrophes normalized, words of `[a-z0-9]+('[a-z]+)?` (anything with a digit is `<unk>`), sentence ends and
 * newlines become `<s>`, commas are kept, everything else is dropped, repeated `<s>`/`,` are collapsed.
 */
object NwpText {
    const val UNK = 0
    const val BOS = 1
    const val COMMA = 2
    const val N_SPECIAL = 3

    private val TOKEN_RE = Regex("""[a-z0-9]+(?:'[a-z]+)?|[.!?]+|,|\n""")
    private val PARTIAL_WORD_RE = Regex("""[\p{L}']+$""")

    fun normalize(text: CharSequence): String =
        text.toString().lowercase().replace('’', '\'').replace('‘', '\'')

    /**
     * Converts the text before the cursor into model ids (last [maxTokens]). When [truncated] is true the text
     * does not start at the beginning of the field, so the first (possibly cut) token is dropped and no leading
     * `<s>` is added.
     */
    fun contextIds(text: CharSequence, idOf: (String) -> Int?, truncated: Boolean, maxTokens: Int): IntArray {
        val out = ArrayList<Int>(maxTokens + 4)
        if (!truncated) {
            out.add(BOS)
        }
        var first = true
        for (match in TOKEN_RE.findAll(normalize(text))) {
            if (truncated && first) {
                first = false
                continue
            }
            first = false
            val tok = match.value
            val c = tok[0]
            when {
                c == '\n' || c == '.' || c == '!' || c == '?' -> {
                    if (out.isNotEmpty() && out.last() != BOS) out.add(BOS)
                }
                c == ',' -> {
                    if (out.isNotEmpty() && out.last() != BOS && out.last() != COMMA) out.add(COMMA)
                }
                tok.any { it.isDigit() } -> out.add(UNK)
                else -> out.add(idOf(tok) ?: UNK)
            }
        }
        if (out.isEmpty()) {
            out.add(BOS)
        }
        val from = (out.size - maxTokens).coerceAtLeast(0)
        return out.subList(from, out.size).toIntArray()
    }

    /** The word currently being typed directly before the cursor (letters and apostrophes), or "". */
    fun partialWord(textBefore: CharSequence): String =
        PARTIAL_WORD_RE.find(textBefore)?.value ?: ""

    /** True if a new word here starts a sentence (empty field or after `.`, `!`, `?`, newline). */
    fun isSentenceStart(textBefore: CharSequence): Boolean {
        val trimmed = textBefore.trimEnd { it == ' ' || it == '\t' }
        val last = trimmed.lastOrNull() ?: return true
        return last == '.' || last == '!' || last == '?' || last == '\n'
    }

    /**
     * How a lowercase dictionary [word] is shown: the pronoun `i` and its contractions are capitalized; a typed
     * [prefix] in all caps (2+ letters) uppercases the word, a capitalized prefix or [sentenceStart] capitalizes it.
     */
    fun displayForm(word: String, prefix: String, sentenceStart: Boolean): String {
        val base = if (word == "i" || word.startsWith("i'")) "I" + word.substring(1) else word
        return when {
            prefix.length >= 2 && prefix.all { !it.isLetter() || it.isUpperCase() } -> base.uppercase()
            prefix.isNotEmpty() && prefix[0].isUpperCase() -> base.replaceFirstChar { it.uppercaseChar() }
            prefix.isEmpty() && sentenceStart -> base.replaceFirstChar { it.uppercaseChar() }
            else -> base
        }
    }
}
