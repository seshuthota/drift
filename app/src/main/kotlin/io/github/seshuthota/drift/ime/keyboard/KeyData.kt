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

package io.github.seshuthota.drift.ime.keyboard

import io.github.seshuthota.drift.ime.keyboard3.touch.InputShiftState
import io.github.seshuthota.drift.ime.text.key.KeyCode
import io.github.seshuthota.drift.ime.text.key.KeyType
import io.github.seshuthota.drift.ime.text.key.KeyVariation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

interface AbstractKeyData {
    /**
     * Returns the data described by this key as a string.
     *
     * @param isForDisplay Specifies if the returned string is intended to be displayed in a UI label (=true) or if
     *  it should be computed to be sent to an input connection (=false).
     *
     * @return The computed string for the key data object. Note: some objects may return an empty string here, meaning
     *  it is always required to check for the string's length before attempting to directly retrieve the first char.
     */
    fun asString(isForDisplay: Boolean): String
}

interface KeyData : AbstractKeyData {
    val type: KeyType
    val code: Int
    val label: String
    val groupId: Int

    companion object {
        const val GROUP_DEFAULT: Int = 0

        /**
         * Constant for the Left modifier key group. Any key belonging to this group will get the
         * popups specified for "~left" in the popup mapping.
         */
        const val GROUP_LEFT: Int = 1

        /**
         * Constant for the right modifier key group. Any key belonging to this group will get the
         * popups specified for "~right" in the popup mapping.
         */
        const val GROUP_RIGHT: Int = 2

        /**
         * Constant for the enter modifier key group. Any key belonging to this group will get the
         * popups specified for "~enter" in the popup mapping.
         */
        const val GROUP_ENTER: Int = 3

        const val GROUP_KANA: Int = 97
    }

    fun isSpaceKey(): Boolean {
        return type == KeyType.CHARACTER && (code == KeyCode.SPACE || code == KeyCode.CJK_SPACE
            || code == KeyCode.HALF_SPACE || code == KeyCode.KESHIDA)
    }
}

@Serializable
@SerialName("case_selector")
class CaseSelector(
    val lower: AbstractKeyData,
    val upper: AbstractKeyData,
) : AbstractKeyData {

    override fun asString(isForDisplay: Boolean): String {
        return ""
    }
}

@Serializable
@SerialName("shift_state_selector")
class ShiftStateSelector(
    val unshifted: AbstractKeyData? = null,
    val shifted: AbstractKeyData? = null,
    val shiftedManual: AbstractKeyData? = null,
    val shiftedAutomatic: AbstractKeyData? = null,
    val capsLock: AbstractKeyData? = null,
    val default: AbstractKeyData? = null,
) : AbstractKeyData {

    override fun asString(isForDisplay: Boolean): String {
        return ""
    }
}

@Serializable
@SerialName("variation_selector")
data class VariationSelector(
    val default: AbstractKeyData? = null,
    val email: AbstractKeyData? = null,
    val uri: AbstractKeyData? = null,
    val normal: AbstractKeyData? = null,
    val password: AbstractKeyData? = null,
) : AbstractKeyData {

    override fun asString(isForDisplay: Boolean): String {
        return ""
    }
}

@Serializable
@SerialName("layout_direction_selector")
class LayoutDirectionSelector(
    val ltr: AbstractKeyData,
    val rtl: AbstractKeyData,
) : AbstractKeyData {

    override fun asString(isForDisplay: Boolean): String {
        return ""
    }
}

@Serializable
@SerialName("char_width_selector")
class CharWidthSelector(
    val full: AbstractKeyData?,
    val half: AbstractKeyData?,
) : AbstractKeyData {

    override fun asString(isForDisplay: Boolean): String {
        return ""
    }
}

@Serializable
@SerialName("kana_selector")
class KanaSelector(
    val hira: AbstractKeyData,
    val kata: AbstractKeyData,
) : AbstractKeyData {

    override fun asString(isForDisplay: Boolean): String {
        return ""
    }
}
