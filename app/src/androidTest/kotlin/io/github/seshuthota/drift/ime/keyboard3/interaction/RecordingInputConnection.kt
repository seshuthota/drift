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

package io.github.seshuthota.drift.ime.keyboard3.interaction

import android.os.Bundle
import android.os.Handler
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo

/**
 * Minimal editor behind the IME: enough of [InputConnection] for text, composing and selection.
 *
 * A [raw] connection behaves like Termux's `TYPE_NULL` connection: it reports no text, ignores composing
 * regions, and treats every committed `U+007F` as an erase on its own line.
 */
internal class RecordingInputConnection(private val raw: Boolean = false) : InputConnection {
    val text = SpannableStringBuilder()
    val commits = mutableListOf<String>()
    private var selStart = 0
    private var selEnd = 0
    private var composingStart = -1
    private var composingEnd = -1

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
        if (raw) null else text.subSequence((selStart - n).coerceAtLeast(0), selStart)

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? =
        if (raw) null else text.subSequence(selEnd, (selEnd + n).coerceAtMost(text.length))

    override fun getSelectedText(flags: Int): CharSequence? =
        when {
            raw -> null
            selStart != selEnd -> text.subSequence(minOf(selStart, selEnd), maxOf(selStart, selEnd))
            else -> ""
        }

    override fun getCursorCapsMode(reqModes: Int): Int = 0

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (raw) return false
        val start = (minOf(selStart, selEnd) - beforeLength).coerceAtLeast(0)
        val end = (maxOf(selStart, selEnd) + afterLength).coerceAtMost(text.length)
        if (start < end) text.delete(start, end)
        selStart = start
        selEnd = start
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
        deleteSurroundingText(beforeLength, afterLength)

    override fun setComposingText(t: CharSequence, newCursorPosition: Int): Boolean {
        if (raw) return false
        replaceComposing(t, keepComposing = true)
        moveCursor(newCursorPosition)
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        if (raw) return false
        composingStart = start.coerceIn(0, text.length)
        composingEnd = end.coerceIn(composingStart, text.length)
        return true
    }

    override fun finishComposingText(): Boolean {
        if (raw) return false
        composingStart = -1
        composingEnd = -1
        return true
    }

    override fun commitText(t: CharSequence, newCursorPosition: Int): Boolean {
        commits += t.toString()
        if (raw) {
            for (char in t) {
                if (char == '\u007F') {
                    if (text.isNotEmpty()) text.delete(text.length - 1, text.length)
                } else {
                    text.append(char)
                }
            }
            return true
        }
        replaceComposing(t, keepComposing = false)
        moveCursor(newCursorPosition)
        return true
    }

    override fun commitCompletion(completion: CompletionInfo?): Boolean = false
    override fun commitCorrection(correction: CorrectionInfo?): Boolean = false

    override fun setSelection(start: Int, end: Int): Boolean {
        selStart = start.coerceIn(0, text.length)
        selEnd = end.coerceIn(0, text.length)
        return true
    }

    override fun performEditorAction(editorAction: Int): Boolean = false
    override fun performContextMenuAction(id: Int): Boolean = false
    override fun beginBatchEdit(): Boolean = true
    override fun endBatchEdit(): Boolean = true
    override fun sendKeyEvent(event: KeyEvent?): Boolean = false
    override fun clearMetaKeyStates(states: Int): Boolean = false
    override fun reportFullscreenMode(enabled: Boolean): Boolean = false
    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = false
    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false
    override fun getHandler(): Handler? = null
    override fun closeConnection() = Unit
    override fun commitContent(contentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean = false

    private fun replaceComposing(t: CharSequence, keepComposing: Boolean) {
        val start = if (composingStart >= 0) composingStart else minOf(selStart, selEnd)
        val end = if (composingEnd >= 0) composingEnd else maxOf(selStart, selEnd)
        text.replace(start, end, t)
        if (keepComposing) {
            composingStart = start
            composingEnd = start + t.length
        } else {
            composingStart = -1
            composingEnd = -1
        }
        selStart = start + t.length
        selEnd = selStart
    }

    private fun moveCursor(newCursorPosition: Int) {
        val position = (selStart + (newCursorPosition - 1).coerceAtLeast(0)).coerceIn(0, text.length)
        selStart = position
        selEnd = position
    }
}
