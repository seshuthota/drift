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
import io.github.seshuthota.drift.personalNgram
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
        const val ProviderId = "io.github.seshuthota.drift.nlp.providers.latin"

        private const val TRUNCATION_HINT_CHARS = 48

        private const val PERSONAL_POOL = 20

        private const val MIN_CORRECTION_LENGTH = 2
        private const val MAX_CORRECTIONS = 3

        const val LEARNED_COUNT = 100
    }

    private val appContext by context.appContext()
    private val predictor by context.nextWordPredictor()
    private val personalNgram by context.personalNgram()
    private val prefs by DriftPreferenceStore

    private val lexiconLock = guardedByLock { Unit }
    private val wordDataSerializer = MapSerializer(String.serializer(), Int.serializer())

    @Volatile
    private var logFreqDenom = 1.0

    @Volatile
    private var corrector: TypoCorrector? = null

    override val providerId = ProviderId

    override suspend fun create() {
    }

    override suspend fun preload(subtype: Subtype) = withContext(Dispatchers.IO) {
        lexiconLock.withLock {
            val loaded = corrector ?: TypoCorrector(
                Json.decodeFromString(wordDataSerializer, appContext.assets.readText("ime/dict/data.json")),
            ).also { logFreqDenom = ln(1.0 + it.maxFrequency).coerceAtLeast(1.0) }
            corrector = loaded.withWords(userDictionaryWords())
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
        if (!hasPredictionContext(textBefore)) {
            return emptyList()
        }
        val prefix = NwpText.partialWord(textBefore)
        val contextText = textBefore.subSequence(0, textBefore.length - prefix.length)
        val truncated = textBefore.length >= TRUNCATION_HINT_CHARS
        val sentenceStart = NwpText.isSentenceStart(contextText)
        val logProbs = predictor.nextWordLogProbs(contextText, truncated)
        val personal = if (prefs.correction.personalLearning.get()) personalNgram else null
        val history = personal?.historyOf(contextText, truncated)
        val alpha = if (personal != null && history != null) {
            personal.alpha(history, PersonalNgram.PREDICTION_BETA)
        } else {
            0.0
        }
        val predictions = if (logProbs == null) {
            emptyList()
        } else {
            val lexicon = corrector
            val allowed = { word: String -> lexicon?.contains(word) == true }
            if (alpha <= 0.0 || personal == null || history == null) {
                predictor.predict(logProbs, prefix, maxCandidateCount, allowed)
            } else {
                val pool = LinkedHashMap<String, Double>()
                for (p in predictor.predict(logProbs, prefix, PERSONAL_POOL, allowed)) {
                    pool[p.word] = exp(p.logProb.toDouble())
                }
                for (word in personal.candidates(history, prefix, PERSONAL_POOL)) {
                    if (word in pool || !(allowed(word) || personal.countOf(word) >= 2)) continue
                    pool[word] = predictor.idOf(word)?.let { exp(logProbs[it].toDouble()) } ?: 0.0
                }
                pool.mapNotNull { (word, modelProb) ->
                    val mixed = personal.mix(history, alpha, word, modelProb)
                    if (mixed > 0.0) NextWordPredictor.Prediction(word, ln(mixed).toFloat()) else null
                }.sortedByDescending { it.logProb }.take(maxCandidateCount)
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
            completions += corrector?.completions(p, maxCandidateCount - completions.size, seen).orEmpty()
        }
        if (p.isEmpty()) {
            return completions.map { candidate(it, prefix, sentenceStart, confidence[it] ?: 0.0) }
        }

        val corrector = corrector
            ?: return completions.map { candidate(it, prefix, sentenceStart, confidence[it] ?: 0.0) }
        val lm: ((String) -> Float?)? = logProbs?.let { lp ->
            { word ->
                val modelLogProb = predictor.idOf(word)?.let { lp[it] }
                if (alpha > 0.0 && personal != null && history != null) {
                    val mixed = personal.mix(history, alpha, word, modelLogProb?.let { exp(it.toDouble()) } ?: 0.0)
                    if (mixed > 0.0) ln(mixed).toFloat() else null
                } else {
                    modelLogProb
                }
            }
        }
        val ranked = if (p.length < MIN_CORRECTION_LENGTH) emptyList() else corrector.correct(p, MAX_CORRECTIONS + 2, lm)
        val corrections = ranked.takeWhile { it.word != p }.take(MAX_CORRECTIONS)
        val lead = corrector.shouldLeadWithCorrection(p, corrections.firstOrNull(), completions, lm, isCommonWord)
        val correctionWords = corrections.map { it.word }.let { words ->
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

    private fun isAutoCorrectCasing(prefix: String, sentenceStart: Boolean, correction: String): Boolean = when {
        prefix.length >= 2 && prefix.all { !it.isLetter() || it.isUpperCase() } -> false
        prefix[0].isUpperCase() && !sentenceStart -> correction.startsWith("i'")
        else -> true
    }

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
        flogDebug { candidate.toString() }
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        flogDebug { candidate.toString() }
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        flogDebug { candidate.toString() }
        return false
    }

    fun isLexiconReady(): Boolean = corrector != null

    fun knowsWord(word: String): Boolean = corrector?.contains(word) == true

    suspend fun addLearnedWord(word: String): Boolean = lexiconLock.withLock {
        val current = corrector ?: return@withLock false
        val updated = current.withWords(mapOf(word to LEARNED_COUNT))
        if (updated === current) return@withLock false
        corrector = updated
        true
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> = corrector?.words ?: emptyList()

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        val count = corrector?.frequencyOf(word) ?: 0
        if (count <= 0) return 0.0
        return ln(1.0 + count) / logFreqDenom
    }

    private fun userDictionaryWords(): Map<String, Int> {
        val manager = try {
            DictionaryManager.default()
        } catch (_: UninitializedPropertyAccessException) {
            return emptyMap()
        }
        manager.loadUserDictionariesIfNecessary()
        val dao = manager.driftUserDictionaryDao() ?: return emptyMap()
        return dao.queryAll().mapNotNull { PersonalLearning.keyOf(it.word) }.associateWith { LEARNED_COUNT }
    }

    override suspend fun destroy() {
    }
}

internal fun hasPredictionContext(textBefore: CharSequence): Boolean = textBefore.any { !it.isWhitespace() }
