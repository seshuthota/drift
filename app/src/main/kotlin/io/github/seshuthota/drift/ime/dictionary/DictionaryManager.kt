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

package io.github.seshuthota.drift.ime.dictionary

import android.content.Context
import androidx.room.Room
import io.github.seshuthota.drift.app.DriftPreferenceStore
import io.github.seshuthota.drift.ime.nlp.SuggestionCandidate
import io.github.seshuthota.drift.ime.nlp.WordSuggestionCandidate
import io.github.seshuthota.drift.lib.DriftLocale
import java.lang.ref.WeakReference

class DictionaryManager private constructor(context: Context) {
    private val applicationContext: WeakReference<Context> = WeakReference(context.applicationContext ?: context)
    private val prefs by DriftPreferenceStore

    private var driftUserDictionaryDatabase: DriftUserDictionaryDatabase? = null
    private var systemUserDictionaryDatabase: SystemUserDictionaryDatabase? = null

    companion object {
        private var defaultInstance: DictionaryManager? = null

        fun init(applicationContext: Context): DictionaryManager {
            val instance = DictionaryManager(applicationContext)
            defaultInstance = instance
            return instance
        }

        fun default(): DictionaryManager {
            val instance = defaultInstance
            if (instance != null) {
                return instance
            } else {
                throw UninitializedPropertyAccessException(
                    "${DictionaryManager::class.simpleName} has not been initialized previously. Make sure to call init(applicationContext) before using default()."
                )
            }
        }
    }

    fun queryUserDictionary(word: String, locale: DriftLocale): List<SuggestionCandidate> {
        val driftDao = driftUserDictionaryDao()
        val systemDao = systemUserDictionaryDao()
        if (driftDao == null && systemDao == null) {
            return emptyList()
        }
        return buildList {
            if (prefs.dictionary.enableDriftUserDictionary.get()) {
                driftDao?.query(word, locale)?.let {
                    for (entry in it) {
                        add(WordSuggestionCandidate(entry.word, confidence = entry.freq / 255.0))
                    }
                }
                driftDao?.queryShortcut(word, locale)?.let {
                    for (entry in it) {
                        add(WordSuggestionCandidate(entry.word, confidence = entry.freq / 255.0))
                    }
                }
            }
            if (prefs.dictionary.enableSystemUserDictionary.get()) {
                systemDao?.query(word, locale)?.let {
                    for (entry in it) {
                        add(WordSuggestionCandidate(entry.word, confidence = entry.freq / 255.0))
                    }
                }
                systemDao?.queryShortcut(word, locale)?.let {
                    for (entry in it) {
                        add(WordSuggestionCandidate(entry.word, confidence = entry.freq / 255.0))
                    }
                }
            }
        }

    }

    fun rememberWord(word: String) {
        loadUserDictionariesIfNecessary()
        val dao = driftUserDictionaryDao() ?: return
        if (dao.queryExact(word).isNotEmpty()) return
        dao.insert(UserDictionaryEntry(id = 0, word = word, freq = FREQUENCY_DEFAULT, locale = null, shortcut = null))
    }

    fun spell(word: String, locale: DriftLocale): Boolean {
        val driftDao = driftUserDictionaryDao()
        val systemDao = systemUserDictionaryDao()
        if (driftDao == null && systemDao == null) {
            return false
        }
        var ret = false
        if (prefs.dictionary.enableDriftUserDictionary.get()) {
            ret = ret || driftDao?.queryExactFuzzyLocale(word, locale)?.isNotEmpty() ?: false
            ret = ret || driftDao?.queryShortcut(word, locale)?.isNotEmpty() ?: false
        }
        if (prefs.dictionary.enableSystemUserDictionary.get()) {
            ret = ret || systemDao?.queryExactFuzzyLocale(word, locale)?.isNotEmpty() ?: false
            ret = ret || systemDao?.queryShortcut(word, locale)?.isNotEmpty() ?: false
        }
        return ret
    }

    @Synchronized
    fun driftUserDictionaryDao(): UserDictionaryDao? {
        return if (prefs.dictionary.enableDriftUserDictionary.get()) {
            driftUserDictionaryDatabase?.userDictionaryDao()
        } else {
            null
        }
    }

    @Synchronized
    fun driftUserDictionaryDatabase(): DriftUserDictionaryDatabase? {
        return if (prefs.dictionary.enableDriftUserDictionary.get()) {
            driftUserDictionaryDatabase
        } else {
            null
        }
    }

    @Synchronized
    fun systemUserDictionaryDao(): UserDictionaryDao? {
        return if (prefs.dictionary.enableSystemUserDictionary.get()) {
            systemUserDictionaryDatabase?.userDictionaryDao()
        } else {
            null
        }
    }

    @Synchronized
    fun systemUserDictionaryDatabase(): SystemUserDictionaryDatabase? {
        return if (prefs.dictionary.enableSystemUserDictionary.get()) {
            systemUserDictionaryDatabase
        } else {
            null
        }
    }

    @Synchronized
    fun loadUserDictionariesIfNecessary() {
        val context = applicationContext.get() ?: return

        if (driftUserDictionaryDatabase == null && prefs.dictionary.enableDriftUserDictionary.get()) {
            driftUserDictionaryDatabase = Room.databaseBuilder(
                context,
                DriftUserDictionaryDatabase::class.java,
                DriftUserDictionaryDatabase.DB_FILE_NAME
            ).allowMainThreadQueries().build()
        }
        if (systemUserDictionaryDatabase == null && prefs.dictionary.enableSystemUserDictionary.get()) {
            systemUserDictionaryDatabase = SystemUserDictionaryDatabase(context)
        }
    }

    @Synchronized
    fun unloadUserDictionariesIfNecessary() {
        if (driftUserDictionaryDatabase != null) {
            driftUserDictionaryDatabase?.close()
            driftUserDictionaryDatabase = null
        }
        if (systemUserDictionaryDatabase != null) {
            systemUserDictionaryDatabase = null
        }
    }
}
