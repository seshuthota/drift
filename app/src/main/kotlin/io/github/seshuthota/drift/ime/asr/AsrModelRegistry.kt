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
 * `decoder_kv_with_attention.ort` (32 MB) is left out: Moonshine only loads it when present, logs a warning
 * otherwise, and its own non-English models ship without it; it serves word-level timing, which Drift does not use.
 *
 * The folder in the URL is Moonshine's own release tag.
 */
object AsrModelRegistry {
    const val MOONSHINE = "moonshine"
    const val PARAKEET = "parakeet"

    const val FLUID_SPEECH_PRO_ID = "fluid-speech-pro-int8"

    const val MOONSHINE_SMALL_EN_ID = "moonshine-small-streaming-en"
    const val MOONSHINE_MEDIUM_EN_ID = "moonshine-medium-streaming-en"
    const val DEFAULT_MODEL_ID = MOONSHINE_SMALL_EN_ID

    private const val MOONSHINE_SMALL_EN_HOST =
        "https://download.moonshine.ai/model/small-streaming-en/quantized_26_08_21/"
    private const val MOONSHINE_MEDIUM_EN_HOST =
        "https://download.moonshine.ai/model/medium-streaming-en/quantized_26_08_21/"

    /** Pinned to a commit so the hashes below stay valid if the repository is updated. */
    private const val FLUID_SPEECH_PRO_HOST =
        "https://huggingface.co/FluidVoice-Labs/fluid-speech-pro-onnx/resolve/fa588e9f87f1b9ad0ca2e2d8f7eaf84dc3803581/"

    val models: List<AsrModelSpec> = listOf(
        AsrModelSpec(
            id = MOONSHINE_SMALL_EN_ID,
            displayName = "Moonshine Small Streaming (English, live, ~142 MB)",
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
        AsrModelSpec(
            id = MOONSHINE_MEDIUM_EN_ID,
            displayName = "Moonshine Medium Streaming (English, live, most accurate, ~270 MB)",
            engineId = MOONSHINE,
            languageTag = "en",
            license = "MIT",
            attribution = "Moonshine Streaming by Moonshine AI (MIT)",
            files = listOf(
                moonshineMediumEn(
                    "adapter.ort",
                    "3f2a287def57cc094367a0eec3c4f5fc36a32ec420e86764b696920991b20281",
                    3_651_296,
                ),
                moonshineMediumEn(
                    "cross_kv.ort",
                    "642f6e21cd305be79342207c6f9e6b681d469d55bc48c72b27b84846fb71fd1e",
                    11_643_776,
                ),
                moonshineMediumEn(
                    "decoder_kv.ort",
                    "193bb366492b74fc4ad338c6778e8d8eb916aaa11b5aa264f9057f4db7759486",
                    146_972_408,
                ),
                moonshineMediumEn(
                    "encoder.ort",
                    "12915e76ebac7dd287c5ea63965d06103a53ba1ce242a4a34f318f3958c60c37",
                    94_705_376,
                ),
                moonshineMediumEn(
                    "frontend.model.ort",
                    "95768855c70c8251eeecc05fedf69999da1b8ab16f605c9f457fd3354b0ad6b5",
                    28_720,
                ),
                moonshineMediumEn(
                    "frontend.weights.ort",
                    "5ac941f490cbe035b335b99a414cc393d62d4c6f9f2423495b286870d271d709",
                    11_889_560,
                ),
                moonshineMediumEn(
                    "streaming_config.json",
                    "28e83b7a28e91472692a035e0dae3116422ae43aeb2bef5ed822c44ce89b88af",
                    513,
                ),
                moonshineMediumEn(
                    "tokenizer.bin",
                    "6884b35fd6377d4c4d32336a0bc152f36b64d1e45b6503683cdc238250a8472d",
                    249_974,
                ),
            ),
        ),
        AsrModelSpec(
            id = FLUID_SPEECH_PRO_ID,
            displayName = "Fluid Speech Pro (25 languages, most accurate, ~800 MB, needs ~1 GB RAM)",
            engineId = PARAKEET,
            languageTag = null,
            license = "CC-BY-4.0",
            attribution = "Fluid Speech Pro by FluidVoice Labs, built on NVIDIA Parakeet TDT 0.6B v3 (CC BY 4.0)",
            streamsLive = false,
            files = listOf(
                fluidSpeechPro(
                    "encoder-model.int8.onnx",
                    "07ad77459bd3bb165c2c53e978ed1dfd64c6e129b18af2ec5ee38343be1d4451",
                    955_592,
                ),
                fluidSpeechPro(
                    "encoder-model.int8.onnx.data",
                    "d2f8bfbb1fa399f207cde85aa3fabee0d67f6730cb31cbf31832c10de69f9c3c",
                    745_971_712,
                ),
                fluidSpeechPro(
                    "decoder_joint-model.int8.onnx",
                    "59e3ff4b22b6629bc5a23e627daff7697ec232d2d4e6de50597c907f4038bd29",
                    8_586,
                ),
                fluidSpeechPro(
                    "decoder_joint-model.int8.onnx.data",
                    "3c7a1e90be06acc16d858b989700cdb51f761c6129a45b0bdc0378f2b40a28a4",
                    53_817_344,
                ),
                fluidSpeechPro(
                    "vocab.txt",
                    "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d",
                    93_939,
                ),
            ),
        ),
    )

    private fun fluidSpeechPro(name: String, sha256: String, bytes: Long) =
        AsrModelFile(name, FLUID_SPEECH_PRO_HOST + name, sha256, bytes)

    private fun moonshineSmallEn(name: String, sha256: String, bytes: Long) =
        AsrModelFile(name, MOONSHINE_SMALL_EN_HOST + name, sha256, bytes)

    private fun moonshineMediumEn(name: String, sha256: String, bytes: Long) =
        AsrModelFile(name, MOONSHINE_MEDIUM_EN_HOST + name, sha256, bytes)

    fun spec(id: String): AsrModelSpec? = models.firstOrNull { it.id == id }

    fun default(): AsrModelSpec = spec(DEFAULT_MODEL_ID)!!

    /** Rows that [engineId] can run, in registry order. */
    fun forEngine(engineId: String): List<AsrModelSpec> = models.filter { it.engineId == engineId }
}
