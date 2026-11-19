/*
 * Copyright (C) 2021-2026 The FlorisBoard Contributors
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

package io.github.seshuthota.drift

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.inputmethodservice.ExtractEditText
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.util.Size
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import io.github.seshuthota.drift.app.DriftAppActivity
import io.github.seshuthota.drift.app.DriftPreferenceStore
import io.github.seshuthota.drift.ime.ImeUiMode
import io.github.seshuthota.drift.ime.editor.DriftEditorInfo
import io.github.seshuthota.drift.ime.keyboard3.ImeController
import io.github.seshuthota.drift.ime.keyboard3.isFullscreenInputRequired
import io.github.seshuthota.drift.ime.keyboard3.ImeEditor
import io.github.seshuthota.drift.ime.landscapeinput.ExtractedInputRootView
import io.github.seshuthota.drift.ime.landscapeinput.LandscapeInputUiMode
import io.github.seshuthota.drift.ime.lifecycle.LifecycleInputMethodService
import io.github.seshuthota.drift.ime.nlp.NlpInlineAutofill
import io.github.seshuthota.drift.ime.smartbar.createInlineSuggestionUiStyleBundle
import io.github.seshuthota.drift.ime.theme.WallpaperChangeReceiver
import io.github.seshuthota.drift.ime.window.ImeRootView
import io.github.seshuthota.drift.ime.window.ImeWindowController
import io.github.seshuthota.drift.lib.devtools.LogTopic
import io.github.seshuthota.drift.lib.devtools.flogDebug
import io.github.seshuthota.drift.lib.devtools.flogError
import io.github.seshuthota.drift.lib.devtools.flogInfo
import io.github.seshuthota.drift.lib.devtools.flogWarning
import io.github.seshuthota.drift.lib.util.InputMethodUtils
import io.github.seshuthota.drift.lib.util.debugSummarize
import io.github.seshuthota.drift.lib.util.launchActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import io.github.seshuthota.drift.lib.android.AndroidInternalR
import io.github.seshuthota.drift.lib.android.AndroidVersion
import io.github.seshuthota.drift.lib.android.systemServiceOrNull
import io.github.seshuthota.drift.lib.kotlin.collectIn
import io.github.seshuthota.drift.lib.kotlin.collectLatestIn
import org.k3lp.runtime.K3TextRange
import java.lang.ref.WeakReference

private var DriftImeServiceReference = WeakReference<DriftImeService?>(null)

class DriftImeService : LifecycleInputMethodService() {
    companion object {
        private val InlineSuggestionUiSmallestSize = Size(0, 0)
        private val InlineSuggestionUiBiggestSize = Size(Int.MAX_VALUE, Int.MAX_VALUE)

        fun currentInputConnection(): InputConnection? {
            return DriftImeServiceReference.get()?.currentInputConnection
        }

        /**
         * Hides the IME and launches [DriftAppActivity].
         */
        fun launchSettings() {
            val ims = DriftImeServiceReference.get() ?: return
            ims.requestHideSelf(0)
            ims.launchActivity(DriftAppActivity::class) {
                it.flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }

        fun showUi() {
            val ims = DriftImeServiceReference.get() ?: return
            ims.showUi()
        }

        fun hideUi() {
            val ims = DriftImeServiceReference.get() ?: return
            ims.hideUi()
        }

        fun switchToPrevInputMethod(): Boolean {
            val ims = DriftImeServiceReference.get() ?: return false
            return ims.switchToPrevInputMethod()
        }

        fun switchToNextInputMethod(): Boolean {
            val ims = DriftImeServiceReference.get() ?: return false
            return ims.switchToNextInputMethod()
        }

        fun switchToVoiceInputMethod(): Boolean {
            val ims = DriftImeServiceReference.get() ?: return false
            return ims.switchToVoiceInputMethod()
        }

        fun showImePicker(): Boolean {
            val ims = DriftImeServiceReference.get() ?: return false
            return InputMethodUtils.showImePicker(ims)
        }

        fun windowControllerOrNull(): ImeWindowController? {
            val ims = DriftImeServiceReference.get() ?: return null
            return ims.windowController
        }

        fun imeControllerOrNull(): ImeController? {
            val ims = DriftImeServiceReference.get() ?: return null
            return ims.imeController
        }
    }

    fun hideUi() {
        requestHideSelf(0)
    }

    fun showUi() {
        if (AndroidVersion.ATLEAST_API28_P) {
            requestShowSelf(0)
        } else {
            @Suppress("DEPRECATION")
            systemServiceOrNull(InputMethodManager::class)
                ?.showSoftInputFromInputMethod(currentInputBinding.connectionToken, 0)
        }
    }


    fun switchToPrevInputMethod(): Boolean {
        val imm = systemServiceOrNull(InputMethodManager::class)
        try {
            if (AndroidVersion.ATLEAST_API28_P) {
                return switchToPreviousInputMethod()
            } else {
                window.window?.let { window ->
                    @Suppress("DEPRECATION")
                    return imm?.switchToLastInputMethod(window.attributes.token) == true
                }
            }
        } catch (e: Exception) {
            flogError { "Unable to switch to the previous IME" }
            imm?.showInputMethodPicker()
        }
        return false
    }

    fun switchToNextInputMethod(): Boolean {
        val imm = systemServiceOrNull(InputMethodManager::class)
        try {
            if (AndroidVersion.ATLEAST_API28_P) {
                return switchToNextInputMethod(false)
            } else {
                window.window?.let { window ->
                    @Suppress("DEPRECATION")
                    return imm?.switchToNextInputMethod(window.attributes.token, false) == true
                }
            }
        } catch (e: Exception) {
            flogError { "Unable to switch to the next IME" }
            imm?.showInputMethodPicker()
        }
        return false
    }

    fun switchToVoiceInputMethod(): Boolean {
        val imm = systemServiceOrNull(InputMethodManager::class) ?: return false
        val list: List<InputMethodInfo> = imm.enabledInputMethodList
        for (el in list) {
            for (i in 0 until el.subtypeCount) {
                // We need to hardcode 'voice' here because the SUBTYPE_MODE_VOICE constant is private.
                // https://cs.android.com/android/platform/superproject/+/android-latest-release:frameworks/base/core/java/android/view/inputmethod/InputMethodManager.java;drc=2b278ab3ac73bb5596327aac1298df85cd94e454;l=309
                if (el.getSubtypeAt(i).mode != "voice") continue
                if (AndroidVersion.ATLEAST_API28_P) {
                    switchInputMethod(el.id, el.getSubtypeAt(i))
                    return true
                } else {
                    window.window?.let { window ->
                        @Suppress("DEPRECATION")
                        imm.setInputMethod(window.attributes.token, el.id)
                        return true
                    }
                }
            }
        }
        return false
    }

    private val prefs by DriftPreferenceStore
    private val appContext = inferDriftApplication()
    val storageController = appContext.storageController
    val imeController = appContext.imeController
    val themeController = appContext.themeController

    private val nlpManager by nlpManager()
    private val subtypeManager by subtypeManager()

    val windowController = ImeWindowController(prefs, lifecycleScope)

    private val systemLocalesFlow = MutableStateFlow(LocaleList())
    var resourcesContext by mutableStateOf(this as Context)
        private set

    private val wallpaperChangeReceiver = WallpaperChangeReceiver()

    init {
        setTheme(R.style.DriftImeTheme)
    }

    override fun onCreate() {
        super.onCreate()
        DriftImeServiceReference = WeakReference(this)
        systemLocalesFlow.value = resources.configuration.locales

        WindowCompat.setDecorFitsSystemWindows(window.window!!, false)
        windowController.onConfigurationChanged(resources.configuration)
        windowController.activeWindowConfig.collectLatestIn(lifecycleScope) {
        }

        combine(
            systemLocalesFlow,
            subtypeManager.activeSubtypeFlow,
            prefs.localization.displayKeyboardLabelsInSubtypeLanguage.asFlow(),
        ) { systemLocales, subtype, shouldUseSubtypeLanguage ->
            systemLocales to (if (shouldUseSubtypeLanguage) subtype.primaryLocale else null)
        }.collectIn(lifecycleScope) { (systemLocales, subtypeLocale) ->
            val config = Configuration().apply {
                setToDefaults()
                if (subtypeLocale != null) {
                    setLocale(subtypeLocale.base)
                } else {
                    setLocales(systemLocales)
                }
            }
            resourcesContext = createConfigurationContext(config)
        }

        prefs.physicalKeyboard.showOnScreenKeyboard.asFlow().collectIn(lifecycleScope) {
            updateInputViewShown()
        }

        @Suppress("DEPRECATION") // We do not retrieve the wallpaper but only listen to changes
        registerReceiver(wallpaperChangeReceiver, IntentFilter(Intent.ACTION_WALLPAPER_CHANGED))
    }

    override fun onCreateInputView(): View? {
        super.installViewTreeOwners()
        val content = window.window!!.findViewById<ViewGroup>(android.R.id.content)
        content.addView(ImeRootView(this))
        return null
    }

    override fun onCreateCandidatesView(): View? {
        return null
    }

    override fun onCreateExtractTextView(): View {
        super.installViewTreeOwners()
        val defaultExtractView = super.onCreateExtractTextView()
        if (defaultExtractView == null || defaultExtractView !is ViewGroup) {
            return ExtractedInputRootView(this, null)
        }
        val extractEditText = defaultExtractView.findViewById<ExtractEditText>(android.R.id.inputExtractEditText)
        (extractEditText?.parent as? ViewGroup)?.removeView(extractEditText)
        defaultExtractView.let {
            it.removeAllViews()
            it.addView(ExtractedInputRootView(this, extractEditText))
        }
        return defaultExtractView
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        systemLocalesFlow.value = newConfig.locales
        windowController.onConfigurationChanged(newConfig)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(wallpaperChangeReceiver)
        DriftImeServiceReference = WeakReference(null)
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        flogInfo { "info=${info?.debugSummarize()} restarting=$restarting" }
        super.onStartInput(info, restarting)
        if (info == null) return
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        flogInfo { "info=${info?.debugSummarize()} restarting=$restarting" }
        super.onStartInputView(info, restarting)
        if (info == null) return
        DriftApplication.getHack().asrController.value.cancel()
        val ic = WeakReference(currentInputConnection)
        val editorInfo = DriftEditorInfo.wrap(info)
        imeController.updateStateBlocking {
            handleStartInputView(ic, editorInfo)
        }
    }

    override fun onEvaluateInputViewShown(): Boolean {
        val config = resources.configuration
        return super.onEvaluateInputViewShown()
            || config.keyboard == Configuration.KEYBOARD_NOKEYS
            || prefs.physicalKeyboard.showOnScreenKeyboard.get()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candStart: Int, candEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candStart, candEnd)
        val newSelection = K3TextRange(start = newSelStart, end = newSelEnd)
        flogInfo { "new=$newSelection" }
        imeController.updateStateBlocking {
            handleUpdateSelection(newSelection)
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        flogInfo { "finishing=$finishingInput" }
        super.onFinishInputView(finishingInput)
        DriftApplication.getHack().asrController.value.cancel()
        imeController.updateStateBlocking {
            handleFinishInputView()
        }
    }

    override fun onFinishInput() {
        flogInfo { "(no args)" }
        super.onFinishInput()
        NlpInlineAutofill.clearInlineSuggestions()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        if (windowController.onWindowShown()) {
            flogInfo(LogTopic.IMS_EVENTS)
        } else {
            flogWarning(LogTopic.IMS_EVENTS) { "Ignoring (is already shown)" }
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        if (windowController.onWindowHidden()) {
            flogInfo(LogTopic.IMS_EVENTS)
            imeController.updateStateBlocking {
                state = state.copy(
                    flags = state.flags
                        .withImeUiMode(ImeUiMode.TEXT)
                        .withActionsEditorVisible(false)
                        .withActionsOverflowVisible(false),
                )
            }
        } else {
            flogWarning(LogTopic.IMS_EVENTS) { "Ignoring (is already hidden)" }
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        val config = resources.configuration
        if (config.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            return false
        }
        return when (prefs.keyboard.landscapeInputUiMode.get()) {
            LandscapeInputUiMode.DYNAMICALLY_SHOW -> super.onEvaluateFullscreenMode()
            LandscapeInputUiMode.NEVER_SHOW -> false
            LandscapeInputUiMode.ALWAYS_SHOW -> true
        }
    }

    override fun onUpdateExtractingVisibility(info: EditorInfo?) {
        if (info != null) {
            val ic = WeakReference(currentInputConnection)
            val editorInfo = DriftEditorInfo.wrap(info)
            imeController.updateStateBlocking {
                handleStartInputView(ic, editorInfo)
            }
        }
        when (prefs.keyboard.landscapeInputUiMode.get()) {
            LandscapeInputUiMode.DYNAMICALLY_SHOW -> super.onUpdateExtractingVisibility(info)
            LandscapeInputUiMode.NEVER_SHOW -> isExtractViewShown = false
            LandscapeInputUiMode.ALWAYS_SHOW -> isExtractViewShown = true
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        if (!prefs.smartbar.enabled.get() || !prefs.suggestion.api30InlineSuggestionsEnabled.get()) {
            flogInfo(LogTopic.IMS_EVENTS) {
                "Ignoring inline suggestions request because Smartbar and/or inline suggestions are disabled."
            }
            return null
        }

        flogInfo(LogTopic.IMS_EVENTS) { "Creating inline suggestions request" }
        val stylesBundle = createInlineSuggestionUiStyleBundle(this)
        if (stylesBundle == null) {
            flogWarning(LogTopic.IMS_EVENTS) { "Failed to retrieve inline suggestions style bundle" }
            return null
        }
        val spec = InlinePresentationSpec.Builder(
            InlineSuggestionUiSmallestSize,
            InlineSuggestionUiBiggestSize,
        ).run {
            setStyle(stylesBundle)
            build()
        }

        return InlineSuggestionsRequest.Builder(listOf(spec)).run {
            setMaxSuggestionCount(InlineSuggestionsRequest.SUGGESTION_COUNT_UNLIMITED)
            build()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        val inlineSuggestions = response.inlineSuggestions
        flogInfo(LogTopic.IMS_EVENTS) {
            "Received inline suggestions response with ${inlineSuggestions.size} suggestion(s) provided."
        }
        return NlpInlineAutofill.showInlineSuggestions(this, inlineSuggestions)
    }

    override fun onComputeInsets(outInsets: Insets?) {
        if (outInsets == null) return
        val imeState = imeController.snapshotState()
        windowController.onComputeInsets(outInsets, imeState.isFullscreenInputRequired())
    }

    override fun getTextForImeAction(imeOptions: Int): String? {
        return try {
            when (imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_NONE -> null
                EditorInfo.IME_ACTION_GO -> resourcesContext.getString(AndroidInternalR.string.ime_action_go)
                EditorInfo.IME_ACTION_SEARCH -> resourcesContext.getString(AndroidInternalR.string.ime_action_search)
                EditorInfo.IME_ACTION_SEND -> resourcesContext.getString(AndroidInternalR.string.ime_action_send)
                EditorInfo.IME_ACTION_NEXT -> resourcesContext.getString(AndroidInternalR.string.ime_action_next)
                EditorInfo.IME_ACTION_DONE -> resourcesContext.getString(AndroidInternalR.string.ime_action_done)
                EditorInfo.IME_ACTION_PREVIOUS -> resourcesContext.getString(AndroidInternalR.string.ime_action_previous)
                else -> resourcesContext.getString(AndroidInternalR.string.ime_action_default)
            }
        } catch (_: Throwable) {
            super.getTextForImeAction(imeOptions)?.toString()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        flogDebug { "onKeyDown(keyCode=$keyCode, event=$event)" }
        return imeController.onHardwareKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        flogDebug { "onKeyUp(keyCode=$keyCode, event=$event)" }
        return imeController.onHardwareKeyUp(keyCode, event) || super.onKeyUp(keyCode, event)
    }
}
