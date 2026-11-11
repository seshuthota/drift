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
import io.github.seshuthota.drift.app.DriftPreferenceStore
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
import io.github.seshuthota.drift.ime.dictionary.DictionaryManager
import io.github.seshuthota.drift.lib.android.readText
import io.github.seshuthota.drift.lib.kotlin.guardedByLock
import org.k3lp.runtime.K3Content
import kotlin.math.exp
import kotlin.math.ln

class LatinLanguageProvider(context: Context) : SpellingProvider, SuggestionProvider {
    companion object {
        // Default user ID used for all subtypes, unless otherwise specified.
        // See `ime/core/Subtype.kt` Line 210 and 211 for the default usage
        const val ProviderId = "io.github.seshuthota.drift.nlp.providers.latin"

        /** The editor context window ([org.k3lp.runtime.K3SurroundingText.textBefore]) is ~50 chars at most. */
        private const val TRUNCATION_HINT_CHARS = 48

        /** Shorter typed text is too ambiguous to correct. */
        private const val MIN_CORRECTION_LENGTH = 2
        private const val MAX_CORRECTIONS = 3

        /**
         * Raw count given to a word learned on device. Small next to corpus counts, so it is known
         * (and therefore not autocorrected) without outranking common words.
         */
        const val LEARNED_COUNT = 100
    }

    private val appContext by context.appContext()
    private val predictor by context.nextWordPredictor()
    private val prefs by DriftPreferenceStore

    private val wordData = guardedByLock { mutableMapOf<String, Int>() }
    private val wordDataSerializer = MapSerializer(String.serializer(), Int.serializer())

    /** `ln(1 + maxCount)`, so [getFrequencyForWord] returns a 0–1 log-frequency. */
    @Volatile
    private var logFreqDenom = 1.0

