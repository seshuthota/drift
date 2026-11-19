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

package io.github.seshuthota.drift.ime.text.keyboard

import android.icu.lang.UCharacter
import io.github.seshuthota.drift.ime.keyboard.KeyData
import io.github.seshuthota.drift.ime.text.key.KeyCode
import io.github.seshuthota.drift.ime.text.key.KeyType
import io.github.seshuthota.drift.lib.DriftLocale
import io.github.seshuthota.drift.lib.Unicode
import io.github.seshuthota.drift.lib.lowercase
import io.github.seshuthota.drift.lib.uppercase
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
@SerialName("text_key")
data class TextKeyData(
    override val type: KeyType = KeyType.CHARACTER,
    override val code: Int = KeyCode.UNSPECIFIED,
    override val label: String = "",
    override val groupId: Int = KeyData.GROUP_DEFAULT,
) : KeyData {
    override fun asString(isForDisplay: Boolean): String {
        return asString(this, isForDisplay)
    }

    override fun toString(): String {
        return "${TextKeyData::class.simpleName} { type=$type code=$code label=\"$label\" groupId=$groupId }"
    }

    @Suppress("MemberVisibilityCanBePrivate")
    companion object {
        val UNSPECIFIED = TextKeyData(
            type = KeyType.UNSPECIFIED,
            code = KeyCode.UNSPECIFIED,
            label = "unspecified",
        )

        val SPACE = TextKeyData(
            type = KeyType.CHARACTER,
            code = KeyCode.SPACE,
            label = "space",
        )

        val CTRL = TextKeyData(
            type = KeyType.MODIFIER,
            code = KeyCode.CTRL,
            label = "ctrl",
        )
        val CTRL_LOCK = TextKeyData(
            type = KeyType.MODIFIER,
            code = KeyCode.CTRL_LOCK,
            label = "ctrl_lock",
        )
        val ALT = TextKeyData(
            type = KeyType.MODIFIER,
            code = KeyCode.ALT,
            label = "alt",
        )
        val ALT_LOCK = TextKeyData(
            type = KeyType.MODIFIER,
            code = KeyCode.ALT_LOCK,
            label = "alt_lock",
        )
        val FN = TextKeyData(
            type = KeyType.MODIFIER,
            code = KeyCode.FN,
            label = "fn",
        )
        val FN_LOCK = TextKeyData(
            type = KeyType.MODIFIER,
            code = KeyCode.FN_LOCK,
            label = "fn_lock",
        )
        val DELETE = TextKeyData(
            type = KeyType.ENTER_EDITING,
            code = KeyCode.DELETE,
            label = "delete",
        )
        val DELETE_WORD = TextKeyData(
            type = KeyType.ENTER_EDITING,
            code = KeyCode.DELETE_WORD,
            label = "delete_word",
        )
        val FORWARD_DELETE = TextKeyData(
            type = KeyType.ENTER_EDITING,
            code = KeyCode.FORWARD_DELETE,
            label = "forward_delete",
        )
        val FORWARD_DELETE_WORD = TextKeyData(
            type = KeyType.ENTER_EDITING,
            code = KeyCode.FORWARD_DELETE_WORD,
            label = "forward_delete_word",
        )
        val SHIFT = TextKeyData(
            type = KeyType.MODIFIER,
            code = KeyCode.SHIFT,
            label = "shift",
        )
        val CAPS_LOCK = TextKeyData(
            type = KeyType.MODIFIER,
            code = KeyCode.CAPS_LOCK,
            label = "caps_lock",
        )

        val ARROW_LEFT = TextKeyData(
            type = KeyType.NAVIGATION,
            code = KeyCode.ARROW_LEFT,
            label = "arrow_left",
        )
        val ARROW_RIGHT = TextKeyData(
            type = KeyType.NAVIGATION,
            code = KeyCode.ARROW_RIGHT,
            label = "arrow_right",
        )
        val ARROW_UP = TextKeyData(
            type = KeyType.NAVIGATION,
            code = KeyCode.ARROW_UP,
            label = "arrow_up",
        )
        val ARROW_DOWN = TextKeyData(
            type = KeyType.NAVIGATION,
            code = KeyCode.ARROW_DOWN,
            label = "arrow_down",
        )
        val MOVE_START_OF_PAGE = TextKeyData(
            type = KeyType.NAVIGATION,
            code = KeyCode.MOVE_START_OF_PAGE,
            label = "move_start_of_page",
        )
        val MOVE_END_OF_PAGE = TextKeyData(
            type = KeyType.NAVIGATION,
            code = KeyCode.MOVE_END_OF_PAGE,
            label = "move_end_of_page",
        )
        val MOVE_START_OF_LINE = TextKeyData(
            type = KeyType.NAVIGATION,
            code = KeyCode.MOVE_START_OF_LINE,
            label = "move_start_of_line",
        )
        val MOVE_END_OF_LINE = TextKeyData(
            type = KeyType.NAVIGATION,
            code = KeyCode.MOVE_END_OF_LINE,
            label = "move_end_of_line",
        )

        val CLIPBOARD_COPY = TextKeyData(
            type = KeyType.SYSTEM_GUI,
            code = KeyCode.CLIPBOARD_COPY,
            label = "clipboard_copy",
        )
        val CLIPBOARD_CUT = TextKeyData(
            type = KeyType.SYSTEM_GUI,
            code = KeyCode.CLIPBOARD_CUT,
            label = "clipboard_cut",
        )
        val CLIPBOARD_PASTE = TextKeyData(
            type = KeyType.SYSTEM_GUI,
            code = KeyCode.CLIPBOARD_PASTE,
            label = "clipboard_paste",
        )
        val CLIPBOARD_SELECT = TextKeyData(
            type = KeyType.SYSTEM_GUI,
            code = KeyCode.CLIPBOARD_SELECT,
            label = "clipboard_select",
        )
        val CLIPBOARD_SELECT_ALL = TextKeyData(
            type = KeyType.SYSTEM_GUI,
            code = KeyCode.CLIPBOARD_SELECT_ALL,
            label = "clipboard_select_all",
        )
        val CLIPBOARD_CLEAR_HISTORY = TextKeyData(
            type = KeyType.SYSTEM_GUI,
            code = KeyCode.CLIPBOARD_CLEAR_HISTORY,
            label = "clipboard_clear_history",
        )
        val CLIPBOARD_CLEAR_FULL_HISTORY = TextKeyData(
            type = KeyType.SYSTEM_GUI,
            code = KeyCode.CLIPBOARD_CLEAR_FULL_HISTORY,
            label = "clipboard_clear_full_history",
        )
        val CLIPBOARD_CLEAR_PRIMARY_CLIP = TextKeyData(
            type = KeyType.SYSTEM_GUI,
            code = KeyCode.CLIPBOARD_CLEAR_PRIMARY_CLIP,
            label = "clipboard_clear_primary_clip",
        )

        val TOGGLE_FLOATING_WINDOW = TextKeyData(
            type = KeyType.SYSTEM_GUI,
            code = KeyCode.TOGGLE_FLOATING_WINDOW,
            label = "toggle_floating_window",
        )
        val TOGGLE_COMPACT_LAYOUT = TextKeyData(
            type = KeyType.SYSTEM_GUI,
            code = KeyCode.TOGGLE_COMPACT_LAYOUT,
            label = "toggle_compact_layout",
        )
        val COMPACT_LAYOUT_TO_LEFT = TextKeyData(
            type = KeyType.SYSTEM_GUI,
            code = KeyCode.COMPACT_LAYOUT_TO_LEFT,
            label = "compact_layout_to_left",
        )
        val COMPACT_LAYOUT_TO_RIGHT = TextKeyData(
            type = KeyType.SYSTEM_GUI,
            code = KeyCode.COMPACT_LAYOUT_TO_RIGHT,
            label = "compact_layout_to_right",
        )
        val TOGGLE_RESIZE_MODE = TextKeyData(
            type = KeyType.SYSTEM_GUI,
            code = KeyCode.TOGGLE_RESIZE_MODE,
            label = "toggle_resize_mode",
        )

        val UNDO = TextKeyData(
            type = KeyType.SYSTEM_GUI,
            code = KeyCode.UNDO,
            label = "undo",
        )
        val REDO = TextKeyData(
            type = KeyType.SYSTEM_GUI,
            code = KeyCode.REDO,
            label = "redo",
        )

        val VIEW_CHARACTERS = TextKeyData(
            type = KeyType.SYSTEM_GUI,
            code = KeyCode.VIEW_CHARACTERS,
            label = "view_characters",
        )
        val VIEW_SYMBOLS = TextKeyData(
            type = KeyType.SYSTEM_GUI,
            code = KeyCode.VIEW_SYMBOLS,
            label = "view_symbols",
        )
        val VIEW_SYMBOLS2 = TextKeyData(
            type = KeyType.SYSTEM_GUI,
            code = KeyCode.VIEW_SYMBOLS2,
            label = "view_symbols2",
        )
        val VIEW_NUMERIC_ADVANCED = TextKeyData(
            type = KeyType.SYSTEM_GUI,
            code = KeyCode.VIEW_NUMERIC_ADVANCED,
            label = "view_numeric_advanced",
        )

        val IME_UI_MODE_TEXT = TextKeyData(
            type = KeyType.SYSTEM_GUI,
            code = KeyCode.IME_UI_MODE_TEXT,
            label = "ime_ui_mode_text",
        )
        val IME_UI_MODE_MEDIA = TextKeyData(
            type = KeyType.SYSTEM_GUI,
            code = KeyCode.IME_UI_MODE_MEDIA,
            label = "ime_ui_mode_media",
        )
        val IME_UI_MODE_CLIPBOARD = TextKeyData(
            type = KeyType.SYSTEM_GUI,
            code = KeyCode.IME_UI_MODE_CLIPBOARD,
            label = "ime_ui_mode_clipboard",
        )

        val SYSTEM_INPUT_METHOD_PICKER = TextKeyData(
            type = KeyType.FUNCTION,
            code = KeyCode.SYSTEM_INPUT_METHOD_PICKER,
            label = "system_input_method_picker",
        )
        val SHOW_SUBTYPE_PICKER = TextKeyData(
            type = KeyType.FUNCTION,
            code = KeyCode.SHOW_SUBTYPE_PICKER,
            label = "subtype_picker",
        )
        val SYSTEM_PREV_INPUT_METHOD = TextKeyData(
            type = KeyType.FUNCTION,
            code = KeyCode.SYSTEM_PREV_INPUT_METHOD,
            label = "system_prev_input_method",
        )
        val SYSTEM_NEXT_INPUT_METHOD = TextKeyData(
            type = KeyType.FUNCTION,
            code = KeyCode.SYSTEM_NEXT_INPUT_METHOD,
            label = "system_next_input_method",
        )
        val IME_SUBTYPE_PICKER = TextKeyData(
            type = KeyType.FUNCTION,
            code = KeyCode.IME_SUBTYPE_PICKER,
            label = "ime_subtype_picker",
        )
        val IME_PREV_SUBTYPE = TextKeyData(
            type = KeyType.FUNCTION,
            code = KeyCode.IME_PREV_SUBTYPE,
            label = "ime_prev_subtype",
        )
        val IME_NEXT_SUBTYPE = TextKeyData(
            type = KeyType.FUNCTION,
            code = KeyCode.IME_NEXT_SUBTYPE,
            label = "ime_next_subtype",
        )
        val LANGUAGE_SWITCH = TextKeyData(
            type = KeyType.SYSTEM_GUI,
            code = KeyCode.LANGUAGE_SWITCH,
            label = "language_switch",
        )

        val IME_SHOW_UI = TextKeyData(
            type = KeyType.FUNCTION,
            code = KeyCode.IME_SHOW_UI,
            label = "ime_show_ui",
        )
        val IME_HIDE_UI = TextKeyData(
            type = KeyType.FUNCTION,
            code = KeyCode.IME_HIDE_UI,
            label = "ime_hide_ui",
        )

        val SETTINGS = TextKeyData(
            type = KeyType.CHARACTER,
            code = KeyCode.SETTINGS,
            label = "settings",
        )

        val VOICE_INPUT = TextKeyData(
            type = KeyType.UNSPECIFIED,
            code = KeyCode.VOICE_INPUT,
            label = "voice_input",
        )

        val TOGGLE_SMARTBAR_VISIBILITY = TextKeyData(
            type = KeyType.SYSTEM_GUI,
            code = KeyCode.TOGGLE_SMARTBAR_VISIBILITY,
            label = "toggle_smartbar_visibility",
        )
        val TOGGLE_ACTIONS_OVERFLOW = TextKeyData(
            type = KeyType.SYSTEM_GUI,
            code = KeyCode.TOGGLE_ACTIONS_OVERFLOW,
            label = "toggle_actions_overflow",
        )
        val TOGGLE_ACTIONS_EDITOR = TextKeyData(
            type = KeyType.SYSTEM_GUI,
            code = KeyCode.TOGGLE_ACTIONS_EDITOR,
            label = "toggle_actions_editor",
        )
        val TOGGLE_INCOGNITO_MODE = TextKeyData(
            type = KeyType.FUNCTION,
            code = KeyCode.TOGGLE_INCOGNITO_MODE,
            label = "toggle_incognito_mode",
        )
        val TOGGLE_AUTOCORRECT = TextKeyData(
            type = KeyType.FUNCTION,
            code = KeyCode.TOGGLE_AUTOCORRECT,
            label = "toggle_autocorrect",
        )
    }
}

