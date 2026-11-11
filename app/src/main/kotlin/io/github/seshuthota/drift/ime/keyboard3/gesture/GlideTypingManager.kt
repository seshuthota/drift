/*
 * Copyright (C) 2025-2026 The FlorisBoard Contributors
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

package io.github.seshuthota.drift.ime.keyboard3.gesture

import android.content.Context
import io.github.seshuthota.drift.DriftImeService
import io.github.seshuthota.drift.app.DriftPreferenceStore
import io.github.seshuthota.drift.ime.core.Subtype
import io.github.seshuthota.drift.ime.keyboard3.touch.InputShiftState
import io.github.seshuthota.drift.ime.nlp.GlideSuggestionCandidate
import io.github.seshuthota.drift.ime.nlp.latin.NwpText
import io.github.seshuthota.drift.nextWordPredictor
import io.github.seshuthota.drift.nlpManager
import io.github.seshuthota.drift.subtypeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.k3lp.lib.text.asK3String
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates glide typing: keeps the classifier fed with layout and word data, throttles previews
 * while the finger is moving, commits the best candidate on release and exposes the trail for
 * drawing.
 *
 * All classifier access is serialized through [classifierLock] because points arrive on the
 * pointer-event thread while classification runs on [scope].
 */
class GlideTypingManager(context: Context) {
    companion object {
        private const val MAX_SUGGESTION_COUNT = 8

        /** Trail samples are batched to roughly one frame; the classifier still sees every point. */
        private const val TRAIL_REFRESH_MS = 16L

        /**
         * Glide + next-word LM fusion: each candidate gets `LM_WEIGHT * ln P_lm(word | text before cursor)`, or
         * `-LM_WEIGHT * LM_FLOOR` if the word is outside the LM vocabulary. Tuned with `ml/nwp/fuse_eval.py`.
         */
        private const val LM_WEIGHT = 0.4
        private const val LM_FLOOR = 10.0

        /** The editor context window is ~50 chars; at or above this the text is assumed to be cut. */
        private const val TRUNCATION_HINT_CHARS = 48
    }

    private val prefs by DriftPreferenceStore
    private val nlpManager by context.nlpManager()
    private val subtypeManager by context.subtypeManager()
    private val predictor by context.nextWordPredictor()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val classifier: GlideTypingClassifier = HybridGlideTypingClassifier(context)
    private val classifierLock = Any()
    private val frequencyCache = HashMap<String, Double>()
    private val requestInFlight = AtomicBoolean(false)

    @Volatile
    private var preparedSubtype: Subtype? = null

    @Volatile
    private var preparedKeys: List<GlideKey>? = null
    private var prepareJob: Job? = null
    private var trailClearJob: Job? = null
    private var lastPreviewAt = 0L
    private var lastTrailAt = 0L
    private var glideId = 0
    private var suppressPreview = false
    private var addedPointCount = 0

    private val _trail = MutableStateFlow<List<GesturePoint>>(emptyList())

    /** Normalized gesture trail of the current/last glide, for drawing. */
    val trail: StateFlow<List<GesturePoint>> = _trail

    /**
     * Updates the key geometry and, when the subtype changed, loads its word corpus. Both happen
     * off the main thread; a gesture before completion simply yields no suggestions.
     */
    fun setLayout(keys: List<GlideKey>, subtype: Subtype) {
        if (keys == preparedKeys && subtype == preparedSubtype) {
            return
        }
        preparedKeys = keys
        if (subtype != preparedSubtype) {
            preparedSubtype = subtype
            synchronized(frequencyCache) { frequencyCache.clear() }
            prepareWords(subtype)
        }
        scope.launch {
            synchronized(classifierLock) { classifier.setLayout(keys) }
        }
    }

    /** Reloads the word list after a word was learned, without waiting for a layout change. */
    fun reloadLexicon() {
        val subtype = preparedSubtype ?: return
        synchronized(frequencyCache) { frequencyCache.clear() }
        prepareWords(subtype)
    }

    private fun prepareWords(subtype: Subtype) {
        prepareJob?.cancel()
        prepareJob = scope.launch {
            nlpManager.ensureSuggestionProviderPreloaded(subtype)
            predictor.ensureLoaded()
            val words = nlpManager.getListOfWords(subtype)
            if (preparedSubtype != subtype) {
                return@launch
            }
            synchronized(classifierLock) {
                classifier.setWordData(words) { word ->
                    synchronized(frequencyCache) {
                        frequencyCache.getOrPut(word) { nlpManager.getFrequencyForWord(subtype, word) }
                    }
                }
            }
        }
    }

    fun onGlideStart() {
        if (!prefs.glide.enabled.get()) {
            return
        }
        trailClearJob?.cancel()
        val currentGlide = synchronized(classifierLock) {
            glideId++
            suppressPreview = false
            classifier.clear()
            classifier.setContextScorer(null)
            glideId
        }
        if (LM_WEIGHT > 0.0) {
            prepareContextScorer(currentGlide)
        }
        _trail.value = emptyList()
        lastPreviewAt = 0L
        lastTrailAt = 0L
        addedPointCount = 0
    }

