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

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Pure per-pointer gesture state machine.
 *
 * The tracker feeds it position samples; it decides whether a press stays a tap, becomes a glide
 * (path-based typing) or ends as a directional swipe. It owns no Android or Compose types, which is
 * what makes the classification testable without a device.
 *
 * Sampling contract: [onDown] once, then zero or more [onMove] calls in chronological order, then
 * exactly one [onUp] or [onCancel]. Samples may be dropped by the platform; the machine must not
 * break on gaps.
 */
class GestureRecognizer {
    private enum class Phase {
        PRESSED,
        GLIDING,
    }

    private class Pointer(
        val config: GestureConfig,
        val keyWidthPx: Float,
        val glideEligible: Boolean,
        val swipeEligible: Boolean,
        val downX: Float,
        val downY: Float,
        val downTimeMillis: Long,
    ) {
        val samples: ArrayDeque<GesturePoint> = ArrayDeque()
        val glidePoints: MutableList<GesturePoint> = mutableListOf()
        var phase: Phase = Phase.PRESSED
        var lastGlideX: Float = 0f
        var lastGlideY: Float = 0f
        val minPointSpacingPx: Float = keyWidthPx * config.glideMinPointSpacingFraction

        fun addSample(point: GesturePoint) {
            samples.addLast(point)
            while (samples.size > MAX_SAMPLES) {
                samples.removeFirst()
            }
        }

        fun velocityWindow(): Pair<Float, Float> {
            val last = samples.lastOrNull() ?: return 0f to 0f
            val anchor = samples.firstOrNull { it.timeMillis >= last.timeMillis - config.velocityWindowMillis }
                ?: samples.first()
            val dt = last.timeMillis - anchor.timeMillis
            if (dt <= 0L) return 0f to 0f
            return (last.x - anchor.x) / dt to (last.y - anchor.y) / dt
        }
    }

    private val pointers = HashMap<Long, Pointer>()

    fun isTracking(pointerId: Long): Boolean = pointers.containsKey(pointerId)

    fun isGliding(pointerId: Long): Boolean = pointers[pointerId]?.phase == Phase.GLIDING

    /**
     * Starts tracking a pointer. [keyWidthPx] is the width of the key the pointer went down on and
     * defines both the glide start distance and the glide point spacing.
     */
    fun onDown(
        pointerId: Long,
        x: Float,
        y: Float,
        timeMillis: Long,
        config: GestureConfig,
        keyWidthPx: Float,
        glideEligible: Boolean,
        swipeEligible: Boolean,
    ) {
        val width = keyWidthPx.coerceAtLeast(1f)
        val pointer = Pointer(
            config = config,
            keyWidthPx = width,
            glideEligible = glideEligible,
            swipeEligible = swipeEligible,
            downX = x,
            downY = y,
            downTimeMillis = timeMillis,
        )
        pointer.addSample(GesturePoint(x, y, timeMillis))
        pointers[pointerId] = pointer
    }

    /**
     * Feeds a move sample and returns the glide signals it produced, if any. Promotes the press to a
     * glide as soon as distance, velocity and duration qualify.
     */
    fun onMove(pointerId: Long, x: Float, y: Float, timeMillis: Long): List<GestureSignal> {
        val pointer = pointers[pointerId] ?: return emptyList()
        pointer.addSample(GesturePoint(x, y, timeMillis))
        val signals = mutableListOf<GestureSignal>()

        if (pointer.phase == Phase.PRESSED) {
            if (pointer.shouldStartGlide(x, y, timeMillis)) {
                pointer.phase = Phase.GLIDING
                signals += GestureSignal.GlideStarted(pointerId)
                // Replay the buffered path so the trail starts where the finger started.
                for (sample in pointer.samples) {
                    if (pointer.acceptGlidePoint(sample)) {
                        signals += GestureSignal.GlidePoint(pointerId, sample.x, sample.y)
                    }
                }
                return signals
            }
        }

        if (pointer.phase == Phase.GLIDING) {
            if (pointer.acceptGlidePoint(GesturePoint(x, y, timeMillis))) {
                signals += GestureSignal.GlidePoint(pointerId, x, y)
            }
        }
        return signals
    }

