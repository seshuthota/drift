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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Opens a model file for reading. Extracted so tests can serve bytes without a network. */
fun interface AsrStreamProvider {
    fun open(url: String): InputStream
}

/** Default provider: one HTTPS request per file, redirects followed (the model host redirects to its CDN). */
class HttpAsrStreamProvider(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : AsrStreamProvider {
    override fun open(url: String): InputStream {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Drift-IME")
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw IOException("HTTP $code for $url")
        }
        return object : FilterInputStream(connection.inputStream) {
            override fun close() {
                super.close()
                connection.disconnect()
            }
        }
    }
}

/**
 * Installs, verifies and removes ASR model files under `files/asr/<modelId>/`.
 *
 * A model counts as installed when every declared file exists with the exact byte size. The SHA-256 is checked
 * once, when the file is written, because hashing a 32 MB file on every listing would be wasteful. Partial
 * downloads live next to the target as `<name>.part` and are discarded on failure.
 */
class AsrModelManager(
    private val root: File,
    private val registry: List<AsrModelSpec> = AsrModelRegistry.models,
    private val streamProvider: AsrStreamProvider = HttpAsrStreamProvider(),
) {
    fun models(): List<AsrModelSpec> = registry

    fun spec(id: String): AsrModelSpec? = registry.firstOrNull { it.id == id }

    fun directoryOf(spec: AsrModelSpec): File = File(root, spec.id)

    fun installed(spec: AsrModelSpec): InstalledAsrModel? {
        val directory = directoryOf(spec)
        return if (isComplete(directory, spec)) InstalledAsrModel(spec, directory) else null
    }

    fun installedOrNull(id: String): InstalledAsrModel? = spec(id)?.let { installed(it) }

    fun installedModels(): List<InstalledAsrModel> = registry.mapNotNull { installed(it) }

    /**
     * Downloads and verifies every missing file of [spec]. [onProgress] receives 0..1 over the model's total bytes.
     * Returns the installed model, or a failure with the download or checksum error.
     */
    suspend fun install(spec: AsrModelSpec, onProgress: (Float) -> Unit = {}): Result<InstalledAsrModel> {
        val directory = directoryOf(spec)
        if (!directory.isDirectory && !directory.mkdirs()) {
            return Result.failure(IOException("cannot create ${directory.path}"))
        }
        var completed = 0L
        for (modelFile in spec.files) {
            currentCoroutineContext().ensureActive()
            val target = File(directory, modelFile.name)
            if (target.isFile && target.length() == modelFile.bytes) {
                completed += modelFile.bytes
                onProgress(progressOf(completed, spec))
                continue
            }
            val part = File(directory, modelFile.name + PART_SUFFIX)
            part.delete()
            try {
                streamProvider.open(modelFile.url).use { input ->
                    part.outputStream().buffered().use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            completed += read
                            onProgress(progressOf(completed, spec))
                        }
                    }
                }
                val actual = sha256(part)
                if (actual != modelFile.sha256) {
                    part.delete()
                    return Result.failure(
                        IllegalStateException("checksum mismatch for ${modelFile.name}: got $actual"),
                    )
                }
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                part.delete()
                return Result.failure(e)
            }
        }
        onProgress(1f)
        return Result.success(InstalledAsrModel(spec, directory))
    }

    /** Removes every file of [spec]. Returns true when nothing is left behind. */
    fun delete(spec: AsrModelSpec): Boolean {
        val directory = directoryOf(spec)
        return !directory.exists() || directory.deleteRecursively()
    }

    private fun progressOf(completed: Long, spec: AsrModelSpec): Float {
        return if (spec.totalBytes <= 0L) 0f else (completed.toFloat() / spec.totalBytes).coerceIn(0f, 1f)
    }

    companion object {
        const val PART_SUFFIX = ".part"
        private const val COPY_BUFFER_BYTES = 64 * 1024

        /** Cheap install check: every declared file exists at the exact size. Contents are verified on install. */
        fun isComplete(directory: File, spec: AsrModelSpec): Boolean {
            return spec.files.all { file ->
                val target = File(directory, file.name)
                target.isFile && target.length() == file.bytes
            }
        }

        /** Lowercase hex SHA-256 of [file]. */
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
