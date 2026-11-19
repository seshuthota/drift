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

package io.github.seshuthota.drift.ime.keyboard3.gesture

import io.github.seshuthota.drift.ime.keyboard3.touch.TouchKey

data class GesturePoint(
    val x: Float,
    val y: Float,
    val timeMillis: Long,
)

/**
 * Direction of a swipe, quantized into eight octants of 45 degrees. The coordinate system is the
 * Android display one, so positive [SwipeDirection.DOWN] is towards larger y values.
 */
enum class SwipeDirection {
    UP_LEFT,
    UP,
    UP_RIGHT,
    RIGHT,
    DOWN_RIGHT,
    DOWN,
    DOWN_LEFT,
    LEFT;

    val isCardinal: Boolean
        get() = this == UP || this == DOWN || this == LEFT || this == RIGHT

    companion object {
        fun of(deltaX: Float, deltaY: Float): SwipeDirection {
            val angle = kotlin.math.atan2(deltaY.toDouble(), deltaX.toDouble())
            val degrees = (Math.toDegrees(angle) + 360.0) % 360.0
            val octant = ((degrees + 22.5) / 45.0).toInt() % 8
            return when (octant) {
                0 -> RIGHT
                1 -> DOWN_RIGHT
                2 -> DOWN
                3 -> DOWN_LEFT
                4 -> LEFT
                5 -> UP_LEFT
                6 -> UP
                else -> UP_RIGHT
            }
        }
    }
}

data class GestureConfig(
    val swipeDistancePx: Float,
    val swipeVelocityPxPerMs: Float,
    val glideEnabled: Boolean,
    val glideMinVelocityPxPerMs: Float,
    val glideMaxDurationMillis: Long,
    val glideMinPointSpacingFraction: Float = 0.10f,
    val shortGlideFraction: Float = 0.6f,
    val velocityWindowMillis: Long = 100L,
) {
    val unitWidthPx: Float
        get() = (swipeDistancePx / 4f).coerceAtLeast(1f)
}

sealed interface GestureSignal {
    data class GlideStarted(val pointerId: Long) : GestureSignal
    data class GlidePoint(val pointerId: Long, val x: Float, val y: Float) : GestureSignal
}

sealed interface GestureOutcome {
    data object Tap : GestureOutcome

    data class Swipe(
        val direction: SwipeDirection,
        val unitCountX: Int,
        val unitCountY: Int,
        val velocityPxPerMs: Float,
    ) : GestureOutcome

    data class Glide(
        val points: List<GesturePoint>,
    ) : GestureOutcome
}

/**
 * Consumer of recognized gestures. The tracker only runs gesture recognition when a listener is
 * supplied, so a keyboard without gesture consumers behaves exactly like a plain key grid.
 *
 * Glide coordinates are normalized to the keyboard box (0..1 in both axes), matching [GlideKey].
 */
interface GestureListener {
    fun onGlideStart() {}

    fun onGlidePoint(x: Float, y: Float) {}

    fun onGlideComplete(points: List<GesturePoint>) {}

    fun onGlideCancel()

    fun onKeyLongPress(key: TouchKey): Boolean

    fun onSwipe(direction: SwipeDirection, unitCountX: Int, unitCountY: Int, origin: TouchKey?) {}
}
