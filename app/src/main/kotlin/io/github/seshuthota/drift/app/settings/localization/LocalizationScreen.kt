/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
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

package io.github.seshuthota.drift.app.settings.localization

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.seshuthota.drift.R
import io.github.seshuthota.drift.app.components.DriftScreen
import io.github.seshuthota.drift.app.enumDisplayEntriesOf
import io.github.seshuthota.drift.ime.core.DisplayLanguageNamesIn
import io.github.seshuthota.drift.lib.compose.DriftWarningCard
import io.github.seshuthota.drift.lib.compose.stringRes
import io.github.seshuthota.drift.subtypeManager
import dev.patrickgold.jetpref.datastore.ui.ListPreference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference

@Composable
fun LocalizationScreen() = DriftScreen {
    title = stringRes(R.string.settings__localization__title)
    previewFieldVisible = true
    iconSpaceReserved = false

    val context = LocalContext.current
    val subtypeManager by context.subtypeManager()

    content {
        ListPreference(
            prefs.localization.displayLanguageNamesIn,
            title = stringRes(R.string.settings__localization__display_language_names_in__label),
            entries = enumDisplayEntriesOf(DisplayLanguageNamesIn::class),
        )
        SwitchPreference(
            prefs.localization.displayKeyboardLabelsInSubtypeLanguage,
            title = stringRes(R.string.settings__localization__display_keyboard_labels_in_subtype_language),
        )
        PreferenceGroup(title = stringRes(R.string.settings__localization__group_subtypes__label)) {
            val subtypes by subtypeManager.subtypesFlow.collectAsState()
            if (subtypes.isEmpty()) {
                DriftWarningCard(
                    modifier = Modifier.padding(all = 8.dp),
                    text = stringRes(R.string.settings__localization__subtype_no_subtypes_configured_warning),
                )
            }
        }
    }
}