    /**
     * Ends the pointer and classifies it. The pointer is no longer tracked afterwards, regardless of
     * the outcome.
     */
    fun onUp(pointerId: Long, x: Float, y: Float, timeMillis: Long): GestureOutcome {
        val pointer = pointers.remove(pointerId) ?: return GestureOutcome.Tap
        pointer.addSample(GesturePoint(x, y, timeMillis))

        if (pointer.phase == Phase.GLIDING) {
            pointer.acceptGlidePoint(GesturePoint(x, y, timeMillis))
            return GestureOutcome.Glide(pointer.glidePoints.toList())
        }

        if (pointer.swipeEligible) {
            val deltaX = x - pointer.downX
            val deltaY = y - pointer.downY
            val distance = maxOf(abs(deltaX), abs(deltaY))
            val (velocityX, velocityY) = pointer.velocityWindow()
            val speed = maxOf(abs(velocityX), abs(velocityY))
            if (distance > pointer.config.swipeDistancePx && speed > pointer.config.swipeVelocityPxPerMs) {
                return GestureOutcome.Swipe(
                    direction = SwipeDirection.of(deltaX, deltaY),
                    unitCountX = (deltaX / pointer.config.unitWidthPx).toInt(),
                    unitCountY = (deltaY / pointer.config.unitWidthPx).toInt(),
                    velocityPxPerMs = hypot(velocityX, velocityY),
                )
            }
        }

        if (pointer.isShortGlide(x, y, timeMillis)) {
            for (sample in pointer.samples) {
                pointer.acceptGlidePoint(sample)
            }
            return GestureOutcome.Glide(pointer.glidePoints.toList())
        }

        return GestureOutcome.Tap
    }

    /**
     * A glide between neighbouring keys (`we`, `as`, `op`) ends before the finger is a full key width away, so it
     * never started in [onMove]. On release (after flicks were taken as swipes) it still counts as a glide when the
     * finger ended at least [GestureConfig.shortGlideFraction] key widths away, quickly enough.
     */
    private fun Pointer.isShortGlide(x: Float, y: Float, timeMillis: Long): Boolean {
        if (!glideEligible || !config.glideEnabled) return false
        val duration = timeMillis - downTimeMillis
        if (duration <= 0L || duration > config.glideMaxDurationMillis) return false
        val distance = hypot(x - downX, y - downY)
        return distance >= keyWidthPx * config.shortGlideFraction && distance / duration > config.glideMinVelocityPxPerMs
    }

    /**
     * Drops the pointer. Returns true when it was gliding, so the caller can clear the trail.
     */
    fun onCancel(pointerId: Long): Boolean {
        val pointer = pointers.remove(pointerId) ?: return false
        return pointer.phase == Phase.GLIDING
    }

    fun clear() {
        pointers.clear()
    }

    private fun Pointer.shouldStartGlide(x: Float, y: Float, timeMillis: Long): Boolean {
        if (!glideEligible || !config.glideEnabled) return false
        if (timeMillis - downTimeMillis > config.glideMaxDurationMillis) return false
        if (hypot(x - downX, y - downY) <= keyWidthPx) return false
        val (velocityX, velocityY) = velocityWindow()
        return hypot(velocityX, velocityY) > config.glideMinVelocityPxPerMs
    }

    private fun Pointer.acceptGlidePoint(point: GesturePoint): Boolean {
        if (glidePoints.isNotEmpty()) {
            val distance = hypot(point.x - lastGlideX, point.y - lastGlideY)
            if (distance < minPointSpacingPx) return false
        }
        glidePoints += point
        lastGlideX = point.x
        lastGlideY = point.y
        return true
    }

    private companion object {
        const val MAX_SAMPLES = 64
    }
}
