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

/**
 * What happens after a dictation ends: the whole recording is decoded once more in one pass, with the full sentence
 * available, and that text can replace what was streamed live.
 */
enum class VoiceRefineMode {
    /** No second pass. */
    OFF,

    /** The second pass runs and is written to the quality log; the streamed text stays. */
    LOG_ONLY,

    /** The second pass replaces the streamed text when [decideRefinement] allows it. */
    REPLACE,
}

/** The outcome of comparing the streamed text with the one-pass text. [reason] goes to the quality log. */
data class RefineDecision(val replace: Boolean, val reason: String)

/** Collapses whitespace so line breaks between streamed lines do not count as a difference. */
fun normalizeTranscript(text: String): String = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")

/**
 * Decides whether [refined] should replace [streamed]. A one-pass decode of a long clip can drop a whole phrase, so
 * a result that lost much of the streamed words is never used, and neither is an empty one.
 */
fun decideRefinement(streamed: String, refined: String, mode: VoiceRefineMode): RefineDecision {
    val a = normalizeTranscript(streamed)
    val b = normalizeTranscript(refined)
    return when {
        b.isEmpty() -> RefineDecision(false, "empty")
        a == b -> RefineDecision(false, "same")
        wordCount(b) < wordCount(a) * MIN_KEPT_WORD_RATIO -> RefineDecision(false, "too_short")
        mode != VoiceRefineMode.REPLACE -> RefineDecision(false, "log_only")
        else -> RefineDecision(true, "replaced")
    }
}

/**
 * Text near the cursor handed to the speech model before dictation. Moonshine extracts key terms from it and boosts
 * them while decoding, so names and unusual words already in the field are recognized more often. The part right
 * before the cursor matters most and is kept whole; whitespace is collapsed.
 */
fun dictationContext(textBefore: String, textAfter: String): String {
    val before = normalizeTranscript(textBefore).takeLast(MAX_CONTEXT_BEFORE)
    val after = normalizeTranscript(textAfter).take(MAX_CONTEXT_AFTER)
    return listOf(before, after).filter { it.isNotEmpty() }.joinToString(" ")
}

private fun wordCount(text: String): Int = if (text.isEmpty()) 0 else text.count { it == ' ' } + 1

private val WHITESPACE = Regex("\\s+")

/** A refined text with fewer than this share of the streamed words is treated as a dropped phrase. */
private const val MIN_KEPT_WORD_RATIO = 0.6

const val MAX_CONTEXT_BEFORE = 1_000
const val MAX_CONTEXT_AFTER = 200
