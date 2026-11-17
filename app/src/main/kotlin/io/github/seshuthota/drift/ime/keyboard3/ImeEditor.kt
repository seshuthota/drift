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

import android.content.ClipDescription
import android.content.ContentUris
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import io.github.seshuthota.drift.DriftApplication
import io.github.seshuthota.drift.ime.clipboard.provider.ClipboardFileStorage
import io.github.seshuthota.drift.ime.clipboard.provider.ClipboardItem
import io.github.seshuthota.drift.ime.editor.DriftEditorInfo
import io.github.seshuthota.drift.ime.editor.ImeOptions
import org.k3lp.runtime.K3Editor
import org.k3lp.runtime.K3SurroundingText
import org.k3lp.runtime.K3TextRange
import java.lang.ref.WeakReference
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/** Characters to erase and characters to write for one raw-editor replace. */
internal data class RawInputEdit(val deletes: Int, val insert: String)

open class ImeEditor(
    val ic: WeakReference<InputConnection>,
    val info: DriftEditorInfo,
) : K3Editor {
    fun getSurroundingText(charsBefore: Int, charsAfter: Int): K3SurroundingText {
        val ic = ic.get() ?: return K3SurroundingText.Empty
        // TODO maybe use eet for getSurroundingText??
//        val eet = ic.getExtractedText(
//            ExtractedTextRequest().apply {
//                token = -1
//                flags = 0
//                hintMaxLines = 10
//                hintMaxChars = 10000
//            },
//            0,
//        )
//        eet.
        val textBeforeCursor = ic.getTextBeforeCursor(charsBefore, 0) ?: return K3SurroundingText.Empty
        val textAfterCursor = ic.getTextAfterCursor(charsAfter, 0) ?: return K3SurroundingText.Empty
        val textSelected = ic.getSelectedText(0) ?: ""
        //val surroundingText = TextUtils.concat(textBeforeCursor, textSelected, textAfterCursor)
        return K3SurroundingText(
            textBefore = textBeforeCursor.toString(),
            textSelected = textSelected.toString(),
            textAfter = textAfterCursor.toString(),
        )
    }

    fun getTextBeforeCursor(charsBefore: Int): String {
        val ic = ic.get() ?: return ""
        return ic.getTextBeforeCursor(charsBefore, 0)?.toString() ?: ""
    }

    // TODO this function is very heavy => we need to optimize k3lp to provide:
    //  insertAtSelection() (for simple appends)
    //  deleteAroundSelection() for bksp/fw delete
    //  in some cases replaceText() is needed due to the transform rules, but we abuse it waaaay too much
    override fun replaceText(
        range: IntRange,
        text: String,
        newSelection: K3TextRange,
        newComposition: K3TextRange?,
    ) {
        val ic = ic.get() ?: return
        if (info.isRawInputEditor) {
            // TYPE_NULL editors such as Termux keep a scratch buffer and forward all of it on
            // commitText. setComposingRegion cannot address characters already on the terminal,
            // and deleteSurroundingText's key events never erase them, so committing the whole
            // word on every key piles up (x, xxh, xxhxhz).
            replaceTextRaw(ic, range, text)
            return
        }
        if (isTextUntrusted) {
            replaceTextRelative(ic, range, text)
            return
        }
        ic.beginBatchEdit()
        ic.setComposingRegion(range.first, range.last + 1)
        ic.commitText(text, 1)
        ic.setSelection(newSelection.start, newSelection.end)
        setComposition(newComposition)
        ic.endBatchEdit()
    }

    /**
     * True while this field's latest report was its on-screen text and positions instead of its stored ones (see
     * [ImeController.isSurroundingTextTrustworthy]). ChatGPT does this for pasted links shown as chips. Offsets
     * k3 derives then point past the end of the stored text, and Compose text fields crash on an out-of-range
     * setComposingRegion or setSelection, so every edit is made relative to the cursor instead.
     */
    @Volatile
    var isTextUntrusted: Boolean = false

    /**
     * Replaces the [range] k3 edits, which ends at the cursor, without naming any position: deletes the length of
     * [range] before the cursor and commits [text] there. No composing region is kept.
     */
    private fun replaceTextRelative(ic: InputConnection, range: IntRange, text: String) {
        ic.beginBatchEdit()
        ic.finishComposingText()
        // commitText replaces a selection itself, so only the part of the range in front of it may be deleted here.
        val deletes = rawInputDeleteBefore(range) - (ic.getSelectedText(0)?.length ?: 0)
        if (deletes > 0) ic.deleteSurroundingText(deletes, 0)
        if (text.isNotEmpty()) ic.commitText(text, 1)
        ic.endBatchEdit()
    }

    /** Text k3 last put before the cursor. Used to send only the changed tail to a raw editor. */
    private var rawTextBefore: String = ""

    private fun replaceTextRaw(ic: InputConnection, range: IntRange, text: String) {
        val oldLength = rawInputDeleteBefore(range)
        val previous = rawTextBefore.takeIf { it.length == oldLength }
        commitRawEdit(ic, rawInputEdit(previous, oldLength, text))
        rawTextBefore = text
    }

    /**
     * Swaps the [oldText] tail of the text k3 last committed for [newText].
     *
     * A raw editor reports none of its own text back, so a suggestion picked from the candidates row cannot
     * locate the word with [getTextBeforeCursor] and replace it with [replaceText]. The tracked text takes
     * its place. Returns the whole tracked text after the edit, or null when it no longer ends with [oldText]
     * (the terminal moved on, e.g. after Ctrl+C or a shell rewrite).
     */
    fun replaceRawTail(oldText: String, newText: String): String? {
        val ic = ic.get() ?: return null
        val previous = rawTextBefore
        val replacement = rawTailReplacement(previous, oldText, newText) ?: return null
        commitRawEdit(ic, rawInputEdit(previous, previous.length, replacement))
        rawTextBefore = replacement
        return replacement
    }

    private fun commitRawEdit(ic: InputConnection, edit: RawInputEdit) {
        val payload = buildString {
            repeat(edit.deletes) { append(RAW_DELETE) }
            append(edit.insert)
        }
        if (payload.isNotEmpty()) {
            // One commit. Termux writes the whole scratch buffer on commitText, so the payload
            // must be only the new tail, with U+007F for each erased code point.
            ic.commitText(payload, 1)
        }
    }

    /**
     * Enter for a raw editor. Termux turns a committed `\n` into the carriage return the shell
     * accepts, including inside reverse-i-search. A KEYCODE_ENTER event never arrives there.
     */
    fun commitRawEnter() {
        rawTextBefore = ""
        ic.get()?.commitText("\n", 1)
    }

    /** One terminal erase. Used when k3's buffer is already empty but the line is not. */
    fun commitRawBackspace() {
        val ic = ic.get() ?: return
        if (rawTextBefore.isNotEmpty()) {
            val last = rawTextBefore.codePointBefore(rawTextBefore.length)
            rawTextBefore = rawTextBefore.dropLast(Character.charCount(last))
        }
        ic.commitText(RAW_DELETE.toString(), 1)
    }

    fun deleteSurroundingText(charsBefore: Int, charsAfter: Int) {
        val ic = ic.get() ?: return
        ic.deleteSurroundingText(charsBefore, charsAfter)
    }

    fun performClipboardCopy(): Boolean {
        val ic = ic.get() ?: return false
        return ic.performContextMenuAction(android.R.id.copy)
    }

    fun performClipboardCut(): Boolean {
        val ic = ic.get() ?: return false
        return ic.performContextMenuAction(android.R.id.cut)
    }

    fun performClipboardPaste(): Boolean {
        val ic = ic.get() ?: return false
        return ic.performContextMenuAction(android.R.id.paste)
    }

    /** The field's current selection, read from the editor, or null when it cannot tell. */
    fun currentSelection(): K3TextRange? {
        val ic = ic.get() ?: return null
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return null
        if (extracted.selectionStart < 0) return null
        return K3TextRange(
            start = extracted.startOffset + extracted.selectionStart,
            end = extracted.startOffset + extracted.selectionEnd,
        )
    }

    /** Whether [setLivePreview] can show text that is still changing; raw editors such as Termux cannot. */
    val supportsLivePreview: Boolean get() = !info.isRawInputEditor

    /** Ends any composing region, so a live preview never replaces a word typed before it. */
    fun finishComposing() {
        ic.get()?.finishComposingText()
    }

    /** Shows [text] as composing text, replacing the previous preview. Empty clears it. */
    fun setLivePreview(text: String) {
        val ic = ic.get() ?: return
        ic.setComposingText(text, 1)
        if (text.isEmpty()) ic.finishComposingText()
    }

    /** Commits [text] in place of the current preview (or at the cursor when there is none). */
    fun commitLiveText(text: String) {
        val ic = ic.get() ?: return
        if (info.isRawInputEditor) {
            commitRawBytes(text, dropTracked = true)
        } else {
            ic.commitText(text, 1)
        }
    }

    fun performClipboardPasteMedia(item: ClipboardItem): Boolean {
        val ic = ic.get() ?: return false
        val uri = item.uri ?: return false
        val mimeTypes = item.mimeTypes
        val id = ContentUris.parseId(uri)
        val appContext = DriftApplication.getHack() // TODO this is a hack
        val file = ClipboardFileStorage.getFileForId(appContext, id)
        if (!file.exists()) return false
        val inputContentInfo = InputContentInfoCompat(
            uri,
            ClipDescription("clipboard media file", mimeTypes.toTypedArray()),
            null,
        )
        ic.finishComposingText()
        val flags = InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
        return InputConnectionCompat.commitContent(ic, info.base, inputContentInfo, flags, null)
    }

    fun performSelectAll(): Boolean {
        val ic = ic.get() ?: return false
        ic.finishComposingText()
        return if (info.isRawInputEditor) {
            sendDownUpKeyEvent(KeyEvent.KEYCODE_A, meta(ctrl = true))
        } else {
            ic.performContextMenuAction(android.R.id.selectAll)
        }
    }

    fun performUndo(): Boolean {
        return sendDownUpKeyEvent(KeyEvent.KEYCODE_Z, meta(ctrl = true))
    }

    fun performRedo(): Boolean {
        return sendDownUpKeyEvent(KeyEvent.KEYCODE_Z, meta(ctrl = true, shift = true))
    }

    fun performEditorAction(action: ImeOptions.Action) {
        val ic = ic.get() ?: return
        ic.performEditorAction(action.toInt())
    }

    private fun meta(
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
    ): Int {
        var metaState = 0
        if (ctrl) {
            metaState = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        }
        if (alt) {
            metaState = metaState or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        }
        if (shift) {
            metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        }
        return metaState
    }

    private fun InputConnection.sendDownKeyEvent(keyCode: Int, metaState: Int, downTime: Long) {
        sendKeyEvent(
            KeyEvent(
                downTime, downTime,
                KeyEvent.ACTION_DOWN, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
    }

    private fun InputConnection.sendUpKeyEvent(keyCode: Int, metaState: Int, downTime: Long, upTime: Long) {
        sendKeyEvent(
            KeyEvent(
                downTime, upTime,
                KeyEvent.ACTION_UP, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
    }

    fun sendDownUpKeyEvent(keyCode: Int, metaState: Int = 0): Boolean {
        val connection = ic.get() ?: return false
        if (info.isRawInputEditor) {
            // Termux drops these key events. Commit the sequence its own KeyHandler would write.
            val sequence = rawKeySequence(keyCode, metaState) ?: return false
            return connection.commitText(sequence, 1)
        }
        val downTime = SystemClock.uptimeMillis()
        connection.sendDownKeyEvent(keyCode, metaState, downTime)
        val upTime = SystemClock.uptimeMillis()
        connection.sendUpKeyEvent(keyCode, metaState, downTime, upTime)
        return true
    }

    /** Commit bytes to a raw editor. [dropTracked] forgets the line Drift thinks it owns. */
    fun commitRawBytes(text: String, dropTracked: Boolean = false) {
        if (dropTracked) {
            rawTextBefore = ""
        }
        ic.get()?.commitText(text, 1)
    }

    override fun setComposition(newComposition: K3TextRange?) {
        val ic = ic.get() ?: return
        if (newComposition == null || newComposition.isCollapsed() || isTextUntrusted) {
            ic.finishComposingText()
        } else {
            ic.setComposingRegion(newComposition.start, newComposition.end)
        }
    }

    private inline fun InputConnection.batchEdit(block: () -> Unit) {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }
        beginBatchEdit()
        block()
        endBatchEdit()
    }

    companion object {
        /**
         * How many characters before the cursor [range] covers.
         *
         * k3 passes `offset until selection`, which is empty when nothing has been typed yet.
         * An empty [IntRange] still has `first > last`, so the length has to go through [IntRange.isEmpty].
         */
        internal fun rawInputDeleteBefore(range: IntRange): Int {
            if (range.isEmpty()) return 0
            return range.last - range.first + 1
        }

        /**
         * Edit that turns the previous raw-editor text into [newText].
         *
         * [previous] is only trusted when it has [oldLength] characters. Otherwise the span length
         * is the only fact available: a longer string contributes its new tail, a shorter one
         * contributes that many deletes.
         */
        internal fun rawInputEdit(previous: String?, oldLength: Int, newText: String): RawInputEdit {
            if (previous != null && previous.length == oldLength) {
                val common = previous.commonPrefixWith(newText).length
                val removed = previous.substring(common)
                return RawInputEdit(
                    deletes = removed.codePointCount(0, removed.length),
                    insert = newText.substring(common),
                )
            }
            if (newText.length >= oldLength) {
                return RawInputEdit(deletes = 0, insert = newText.substring(oldLength.coerceAtLeast(0)))
            }
            return RawInputEdit(deletes = oldLength - newText.length, insert = "")
        }

        /**
         * Tracked text after replacing the [oldText] tail of [previous] with [newText], or null when that tail
         * is not there anymore. The tail must be non-empty, as an empty one could match anywhere.
         */
        internal fun rawTailReplacement(previous: String, oldText: String, newText: String): String? {
            if (oldText.isEmpty() || !previous.endsWith(oldText)) return null
            return previous.dropLast(oldText.length) + newText
        }

        /** DEL. Termux's shell erases on this byte; deleteSurroundingText key events do not land. */
        internal const val RAW_DELETE = '\u007f'

        /** True when a backspace did not shorten k3's text, so the terminal still needs an erase. */
        internal fun rawBackspaceNeedsExtraDelete(lengthBefore: Int, lengthAfter: Int): Boolean {
            return lengthAfter >= lengthBefore
        }

        /**
         * Bytes a `TYPE_NULL` terminal should receive instead of [keyCode].
         *
         * Sequences match Termux `KeyHandler` with cursor-application mode off (a normal shell).
         * Ctrl+Z is absent on purpose: that byte suspends the foreground job.
         * Returns null when this key has no terminal equivalent.
         */
        internal fun rawKeySequence(keyCode: Int, metaState: Int = 0): String? {
            val shift = metaState and (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON or
                KeyEvent.META_SHIFT_RIGHT_ON) != 0
            val alt = metaState and (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON or
                KeyEvent.META_ALT_RIGHT_ON) != 0
            val ctrl = metaState and (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or
                KeyEvent.META_CTRL_RIGHT_ON) != 0
            val mod = when {
                shift && alt && ctrl -> 8
                alt && ctrl -> 7
                shift && ctrl -> 6
                ctrl -> 5
                shift && alt -> 4
                alt -> 3
                shift -> 2
                else -> 0
            }
            fun csi(final: Char, bare: String): String {
                return if (mod == 0) bare else "\u001b[1;$mod$final"
            }
            fun tilde(code: Int): String {
                return if (mod == 0) "\u001b[$code~" else "\u001b[$code;$mod~"
            }
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> csi('A', "\u001b[A")
                KeyEvent.KEYCODE_DPAD_DOWN -> csi('B', "\u001b[B")
                KeyEvent.KEYCODE_DPAD_RIGHT -> csi('C', "\u001b[C")
                KeyEvent.KEYCODE_DPAD_LEFT -> csi('D', "\u001b[D")
                KeyEvent.KEYCODE_MOVE_HOME -> csi('H', "\u001b[H")
                KeyEvent.KEYCODE_MOVE_END -> csi('F', "\u001b[F")
                KeyEvent.KEYCODE_PAGE_UP -> tilde(5)
                KeyEvent.KEYCODE_PAGE_DOWN -> tilde(6)
                KeyEvent.KEYCODE_FORWARD_DEL -> tilde(3)
                KeyEvent.KEYCODE_DEL -> RAW_DELETE.toString()
                KeyEvent.KEYCODE_ESCAPE -> "\u001b"
                KeyEvent.KEYCODE_TAB -> if (shift) "\u001b[Z" else "\t"
                KeyEvent.KEYCODE_A -> if (ctrl && mod == 5) "\u0001" else null
                else -> null
            }
        }

        val Disconnected = ImeEditor(
            ic = WeakReference(null),
            info = DriftEditorInfo.Unspecified,
        )
    }
}
