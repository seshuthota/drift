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

package io.github.seshuthota.drift.app

import androidx.compose.ui.graphics.Color
import io.github.seshuthota.drift.app.settings.theme.ColorPreferenceSerializer
import io.github.seshuthota.drift.app.settings.theme.DisplayKbdAfterDialogs
import io.github.seshuthota.drift.app.settings.theme.SnyggLevel
import io.github.seshuthota.drift.ime.asr.AsrModelRegistry
import io.github.seshuthota.drift.ime.asr.VoiceRefineMode
import io.github.seshuthota.drift.ime.clipboard.CLIPBOARD_HISTORY_NUM_GRID_COLUMNS_AUTO
import io.github.seshuthota.drift.ime.clipboard.ClipboardSyncBehavior
import io.github.seshuthota.drift.ime.core.DisplayLanguageNamesIn
import io.github.seshuthota.drift.ime.core.Subtype
import io.github.seshuthota.drift.ime.extension.ExtensionComponentName
import io.github.seshuthota.drift.ime.extension.extCoreTheme
import io.github.seshuthota.drift.ime.keyboard.IncognitoMode
import io.github.seshuthota.drift.ime.keyboard.SpaceBarMode
import io.github.seshuthota.drift.ime.keyboard3.ImeActions
import io.github.seshuthota.drift.ime.keyboard3.interaction.InteractionTimingOptions
import io.github.seshuthota.drift.ime.keyboard3.touch.FnKeyAction
import io.github.seshuthota.drift.ime.keyboard3.touch.FnKeyArrangement
import io.github.seshuthota.drift.ime.keyboard3.touch.ShiftKeyBehavior
import io.github.seshuthota.drift.ime.keyboard3.touch.TouchModelOptions
import io.github.seshuthota.drift.ime.landscapeinput.LandscapeInputUiMode
import io.github.seshuthota.drift.ime.media.emoji.EmojiHairStyle
import io.github.seshuthota.drift.ime.media.emoji.EmojiHistory
import io.github.seshuthota.drift.ime.media.emoji.EmojiSkinTone
import io.github.seshuthota.drift.ime.media.emoji.EmojiSuggestionType
import io.github.seshuthota.drift.ime.nlp.SpellingLanguageMode
import io.github.seshuthota.drift.ime.smartbar.CandidatesDisplayMode
import io.github.seshuthota.drift.ime.smartbar.ExtendedActionsPlacement
import io.github.seshuthota.drift.ime.smartbar.IncognitoDisplayMode
import io.github.seshuthota.drift.ime.smartbar.SmartbarLayout
import io.github.seshuthota.drift.ime.smartbar.quickaction.QuickActionArrangement
import io.github.seshuthota.drift.ime.smartbar.quickaction.QuickActionJsonConfig
import io.github.seshuthota.drift.ime.keyboard3.gesture.SwipeAction
import io.github.seshuthota.drift.ime.theme.PreferredThemeMode
import io.github.seshuthota.drift.ime.window.ImeWindowConfig
import dev.patrickgold.jetpref.datastore.annotations.Preferences
import dev.patrickgold.jetpref.datastore.jetprefDataStoreOf
import dev.patrickgold.jetpref.datastore.model.LocalTime
import dev.patrickgold.jetpref.datastore.model.PreferenceMigrationEntry
import dev.patrickgold.jetpref.datastore.model.PreferenceModel
import dev.patrickgold.jetpref.datastore.model.PreferenceType
import dev.patrickgold.jetpref.material.ui.ColorRepresentation
import kotlinx.serialization.json.Json

val DriftPreferenceStore = jetprefDataStoreOf(DriftPreferenceModel::class)

@Preferences
abstract class DriftPreferenceModel : PreferenceModel() {
    companion object {
        const val NAME = "drift-app-prefs"
    }

    val voice = Voice()
    inner class Voice {
        val modelId = string(
            key = "voice__model_id",
            default = AsrModelRegistry.DEFAULT_MODEL_ID,
        )
        val maxDurationSeconds = int(
            key = "voice__max_duration_seconds",
            default = 30,
        )
        val keepModelLoaded = boolean(
            key = "voice__keep_model_loaded",
            default = false,
        )

        val plainMicSource = boolean(
            key = "voice__plain_mic_source",
            default = false,
        )

        val refineMode = enum(
            key = "voice__refine_mode",
            default = VoiceRefineMode.LOG_ONLY,
        )

        /** Model of the finishing pass, or empty to refine with the live model. Only one-pass models are offered. */
        val finisherModelId = string(
            key = "voice__finisher_model_id",
            default = "",
        )
    }

