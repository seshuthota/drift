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

package io.github.seshuthota.drift.ime.keyboard3

import android.icu.text.BreakIterator
import android.text.TextUtils
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.seshuthota.drift.DriftApplication
import io.github.seshuthota.drift.DriftImeService
import io.github.seshuthota.drift.app.DriftPreferenceStore
import io.github.seshuthota.drift.ime.ImeUiMode
import io.github.seshuthota.drift.ime.QualityLog
import io.github.seshuthota.drift.ime.clipboard.ClipboardManager
import io.github.seshuthota.drift.ime.clipboard.provider.ClipboardItem
import io.github.seshuthota.drift.ime.clipboard.provider.ItemType
import io.github.seshuthota.drift.ime.core.SubtypeManager
import io.github.seshuthota.drift.ime.editor.DriftEditorInfo
import io.github.seshuthota.drift.ime.editor.ImeOptions
import io.github.seshuthota.drift.ime.editor.InputAttributes
import io.github.seshuthota.drift.ime.io.StorageController
import io.github.seshuthota.drift.ime.keyboard.IncognitoMode
import io.github.seshuthota.drift.ime.keyboard3.extension.loadFoundationKeyboards
import io.github.seshuthota.drift.ime.keyboard3.extension.selectFoundationKeyboard
import io.github.seshuthota.drift.ime.keyboard3.hint.FlickKeyHintPlacement
import io.github.seshuthota.drift.ime.keyboard3.hint.LongPressKeyHintPlacement
import io.github.seshuthota.drift.ime.keyboard3.touch.FnKeyArrangement
import io.github.seshuthota.drift.ime.keyboard3.touch.InputShiftState
import io.github.seshuthota.drift.ime.keyboard3.touch.ShiftKeyBehavior
import io.github.seshuthota.drift.ime.keyboard3.touch.TouchModelCache
import io.github.seshuthota.drift.ime.keyboard3.touch.TouchModelOptions
import io.github.seshuthota.drift.ime.media.emoji.EmojiSuggestionType
import io.github.seshuthota.drift.ime.nlp.BreakIterators
import io.github.seshuthota.drift.ime.nlp.ClipboardSuggestionCandidate
import io.github.seshuthota.drift.ime.nlp.GlideSuggestionCandidate
import io.github.seshuthota.drift.ime.nlp.NlpManager
import io.github.seshuthota.drift.ime.nlp.latin.NwpText
import io.github.seshuthota.drift.ime.nlp.latin.isTypingPosition
import io.github.seshuthota.drift.ime.nlp.SuggestionCandidate
import io.github.seshuthota.drift.ime.nlp.WordSuggestionCandidate
import io.github.seshuthota.drift.ime.text.key.KeyVariation
import io.github.seshuthota.drift.lib.DriftLocale
import io.github.seshuthota.drift.lib.devtools.flogDebug
import io.github.seshuthota.drift.lib.devtools.flogInfo
import io.github.seshuthota.drift.lib.devtools.flogWarning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import io.github.seshuthota.drift.lib.kotlin.collectIn
import org.k3lp.lib.text.K3Descriptor
import org.k3lp.lib.text.K3String
import org.k3lp.lib.text.K3StringOrDescriptor
import org.k3lp.lib.text.asK3String
import org.k3lp.model.K3Model
import org.k3lp.model.layer.K3LayerId
import org.k3lp.runtime.K3Content
import org.k3lp.runtime.K3InputMethod
import org.k3lp.runtime.K3SurroundingText
import org.k3lp.runtime.K3TextRange
import java.lang.ref.WeakReference
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

val LocalImeController = staticCompositionLocalOf<ImeController> {
    error("No IME controller is associated with this composition tree.")
}

