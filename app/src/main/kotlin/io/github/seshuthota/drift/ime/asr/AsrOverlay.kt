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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.seshuthota.drift.DriftImeService
import io.github.seshuthota.drift.R
import io.github.seshuthota.drift.asrController
import io.github.seshuthota.drift.lib.compose.stringRes
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Recording and transcription panel shown over the keyboard while local voice input is active. It is the only
 * surface of the ASR feature, so the mic action stays a one-liner.
 */
@Composable
fun BoxScope.AsrOverlay() {
    val context = LocalContext.current
    val controller by context.asrController()
    val state by controller.state.collectAsState()
    val amplitude by controller.amplitude.collectAsState()

    val surfaceModifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(horizontal = 12.dp, vertical = 10.dp)
        .fillMaxWidth(0.94f)

    when (val current = state) {
        AsrState.Idle -> Unit
        AsrState.Recording -> {
            var startedAt by remember(current) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
            var now by remember(current) { mutableLongStateOf(startedAt) }
            LaunchedEffect(current) {
                while (isActive) {
                    now = SystemClock.elapsedRealtime()
                    delay(200)
                }
            }
            Panel(modifier = surfaceModifier) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringRes(R.string.voice_input__listening),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        LevelBar(amplitude = amplitude)
                    }
                    Text(
                        text = "${(now - startedAt) / 1_000}s",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    IconButton(onClick = { controller.stopAndTranscribe() }) {
                        Icon(Icons.Filled.Stop, contentDescription = stringRes(R.string.voice_input__stop))
                    }
                    IconButton(onClick = { controller.cancel() }) {
                        Icon(Icons.Filled.Close, contentDescription = stringRes(R.string.voice_input__cancel))
                    }
                }
            }
        }
        AsrState.Transcribing -> Panel(modifier = surfaceModifier) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    text = stringRes(R.string.voice_input__transcribing),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        is AsrState.Error -> Panel(modifier = surfaceModifier) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(text = errorMessage(current.reason), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { controller.dismissError() }) {
                        Text(stringRes(R.string.voice_input__dismiss))
                    }
                    TextButton(onClick = {
                        controller.dismissError()
                        DriftImeService.launchSettings()
                    }) {
                        Text(stringRes(R.string.voice_input__open_settings))
                    }
                }
            }
        }
    }
}

@Composable
private fun Panel(modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        content()
    }
}

@Composable
private fun LevelBar(amplitude: Float) {
    val level = amplitude.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(level)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun errorMessage(reason: AsrError): String = when (reason) {
    AsrError.MissingPermission -> stringRes(R.string.voice_input__missing_permission)
    AsrError.MissingModel -> stringRes(R.string.voice_input__missing_model)
    AsrError.MicrophoneBusy -> stringRes(R.string.voice_input__microphone_busy)
    is AsrError.Failure -> reason.message
}
