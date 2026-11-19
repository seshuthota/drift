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

package io.github.seshuthota.drift.app

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideIn
import androidx.compose.animation.slideOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import androidx.core.net.toUri
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import io.github.seshuthota.drift.app.devtools.AndroidLocalesScreen
import io.github.seshuthota.drift.app.devtools.AndroidSettingsScreen
import io.github.seshuthota.drift.app.devtools.DevtoolsScreen
import io.github.seshuthota.drift.app.devtools.ExportDebugLogScreen
import io.github.seshuthota.drift.app.ext.CheckUpdatesScreen
import io.github.seshuthota.drift.app.ext.ExtensionDetailScreen
import io.github.seshuthota.drift.app.ext.ExtensionEditScreen
import io.github.seshuthota.drift.app.ext.ExtensionImportScreen
import io.github.seshuthota.drift.app.ext.ExtensionImportScreenType
import io.github.seshuthota.drift.app.settings.HomeScreen
import io.github.seshuthota.drift.app.settings.about.AboutScreen
import io.github.seshuthota.drift.app.settings.about.ProjectLicenseScreen
import io.github.seshuthota.drift.app.settings.about.ThirdPartyLicensesScreen
import io.github.seshuthota.drift.app.settings.advanced.BackupScreen
import io.github.seshuthota.drift.app.settings.advanced.OtherScreen
import io.github.seshuthota.drift.app.settings.advanced.PhysicalKeyboardScreen
import io.github.seshuthota.drift.app.settings.advanced.RestoreScreen
import io.github.seshuthota.drift.app.settings.clipboard.ClipboardScreen
import io.github.seshuthota.drift.app.settings.dictionary.DictionaryScreen
import io.github.seshuthota.drift.app.settings.dictionary.UserDictionaryScreen
import io.github.seshuthota.drift.app.settings.dictionary.UserDictionaryType
import io.github.seshuthota.drift.app.settings.gestures.GesturesScreen
import io.github.seshuthota.drift.app.settings.keyboard.FnKeyScreen
import io.github.seshuthota.drift.app.settings.keyboard.InputFeedbackScreen
import io.github.seshuthota.drift.app.settings.keyboard.KeyboardScreen
import io.github.seshuthota.drift.app.settings.localization.LocalizationScreen
import io.github.seshuthota.drift.app.settings.localization.SelectLocaleScreen
import io.github.seshuthota.drift.app.settings.media.MediaScreen
import io.github.seshuthota.drift.app.settings.smartbar.SmartbarScreen
import io.github.seshuthota.drift.app.settings.theme.ThemeScreen
import io.github.seshuthota.drift.app.settings.theme.ThemeSelectionScreen
import io.github.seshuthota.drift.app.settings.theme.ThemeSelectionScreenAction
import io.github.seshuthota.drift.app.settings.typing.TypingScreen
import io.github.seshuthota.drift.app.settings.voice.VoiceInputScreen
import io.github.seshuthota.drift.app.setup.SetupScreen
import io.github.seshuthota.drift.ime.extension.ExtensionType
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Deeplink(val path: String)

inline fun <reified T : Any> NavGraphBuilder.composableWithDeepLink(
    kClass: KClass<T>,
    noinline content: @Composable (AnimatedContentScope.(NavBackStackEntry) -> Unit),
) {
    val deeplink = requireNotNull(kClass.annotations.firstOrNull { it is Deeplink } as? Deeplink) {
        "faulty class: $kClass with annotations ${kClass.annotations}"
    }
    composable<T>(
        deepLinks = listOf(navDeepLink<T>(basePath = "ui://drift/${deeplink.path}")),
        content = content,
    )
}

object Routes {
    object Setup {
        @Serializable
        data class Screen(
            val hasRestoredBackup: Boolean = false,
        )
    }

    object Settings {
        @Serializable
        @Deeplink("settings/home")
        object Home

        @Serializable
        @Deeplink("settings/localization")
        object Localization