@OptIn(ExperimentalAtomicApi::class)
class ImeController(
    storageController: StorageController,
    val clipboardManager: ClipboardManager,
    initialState: ImeState = ImeState(),
    val touchModelCache: TouchModelCache = TouchModelCache(),
    private val subtypeManager: SubtypeManager? = null,
    private val nlpManager: Lazy<NlpManager>? = null,
) : K3InputMethod<ImeState, ImeEditor, ImeController.UpdateImeStateScope>(
    initialState = initialState,
) {
    private val prefs by DriftPreferenceStore
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var foundationKeyboards: List<K3Model> = emptyList()
    private val breakIterators = BreakIterators()
    private val expectedContentQueue = ExpectedContentQueue()
    private var emitInterceptor = AtomicReference<EmitInterceptor?>(null)

    private var lastAutocorrection: Autocorrection? = null
    private var rejectedAutocorrection: String? = null
    /**
     * Words autocorrect changed in this field, lowercased. Typing one of them again after deleting the correction
     * means it was meant: it is left alone and learned, as if the correction had been undone.
     */
    private val recentlyAutocorrected = ArrayDeque<String>()
    @Volatile
    private var lastEditorSelection: K3TextRange? = null

    val activeTouchModelOptions = combine<Any, TouchModelOptions>(
        prefs.keyboard.numberRow.asFlow(),
        prefs.keyboard.fnKeyEnabled.asFlow(),
        prefs.keyboard.fnKeyArrangement.asFlow(),
        prefs.keyboard.longPressKeyHintEnabled.asFlow(),
        prefs.keyboard.longPressKeyHintPlacement.asFlow(),
        prefs.keyboard.multiTapHighlightEnabled.asFlow(),
        prefs.keyboard.flickKeyHintEnabled.asFlow(),
        prefs.keyboard.flickKeyHintPlacement.asFlow(),
    ) { values ->
        TouchModelOptions(
            showNumberRow = values[0] as Boolean,
            fnKeyEnabled = values[1] as Boolean,
            fnKeyArrangement = values[2] as FnKeyArrangement,
            longPressKeyHintEnabled = values[3] as Boolean,
            longPressKeyHintPlacement = values[4] as LongPressKeyHintPlacement,
            multiTapHighlightEnabled = values[5] as Boolean,
            flickKeyHintEnabled = values[6] as Boolean,
            flickKeyHintPlacement = values[7] as FlickKeyHintPlacement,
        )
    }.stateIn(scope, SharingStarted.Eagerly, TouchModelOptions.Default)

    val activeSmartbarVisibleDynamicActionsCount = MutableStateFlow(0)

    init {
        combine(
            prefs.devtools.enabled.asFlow(),
            prefs.devtools.showDragAndDropHelpers.asFlow(),
        ) { devtoolsEnabled, showDragAndDropHelpers ->
            devtoolsEnabled && showDragAndDropHelpers
        }.collectIn(scope) { showDragAndDropHelpers ->
            updateState {
                state = state.copy(
                    flags = state.flags
                        .withDebugShowDragAndDropHelpers(showDragAndDropHelpers),
                )
            }
        }

        scope.launch {
            foundationKeyboards = loadFoundationKeyboards(storageController.activeStorage.value)
            applyFoundationKeyboardForActiveSubtype()
            subtypeManager?.activeSubtypeFlow?.collect {
                applyFoundationKeyboardForActiveSubtype()
            }
        }
    }

    private suspend fun applyFoundationKeyboardForActiveSubtype() {
        val model = selectFoundationKeyboard(foundationKeyboards, subtypeManager?.activeSubtype?.primaryLocale) ?: return
        updateState {
            if (state.model !== model) {
                switchModel(model)
            }
        }
    }

    fun onHardwareKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null) {
            return false
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DEL -> true
            KeyEvent.KEYCODE_FORWARD_DEL -> true
            else -> false
        }
    }

    fun onHardwareKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null) {
            return false
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                updateStateBlocking {
                    emitBackspace()
                }
                true
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                updateStateBlocking {
                    emitForwardDelete()
                }
                true
            }
            else -> false
        }
    }

    /**
     * True while something other than k3 (voice input) is writing composing text into the field. Selection
     * updates are ignored meanwhile: k3 would re-evaluate the composition and shrink the composing region to the
     * last word, so the next live preview replaced only that word and the text piled up. k3 resyncs on the first
     * selection update after this is cleared.
     */
    @Volatile
    var isExternalEditActive: Boolean = false
        private set

    fun beginExternalEdit() {
        isExternalEditActive = true
    }

    fun endExternalEdit() {
        if (!isExternalEditActive) return
        isExternalEditActive = false
        val selection = lastEditorSelection ?: activeState.value.editor.currentSelection() ?: return
        updateStateBlocking { handleUpdateSelection(selection) }
    }

    fun setEmitInterceptor(interceptor: EmitInterceptor?) {
        emitInterceptor.store(interceptor)
    }

    fun snapshotState(): ImeState = activeState.value

    inline fun updateStateBlocking(crossinline function: UpdateImeStateScope.() -> Unit) {
        runBlocking {
            updateState(function)
        }
    }

    override fun updateStateScopeOf(state: ImeState): UpdateImeStateScope {
        return UpdateImeStateScope(state)
    }

    inner class UpdateImeStateScope(
        state: ImeState,
    ) : UpdateStateScope<ImeState, ImeEditor>(state) {
        fun handleStartInputView(
            ic: WeakReference<InputConnection>,
            info: DriftEditorInfo,
        ) {
            val newTouchLayerId: K3LayerId
            val keyVariation: KeyVariation
            when (info.inputAttributes.type) {
                InputAttributes.Type.NUMBER -> {
                    keyVariation = KeyVariation.NORMAL
                    newTouchLayerId = ImeLayerIds.Numpad
                }
                InputAttributes.Type.PHONE -> {
                    keyVariation = KeyVariation.NORMAL
                    newTouchLayerId = ImeLayerIds.Telpad
                }
                InputAttributes.Type.TEXT -> {
                    keyVariation = when (info.inputAttributes.variation) {
                        InputAttributes.Variation.EMAIL_ADDRESS,
                        InputAttributes.Variation.WEB_EMAIL_ADDRESS,
                            -> {
                            KeyVariation.EMAIL_ADDRESS
                        }
                        InputAttributes.Variation.PASSWORD,
                        InputAttributes.Variation.VISIBLE_PASSWORD,
                        InputAttributes.Variation.WEB_PASSWORD,
                            -> {
                            KeyVariation.PASSWORD
                        }
                        InputAttributes.Variation.URI -> {
                            KeyVariation.URI
                        }
                        else -> {
                            KeyVariation.NORMAL
                        }
                    }
                    newTouchLayerId = ImeLayerIds.Base
                }
                else -> {
                    keyVariation = KeyVariation.NORMAL
                    newTouchLayerId = ImeLayerIds.Base
                }
            }
            val initialSelection = info.initialSelection
            val initialSurrounding = K3SurroundingText(
                textBefore = info.getInitialTextBeforeCursor(20)?.toString() ?: "",
                textSelected = info.getInitialSelectedText()?.toString() ?: "",
                textAfter = info.getInitialTextAfterCursor(20)?.toString() ?: "",
            )

            val rememberCapsLockState = prefs.typing.rememberCapsLockState.get() &&
                state.flags.inputShiftState == InputShiftState.CAPS_LOCK

            state = state.copy(
                editor = ImeEditor(ic, info),
                touchLayerId = when {
                    rememberCapsLockState && newTouchLayerId.isTextLayer() -> ImeLayerIds.Caps
                    else -> newTouchLayerId
                },
                flags = state.flags
                    .withKeyVariation(keyVariation)
                    .withImeUiMode(
                        if (state.flags.imeUiMode != ImeUiMode.CLIPBOARD || prefs.clipboard.historyHideOnNextTextField.get()) {
                            ImeUiMode.TEXT
                        } else {
                            state.flags.imeUiMode
                        }
                    )
                    .withActionsOverflowVisible(false)
                    .withActionsEditorVisible(false)
                    .withInputShiftState(
                        when {
                            rememberCapsLockState -> InputShiftState.CAPS_LOCK
                            else -> InputShiftState.UNSHIFTED
                        }
                    )
                    .withComposingEnabled(
                        when (newTouchLayerId) {
                            ImeLayerIds.Numpad, ImeLayerIds.Telpad -> false
                            else -> keyVariation != KeyVariation.PASSWORD &&
                                info.isRichInputEditor &&
                                info.inputAttributes.flagTextAutoCorrect
                        }
                    )
                    .withIncognitoMode(
                        when (prefs.suggestion.incognitoMode.get()) {
                            IncognitoMode.FORCE_OFF -> false
                            IncognitoMode.FORCE_ON -> true
                            IncognitoMode.DYNAMIC_ON_OFF -> {
                                info.imeOptions.flagNoPersonalizedLearning ||
                                    prefs.suggestion.forceIncognitoModeFromDynamic.get()
                            }
                        }
                    )
            )
            lastEditorSelection = null
            if (!info.isRawInputEditor) {
                val live = state.editor.getSurroundingText(50, 10)
                val trustworthy = isSurroundingTextTrustworthy(initialSelection, live, initialSurrounding)
                setTrust(!trustworthy, "startInputView", initialSelection)
            }
            loadContent(initialSelection, initialSurrounding)
            expectedContentQueue.clear()
            lastAutocorrection = null
            rejectedAutocorrection = null
            recentlyAutocorrected.clear()
            requestSuggestions(force = true)
        }

        fun handleUpdateSelection(newSelection: K3TextRange) {
            lastEditorSelection = newSelection
            if (isExternalEditActive) return
            val content = expectedContentQueue.popUntilOrNull { it.selection == newSelection }
            if (content != null) {
                flogDebug { "DEDUPLICATED!!1" }
                return
            }
            resetContent(newSelection, state.editor.getSurroundingText(50, 10))
            lastAutocorrection = null
            // Selection updates also fire for layout/scroll changes and can carry stale selections, so only drop
            // glide alternatives once the glided word is really no longer directly before the cursor.
            dismissGlideAlternativesIfStale()
            expectedContentQueue.push(state.content)
            requestSuggestions()
        }

        override fun emit(value: K3StringOrDescriptor) {
            val interceptor = emitInterceptor.load()
            if (interceptor != null) {
                interceptor.onEmit(value)
                return
            }
            dismissGlideAlternatives()
            super.emit(value)
        }

        override fun emitText(value: K3String) {
            val text = value.toText()
            if (text == " " && commitDoubleSpacePeriod()) {
                return
            }
            val wordBeforeSeparator = if (text.length == 1 && text[0] in AUTOCORRECT_SEPARATORS) {
                NwpText.partialWord(state.content.surroundingText.textBefore)
            } else {
                null
            }
            val textBeforeWord = wordBeforeSeparator?.let { word ->
                state.content.surroundingText.textBefore.let { it.subSequence(0, it.length - word.length).toString() }
            }
            val corrected = if (wordBeforeSeparator != null) autocorrectBeforeSeparator() else null
            lastAutocorrection = null
            super.emitText(value)
            if (corrected == null && !wordBeforeSeparator.isNullOrEmpty() && isProseField()) {
                nlpManager?.takeIf { it.isInitialized() }?.value?.noteTypedWord(wordBeforeSeparator, immediate = false)
            }
            if (!wordBeforeSeparator.isNullOrEmpty() && textBeforeWord != null && isProseField()) {
                nlpManager?.takeIf { it.isInitialized() }?.value
                    ?.learnWord(textBeforeWord, corrected?.corrected ?: wordBeforeSeparator)
            }
            if (!wordBeforeSeparator.isNullOrEmpty() && isQualityLogged()) {
                if (corrected != null) {
                    QualityLog.wordCommitted(corrected.corrected, "autocorrect", original = corrected.original)
                } else {
                    QualityLog.wordCommitted(wordBeforeSeparator, "typed")
                }
            }
            expectedContentQueue.push(state.content)
            if (corrected != null) {
                lastAutocorrection = corrected.copy(separator = text)
            }
            reevaluateInputShiftState()
            requestSuggestions()
        }

        private fun commitDoubleSpacePeriod(): Boolean {
            if (!prefs.correction.doubleSpacePeriod.get()) return false
            if (state.content.selection.isNotCollapsed()) return false
            val info = state.editor.info
            if (info.isRawInputEditor || !info.isRichInputEditor) return false
            if (state.flags.keyVariation == KeyVariation.PASSWORD) return false
            val variation = info.inputAttributes.variation
            if (variation == InputAttributes.Variation.PASSWORD ||
                variation == InputAttributes.Variation.VISIBLE_PASSWORD ||
                variation == InputAttributes.Variation.WEB_PASSWORD
            ) {
                return false
            }
            val before = state.editor.getTextBeforeCursor(2)
            if (!isDoubleSpacePeriod(before)) return false
            lastAutocorrection = null
            if (!replaceBeforeCursor(1, ". ")) return false
            requestSuggestions()
            return true
        }

        private fun autocorrectBeforeSeparator(): Autocorrection? {
            if (!prefs.correction.autoCorrect.get()) return null
            if (!isProseField()) return null
            if (state.content.selection.isNotCollapsed()) return null
            val nlp = nlpManager?.takeIf { it.isInitialized() }?.value ?: return null
            val subtype = subtypeManager?.activeSubtype ?: return null
            val typed = NwpText.partialWord(state.content.surroundingText.textBefore)
            if (typed.length < 2 || typed == rejectedAutocorrection) return null
            if (typed.lowercase() in recentlyAutocorrected) {
                rejectedAutocorrection = typed
                nlp.noteTypedWord(typed, immediate = true)
                flogDebug { "autocorrect: '$typed' retyped after a correction, keeping it" }
                return null
            }
            val start = System.nanoTime()
            val candidate = nlp.autoCommitCandidateFor(subtype, state.content) as? WordSuggestionCandidate ?: return null
            flogDebug { "autocorrect lookup took ${(System.nanoTime() - start) / 1000} µs" }
            if (candidate.correctsWord != typed) return null
            if (state.editor.getTextBeforeCursor(typed.length).toString() != typed) return null
            val corrected = candidate.text.toString()
            if (corrected == typed || !replaceBeforeCursor(typed.length, corrected)) return null
            flogDebug { "autocorrect: '$typed' -> '$corrected'" }
            recentlyAutocorrected.remove(typed.lowercase())
            recentlyAutocorrected.addLast(typed.lowercase())
            if (recentlyAutocorrected.size > RECENT_AUTOCORRECTIONS) recentlyAutocorrected.removeFirst()
            return Autocorrection(original = typed, corrected = corrected, separator = "")
        }

        fun isProseField(): Boolean {
            val info = state.editor.info
            if (info.isRawInputEditor || !info.isRichInputEditor) return false
            val attributes = info.inputAttributes
            return attributes.type == InputAttributes.Type.TEXT && !attributes.flagTextNoSuggestions &&
                attributes.variation in AUTOCORRECT_VARIATIONS && state.flags.keyVariation != KeyVariation.PASSWORD
        }

        private fun revertAutocorrection(autocorrection: Autocorrection): Boolean {
            val committed = autocorrection.corrected + autocorrection.separator
            if (state.content.selection.isNotCollapsed() ||
                state.editor.getTextBeforeCursor(committed.length).toString() != committed
            ) {
                return false
            }
            val textBefore = state.content.surroundingText.textBefore
            val textBeforeWord = textBefore.subSequence(0, textBefore.length - committed.length)
            if (!replaceBeforeCursor(committed.length, autocorrection.original)) return false
            if (isProseField()) {
                nlpManager?.takeIf { it.isInitialized() }?.value
                    ?.replaceLearnedWord(textBeforeWord, autocorrection.corrected, autocorrection.original)
            }
            flogDebug { "autocorrect reverted: '${autocorrection.corrected}' -> '${autocorrection.original}'" }
            if (isQualityLogged()) QualityLog.autocorrectUndone(autocorrection.original, autocorrection.corrected)
            rejectedAutocorrection = autocorrection.original
            nlpManager?.takeIf { it.isInitialized() }?.value?.noteTypedWord(autocorrection.original, immediate = true)
            requestSuggestions()
            return true
        }

        override fun emitDescriptor(descriptor: K3Descriptor) {
            val windowController = DriftImeService.windowControllerOrNull()
            when (descriptor) {
                ImeActions.Delete -> emitForwardDelete()
                ImeActions.BackspaceWord -> {
                    if (state.editor.info.isRawInputEditor) {
                        // Ctrl+W is readline's kill-word. The text model does not hold the shell line.
                        state.editor.commitRawBytes("\u0017", dropTracked = true)
                        resetContent()
                    } else {
                        super.emitDescriptor(descriptor)
                    }
                }
                ImeActions.ArrowDown -> state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN)
                ImeActions.ArrowLeft -> state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT)
                ImeActions.ArrowRight -> state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT)
                ImeActions.ArrowUp -> state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_UP)
                ImeActions.ClipboardCopy -> {
                    if (state.editor.info.isRichInputEditor) {
                        val text = state.content.surroundingText.textSelected
                        if (text.isNotEmpty()) {
                            clipboardManager.addNewPlaintext(text)
                        }
                    } else {
                        state.editor.performClipboardCopy()
                    }
                }
                ImeActions.ClipboardCut -> {
                    if (state.editor.info.isRichInputEditor) {
                        val text = state.content.surroundingText.textSelected
                        if (text.isNotEmpty()) {
                            clipboardManager.addNewPlaintext(text)
                            emitBackspace()
                        }
                    } else {
                        state.editor.performClipboardCut()
                    }
                }
                ImeActions.ClipboardPaste -> {
                    if (state.editor.info.isRichInputEditor) {
                        emitClipboardItem(clipboardManager.primaryClip)
                    } else {
                        state.editor.performClipboardPaste()
                    }
                }
                ImeActions.ClipboardClearHistory -> clipboardManager.clearHistory()
                ImeActions.ClipboardClearFullHistory -> clipboardManager.clearFullHistory()
                ImeActions.ClipboardClearPrimaryClip -> {
                    if (prefs.clipboard.clearPrimaryClipAffectsHistoryIfUnpinned.get()) {
                        clipboardManager.primaryClip?.let { clipboardManager.deleteClip(it, onlyIfUnpinned = true) }
                    }
                    clipboardManager.updatePrimaryClip(null)
                }
                ImeActions.SelectAll -> state.editor.performSelectAll()
                ImeActions.ShowImeWindow -> DriftImeService.showUi()
                ImeActions.HideImeWindow -> DriftImeService.hideUi()
                ImeActions.ShowTextPanel -> {
                    state = state.copy(
                        flags = state.flags.withImeUiMode(ImeUiMode.TEXT),
                    )
                }
                ImeActions.ShowMediaPanel -> {
                    state = state.copy(
                        flags = state.flags.withImeUiMode(ImeUiMode.MEDIA),
                    )
                }
                ImeActions.ShowClipboardPanel -> {
                    state = state.copy(
                        flags = state.flags.withImeUiMode(ImeUiMode.CLIPBOARD),
                    )
                }
                ImeActions.Undo -> state.editor.performUndo()
                ImeActions.Redo -> state.editor.performRedo()
                ImeActions.Settings -> DriftImeService.launchSettings()
                ImeActions.ShowInputMethodPicker -> DriftImeService.showImePicker()
                ImeActions.SwitchToPrevInputMethod -> DriftImeService.switchToPrevInputMethod()
                ImeActions.SwitchToNextInputMethod -> DriftImeService.switchToNextInputMethod()
                ImeActions.ShowSubtypePicker -> {}
                ImeActions.SwitchToPrevSubtype -> subtypeManager?.switchToPrevSubtype()
                ImeActions.SwitchToNextSubtype -> subtypeManager?.switchToNextSubtype()
                ImeActions.ToggleActionsEditor -> {
                    state = state.copy(
                        flags = state.flags
                            .withActionsEditorVisible(!state.flags.isActionsEditorVisible),
                    )
                }
                ImeActions.ToggleActionsOverflow -> {
                    state = state.copy(
                        flags = state.flags
                            .withActionsOverflowVisible(!state.flags.isActionsOverflowVisible),
                    )
                }
                ImeActions.ToggleCompactLayout -> windowController?.actions?.toggleCompactLayout()
                ImeActions.ToggleSmartbarVisibility -> {
                    val enabled = prefs.smartbar.enabled.get()
                    scope.launch { prefs.smartbar.enabled.set(!enabled) }
                }
                ImeActions.ToggleAutocorrect -> {
                    val enabled = prefs.correction.autoCorrect.get()
                    scope.launch { prefs.correction.autoCorrect.set(!enabled) }
                    requestSuggestions()
                }
                ImeActions.ToggleFloatingWindow -> windowController?.actions?.toggleFloatingWindow()
                ImeActions.ToggleResizeMode -> windowController?.editor?.toggleEnabled()
                ImeActions.CompactLayoutToLeft -> windowController?.actions?.compactLayoutToLeft()
                ImeActions.CompactLayoutToRight -> windowController?.actions?.compactLayoutToRight()
                ImeActions.LocalVoiceInput -> DriftApplication.getHack().asrController.value.toggle()
                ImeActions.ExternalVoiceInput -> DriftImeService.switchToVoiceInputMethod()
                else -> super.emitDescriptor(descriptor)
            }
            reevaluateInputShiftState()
        }

        override fun emitBackspace() {
            if (!state.flags.isIncognitoMode) QualityLog.backspace()
            val autocorrection = lastAutocorrection
            lastAutocorrection = null
            if (autocorrection != null && revertAutocorrection(autocorrection)) {
                return
            }
            nlpManager?.takeIf { it.isInitialized() }?.value?.onBackspace(state.content.surroundingText.textBefore)
            if (state.editor.info.isRawInputEditor) {
                // k3 only deletes characters it has tracked since this field was focused. A terminal
                // line is longer than that (prompt leftovers, text from before the keyboard attached),
                // and once the tracked buffer is empty further backspaces would do nothing.
                val lengthBefore = state.content.surroundingText.textBefore.length
                super.emitBackspace()
                if (ImeEditor.rawBackspaceNeedsExtraDelete(
                        lengthBefore,
                        state.content.surroundingText.textBefore.length,
                    )
                ) {
                    state.editor.commitRawBackspace()
                }
            } else if (state.editor.isTextUntrusted) {
                // k3 holds no reliable text for this field. A DEL key event lets the field delete by its own rules,
                // which also removes a link chip as one unit. k3 forgets its text so the next key cannot delete more.
                state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_DEL)
                super.resetContent(state.content.selection, K3SurroundingText.Empty)
                expectedContentQueue.clear()
                reevaluateInputShiftState()
                requestSuggestions()
                return
            } else {
                refillTrackedTextBeforeCursor()
                super.emitBackspace()
            }
            expectedContentQueue.push(state.content)
            requestSuggestions()
        }

        /**
         * k3 tracks only a window of the text before the cursor ([TRACKED_CHARS_BEFORE]). Each backspace shrinks
         * it, and the matching selection update is deduplicated against [expectedContentQueue] so it is never
         * re-read. In a long field the window runs dry while text remains and backspace silently stops. Re-read it
         * from the editor once it is shorter than the text that really precedes the cursor.
         */
        private fun refillTrackedTextBeforeCursor() {
            val content = state.content
            val selection = content.selection
            if (selection.isNotCollapsed()) return
            val tracked = content.surroundingText.textBefore.length
            if (tracked >= minOf(selection.start, TRACKED_REFILL_THRESHOLD)) return
            val fresh = state.editor.getSurroundingText(TRACKED_CHARS_BEFORE, 10)
            if (!isSurroundingTextTrustworthy(selection, fresh) || fresh.textBefore.length <= tracked) return
            resetContent(selection, fresh)
        }

        override fun emitEnter() {
            val info = state.editor.info
            if (info.isRawInputEditor) {
                // A terminal has no editor action. Enter must write a carriage return even when
                // imeOptions says NONE or UNSPECIFIED, or reverse-i-search never accepts the line.
                emitEnterKey()
                return
            }
            val isShiftPressed = false
            if (info.imeOptions.flagNoEnterAction || info.inputAttributes.flagTextMultiLine && isShiftPressed) {
                emitEnterKey()
            } else {
                when (val action = info.imeOptions.action) {
                    ImeOptions.Action.UNSPECIFIED,
                    ImeOptions.Action.DONE,
                    ImeOptions.Action.GO,
                    ImeOptions.Action.NEXT,
                    ImeOptions.Action.PREVIOUS,
                    ImeOptions.Action.SEARCH,
                    ImeOptions.Action.SEND -> emitEnterAction(action)
                    else -> emitEnterKey()
                }
            }
        }

        fun emitEnterKey() {
            val info = state.editor.info
            if (info.isRawInputEditor) {
                state.editor.commitRawEnter()
                // The shell consumed the line. Drop the tracked buffer so the next key starts clean.
                resetContent()
            } else {
                emitText(NEWLINE_SEQ)
            }
        }

        fun emitEnterAction(action: ImeOptions.Action) {
            state.editor.performEditorAction(action)
        }

        fun emitForwardDelete() {
            if (state.editor.info.isRawInputEditor) {
                state.editor.sendDownUpKeyEvent(KeyEvent.KEYCODE_FORWARD_DEL)
                return
            }
            if (state.content.selection.isNotCollapsed()) {
                emitBackspace()
            } else {
                val newSurroundingText = state.content.surroundingText.copy(
                    textAfter = state.content.surroundingText.textAfter.let { text ->
                        if (text.isEmpty()) text else text.substring(1)
                    },
                )
                state = state.copy(
                    content = state.content.copy(
                        surroundingText = newSurroundingText,
                    ),
                )
                state.editor.deleteSurroundingText(
                    charsBefore = 0,
                    charsAfter = 1,
                )
            }
        }

        fun emitCandidate(candidate: SuggestionCandidate) {
            when (candidate) {
                is ClipboardSuggestionCandidate -> emitClipboardItem(candidate.clipboardItem)
                is GlideSuggestionCandidate -> emitGlideReplacement(candidate)
                else -> emitCompletion(candidate)
            }
        }

        fun emitGlideReplacement(candidate: GlideSuggestionCandidate): Boolean {
            val oldText = candidate.replacesText ?: return false
            val newText = candidate.text.toString() + " "
            if (oldText != newText) {
                val textBefore = state.editor.getTextBeforeCursor(64)
                val textBeforeWord = textBefore.subSequence(0, (textBefore.length - oldText.length).coerceAtLeast(0))
                val replaced = if (state.editor.info.isRawInputEditor) {
                    replaceRawGlideTail(oldText, newText)
                } else {
                    state.editor.getTextBeforeCursor(oldText.length) == oldText &&
                        replaceBeforeCursor(oldText.length, newText)
                }
                if (!replaced) {
                    dismissGlideAlternatives()
                    return false
                }
                if (isProseField() && textBefore.endsWith(oldText)) {
                    nlpManager?.takeIf { it.isInitialized() }?.value
                        ?.replaceLearnedWord(textBeforeWord, oldText.trim(), newText.trim())
                }
                if (!state.flags.isIncognitoMode) {
                    val rank = nlpManager?.takeIf { it.isInitialized() }?.value?.activeCandidates.orEmpty()
                        .indexOfFirst { it is GlideSuggestionCandidate && it.text == candidate.text }
                    QualityLog.glidePicked(candidate.text.toString(), rank)
                }
            }
            requestSuggestions(force = true)
            return true
        }

        private fun replaceRawGlideTail(oldText: String, newText: String): Boolean {
            val tracked = state.editor.replaceRawTail(oldText, newText) ?: return false
            val content = state.content
            resetContent(
                K3TextRange(content.offset + tracked.length),
                content.surroundingText.copy(textBefore = tracked),
            )
            expectedContentQueue.push(state.content)
            val nlp = nlpManager?.takeIf { it.isInitialized() }?.value
            val alternatives = nlp?.activeCandidates.orEmpty()
                .map { if (it is GlideSuggestionCandidate) it.copy(replacesText = newText) else it }
            if (alternatives.size > 1) {
                nlp?.suggestDirectly(alternatives)
            }
            return true
        }

        /**
         * Replaces the [oldLength] characters directly before the cursor with [newText] via a single
         * composing-region commit (a plain deleteSurroundingText would skip over an active composing region). An
         * editor's inline autocompletion after the cursor is replaced as well.
         */
        private fun replaceBeforeCursor(oldLength: Int, newText: String): Boolean {
            val selection = state.content.selection
            if (!state.content.isTypingPosition() || selection.start < oldLength) {
                return false
            }
            if (oldLength == 0 && selection.isCollapsed()) {
                emitText(newText.asK3String())
                return true
            }
            val start = selection.start - oldLength
            val newCursor = start + newText.length
            val newSelection = K3TextRange(start = newCursor, end = newCursor)
            state.editor.replaceText(start until selection.end, newText, newSelection, null)
            resetContent(newSelection, state.editor.getSurroundingText(50, 10))
            expectedContentQueue.push(state.content)
            reevaluateInputShiftState()
            return true
        }

        private fun requestSuggestions(force: Boolean = false) {
            val nlp = nlpManager?.value ?: return
            val subtype = subtypeManager?.activeSubtype ?: return
            val info = state.editor.info
            if (info.isRawInputEditor || !info.isRichInputEditor ||
                state.flags.keyVariation == KeyVariation.PASSWORD
            ) {
                return
            }
            if (!force && nlp.activeCandidates.any { it is GlideSuggestionCandidate && it.replacesText != null }) {
                return
            }
            nlp.suggest(subtype, state.content)
        }

        private fun dismissGlideAlternativesIfStale() {
            val nlp = nlpManager?.takeIf { it.isInitialized() }?.value ?: return
            val glided = nlp.activeCandidates.firstNotNullOfOrNull { (it as? GlideSuggestionCandidate)?.replacesText }
                ?: return
            if (!state.content.isTypingPosition() || state.editor.getTextBeforeCursor(glided.length) != glided) {
                nlp.clearSuggestions()
            }
        }

        private fun dismissGlideAlternatives() {
            val nlp = nlpManager?.takeIf { it.isInitialized() }?.value ?: return
            if (nlp.activeCandidates.any { it is GlideSuggestionCandidate && it.replacesText != null }) {
                nlp.clearSuggestions()
            }
        }

        fun emitClipboardItem(item: ClipboardItem?): Boolean {
            return item != null && when (item.type) {
                ItemType.TEXT -> {
                    val text = item.text?.asK3String()
                    if (text != null) {
                        emitText(text)
                        true
                    } else {
                        false
                    }
                }
                ItemType.IMAGE, ItemType.VIDEO -> {
                    state.editor.performClipboardPasteMedia(item)
                }
            }.also {
                if (prefs.clipboard.historyHideOnPaste.get()) {
                    state = state.copy(
                        flags = state.flags.withImeUiMode(ImeUiMode.TEXT)
                    )
                }
            }
        }

        fun emitCompletion(candidate: SuggestionCandidate): Boolean {
            var text = candidate.text.toString()
            if (text.isEmpty() || state.editor.info.isRawInputEditor || !state.content.isTypingPosition()) {
                return false
            }
            if (state.flags.inputShiftState == InputShiftState.CAPS_LOCK) {
                text = text.uppercase()
            }
            val before = state.editor.getTextBeforeCursor(64)
            val partial = NwpText.partialWord(before)
            val last = before.getOrNull(before.length - partial.length - 1)
            val leadingSpace = partial.isEmpty() && last != null && (last.isLetterOrDigit() || last in ".,!?;:")
            val newText = (if (leadingSpace) " " else "") + text + " "
            val rank = nlpManager?.takeIf { it.isInitialized() }?.value?.activeCandidates.orEmpty().indexOf(candidate)
            val logged = isQualityLogged()
            val textBeforeWord = before.subSequence(0, before.length - partial.length).toString() +
                (if (leadingSpace) " " else "")
            if (!replaceBeforeCursor(partial.length, newText)) {
                return false
            }
            if (isProseField()) nlpManager?.takeIf { it.isInitialized() }?.value?.learnWord(textBeforeWord, text)
            if (logged) {
                QualityLog.wordCommitted(
                    text,
                    "pick",
                    rank = rank,
                    prefix = partial.toString(),
                    kind = candidate.javaClass.simpleName,
                )
            }
            requestSuggestions(force = true)
            return true
        }

        private fun isQualityLogged(): Boolean =
            !state.flags.isIncognitoMode && isProseField() && QualityLog.isEnabled()

        override fun switchTouchLayer(newTouchLayerId: K3LayerId) {
            if (newTouchLayerId.isTextLayer()) {
                super.switchTouchLayer(state.flags.inputShiftState.correspondingLayerId)
            } else {
                super.switchTouchLayer(newTouchLayerId)
            }
        }

        fun cycleInputShiftState(isDoubleTap: Boolean) {
            val shiftKeyBehavior = prefs.typing.shiftKeyBehavior.get()
            val oldInputShiftState = state.flags.inputShiftState
            val newInputShiftState = when (shiftKeyBehavior) {
                ShiftKeyBehavior.CAPSLOCK_BY_DOUBLE_TAP -> when {
                    isDoubleTap -> InputShiftState.CAPS_LOCK
                    oldInputShiftState == InputShiftState.UNSHIFTED -> InputShiftState.SHIFTED_MANUAL
                    else -> InputShiftState.UNSHIFTED
                }
                ShiftKeyBehavior.CAPSLOCK_BY_CYCLE -> when (oldInputShiftState) {
                    InputShiftState.UNSHIFTED -> InputShiftState.SHIFTED_MANUAL
                    InputShiftState.SHIFTED_MANUAL -> InputShiftState.CAPS_LOCK
                    InputShiftState.SHIFTED_AUTOMATIC -> InputShiftState.UNSHIFTED
                    InputShiftState.CAPS_LOCK -> InputShiftState.UNSHIFTED
                }
            }
            state = state.copy(
                touchLayerId = newInputShiftState.correspondingLayerId,
                flags = state.flags.withInputShiftState(newInputShiftState),
            )
        }

        fun revertCycleInputShiftState(
            newTouchLayerId: K3LayerId,
            newInputShiftState: InputShiftState,
        ) {
            state = state.copy(
                touchLayerId = newTouchLayerId,
                flags = state.flags.withInputShiftState(newInputShiftState),
            )
        }

        fun reevaluateInputShiftState() {
            val isPeekOngoing = false
            if (state.flags.inputShiftState == InputShiftState.CAPS_LOCK || isPeekOngoing) return
            val capsMode = state.content.cursorCapsMode(state.editor.info.inputAttributes)
            val shift = prefs.typing.autoCapitalization.get()
                && capsMode != InputAttributes.CapsMode.NONE
            val inputShiftState = when {
                shift -> InputShiftState.SHIFTED_AUTOMATIC
                else -> InputShiftState.UNSHIFTED
            }
            val touchLayerId = when {
                state.touchLayerId.isTextLayer() -> inputShiftState.correspondingLayerId
                else -> state.touchLayerId
            }
            state = state.copy(
                touchLayerId = touchLayerId,
                flags = state.flags.withInputShiftState(inputShiftState),
            )
        }

        private fun setTrust(untrusted: Boolean, source: String, selection: K3TextRange) {
            val editor = state.editor
            if (editor === ImeEditor.Disconnected || editor.isTextUntrusted == untrusted) return
            editor.isTextUntrusted = untrusted
            if (untrusted) {
                flogWarning { "$source: field reports on-screen text at $selection, editing relatively" }
            } else {
                flogInfo { "$source: field reports stored text again, editing by offsets" }
            }
        }

        private fun loadContent(selection: K3TextRange, surrounding: K3SurroundingText) {
            super.resetContent(selection, if (state.editor.isTextUntrusted) K3SurroundingText.Empty else surrounding)
            reevaluateInputShiftState()
        }

        override fun resetContent(newSelection: K3TextRange, newSurrounding: K3SurroundingText) {
            setTrust(!isSurroundingTextTrustworthy(newSelection, newSurrounding), "resetContent", newSelection)
            loadContent(newSelection, newSurrounding)
        }

        override fun resetContent() {
            super.resetContent()
            reevaluateInputShiftState()
        }

        fun handleFinishInputView() {
            resetContent()
            state = state.copy(editor = ImeEditor.Disconnected)
            lastEditorSelection = null
            expectedContentQueue.clear()
            nlpManager?.takeIf { it.isInitialized() }?.value?.clearSuggestions()
        }

        override fun evaluateCompositionOf(
            model: K3Model,
            selection: K3TextRange,
            surroundingText: K3SurroundingText
        ): K3TextRange? {
            if (selection.isNotCollapsed() || !state.flags.isComposingEnabled) {
                return null
            }
            val locale = DriftLocale.fromTag(model.locales.getOrElse(0) { "" })
            return breakIterators.word(locale) {
                it.setText(surroundingText.textBefore)
                val end = it.last()
                val isWord = it.ruleStatus != BreakIterator.WORD_NONE
                if (isWord) {
                    val start = it.previous().let { pos ->
                        (pos - 1).takeIf { updatedPos ->
                            surroundingText.textBefore.getOrNull(updatedPos) == EmojiSuggestionType.LEADING_COLON.prefix.first()
                        } ?: pos
                    }
                    val offset = (selection.min - surroundingText.textBefore.length).coerceAtLeast(0)
                    K3TextRange(start + offset, end + offset)
                } else {
                    null
                }
            }
        }
    }

    companion object {
        private val NEWLINE_SEQ = "\n".asK3String()
        private const val RECENT_AUTOCORRECTIONS = 8
        private const val TRACKED_CHARS_BEFORE = 50
        private const val TRACKED_REFILL_THRESHOLD = 20

        /** U+FFFC OBJECT REPLACEMENT CHARACTER, which editors put in their on-screen text for inline objects. */
        private const val OBJECT_REPLACEMENT = '\uFFFC'

        /**
         * Whether [surrounding] is the field's stored text, so that k3 may rewrite it at offsets taken from
         * [selection].
         *
         * Some editors return their on-screen text instead. ChatGPT shows a pasted link as a chip: its text reads
         * `U+2066 U+FFFC U+00A0 https://example.com/a… U+2069` while the field stores `https://example.com/abc`.
         * Building edits on that text replaced the link with its label, and offsets past the end of the stored
         * text crash Compose text fields. Text longer than the cursor position cannot end at the cursor, and U+FFFC
         * stands for an object rather than a character, so either means the text is not the stored text.
         */
        internal fun isSurroundingTextTrustworthy(selection: K3TextRange, surrounding: K3SurroundingText): Boolean {
            val cursor = minOf(selection.start, selection.end)
            if (cursor < 0) return true
            if (surrounding.textBefore.length > cursor) return false
            if (surrounding.textSelected.length > maxOf(selection.start, selection.end) - cursor) return false
            return OBJECT_REPLACEMENT !in surrounding.textBefore &&
                OBJECT_REPLACEMENT !in surrounding.textSelected &&
                OBJECT_REPLACEMENT !in surrounding.textAfter
        }

        internal fun isSurroundingTextTrustworthy(
            selection: K3TextRange,
            live: K3SurroundingText,
            initial: K3SurroundingText,
        ): Boolean {
            return isSurroundingTextTrustworthy(selection, live) && isSurroundingTextTrustworthy(selection, initial)
        }
    }
}

