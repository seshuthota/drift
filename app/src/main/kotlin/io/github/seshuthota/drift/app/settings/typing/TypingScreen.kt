/*
 * Copyright (C) 2021-2026 The FlorisBoard Contributors
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

package io.github.seshuthota.drift.app.settings.typing

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material3.Card
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import io.github.seshuthota.drift.R
import io.github.seshuthota.drift.app.DriftPreferenceStore
import io.github.seshuthota.drift.app.LocalNavController
import io.github.seshuthota.drift.app.components.ScrollableScreenColumn
import io.github.seshuthota.drift.app.components.SegmentedListColumn
import io.github.seshuthota.drift.app.enumDisplayEntriesOf
import io.github.seshuthota.drift.ime.keyboard.IncognitoMode
import io.github.seshuthota.drift.ime.keyboard3.touch.ShiftKeyBehavior
import io.github.seshuthota.drift.ime.nlp.SpellingLanguageMode
import io.github.seshuthota.drift.app.components.DriftHyperlinkText
import io.github.seshuthota.drift.app.components.DriftScreenNg
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.datastore.ui.ListPreference
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import io.github.seshuthota.drift.lib.android.AndroidVersion
import io.github.seshuthota.drift.lib.compose.DriftErrorCard
import io.github.seshuthota.drift.lib.compose.stringRes

@Composable
fun TypingScreen() {
    val prefs by DriftPreferenceStore
    val navController = LocalNavController.current

    DriftScreenNg(
        title = stringRes(R.string.settings__typing__title),
        previewFieldVisible = true,
    ) { contentPadding ->
        ScrollableScreenColumn(contentPadding) {
            SegmentedListColumn(title = stringRes(R.string.pref__typing_caps_group__title)) {
                val count = 3
                SwitchPreference(
                    prefs.typing.autoCapitalization,
                    shapes = ListItemDefaults.segmentedShapes(0, count),
                    title = stringRes(R.string.pref__correction__auto_capitalization__label),
                    summary = stringRes(R.string.pref__correction__auto_capitalization__summary),
                )
                SwitchPreference(
                    prefs.typing.rememberCapsLockState,
                    shapes = ListItemDefaults.segmentedShapes(1, count),
                    title = stringRes(R.string.pref__correction__remember_caps_lock_state__label),
                    summary = stringRes(R.string.pref__correction__remember_caps_lock_state__summary),
                )
                ListPreference(
                    prefs.typing.shiftKeyBehavior,
                    shapes = ListItemDefaults.segmentedShapes(2, count),
                    title = stringRes(R.string.pref__typing__shift_key_behavior__label),
                    entries = enumDisplayEntriesOf(ShiftKeyBehavior::class),
                )
            }

            // This card is temporary and is therefore not using a string resource (not so temporary as we thought...)
            DriftErrorCard(
                modifier = Modifier.padding(top = 32.dp),
                text = """
                Suggestions (except system autofill) and spell checking are not available in this release.
            """.trimIndent().replace('\n', ' '),
            )

            SegmentedListColumn(title = stringRes(R.string.pref__suggestion__title)) {
                val countOffset = if (AndroidVersion.ATLEAST_API30_R) 1 else 0
                val count = 3 + countOffset
                SwitchPreference(
                    prefs.suggestion.enabled,
                    shapes = ListItemDefaults.segmentedShapes(0, count),
                    title = stringRes(R.string.pref__suggestion__enabled__label),
                    summary = stringRes(R.string.pref__suggestion__enabled__summary),
                )
                SwitchPreference(
                    prefs.suggestion.blockPossiblyOffensive,
                    shapes = ListItemDefaults.segmentedShapes(1, count),
                    title = stringRes(R.string.pref__suggestion__block_possibly_offensive__label),
                    summary = stringRes(R.string.pref__suggestion__block_possibly_offensive__summary),
                    enabledIf = { prefs.suggestion.enabled isEqualTo true },
                )
                if (AndroidVersion.ATLEAST_API30_R) {
                    SwitchPreference(
                        prefs.suggestion.api30InlineSuggestionsEnabled,
                        shapes = ListItemDefaults.segmentedShapes(2, count),
                        title = stringRes(R.string.pref__suggestion__api30_inline_suggestions_enabled__label),
                        summary = stringRes(R.string.pref__suggestion__api30_inline_suggestions_enabled__summary),
                    )
                }
                ListPreference(
                    prefs.suggestion.incognitoMode,
                    shapes = ListItemDefaults.segmentedShapes(2 + countOffset, count),
                    icon = ImageVector.vectorResource(id = R.drawable.ic_incognito),
                    title = stringRes(R.string.pref__suggestion__incognito_mode__label),
                    entries = enumDisplayEntriesOf(IncognitoMode::class),
                )
            }

            SegmentedListColumn(title = stringRes(R.string.pref__correction__title)) {
                val count = 2
                val isAutoSpacePunctuationEnabled by prefs.correction.autoSpacePunctuation.collectAsState()
                SwitchPreference(
                    prefs.correction.autoSpacePunctuation,
                    shapes = ListItemDefaults.segmentedShapes(0, count),
                    icon = Icons.Default.SpaceBar,
                    title = stringRes(R.string.pref__correction__auto_space_punctuation__label),
                    summary = stringRes(R.string.pref__correction__auto_space_punctuation__summary),
                )
                if (isAutoSpacePunctuationEnabled) {
                    Card(modifier = Modifier.padding(8.dp)) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = """
                                Auto-space after punctuation is an experimental feature which may break or behave
                                unexpectedly. If you want, please give feedback about it in below linked feedback
                                thread. This helps a lot in improving this feature. Thanks!
                            """.trimIndent().replace('\n', ' '),
                            )
                            DriftHyperlinkText(
                                text = "Feedback thread (GitHub)",
                                url = "https://github.com/seshuthota/drift/discussions/1935",
                            )
                        }
                    }
                }
                SwitchPreference(
                    prefs.correction.doubleSpacePeriod,
                    shapes = ListItemDefaults.segmentedShapes(1, count),
                    title = stringRes(R.string.pref__correction__double_space_period__label),
                    summary = stringRes(R.string.pref__correction__double_space_period__summary),
                )
            }

            SegmentedListColumn(title = stringRes(R.string.pref__spelling__title)) {
                val count = 2
                val driftSpellCheckerEnabled = remember { mutableStateOf(false) }
                SpellCheckerServiceSelector(driftSpellCheckerEnabled)
                ListPreference(
                    prefs.spelling.languageMode,
                    shapes = ListItemDefaults.segmentedShapes(1, count),
                    icon = Icons.Default.Language,
                    title = stringRes(R.string.pref__spelling__language_mode__label),
                    entries = enumDisplayEntriesOf(SpellingLanguageMode::class),
                    enabledIf = { driftSpellCheckerEnabled.value },
                )
                /*
                SwitchPreference(
                    prefs.spelling.useContacts,
                    icon = Icons.Default.Contacts,
                    title = stringRes(R.string.pref__spelling__use_contacts__label),
                    summary = stringRes(R.string.pref__spelling__use_contacts__summary),
                    enabledIf = { driftSpellCheckerEnabled.value },
                )
                SwitchPreference(
                    prefs.spelling.useUdmEntries,
                    icon = Icons.AutoMirrored.Filled.LibraryBooks,
                    title = stringRes(R.string.pref__spelling__use_udm_entries__label),
                    summary = stringRes(R.string.pref__spelling__use_udm_entries__summary),
                    enabledIf = { driftSpellCheckerEnabled.value },
                )
                */
            }

            /*
            SegmentedListColumn(title = stringRes(R.string.settings__dictionary__title)) {
                val count = 1
                Preference(
                    shapes = ListItemDefaults.segmentedShapes(0, count),
                    icon = Icons.AutoMirrored.Filled.LibraryBooks,
                    title = stringRes(R.string.settings__dictionary__title),
                    onClick = { navController.navigate(Routes.Settings.Dictionary) },
                    enabledIf = { false },
                )
            }
            */
        }
    }
}