    val clipboard = Clipboard()
    inner class Clipboard {
        val useInternalClipboard = boolean(
            key = "clipboard__use_internal_clipboard",
            default = false,
        )
        val syncToDrift = enum(
            key = "clipboard__sync_to_drift",
            default = ClipboardSyncBehavior.ALL_EVENTS,
        )
        val syncToSystem = enum(
            key = "clipboard__sync_to_system",
            default = ClipboardSyncBehavior.NO_EVENTS,
        )
        val suggestionEnabled = boolean(
            key = "clipboard__suggestion_enabled",
            default = true,
        )
        val suggestionTimeout = int(
            key = "clipboard__suggestion_timeout",
            default = 60,
        )
        val historyEnabled = boolean(
            key = "clipboard__history_enabled",
            default = false,
        )
        val historyNumGridColumnsPortrait = int(
            key = "clipboard__history_num_grid_columns_portrait",
            default = CLIPBOARD_HISTORY_NUM_GRID_COLUMNS_AUTO,
        )
        val historyNumGridColumnsLandscape = int(
            key = "clipboard__history_num_grid_columns_landscape",
            default = CLIPBOARD_HISTORY_NUM_GRID_COLUMNS_AUTO,
        )
        val historyAutoCleanOldEnabled = boolean(
            key = "clipboard__history_auto_clean_old_enabled",
            default = false,
        )
        val historyAutoCleanOldAfter = int(
            key = "clipboard__history_auto_clean_old_after",
            default = 20,
        )
        val historyAutoCleanSensitiveEnabled = boolean(
            key = "clipboard__history_auto_clean_sensitive_enabled",
            default = false,
        )
        val historyAutoCleanSensitiveAfter = int(
            key = "clipboard__history_auto_clean_sensitive_after",
            default = 20,
        )
        val historySizeLimitEnabled = boolean(
            key = "clipboard__history_size_limit_enabled",
            default = true,
        )
        val historySizeLimit = int(
            key = "clipboard__history_size_limit",
            default = 20,
        )
        val historyHideOnPaste = boolean(
            key = "clipboard__history_hide_on_paste",
            default = false,
        )
        val historyHideOnNextTextField = boolean(
            key = "clipboard__history_hide_on_next_text_field",
            default = true,
        )
        val clearPrimaryClipAffectsHistoryIfUnpinned = boolean(
            key = "clipboard__clear_primary_clip_affects_history_if_unpinned",
            default = true,
        )
    }

    val correction = Correction()
    inner class Correction {
        val autoCorrect = boolean(
            key = "correction__auto_correct",
            default = true,
        )
        val autoSpacePunctuation = boolean(
            key = "correction__auto_space_punctuation",
            default = false,
        )
        val doubleSpacePeriod = boolean(
            key = "correction__double_space_period",
            default = true,
        )
        val personalLearning = boolean(
            key = "correction__personal_learning",
            default = false,
        )
    }

    val devtools = Devtools()
    inner class Devtools {
        val enabled = boolean(
            key = "devtools__enabled",
            default = false,
        )
        val showPrimaryClip = boolean(
            key = "devtools__show_primary_clip",
            default = false,
        )
        val showInputStateOverlay = boolean(
            key = "devtools__show_input_state_overlay",
            default = false,
        )
        val showSpellingOverlay = boolean(
            key = "devtools__show_spelling_overlay",
            default = false,
        )
        val showInlineAutofillOverlay = boolean(
            key = "devtools__show_inline_autofill_overlay",
            default = false,
        )
        val showKeyTouchBoundaries = boolean(
            key = "devtools__show_touch_boundaries",
            default = false,
        )
        val showDragAndDropHelpers = boolean(
            key = "devtools__show_drag_and_drop_helpers",
            default = false,
        )
        val showWindowResizeHandleBoundaries = boolean(
            key = "devtools__show_window_resize_handle_boundaries",
            default = false,
        )
        val glideDiagnostics = boolean(
            key = "devtools__glide_diagnostics",
            default = false,
        )
        val qualityLog = boolean(
            key = "devtools__quality_log",
            default = false,
        )
        val voiceClips = boolean(
            key = "devtools__voice_clips",
            default = false,
        )
    }

