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

package io.github.seshuthota.drift.ime.keyboard3.interaction

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.k3lp.lib.text.K3StringOrDescriptor
import kotlin.time.Duration.Companion.milliseconds

internal val TestTiming = InteractionTimingOptions(
    keyRepeatTimeout = 400.milliseconds,
    keyRepeatDelay = 50.milliseconds,
    longPressTimeout = 400.milliseconds,
    multiTapTimeout = 300.milliseconds,
    multiPressTimeout = 100.milliseconds,
    doubleTapTimeout = 300.milliseconds,
)

/** Interaction controller that keeps every timing default and drops feedback and toasts. */
internal object NoOpInteractionController : InteractionController {
    override val activeSystemTimingOptions: StateFlow<InteractionTimingOptions> =
        MutableStateFlow(TestTiming)
    override val activeTimingOptions: StateFlow<InteractionTimingOptions> =
        MutableStateFlow(TestTiming)
    override val activeFeedbackOptions: StateFlow<InteractionFeedbackOptions> =
        MutableStateFlow(InteractionFeedbackOptions.Fallback)

    override fun performAudioFeedback(kind: InteractionKind, output: K3StringOrDescriptor?) = Unit
    override fun performHapticFeedback(kind: InteractionKind, output: K3StringOrDescriptor?) = Unit

    override suspend fun showToast(
        text: String,
        type: InteractionController.ToastType,
    ): InteractionController.ToastHandle = object : InteractionController.ToastHandle {
        override suspend fun hide() = Unit
    }
}
