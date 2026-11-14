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
import io.kotest.matchers.shouldBe

class SwipeTrieTest : FunSpec({
    test("lookups are unchanged by trimming, and inserting afterwards still works") {
        val trie = SwipeTrie()
        val words = listOf("the", "then", "there", "a", "an", "and", "zebra")
        words.forEach(trie::insert)
        trie.trimToSize()
        words.forEach { trie.isWord(it) shouldBe true }
        trie.contains("the") shouldBe true
        trie.isWord("th") shouldBe false
        trie.contains("x") shouldBe false
        trie.insert("xylophone")
        trie.isWord("xylophone") shouldBe true
        trie.isWord("then") shouldBe true
    }

    test("an empty trie can be trimmed and grown again") {
        val trie = SwipeTrie()
        trie.trimToSize()
        trie.insert("hello")
        trie.isWord("hello") shouldBe true
    }
})
