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

package io.github.seshuthota.drift.ime.core

import android.content.Context
import io.github.seshuthota.drift.app.DriftPreferenceStore
import io.github.seshuthota.drift.lib.devtools.flogDebug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import io.github.seshuthota.drift.lib.kotlin.collectLatestIn

val SubtypeJsonConfig = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    isLenient = false
}

class SubtypeManager(context: Context) {
    private val prefs by DriftPreferenceStore
    private val scope = CoroutineScope(Dispatchers.Default)

    val subtypesFlow: StateFlow<List<Subtype>>
        field = MutableStateFlow(listOf())
    inline var subtypes
        get() = subtypesFlow.value
        private set(v) { subtypesFlow.value = v }

    val activeSubtypeFlow: StateFlow<Subtype>
        field = MutableStateFlow(Subtype.DEFAULT)
    inline var activeSubtype
        get() = activeSubtypeFlow.value
        private set(v) { activeSubtypeFlow.value = v }

    init {
        prefs.localization.subtypes.asFlow().collectLatestIn(scope) { listRaw ->
            flogDebug { listRaw }
            val list = if (listRaw.isNotBlank()) {
                SubtypeJsonConfig.decodeFromString<List<Subtype>>(listRaw)
            } else {
                emptyList()
            }
            subtypes = list
            evaluateActiveSubtype(list)
        }
    }

    private fun persistNewSubtypeList(list: List<Subtype>) = scope.launch {
        val listRaw = SubtypeJsonConfig.encodeToString(list)
        prefs.localization.subtypes.set(listRaw)
    }

    private fun evaluateActiveSubtype(list: List<Subtype>) = scope.launch {
        val activeSubtypeId = prefs.localization.activeSubtypeId.get()
        val subtype = list.find { it.id == activeSubtypeId } ?: list.firstOrNull() ?: Subtype.DEFAULT
        if (subtype.id != activeSubtypeId) {
            prefs.localization.activeSubtypeId.set(subtype.id)
        }
        activeSubtype = subtype
    }

    fun addSubtype(subtype: Subtype): Boolean {
        val subtypeToAdd = subtype.copy(id = System.currentTimeMillis())
        val subtypeList = subtypes
        if (subtypeList.find { it.equalsExcludingId(subtype) } != null) {
            return false
        }
        val newSubtypeList = subtypeList + subtypeToAdd
        persistNewSubtypeList(newSubtypeList)
        return true
    }

    fun getSubtypeById(id: Long): Subtype? {
        val subtypeList = subtypes
        return subtypeList.find { it.id == id }
    }

    fun modifySubtypeWithSameId(subtypeToModify: Subtype) {
        val subtypeList = subtypes
        val index = subtypeList.indexOfFirst { subtypeToModify.id == it.id }
        if (index >= 0 && index < subtypeList.size) {
            val newSubtypeList = subtypeList.mapIndexed { n, subtype ->
                if (n == index) {
                    subtypeToModify
                } else {
                    subtype
                }
            }
            persistNewSubtypeList(newSubtypeList)
        }
    }

    fun removeSubtype(subtypeToRemove: Subtype) {
        val subtypeList = subtypes
        val indexToRemove = subtypeList.indexOf(subtypeToRemove)
        if (indexToRemove in subtypeList.indices) {
            val newSubtypeList = subtypeList.mapIndexedNotNull { n, subtype ->
                if (n != indexToRemove) {
                    subtype
                } else {
                    null
                }
            }
            persistNewSubtypeList(newSubtypeList)
            evaluateActiveSubtype(newSubtypeList)
        }
    }

    fun switchToPrevSubtype() = scope.launch {
        val subtypeList = subtypes
        val cachedActiveSubtype = activeSubtype
        var triggerNextSubtype = false
        var newActiveSubtype: Subtype = Subtype.DEFAULT
        for (subtype in subtypeList.asReversed()) {
            if (triggerNextSubtype) {
                triggerNextSubtype = false
                newActiveSubtype = subtype
            } else if (subtype == cachedActiveSubtype) {
                triggerNextSubtype = true
            }
        }
        if (triggerNextSubtype) {
            newActiveSubtype = subtypeList.last()
        }
        prefs.localization.activeSubtypeId.set(newActiveSubtype.id)
        activeSubtype = newActiveSubtype
    }

    fun switchToNextSubtype() = scope.launch {
        val subtypeList = subtypes
        val cachedActiveSubtype = activeSubtype
        var triggerNextSubtype = false
        var newActiveSubtype: Subtype = Subtype.DEFAULT
        for (subtype in subtypeList) {
            if (triggerNextSubtype) {
                triggerNextSubtype = false
                newActiveSubtype = subtype
            } else if (subtype == cachedActiveSubtype) {
                triggerNextSubtype = true
            }
        }
        if (triggerNextSubtype) {
            newActiveSubtype = subtypeList.first()
        }
        prefs.localization.activeSubtypeId.set(newActiveSubtype.id)
        activeSubtype = newActiveSubtype
    }

    fun switchToSubtypeById(id: Long) = scope.launch {
        if (subtypes.any { it.id == id }) {
            activeSubtype = getSubtypeById(id)!!
            prefs.localization.activeSubtypeId.set(id)
        }
    }
}
