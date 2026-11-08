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
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.seshuthota.drift.R
import io.github.seshuthota.drift.app.components.ScrollableScreenColumn
import io.github.seshuthota.drift.ime.extension.ExtensionDependencyDecl
import io.github.seshuthota.drift.app.components.DriftScreenNg
import io.github.seshuthota.drift.lib.compose.DriftInfoCard
import io.github.seshuthota.drift.lib.compose.stringRes

@Composable
fun ExtensionEditDependenciesOverlay(
    dependencies: ExtensionDependencyDecl,
    onConfirm: (ExtensionDependencyDecl) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler {
        onDismiss()
    }

    DriftScreenNg(
        title = stringRes(R.string.ext__editor__dependencies__title),
        previewFieldVisible = false,
        navigationIcon = Icons.Default.Close,
        navigationIconOnClick = onDismiss,
    ) { contentPadding ->
        ScrollableScreenColumn(contentPadding) {
            DriftInfoCard(
                modifier = Modifier.padding(all = 8.dp),
                text = """
                Dependencies are currently not implemented, but are already somewhat
                integrated as a placeholder for the future.
            """.trimIndent().replace('\n', ' '),
            )
            if (dependencies.isEmpty()) {
                Text(text = "no deps found")
            } else {
                for (dependency in dependencies) {
                    Text(text = dependency.toString())
                }
            }
        }
    }
}
