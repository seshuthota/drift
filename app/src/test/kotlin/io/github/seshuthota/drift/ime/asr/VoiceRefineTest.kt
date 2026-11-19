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
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith

class VoiceRefineTest : FunSpec({
    context("decideRefinement") {
        test("replaces a different, complete result in replace mode") {
            decideRefinement("I scream for ice", "I scream for ice cream", VoiceRefineMode.REPLACE) shouldBe
                RefineDecision(true, "replaced")
        }

        test("only logs in log-only mode") {
            decideRefinement("I scream for ice", "I scream for ice cream", VoiceRefineMode.LOG_ONLY) shouldBe
                RefineDecision(false, "log_only")
        }

        test("line breaks and spacing are not a difference") {
            decideRefinement("Hello there.\nHow are you?", " Hello there. How  are you? ", VoiceRefineMode.REPLACE) shouldBe
                RefineDecision(false, "same")
        }

        test("never replaces with nothing") {
            decideRefinement("hello", "  ", VoiceRefineMode.REPLACE) shouldBe RefineDecision(false, "empty")
        }

        test("a result that dropped a phrase is not used") {
            decideRefinement("send the report to the team by friday", "send the report", VoiceRefineMode.REPLACE) shouldBe
                RefineDecision(false, "too_short")
        }

        test("a small word-count drop is still a valid correction") {
            decideRefinement("it is going to be a good day", "it's going to be a good day", VoiceRefineMode.REPLACE) shouldBe
                RefineDecision(true, "replaced")
        }
    }

    context("dictationContext") {
        test("joins the text around the cursor with collapsed whitespace") {
            dictationContext("Meeting with\nPriyanka ", "  at Koramangala") shouldBe "Meeting with Priyanka at Koramangala"
        }

        test("keeps the end of a long text before the cursor") {
            val context = dictationContext("a ".repeat(2_000) + "Thiruvananthapuram", "")
            context.length shouldBe MAX_CONTEXT_BEFORE
            context shouldEndWith "Thiruvananthapuram"
        }

        test("keeps the start of the text after the cursor") {
            val context = dictationContext("", "Bengaluru " + "b ".repeat(500))
            context.length shouldBe MAX_CONTEXT_AFTER
            context shouldStartWith "Bengaluru"
        }

        test("an empty field gives no context") {
            dictationContext("", "") shouldBe ""
        }
    }
})
