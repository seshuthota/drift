/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.seshuthota.drift.ime

import android.content.Context
import android.util.Log
import io.github.seshuthota.drift.app.DriftPreferenceStore
import io.github.seshuthota.drift.ime.keyboard3.gesture.GesturePoint
import io.github.seshuthota.drift.ime.keyboard3.gesture.GlideKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * One JSON object per line in `Android/data/<applicationId>/files/diagnostics/quality.jsonl`, pulled with adb and
 * analysed by `ml/quality/analyze.py`. The file rotates to `quality.1.jsonl` at [MAX_BYTES].
 *
 * Events (`e`):
 * - `layout`: letter key geometry in the same normalized space as glide points, written when it changes.
 * - `glide`: a committed glide: id, decode ms, candidates in order, normalized trace.
 * - `glide_pick`: an alternative from the candidate row replaced glide `id`.
 * - `glide_del`: the first backspace after glide `id`, while its word was still the last thing committed.
 * - `word`: a finished word. `how` is `typed`, `autocorrect` (with `orig`), `pick` (with `rank`, `prefix` and
 *   `kind`) or `glide`. `pred` is the next-word row that was showing when the word was started; `after_del` names a
 *   glide whose word was deleted just before.
 * - `autocorrect_undo`: backspace restored `orig` over `corrected`.
 * - `voice`: one dictation: model, duration, per-second peak dBFS and gain, the committed lines, how it ended, and
 *   the clip file when clips are kept.
 * - `voice_bs`: a backspace within [VOICE_EDIT_WINDOW_MS] of the end of a dictation (`dt` ms after it).
 *
 * All methods are cheap and thread-safe; the file is written on one background thread.
 */
object QualityLog {
    private const val TAG = "DriftQuality"
    private const val FILE_NAME = "quality.jsonl"
    private const val ROTATED_NAME = "quality.1.jsonl"
    private const val MAX_BYTES = 16L * 1024 * 1024
    const val VOICE_EDIT_WINDOW_MS = 20_000L
    private const val CLIP_DIR = "voice"
    private const val MAX_CLIPS = 30

