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

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Class for an `extension.json` file, which serves as a configuration of an extension
 * package for Drift (`.flex` archive files).
 *
 * Should multiple files exist which match the regex, always the first match will be used.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ExtensionMeta(
    val id: String,

    val version: String,

    val title: String,

    val description: String? = null,

    val keywords: List<String>? = null,

    val homepage: String? = null,

    val issueTracker: String? = null,

    /**
     * A list of maintainers who (actively) worked on putting the content together for this extension. Note that
     * the actual author of each file within the extension (theme, layout, e.g.) may be different and is specified
     * in the file meta itself.
     */
    @JsonNames("authors")
    val maintainers: List<ExtensionMaintainer>,

    /**
     * A valid license identifier, according to the [SPDX license list](https://spdx.org/licenses/).
     * Use an SPDX license expression if this extension has multiple licenses.
     */
    val license: String,
) {
    fun getUpdateJsonPair(): String {
        return "\"${id}\":\"${version}\""
    }
}
