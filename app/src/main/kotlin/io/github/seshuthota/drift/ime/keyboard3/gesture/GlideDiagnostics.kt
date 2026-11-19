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

import android.content.Context
import android.util.Log
import io.github.seshuthota.drift.app.DriftPreferenceStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal object GlideDiagnostics {
    private const val FILE_NAME = "glide.jsonl"
    private const val MAX_BYTES = 8L * 1024 * 1024
    private val lock = Any()
    private val prefs by DriftPreferenceStore

    fun record(
        context: Context,
        points: List<GesturePoint>,
        decodeMillis: Long,
        suggestions: List<String>,
        rawEditor: Boolean,
    ) {
        if (!prefs.devtools.glideDiagnostics.get()) {
            return
        }
        try {
            val directory = context.getExternalFilesDir("diagnostics") ?: context.filesDir
            directory.mkdirs()
            val file = File(directory, FILE_NAME)
            synchronized(lock) {
                if (file.length() > MAX_BYTES) {
                    file.delete()
                }
                val trace = JSONArray()
                for (point in points) {
                    trace.put(JSONArray().put(round(point.x)).put(round(point.y)))
                }
                val words = JSONArray()
                suggestions.take(3).forEach { words.put(it) }
                val line = JSONObject()
                    .put("t", System.currentTimeMillis())
                    .put("ms", decodeMillis)
                    .put("raw", rawEditor)
                    .put("words", words)
                    .put("pts", trace)
                    .toString()
                file.appendText(line + "\n")
            }
        } catch (t: Throwable) {
            Log.w("DriftGlide", "diagnostics write failed", t)
        }
    }

    private fun round(value: Float): Double = (value * 10_000.0).toInt() / 10_000.0
}