    /** Built once [wordData] is loaded; null until then. */
    @Volatile
    private var corrector: TypoCorrector? = null

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
                val maxCount = wordData.values.maxOrNull() ?: 0
                logFreqDenom = ln(1.0 + maxCount).coerceAtLeast(1.0)
            }
            // Rebuilding the corrector walks the whole word list, so only do it when something changed.
            if (absorbUserDictionary(wordData) || corrector == null) {
                corrector = TypoCorrector(wordData)
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
        val corrector = corrector ?: return SpellingResult.unspecified()
        val normalized = NwpText.normalize(word).trim('\'')
        // Only plain words are checked: numbers, names with symbols, URLs and the like are left alone.
        if (normalized.isEmpty() || !normalized.all { it in 'a'..'z' || it == '\'' }) {
            return SpellingResult.validWord()
        }
        val known = corrector.contains(normalized) || corrector.contains(normalized.removeSuffix("'s"))
        val corrections = corrector.correct(normalized, maxSuggestionCount + 1).filter { it.word != normalized }
        if (known && corrections.firstOrNull()?.let { corrector.isLikelyMisspelling(normalized, it, isCommonWord) } != true) {
            return SpellingResult.validWord()
        }
        val suggestions = corrections.map { NwpText.displayForm(it.word, word, sentenceStart = false) }
        val confident = corrections.firstOrNull()?.let { it.cost <= TypoCorrector.SUB_NEAR_COST } == true
        return SpellingResult.typo(suggestions.toTypedArray(), isHighConfidenceResult = confident)
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
        val logProbs = predictor.nextWordLogProbs(contextText, truncated)
        // The model vocabulary still contains spellings removed from the word list (`dont`, `recieve`).
        val predictions = if (logProbs == null) {
            emptyList()
        } else {
            wordData.withLock { data ->
                predictor.predict(logProbs, prefix, maxCandidateCount) { word -> word in data }
            }
        }
        val confidence = HashMap<String, Double>()
        for (prediction in predictions) {
            confidence.putIfAbsent(prediction.word, exp(prediction.logProb.toDouble()))
        }
        val completions = predictions.mapTo(ArrayList()) { it.word }
        // Words outside the model's vocabulary: complete from the full dictionary, most frequent first.
        val p = NwpText.normalize(prefix)
        if (p.isNotEmpty() && completions.size < maxCandidateCount) {
            val seen = completions.toHashSet()
            completions += wordData.withLock { data ->
                data.entries.asSequence()
                    .filter { it.key.startsWith(p) && it.key !in seen }
                    .sortedByDescending { it.value }
                    .take(maxCandidateCount - completions.size)
                    .map { it.key }
                    .toList()
            }
        }
        if (p.isEmpty()) {
            return completions.map { candidate(it, prefix, sentenceStart, confidence[it] ?: 0.0) }
        }

        val corrector = corrector
            ?: return completions.map { candidate(it, prefix, sentenceStart, confidence[it] ?: 0.0) }
        val lm: ((String) -> Float?)? = logProbs?.let { lp -> { word -> predictor.idOf(word)?.let { lp[it] } } }
        // Results are best first and include the typed word when it is in the dictionary. A dictionary word still
        // gets the corrections that score above it (`dont` is absent, so `don't` can lead).
        val ranked = if (p.length < MIN_CORRECTION_LENGTH) emptyList() else corrector.correct(p, MAX_CORRECTIONS + 2, lm)
        val corrections = ranked.takeWhile { it.word != p }.take(MAX_CORRECTIONS)
        val lead = corrector.shouldLeadWithCorrection(p, corrections.firstOrNull(), completions, lm, isCommonWord)
        val correctionWords = corrections.map { it.word }.let { words ->
            // A typed dictionary word stays right after the correction that displaced it.
            if (lead && corrector.contains(p)) listOf(words[0], p) + words.drop(1) else words
        }
        val words = TypingSuggestions.merge(
            corrections = correctionWords,
            completions = completions,
            leadWithCorrection = lead,
            maxCount = maxCandidateCount,
        )
        val autoCorrect = lead && prefs.correction.autoCorrect.get() && isAutoCorrectCasing(prefix, sentenceStart, corrections.first().word) &&
            corrector.isAutoCorrectable(p, ranked, lm)
        return words.mapIndexed { i, word ->
            if (i == 0 && autoCorrect) {
                candidate(word, prefix, sentenceStart, confidence[word] ?: 0.0)
                    .copy(isEligibleForAutoCommit = true, correctsWord = prefix)
            } else {
                candidate(word, prefix, sentenceStart, confidence[word] ?: 0.0)
            }
        }
    }

    /**
     * All-caps text (acronyms) and words capitalized mid-sentence (likely names) are never autocorrected, except
     * into a contraction of "I" (`Im` → `I'm`).
     */
    private fun isAutoCorrectCasing(prefix: String, sentenceStart: Boolean, correction: String): Boolean = when {
        prefix.length >= 2 && prefix.all { !it.isLetter() || it.isUpperCase() } -> false
        prefix[0].isUpperCase() && !sentenceStart -> correction.startsWith("i'")
        else -> true
    }

    /** Words in the next-word model's vocabulary (the ~20k most frequent); false if the model is unavailable. */
    private val isCommonWord: (String) -> Boolean = { predictor.idOf(it) != null }

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

    /** True once the asset dictionary has been read. Learning before that would hide the asset list. */
    suspend fun isLexiconReady(): Boolean = wordData.withLock { corrector != null }

    suspend fun knowsWord(word: String): Boolean = wordData.withLock { word in it }

    /**
     * Adds [word] to the in-memory lexicon and rebuilds the corrector. False when it is already
     * present or the lexicon is not loaded yet.
     */
    suspend fun addLearnedWord(word: String): Boolean = wordData.withLock { data ->
        if (corrector == null || word in data) return@withLock false
        data[word] = LEARNED_COUNT
        corrector = TypoCorrector(data)
        true
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> {
        return wordData.withLock { it.keys.toList() }
    }

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        val count = wordData.withLock { it.getOrDefault(word, 0) }
        if (count <= 0) return 0.0
        return ln(1.0 + count) / logFreqDenom
    }

    /** Adds internal user-dictionary words missing from [data]. True when any word was added. */
    private fun absorbUserDictionary(data: MutableMap<String, Int>): Boolean {
        val manager = try {
            DictionaryManager.default()
        } catch (_: UninitializedPropertyAccessException) {
            return false
        }
        manager.loadUserDictionariesIfNecessary()
        val dao = manager.driftUserDictionaryDao() ?: return false
        var added = false
        for (entry in dao.queryAll()) {
            val key = PersonalLearning.keyOf(entry.word) ?: continue
            if (key !in data) {
                data[key] = LEARNED_COUNT
                added = true
            }
        }
        return added
    }

    override suspend fun destroy() {
        // Here we have the chance to de-allocate memory and finish our work. However this might never be called if
        // the app process is killed (which will most likely always be the case).
    }
}