    val dictionary = Dictionary()
    inner class Dictionary {
        val enableSystemUserDictionary = boolean(
            key = "suggestion__enable_system_user_dictionary",
            default = true,
        )
        val enableDriftUserDictionary = boolean(
            key = "suggestion__enable_drift_user_dictionary",
            default = true,
        )
    }

    val emoji = Emoji()
    inner class Emoji {
        val preferredSkinTone = enum(
            key = "emoji__preferred_skin_tone",
            default = EmojiSkinTone.DEFAULT,
        )
        val preferredHairStyle = enum(
            key = "emoji__preferred_hair_style",
            default = EmojiHairStyle.DEFAULT,
        )
        val historyEnabled = boolean(
            key = "emoji__history_enabled",
            default = true,
        )
        val historyData = custom(
            key = "emoji__history_data",
            default = EmojiHistory.Empty,
            serializer = EmojiHistory.Serializer,
        )
        val historyPinnedUpdateStrategy = enum(
            key = "emoji__history_pinned_update_strategy",
            default = EmojiHistory.UpdateStrategy.MANUAL_SORT_PREPEND,
        )
        val historyPinnedMaxSize = int(
            key = "emoji__history_pinned_max_size",
            default = EmojiHistory.MaxSizeUnlimited,
        )
        val historyRecentUpdateStrategy = enum(
            key = "emoji__history_recent_update_strategy",
            default = EmojiHistory.UpdateStrategy.AUTO_SORT_PREPEND,
        )
        val historyRecentMaxSize = int(
            key = "emoji__history_recent_max_size",
            default = 90,
        )
        val suggestionEnabled = boolean(
            key = "emoji__suggestion_enabled",
            default = true,
        )
        val suggestionType = enum(
            key = "emoji__suggestion_type",
            default = EmojiSuggestionType.LEADING_COLON,
        )
        val suggestionUpdateHistory = boolean(
            key = "emoji__suggestion_update_history",
            default = true,
        )
        val suggestionCandidateShowName = boolean(
            key = "emoji__suggestion_candidate_show_name",
            default = false,
        )
        val suggestionQueryMinLength = int(
            key = "emoji__suggestion_query_min_length",
            default = 3,
        )
        val suggestionCandidateMaxCount = int(
            key = "emoji__suggestion_candidate_max_count",
            default = 5,
        )
        val experimentalEmojiSearchEnabled = boolean(
            key = "emoji__experimental_emoji_search_enabled",
            default = false,
        )
    }

    val gestures = Gestures()
    inner class Gestures {
        val swipeUp = enum(
            key = "gestures__swipe_up",
            default = SwipeAction.SHIFT,
        )
        val swipeDown = enum(
            key = "gestures__swipe_down",
            default = SwipeAction.HIDE_KEYBOARD,
        )
        val swipeLeft = enum(
            key = "gestures__swipe_left",
            default = SwipeAction.SWITCH_TO_NEXT_SUBTYPE,
        )
        val swipeRight = enum(
            key = "gestures__swipe_right",
            default = SwipeAction.SWITCH_TO_PREV_SUBTYPE,
        )
        val spaceBarSwipeUp = enum(
            key = "gestures__space_bar_swipe_up",
            default = SwipeAction.NO_ACTION,
        )
        val spaceBarSwipeLeft = enum(
            key = "gestures__space_bar_swipe_left",
            default = SwipeAction.MOVE_CURSOR_LEFT,
        )
        val spaceBarSwipeRight = enum(
            key = "gestures__space_bar_swipe_right",
            default = SwipeAction.MOVE_CURSOR_RIGHT,
        )
        val spaceBarLongPress = enum(
            key = "gestures__space_bar_long_press",
            default = SwipeAction.SHOW_INPUT_METHOD_PICKER,
        )
        val deleteKeySwipeLeft = enum(
            key = "gestures__delete_key_swipe_left",
            default = SwipeAction.DELETE_CHARACTERS_PRECISELY,
        )
        val deleteKeyLongPress = enum(
            key = "gestures__delete_key_long_press",
            default = SwipeAction.DELETE_CHARACTER,
        )
        val swipeDistanceThreshold = int(
            key = "gestures__swipe_distance_threshold",
            default = 32,
        )
        val swipeVelocityThreshold = int(
            key = "gestures__swipe_velocity_threshold",
            default = 1900,
        )
    }