@Serializable
@SerialName("auto_text_key")
class AutoTextKeyData(
    override val type: KeyType = KeyType.CHARACTER,
    override val code: Int = KeyCode.UNSPECIFIED,
    override val label: String = "",
    override val groupId: Int = KeyData.GROUP_DEFAULT,
) : KeyData {
    @Transient private val state = AutoLetterState()

    override fun asString(isForDisplay: Boolean): String {
        return asString(this, isForDisplay)
    }

    override fun toString(): String {
        return "${AutoTextKeyData::class.simpleName} { type=$type code=$code label=\"$label\" groupId=$groupId }"
    }

    private inner class AutoLetterState {
        private var locale: DriftLocale = DriftLocale.ROOT
        var lower: TextKeyData = TextKeyData.UNSPECIFIED
            private set
        var upper: TextKeyData = TextKeyData.UNSPECIFIED
            private set

        fun recomputeIfNecessary(locale: DriftLocale) {
            if (this.locale == locale) return
            this.locale = locale
            lower = TextKeyData(
                type,
                UCharacter.toString(code).lowercase(locale).codePointAt(0),
                label.lowercase(locale),
                groupId,
            )
            upper = TextKeyData(
                type,
                UCharacter.toString(code).uppercase(locale).codePointAt(0),
                label.uppercase(locale),
                groupId,
            )
        }
    }
}

