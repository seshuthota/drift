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

package io.github.seshuthota.drift.app.settings.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.jetpref.datastore.model.collectAsState
import io.github.seshuthota.drift.R
import io.github.seshuthota.drift.app.DriftPreferenceStore
import io.github.seshuthota.drift.app.components.DriftScreenNg
import io.github.seshuthota.drift.app.components.ScrollableScreenColumn
import io.github.seshuthota.drift.asrModelManager
import io.github.seshuthota.drift.ime.asr.AsrModelSpec
import io.github.seshuthota.drift.ime.asr.hasRecordAudioPermission
import io.github.seshuthota.drift.lib.compose.stringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Model management for local voice input: permission, download/delete/select models, and whether the engine
 * stays in memory. Downloading is the only network use of the whole feature.
 */
@Composable
fun VoiceInputScreen() {
    val context = LocalContext.current
    val modelManager = context.asrModelManager().value
    val prefs by DriftPreferenceStore
    val scope = rememberCoroutineScope()

    var permissionGranted by remember { mutableStateOf(context.hasRecordAudioPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> permissionGranted = granted }

    var installedIds by remember { mutableStateOf(modelManager.installedModels().map { it.spec.id }.toSet()) }
    val storedModelId by prefs.voice.modelId.collectAsState()
    // A stored id of a removed model falls back to the first one, as AsrController does.
    val selectedId = modelManager.spec(storedModelId)?.id ?: modelManager.models().firstOrNull()?.id
    val keepLoaded by prefs.voice.keepModelLoaded.collectAsState()
    val plainMic by prefs.voice.plainMicSource.collectAsState()
    var downloadingId by remember { mutableStateOf<String?>(null) }
    val progress = remember { MutableStateFlow(0f) }
    val progressValue by progress.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }

    fun refreshInstalled() {
        installedIds = modelManager.installedModels().map { it.spec.id }.toSet()
    }

    fun download(spec: AsrModelSpec) {
        if (downloadingId != null) return
        downloadingId = spec.id
        error = null
        progress.value = 0f
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                modelManager.install(spec) { value -> progress.value = value }
            }
            downloadingId = null
            result.onSuccess {
                prefs.voice.modelId.set(spec.id)
                refreshInstalled()
            }.onFailure { failure ->
                error = failure.message ?: failure.javaClass.simpleName
            }
        }
    }

    fun delete(spec: AsrModelSpec) {
        scope.launch {
            withContext(Dispatchers.IO) { modelManager.delete(spec) }
            refreshInstalled()
        }
    }

    fun select(spec: AsrModelSpec) {
        scope.launch { prefs.voice.modelId.set(spec.id) }
    }

    DriftScreenNg(
        title = stringRes(R.string.settings__voice__title),
        previewFieldVisible = false,
    ) { padding ->
        ScrollableScreenColumn(padding) {
            Text(
                text = stringRes(R.string.settings__voice__microphone),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringRes(
                        if (permissionGranted) {
                            R.string.settings__voice__permission_granted
                        } else {
                            R.string.settings__voice__permission_denied
                        },
                    ),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!permissionGranted) {
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                        Text(stringRes(R.string.settings__voice__allow_microphone))
                    }
                }
            }
            if (!permissionGranted) {
                TextButton(onClick = { openAppSettings(context) }) {
                    Text(stringRes(R.string.settings__voice__open_app_settings))
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Text(
                text = stringRes(R.string.settings__voice__models),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            for (spec in modelManager.models()) {
                ModelRow(
                    spec = spec,
                    installed = spec.id in installedIds,
                    selected = spec.id == selectedId,
                    downloading = downloadingId == spec.id,
                    progress = progressValue,
                    onSelect = { select(spec) },
                    onDownload = { download(spec) },
                    onDelete = { delete(spec) },
                )
            }

            error?.let { reason ->
                Text(
                    text = stringRes(R.string.settings__voice__download_failed, "reason" to reason),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringRes(R.string.settings__voice__keep_loaded),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringRes(R.string.settings__voice__keep_loaded_summary),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = keepLoaded,
                    onCheckedChange = { value -> scope.launch { prefs.voice.keepModelLoaded.set(value) } },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringRes(R.string.settings__voice__plain_mic),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringRes(R.string.settings__voice__plain_mic_summary),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = plainMic,
                    onCheckedChange = { value -> scope.launch { prefs.voice.plainMicSource.set(value) } },
                )
            }
        }
    }
}

@Composable
private fun ModelRow(
    spec: AsrModelSpec,
    installed: Boolean,
    selected: Boolean,
    downloading: Boolean,
    progress: Float,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RadioButton(selected = selected, onClick = onSelect, enabled = installed)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = spec.displayName, style = MaterialTheme.typography.bodyLarge)
                val summary = buildString {
                    append(spec.languages)
                    append(" · ")
                    append(formatBytes(spec.totalBytes))
                    if (installed) {
                        append(" · ")
                        append(stringRes(R.string.settings__voice__installed))
                    }
                }
                Text(text = summary, style = MaterialTheme.typography.bodySmall)
            }
            when {
                downloading -> Text(
                    text = stringRes(R.string.settings__voice__downloading),
                    style = MaterialTheme.typography.labelMedium,
                )
                installed -> TextButton(onClick = onDelete) {
                    Text(stringRes(R.string.settings__voice__delete))
                }
                else -> Button(onClick = onDownload) {
                    Text(stringRes(R.string.settings__voice__download))
                }
            }
        }
        if (downloading) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return if (bytes >= 1_000_000L) {
        "%.1f MB".format(bytes / 1_000_000.0)
    } else {
        "${bytes / 1_000} KB"
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