    val glide = Glide()
    inner class Glide {
        val enabled = boolean(
            key = "glide__enabled",
            default = true,
        )
        val showTrail = boolean(
            key = "glide__show_trail",
            default = true,
        )
        val trailDuration = int(
            key = "glide__trail_fade_duration",
            default = 200,
        )
        val showPreview = boolean(
            key = "glide__show_preview",
            default = true,
        )
        val previewRefreshDelay = int(
            key = "glide__preview_refresh_delay",
            default = 150,
        )
        val immediateBackspaceDeletesWord = boolean(
            key = "glide__immediate_backspace_deletes_word",
            default = true,
        )
    }

    val inputFeedback = InputFeedback()
    inner class InputFeedback {
        val audioEnabled = boolean(
            key = "input_feedback__audio_enabled",
            default = true,
        )
        val audioVolume = int(
            key = "input_feedback__audio_volume",
            default = 50,
        )
        val audioFeatKeyPress = boolean(
            key = "input_feedback__audio_feat_key_press",
            default = true,
        )
        val audioFeatKeyLongPress = boolean(
            key = "input_feedback__audio_feat_key_long_press",
            default = false,
        )
        val audioFeatKeyRepeatedAction = boolean(
            key = "input_feedback__audio_feat_key_repeated_action",
            default = false,
        )
        val audioFeatGestureSwipe = boolean(
            key = "input_feedback__audio_feat_gesture_swipe",
            default = false,
        )
        val audioFeatGestureMovingSwipe = boolean(
            key = "input_feedback__audio_feat_gesture_moving_swipe",
            default = false,
        )

        val hapticEnabled = boolean(
            key = "input_feedback__haptic_enabled",
            default = true,
        )
        val hapticFeatKeyPress = boolean(
            key = "input_feedback__haptic_feat_key_press",
            default = true,
        )
        val hapticFeatKeyLongPress = boolean(
            key = "input_feedback__haptic_feat_key_long_press",
            default = false,
        )
        val hapticFeatKeyRepeatedAction = boolean(
            key = "input_feedback__haptic_feat_key_repeated_action",
            default = true,
        )
        val hapticFeatGestureSwipe = boolean(
            key = "input_feedback__haptic_feat_gesture_swipe",
            default = false,
        )
        val hapticFeatGestureMovingSwipe = boolean(
            key = "input_feedback__haptic_feat_gesture_moving_swipe",
            default = true,
        )
    }

    val keyboard = Keyboard()
    inner class Keyboard {
        val windowConfig = custom(
            key = "keyboard__window_config",
            default = emptyMap(),
            serializer = ImeWindowConfig.ByTypeSerializer,
        )
        val numberRow = boolean(
            key = "keyboard__number_row",
            default = false,
        )
        val spaceBarMode = enum(
            key = "keyboard__space_bar_display_mode",
            default = SpaceBarMode.CURRENT_LANGUAGE,
        )
        val fontSizeMultiplierPortrait = int(
            key = "keyboard__font_size_multiplier_portrait",
            default = 100,
        )
        val fontSizeMultiplierLandscape = int(
            key = "keyboard__font_size_multiplier_landscape",
            default = 100,
        )
        val landscapeInputUiMode = enum(
            key = "keyboard__landscape_input_ui_mode",
            default = LandscapeInputUiMode.DYNAMICALLY_SHOW,
        )
        val keySpacingVertical = int(
            key = "keyboard__key_spacing_vertical",
            default = 100,
        )
        val keySpacingHorizontal = int(
            key = "keyboard__key_spacing_horizontal",
            default = 100,
        )
        val popupEnabled = boolean(
            key = "keyboard__popup_enabled",
            default = true,
        )
        val spaceBarSwitchesToCharacters = boolean(
            key = "keyboard__space_bar_switches_to_characters",
            default = true,
        )
        val incognitoDisplayMode = enum(
            key = "keyboard__incognito_indicator",
            default = IncognitoDisplayMode.DISPLAY_BEHIND_KEYBOARD,
        )

        val fnKeyEnabled = boolean(
            key = "keyboard__fn_key_enabled",
            default = TouchModelOptions.Default.fnKeyEnabled,
        )
        val fnKeyArrangement = custom(
            key = "keyboard__fn_key_arrangement",
            default = TouchModelOptions.Default.fnKeyArrangement,
            serializer = FnKeyArrangement.Serializer,
        )

        val longPressTimeoutUseSystem = boolean(
            key = "keyboard__long_press_timeout_use_system",
            default = true,
        )
        val longPressTimeout = int(
            key = "keyboard__long_press_timeout",
            default = InteractionTimingOptions.Default.longPressTimeout.inWholeMilliseconds.toInt(),
        )
        val longPressKeyHintEnabled = boolean(
            key = "keyboard__long_press_key_hint_enabled",
            default = TouchModelOptions.Default.longPressKeyHintEnabled,
        )
        val longPressKeyHintPlacement = enum(
            key = "keyboard__long_press_key_hint_placement",
            default = TouchModelOptions.Default.longPressKeyHintPlacement,
        )

        val multiTapTimeoutUseSystem = boolean(
            key = "keyboard__multi_tap_timeout_use_system",
            default = true,
        )
        val multiTapTimeout = int(
            key = "keyboard__multi_tap_timeout",
            default = InteractionTimingOptions.Default.multiTapTimeout.inWholeMilliseconds.toInt(),
        )
        val multiTapHighlightEnabled = boolean(
            key = "keyboard__multi_tap_highlight_enabled",
            default = TouchModelOptions.Default.multiTapHighlightEnabled,
        )

        val flickKeyHintEnabled = boolean(
            key = "keyboard__flick_key_hint_enabled",
            default = TouchModelOptions.Default.flickKeyHintEnabled,
        )
        val flickKeyHintPlacement = enum(
            key = "keyboard__flick_key_hint_placement",
            default = TouchModelOptions.Default.flickKeyHintPlacement,
        )
    }

