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


package io.github.seshuthota.drift.ime.asr

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StripNonSpeechTest : FunSpec({
    test("blank audio tag becomes empty") {
        stripNonSpeech(" [BLANK_AUDIO]") shouldBe ""
    }

    test("noise tags are removed around speech") {
        stripNonSpeech("[MUSIC] hello (laughs) world *cough*") shouldBe "hello world"
    }

    test("plain speech and newlines are kept") {
        stripNonSpeech("hello\nworld") shouldBe "hello world"
    }
})
