/*
 * Copyright (C) 2022-2026 The FlorisBoard Contributors
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

package io.github.seshuthota.drift.app.devtools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import io.github.seshuthota.drift.R
import io.github.seshuthota.drift.app.DriftPreferenceStore
import io.github.seshuthota.drift.clipboardManager
import io.github.seshuthota.drift.ime.keyboard3.interaction.LocalInteractionController
import io.github.seshuthota.drift.ime.keyboard3.interaction.showShortToast
import io.github.seshuthota.drift.app.components.DriftScreenNg
import io.github.seshuthota.drift.lib.devtools.Devtools
import kotlinx.coroutines.launch
import io.github.seshuthota.drift.lib.compose.DriftButton
import io.github.seshuthota.drift.app.components.DriftBottomBar
import io.github.seshuthota.drift.lib.compose.driftHorizontalScroll
import io.github.seshuthota.drift.lib.compose.driftScrollbar
import io.github.seshuthota.drift.lib.compose.stringRes

@Composable
fun ExportDebugLogScreen() {
    val prefs by DriftPreferenceStore
    val context = LocalContext.current
    val clipboardManager by context.clipboardManager()
    val interactionController = LocalInteractionController.current
    val scope = rememberCoroutineScope()

    var debugLog by remember { mutableStateOf<List<String>?>(null) }
    var formattedDebugLog by remember { mutableStateOf<List<String>?>(null) }

    LaunchedEffect(Unit) {
        debugLog = Devtools.generateDebugLog(context, prefs, includeLogcat = true).lines()
        formattedDebugLog = Devtools.generateDebugLogForGithub(context, prefs, includeLogcat = true).lines()
    }

    DriftScreenNg(
        title = stringRes(R.string.devtools__debuglog__title),
        previewFieldVisible = false,
        bottomBar = {
            DriftBottomBar {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    val copyToClipboardMsg = stringRes(R.string.devtools__debuglog__copied_to_clipboard)
                    DriftButton(
                        onClick = {
                            scope.launch {
                                clipboardManager.addNewPlaintext(debugLog!!.joinToString("\n"))
                                interactionController.showShortToast(copyToClipboardMsg)
                            }
                        },
                        modifier = Modifier,
                        text = stringRes(R.string.devtools__debuglog__copy_log),
                        enabled = debugLog != null,
                    )
                    DriftButton(
                        onClick = {
                            scope.launch {
                                clipboardManager.addNewPlaintext(formattedDebugLog!!.joinToString("\n"))
                                interactionController.showShortToast(copyToClipboardMsg)
                            }
                        },
                        text = stringRes(R.string.devtools__debuglog__copy_for_github),
                        enabled = debugLog != null,
                    )
                }
            }
        },
    ) { contentPadding ->
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            val lazyListState = rememberLazyListState()
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .driftScrollbar(lazyListState, isVertical = true)
                    .driftHorizontalScroll(),
                contentPadding = contentPadding,
                state = lazyListState,
            ) {
                val log = debugLog
                if (log == null) {
                    item {
                        Text(stringRes(R.string.devtools__debuglog__loading))
                    }
                } else {
                    items(log) { logLine ->
                        Text(
                            text = logLine,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}
