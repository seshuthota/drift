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

package io.github.seshuthota.drift.ime.asr

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest

class AsrModelManagerTest : FunSpec({
    test("the registry rows are well formed") {
        val ids = AsrModelRegistry.models.map { it.id }
        ids.toSet() shouldHaveSize ids.size
        AsrModelRegistry.default().id shouldBe AsrModelRegistry.DEFAULT_MODEL_ID
        AsrModelRegistry.forEngine(AsrModelRegistry.MOONSHINE) shouldHaveSize AsrModelRegistry.models.size
        for (spec in AsrModelRegistry.models) {
            spec.files.isEmpty().shouldBeFalse()
            spec.totalBytes shouldBeGreaterThan 1_000_000L
            for (file in spec.files) {
                file.sha256.length shouldBe 64
                file.sha256.all { it in '0'..'9' || it in 'a'..'f' }.shouldBeTrue()
                file.url.startsWith("https://").shouldBeTrue()
                file.bytes shouldBeGreaterThan 0L
            }
        }
    }

    test("install downloads, verifies and reports progress") {
        withTempRoot { root ->
            val content = "hello model".toByteArray()
            val spec = testSpec(content)
            val provider = FakeProvider(mapOf(spec.files.single().url to content))
            val manager = AsrModelManager(root, listOf(spec), provider)
            val progress = mutableListOf<Float>()

            val result = runBlocking { manager.install(spec) { progress += it } }

            result.isSuccess.shouldBeTrue()
            File(root, spec.id).resolve("model.bin").readBytes() shouldBe content
            provider.opened shouldContainExactly listOf(spec.files.single().url)
            progress.last() shouldBe 1f
            manager.installedModels() shouldHaveSize 1
            manager.installedOrNull(spec.id).shouldNotBeNull()
        }
    }

    test("install does not download again when the files are already there") {
        withTempRoot { root ->
            val content = "already here".toByteArray()
            val spec = testSpec(content)
            val directory = File(root, spec.id)
            directory.mkdirs()
            File(directory, "model.bin").writeBytes(content)
            val provider = FakeProvider(mapOf(spec.files.single().url to ByteArray(0)))
            val manager = AsrModelManager(root, listOf(spec), provider)

            val result = runBlocking { manager.install(spec) }

            result.isSuccess.shouldBeTrue()
            provider.opened shouldHaveSize 0
        }
    }

    test("a checksum mismatch fails the install and leaves nothing behind") {
        withTempRoot { root ->
            val expected = "the real model".toByteArray()
            val spec = testSpec(expected)
            val provider = FakeProvider(mapOf(spec.files.single().url to "corrupted".toByteArray()))
            val manager = AsrModelManager(root, listOf(spec), provider)

            val result = runBlocking { manager.install(spec) }

            result.isFailure.shouldBeTrue()
            manager.installedOrNull(spec.id) shouldBe null
            val directory = File(root, spec.id)
            File(directory, "model.bin").exists().shouldBeFalse()
            File(directory, "model.bin" + AsrModelManager.PART_SUFFIX).exists().shouldBeFalse()
        }
    }

    test("a download error fails the install and leaves no partial file") {
        withTempRoot { root ->
            val spec = testSpec("missing".toByteArray())
            val provider = object : AsrStreamProvider {
                override fun open(url: String): InputStream = throw IOException("offline")
            }
            val manager = AsrModelManager(root, listOf(spec), provider)

            val result = runBlocking { manager.install(spec) }

            result.isFailure.shouldBeTrue()
            File(File(root, spec.id), "model.bin.part").exists().shouldBeFalse()
        }
    }

    test("a model whose file has the wrong size is not installed") {
        withTempRoot { root ->
            val content = "12345".toByteArray()
            val spec = testSpec(content)
            val directory = File(root, spec.id)
            directory.mkdirs()
            File(directory, "model.bin").writeBytes("1234".toByteArray())

            AsrModelManager.isComplete(directory, spec).shouldBeFalse()
            AsrModelManager(root, listOf(spec), FakeProvider(emptyMap())).installedOrNull(spec.id) shouldBe null
        }
    }

    test("delete removes the model directory") {
        withTempRoot { root ->
            val spec = testSpec(byteArrayOf(1, 2, 3))
            val manager = AsrModelManager(root, listOf(spec), FakeProvider(emptyMap()))
            val directory = File(root, spec.id)
            directory.mkdirs()
            File(directory, "model.bin").writeBytes(byteArrayOf(1, 2, 3))

            manager.delete(spec).shouldBeTrue()
            directory.exists().shouldBeFalse()
        }
    }

    test("removeUnlisted deletes unknown model directories and undeclared files") {
        withTempRoot { root ->
            val spec = testSpec(byteArrayOf(1, 2, 3))
            val manager = AsrModelManager(root, listOf(spec), FakeProvider(emptyMap()))
            val kept = File(root, spec.id).apply { mkdirs() }
            File(kept, "model.bin").writeBytes(byteArrayOf(1, 2, 3))
            val stale = File(root, "whisper-tiny-q5_1").apply { mkdirs() }
            File(stale, "ggml-tiny-q5_1.bin").writeBytes(byteArrayOf(9))

            val undeclared = File(kept, "decoder_kv_with_attention.ort").apply { writeBytes(byteArrayOf(7)) }

            manager.removeUnlisted()

            kept.exists().shouldBeTrue()
            File(kept, "model.bin").exists().shouldBeTrue()
            undeclared.exists().shouldBeFalse()
            stale.exists().shouldBeFalse()
        }
    }
})

private class FakeProvider(private val files: Map<String, ByteArray>) : AsrStreamProvider {
    val opened = mutableListOf<String>()

    override fun open(url: String): InputStream {
        opened += url
        return files[url]?.inputStream() ?: throw IOException("no fixture for $url")
    }
}

private fun testSpec(content: ByteArray): AsrModelSpec = AsrModelSpec(
    id = "test-model",
    displayName = "Test model",
    engineId = AsrModelRegistry.MOONSHINE,
    languageTag = null,
    license = "MIT",
    attribution = "test",
    files = listOf(
        AsrModelFile(
            name = "model.bin",
            url = "https://example.test/model.bin",
            sha256 = MessageDigest.getInstance("SHA-256").digest(content)
                .joinToString("") { "%02x".format(it) },
            bytes = content.size.toLong(),
        ),
    ),
)

private fun withTempRoot(block: (File) -> Unit) {
    val root = Files.createTempDirectory("drift-asr-test").toFile()
    try {
        block(root)
    } finally {
        root.deleteRecursively()
    }
}
