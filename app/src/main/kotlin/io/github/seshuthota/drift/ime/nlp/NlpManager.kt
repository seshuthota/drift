/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
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

package io.github.seshuthota.drift.ime.nlp

import android.content.Context
import android.os.SystemClock
import android.util.LruCache
import io.github.seshuthota.drift.app.DriftPreferenceStore
import io.github.seshuthota.drift.clipboardManager
import io.github.seshuthota.drift.ime.QualityLog
import io.github.seshuthota.drift.ime.clipboard.provider.ClipboardItem
import io.github.seshuthota.drift.ime.clipboard.provider.ItemType
import io.github.seshuthota.drift.ime.core.Subtype
import io.github.seshuthota.drift.ime.media.emoji.EmojiSuggestionProvider
import io.github.seshuthota.drift.glideTypingManager
import io.github.seshuthota.drift.ime.dictionary.DictionaryManager
import io.github.seshuthota.drift.ime.nlp.latin.LatinLanguageProvider
import io.github.seshuthota.drift.ime.nlp.latin.NwpText
import io.github.seshuthota.drift.ime.nlp.latin.PersonalLearning
import io.github.seshuthota.drift.ime.nlp.latin.PersonalNgram
import io.github.seshuthota.drift.ime.nlp.latin.isTypingPosition
import io.github.seshuthota.drift.inferDriftApplication
import io.github.seshuthota.drift.personalNgram
import io.github.seshuthota.drift.lib.util.NetworkUtils
import io.github.seshuthota.drift.subtypeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.seshuthota.drift.lib.kotlin.guardedByLock
import io.github.seshuthota.drift.lib.kotlin.collectLatestIn
import org.k3lp.runtime.K3Content
import org.k3lp.runtime.K3TextRange
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.properties.Delegates

private const val BLANK_STR_PATTERN = "^\\s*$"

class NlpManager(context: Context) {
    private val blankStrRegex = Regex(BLANK_STR_PATTERN)

    private val prefs by DriftPreferenceStore
    val appContext = context.inferDriftApplication()
    val imeController = appContext.imeController
    private val clipboardManager by context.clipboardManager()
    private val subtypeManager by context.subtypeManager()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val clipboardSuggestionProvider = ClipboardSuggestionProvider(context)
    private val emojiSuggestionProvider = EmojiSuggestionProvider(context)
    private val providers = guardedByLock {
        mapOf(
            LatinLanguageProvider.ProviderId to ProviderInstanceWrapper(LatinLanguageProvider(context)),
            //HanShapeBasedLanguageProvider.ProviderId to ProviderInstanceWrapper(HanShapeBasedLanguageProvider(context)),
        )
    }
    // lock unnecessary because values constant
    private val providersForceSuggestionOn = mutableMapOf<String, Boolean>()

    private val internalSuggestionsGuard = Mutex()
    private val personalLearning = PersonalLearning()
    private val personalLearningGuard = Mutex()
    private var internalSuggestions by Delegates.observable(SystemClock.uptimeMillis() to listOf<SuggestionCandidate>()) { _, _, _ ->
        scope.launch { assembleCandidates() }
    }

    private val _activeCandidatesFlow = MutableStateFlow(listOf<SuggestionCandidate>())
    val activeCandidatesFlow = _activeCandidatesFlow.asStateFlow()
    inline var activeCandidates
        get() = activeCandidatesFlow.value
        private set(v) {
            _activeCandidatesFlow.value = v
        }

    val debugOverlaySuggestionsInfos = LruCache<Long, Pair<String, SpellingResult>>(10)
    var debugOverlayVersion = MutableStateFlow(0)

    init {
        clipboardManager.primaryClipFlow.collectLatestIn(scope) {
            assembleCandidates()
        }
        prefs.suggestion.enabled.asFlow().collectLatestIn(scope) {
            assembleCandidates()
        }
        prefs.clipboard.suggestionEnabled.asFlow().collectLatestIn(scope) {
            assembleCandidates()
        }
        prefs.emoji.suggestionEnabled.asFlow().collectLatestIn(scope) {
            assembleCandidates()
        }
        subtypeManager.activeSubtypeFlow.collectLatestIn(scope) { subtype ->
            preload(subtype)
        }
    }

    /**
     * Gets the punctuation rule from the currently active subtype and returns it. Falls back to a default one if the
     * subtype does not exist or defines an invalid punctuation rule.
     *
     * @return The punctuation rule or a fallback.
     */
    fun getActivePunctuationRule(): PunctuationRule {
        return getPunctuationRule(subtypeManager.activeSubtype)
    }

