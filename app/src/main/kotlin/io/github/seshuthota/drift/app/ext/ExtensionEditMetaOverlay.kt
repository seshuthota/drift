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

package io.github.seshuthota.drift.app.ext

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.seshuthota.drift.R
import io.github.seshuthota.drift.app.components.ScrollableScreenColumn
import io.github.seshuthota.drift.app.components.driftColors
import io.github.seshuthota.drift.ime.extension.ExtensionMaintainer
import io.github.seshuthota.drift.ime.extension.ExtensionMeta
import io.github.seshuthota.drift.ime.extension.ExtensionValidation
import io.github.seshuthota.drift.lib.ValidationResult
import io.github.seshuthota.drift.app.components.DriftScreenNg
import io.github.seshuthota.drift.app.components.Validation
import io.github.seshuthota.drift.lib.rememberValidationResult
import io.github.seshuthota.drift.app.components.DriftBottomBarPrimaryButton
import io.github.seshuthota.drift.app.components.DriftBottomBarSecondaryButton
import io.github.seshuthota.drift.app.components.DriftBottomBar
import io.github.seshuthota.drift.lib.compose.stringRes

@Composable
fun ExtensionEditMetaOverlay(
    meta: ExtensionMeta,
    isCreateExt: Boolean,
    onConfirm: (ExtensionMeta) -> Unit,
    onDismiss: () -> Unit,
) {
    var showValidationErrors by rememberSaveable { mutableStateOf(false) }

    var id by rememberSaveable { mutableStateOf(meta.id) }
    val idValidation = rememberValidationResult(ExtensionValidation.MetaId, id)
    var version by rememberSaveable { mutableStateOf(meta.version) }
    val versionValidation = rememberValidationResult(ExtensionValidation.MetaVersion, version)
    var title by rememberSaveable { mutableStateOf(meta.title) }
    val titleValidation = rememberValidationResult(ExtensionValidation.MetaTitle, title)
    var description by rememberSaveable { mutableStateOf(meta.description ?: "") }
    var maintainers by rememberSaveable { mutableStateOf(meta.maintainers.joinToString("\n")) }
    val maintainersValidation = rememberValidationResult(ExtensionValidation.MetaMaintainers, maintainers)
    var keywords by rememberSaveable { mutableStateOf(meta.keywords?.joinToString("\n") ?: "") }
    var homepage by rememberSaveable { mutableStateOf(meta.homepage ?: "") }
    var issueTracker by rememberSaveable { mutableStateOf(meta.issueTracker ?: "") }
    var license by rememberSaveable { mutableStateOf(meta.license) }
    val licenseValidation = rememberValidationResult(ExtensionValidation.MetaLicense, license)

    fun handleApply() {
        val invalid = idValidation.isInvalid() ||
            versionValidation.isInvalid() ||
            titleValidation.isInvalid() ||
            maintainersValidation.isInvalid() ||
            licenseValidation.isInvalid()
        if (invalid) {
            showValidationErrors = true
        } else {
            val newMeta = ExtensionMeta(
                id = id.trim(),
                version = version.trim(),
                title = title.trim(),
                description = description.trim().takeIf { it.isNotEmpty() },
                maintainers = maintainers
                    .lineSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .map(ExtensionMaintainer::fromOrTakeRaw)
                    .toList(),
                keywords = keywords
                    .lineSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .toList()
                    .ifEmpty { null },
                homepage = homepage.trim().takeIf { it.isNotEmpty() },
                issueTracker = issueTracker.trim().takeIf { it.isNotEmpty() },
                license = license.trim(),
            )
            onConfirm(newMeta)
        }
    }

    BackHandler {
        onDismiss()
    }

    DriftScreenNg(
        title = stringRes(R.string.ext__editor__metadata__title),
        previewFieldVisible = false,
        navigationIcon = Icons.Default.Close,
        navigationIconOnClick = onDismiss,
        bottomBar = {
            DriftBottomBar {
                Spacer(modifier = Modifier.weight(1f))
                DriftBottomBarSecondaryButton(text = stringRes(R.string.action__cancel)) {
                    onDismiss()
                }
                DriftBottomBarPrimaryButton(text = stringRes(R.string.action__apply)) {
                    handleApply()
                }
            }
        },
    ) { contentPadding ->
        ScrollableScreenColumn(contentPadding) {
            EditorSheetTextField(
                enabled = isCreateExt,
                isRequired = true,
                value = id,
                onValueChange = { id = it },
                label = stringRes(R.string.ext__meta__id),
                showValidationErrors = showValidationErrors,
                validationResult = idValidation,
            )
            EditorSheetTextField(
                isRequired = true,
                value = version,
                onValueChange = { version = it },
                label = stringRes(R.string.ext__meta__version),
                showValidationErrors = showValidationErrors,
                validationResult = versionValidation,
            )
            EditorSheetTextField(
                isRequired = true,
                value = title,
                onValueChange = { title = it },
                label = stringRes(R.string.ext__meta__title),
                showValidationErrors = showValidationErrors,
                validationResult = titleValidation,
            )
            EditorSheetTextField(
                value = description,
                onValueChange = { description = it },
                label = stringRes(R.string.ext__meta__description),
            )
            EditorSheetTextField(
                isRequired = true,
                value = maintainers,
                onValueChange = { maintainers = it },
                label = stringRes(R.string.ext__meta__maintainers),
                singleLine = false,
                showValidationErrors = showValidationErrors,
                validationResult = maintainersValidation,
            )
            EditorSheetTextField(
                value = keywords,
                onValueChange = { keywords = it },
                label = stringRes(R.string.ext__meta__keywords),
                singleLine = false,
            )
            EditorSheetTextField(
                value = homepage,
                onValueChange = { homepage = it },
                label = stringRes(R.string.ext__meta__homepage),
            )
            EditorSheetTextField(
                value = issueTracker,
                onValueChange = { issueTracker = it },
                label = stringRes(R.string.ext__meta__issue_tracker),
            )
            EditorSheetTextField(
                isRequired = true,
                value = license,
                onValueChange = { license = it },
                label = stringRes(R.string.ext__meta__license),
                showValidationErrors = showValidationErrors,
                validationResult = licenseValidation,
            )
        }
    }
}

@Composable
private fun EditorSheetTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isRequired: Boolean = false,
    singleLine: Boolean = true,
    showValidationErrors: Boolean = false,
    validationResult: ValidationResult? = null,
) {
    val isError = showValidationErrors && validationResult?.isInvalid() == true

    TextField(
        modifier = modifier.fillMaxWidth(),
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine,
        isError = isError,
        label = {
            Text(
                text = buildAnnotatedString {
                    append(label)
                    if (isRequired) {
                        append(" ")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.error)) {
                            append("*")
                        }
                    }
                },
            )
        },
        supportingText = {
            Validation(
                modifier = Modifier.padding(bottom = 8.dp),
                showValidationErrors = showValidationErrors,
                validationResult = validationResult,
            )
        },
        trailingIcon = if (isError) {
            {
                Icon(Icons.Default.Error, null)
            }
        } else {
            null
        },
        colors = TextFieldDefaults.driftColors(),
    )
}
