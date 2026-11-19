/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
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

package io.github.seshuthota.drift.lib

import java.util.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Project-specific locale class wrapping [java.util.Locale]. The wrapping is
 * necessary to provide consistent language display names and tags across the
 * whole code base.
 * @see java.util.Locale
 */
@Serializable(with = DriftLocale.Serializer::class)
class DriftLocale private constructor(val base: Locale) {
    companion object {
        private const val DELIMITER_LANGUAGE_TAG = '-'
        private const val DELIMITER_LOCALE_TAG = '_'

        private val DELIMITER_SPLITTER = """[${DELIMITER_LANGUAGE_TAG}${DELIMITER_LOCALE_TAG}]""".toRegex()

        val ROOT = from("", "", "")

        val ENGLISH = from("en", "", "")

        fun from(javaLocale: Locale) = DriftLocale(javaLocale)

        /**
         * Constructs a new [DriftLocale] with given [language].
         *
         * @param language A two-letter language code.
         *
         * @return A new [DriftLocale].
         */
        fun from(language: String) = from(Locale(language))

        /**
         * Constructs a new [DriftLocale] with given [language] and [country].
         *
         * @param language A two-letter language code.
         * @param country A two-letter country code.
         *
         * @return A new [DriftLocale].
         */
        fun from(language: String, country: String) = from(Locale(language, country))

        /**
         * Constructs a new [DriftLocale] with given [language], [country] and [variant].
         *
         * @param language A two-letter language code.
         * @param country A two-letter country code.
         * @param variant A two-letter variant code.
         *
         * @return A new [DriftLocale].
         */
        fun from(language: String, country: String, variant: String) = from(Locale(language, country, variant))

        /**
         * Constructs a new [DriftLocale] from given [str].
         *
         * @param str Either a language or locale tag in string form.
         *
         * @return A new [DriftLocale].
         */
        fun fromTag(str: String) = when {
            str.contains(DELIMITER_SPLITTER) -> {
                val lc = str.split(DELIMITER_SPLITTER)
                if (lc.size >= 3) {
                    from(lc[0], lc[1], lc[2])
                } else {
                    from(lc[0], lc[1])
                }
            }
            else -> from(str)
        }

        fun default() = DriftLocale(Locale.getDefault())

        fun installedSystemLocales(): List<DriftLocale> = Locale.getAvailableLocales().map { from(it) }

        fun extendedAvailableLocales(): List<DriftLocale> {
            val systemLocales = installedSystemLocales()
            return systemLocales
        }
    }

    private fun buildLocaleString(delimiter: Char) = buildString {
        val language = base.language
        val country = base.country
        val variant = base.variant
        append(language)
        if (language.isNotBlank() && country.isNotBlank()) {
            append(delimiter)
        }
        append(country)
        if (country.isNotBlank() && variant.isNotBlank()) {
            append(delimiter)
        }
        append(variant)
    }

    val language: String get() = base.language

    val country: String get() = base.country

    val variant: String get() = base.variant

    val iso3Language: String get() = base.isO3Language

    val iso3Country: String get() = base.isO3Country

    val supportsCapitalization: Boolean
        get() = when (language) {
            "zh", "ko", "th", "bn", "hi" -> false
            else -> true
        }

    val supportsAutoSpace: Boolean
        get() = when (language) {
            "zh", "ko", "jp", "th" -> false
            else -> true
        }

    /**
     * Generates the language tag for this locale in the format `xx`,
     * `xx-YY` or `xx-YY-zzz` and returns it as a string.
     *
     * xx: Two-letter language code
     * YY: Two-letter country code
     * zzz: Three letter variant
     *
     * @return The language tag for this locale. May be an empty string if
     *  [language], [country] and [variant] are not specified.
     */
    fun languageTag(): String = buildLocaleString(DELIMITER_LANGUAGE_TAG)

    /**
     * Generates the locale tag for this locale in the format `xx`,
     * `xx_YY` or `xx_YY_zzz` and returns it as a string.
     *
     * xx: Two-letter language code
     * YY: Two-letter country code
     * zzz: Three letter variant
     *
     * @return The locale tag for this locale. May be an empty string if
     *  [language], [country] and [variant] are not specified.
     */
    fun localeTag(): String = buildLocaleString(DELIMITER_LOCALE_TAG)

    fun displayLanguage(locale: DriftLocale = default()): String {
        return base.getDisplayLanguage(locale.base).titlecase(locale)
    }

    fun displayCountry(locale: DriftLocale = default()): String = base.getDisplayCountry(locale.base)

    fun displayVariant(locale: DriftLocale = default()): String = base.getDisplayVariant(locale.base)

    /**
     * Returns the display name for this locale, localized to [locale] in
     * the format `Language`, `Language (Country)` or `Language (Country) \[VARIANT]`.
     *
     * @param locale The locale to use for generating the display name for
     *  this locale, or [default] if otherwise.
     *
     * @return The display name for this locale. May be an empty string if
     *  [language], [country] and [variant] are not specified.
     */
    fun displayName(locale: DriftLocale = default()) = buildString {
        val languageName = displayLanguage(locale).ifBlank { base.language }
        val countryName = displayCountry(locale).ifBlank { base.country }
        val variantName = displayVariant(locale).ifBlank { base.variant }
        append(languageName)
        if (countryName.isNotBlank()) {
            if (languageName.isNotBlank()) {
                append(' ')
            }
            append('(')
            append(countryName)
            append(')')
        }
        if (variantName.isNotBlank()) {
            if (languageName.isNotBlank() || countryName.isNotBlank()) {
                append(' ')
            }
            append('[')
            append(variantName.uppercase())
            append(']')
        }
    }

    /**
     * Generate a debug string representing this locale. Not to be confused
     * with [java.util.Locale.toString], which produces a locale tag. If such
     * tag is needed, use [localeTag].
     *
     * @return The debug representation of this locale.
     */
    override fun toString() = "DriftLocale { l=${base.language} c=${base.country} v=${base.variant} }"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DriftLocale

        if (base != other.base) return false

        return true
    }

    override fun hashCode(): Int {
        return base.hashCode()
    }

    class Serializer : KSerializer<DriftLocale> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("DriftLocale", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: DriftLocale) {
            encoder.encodeString(value.languageTag())
        }

        override fun deserialize(decoder: Decoder): DriftLocale {
            return fromTag(decoder.decodeString())
        }
    }
}

@Suppress("NOTHING_TO_INLINE")
inline fun String.lowercase(locale: DriftLocale): String = this.lowercase(locale.base)

@Suppress("NOTHING_TO_INLINE")
inline fun String.uppercase(locale: DriftLocale): String = this.uppercase(locale.base)

@Suppress("NOTHING_TO_INLINE")
inline fun String.titlecase(locale: DriftLocale = DriftLocale.ROOT): String {
    return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale.base) else it.toString() }
}
