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

package io.github.seshuthota.drift.ime.clipboard.provider

import android.content.Context
import android.net.Uri
import io.github.seshuthota.drift.lib.devtools.LogTopic
import io.github.seshuthota.drift.lib.devtools.flogDebug
import io.github.seshuthota.drift.lib.android.readToFile
import io.github.seshuthota.drift.lib.kotlin.io.FsFile
import io.github.seshuthota.drift.lib.kotlin.io.subFile

object ClipboardFileStorage {
    const val CLIPBOARD_FILES_PATH = "clipboard_files"

    private val Context.clipboardFilesDir: FsFile
        get() = FsFile(this.noBackupFilesDir, "clipboard_files").also { it.mkdirs() }

    @Synchronized
    fun cloneUri(context: Context, uri: Uri): Long {
        val id = System.nanoTime()
        val file = context.clipboardFilesDir.subFile(id.toString())
        context.contentResolver.readToFile(uri, file)
        return id
    }

    fun deleteById(context: Context, id: Long) {
        flogDebug(LogTopic.CLIPBOARD) { "Cleaning up $id" }
        val file = context.clipboardFilesDir.subFile(id.toString())
        file.delete()
    }

    fun getFileForId(context: Context, id: Long): FsFile {
        return context.clipboardFilesDir.subFile(id.toString())
    }


    fun insertFileFromBackupIfNotExisting(context: Context, file: FsFile) {
        if (!context.clipboardFilesDir.subFile(file.name).isFile) {
            file.copyTo(context.clipboardFilesDir.subFile(file.name), overwrite = false)
        }
    }

    fun resetClipboardFileStorage(context: Context) {
        context.clipboardFilesDir.listFiles()?.forEach {
            it.delete()
        }
    }

}
