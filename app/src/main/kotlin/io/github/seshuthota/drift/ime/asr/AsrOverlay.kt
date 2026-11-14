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

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.seshuthota.drift.DriftImeService
import io.github.seshuthota.drift.R
import io.github.seshuthota.drift.asrController
import io.github.seshuthota.drift.ime.theme.DriftImeUi
import io.github.seshuthota.drift.ime.theme.ImeUiAttrs
import io.github.seshuthota.drift.lib.compose.stringRes
import io.github.seshuthota.drift.lib.snygg.ui.rememberSnyggThemeQuery
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** True while voice input owns the keyboard area: recording, transcribing or showing an error. */
@Composable
fun isAsrActive(): Boolean {
    val controller by LocalContext.current.asrController()
    val state by controller.state.collectAsState()
    return state != AsrState.Idle
}

/**
 * Colours of the voice panel, taken from the keyboard theme so the panel looks like the keys it replaces: plain
 * text and the idle button follow a normal key, the recording button follows the enter key (the theme's accent) and
 * secondary text follows the space bar's label.
 */
private data class VoicePanelColors(
    val text: Color,
    val secondaryText: Color,
    val idleButton: Color,
    val idleIcon: Color,
    val activeButton: Color,
    val activeIcon: Color,
)

@Composable
private fun rememberVoicePanelColors(): VoicePanelColors {
    val key = rememberSnyggThemeQuery(DriftImeUi.Key.elementName)
    val enter = rememberSnyggThemeQuery(
        DriftImeUi.Key.elementName,
        attributes = mapOf(ImeUiAttrs.Output.name to ENTER_OUTPUT),
    )
    val space = rememberSnyggThemeQuery(
        DriftImeUi.Key.elementName,
        attributes = mapOf(ImeUiAttrs.Output.name to " "),
    )
    val fallback = MaterialTheme.colorScheme
    val text = key.foreground(fallback.onSurface)
    val keyBackground = key.background(fallback.surfaceVariant)
    return VoicePanelColors(
        text = text,
        secondaryText = space.foreground(text.copy(alpha = 0.7f)),
        idleButton = keyBackground,
        idleIcon = text,
        activeButton = enter.background(fallback.primary),
        activeIcon = enter.foreground(text),
    )
}

/** Theme attribute value of the enter key's output, which themes use to give it the accent colour. */
private const val ENTER_OUTPUT = "@k3:action/enter"

/**
 * Full-height voice panel that replaces the keyboard while local voice input is active. One big button in the
 * centre reflects the state (active while dictating, muted while the last audio is finished) and ends dictation
 * when tapped; the close button in the top-right cancels and returns to the keyboard.
 */
@Composable
fun AsrPanel(height: Dp) {
    val context = LocalContext.current
    val controller by context.asrController()
    val state by controller.state.collectAsState()
    val amplitude by controller.amplitude.collectAsState()
    val colors = rememberVoicePanelColors()

    // No background of its own: the keyboard window's themed background shows through, as behind the keys.
    Box(modifier = Modifier.fillMaxWidth().height(height)) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val recording = state == AsrState.Recording
                val transcribing = state == AsrState.Transcribing
                if (state is AsrState.Error) {
                    val reason = (state as AsrState.Error).reason
                    Text(
                        text = errorMessage(reason),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.text,
                        textAlign = TextAlign.Center,
                    )
                    if (reason != AsrError.MicrophoneBusy && reason !is AsrError.Failure) {
                        TextButton(
                            colors = ButtonDefaults.textButtonColors(contentColor = colors.activeButton),
                            onClick = {
                            controller.dismissError()
                                DriftImeService.launchSettings()
                            },
                        ) {
                            Text(stringRes(R.string.voice_input__open_settings))
                        }
                    }
                } else {
                    VoiceButton(
                        colors = colors,
                        recording = recording,
                        transcribing = transcribing,
                        amplitude = amplitude,
                        onClick = { controller.stopAndTranscribe() },
                    )
                    Text(
                        text = stringRes(
                            if (recording) R.string.voice_input__tap_to_finish else R.string.voice_input__transcribing,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.text,
                    )
                    if (recording) ElapsedTime(colors.secondaryText)
                }
            }
            IconButton(
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                onClick = {
                    if (state is AsrState.Error) controller.dismissError() else controller.cancel()
                },
            ) {
                Icon(Icons.Filled.Close, contentDescription = stringRes(R.string.voice_input__cancel), tint = colors.text)
            }
        }
    }
}

@Composable
private fun VoiceButton(
    colors: VoicePanelColors,
    recording: Boolean,
    transcribing: Boolean,
    amplitude: Float,
    onClick: () -> Unit,
) {
    val buttonColor by animateColorAsState(if (recording) colors.activeButton else colors.idleButton, label = "voice_color")
    val iconColor by animateColorAsState(if (recording) colors.activeIcon else colors.idleIcon, label = "voice_icon")
    val level by animateFloatAsState(if (recording) amplitude.coerceIn(0f, 1f) else 0f, label = "voice_level")
    val haloColor = colors.activeButton

    Box(
        modifier = Modifier
            .size(160.dp)
            .drawBehind {
                val base = 44.dp.toPx()
                val radius = base + level * (size.minDimension / 2f - base)
                drawCircle(color = haloColor.copy(alpha = 0.25f), radius = radius)
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(buttonColor)
                .clickable(enabled = recording, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (transcribing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    color = colors.activeButton,
                    strokeWidth = 3.dp,
                )
            } else if (recording) {
                Icon(Icons.Filled.Stop, contentDescription = stringRes(R.string.voice_input__stop), tint = iconColor, modifier = Modifier.size(40.dp))
            } else {
                Icon(Icons.Filled.Mic, contentDescription = stringRes(R.string.voice_input__stop), tint = iconColor, modifier = Modifier.size(40.dp))
            }
        }
    }
}

@Composable
private fun ElapsedTime(color: Color) {
    val startedAt = remember { SystemClock.elapsedRealtime() }
    var now by remember { mutableLongStateOf(startedAt) }
    LaunchedEffect(Unit) {
        while (isActive) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val seconds = (now - startedAt) / 1_000
    Text(
        text = "%d:%02d".format(seconds / 60, seconds % 60),
        style = MaterialTheme.typography.labelLarge,
        color = color,
    )
}

@Composable
private fun errorMessage(reason: AsrError): String = when (reason) {
    AsrError.MissingPermission -> stringRes(R.string.voice_input__missing_permission)
    AsrError.MissingModel -> stringRes(R.string.voice_input__missing_model)
    AsrError.MicrophoneBusy -> stringRes(R.string.voice_input__microphone_busy)
    is AsrError.Failure -> reason.message
}
