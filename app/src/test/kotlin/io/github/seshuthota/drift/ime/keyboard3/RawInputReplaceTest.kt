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

package io.github.seshuthota.drift.ime.keyboard3

import android.view.KeyEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RawInputReplaceTest : FunSpec({
    test("the first character deletes nothing") {
        ImeEditor.rawInputDeleteBefore(IntRange.EMPTY) shouldBe 0
        ImeEditor.rawInputDeleteBefore(0 until 0) shouldBe 0
    }

    test("a word replace covers the previous characters, not the new word") {
        ImeEditor.rawInputDeleteBefore(0 until 1) shouldBe 1
        ImeEditor.rawInputDeleteBefore(0 until 2) shouldBe 2
        ImeEditor.rawInputDeleteBefore(4 until 7) shouldBe 3
    }

    test("typing sends only the new character") {
        ImeEditor.rawInputEdit(previous = "", oldLength = 0, newText = "x") shouldBe
            RawInputEdit(deletes = 0, insert = "x")
        ImeEditor.rawInputEdit(previous = "x", oldLength = 1, newText = "xh") shouldBe
            RawInputEdit(deletes = 0, insert = "h")
        ImeEditor.rawInputEdit(previous = "xhzj", oldLength = 4, newText = "xhzjx") shouldBe
            RawInputEdit(deletes = 0, insert = "x")
    }

    test("backspace sends one delete and does not resend the word") {
        ImeEditor.rawInputEdit(previous = "xh", oldLength = 2, newText = "x") shouldBe
            RawInputEdit(deletes = 1, insert = "")
    }

    test("arrow and editing keys are terminal sequences, and ctrl+z is not sent") {
        ImeEditor.rawKeySequence(KeyEvent.KEYCODE_DPAD_UP) shouldBe "\u001b[A"
        ImeEditor.rawKeySequence(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_CTRL_ON) shouldBe "\u001b[1;5D"
        ImeEditor.rawKeySequence(KeyEvent.KEYCODE_FORWARD_DEL) shouldBe "\u001b[3~"
        ImeEditor.rawKeySequence(KeyEvent.KEYCODE_MOVE_HOME) shouldBe "\u001b[H"
        ImeEditor.rawKeySequence(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON) shouldBe "\u0001"
        ImeEditor.rawKeySequence(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON) shouldBe null
    }

    test("picking a glide alternative swaps only the glided word") {
        ImeEditor.rawTailReplacement(previous = "ls hello ", oldText = "hello ", newText = "world ") shouldBe
            "ls world "
        ImeEditor.rawTailReplacement(previous = "hello ", oldText = "hello ", newText = "world ") shouldBe "world "
    }

    test("a glide alternative is ignored once the tracked text no longer ends with the glided word") {
        ImeEditor.rawTailReplacement(previous = "ls ", oldText = "hello ", newText = "world ") shouldBe null
        ImeEditor.rawTailReplacement(previous = "", oldText = "hello ", newText = "world ") shouldBe null
        ImeEditor.rawTailReplacement(previous = "hello ", oldText = "", newText = "world ") shouldBe null
    }

    test("a glide alternative sends the erased word and the picked one") {
        val previous = "ls hello "
        val replacement = ImeEditor.rawTailReplacement(previous, oldText = "hello ", newText = "world ")
        ImeEditor.rawInputEdit(previous, previous.length, replacement!!) shouldBe
            RawInputEdit(deletes = 6, insert = "world ")
    }

    test("backspace keeps erasing when the tracked buffer no longer shrinks") {
        ImeEditor.rawBackspaceNeedsExtraDelete(lengthBefore = 4, lengthAfter = 3) shouldBe false
        ImeEditor.rawBackspaceNeedsExtraDelete(lengthBefore = 0, lengthAfter = 0) shouldBe true
        ImeEditor.rawBackspaceNeedsExtraDelete(lengthBefore = 2, lengthAfter = 2) shouldBe true
    }

    test("a reset context does not delete the command already on screen") {
        ImeEditor.rawInputEdit(previous = "ssh curious@10", oldLength = 0, newText = "x") shouldBe
            RawInputEdit(deletes = 0, insert = "x")
        ImeEditor.rawInputEdit(previous = null, oldLength = 3, newText = "xhzj") shouldBe
            RawInputEdit(deletes = 0, insert = "j")
    }
})