    private val prefs by DriftPreferenceStore
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "drift-quality-log").apply { isDaemon = true }
    }
    private val lock = Any()

    @Volatile
    private var appContext: Context? = null

    private var lastGlideId = 0L
    private var pendingGlide: Long? = null
    private var deletedGlide: Long? = null
    private var predictions: List<String> = emptyList()
    private var predictionsAt = 0L
    private var lastLayout: List<GlideKey>? = null
    private var voiceEndedAt = 0L

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    fun isEnabled(): Boolean = appContext != null && runCatching { prefs.devtools.qualityLog.get() }.getOrDefault(false)

    fun keepVoiceClips(): Boolean = isEnabled() && runCatching { prefs.devtools.voiceClips.get() }.getOrDefault(false)

    /** Records the letter geometry the next glide traces refer to, once per change. */
    fun layout(keys: List<GlideKey>) {
        if (!isEnabled()) return
        synchronized(lock) {
            if (keys == lastLayout) return
            lastLayout = keys
        }
        val array = JSONArray()
        for (key in keys) {
            if (key.code !in 'a'.code..'z'.code) continue
            array.put(
                JSONArray().put(key.code.toChar().toString()).put(round(key.centerX)).put(round(key.centerY))
                    .put(round(key.width)).put(round(key.height)),
            )
        }
        write(JSONObject().put("e", "layout").put("keys", array))
    }

    /** A glide was committed as `candidates.first()`. Returns its id. */
    fun glideCommitted(points: List<GesturePoint>, candidates: List<String>, decodeMs: Long, raw: Boolean): Long {
        if (!isEnabled()) return 0L
        val id: Long
        val pred: List<String>
        val afterDelete: Long?
        synchronized(lock) {
            id = maxOf(System.currentTimeMillis(), lastGlideId + 1)
            lastGlideId = id
            pred = predictions
            afterDelete = deletedGlide
            deletedGlide = null
            pendingGlide = id
            predictions = emptyList()
        }
        val trace = JSONArray()
        for (point in points) trace.put(JSONArray().put(round(point.x)).put(round(point.y)))
        write(
            JSONObject().put("e", "glide").put("id", id).put("ms", decodeMs).put("raw", raw)
                .put("words", JSONArray(candidates)).put("pts", trace),
        )
        val word = JSONObject().put("e", "word").put("w", candidates.firstOrNull().orEmpty()).put("how", "glide")
            .put("id", id).put("pred", JSONArray(pred))
        if (afterDelete != null) word.put("after_del", afterDelete)
        write(word)
        return id
    }

    /** An alternative from the candidate row replaced the last glide's word. */
    fun glidePicked(word: String, rank: Int) {
        if (!isEnabled()) return
        val id = synchronized(lock) { pendingGlide } ?: return
        write(JSONObject().put("e", "glide_pick").put("id", id).put("w", word).put("rank", rank))
    }

    fun backspace() {
        if (!isEnabled()) return
        val now = System.currentTimeMillis()
        val glide: Long?
        val sinceVoice: Long
        synchronized(lock) {
            glide = pendingGlide
            if (glide != null) {
                deletedGlide = glide
                pendingGlide = null
            }
            sinceVoice = now - voiceEndedAt
        }
        if (glide != null) write(JSONObject().put("e", "glide_del").put("id", glide))
        if (voiceEndedAt > 0L && sinceVoice in 0..VOICE_EDIT_WINDOW_MS) {
            write(JSONObject().put("e", "voice_bs").put("dt", sinceVoice))
        }
    }

    /** The next-word row now shows [candidates] for an empty word. [requestedAt] orders racing requests. */
    fun predictionsShown(candidates: List<String>, requestedAt: Long) {
        if (!isEnabled()) return
        synchronized(lock) {
            if (requestedAt < predictionsAt) return
            predictionsAt = requestedAt
            predictions = candidates.take(3)
        }
    }

    /**
     * A word was finished by typing it ([how] `typed` or `autocorrect`) or by picking a candidate (`pick`, with its
     * [rank] in the row, the [prefix] typed before it and the candidate [kind]).
     */
    fun wordCommitted(
        word: String,
        how: String,
        original: String? = null,
        rank: Int? = null,
        prefix: String? = null,
        kind: String? = null,
    ) {
        if (!isEnabled() || word.isEmpty()) return
        val pred: List<String>
        val afterDelete: Long?
        synchronized(lock) {
            pred = predictions
            predictions = emptyList()
            afterDelete = deletedGlide
            deletedGlide = null
            pendingGlide = null
        }
        val json = JSONObject().put("e", "word").put("w", word).put("how", how).put("pred", JSONArray(pred))
        if (original != null) json.put("orig", original)
        if (rank != null) json.put("rank", rank)
        if (prefix != null) json.put("prefix", prefix)
        if (kind != null) json.put("kind", kind)
        if (afterDelete != null) json.put("after_del", afterDelete)
        write(json)
    }

    fun autocorrectUndone(original: String, corrected: String) {
        if (!isEnabled()) return
        write(JSONObject().put("e", "autocorrect_undo").put("orig", original).put("corrected", corrected))
    }

    /** One finished dictation. [pcm] is the raw microphone audio, saved as a WAV clip when clips are kept. */
    fun voice(
        model: String,
        source: String,
        durationMs: Long,
        peakDb: List<Float>,
        gain: List<Float>,
        lines: List<String>,
        ending: String,
        pcm: ShortArray?,
        sampleRate: Int,
        contextChars: Int = 0,
        refineMode: String = "off",
        refined: String? = null,
        refineMs: Long? = null,
        refineResult: String? = null,
    ) {
        if (!isEnabled()) return
        synchronized(lock) { voiceEndedAt = System.currentTimeMillis() }
        val json = JSONObject().put("e", "voice").put("model", model).put("src", source).put("ms", durationMs)
            .put("peak_db", JSONArray(peakDb.map { round(it) })).put("gain", JSONArray(gain.map { round(it) }))
            .put("lines", JSONArray(lines)).put("end", ending)
            .put("ctx_chars", contextChars).put("refine", refineMode)
        // The one-pass text, how long it took, and what happened to it (same, replaced, log_only, too_short, ...).
        if (refined != null) json.put("refined", refined)
        if (refineMs != null) json.put("refine_ms", refineMs)
        if (refineResult != null) json.put("refine_result", refineResult)
        val clipName = if (pcm != null && pcm.isNotEmpty() && keepVoiceClips()) "${System.currentTimeMillis()}.wav" else null
        if (clipName != null) json.put("clip", clipName)
        write(json) { directory ->
            if (clipName != null && pcm != null) saveClip(File(directory, CLIP_DIR), clipName, pcm, sampleRate)
        }
    }

    private fun write(json: JSONObject, extra: ((File) -> Unit)? = null) {
        val context = appContext ?: return
        json.put("t", System.currentTimeMillis())
        val line = json.toString()
        writer.execute {
            try {
                val directory = context.getExternalFilesDir("diagnostics") ?: File(context.filesDir, "diagnostics")
                directory.mkdirs()
                val file = File(directory, FILE_NAME)
                if (file.length() > MAX_BYTES) {
                    val rotated = File(directory, ROTATED_NAME)
                    rotated.delete()
                    file.renameTo(rotated)
                }
                file.appendText(line + "\n")
                extra?.invoke(directory)
            } catch (t: Throwable) {
                Log.w(TAG, "quality log write failed", t)
            }
        }
    }

    private fun saveClip(directory: File, name: String, pcm: ShortArray, sampleRate: Int) {
        directory.mkdirs()
        File(directory, name).writeBytes(wav(pcm, sampleRate))
        val clips = directory.listFiles { file -> file.name.endsWith(".wav") }?.sortedBy { it.name } ?: return
        clips.dropLast(MAX_CLIPS).forEach { it.delete() }
    }

    internal fun wav(pcm: ShortArray, sampleRate: Int): ByteArray {
        val dataBytes = pcm.size * 2
        val out = java.nio.ByteBuffer.allocate(44 + dataBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate)
            .putInt(sampleRate * 2).putShort(2).putShort(16)
        out.put("data".toByteArray()).putInt(dataBytes)
        for (sample in pcm) out.putShort(sample)
        return out.array()
    }

    private fun round(value: Float): Double = Math.round(value * 10_000.0) / 10_000.0
}
