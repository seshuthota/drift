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

package io.github.seshuthota.drift.ime.keyboard3.extension

import android.net.Uri
import io.github.seshuthota.drift.ime.io.DriftRef
import io.github.seshuthota.drift.ime.io.Storage
import io.github.seshuthota.drift.ime.io.readText
import io.github.seshuthota.drift.lib.DriftLocale
import io.github.seshuthota.drift.lib.devtools.flogError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.k3lp.lib.meta.source.SourceFileRef
import org.k3lp.lib.meta.source.TextSourceFile
import org.k3lp.lib.text.WillRequireMigrationToRichErrors
import org.k3lp.model.K3CompileResult
import org.k3lp.model.K3ImpliedImports
import org.k3lp.model.K3ImportResolver
import org.k3lp.model.K3Model
import org.k3lp.model.compileToModel

private val FOUNDATION_KEYBOARD_FILES = listOf(
    "keyboard/qwerty.xml",
    "keyboard/qwertz.xml",
)

@OptIn(WillRequireMigrationToRichErrors::class)
suspend fun loadFoundationKeyboards(storage: Storage): List<K3Model> {
    suspend fun loadAssetFile(path: String): TextSourceFile {
        val xml = withContext(Dispatchers.IO) {
            val ref = DriftRef.assets("extensions/io.github.seshuthota.drift.k3.foundation/$path")
            storage.readText(ref)
        }
        val sourceFile = TextSourceFile(object : SourceFileRef {
            override fun toString(): String {
                return "toString()"
            }
        }, xml)
        return sourceFile
    }
    val importResolver = K3ImportResolver { path, _ ->
        val relPath = "import/" + runCatching { Uri.parse(path).path?.removePrefix("/") ?: "" }.getOrDefault(path)
        loadAssetFile(relPath)
    }
    val impliedImports = K3ImpliedImports(
        displays = listOf("flex://io.github.seshuthota.drift.k3.foundation/displays-implied.xml"),
        keys = listOf("flex://io.github.seshuthota.drift.k3.foundation/keys-implied.xml"),
    )
    return FOUNDATION_KEYBOARD_FILES.mapNotNull { path ->
        val result = loadAssetFile(path).compileToModel(importResolver, impliedImports)
        for (report in result.reports) {
            flogError { report.cause?.stackTraceToString() ?: "" }
        }
        (result as? K3CompileResult.Success)?.model
    }
}

fun selectFoundationKeyboard(
    keyboards: List<K3Model>,
    primaryLocale: DriftLocale?,
): K3Model? {
    val fallback = keyboards.firstOrNull { it.supportsLanguage("en") } ?: keyboards.firstOrNull()
    if (primaryLocale == null) {
        return fallback
    }
    val language = primaryLocale.language.lowercase()
    return keyboards.firstOrNull { it.supportsLanguage(language) } ?: fallback
}

private fun K3Model.supportsLanguage(language: String): Boolean = locales.any { tag ->
    runCatching { DriftLocale.fromTag(tag).language.lowercase() }.getOrNull() == language
}
