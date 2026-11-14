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

/**
 * Built-in ASR model catalog. Rows are data; another size or language of the same family only adds a row, and a
 * different family adds a row plus a [StreamingAsrEngine] for its [AsrModelSpec.engineId].
 *
 * `decoder_kv_with_attention.ort` (32 MB) is left out: Moonshine only loads it when present, logs a warning
 * otherwise, and its own non-English models ship without it; it serves word-level timing, which Drift does not use.
 *
 * Moonshine sizes and SHA-256 were measured from the downloaded files on 2026-11-14; the folder in the URL is
 * Moonshine's own release tag. Weights: MIT, Moonshine AI.
 */
object AsrModelRegistry {
    const val MOONSHINE = "moonshine"

    const val MOONSHINE_TINY_EN_ID = "moonshine-tiny-streaming-en"
    const val DEFAULT_MODEL_ID = MOONSHINE_TINY_EN_ID

    private const val MOONSHINE_TINY_EN_HOST =
        "https://download.moonshine.ai/model/tiny-streaming-en/quantized_26_08_21/"

    val models: List<AsrModelSpec> = listOf(
        AsrModelSpec(
            id = MOONSHINE_TINY_EN_ID,
            displayName = "Moonshine Tiny Streaming (English, live)",
            engineId = MOONSHINE,
            languageTag = "en",
            license = "MIT",
            attribution = "Moonshine Streaming by Moonshine AI (MIT)",
            files = listOf(
                moonshineTinyEn(
                    "adapter.ort",
                    "22ecc949e146c49667fda28d102d4e30749a107dc88a396292aa8f277ef1347c",
                    1_319_664,
                ),
                moonshineTinyEn(
                    "cross_kv.ort",
                    "143a36667b8d05fd9d04e8c337b7ee121f37ef299aea6b3d82bdb3d3401950b4",
                    1_287_544,
                ),
                moonshineTinyEn(
                    "decoder_kv.ort",
                    "8852553f312adb6c9aa4d17418015049b30f412209ee569d336548c0044627de",
                    32_583_720,
                ),
                moonshineTinyEn(
                    "encoder.ort",
                    "a8414e1a5dedf9f2093d7680601dd8a9b0433e7020260eafe0e370ead91134ca",
                    7_675_440,
                ),
                moonshineTinyEn(
                    "frontend.model.ort",
                    "5121b561417b638afce0c6c31b760e37c93cf97f80d9b0031aad1fe7b6f25d61",
                    23_344,
                ),
                moonshineTinyEn(
                    "frontend.weights.ort",
                    "217da24ac6f522ebf02da8ef288e77d1ac68d50d4a6821433182e4fbf4204bbd",
                    2_093_464,
                ),
                moonshineTinyEn(
                    "streaming_config.json",
                    "74fe5ddebd63b17caf59e8a3b18c17547ff7bce1642050edbb1c3962674f8950",
                    509,
                ),
                moonshineTinyEn(
                    "tokenizer.bin",
                    "6884b35fd6377d4c4d32336a0bc152f36b64d1e45b6503683cdc238250a8472d",
                    249_974,
                ),
            ),
        ),
    )

    private fun moonshineTinyEn(name: String, sha256: String, bytes: Long) =
        AsrModelFile(name, MOONSHINE_TINY_EN_HOST + name, sha256, bytes)

    fun spec(id: String): AsrModelSpec? = models.firstOrNull { it.id == id }

    fun default(): AsrModelSpec = spec(DEFAULT_MODEL_ID)!!

    /** Rows that [engineId] can run, in registry order. */
    fun forEngine(engineId: String): List<AsrModelSpec> = models.filter { it.engineId == engineId }
}
