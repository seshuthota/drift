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

package io.github.seshuthota.drift.ime.extension

import io.github.seshuthota.drift.ime.io.DriftRef
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import io.github.seshuthota.drift.lib.snygg.color.MaterialYouFlags

class ThemeExtension(
    override val manifest: Manifest,
    override val sourceRef: DriftRef,
) : Extension<ThemeExtension.Manifest> {
    @SerialName("ime.extension.theme")
    @Serializable
    data class Manifest(
        override val meta: ExtensionMeta,
        override val dependencies: ExtensionDependencyDecl = emptyMap(),
        val themes: List<ThemeComponent>,
    ) : ExtensionManifest {
        override val type: ExtensionType
            get() = ExtensionType.THEME

        override fun components(): Sequence<ExtensionComponent> {
            return sequence {
                yieldAll(themes)
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class ThemeComponent(
        override val id: String,
        @JsonNames("label")
        override val name: String,
        override val authors: List<String>,
        @SerialName("isNight")
        val isNightTheme: Boolean = true,
        @EncodeDefault
        val materialYouFlags: MaterialYouFlags = MaterialYouFlags(),
        @SerialName("stylesheet")
        val stylesheetPath: String? = null,
    ) : ExtensionComponent {
        fun stylesheetPath(): String = stylesheetPath.takeUnless { it.isNullOrBlank() } ?: defaultStylesheetPath(id)

        companion object {
            fun defaultStylesheetPath(id: String): String {
                return "stylesheets/$id.json"
            }
        }
    }
}

fun extCoreTheme(id: String) = ExtensionComponentName(
    extensionId = "io.github.seshuthota.drift.themes",
    componentId = id,
)

fun extPreviewTheme(id: String) = ExtensionComponentName(
    extensionId = "local.themes.preview",
    componentId = id,
)