    val localization = Localization()
    inner class Localization {
        val displayLanguageNamesIn = enum(
            key = "localization__display_language_names_in",
            default = DisplayLanguageNamesIn.SYSTEM_LOCALE,
        )
        val displayKeyboardLabelsInSubtypeLanguage = boolean(
            key = "localization__display_keyboard_labels_in_subtype_language",
            default = false,
        )
        val activeSubtypeId = long(
            key = "localization__active_subtype_id",
            default = Subtype.DEFAULT.id,
        )
        val subtypes = string(
            key = "localization__subtypes",
            default = "[]",
        )
    }

    val other = Other()
    inner class Other {
        val settingsTheme = enum(
            key = "other__settings_theme",
            default = AppTheme.AUTO,
        )
        val accentColor = custom(
            key = "other__accent_color",
            default = Color.Unspecified,
            serializer = ColorPreferenceSerializer,
        )
        val settingsLanguage = string(
            key = "other__settings_language",
            default = "auto",
        )
        val showAppIcon = boolean(
            key = "other__show_app_icon",
            default = true,
        )
    }

    val physicalKeyboard = PhysicalKeyboard()
    inner class PhysicalKeyboard {
        val showOnScreenKeyboard = boolean(
            key = "physical_keyboard__show_on_screen_keyboard",
            default = false,
        )
    }

    val smartbar = Smartbar()
    inner class Smartbar {
        val enabled = boolean(
            key = "smartbar__enabled",
            default = true,
        )
        val layout = enum(
            key = "smartbar__layout",
            default = SmartbarLayout.SUGGESTIONS_ACTIONS_SHARED,
        )
        val actionArrangement = custom(
            key = "smartbar__action_arrangement",
            default = QuickActionArrangement.Default,
            serializer = QuickActionArrangement.Serializer,
        )
        val flipToggles = boolean(
            key = "smartbar__flip_toggles",
            default = false,
        )
        val sharedActionsExpanded = boolean(
            key = "smartbar__shared_actions_expanded",
            default = false,
        )
        @Deprecated("Always enabled due to UX issues")
        val sharedActionsAutoExpandCollapse = boolean(
            key = "smartbar__shared_actions_auto_expand_collapse",
            default = true,
        )
        val sharedActionsExpandWithAnimation = boolean(
            key = "smartbar__shared_actions_expand_with_animation",
            default = true,
        )
        val extendedActionsExpanded = boolean(
            key = "smartbar__extended_actions_expanded",
            default = false,
        )
        val extendedActionsPlacement = enum(
            key = "smartbar__extended_actions_placement",
            default = ExtendedActionsPlacement.ABOVE_CANDIDATES,
        )
    }

    val spelling = Spelling()
    inner class Spelling {
        val languageMode = enum(
            key = "spelling__language_mode",
            default = SpellingLanguageMode.USE_KEYBOARD_SUBTYPES,
        )
        val useContacts = boolean(
            key = "spelling__use_contacts",
            default = true,
        )
        val useUdmEntries = boolean(
            key = "spelling__use_udm_entries",
            default = true,
        )
    }

