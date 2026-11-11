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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.checkAll

class GestureRecognizerTest : FunSpec({
    val config = GestureConfig(
        swipeDistancePx = 50f,
        swipeVelocityPxPerMs = 1f,
        glideEnabled = true,
        glideMinVelocityPxPerMs = 0.1f,
        glideMaxDurationMillis = 500L,
        velocityWindowMillis = 100L,
    )
    val keyWidthPx = 100f

    fun GestureRecognizer.press(
        x: Float = 0f,
        y: Float = 0f,
        t: Long = 0L,
        glideEligible: Boolean = true,
        swipeEligible: Boolean = true,
        pointerId: Long = 1L,
    ) = onDown(pointerId, x, y, t, config, keyWidthPx, glideEligible, swipeEligible)

    context("a tap") {
        test("stays a tap when the finger barely moves") {
            val recognizer = GestureRecognizer()
            recognizer.press()
            recognizer.onUp(1L, 2f, 1f, 120L) shouldBe GestureOutcome.Tap
        }

        test("stays a tap when dragged slowly below the velocity threshold") {
            val recognizer = GestureRecognizer()
            recognizer.press()
            recognizer.onMove(1L, 20f, 0f, 500L)
            recognizer.onUp(1L, 200f, 0f, 1000L) shouldBe GestureOutcome.Tap
        }

        test("stays a tap when the finger pauses before release") {
            val recognizer = GestureRecognizer()
            recognizer.press(glideEligible = false)
            recognizer.onMove(1L, 200f, 0f, 50L)
            recognizer.onMove(1L, 200f, 0f, 300L)
            recognizer.onUp(1L, 200f, 0f, 500L) shouldBe GestureOutcome.Tap
        }

        test("is not reported for an unknown pointer") {
            GestureRecognizer().onUp(42L, 0f, 0f, 0L) shouldBe GestureOutcome.Tap
        }
    }

    context("a swipe") {
        test("is detected on a fast flick with signed unit counts") {
            val recognizer = GestureRecognizer()
            recognizer.press(glideEligible = false)
            recognizer.onMove(1L, 150f, 0f, 50L)
            val outcome = recognizer.onUp(1L, 300f, 0f, 100L)
            val swipe = outcome.shouldBeInstanceOf<GestureOutcome.Swipe>()
            swipe.direction shouldBe SwipeDirection.RIGHT
            swipe.unitCountX shouldBe 24
            swipe.unitCountY shouldBe 0
        }

        test("uses negative units when swiping left") {
            val recognizer = GestureRecognizer()
            recognizer.press(x = 400f, y = 0f, glideEligible = false)
            recognizer.onMove(1L, 250f, 0f, 50L)
            val swipe = recognizer.onUp(1L, 100f, 0f, 100L).shouldBeInstanceOf<GestureOutcome.Swipe>()
            swipe.direction shouldBe SwipeDirection.LEFT
            swipe.unitCountX shouldBe -24
        }

        test("quantizes into eight octants") {
            SwipeDirection.of(300f, 0f) shouldBe SwipeDirection.RIGHT
            SwipeDirection.of(300f, 300f) shouldBe SwipeDirection.DOWN_RIGHT
            SwipeDirection.of(0f, 300f) shouldBe SwipeDirection.DOWN
            SwipeDirection.of(-300f, 300f) shouldBe SwipeDirection.DOWN_LEFT
            SwipeDirection.of(-300f, 0f) shouldBe SwipeDirection.LEFT
            SwipeDirection.of(-300f, -300f) shouldBe SwipeDirection.UP_LEFT
            SwipeDirection.of(0f, -300f) shouldBe SwipeDirection.UP
            SwipeDirection.of(300f, -300f) shouldBe SwipeDirection.UP_RIGHT
        }

        test("does not trigger on a key marked swipe ineligible") {
            val recognizer = GestureRecognizer()
            recognizer.press(glideEligible = false, swipeEligible = false)
            recognizer.onMove(1L, 150f, 0f, 50L)
            recognizer.onUp(1L, 300f, 0f, 100L) shouldBe GestureOutcome.Tap
        }
    }

    context("a glide") {
        test("starts after the key width is exceeded and replays buffered points") {
            val recognizer = GestureRecognizer()
            recognizer.press()
            recognizer.onMove(1L, 50f, 0f, 20L) shouldHaveSize 0
            val signals = recognizer.onMove(1L, 150f, 0f, 40L)
            signals.first().shouldBeInstanceOf<GestureSignal.GlideStarted>()
            // Buffered samples (0,0), (50,0), (150,0) all clear the quarter-key spacing.
            signals.filterIsInstance<GestureSignal.GlidePoint>().shouldHaveSize(3)
        }

        test("completes with the full path instead of a key press") {
            val recognizer = GestureRecognizer()
            recognizer.press()
            recognizer.onMove(1L, 150f, 0f, 40L)
            recognizer.onMove(1L, 300f, 20f, 80L)
            val glide = recognizer.onUp(1L, 450f, 40f, 120L).shouldBeInstanceOf<GestureOutcome.Glide>()
            glide.points.size shouldBe 4
        }

        test("a short glide to the neighbouring key completes on release") {
            val recognizer = GestureRecognizer()
            recognizer.press()
            recognizer.onMove(1L, 50f, 0f, 60L) shouldHaveSize 0
            recognizer.onMove(1L, 100f, 0f, 120L) shouldHaveSize 0
            val glide = recognizer.onUp(1L, 100f, 0f, 150L).shouldBeInstanceOf<GestureOutcome.Glide>()
            glide.points.first() shouldBe GesturePoint(0f, 0f, 0L)
            glide.points.last().x shouldBe 100f
        }

        test("a fast flick of the same length stays a swipe") {
            val recognizer = GestureRecognizer()
            recognizer.press()
            recognizer.onMove(1L, 40f, 0f, 20L)
            recognizer.onUp(1L, 90f, 0f, 40L).shouldBeInstanceOf<GestureOutcome.Swipe>()
        }

        test("a slow vertical move of the same length stays a tap") {
            val recognizer = GestureRecognizer()
            recognizer.press()
            recognizer.onMove(1L, 0f, -50f, 60L)
            recognizer.onUp(1L, 5f, -90f, 150L) shouldBe GestureOutcome.Tap
        }

        test("a diagonal neighbour still counts as a short glide") {
            val recognizer = GestureRecognizer()
            recognizer.press()
            recognizer.onUp(1L, -50f, 95f, 150L).shouldBeInstanceOf<GestureOutcome.Glide>()
        }

        test("a slide that stays within the down key is a tap") {
            val recognizer = GestureRecognizer()
            recognizer.press()
            recognizer.onUp(1L, 40f, 0f, 100L) shouldBe GestureOutcome.Tap
        }

        test("never starts on an ineligible key") {
            val recognizer = GestureRecognizer()
            recognizer.press(glideEligible = false)
            recognizer.onMove(1L, 150f, 0f, 40L)
            recognizer.onUp(1L, 300f, 0f, 80L)
            recognizer.isGliding(1L) shouldBe false
        }

        test("does not start after the maximum duration") {
            val recognizer = GestureRecognizer()
            recognizer.press()
            recognizer.onMove(1L, 150f, 0f, 600L) shouldHaveSize 0
            recognizer.isGliding(1L) shouldBe false
        }

        test("does not start when glide is disabled") {
            val recognizer = GestureRecognizer()
            val disabled = config.copy(glideEnabled = false)
            recognizer.onDown(1L, 0f, 0f, 0L, disabled, keyWidthPx, true, true)
            recognizer.onMove(1L, 150f, 0f, 40L) shouldHaveSize 0
        }

        test("is reported as cancelled while gliding") {
            val recognizer = GestureRecognizer()
            recognizer.press()
            recognizer.onMove(1L, 150f, 0f, 40L)
            recognizer.onCancel(1L) shouldBe true
            recognizer.isTracking(1L) shouldBe false
        }

        test("a plain tap is not reported as cancelled") {
            val recognizer = GestureRecognizer()
            recognizer.press()
            recognizer.onCancel(1L) shouldBe false
        }
    }

    context("multiple pointers") {
        test("are tracked independently") {
            val recognizer = GestureRecognizer()
            recognizer.press(pointerId = 1L)
            recognizer.press(x = 500f, y = 500f, pointerId = 2L)
            recognizer.onMove(1L, 150f, 0f, 40L)
            recognizer.onMove(2L, 503f, 502f, 40L)
            recognizer.onUp(2L, 504f, 501f, 90L) shouldBe GestureOutcome.Tap
            recognizer.onUp(1L, 450f, 0f, 90L).shouldBeInstanceOf<GestureOutcome.Glide>()
        }
    }

    context("direction invariants") {
        test("are invariant under positive scaling") {
            checkAll(Arb.double(-500.0, 500.0), Arb.double(-500.0, 500.0)) { dx, dy ->
                if (kotlin.math.abs(dx) > 1.0 && kotlin.math.abs(dy) > 1.0) {
                    val direction = SwipeDirection.of(dx.toFloat(), dy.toFloat())
                    SwipeDirection.of((dx * 3).toFloat(), (dy * 3).toFloat()) shouldBe direction
                }
            }
        }

        test("are point symmetric under negation") {
            val opposite = mapOf(
                SwipeDirection.UP to SwipeDirection.DOWN,
                SwipeDirection.DOWN to SwipeDirection.UP,
                SwipeDirection.LEFT to SwipeDirection.RIGHT,
                SwipeDirection.RIGHT to SwipeDirection.LEFT,
                SwipeDirection.UP_LEFT to SwipeDirection.DOWN_RIGHT,
                SwipeDirection.DOWN_RIGHT to SwipeDirection.UP_LEFT,
                SwipeDirection.UP_RIGHT to SwipeDirection.DOWN_LEFT,
                SwipeDirection.DOWN_LEFT to SwipeDirection.UP_RIGHT,
            )
            checkAll(Arb.double(-500.0, 500.0), Arb.double(-500.0, 500.0)) { dx, dy ->
                if (kotlin.math.abs(dx) > 1.0 && kotlin.math.abs(dy) > 1.0) {
                    val forward = SwipeDirection.of(dx.toFloat(), dy.toFloat())
                    SwipeDirection.of(-dx.toFloat(), -dy.toFloat()) shouldBe opposite.getValue(forward)
                }
            }
        }
    }
})
