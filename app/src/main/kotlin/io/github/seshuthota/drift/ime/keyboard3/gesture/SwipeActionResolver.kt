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

package io.github.seshuthota.drift.ime.keyboard3.gesture

import io.github.seshuthota.drift.ime.ImeUiMode
import io.github.seshuthota.drift.ime.keyboard3.ImeActions
import org.k3lp.lib.text.K3Descriptor
import kotlin.math.abs
import kotlin.math.max

enum class EditorKey {
    LEFT,
    RIGHT,
    UP,
    DOWN,
    LINE_START,
    LINE_END,
    PAGE_UP,
    PAGE_DOWN,
}

sealed interface ActionOp {
    data class Descriptor(val descriptor: K3Descriptor) : ActionOp

    data class Text(val text: String) : ActionOp

    data class Backspace(val count: Int, val wordWise: Boolean) : ActionOp

    data class Key(
        val key: EditorKey,
        val count: Int,
        val shift: Boolean = false,
        val wordWise: Boolean = false,
    ) : ActionOp

    data object Shift : ActionOp
}

object SwipeActionResolver {
    fun resolve(
        action: SwipeAction,
        unitCountX: Int,
        unitCountY: Int,
        uiMode: ImeUiMode,
    ): List<ActionOp> {
        val horizontal = max(1, abs(unitCountX))
        val vertical = max(1, abs(unitCountY))
        return when (action) {
            SwipeAction.NO_ACTION -> emptyList()
            SwipeAction.CYCLE_TO_NEXT_KEYBOARD_MODE -> listOf(
                ActionOp.Descriptor(descriptorFor(nextMode(uiMode))),
            )
            SwipeAction.CYCLE_TO_PREVIOUS_KEYBOARD_MODE -> listOf(
                ActionOp.Descriptor(descriptorFor(previousMode(uiMode))),
            )
            SwipeAction.DELETE_CHARACTER -> listOf(ActionOp.Backspace(count = 1, wordWise = false))
            SwipeAction.DELETE_CHARACTERS_PRECISELY -> listOf(ActionOp.Backspace(horizontal, wordWise = false))
            SwipeAction.DELETE_WORD -> listOf(ActionOp.Backspace(count = 1, wordWise = true))
            SwipeAction.DELETE_WORDS_PRECISELY -> listOf(ActionOp.Backspace(horizontal, wordWise = true))
            SwipeAction.HIDE_KEYBOARD -> listOf(ActionOp.Descriptor(ImeActions.HideImeWindow))
            SwipeAction.INSERT_SPACE -> listOf(ActionOp.Text(" "))
            SwipeAction.MOVE_CURSOR_LEFT -> listOf(ActionOp.Key(EditorKey.LEFT, horizontal))
            SwipeAction.MOVE_CURSOR_RIGHT -> listOf(ActionOp.Key(EditorKey.RIGHT, horizontal))
            SwipeAction.MOVE_CURSOR_UP -> listOf(ActionOp.Key(EditorKey.UP, vertical))
            SwipeAction.MOVE_CURSOR_DOWN -> listOf(ActionOp.Key(EditorKey.DOWN, vertical))
            SwipeAction.MOVE_CURSOR_START_OF_LINE -> listOf(ActionOp.Key(EditorKey.LINE_START, 1))
            SwipeAction.MOVE_CURSOR_END_OF_LINE -> listOf(ActionOp.Key(EditorKey.LINE_END, 1))
            SwipeAction.MOVE_CURSOR_START_OF_PAGE -> listOf(ActionOp.Key(EditorKey.PAGE_UP, 1))
            SwipeAction.MOVE_CURSOR_END_OF_PAGE -> listOf(ActionOp.Key(EditorKey.PAGE_DOWN, 1))
            SwipeAction.REDO -> listOf(ActionOp.Descriptor(ImeActions.Redo))
            SwipeAction.SELECT_CHARACTERS_PRECISELY -> listOf(
                ActionOp.Key(if (unitCountX >= 0) EditorKey.RIGHT else EditorKey.LEFT, horizontal, shift = true),
            )
            SwipeAction.SELECT_WORDS_PRECISELY -> listOf(
                ActionOp.Key(
                    if (unitCountX >= 0) EditorKey.RIGHT else EditorKey.LEFT,
                    horizontal,
                    shift = true,
                    wordWise = true,
                ),
            )
            SwipeAction.SHIFT -> listOf(ActionOp.Shift)
            SwipeAction.SHOW_INPUT_METHOD_PICKER -> listOf(ActionOp.Descriptor(ImeActions.ShowInputMethodPicker))
            SwipeAction.SHOW_SUBTYPE_PICKER -> listOf(ActionOp.Descriptor(ImeActions.ShowSubtypePicker))
            SwipeAction.SWITCH_TO_PREV_SUBTYPE -> listOf(ActionOp.Descriptor(ImeActions.SwitchToPrevSubtype))
            SwipeAction.SWITCH_TO_NEXT_SUBTYPE -> listOf(ActionOp.Descriptor(ImeActions.SwitchToNextSubtype))
            SwipeAction.SWITCH_TO_CLIPBOARD_CONTEXT -> listOf(ActionOp.Descriptor(ImeActions.ShowClipboardPanel))
            SwipeAction.SWITCH_TO_MEDIA_CONTEXT -> listOf(ActionOp.Descriptor(ImeActions.ShowMediaPanel))
            SwipeAction.SWITCH_TO_PREV_KEYBOARD -> listOf(ActionOp.Descriptor(ImeActions.SwitchToPrevInputMethod))
            SwipeAction.TOGGLE_COMPACT_LAYOUT -> listOf(ActionOp.Descriptor(ImeActions.ToggleCompactLayout))
            SwipeAction.TOGGLE_SMARTBAR_VISIBILITY ->
                listOf(ActionOp.Descriptor(ImeActions.ToggleSmartbarVisibility))
            SwipeAction.UNDO -> listOf(ActionOp.Descriptor(ImeActions.Undo))
        }
    }

    private fun nextMode(mode: ImeUiMode): ImeUiMode {
        val modes = ImeUiMode.entries
        return modes[(modes.indexOf(mode) + 1) % modes.size]
    }

    private fun previousMode(mode: ImeUiMode): ImeUiMode {
        val modes = ImeUiMode.entries
        return modes[(modes.indexOf(mode) - 1 + modes.size) % modes.size]
    }

    private fun descriptorFor(mode: ImeUiMode): K3Descriptor {
        return when (mode) {
            ImeUiMode.TEXT -> ImeActions.ShowTextPanel
            ImeUiMode.MEDIA -> ImeActions.ShowMediaPanel
            ImeUiMode.CLIPBOARD -> ImeActions.ShowClipboardPanel
        }
    }
}
