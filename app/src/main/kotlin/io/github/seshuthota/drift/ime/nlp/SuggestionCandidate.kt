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

package io.github.seshuthota.drift.ime.nlp

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.seshuthota.drift.ime.clipboard.provider.ClipboardItem
import io.github.seshuthota.drift.ime.clipboard.provider.ItemType
import io.github.seshuthota.drift.ime.media.emoji.Emoji
import io.github.seshuthota.drift.lib.util.NetworkUtils

interface SuggestionCandidate {
    val text: CharSequence

    val secondaryText: CharSequence?

    val confidence: Double

    val isEligibleForAutoCommit: Boolean

    val isEligibleForUserRemoval: Boolean

    val icon: ImageVector?

    val sourceProvider: SuggestionProvider?
}

/**
 * Default implementation for a word candidate (autocorrect and next/current word suggestion).
 *
 * [correctsWord] is the exact typed text (as it appears before the cursor) this candidate was computed to replace;
 * autocorrect only applies an [isEligibleForAutoCommit] candidate while that text is still the word being typed.
 *
 * @see SuggestionCandidate
 */
data class WordSuggestionCandidate(
    override val text: CharSequence,
    override val secondaryText: CharSequence? = null,
    override val confidence: Double = 0.0,
    override val isEligibleForAutoCommit: Boolean = false,
    override val isEligibleForUserRemoval: Boolean = true,
    override val sourceProvider: SuggestionProvider? = null,
    val correctsWord: String? = null,
) : SuggestionCandidate {
    override val icon: ImageVector? = null
}

/**
 * A glide typing candidate. While gliding [replacesText] is null and the candidate is only a preview. After a glide
 * was committed, [replacesText] holds the exact text that was inserted (including the trailing space), so selecting
 * this candidate swaps the committed word for [text] — provided that text is still directly before the cursor.
 */
data class GlideSuggestionCandidate(
    override val text: CharSequence,
    val replacesText: String? = null,
    override val confidence: Double = 0.0,
    /** Set when the alternative shares the row with next-word predictions, to mark it as a replacement. */
    override val icon: ImageVector? = null,
) : SuggestionCandidate {
    override val secondaryText: CharSequence? = null
    override val isEligibleForAutoCommit: Boolean = false
    override val isEligibleForUserRemoval: Boolean = false
    override val sourceProvider: SuggestionProvider? = null
}

data class ClipboardSuggestionCandidate(
    val clipboardItem: ClipboardItem,
    override val sourceProvider: SuggestionProvider?,
    val context: Context,
) : SuggestionCandidate {
    override val text: CharSequence = clipboardItem.displayText(context)

    override val secondaryText: CharSequence? = null

    override val confidence: Double = 1.0

    override val isEligibleForAutoCommit: Boolean = false

    override val isEligibleForUserRemoval: Boolean = true

    override val icon: ImageVector = when (clipboardItem.type) {
        ItemType.TEXT -> when {
            NetworkUtils.isEmailAddress(text) -> Icons.Default.Email
            NetworkUtils.isUrl(text) -> Icons.Default.Link
            NetworkUtils.isPhoneNumber(text) -> Icons.Default.Phone
            else -> Icons.AutoMirrored.Outlined.Assignment
        }
        ItemType.IMAGE -> Icons.Default.Image
        ItemType.VIDEO -> Icons.Default.Videocam
    }
}

data class EmojiSuggestionCandidate(
    val emoji: Emoji,
    val showName: Boolean,
    override val confidence: Double = 1.0,
    override val isEligibleForAutoCommit: Boolean = false,
    override val isEligibleForUserRemoval: Boolean = false,
    override val icon: ImageVector? = null,
    override val sourceProvider: SuggestionProvider? = null,
) : SuggestionCandidate {
    override val text = emoji.value
    override val secondaryText = if (showName) emoji.name else null
}