@Serializable
@SerialName("multi_text_key")
class MultiTextKeyData(
    override val type: KeyType = KeyType.CHARACTER,
    val codePoints: IntArray = intArrayOf(),
    override val label: String = "",
    override val groupId: Int = KeyData.GROUP_DEFAULT,
) : KeyData {
    @Transient override val code: Int = KeyCode.MULTIPLE_CODE_POINTS

    override fun asString(isForDisplay: Boolean): String {
        return buildString {
            if (isForDisplay) {
                append(label)
            } else {
                for (codePoint in codePoints) {
                    try { appendCodePoint(codePoint) } catch (_: Throwable) { }
                }
            }
        }
    }

    override fun toString(): String {
        return "${MultiTextKeyData::class.simpleName} { type=$type code=$code label=\"$label\" groupId=$groupId }"
    }
}

internal fun asString(data: KeyData, isForDisplay: Boolean) : String {
    return buildString {
        if (isForDisplay || data.code == KeyCode.URI_COMPONENT_TLD || data.code < KeyCode.SPACE) {
            if (Unicode.isNonSpacingMark(data.code) && !data.label.startsWith("◌")) {
                append("◌")
            }
            append(data.label)
        } else {
            try { appendCodePoint(data.code) } catch (_: Throwable) { }
        }
    }
}
