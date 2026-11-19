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

package io.github.seshuthota.drift.ime.io

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import okio.IOException
import okio.Path.Companion.toPath

class AndroidStorageResolveTest : FunSpec({
    val cache = "/data/user/0/io.github.seshuthota.drift/cache".toPath()

    test("resolves a relative path under the base") {
        resolveWithin(cache, "extensions/style.json".toPath()) shouldBe "$cache/extensions/style.json".toPath()
    }

    test("collapses inner parent segments that stay inside the base") {
        resolveWithin(cache, "extensions/../themes/style.json".toPath()) shouldBe "$cache/themes/style.json".toPath()
    }

    test("refuses a relative path that climbs out of the base") {
        shouldThrow<IOException> {
            resolveWithin(cache, "../../files/prefs.json".toPath())
        }
    }

    test("refuses an absolute path") {
        shouldThrow<IOException> {
            resolveWithin(cache, "/data/local/tmp/evil".toPath())
        }
    }

    test("refuses a base-relative path that lands on the base's parent") {
        shouldThrow<IOException> {
            resolveWithin(cache, "..".toPath())
        }
    }
})
