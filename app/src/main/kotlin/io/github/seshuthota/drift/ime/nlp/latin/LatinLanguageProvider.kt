/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
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

import android.content.Context
import io.github.seshuthota.drift.appContext
import io.github.seshuthota.drift.ime.core.Subtype
import io.github.seshuthota.drift.ime.nlp.SpellingProvider
import io.github.seshuthota.drift.ime.nlp.SpellingResult
import io.github.seshuthota.drift.ime.nlp.SuggestionCandidate
import io.github.seshuthota.drift.ime.nlp.SuggestionProvider
import io.github.seshuthota.drift.ime.nlp.WordSuggestionCandidate
import io.github.seshuthota.drift.nextWordPredictor
import io.github.seshuthota.drift.lib.devtools.flogDebug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import io.github.seshuthota.drift.lib.android.readText
import io.github.seshuthota.drift.lib.kotlin.guardedByLock
import org.k3lp.runtime.K3Content
import kotlin.math.exp

class LatinLanguageProvider(context: Context) : SpellingProvider, SuggestionProvider {
    companion object {
        // Default user ID used for all subtypes, unless otherwise specified.
        // See `ime/core/Subtype.kt` Line 210 and 211 for the default usage
        const val ProviderId = "io.github.seshuthota.drift.nlp.providers.latin"

        /** The editor context window ([org.k3lp.runtime.K3SurroundingText.textBefore]) is ~50 chars at most. */
        private const val TRUNCATION_HINT_CHARS = 48
    }

    private val appContext by context.appContext()
    private val predictor by context.nextWordPredictor()

    private val wordData = guardedByLock { mutableMapOf<String, Int>() }
    private val wordDataSerializer = MapSerializer(String.serializer(), Int.serializer())

    override val providerId = ProviderId

    override suspend fun create() {
        // Here we initialize our provider, set up all things which are not language dependent.
    }

    override suspend fun preload(subtype: Subtype) = withContext(Dispatchers.IO) {
        // Here we have the chance to preload dictionaries and prepare a neural network for a specific language.
        // Is kept in sync with the active keyboard subtype of the user, however a new preload does not necessary mean
        // the previous language is not needed anymore (e.g. if the user constantly switches between two subtypes)

        // To read a file from the APK assets the following methods can be used:
        // appContext.assets.open()
        // appContext.assets.reader()
        // appContext.assets.bufferedReader()
        // appContext.assets.readText()
        // To copy an APK file/dir to the file system cache (appContext.cacheDir), the following methods are available:
        // appContext.assets.copy()
        // appContext.assets.copyRecursively()

        // The subtype we get here contains a lot of data, however we are only interested in subtype.primaryLocale and
        // subtype.secondaryLocales.

        wordData.withLock { wordData ->
            if (wordData.isEmpty()) {
                // Here we use readText() because the test dictionary is a json dictionary
                val rawData = appContext.assets.readText("ime/dict/data.json")
                val jsonData = Json.decodeFromString(wordDataSerializer, rawData)
                wordData.putAll(jsonData)
            }
        }
        predictor.ensureLoaded()
        Unit
    }

    override suspend fun spell(
        subtype: Subtype,
        word: String,
        precedingWords: List<String>,
        followingWords: List<String>,
        maxSuggestionCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): SpellingResult {
        return when (word.lowercase()) {
            // Use typo for typing errors
            "typo" -> SpellingResult.typo(arrayOf("typo1", "typo2", "typo3"))
            // Use grammar error if the algorithm can detect this. On Android 11 and lower grammar errors are visually
            // marked as typos due to a lack of support
            "gerror" -> SpellingResult.grammarError(arrayOf("grammar1", "grammar2", "grammar3"))
            // Use valid word for valid input
            else -> SpellingResult.validWord()
        }
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: K3Content,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        if (!content.isTypingPosition()) {
            return emptyList()
        }
        val textBefore = content.surroundingText.textBefore
        val prefix = NwpText.partialWord(textBefore)
        val contextText = textBefore.subSequence(0, textBefore.length - prefix.length)
        val truncated = textBefore.length >= TRUNCATION_HINT_CHARS
        val sentenceStart = NwpText.isSentenceStart(contextText)
        val predictions = predictor.predict(contextText, truncated, prefix, maxCandidateCount)

        val seen = HashSet<String>()
        val candidates = ArrayList<SuggestionCandidate>(maxCandidateCount)
        for (prediction in predictions) {
            if (seen.add(prediction.word)) {
                candidates.add(candidate(prediction.word, prefix, sentenceStart, exp(prediction.logProb.toDouble())))
            }
        }
        // Words outside the model's vocabulary: complete from the full dictionary, most frequent first.
        if (prefix.isNotEmpty() && candidates.size < maxCandidateCount) {
            val p = NwpText.normalize(prefix)
            val extra = wordData.withLock { data ->
                data.entries.asSequence()
                    .filter { it.key.startsWith(p) && it.key !in seen }
                    .sortedByDescending { it.value }
                    .take(maxCandidateCount - candidates.size)
                    .map { it.key }
                    .toList()
            }
            for (word in extra) {
                candidates.add(candidate(word, prefix, sentenceStart, 0.0))
            }
        }
        return candidates
    }

    private fun candidate(word: String, prefix: String, sentenceStart: Boolean, confidence: Double) =
        WordSuggestionCandidate(
            text = NwpText.displayForm(word, prefix, sentenceStart),
            confidence = confidence,
            isEligibleForAutoCommit = false,
            isEligibleForUserRemoval = false,
            sourceProvider = this,
        )

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        // We can use flogDebug, flogInfo, flogWarning and flogError for debug logging, which is a wrapper for Logcat
        flogDebug { candidate.toString() }
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        flogDebug { candidate.toString() }
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        flogDebug { candidate.toString() }
        return false
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> {
        return wordData.withLock { it.keys.toList() }
    }

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        return wordData.withLock { it.getOrDefault(word, 0) / 255.0 }
    }

    override suspend fun destroy() {
        // Here we have the chance to de-allocate memory and finish our work. However this might never be called if
        // the app process is killed (which will most likely always be the case).
    }
}
