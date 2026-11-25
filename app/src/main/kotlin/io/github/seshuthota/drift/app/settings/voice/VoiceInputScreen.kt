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
import androidx.compose.runtime.LaunchedEffect
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
import io.github.seshuthota.drift.ime.asr.AsrModelRegistry
import io.github.seshuthota.drift.ime.asr.AsrModelSpec
import io.github.seshuthota.drift.ime.asr.VoiceRefineMode
import io.github.seshuthota.drift.ime.asr.hasRecordAudioPermission
import io.github.seshuthota.drift.lib.compose.stringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val finisherId by prefs.voice.finisherModelId.collectAsState()
    val liveModels = modelManager.models().filter { it.streamsLive }
    val selectedId = liveModels.firstOrNull { it.id == storedModelId }?.id
        ?: liveModels.firstOrNull { it.id in installedIds }?.id
        ?: liveModels.firstOrNull()?.id
    val keepLoaded by prefs.voice.keepModelLoaded.collectAsState()
    val plainMic by prefs.voice.plainMicSource.collectAsState()
    val refineMode by prefs.voice.refineMode.collectAsState()
    var downloadingId by remember { mutableStateOf<String?>(null) }
    val progress = remember { MutableStateFlow(0f) }
    val progressValue by progress.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }

    fun refreshInstalled() {
        installedIds = modelManager.installedModels().map { it.spec.id }.toSet()
    }

    // A one-pass model selected before finishers existed becomes the finisher, not the live model.
    LaunchedEffect(storedModelId, installedIds) {
        val stored = modelManager.spec(storedModelId) ?: return@LaunchedEffect
        if (stored.streamsLive) return@LaunchedEffect
        if (prefs.voice.finisherModelId.get().isEmpty()) prefs.voice.finisherModelId.set(stored.id)
        val live = modelManager.models().firstOrNull { it.streamsLive && it.id in installedIds }
        prefs.voice.modelId.set(live?.id ?: AsrModelRegistry.DEFAULT_MODEL_ID)
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
                if (spec.streamsLive) {
                    prefs.voice.modelId.set(spec.id)
                } else {
                    prefs.voice.finisherModelId.set(spec.id)
                }
                refreshInstalled()
            }.onFailure { failure ->
                error = failure.message ?: failure.javaClass.simpleName
            }
        }
    }

    fun delete(spec: AsrModelSpec) {
        scope.launch {
            withContext(Dispatchers.IO) { modelManager.delete(spec) }
            if (prefs.voice.finisherModelId.get() == spec.id) prefs.voice.finisherModelId.set("")
            if (prefs.voice.modelId.get() == spec.id) {
                val live = modelManager.models().firstOrNull { it.streamsLive && modelManager.installed(it) != null }
                prefs.voice.modelId.set(live?.id ?: AsrModelRegistry.DEFAULT_MODEL_ID)
            }
            refreshInstalled()
        }
    }

    fun select(spec: AsrModelSpec) {
        if (!spec.streamsLive) return
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

            val liveName = liveModels.firstOrNull { it.id == selectedId }?.shortName().orEmpty()
            val finisher = modelManager.models().firstOrNull { !it.streamsLive && it.id == finisherId && it.id in installedIds }
            Text(
                text = when (refineMode) {
                    VoiceRefineMode.OFF -> stringRes(R.string.settings__voice__status_off, "live" to liveName)
                    VoiceRefineMode.LOG_ONLY -> stringRes(
                        R.string.settings__voice__status_log_only,
                        "live" to liveName,
                        "finisher" to (finisher?.shortName() ?: liveName),
                    )
                    VoiceRefineMode.REPLACE -> stringRes(
                        R.string.settings__voice__status_replace,
                        "live" to liveName,
                        "finisher" to (finisher?.shortName() ?: liveName),
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            Text(
                text = stringRes(R.string.settings__voice__while_speaking),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringRes(R.string.settings__voice__while_speaking_summary),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            for (spec in liveModels) {
                ModelRow(
                    spec = spec,
                    installed = spec.id in installedIds,
                    selectable = spec.id in installedIds,
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

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            Text(
                text = stringRes(R.string.settings__voice__after_stop),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringRes(R.string.settings__voice__refine_summary),
                style = MaterialTheme.typography.bodySmall,
            )
            for ((mode, label) in listOf(
                VoiceRefineMode.OFF to R.string.settings__voice__refine_off,
                VoiceRefineMode.LOG_ONLY to R.string.settings__voice__refine_log_only,
                VoiceRefineMode.REPLACE to R.string.settings__voice__refine_replace,
            )) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = refineMode == mode,
                        onClick = { scope.launch { prefs.voice.refineMode.set(mode) } },
                    )
                    Text(text = stringRes(label), style = MaterialTheme.typography.bodyMedium)
                }
            }

            val refining = refineMode != VoiceRefineMode.OFF
            Text(
                text = stringRes(R.string.settings__voice__finish_with),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = stringRes(R.string.settings__voice__finish_with_summary),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RadioButton(
                    selected = finisher == null,
                    onClick = { scope.launch { prefs.voice.finisherModelId.set("") } },
                    enabled = refining,
                )
                Text(
                    text = stringRes(R.string.settings__voice__finish_with_live, "live" to liveName),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            for (spec in modelManager.models().filter { !it.streamsLive }) {
                ModelRow(
                    spec = spec,
                    installed = spec.id in installedIds,
                    selectable = refining && spec.id in installedIds,
                    selected = spec.id == finisher?.id,
                    downloading = downloadingId == spec.id,
                    progress = progressValue,
                    onSelect = { scope.launch { prefs.voice.finisherModelId.set(spec.id) } },
                    onDownload = { download(spec) },
                    onDelete = { delete(spec) },
                )
            }
        }
    }
}

@Composable
private fun ModelRow(
    spec: AsrModelSpec,
    installed: Boolean,
    selectable: Boolean,
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
            RadioButton(selected = selected, onClick = onSelect, enabled = selectable)
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

/** The display name without its parenthesised details, for sentences such as the status line. */
private fun AsrModelSpec.shortName(): String = displayName.substringBefore(" (")

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
