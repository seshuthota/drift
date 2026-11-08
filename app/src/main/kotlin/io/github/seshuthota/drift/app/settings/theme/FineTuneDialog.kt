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

package io.github.seshuthota.drift.app.settings.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import io.github.seshuthota.drift.R
import io.github.seshuthota.drift.app.DriftPreferenceStore
import io.github.seshuthota.drift.app.components.SegmentedListColumn
import io.github.seshuthota.drift.app.enumDisplayEntriesOf
import dev.patrickgold.jetpref.datastore.ui.ListPreference
import dev.patrickgold.jetpref.material.ui.ColorRepresentation
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import io.github.seshuthota.drift.lib.compose.stringRes

private val FineTuneContentPadding = PaddingValues(horizontal = 8.dp)

@Composable
fun FineTuneDialog(onDismiss: () -> Unit) {
    val prefs by DriftPreferenceStore

    JetPrefAlertDialog(
        title = stringRes(R.string.settings__theme_editor__fine_tune__title),
        onDismiss = onDismiss,
        contentPadding = FineTuneContentPadding,
    ) {
        SegmentedListColumn {
            val count = 3
            ListPreference(
                listPref = prefs.theme.editorLevel,
                shapes = ListItemDefaults.segmentedShapes(0, count),
                title = stringRes(R.string.settings__theme_editor__fine_tune__level),
                entries = enumDisplayEntriesOf(SnyggLevel::class),
            )
            ListPreference(
                listPref = prefs.theme.editorColorRepresentation,
                shapes = ListItemDefaults.segmentedShapes(1, count),
                title = stringRes(R.string.settings__theme_editor__fine_tune__color_representation),
                entries = enumDisplayEntriesOf(ColorRepresentation::class),
            )
            ListPreference(
                listPref = prefs.theme.editorDisplayKbdAfterDialogs,
                shapes = ListItemDefaults.segmentedShapes(2, count),
                title = stringRes(R.string.settings__theme_editor__fine_tune__display_kbd_after_dialogs),
                entries = enumDisplayEntriesOf(DisplayKbdAfterDialogs::class),
            )
        }
    }
}
