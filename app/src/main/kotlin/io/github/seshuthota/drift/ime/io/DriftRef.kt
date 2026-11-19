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

package io.github.seshuthota.drift.ime.io

import android.net.Uri
import dev.patrickgold.jetpref.datastore.model.PreferenceSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import okio.Path
import okio.Path.Companion.toPath
import org.k3lp.lib.meta.source.SourceFileRef

/**
 * A universal resource reference, capable to point to destinations within
 * Drift's app user interface screens, APK assets, cache and internal
 * storage, external resources provided to Drift via content URIs, as
 * well as hyperlinks.
 *
 * [android.net.Uri] is used as the underlying implementation for storing the
 * reference and also handles parsing of raw string URIs.
 *
 * The reference is immutable. If a change is required, consider constructing
 * a new reference with the provided builder methods.
 *
 * @property uri The underlying URI, which can be used for external references
 *  to pass along to the system.
 */
@Serializable(with = DriftRef.Serializer::class)
@JvmInline
value class DriftRef private constructor(val uri: Uri) : SourceFileRef {
    companion object {
        internal const val SCHEME_DRIFT = "drift"

        internal const val AUTHORITY_APP_UI = "app-ui"

        internal const val AUTHORITY_ASSETS = "assets"

        internal const val AUTHORITY_CACHE = "cache"

        internal const val AUTHORITY_INTERNAL = "internal"

        private const val URL_HTTP_PREFIX = "http://"
        private const val URL_HTTPS_PREFIX = "https://"
        private const val URL_MAILTO_PREFIX = "mailto:"

        private val OKIO_ROOT_PATH = "/".toPath()

        fun app(path: String) = Uri.Builder().run {
            scheme(SCHEME_DRIFT)
            authority(AUTHORITY_APP_UI)
            encodedPath(path)
            DriftRef(build())
        }

        fun assets(path: String) = Uri.Builder().run {
            scheme(SCHEME_DRIFT)
            authority(AUTHORITY_ASSETS)
            encodedPath(path)
            DriftRef(build())
        }

        fun cache(path: String) = Uri.Builder().run {
            scheme(SCHEME_DRIFT)
            authority(AUTHORITY_CACHE)
            encodedPath(path)
            DriftRef(build())
        }

        fun internal(path: String) = Uri.Builder().run {
            scheme(SCHEME_DRIFT)
            authority(AUTHORITY_INTERNAL)
            encodedPath(path)
            DriftRef(build())
        }

        fun from(uri: Uri) = DriftRef(uri)

        fun from(str: String): DriftRef {
            // First two entries only kept due to backwards-compatibility reasons.
            return when {
                str.startsWith("assets:") -> assets(str.substring(7))
                str.startsWith("internal:") -> internal(str.substring(9))
                else -> DriftRef(Uri.parse(str))
            }
        }

        fun fromUrl(url: String): DriftRef {
            return DriftRef(when {
                url.startsWith(URL_HTTP_PREFIX) ||
                    url.startsWith(URL_HTTPS_PREFIX) ||
                    url.startsWith(URL_MAILTO_PREFIX) -> Uri.parse(url)
                else -> Uri.parse("$URL_HTTPS_PREFIX$url").normalizeScheme()
            })
        }

        fun from(scheme: String, path: String) = Uri.Builder().run {
            scheme(scheme)
            encodedPath(path)
            DriftRef(build())
        }
    }

    val isAppUi: Boolean
        get() = uri.scheme == SCHEME_DRIFT && uri.authority == AUTHORITY_APP_UI

    val isAssets: Boolean
        get() = uri.scheme == SCHEME_DRIFT && uri.authority == AUTHORITY_ASSETS

    val isCache: Boolean
        get() = uri.scheme == SCHEME_DRIFT && uri.authority == AUTHORITY_CACHE

    val isInternal: Boolean
        get() = uri.scheme == SCHEME_DRIFT && uri.authority == AUTHORITY_INTERNAL

    val isExternal: Boolean
        get() = uri.scheme != SCHEME_DRIFT

    val scheme: String
        get() = uri.scheme ?: ""

    val authority: String
        get() = uri.authority ?: ""

    val okioAbsPath: Path
        get() = uri.path?.toPath(normalize = true) ?: OKIO_ROOT_PATH

    val okioRelPath: Path
        get() = okioAbsPath.relativeTo(OKIO_ROOT_PATH)

    val okioPathName: String
        get() = okioAbsPath.name

    val parentRef: DriftRef?
        get() {
            val parent = okioAbsPath.parent ?: return null
            return from(uri.buildUpon().run {
                encodedPath(parent.toString())
                build()
            })
        }

    fun subRef(relPathStr: String): DriftRef {
        val relPath = relPathStr.toPath(normalize = true)
        require(relPath.isRelative)
        val newAbsPath = (okioAbsPath / relPath).normalized()
        return from(uri.buildUpon().run {
            encodedPath(newAbsPath.toString())
            build()
        })
    }

    fun relativeTo(other: DriftRef): String {
        require(isAssets && other.isAssets || isCache && other.isCache || isInternal && other.isInternal) {
            "relativeTo behavior is only defined for assets/cache/internal refs"
        }
        val relPath = okioAbsPath.relativeTo(other.okioAbsPath)
        return relPath.toString()
    }

    override fun toString(): String {
        return uri.toString()
    }

    object Serializer : PreferenceSerializer<DriftRef>, KSerializer<DriftRef> {
        override val descriptor = PrimitiveSerialDescriptor("DriftRef", PrimitiveKind.STRING)

        override fun serialize(value: DriftRef): String {
            return value.toString()
        }

        override fun serialize(encoder: Encoder, value: DriftRef) {
            encoder.encodeString(value.toString())
        }

        override fun deserialize(value: String): DriftRef {
            return from(value)
        }

        override fun deserialize(decoder: Decoder): DriftRef {
            return from(decoder.decodeString())
        }
    }
}
