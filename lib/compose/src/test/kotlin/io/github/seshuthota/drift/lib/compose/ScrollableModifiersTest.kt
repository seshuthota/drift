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

package io.github.seshuthota.drift.lib.compose

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ScrollableModifiersTest {
    @Test
    fun `thumb keeps its wanted length when it fits`() {
        assertEquals(120f, thumbLength(wantedPx = 120f, viewportPx = 800f, offsetPx = 40f))
    }

    @Test
    fun `thumb is clipped to the space left along its own axis`() {
        assertEquals(60f, thumbLength(wantedPx = 400f, viewportPx = 800f, offsetPx = 740f))
    }

    @Test
    fun `horizontal thumb is clipped by width, not by a short viewport height`() {
        val viewportWidth = 1200f
        val offsetX = 900f

        assertEquals(200f, thumbLength(wantedPx = 200f, viewportPx = viewportWidth, offsetPx = offsetX))
        assertEquals(300f, thumbLength(wantedPx = 500f, viewportPx = viewportWidth, offsetPx = offsetX))
    }
}
