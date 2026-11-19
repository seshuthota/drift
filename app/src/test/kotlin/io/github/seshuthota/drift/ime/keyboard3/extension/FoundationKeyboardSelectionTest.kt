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

import io.github.seshuthota.drift.lib.DriftLocale
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.k3lp.lib.meta.source.SourceFileRef
import org.k3lp.lib.meta.source.TextSourceFile
import org.k3lp.lib.text.WillRequireMigrationToRichErrors
import org.k3lp.model.K3CompileResult
import org.k3lp.model.K3ImpliedImports
import org.k3lp.model.K3ImportResolver
import org.k3lp.model.K3Model
import org.k3lp.model.compileToModel
import java.io.File

@OptIn(WillRequireMigrationToRichErrors::class)
class FoundationKeyboardSelectionTest : FunSpec({
    val extensionDir = File("src/main/assets/extensions/io.github.seshuthota.drift.k3.foundation")

    fun assetFile(path: String): TextSourceFile {
        val xml = File(extensionDir, path).readText()
        return TextSourceFile(object : SourceFileRef {
            override fun toString() = path
        }, xml)
    }

    val importResolver = K3ImportResolver { path, _ -> assetFile("import/" + path.substringAfterLast('/')) }
    val impliedImports = K3ImpliedImports(
        displays = listOf("flex://io.github.seshuthota.drift.k3.foundation/displays-implied.xml"),
        keys = listOf("flex://io.github.seshuthota.drift.k3.foundation/keys-implied.xml"),
    )

    suspend fun compile(path: String): K3Model {
        val result = assetFile(path).compileToModel(importResolver, impliedImports)
        (result as? K3CompileResult.Success).shouldNotBeNull()
        return result.model
    }

    val qwerty = runBlocking { compile("keyboard/qwerty.xml") }
    val qwertz = runBlocking { compile("keyboard/qwertz.xml") }
    val keyboards = listOf(qwerty, qwertz)

    test("foundation keyboards declare their language") {
        qwerty.locales shouldBe listOf("en")
        qwertz.locales shouldBe listOf("de")
    }

    test("english subtype selects QWERTY") {
        selectFoundationKeyboard(keyboards, DriftLocale.from("en", "US")) shouldBe qwerty
    }

    test("german subtype selects QWERTZ") {
        selectFoundationKeyboard(keyboards, DriftLocale.from("de", "DE")) shouldBe qwertz
    }

    test("language match ignores region") {
        selectFoundationKeyboard(keyboards, DriftLocale.from("de", "AT")) shouldBe qwertz
    }

    test("unknown language falls back to english") {
        selectFoundationKeyboard(keyboards, DriftLocale.from("fr", "FR")) shouldBe qwerty
    }

    test("touch layers leave no key slots empty") {
        for (path in listOf("keyboard/qwerty.xml", "keyboard/qwertz.xml")) {
            val xml = File(extensionDir, path).readText()
            val touch = xml.substringAfter("<layers formId=\"touch\"").substringBefore("</layers>")
            val rows = Regex("<row\\s+keys=\"([^\"]*)\"").findAll(touch).map { it.groupValues[1] }.toList()
            rows.isNotEmpty() shouldBe true
            for (row in rows) {
                withClue("$path row \"$row\"") {
                    row.split(Regex("\\s+")).contains("gap") shouldBe false
                }
            }
        }
    }
})