enum class OperationUnit {
    CHARACTERS,
    WORDS;
}

enum class OperationScope {
    BEFORE_CURSOR,
    AFTER_CURSOR;
}

fun interface EmitInterceptor {
    fun onEmit(value: K3StringOrDescriptor)
}

private class ExpectedContentQueue {
    private val list = mutableListOf<K3Content>()

    fun popUntilOrNull(predicate: (K3Content) -> Boolean): K3Content? {
        while (list.isNotEmpty()) {
            val item = list[0]
            if (predicate(item)) return item
            list.removeAt(0)
        }
        return null
    }

    fun push(item: K3Content) {
        list.add(item)
    }

    fun clear() {
        list.clear()
    }
}

private data class Autocorrection(val original: String, val corrected: String, val separator: String)

private val AUTOCORRECT_SEPARATORS = setOf(' ', '.', ',', '!', '?', ';', ':', '\n')

internal fun isDoubleSpacePeriod(textBeforeCursor: CharSequence): Boolean {
    if (textBeforeCursor.length < 2) return false
    val end = textBeforeCursor.length
    if (textBeforeCursor[end - 1] != ' ') return false
    return textBeforeCursor[end - 2].isLetterOrDigit()
}

private val AUTOCORRECT_VARIATIONS = setOf(
    InputAttributes.Variation.NORMAL,
    InputAttributes.Variation.EMAIL_SUBJECT,
    InputAttributes.Variation.LONG_MESSAGE,
    InputAttributes.Variation.SHORT_MESSAGE,
    InputAttributes.Variation.WEB_EDIT_TEXT,
)

fun K3Content.cursorCapsMode(inputAttributes: InputAttributes): InputAttributes.CapsMode {
    return InputAttributes.CapsMode.fromFlags(
        TextUtils.getCapsMode(surroundingText.textBefore, surroundingText.textBefore.length, inputAttributes.raw)
    )
}