        @Serializable
        @Deeplink("settings/localization/select-locale")
        object SelectLocale

        @Serializable
        @Deeplink("settings/theme")
        object Theme

        @Serializable
        @Deeplink("settings/theme/selection")
        data class ThemeSelection(val action: ThemeSelectionScreenAction)

        @Serializable
        @Deeplink("settings/keyboard")
        object Keyboard {
            @Serializable
            @Deeplink("settings/keyboard/fn-key")
            object FnKey

            @Serializable
            @Deeplink("settings/keyboard/input-feedback")
            object InputFeedback
        }

        @Serializable
        @Deeplink("settings/smartbar")
        object Smartbar

        @Serializable
        @Deeplink("settings/typing")
        object Typing

        @Serializable
        @Deeplink("settings/voice")
        object VoiceInput

        @Serializable
        @Deeplink("settings/dictionary")
        object Dictionary

        @Serializable
        @Deeplink("settings/dictionary/user-dictionary")
        data class UserDictionary(val type: UserDictionaryType)

        @Serializable
        @Deeplink("settings/gestures")
        object Gestures

        @Serializable
        @Deeplink("settings/clipboard")
        object Clipboard

        @Serializable
        @Deeplink("settings/media")
        object Media

        @Serializable
        @Deeplink("settings/other")
        object Other

        @Serializable
        @Deeplink("settings/other/physical-keyboard")
        object PhysicalKeyboard

        @Serializable
        @Deeplink("settings/other/backup")
        object Backup

        @Serializable
        @Deeplink("settings/other/restore")
        data class Restore(val isSetup: Boolean = false)

        @Serializable
        @Deeplink("settings/about")
        object About

        @Serializable
        @Deeplink("settings/about/project-license")
        object ProjectLicense

        @Serializable
        @Deeplink("settings/about/third-party-licenses")
        object ThirdPartyLicenses
    }

    object Devtools {
        @Serializable
        @Deeplink("devtools")
        object Home

        @Serializable
        @Deeplink("devtools/android/locales")
        object AndroidLocales

        @Serializable
        @Deeplink("devtools/android/settings")
        data class AndroidSettings(val name: String)

        @Serializable
        @Deeplink("export-debug-log")
        object ExportDebugLog
    }

    object Ext {
        @Serializable
        @Deeplink("ext/detail")
        data class Detail(val id: String)

        @Serializable
        @Deeplink("ext/edit")
        data class Edit(val id: String?, val type: ExtensionType)

        @Serializable
        @Deeplink("ext/import")
        data class Import(val type: ExtensionImportScreenType, val givenUriStr: String? = null)

        @Serializable
        @Deeplink("ext/check-updates")
        object CheckUpdates
    }