    /**
     * Gets the punctuation rule from the given subtype and returns it. Falls back to a default one if the subtype does
     * not exist or defines an invalid punctuation rule.
     *
     * @return The punctuation rule or a fallback.
     */
    fun getPunctuationRule(subtype: Subtype): PunctuationRule {
        return PunctuationRule.Fallback
    }

    private suspend fun getSpellingProvider(subtype: Subtype): SpellingProvider {
        return providers.withLock { it[subtype.nlpProviders.spelling] }?.provider as? SpellingProvider
            ?: FallbackNlpProvider
    }

    private suspend fun getSuggestionProvider(subtype: Subtype): SuggestionProvider {
        return providers.withLock { it[subtype.nlpProviders.suggestion] }?.provider as? SuggestionProvider
            ?: FallbackNlpProvider
    }

    /**
     * Creates and preloads the suggestion provider for [subtype] synchronously. Glide typing needs
     * the word corpus immediately and must not depend on the asynchronous preload kicked off when
     * the subtype became active (or on the system spell checker having warmed the provider).
     */
    suspend fun ensureSuggestionProviderPreloaded(subtype: Subtype) {
        providers.withLock { providers ->
            providers[subtype.nlpProviders.suggestion]?.let { wrapper ->
                wrapper.createIfNecessary()
                wrapper.preload(subtype)
            }
        }
    }

    fun preload(subtype: Subtype) {
        scope.launch {
            emojiSuggestionProvider.preload(subtype)
            providers.withLock { providers ->
                subtype.nlpProviders.forEach { _, providerId ->
                    providers[providerId]?.let { provider ->
                        provider.createIfNecessary()
                        provider.preload(subtype)
                    }
                }
            }
        }
    }

    /**
     * Spell wrapper helper which calls the spelling provider and returns the result. Coroutine management must be done
     * by the source spell checker service.
     */
    suspend fun spell(
        subtype: Subtype,
        word: String,
        precedingWords: List<String>,
        followingWords: List<String>,
        maxSuggestionCount: Int,
    ): SpellingResult {
        return getSpellingProvider(subtype).spell(
            subtype = subtype,
            word = word,
            precedingWords = precedingWords,
            followingWords = followingWords,
            maxSuggestionCount = maxSuggestionCount,
            allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
            isPrivateSession = false// TODO keyboardManager.activeState.isIncognitoMode,
        )
    }

    suspend fun determineLocalComposing(
        textBeforeSelection: CharSequence, breakIterators: BreakIteratorGroup, localLastCommitPosition: Int
    ): K3TextRange {
        return getSuggestionProvider(subtypeManager.activeSubtype).determineLocalComposing(
            subtypeManager.activeSubtype, textBeforeSelection, breakIterators, localLastCommitPosition
        )
    }

    fun providerForcesSuggestionOn(subtype: Subtype): Boolean {
        // Using a cache because I have no idea how fast the runBlocking is
        return providersForceSuggestionOn.getOrPut(subtype.nlpProviders.suggestion) {
            runBlocking {
                getSuggestionProvider(subtype).forcesSuggestionOn
            }
        }
    }

    fun isSuggestionOn(): Boolean =
        prefs.suggestion.enabled.get()
            || prefs.emoji.suggestionEnabled.get()
            // Glide previews and post-glide alternatives are delivered through the candidate row.
            || prefs.glide.enabled.get()
            || providerForcesSuggestionOn(subtypeManager.activeSubtype)

    fun suggest(subtype: Subtype, content: K3Content) {
        val reqTime = SystemClock.uptimeMillis()
        scope.launch {
            val emojiSuggestions = when {
                prefs.emoji.suggestionEnabled.get() -> {
                    emojiSuggestionProvider.suggest(
                        subtype = subtype,
                        content = content,
                        maxCandidateCount = prefs.emoji.suggestionCandidateMaxCount.get(),
                        allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
                        isPrivateSession = false // TODO keyboardManager.activeState.isIncognitoMode,
                    )
                }
                else -> emptyList()
            }
            val suggestions = when {
                emojiSuggestions.isNotEmpty() && prefs.emoji.suggestionType.get().prefix.isNotEmpty() -> {
                    emptyList()
                }
                !prefs.suggestion.enabled.get() -> {
                    emptyList()
                }
                else -> {
                    getSuggestionProvider(subtype).suggest(
                        subtype = subtype,
                        content = content,
                        maxCandidateCount = 8,
                        allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
                        isPrivateSession = false // TODO keyboardManager.activeState.isIncognitoMode,
                    )
                }
            }
            internalSuggestionsGuard.withLock {
                if (internalSuggestions.first < reqTime) {
                    internalSuggestions = reqTime to buildList {
                        addAll(emojiSuggestions)
                        addAll(suggestions)
                    }
                    if (NwpText.partialWord(content.surroundingText.textBefore).isEmpty()) {
                        QualityLog.predictionsShown(suggestions.map { it.text.toString() }, reqTime)
                    }
                }
            }
        }
    }

