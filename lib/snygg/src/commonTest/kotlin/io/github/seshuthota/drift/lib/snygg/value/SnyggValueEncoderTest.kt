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

package io.github.seshuthota.drift.lib.snygg.value

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SnyggValueEncoderTest {
    @Test
    fun `unknown keyword in a failure message names the value that was rejected`() {
        val failure = SnyggContentScaleValue.deserialize("stretch")
        val message = assertNotNull(failure.exceptionOrNull(), "deserialize should fail").message.orEmpty()
        assertTrue(message.contains("stretch"), "message should name the rejected value: $message")
    }
}
