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

package io.github.seshuthota.drift.lib.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import io.github.seshuthota.drift.BuildConfig
import io.github.seshuthota.drift.lib.devtools.flogDebug
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import io.github.seshuthota.drift.lib.android.AndroidSettings
import io.github.seshuthota.drift.lib.android.AndroidVersion
import io.github.seshuthota.drift.lib.android.systemServiceOrNull
import kotlin.time.Duration.Companion.milliseconds

private const val DELIMITER = ':'
private const val IME_SERVICE_CLASS_NAME = "io.github.seshuthota.drift.DriftImeService"
private val TIMED_QUERY_DELAY = 500.milliseconds

object InputMethodUtils {
    fun isDriftboardEnabled(context: Context): Boolean {
        return if (AndroidVersion.ATLEAST_API34_U) {
            context.systemServiceOrNull(InputMethodManager::class)
                ?.enabledInputMethodList
                ?.any { it.packageName == BuildConfig.APPLICATION_ID } ?: false
        } else {
            val enabledImeList = AndroidSettings.Secure.getString(
                context, Settings.Secure.ENABLED_INPUT_METHODS
            )
            enabledImeList != null && parseIsDriftboardEnabled(context, enabledImeList)
        }
    }

    fun isDriftboardSelected(context: Context): Boolean {
        return if (AndroidVersion.ATLEAST_API34_U) {
            context.systemServiceOrNull(InputMethodManager::class)
                ?.currentInputMethodInfo
                ?.packageName == BuildConfig.APPLICATION_ID
        } else {
            val selectedIme = AndroidSettings.Secure.getString(
                context, Settings.Secure.DEFAULT_INPUT_METHOD
            )
            selectedIme != null && parseIsDriftboardSelected(context, selectedIme)
        }
    }

    @Composable
    fun observeIsDriftboardEnabled(
        context: Context = LocalContext.current.applicationContext,
    ): State<Boolean> {
        return observeIsDriftboardEnabledFlow(context).collectAsState(isDriftboardEnabled(context))
    }

    @Composable
    fun observeIsDriftboardSelected(
        context: Context = LocalContext.current.applicationContext,
    ): State<Boolean> {
        return observeIsDriftboardSelectedFlow(context).collectAsState(isDriftboardSelected(context))
    }

    fun observeIsDriftboardEnabledFlow(context: Context): Flow<Boolean> {
        val appContext = context.applicationContext
        return flow {
            while (true) {
                emit(isDriftboardEnabled(appContext))
                delay(TIMED_QUERY_DELAY)
            }
        }.distinctUntilChanged()
    }

    fun observeIsDriftboardSelectedFlow(context: Context): Flow<Boolean> {
        val appContext = context.applicationContext
        return flow {
            while (true) {
                emit(isDriftboardSelected(appContext))
                delay(TIMED_QUERY_DELAY)
            }
        }.distinctUntilChanged()
    }

    fun parseIsDriftboardEnabled(context: Context, activeImeIds: String): Boolean {
        flogDebug { activeImeIds }
        return activeImeIds.split(DELIMITER).map { componentStr ->
            ComponentName.unflattenFromString(componentStr)
        }.any { it?.packageName == context.packageName && it.className == IME_SERVICE_CLASS_NAME }
    }

    fun parseIsDriftboardSelected(context: Context, selectedImeId: String): Boolean {
        flogDebug { selectedImeId }
        val component = ComponentName.unflattenFromString(selectedImeId)
        return component?.packageName == context.packageName && component.className == IME_SERVICE_CLASS_NAME
    }

    fun showImeEnablerActivity(context: Context) {
        val intent = Intent()
        intent.action = Settings.ACTION_INPUT_METHOD_SETTINGS
        intent.addCategory(Intent.CATEGORY_DEFAULT)
        context.startActivity(intent)
    }

    fun showImePicker(context: Context): Boolean {
        val imm = context.systemServiceOrNull(InputMethodManager::class)
        return if (imm != null) {
            imm.showInputMethodPicker()
            true
        } else {
            false
        }
    }
}
