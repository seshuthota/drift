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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class SwipeActionResolverTest : FunSpec({
    fun resolve(
        action: SwipeAction,
        unitCountX: Int = 0,
        unitCountY: Int = 0,
        uiMode: ImeUiMode = ImeUiMode.TEXT,
    ) = SwipeActionResolver.resolve(action, unitCountX, unitCountY, uiMode)

    test("no action resolves to no operations") {
        resolve(SwipeAction.NO_ACTION).shouldBeEmpty()
    }

    test("single backspace resolves once") {
        resolve(SwipeAction.DELETE_CHARACTER).shouldBe(
            listOf(ActionOp.Backspace(count = 1, wordWise = false)),
        )
    }

    test("precise backspace repeats for the swipe distance") {
        resolve(SwipeAction.DELETE_CHARACTERS_PRECISELY, unitCountX = -3) shouldBe
            listOf(ActionOp.Backspace(count = 3, wordWise = false))
    }

    test("precise delete clamps a zero distance to one step") {
        resolve(SwipeAction.DELETE_WORDS_PRECISELY, unitCountX = 0) shouldBe
            listOf(ActionOp.Backspace(count = 1, wordWise = true))
    }

    test("cursor movement keeps the swipe direction out of the key") {
        resolve(SwipeAction.MOVE_CURSOR_LEFT, unitCountX = -2) shouldBe
            listOf(ActionOp.Key(EditorKey.LEFT, count = 2))
    }

    test("selection uses shift and the swipe direction") {
        resolve(SwipeAction.SELECT_CHARACTERS_PRECISELY, unitCountX = -2) shouldBe
            listOf(ActionOp.Key(EditorKey.LEFT, count = 2, shift = true))
        resolve(SwipeAction.SELECT_WORDS_PRECISELY, unitCountX = 2) shouldBe
            listOf(ActionOp.Key(EditorKey.RIGHT, count = 2, shift = true, wordWise = true))
    }

    test("next and previous mode cycle through the ui modes") {
        val next = resolve(SwipeAction.CYCLE_TO_NEXT_KEYBOARD_MODE, uiMode = ImeUiMode.TEXT)
            .single() as ActionOp.Descriptor
        next.descriptor shouldBe ImeActions.ShowMediaPanel
        val previous = resolve(SwipeAction.CYCLE_TO_PREVIOUS_KEYBOARD_MODE, uiMode = ImeUiMode.TEXT)
            .single() as ActionOp.Descriptor
        previous.descriptor shouldBe ImeActions.ShowClipboardPanel
    }

    test("panel actions map to their descriptors") {
        val ops = resolve(SwipeAction.HIDE_KEYBOARD)
        ops.shouldHaveSize(1)
        (ops.single() as ActionOp.Descriptor).descriptor shouldBe ImeActions.HideImeWindow
    }

    test("undo and redo map to editor descriptors") {
        (resolve(SwipeAction.UNDO).single() as ActionOp.Descriptor).descriptor shouldBe ImeActions.Undo
        (resolve(SwipeAction.REDO).single() as ActionOp.Descriptor).descriptor shouldBe ImeActions.Redo
    }

    test("insert space emits a single space") {
        resolve(SwipeAction.INSERT_SPACE) shouldBe listOf(ActionOp.Text(" "))
    }

    test("smartbar visibility maps to its descriptor") {
        (resolve(SwipeAction.TOGGLE_SMARTBAR_VISIBILITY).single() as ActionOp.Descriptor).descriptor shouldBe
            ImeActions.ToggleSmartbarVisibility
    }

    test("shift toggles the shift state") {
        resolve(SwipeAction.SHIFT) shouldBe listOf(ActionOp.Shift)
    }
})
