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

package io.github.seshuthota.drift.lib.devtools

import android.util.Log
import io.github.seshuthota.drift.lib.devtools.Flog.createTag
import io.github.seshuthota.drift.lib.devtools.Flog.getStacktraceElement
import io.github.seshuthota.drift.lib.devtools.Flog.log
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

typealias FlogTopic = UInt

typealias FlogLevel = UInt

inline fun flogError(topic: FlogTopic = Flog.TOPIC_OTHER, block: () -> String = { "" }) {
    contract {
        callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }
    if (Flog.checkShouldFlog(topic, Flog.LEVEL_ERROR)) {
        log(Flog.LEVEL_ERROR, block())
    }
}

inline fun flogWarning(topic: FlogTopic = Flog.TOPIC_OTHER, block: () -> String = { "" }) {
    contract {
        callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }
    if (Flog.checkShouldFlog(topic, Flog.LEVEL_WARNING)) {
        log(Flog.LEVEL_WARNING, block())
    }
}

inline fun flogInfo(topic: FlogTopic = Flog.TOPIC_OTHER, block: () -> String = { "" }) {
    contract {
        callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }
    if (Flog.checkShouldFlog(topic, Flog.LEVEL_INFO)) {
        log(Flog.LEVEL_INFO, block())
    }
}

inline fun flogDebug(topic: FlogTopic = Flog.TOPIC_OTHER, block: () -> String = { "" }) {
    contract {
        callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }
    if (Flog.checkShouldFlog(topic, Flog.LEVEL_DEBUG)) {
        log(Flog.LEVEL_DEBUG, block())
    }
}

private infix fun UInt.isSet(flag: UInt): Boolean {
    return (this and flag) == flag
}

/**
 * This helper object uses some parts of the Timber library to assist in
 * logging. In particular:
 *  - [createTag] (converted to Kotlin, renamed from "createStackElementTag",
 *     removed manual tagging).
 *  - [getStacktraceElement] (converted to Kotlin, renamed from "getTag",
 *     method now returns stack trace element).
 *  - [log] (only the console part, converted to Kotlin).
 * Timber is licensed under the Apache 2.0 license, see the repo here:
 *  https://github.com/JakeWharton/timber
 */
@Suppress("MemberVisibilityCanBePrivate")
object Flog {
    const val TOPIC_NONE: FlogTopic =               UInt.MIN_VALUE
    const val TOPIC_OTHER: FlogTopic =              0x80000000u
    const val TOPIC_ALL: FlogTopic =                UInt.MAX_VALUE

    const val LEVEL_NONE: FlogLevel =               UInt.MIN_VALUE
    const val LEVEL_ERROR: FlogLevel =              0x01u
    const val LEVEL_WARNING: FlogLevel =            0x02u
    const val LEVEL_INFO: FlogLevel =               0x04u
    const val LEVEL_DEBUG: FlogLevel =              0x08u
    const val LEVEL_ALL: FlogLevel =                UInt.MAX_VALUE

    private const val CALL_STACK_INDEX: Int =       3

    private const val MAX_LOG_LENGTH: Int =         4000

    private var isFloggingEnabled: Boolean = false
    private var flogTopics: FlogTopic = TOPIC_NONE
    private var flogLevels: FlogLevel = LEVEL_NONE

    /**
     * Installs the flog utility and sets the relevant configuration variables based on the
     * given config values.
     *
     * @param isFloggingEnabled If logging is enabled. If this value is false, all calls to
     *  the flog methods will be ignored and no logs will be written, regardless of the topics
     *  and levels set.
     * @param flogTopics The enabled topics for this installation. Use [TOPIC_ALL] to enable
     *  all topics. If this value is [TOPIC_NONE], this essentially disables all logging.
     * @param flogLevels The enabled levels for this installation. Use [LEVEL_ALL] to enable
     *  all levels. If this value is [LEVEL_NONE], this essentially disables all logging.
     */
    fun install(
        isFloggingEnabled: Boolean,
        flogTopics: FlogTopic,
        flogLevels: FlogLevel,
    ) {
        this.isFloggingEnabled = isFloggingEnabled
        this.flogTopics = flogTopics
        this.flogLevels = flogLevels
    }

    fun checkShouldFlog(topic: FlogTopic, level: FlogLevel): Boolean {
        return isFloggingEnabled && (flogTopics isSet topic) && (flogLevels isSet level)
    }

    private fun createTag(element: StackTraceElement): String {
        var tag = element.className
        tag = tag.substring(tag.lastIndexOf('.') + 1)
        return tag
    }

    private fun createMessage(element: StackTraceElement, msg: String): String {
        return StringBuilder().run {
            append(element.methodName)
            append('(')
            append(')')
            if (msg.isNotBlank()) {
                append(' ')
                append('-')
                append(' ')
                append(msg)
            }
            toString()
        }
    }

    private fun getStacktraceElement(): StackTraceElement {
        val stackTrace = Throwable().stackTrace
        check(stackTrace.size > CALL_STACK_INDEX) {
            "Synthetic stacktrace didn't have enough elements: are you using proguard?"
        }
        return stackTrace[CALL_STACK_INDEX]
    }

    fun log(level: FlogLevel, msg: String) {
        if (msg.length < MAX_LOG_LENGTH) {
            androidLog(level, msg)
            return
        }
        var i = 0
        val length: Int = msg.length
        while (i < length) {
            var newline: Int = msg.indexOf('\n', i)
            newline = if (newline != -1) newline else length
            do {
                val end = newline.coerceAtMost(i + MAX_LOG_LENGTH)
                val part: String = msg.substring(i, end)
                androidLog(level, part)
                i = end
            } while (i < newline)
            i++
        }
    }

    private fun androidLog(level: FlogLevel, msg: String) {
        val ste = getStacktraceElement()
        val tag = createTag(ste)
        val message = createMessage(ste, msg)
        when {
            level isSet LEVEL_ERROR ->      Log.e(tag, message)
            level isSet LEVEL_WARNING ->    Log.w(tag, message)
            level isSet LEVEL_INFO ->       Log.i(tag, message)
            level isSet LEVEL_DEBUG ->      Log.d(tag, message)
        }
    }
}
