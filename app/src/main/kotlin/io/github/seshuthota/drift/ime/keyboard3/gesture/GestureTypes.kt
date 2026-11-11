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

/**
 * A single pointer sample. Pure Kotlin on purpose so the gesture logic can be unit-tested without
 * Android or Compose on the classpath.
 */
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
        /**
         * Quantizes a displacement into one of eight directions. 0 degrees points right, 90 down,
         * 180 left and 270 up. Values exactly on an octant boundary round towards the next
         * clockwise octant.
         */
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

/**
 * Thresholds for [GestureRecognizer]. All lengths are pixels, all velocities pixels per millisecond.
 * Callers convert densities and preferences once; the recognizer never reads configuration itself.
 */
data class GestureConfig(
    /** Minimum displacement from the down position before an up is classified as a swipe. */
    val swipeDistancePx: Float,
    /** Minimum velocity on either axis before an up is classified as a swipe. */
    val swipeVelocityPxPerMs: Float,
    val glideEnabled: Boolean,
    /** Minimum velocity before a press is promoted to a glide. */
    val glideMinVelocityPxPerMs: Float,
    /** A press older than this cannot become a glide anymore. */
    val glideMaxDurationMillis: Long,
    /** Minimum distance between accepted glide points, as a fraction of the down key's width. */
    val glideMinPointSpacingFraction: Float = 0.25f,
    /**
     * Minimum release distance, as a fraction of the down key's width, for a press that never started gliding to
     * still count as a short glide between neighbouring keys. Past half a key width the finger is on another key.
     */
    val shortGlideFraction: Float = 0.6f,
    /** Time window used to estimate the release velocity. */
    val velocityWindowMillis: Long = 100L,
) {
    /** Swipe actions that need a count (cursor move, precise delete) use quarter-threshold units. */
    val unitWidthPx: Float
        get() = (swipeDistancePx / 4f).coerceAtLeast(1f)
}

/** Emitted while a glide is ongoing, so the UI can draw the trail and show previews. */
sealed interface GestureSignal {
    data class GlideStarted(val pointerId: Long) : GestureSignal
    data class GlidePoint(val pointerId: Long, val x: Float, val y: Float) : GestureSignal
}

/** Terminal classification of a single pointer, decided on release. */
sealed interface GestureOutcome {
    /** The press is a normal key press; the caller emits the down key. */
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

    /** Returns true when the key's long-press action replaced the normal key output. */
    fun onKeyLongPress(key: TouchKey): Boolean

    fun onSwipe(direction: SwipeDirection, unitCountX: Int, unitCountY: Int, origin: TouchKey?) {}
}
