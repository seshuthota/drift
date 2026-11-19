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

import android.view.KeyEvent
import io.github.seshuthota.drift.ime.keyboard3.ImeActions
import io.github.seshuthota.drift.ime.keyboard3.ImeController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.k3lp.lib.text.asK3String

class SwipeActionDispatcher(
    private val imeController: ImeController,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val MAX_REPEAT = 64
    }

    fun dispatch(action: SwipeAction, unitCountX: Int, unitCountY: Int) {
        if (action == SwipeAction.NO_ACTION) {
            return
        }
        val ops = SwipeActionResolver.resolve(
            action = action,
            unitCountX = unitCountX,
            unitCountY = unitCountY,
            uiMode = imeController.snapshotState().flags.imeUiMode,
        )
        if (ops.isEmpty()) {
            return
        }
        scope.launch {
            imeController.updateState {
                for (op in ops) {
                    when (op) {
                        is ActionOp.Descriptor -> emit(op.descriptor)
                        is ActionOp.Text -> emitText(op.text.asK3String())
                        is ActionOp.Backspace -> repeat(op.count.coerceIn(1, MAX_REPEAT)) {
                            if (op.wordWise) {
                                emit(ImeActions.BackspaceWord)
                            } else {
                                emitBackspace()
                            }
                        }
                        is ActionOp.Key -> repeat(op.count.coerceIn(1, MAX_REPEAT)) {
                            state.editor.sendDownUpKeyEvent(op.key.toAndroidKeyCode(), op.metaState())
                        }
                        ActionOp.Shift -> cycleInputShiftState(isDoubleTap = false)
                    }
                }
            }
        }
    }

    private fun ActionOp.Key.metaState(): Int {
        var metaState = 0
        if (shift) {
            metaState = metaState or KeyEvent.META_SHIFT_ON
        }
        if (wordWise) {
            metaState = metaState or KeyEvent.META_CTRL_ON
        }
        return metaState
    }

    private fun EditorKey.toAndroidKeyCode(): Int {
        return when (this) {
            EditorKey.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
            EditorKey.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
            EditorKey.UP -> KeyEvent.KEYCODE_DPAD_UP
            EditorKey.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
            EditorKey.LINE_START -> KeyEvent.KEYCODE_MOVE_HOME
            EditorKey.LINE_END -> KeyEvent.KEYCODE_MOVE_END
            EditorKey.PAGE_UP -> KeyEvent.KEYCODE_PAGE_UP
            EditorKey.PAGE_DOWN -> KeyEvent.KEYCODE_PAGE_DOWN
        }
    }
}
