/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
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

import android.annotation.SuppressLint
import android.content.Context
import androidx.emoji2.text.DefaultEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat
import io.github.seshuthota.drift.lib.devtools.flogError
import io.github.seshuthota.drift.lib.devtools.flogInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

object DriftEmojiCompat {
    private lateinit var instanceNoReplace: InstanceHandler
    private lateinit var instanceReplaceAll: InstanceHandler

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Initialize this helper and its EmojiCompat instances with given [context]. Immediately begins loading the emoji
     * metadata in a background thread. After this method has been called, it is safe to call [getAsFlow].
     */
    fun init(context: Context) {
        instanceNoReplace = InstanceHandler(context, replaceAll = false)
        instanceReplaceAll = InstanceHandler(context, replaceAll = true)

        scope.launch {
            instanceNoReplace.load()
        }
        scope.launch {
            instanceReplaceAll.load()
        }
    }

    /**
     * Gets the current EmojiCompat instance based on [replaceAll] and sets it as the default instance if
     * [setAsDefaultInstance] is true. Calling this method before [init] will cause an exception to be thrown.
     *
     * @return A state flow providing the latest EmojiCompat instance for given args. The flow may provide null if
     *  EmojiCompat is still loading or if it has failed.
     */
    @SuppressLint("RestrictedApi")
    fun getAsFlow(replaceAll: Boolean, setAsDefaultInstance: Boolean = true): StateFlow<EmojiCompat?> {
        val instanceFlow = if (replaceAll) {
            instanceReplaceAll.publishedInstanceFlow
        } else {
            instanceNoReplace.publishedInstanceFlow
        }
        val instance = instanceFlow.value
        if (setAsDefaultInstance && instance != null) {
            flogInfo { "Set default EmojiCompat instance to $instance(replaceAll=$replaceAll)" }
            EmojiCompat.reset(instance)
        }
        return instanceFlow
    }

    private class InstanceHandler(context: Context, replaceAll: Boolean = false) {
        private val initCallback: EmojiCompat.InitCallback = object : EmojiCompat.InitCallback() {
            override fun onInitialized() {
                super.onInitialized()
                flogInfo { "EmojiCompat(replaceAll=$replaceAll) successfully loaded!" }
                publishedInstanceFlow.value = instance
            }

            override fun onFailed(throwable: Throwable?) {
                super.onFailed(throwable)
                flogError { "EmojiCompat(replaceAll=$replaceAll) failed to load: $throwable" }
            }
        }

        private val config: EmojiCompat.Config? = DefaultEmojiCompatConfig.create(context)?.apply {
            setReplaceAll(replaceAll)
            setMetadataLoadStrategy(EmojiCompat.LOAD_STRATEGY_MANUAL)
            registerInitCallback(initCallback)
        }

        // Despite its name, `EmojiCompat.reset()` actually creates a new instance, exactly what we need
        private val instance: EmojiCompat? = if (config != null) EmojiCompat.reset(config) else null
        val publishedInstanceFlow = MutableStateFlow<EmojiCompat?>(null)

        /**
         * Manually loads the EmojiCompat instance. Call this method on a background thread to avoid blocking main.
         *
         * @see EmojiCompat.load
         */
        fun load() {
            instance?.load()
        }
    }
}
