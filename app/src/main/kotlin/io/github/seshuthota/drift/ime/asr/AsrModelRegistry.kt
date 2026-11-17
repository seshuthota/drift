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
 * Moonshine sizes and SHA-256 were measured from the downloaded files (Tiny 2026-11-14, Small 2026-11-17); the
 * folder in the URL is Moonshine's own release tag. Weights: MIT, Moonshine AI. Small is ~142 MB against Tiny's
 * ~45 MB and needs more memory while dictating, so Tiny stays the default and Small is opt-in in Settings.
 */
object AsrModelRegistry {
    const val MOONSHINE = "moonshine"

    const val MOONSHINE_TINY_EN_ID = "moonshine-tiny-streaming-en"
    const val MOONSHINE_SMALL_EN_ID = "moonshine-small-streaming-en"
    const val DEFAULT_MODEL_ID = MOONSHINE_TINY_EN_ID

    private const val MOONSHINE_TINY_EN_HOST =
        "https://download.moonshine.ai/model/tiny-streaming-en/quantized_26_08_21/"
    private const val MOONSHINE_SMALL_EN_HOST =
        "https://download.moonshine.ai/model/small-streaming-en/quantized_26_08_21/"

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
        AsrModelSpec(
            id = MOONSHINE_SMALL_EN_ID,
            displayName = "Moonshine Small Streaming (English, live, more accurate)",
            engineId = MOONSHINE,
            languageTag = "en",
            license = "MIT",
            attribution = "Moonshine Streaming by Moonshine AI (MIT)",
            files = listOf(
                moonshineSmallEn(
                    "adapter.ort",
                    "c665f742364febad597cc9ac1e0b341ffbee0e24a1466e2f3bde95e6e4771762",
                    2_870_368,
                ),
                moonshineSmallEn(
                    "cross_kv.ort",
                    "e2d3417144e9514055ebfefe8dcc4c0a55a55adcb8530435844c75c53e352bf6",
                    5_356_536,
                ),
                moonshineSmallEn(
                    "decoder_kv.ort",
                    "1a05465b1dd955858dfcbee039c0020fb5dd982b0f5094c34e61735d518d771b",
                    81_878_600,
                ),
                moonshineSmallEn(
                    "encoder.ort",
                    "2d4d973e91e8aca08c51e7e7efa28a46ab265b63d809d5294d18b86bcd85b993",
                    44_148_576,
                ),
                moonshineSmallEn(
                    "frontend.model.ort",
                    "09b1210ae30dc5f0f3e45f0ebab914c254741323114f53fbbe5ae62cca35058f",
                    26_944,
                ),
                moonshineSmallEn(
                    "frontend.weights.ort",
                    "7ef97521bd4bad3928f5bb6808586f4fcc6e92bd5990394112eed7d4052ec338",
                    7_769_464,
                ),
                moonshineSmallEn(
                    "streaming_config.json",
                    "26f02b6afb22d60871a5efd85c3d38e569cc0ddb6c5eb6e93d3260152ae8a47a",
                    512,
                ),
                moonshineSmallEn(
                    "tokenizer.bin",
                    "6884b35fd6377d4c4d32336a0bc152f36b64d1e45b6503683cdc238250a8472d",
                    249_974,
                ),
            ),
        ),
    )

    private fun moonshineTinyEn(name: String, sha256: String, bytes: Long) =
        AsrModelFile(name, MOONSHINE_TINY_EN_HOST + name, sha256, bytes)

    private fun moonshineSmallEn(name: String, sha256: String, bytes: Long) =
        AsrModelFile(name, MOONSHINE_SMALL_EN_HOST + name, sha256, bytes)

    fun spec(id: String): AsrModelSpec? = models.firstOrNull { it.id == id }

    fun default(): AsrModelSpec = spec(DEFAULT_MODEL_ID)!!

    /** Rows that [engineId] can run, in registry order. */
    fun forEngine(engineId: String): List<AsrModelSpec> = models.filter { it.engineId == engineId }
}