    val suggestion = Suggestion()
    inner class Suggestion {
        val api30InlineSuggestionsEnabled = boolean(
            key = "suggestion__api30_inline_suggestions_enabled",
            default = true,
        )
        val enabled = boolean(
            key = "suggestion__enabled",
            default = true,
        )
        val displayMode = enum(
            key = "suggestion__display_mode",
            default = CandidatesDisplayMode.DYNAMIC_SCROLLABLE,
        )
        val blockPossiblyOffensive = boolean(
            key = "suggestion__block_possibly_offensive",
            default = true,
        )
        val incognitoMode = enum(
            key = "suggestion__incognito_mode",
            default = IncognitoMode.DYNAMIC_ON_OFF,
        )
        val forceIncognitoModeFromDynamic = boolean(
            key = "suggestion__force_incognito_mode_from_dynamic",
            default = false,
        )
    }

    val theme = Theme()
    inner class Theme {
        val mode = enum(
            key = "theme__mode",
            default = PreferredThemeMode.FOLLOW_SYSTEM,
        )
        val dayThemeId = custom(
            key = "theme__day_theme_id",
            default = extCoreTheme("drift_day"),
            serializer = ExtensionComponentName.Serializer,
        )
        val nightThemeId = custom(
            key = "theme__night_theme_id",
            default = extCoreTheme("drift_night"),
            serializer = ExtensionComponentName.Serializer,
        )
        val accentColor = custom(
            key = "theme__accent_color",
            default = Color.Unspecified,
            serializer = ColorPreferenceSerializer,
        )
        val sunriseTime = localTime(
            key = "theme__sunrise_time",
            default = LocalTime(6, 0),
        )
        val sunsetTime = localTime(
            key = "theme__sunset_time",
            default = LocalTime(18, 0),
        )
        val editorColorRepresentation = enum(
            key = "theme__editor_color_representation",
            default = ColorRepresentation.HEX,
        )
        val editorDisplayKbdAfterDialogs = enum(
            key = "theme__editor_display_kbd_after_dialogs",
            default = DisplayKbdAfterDialogs.REMEMBER,
        )
        val editorLevel = enum(
            key = "theme__editor_level",
            default = SnyggLevel.ADVANCED,
        )
    }

    val typing = Typing()
    inner class Typing {
        val autoCapitalization = boolean(
            key = "typing__auto_capitalization",
            default = true,
        )
        val rememberCapsLockState = boolean(
            key = "typing__remember_caps_lock_state",
            default = false,
        )
        val shiftKeyBehavior = enum(
            key = "typing__shift_key_behavior",
            default = ShiftKeyBehavior.CAPSLOCK_BY_DOUBLE_TAP,
        )
    }

