/*
 * Copyright (C) 2021-2026 The FlorisBoard Contributors
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

package io.github.seshuthota.drift

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Handler
import android.util.Log
import androidx.core.os.UserManagerCompat
import io.github.seshuthota.drift.app.DriftPreferenceModel
import io.github.seshuthota.drift.app.DriftPreferenceStore
import io.github.seshuthota.drift.app.NoBackupPreferenceModel
import io.github.seshuthota.drift.app.NoBackupPreferenceStore
import io.github.seshuthota.drift.ime.QualityLog
import io.github.seshuthota.drift.ime.asr.AsrController
import io.github.seshuthota.drift.ime.asr.AsrModelManager
import io.github.seshuthota.drift.ime.clipboard.ClipboardManager
import io.github.seshuthota.drift.ime.core.SubtypeManager
import io.github.seshuthota.drift.ime.dictionary.DictionaryManager
import io.github.seshuthota.drift.ime.extension.ExtensionController
import io.github.seshuthota.drift.ime.io.AndroidStorageController
import io.github.seshuthota.drift.ime.keyboard3.ImeController
import io.github.seshuthota.drift.ime.media.emoji.DriftEmojiCompat
import io.github.seshuthota.drift.ime.nlp.NlpManager
import io.github.seshuthota.drift.ime.nlp.latin.NextWordPredictor
import io.github.seshuthota.drift.ime.nlp.latin.PersonalNgram
import io.github.seshuthota.drift.ime.keyboard3.gesture.GlideTypingManager
import io.github.seshuthota.drift.ime.theme.SystemThemeMode
import io.github.seshuthota.drift.ime.theme.ThemeController
import io.github.seshuthota.drift.lib.crashutility.CrashUtility
import io.github.seshuthota.drift.lib.devtools.Flog
import io.github.seshuthota.drift.lib.devtools.LogTopic
import io.github.seshuthota.drift.lib.devtools.flogError
import dev.patrickgold.jetpref.datastore.runtime.initAndroid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import io.github.seshuthota.drift.lib.kotlin.io.deleteContentsRecursively
import io.github.seshuthota.drift.lib.kotlin.tryOrNull
import io.github.seshuthota.drift.libnative.dummyAdd
import java.io.File
import java.lang.ref.WeakReference

/**
 * Global weak reference for the [DriftApplication] class. This is needed as in certain scenarios an application
 * reference is needed, but the Android framework hasn't finished setting up
 */
private var DriftApplicationReference = WeakReference<DriftApplication?>(null)

// TODO this class is a mess
@Suppress("unused")
class DriftApplication : Application() {
    companion object {
        init {
            try {
                System.loadLibrary("drift_native")
            } catch (_: Exception) {
            }
        }

        fun getHack(): DriftApplication = DriftApplicationReference.get()!!
    }

    private val mainHandler by lazy { Handler(mainLooper) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs by DriftPreferenceStore
    val preferenceStoreLoaded = MutableStateFlow(false)

    lateinit var storageController: AndroidStorageController
    lateinit var extensionController: ExtensionController
    lateinit var imeController: ImeController
    lateinit var themeController: ThemeController

    val asrModelManager = lazy { AsrModelManager(File(filesDir, "asr")) }
    val asrController = lazy {
        AsrController(
            context = this,
            modelManager = asrModelManager.value,
            prefs = prefs,
            imeControllerProvider = { imeController },
        )
    }
    val clipboardManager = lazy { ClipboardManager(this) }
    val glideTypingManager = lazy { GlideTypingManager(this) }
    val nlpManager = lazy { NlpManager(this) }
    val nextWordPredictor = lazy { NextWordPredictor(this) }
    val personalNgram = lazy {
        PersonalNgram(File(filesDir, "personal/ngram.tsv")) { UserManagerCompat.isUserUnlocked(this) }
    }
    val subtypeManager = lazy { SubtypeManager(this) }

    override fun onCreate() {
        super.onCreate()
        DriftApplicationReference = WeakReference(this)

        try {
            Flog.install(
                context = this,
                isFloggingEnabled = BuildConfig.DEBUG,
                flogTopics = LogTopic.ALL,
                flogLevels = Flog.LEVEL_ALL,
                flogOutputs = Flog.OUTPUT_CONSOLE,
            )
            CrashUtility.install(this)

            storageController = AndroidStorageController(this)
            extensionController = ExtensionController(storageController)
            imeController = ImeController(
                storageController,
                clipboardManager.value,
                subtypeManager = subtypeManager.value,
                nlpManager = nlpManager,
            )
            themeController = ThemeController(
                storageController,
                extensionController,
                initialSystemThemeMode = resources.configuration.determineSystemThemeMode(),
            )

            DriftEmojiCompat.init(this)
            flogError { "dummy result: ${dummyAdd(3,4)}" }

            if (!UserManagerCompat.isUserUnlocked(this)) {
                cacheDir?.deleteContentsRecursively()
                registerReceiver(BootComplete(), IntentFilter(Intent.ACTION_USER_UNLOCKED))
                return
            }

            init()
        } catch (e: Exception) {
            CrashUtility.stageException(e)
            return
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        themeController.activeSystemThemeMode.value = newConfig.determineSystemThemeMode()
    }

    fun init() {
        cacheDir?.deleteContentsRecursively()
        scope.launch {
            val resultAppPrefs = DriftPreferenceStore.initAndroid(
                context = this@DriftApplication,
                datastoreName = DriftPreferenceModel.NAME,
            )
            Log.i("PREFS", resultAppPrefs.toString())
            val resultNoBackupPrefs = NoBackupPreferenceStore.initAndroid(
                context = this@DriftApplication,
                datastoreName = NoBackupPreferenceModel.NAME,
            )
            Log.i("PREFS", resultNoBackupPrefs.toString())
            preferenceStoreLoaded.value = true
        }
        clipboardManager.value.initializeForContext(this)
        DictionaryManager.init(this)
        QualityLog.attach(this)
    }

    private fun Configuration.determineSystemThemeMode(): SystemThemeMode {
        return when (uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> SystemThemeMode.NIGHT
            Configuration.UI_MODE_NIGHT_NO -> SystemThemeMode.DAY
            else -> SystemThemeMode.UNKNOWN
        }
    }

    private inner class BootComplete : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            if (intent.action == Intent.ACTION_USER_UNLOCKED) {
                try {
                    unregisterReceiver(this)
                } catch (e: Exception) {
                    flogError { e.toString() }
                }
                storageController.notifyUserUnlocked()
                mainHandler.post {
                    init()
                }
            }
        }
    }
}

tailrec fun Context.inferDriftApplication(): DriftApplication {
    return when (this) {
        is DriftApplication -> this
        is ContextWrapper -> when {
            this.baseContext != null -> this.baseContext.inferDriftApplication()
            else -> DriftApplicationReference.get()!!
        }
        else -> tryOrNull { this.applicationContext as DriftApplication } ?: DriftApplicationReference.get()!!
    }
}

fun Context.appContext() = lazyOf(this.inferDriftApplication())

fun Context.clipboardManager() = this.inferDriftApplication().clipboardManager

fun Context.glideTypingManager() = this.inferDriftApplication().glideTypingManager

fun Context.asrModelManager() = this.inferDriftApplication().asrModelManager

fun Context.asrController() = this.inferDriftApplication().asrController

fun Context.nlpManager() = this.inferDriftApplication().nlpManager

fun Context.nextWordPredictor() = this.inferDriftApplication().nextWordPredictor

fun Context.personalNgram() = this.inferDriftApplication().personalNgram

fun Context.subtypeManager() = this.inferDriftApplication().subtypeManager
