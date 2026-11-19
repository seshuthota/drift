/*
 * Copyright (C) 2024-2025 The FlorisBoard Contributors
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

package io.github.seshuthota.drift.ime.media.emoji

import android.content.Context
import io.github.seshuthota.drift.lib.DriftLocale
import io.github.seshuthota.drift.lib.android.bufferedReader
import io.github.reactivecircus.cache4k.Cache
import java.util.*

private typealias EmojiDataByCategoryImpl = EnumMap<EmojiCategory, MutableList<EmojiSet>>
private typealias EmojiDataBySkinToneImpl = EnumMap<EmojiSkinTone, MutableList<Emoji>>
typealias EmojiDataByCategory = Map<EmojiCategory, List<EmojiSet>>
typealias EmojiDataBySkinTone = Map<EmojiSkinTone, List<Emoji>>

class EmojiData(
    val byCategory: EmojiDataByCategory,
    val bySkinTone: EmojiDataBySkinTone,
) {
    companion object {
        private val cache = Cache.Builder<String, EmojiData>().build()
        val Fallback = empty()

        private fun newByCategory(): EmojiDataByCategoryImpl {
            return EmojiDataByCategoryImpl(EmojiCategory::class.java).also { map ->
                for (category in EmojiCategory.entries) {
                    map[category] = mutableListOf()
                }
            }
        }

        private fun newBySkinTone(): EmojiDataBySkinToneImpl {
            return EmojiDataBySkinToneImpl(EmojiSkinTone::class.java).also { map ->
                for (skinTone in EmojiSkinTone.entries) {
                    map[skinTone] = mutableListOf()
                }
            }
        }

        fun empty(): EmojiData {
            return EmojiData(newByCategory(), newBySkinTone())
        }

        suspend fun get(context: Context, path: String): EmojiData {
            return cache.get(path) {
                loadEmojiDataMap(context, path)
            }
        }

        suspend fun get(context: Context, locale: DriftLocale): EmojiData {
            val path = resolveEmojiAssetPath(context, locale) ?: return empty()
            return get(context, path)
        }

        private fun loadEmojiDataMap(context: Context, path: String): EmojiData {
            val byCategory = newByCategory()
            val bySkinTone = newBySkinTone()

            var ec: EmojiCategory? = null
            var emojiEditorList: MutableList<Emoji>? = null

            fun commitEmojiEditorList() {
                emojiEditorList?.let { byCategory[ec]!!.add(EmojiSet(it)) }
                emojiEditorList = null
            }

            context.assets.bufferedReader(path).useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("#")) {
                    } else if (line.startsWith("[")) {
                        commitEmojiEditorList()
                        ec = EmojiCategory.entries.find { it.id == line.slice(1 until (line.length - 1)) }
                    } else if (line.trim().isEmpty() || ec == null) {
                        continue
                    } else {
                        if (!line.startsWith("\t")) {
                            commitEmojiEditorList()
                        }
                        val data = line.split(";")
                        if (data.size == 3) {
                            val base = emojiEditorList?.first()
                            val emoji = Emoji(
                                value = data[0].trim(),
                                name = base?.name ?: data[1].trim(),
                                keywords = data[2].split("|").map { it.trim() },
                            )
                            if (emojiEditorList != null) {
                                emojiEditorList!!.add(emoji)
                            } else {
                                emojiEditorList = mutableListOf(emoji)
                            }
                        }
                    }
                }
                commitEmojiEditorList()
            }

            for (category in byCategory.keys) {
                for (emojiSet in byCategory[category]!!) {
                    if (emojiSet.emojis.size == 1) {
                        val base = emojiSet.emojis.first()
                        for (skinTone in EmojiSkinTone.entries) {
                            bySkinTone[skinTone]!!.add(base)
                        }
                        continue
                    }
                    for (emoji in emojiSet.emojis) {
                        bySkinTone[emoji.skinTone]!!.add(emoji)
                    }
                }
            }

            return EmojiData(byCategory, bySkinTone)
        }

        private fun resolveEmojiAssetPath(context: Context, locale: DriftLocale): String? {
            val emojiAssets = context.assets.list("ime/media/emoji/")!!.toList()
            val makePath = { file: String -> "ime/media/emoji/$file" }
            val language = locale.language.lowercase()
            val country = locale.country.takeIf { it.isNotBlank() }
            val variant = locale.variant.takeIf { it.isNotBlank() }
            if (variant != null && country != null) {
                "${language}_${country}_${variant}.txt".takeIf { emojiAssets.contains(it) }?.let {
                    return makePath(it)
                }
            }
            if (country != null) {
                "${language}_${country}.txt".takeIf { emojiAssets.contains(it) }?.let { return makePath(it) }
            }
            "${language}.txt".takeIf { emojiAssets.contains(it) }?.let {
                return makePath(it)
            }
            return null
        }
    }
}