    fun suggestDirectly(suggestions: List<SuggestionCandidate>) {
        val reqTime = SystemClock.uptimeMillis()
        runBlocking {
            internalSuggestions = reqTime to suggestions
        }
    }

    fun clearSuggestions() {
        val reqTime = SystemClock.uptimeMillis()
        runBlocking {
            internalSuggestions = reqTime to emptyList()
        }
    }

    fun getAutoCommitCandidate(): SuggestionCandidate? {
        return activeCandidates.firstOrNull { it.isEligibleForAutoCommit }
    }

    /**
     * The auto-commit candidate for exactly [content], computed synchronously. Used when a word separator is typed:
     * the asynchronous suggestions may still be for an earlier keystroke. Blocks for one provider call (a few ms).
     */
    fun autoCommitCandidateFor(subtype: Subtype, content: K3Content): SuggestionCandidate? {
        if (!prefs.suggestion.enabled.get()) return null
        return runBlocking {
            getSuggestionProvider(subtype).suggest(
                subtype = subtype,
                content = content,
                maxCandidateCount = 3,
                allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
                isPrivateSession = false,
            )
        }.firstOrNull { it.isEligibleForAutoCommit }
    }

    fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        return runBlocking { candidate.sourceProvider?.removeSuggestion(subtype, candidate) == true }.also { result ->
            if (result) {
                scope.launch {
                    // Need to re-trigger the suggestions algorithm
                    if (candidate is ClipboardSuggestionCandidate) {
                        assembleCandidates()
                    } else {
                        suggest(subtypeManager.activeSubtype, imeController.snapshotState().content)
                    }
                }
            }
        }
    }

    /**
     * Records a committed word for personal learning. [immediate] is an autocorrect undo.
     * No-op unless the preference is on and the field is not incognito. Work happens off the caller thread.
     */
    fun noteTypedWord(word: String, immediate: Boolean) {
        if (!prefs.correction.personalLearning.get()) return
        if (imeController.snapshotState().flags.isIncognitoMode) return
        val key = PersonalLearning.keyOf(word) ?: return
        scope.launch {
            personalLearningGuard.withLock {
                if (!prefs.correction.personalLearning.get()) return@withLock
                if (imeController.snapshotState().flags.isIncognitoMode) return@withLock
                val latin = latinProvider() ?: return@withLock
                if (!latin.isLexiconReady() || latin.knowsWord(key)) return@withLock
                val ready = personalLearning.observe(key, immediate) ?: return@withLock
                if (!latin.addLearnedWord(ready)) return@withLock
                DictionaryManager.default().rememberWord(ready)
                reloadGlideLexicon()
            }
        }
    }

    /** The user's word sequences; the latin provider and glide mix them into the next-word model. */
    val personalNgram: PersonalNgram by appContext.personalNgram()

    /** The last word handed to [learnWord] (text before it, word), so deleting it right away takes it back. */
    private var lastLearned: Pair<String, String>? = null

    /** Personal learning is on and the field is not incognito. Callers also check that the field takes prose. */
    fun isPersonalLearningActive(): Boolean =
        prefs.correction.personalLearning.get() && !imeController.snapshotState().flags.isIncognitoMode

    /** [word] was committed after [textBeforeWord]. Main thread; the counting happens off it. */
    fun learnWord(textBeforeWord: CharSequence, word: String) {
        if (word.isBlank() || !isPersonalLearningActive()) return
        val before = textBeforeWord.toString()
        lastLearned = before to word
        scope.launch { personalNgram.learn(before, before.length >= TRUNCATION_HINT_CHARS, word) }
    }

    /** The word committed after [textBeforeWord] changed from [old] to [new] (a glide pick or an autocorrect undo). */
    fun replaceLearnedWord(textBeforeWord: CharSequence, old: String, new: String) {
        if (!isPersonalLearningActive()) return
        val before = textBeforeWord.toString()
        lastLearned = before to new
        scope.launch {
            val truncated = before.length >= TRUNCATION_HINT_CHARS
            personalNgram.unlearn(before, truncated, old)
            personalNgram.learn(before, truncated, new)
        }
    }

    /**
     * A backspace is about to delete the character before the end of [textBefore]. When it reaches into the word
     * just learned (its separator first, then the word itself), the word was wrong and is taken back.
     */
    fun onBackspace(textBefore: CharSequence) {
        val (before, word) = lastLearned ?: return
        val text = textBefore.toString()
        when {
            text.endsWith(word, ignoreCase = true) -> {
                lastLearned = null
                if (!isPersonalLearningActive()) return
                scope.launch { personalNgram.unlearn(before, before.length >= TRUNCATION_HINT_CHARS, word) }
            }
            text.endsWith("$word ", ignoreCase = true) -> Unit
            else -> lastLearned = null
        }
    }

    private suspend fun latinProvider(): LatinLanguageProvider? {
        val subtype = subtypeManager.activeSubtype
        return providers.withLock { it[subtype.nlpProviders.suggestion] }?.provider as? LatinLanguageProvider
    }

    private fun reloadGlideLexicon() {
        val glide = appContext.glideTypingManager
        if (!glide.isInitialized()) return
        glide.value.reloadLexicon()
    }

    fun getListOfWords(subtype: Subtype): List<String> {
        return runBlocking { getSuggestionProvider(subtype).getListOfWords(subtype) }
    }

    fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        return runBlocking { getSuggestionProvider(subtype).getFrequencyForWord(subtype, word) }
    }

    private fun assembleCandidates() {
        runBlocking {
            val candidates = when {
                isSuggestionOn() -> {
                    clipboardSuggestionProvider.suggest(
                        subtype = Subtype.DEFAULT,
                        content = imeController.snapshotState().content,
                        maxCandidateCount = 8,
                        allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
                        isPrivateSession = false // TODO keyboardManager.activeState.isIncognitoMode,
                    ).ifEmpty {
                        buildList {
                            internalSuggestionsGuard.withLock {
                                addAll(internalSuggestions.second)
                            }
                        }
                    }
                }
                else -> emptyList()
            }
            activeCandidates = candidates
            autoExpandCollapseSmartbarActions(candidates, NlpInlineAutofill.suggestions.value)
        }
    }

    fun autoExpandCollapseSmartbarActions(list1: List<*>?, list2: List<*>?) {
        if (!prefs.smartbar.enabled.get()) {// || !prefs.smartbar.sharedActionsAutoExpandCollapse.get()) {
            return
        }
        // TODO: this is a mess and needs to be cleaned up in v0.5 with the NLP development
        /*if (keyboardManager.inputEventDispatcher.isRepeatableCodeLastDown()
            && !keyboardManager.inputEventDispatcher.isPressed(KeyCode.DELETE)
            && !keyboardManager.inputEventDispatcher.isPressed(KeyCode.FORWARD_DELETE)
            || keyboardManager.activeState.isActionsOverflowVisible
        ) {
            return // We do not auto switch if a repeatable action key was last pressed or if the actions overflow
                   // menu is visible to prevent annoying UI changes
        }*/
        // An editor's inline autocompletion (browser address bars) is not a user selection.
        val isSelection = !imeController.snapshotState().content.isTypingPosition()
        val isExpanded = shouldExpandSharedActions(list1, list2, isSelection)
        scope.launch {
            prefs.smartbar.sharedActionsExpandWithAnimation.set(false)
            prefs.smartbar.sharedActionsExpanded.set(isExpanded)
        }
    }

    fun addToDebugOverlay(word: String, info: SpellingResult) {
        debugOverlaySuggestionsInfos.put(System.currentTimeMillis(), word to info)
        debugOverlayVersion.update { it + 1 }
    }

    fun clearDebugOverlay() {
        debugOverlaySuggestionsInfos.evictAll()
        debugOverlayVersion.update { it + 1 }
    }

    private class ProviderInstanceWrapper(val provider: NlpProvider) {
        private var isInstanceAlive = AtomicBoolean(false)

        suspend fun createIfNecessary() {
            if (!isInstanceAlive.getAndSet(true)) provider.create()
        }

        suspend fun preload(subtype: Subtype) {
            provider.preload(subtype)
        }

        suspend fun destroyIfNecessary() {
            if (isInstanceAlive.getAndSet(true)) provider.destroy()
        }
    }

    inner class ClipboardSuggestionProvider internal constructor(private val context: Context) : SuggestionProvider {
        private var lastClipboardItemId: Long = -1

        override val providerId = "io.github.seshuthota.drift.nlp.providers.clipboard"

        override suspend fun create() {
            // Do nothing
        }

        override suspend fun preload(subtype: Subtype) {
            // Do nothing
        }

        override suspend fun suggest(
            subtype: Subtype,
            content: K3Content,
            maxCandidateCount: Int,
            allowPossiblyOffensive: Boolean,
            isPrivateSession: Boolean,
        ): List<SuggestionCandidate> {
            // Check if enabled
            if (!prefs.clipboard.suggestionEnabled.get()) return emptyList()

            val text = buildString {
                // TODO hack
                append(content.surroundingText.textBefore)
                append(content.surroundingText.textSelected)
                append(content.surroundingText.textAfter)
            }
            val currentItem = validateClipboardItem(clipboardManager.primaryClip, lastClipboardItemId, text)
                ?: return emptyList()

            return buildList {
                val now = System.currentTimeMillis()
                if ((now - currentItem.creationTimestampMs) < prefs.clipboard.suggestionTimeout.get() * 1000) {
                    add(ClipboardSuggestionCandidate(currentItem, sourceProvider = this@ClipboardSuggestionProvider, context = context))
                    if (currentItem.isSensitive) {
                        return@buildList
                    }
                    if (currentItem.type == ItemType.TEXT) {
                        val text = currentItem.stringRepresentation()
                        val matches = buildList {
                            addAll(NetworkUtils.getEmailAddresses(text))
                            addAll(NetworkUtils.getUrls(text))
                            addAll(NetworkUtils.getPhoneNumbers(text))
                        }
                        matches.forEachIndexed { i, match ->
                            val isUniqueMatch = matches.subList(0, i).all { prevMatch ->
                                prevMatch.value != match.value && prevMatch.range.intersect(match.range).isEmpty()
                            }
                            if (match.value != text && isUniqueMatch) {
                                add(ClipboardSuggestionCandidate(
                                    clipboardItem = currentItem.copy(
                                        // TODO: adjust regex of phone number so we don't need to manually strip the
                                        //  parentheses from the match results
                                        text = if (match.value.startsWith("(") && match.value.endsWith(")")) {
                                            match.value.substring(1, match.value.length - 1)
                                        } else {
                                            match.value
                                        }
                                    ),
                                    sourceProvider = this@ClipboardSuggestionProvider,
                                    context = context,
                                ))
                            }
                        }
                    }
                }
            }
        }

        override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
            if (candidate is ClipboardSuggestionCandidate) {
                lastClipboardItemId = candidate.clipboardItem.id
            }
        }

        override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
            // Do nothing
        }

        override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
            if (candidate is ClipboardSuggestionCandidate) {
                lastClipboardItemId = candidate.clipboardItem.id
                return true
            }
            return false
        }

        override suspend fun getListOfWords(subtype: Subtype): List<String> {
            return emptyList()
        }

        override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
            return 0.0
        }

        override suspend fun destroy() {
            // Do nothing
        }

        private fun validateClipboardItem(currentItem: ClipboardItem?, lastItemId: Long, contentText: String) =
            currentItem?.takeIf {
                // Check if already used
                it.id != lastItemId
                    // Check if content is empty
                    && contentText.isBlank()
                    // Check if clipboard content has any valid characters
                    && !currentItem.text.isNullOrBlank()
                    && !blankStrRegex.matches(currentItem.text)
            }
    }
}

/** The editor context window is ~50 chars; at or above this the text is assumed to be cut. */
private const val TRUNCATION_HINT_CHARS = 48

/**
 * The shared smartbar row shows actions instead of suggestions when there is nothing to suggest (no candidates and
 * no inline autofill suggestions) or when the user has selected text, where actions such as copy are what they need.
 */
internal fun shouldExpandSharedActions(candidates: List<*>?, inlineSuggestions: List<*>?, isSelection: Boolean): Boolean {
    return isSelection || (candidates.isNullOrEmpty() && inlineSuggestions.isNullOrEmpty())
}