    override fun migrate(entry: PreferenceMigrationEntry): PreferenceMigrationEntry {
        return when (entry.key) {

            "media__emoji_recently_used" -> {
                val emojiValues = entry.rawValue.split(";")
                val recent = emojiValues.map {
                    io.github.seshuthota.drift.ime.media.emoji.Emoji(it, "", emptyList())
                }
                val data = EmojiHistory(emptyList(), recent)
                entry.transform(key = "emoji__history_data", rawValue = Json.encodeToString(data))
            }
            "media__emoji_recently_used_max_size" -> {
                entry.transform(key = "emoji__history_recent_max_size")
            }

            "advanced__settings_theme" -> {
                entry.transform(key = "other__settings_theme")
            }
            "advanced__accent_color" -> {
                entry.transform(key = "other__accent_color")
            }
            "advanced__settings_language" -> {
                entry.transform(key = "other__settings_language")
            }
            "advanced__show_app_icon" -> {
                entry.transform(key = "other__show_app_icon")
            }
            "advanced__incognito_mode" -> {
                entry.transform(key = "suggestion__incognito_mode")
            }
            "advanced__force_incognito_mode_from_dynamic" -> {
                entry.transform(key = "suggestion__force_incognito_mode_from_dynamic")
            }
            "suggestion__clipboard_content_enabled" -> {
                entry.transform(key = "clipboard__suggestion_enabled")
            }
            "suggestion__clipboard_content_timeout" -> {
                entry.transform(key = "clipboard__suggestion_timeout")
            }

            "keyboard__one_handed_mode" -> {
                if (entry.rawValue == "OFF") {
                    entry.reset()
                } else {
                    entry.keepAsIs()
                }
            }

            "smartbar__action_arrangement" -> {
                val arrangement = QuickActionJsonConfig.decodeFromString<QuickActionArrangement>(entry.rawValue)
                var newArrangement = arrangement.migrateToK3Descriptors()
                QuickActionArrangement.Default.forEach { defaultAction ->
                    if (defaultAction !in newArrangement) {
                        newArrangement = newArrangement.copy(
                            dynamicActions = newArrangement.dynamicActions.plus(defaultAction),
                        )
                    }
                }
                val json = QuickActionJsonConfig.encodeToString(newArrangement.distinct())
                entry.transform(rawValue = json)
            }

            "theme__editor_display_colors_as" -> {
                val colorRepresentation = when (entry.rawValue) {
                    "RGBA" -> ColorRepresentation.RGB
                    else -> ColorRepresentation.HEX
                }
                entry.transform(
                    key = "theme__editor_color_representation",
                    rawValue = colorRepresentation.name,
                )
            }

            "clipboard__sync_to_drift", "clipboard__sync_to_system" -> {
                entry.transform(
                    type = PreferenceType.string(),
                    rawValue = when (entry.rawValue) {
                        "true" -> ClipboardSyncBehavior.ALL_EVENTS.name
                        "false" -> ClipboardSyncBehavior.NO_EVENTS.name
                        else -> entry.rawValue
                    },
                )
            }
            "clipboard__num_history_grid_columns_portrait" -> {
                entry.transform(key = "clipboard__history_num_grid_columns_portrait")
            }
            "clipboard__num_history_grid_columns_landscape" -> {
                entry.transform(key = "clipboard__history_num_grid_columns_landscape")
            }
            "clipboard__clean_up_old" -> {
                entry.transform(key = "clipboard__history_auto_clean_old_enabled")
            }
            "clipboard__clean_up_after" -> {
                entry.transform(key = "clipboard__history_auto_clean_old_after")
            }
            "clipboard__auto_clean_sensitive" -> {
                entry.transform(key = "clipboard__history_auto_clean_sensitive_enabled")
            }
            "clipboard__auto_clean_sensitive_after" -> {
                entry.transform(key = "clipboard__history_auto_clean_sensitive_after")
            }
            "clipboard__limit_history_size" -> {
                entry.transform(key = "clipboard__history_size_limit_enabled")
            }
            "clipboard__max_history_size" -> {
                entry.transform(key = "clipboard__history_size_limit")
            }
            "clipboard__clear_primary_clip_deletes_last_item" -> {
                entry.transform(key = "clipboard__clear_primary_clip_affects_history_if_unpinned")
            }

            "keyboard__key_spacing_horizontal" -> {
                if (entry.type.isFloat()) {
                    entry.reset()
                } else {
                    entry.keepAsIs()
                }
            }
            "keyboard__key_spacing_vertical" -> {
                if (entry.type.isFloat()) {
                    entry.reset()
                } else {
                    entry.keepAsIs()
                }
            }

            "keyboard__utility_key_enabled" -> {
                entry.transform(key = "keyboard__fn_key_enabled")
            }
            "keyboard__utility_key_action" -> {
                entry.transform(
                    key = "keyboard__fn_key_arrangement",
                    rawValue = let {
                        val fnKey = FnKeyArrangement(
                            simpleAction = FnKeyAction(when (entry.rawValue) {
                                "SWITCH_TO_EMOJIS" -> ImeActions.ShowMediaPanel
                                "SWITCH_KEYBOARD_APP" -> ImeActions.SwitchToNextInputMethod
                                else -> ImeActions.SwitchToNextSubtype
                            }),
                            longPressActions = FnKeyArrangement.Default.longPressActions,
                        )
                        Json.encodeToString(fnKey)
                    },
                )
            }

            "keyboard__long_press_delay" -> {
                entry.transform(key = "keyboard__long_press_timeout")
            }

            "correction__auto_capitalization" -> {
                entry.transform(key = "typing__auto_capitalization")
            }
            "correction__remember_caps_lock_state" -> {
                entry.transform(key = "typing__remember_caps_lock_state")
            }
            "keyboard__capitalization_behavior" -> {
                entry.transform(key = "typing__shift_key_behavior")
            }

            else -> entry.keepAsIs()
        }
    }
}
