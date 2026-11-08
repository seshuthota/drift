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

package io.github.seshuthota.drift.ime.landscapeinput

import android.annotation.SuppressLint
import android.inputmethodservice.ExtractEditText
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.seshuthota.drift.DriftImeService
import io.github.seshuthota.drift.R
import io.github.seshuthota.drift.app.DriftPreferenceStore
import io.github.seshuthota.drift.ime.io.LocalStorageController
import io.github.seshuthota.drift.ime.keyboard3.LocalImeController
import io.github.seshuthota.drift.ime.keyboard3.interaction.LocalInteractionController
import io.github.seshuthota.drift.ime.keyboard3.interaction.rememberAndroidInteractionController
import io.github.seshuthota.drift.ime.theme.DriftImeTheme
import io.github.seshuthota.drift.ime.theme.DriftImeUi
import io.github.seshuthota.drift.ime.theme.LocalThemeController
import io.github.seshuthota.drift.ime.window.LocalWindowController
import io.github.seshuthota.drift.lib.devtools.flogError
import io.github.seshuthota.drift.lib.compose.ProvideLocalizedResources
import io.github.seshuthota.drift.lib.snygg.ui.SnyggBox
import io.github.seshuthota.drift.lib.snygg.ui.SnyggButton
import io.github.seshuthota.drift.lib.snygg.ui.SnyggRow
import io.github.seshuthota.drift.lib.snygg.ui.SnyggText
import io.github.seshuthota.drift.lib.snygg.ui.rememberSnyggThemeQuery

@SuppressLint("ViewConstructor")
class ExtractedInputRootView(val ims: DriftImeService, eet: ExtractEditText?) : FrameLayout(ims) {
    val composeView: ComposeView
    val extractEditText: ExtractEditText

    init {
        isHapticFeedbackEnabled = true
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

        extractEditText = (eet ?: ExtractEditText(context)).also {
            it.id = android.R.id.inputExtractEditText
            it.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            it.background = null
            it.gravity = Gravity.TOP
            it.isVerticalScrollBarEnabled = true
        }
        addView(extractEditText)

        composeView = ComposeView(context).also { it.setContent { Content() } }
        addView(composeView)
    }

    @Composable
    fun Content() {
        val prefs by DriftPreferenceStore
        val interactionController = rememberAndroidInteractionController(prefs)

        CompositionLocalProvider(
            LocalImeController provides ims.imeController,
            LocalInteractionController provides interactionController,
            LocalStorageController provides ims.storageController,
            LocalThemeController provides ims.themeController,
            LocalWindowController provides ims.windowController,
        ) {
            ProvideLocalizedResources(
                ims.resourcesContext,
                appName = R.string.app_name,
                forceLayoutDirection = LayoutDirection.Ltr,
            ) {
                DriftImeTheme {
                    val imeState by ims.imeController.activeState.collectAsState()
                    val editorInfo by remember { derivedStateOf { imeState.editor.info } }
                    val rootInsets by ims.windowController.activeRootInsets.collectAsState()
                    val windowInsets by ims.windowController.activeWindowInsets.collectAsState()
                    val height by remember {
                        derivedStateOf {
                            val rootBounds = rootInsets.boundsDp
                            val windowBounds = windowInsets?.boundsDp ?: return@derivedStateOf 0.dp
                            rootBounds.height - windowBounds.height
                        }
                    }
                    SnyggBox(DriftImeUi.ExtractedLandscapeInputLayout.elementName) {
                        SnyggRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(height),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SnyggBox(
                                elementName = DriftImeUi.ExtractedLandscapeInputLayout.elementName,
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .weight(1f),
                            ) {
                                val fieldStyle =
                                    rememberSnyggThemeQuery(DriftImeUi.ExtractedLandscapeInputField.elementName)
                                val foreground = fieldStyle.foreground()
                                AndroidView(
                                    factory = { extractEditText },
                                    update = { view ->
                                        view.background = null
                                        view.backgroundTintList = null
                                        view.foregroundTintList = null
                                        view.setTextColor(foreground.toArgb())
                                        view.setHintTextColor(foreground.copy(foreground.alpha * 0.6f).toArgb())
                                        view.setTextSize(
                                            TypedValue.COMPLEX_UNIT_SP,
                                            fieldStyle.fontSize(default = 16.sp).value,
                                        )
                                    },
                                )
                            }
                            SnyggButton(
                                DriftImeUi.ExtractedLandscapeInputAction.elementName,
                                onClick = {
                                    ims.imeController.updateStateBlocking {
                                        if (editorInfo.extractedActionId != 0) {
                                            state.editor.ic.get()?.performEditorAction(editorInfo.extractedActionId)
                                        } else {
                                            state.editor.performEditorAction(editorInfo.imeOptions.action)
                                        }
                                    }
                                },
                                modifier = Modifier.padding(horizontal = 8.dp),
                            ) {
                                SnyggText(
                                    text = editorInfo.extractedActionLabel
                                        ?: ims.getTextForImeAction(editorInfo.imeOptions.action.toInt())
                                        ?: "ACTION",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun getAccessibilityClassName(): CharSequence {
        return javaClass.name
    }

    override fun onAttachedToWindow() {
        removeView(extractEditText)
        super.onAttachedToWindow()
        try {
            (parent as LinearLayout).let { extractEditLayout ->
                extractEditLayout.layoutParams = LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ).also { it.setMargins(0, 0, 0, 0) }
                extractEditLayout.setPadding(0, 0, 0, 0)
            }
        } catch (e: Throwable) {
            flogError { e.message.toString() }
        }
    }
}
