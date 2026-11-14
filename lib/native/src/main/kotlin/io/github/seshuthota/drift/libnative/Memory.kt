/*
 * Copyright (C) 2025 The FlorisBoard Contributors
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

package io.github.seshuthota.drift.libnative

/** Asks the native allocator to return freed pages to the system. Returns 1 when it did; see [purgeNativeMemory]. */
private external fun nativePurgeMemory(): Int

/**
 * Returns memory freed by native code (e.g. a just-closed ONNX model) to the system. The allocator otherwise keeps
 * freed pages for reuse, so the process stays at its peak size. Safe to call when `drift_native` failed to load;
 * a no-op before Android 9, where the purge option does not exist.
 */
fun purgeNativeMemory(): Boolean {
    return try {
        nativePurgeMemory() == 1
    } catch (_: UnsatisfiedLinkError) {
        false
    }
}