    @Composable
    fun AppNavHost(
        modifier: Modifier,
        navController: NavHostController,
        startDestination: KClass<*>,
    ) {
        NavHost(
            modifier = modifier,
            navController = navController,
            startDestination = startDestination,
            enterTransition = {
                slideIn { IntOffset(it.width, 0) } + fadeIn()
            },
            exitTransition = {
                slideOut { IntOffset(-it.width, 0) } + fadeOut()
            },
            popEnterTransition = {
                EnterTransition.None
            } ,
            popExitTransition = {
                scaleOut(
                    targetScale = 0.85F,
                    transformOrigin = TransformOrigin(pivotFractionX = 0.8f, pivotFractionY = 0.5f)
                ) + fadeOut(spring(stiffness = Spring.StiffnessMedium))
            },
            predictivePopEnterTransition = {
                EnterTransition.None
            },
            predictivePopExitTransition = {
                scaleOut(
                    targetScale = 0.85F,
                    transformOrigin = TransformOrigin(pivotFractionX = 0.8f, pivotFractionY = 0.5f)
                ) + fadeOut(spring(stiffness = Spring.StiffnessMedium))
            },
        ) {
            composable<Setup.Screen> { navBackStack ->
                val payload = navBackStack.toRoute<Setup.Screen>()
                SetupScreen(payload.hasRestoredBackup)
            }

            composableWithDeepLink(Settings.Home::class) { HomeScreen() }

            composableWithDeepLink(Settings.Localization::class) { LocalizationScreen() }
            composableWithDeepLink(Settings.SelectLocale::class) { SelectLocaleScreen() }

            composableWithDeepLink(Settings.Theme::class) { ThemeScreen() }
            composableWithDeepLink(Settings.ThemeSelection::class) { navBackStack ->
                val payload = navBackStack.toRoute<Settings.ThemeSelection>()
                ThemeSelectionScreen(payload.action)
            }

            composableWithDeepLink(Settings.Keyboard::class) { KeyboardScreen() }
            composableWithDeepLink(Settings.Keyboard.FnKey::class) { FnKeyScreen() }
            composableWithDeepLink(Settings.Keyboard.InputFeedback::class) { InputFeedbackScreen() }

            composableWithDeepLink(Settings.Smartbar::class) { SmartbarScreen() }

            composableWithDeepLink(Settings.Typing::class) { TypingScreen() }

            composableWithDeepLink(Settings.VoiceInput::class) { VoiceInputScreen() }

            composableWithDeepLink(Settings.Dictionary::class) { DictionaryScreen() }
            composableWithDeepLink(Settings.UserDictionary::class) { navBackStack ->
                val payload = navBackStack.toRoute<Settings.UserDictionary>()
                UserDictionaryScreen(payload.type)
            }

            composableWithDeepLink(Settings.Gestures::class) { GesturesScreen() }

            composableWithDeepLink(Settings.Clipboard::class) { ClipboardScreen() }

            composableWithDeepLink(Settings.Media::class) { MediaScreen() }

            composableWithDeepLink(Settings.Other::class) { OtherScreen() }
            composableWithDeepLink(Settings.PhysicalKeyboard::class) { PhysicalKeyboardScreen() }
            composableWithDeepLink(Settings.Backup::class) { BackupScreen() }
            composableWithDeepLink(Settings.Restore::class) { navBackStack ->
                val payload = navBackStack.toRoute<Settings.Restore>()
                RestoreScreen(payload.isSetup)
            }

            composableWithDeepLink(Settings.About::class) { AboutScreen() }
            composableWithDeepLink(Settings.ProjectLicense::class) { ProjectLicenseScreen() }
            composableWithDeepLink(Settings.ThirdPartyLicenses::class) { ThirdPartyLicensesScreen() }

            composableWithDeepLink(Devtools.Home::class) { DevtoolsScreen() }
            composableWithDeepLink(Devtools.AndroidLocales::class) { AndroidLocalesScreen() }
            composableWithDeepLink(Devtools.AndroidSettings::class) { navBackStack ->
                val payload = navBackStack.toRoute<Devtools.AndroidSettings>()
                AndroidSettingsScreen(payload.name)
            }
            composableWithDeepLink(Devtools.ExportDebugLog::class) { ExportDebugLogScreen() }

            composableWithDeepLink(Ext.Detail::class) { navBackStack ->
                val payload = navBackStack.toRoute<Ext.Detail>()
                ExtensionDetailScreen(payload.id)
            }
            composableWithDeepLink(Ext.Edit::class) { navBackStack ->
                val payload = navBackStack.toRoute<Ext.Edit>()
                val extensionId = payload.id
                val type = payload.type
                ExtensionEditScreen(extensionId, type)
            }
            composableWithDeepLink(Ext.Import::class) { navBackStack ->
                val payload = navBackStack.toRoute<Ext.Import>()
                val givenUriList = if (payload.givenUriStr != null) {
                    listOf(payload.givenUriStr.toUri())
                } else null
                ExtensionImportScreen(payload.type, givenUriList)
            }
            composableWithDeepLink(Ext.CheckUpdates::class) {
                CheckUpdatesScreen()
            }
        }
    }
}