    /**
     * Scores glide candidates by how well they continue the text before the cursor. Runs while the finger is still
     * moving (one model call, ~1 ms); a glide that finishes first is decoded without context.
     */
    private fun prepareContextScorer(forGlide: Int) {
        val textBefore = DriftImeService.imeControllerOrNull()?.snapshotState()?.content?.surroundingText?.textBefore
            ?.toString() ?: return
        scope.launch {
            val logProbs = predictor.nextWordLogProbs(textBefore, textBefore.length >= TRUNCATION_HINT_CHARS)
                ?: return@launch
            val scorer: (String) -> Double = { word ->
                val id = predictor.idOf(NwpText.normalize(word))
                LM_WEIGHT * (if (id != null && id < logProbs.size) logProbs[id].toDouble() else -LM_FLOOR)
            }
            synchronized(classifierLock) {
                if (glideId == forGlide) {
                    classifier.setContextScorer(scorer)
                }
            }
        }
    }

    fun onGlidePoint(point: GesturePoint) {
        if (!prefs.glide.enabled.get()) {
            return
        }
        synchronized(classifierLock) { classifier.addGesturePoint(point) }
        addedPointCount++

        val now = System.currentTimeMillis()
        if (prefs.glide.showTrail.get() && now - lastTrailAt >= TRAIL_REFRESH_MS) {
            lastTrailAt = now
            _trail.update { it + point }
        }
        val refreshDelay = prefs.glide.previewRefreshDelay.get().toLong()
        if (prefs.glide.showPreview.get() && now - lastPreviewAt >= refreshDelay) {
            lastPreviewAt = now
            requestSuggestions(commit = false)
        }
    }

    fun onGlideComplete(points: List<GesturePoint>) {
        val enabled = prefs.glide.enabled.get()
        scheduleTrailClear()
        if (!enabled) {
            _trail.value = emptyList()
            return
        }
        synchronized(classifierLock) {
            // Pointer moves already fed the classifier; the outcome list is only a fallback for
            // streams that delivered no move samples at all.
            if (addedPointCount == 0) {
                points.forEach { classifier.addGesturePoint(it) }
            }
        }
        requestSuggestions(commit = true)
    }

    fun onGlideCancel() {
        synchronized(classifierLock) {
            glideId++
            suppressPreview = true
            classifier.clear()
        }
        addedPointCount = 0
        _trail.value = emptyList()
    }

    private fun scheduleTrailClear() {
        val showTrail = prefs.glide.showTrail.get()
        if (!showTrail) {
            _trail.value = emptyList()
            return
        }
        trailClearJob?.cancel()
        trailClearJob = scope.launch {
            delay(prefs.glide.trailDuration.get().toLong())
            _trail.value = emptyList()
        }
    }

    private fun requestSuggestions(commit: Boolean) {
        var requestId = 0
        val proceed = synchronized(classifierLock) {
            if (commit) {
                // A commit must never be dropped; it only needs to suppress stale previews.
                suppressPreview = true
            } else if (requestInFlight.getAndSet(true)) {
                return@synchronized false
            }
            requestId = glideId
            true
        }
        if (!proceed) {
            return
        }
        scope.launch {
            try {
                val suggestions = synchronized(classifierLock) {
                    classifier.getSuggestions(MAX_SUGGESTION_COUNT)
                }
                if (suggestions.isEmpty()) {
                    return@launch
                }
                val stillCurrent = synchronized(classifierLock) {
                    requestId == glideId && (commit || !suppressPreview)
                }
                if (!stillCurrent) {
                    return@launch
                }
                if (commit) {
                    withContext(Dispatchers.Main) {
                        val imeController = DriftImeService.imeControllerOrNull() ?: return@withContext
                        var alternatives = emptyList<GlideSuggestionCandidate>()
                        imeController.updateState {
                            val shiftState = state.flags.inputShiftState
                            val cased = suggestions.map { applyShiftState(it, shiftState) }
                            // Auto-space between words: a glided word after a manually typed
                            // word or letter must not be appended without a separator.
                            if (state.editor.getTextBeforeCursor(1).lastOrNull()?.isLetterOrDigit() == true) {
                                emitText(" ".asK3String())
                            }
                            val committed = cased.first() + " "
                            emitText(committed.asK3String())
                            // Keep every candidate (the committed one included, so the user can switch
                            // back) as a one-tap replacement for the word that was just inserted.
                            alternatives = cased.mapIndexed { index, word ->
                                GlideSuggestionCandidate(
                                    text = word,
                                    replacesText = committed,
                                    confidence = 1.0 / (index + 1),
                                )
                            }
                        }
                        if (alternatives.size > 1) {
                            nlpManager.suggestDirectly(alternatives)
                        } else {
                            nlpManager.clearSuggestions()
                        }
                    }
                } else {
                    nlpManager.suggestDirectly(
                        suggestions.mapIndexed { index, word ->
                            GlideSuggestionCandidate(text = word, confidence = 1.0 / (index + 1))
                        },
                    )
                }
            } finally {
                if (!commit) {
                    requestInFlight.set(false)
                }
            }
        }
    }
}

/** Cases a glided [word] the way a typed word would be cased under [shiftState]. */
internal fun applyShiftState(word: String, shiftState: InputShiftState): String = when (shiftState) {
    InputShiftState.CAPS_LOCK -> word.uppercase()
    InputShiftState.UNSHIFTED -> word
    else -> word.replaceFirstChar { it.uppercaseChar() }
}
