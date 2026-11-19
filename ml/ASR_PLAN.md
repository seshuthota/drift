# Drift local voice input (ASR) — design and plan

**Status (2026-11-14):** superseded — Whisper was replaced by Moonshine Streaming; see "Outcome" below. The rest
of this document is the original Whisper plan, kept for its reasoning.
**Scope:** on-device speech-to-text for the IME, Whisper Tiny first, engine and model replaceable
**Out of scope for v1:** streaming/partial transcripts, punctuation restoration model, standalone VAD model,
  translation, hands-free/always-listening modes

## Outcome

On the device (Snapdragon 8 Elite, a microphone that delivers speech around -45 dBFS) Whisper Tiny was not usable
for dictation: one-word utterances came back as `[BLANK_AUDIO]` or as hallucinated sentences, live previews meant
re-transcribing the growing clip every 1-2 s, and multilingual auto-detection picked random languages on short
audio. Silence gating, trimming, gain and prompting helped only partly.

Voice input now uses **Moonshine Tiny Streaming (English)** through the `ai.moonshine:moonshine-voice` Android
library (MIT, ONNX Runtime CPU, built-in Silero VAD). It emits text for the line being spoken while audio arrives
and ends lines at pauses, which the IME shows as composing text and commits per line (`MoonshineEngine`,
`StreamingAsrEngine`, `AsrController`). whisper.cpp, whisper-rs and the Rust/JNI ASR bridge were removed.
Integration notes: moonshine-voice bundles a minimal `libonnxruntime.so` that cannot load `.onnx` models, so an
artifact transform in `app/build.gradle.kts` strips it and the app's full onnxruntime-android 1.23.2 serves both.

**2026-11-18.** Tiny was removed: in daily use Small was clearly better, so **Moonshine Small Streaming** is the only
model (the old Tiny files are deleted by `removeUnlisted`). Two additions:

- **Field context.** Before each dictation the text around the cursor (up to 1,000 chars before, 200 after) goes to
  `Transcriber.setContext`. In Moonshine this is key-term biasing (`ContextBiaser`): terms from the text are boosted
  during decoding, for streaming and the one-pass decode alike. It helps names and unusual words already in the
  field; it is not a sentence prompt.
- **Refine after speaking** (Settings > Voice input; Off / Compare only / Replace, default Compare only). On stop,
  recordings up to 30 s are decoded again in one pass (`transcribeWithoutStreaming`) on the audio the engine heard.
  `decideRefinement` never uses an empty result or one that lost over 40 % of the streamed words. In Replace mode the
  dictated text is swapped only while it is still exactly before a collapsed cursor (`ImeEditor.replaceLiveText`),
  never in raw editors. Every run logs `refined`, `refine_ms` and `refine_result` in the voice event;
  `ml/quality/analyze.py` prints the word differences and backspaces after each outcome.
  `VoiceRefineDeviceTest`: on an espeak-ng clip of two sentences both passes were exact (one pass 1.4 s for 8 s of
  audio on the x86 emulator).

### Small vs. Medium

Medium Streaming (~270 MB, opt-in in Settings) was added on 2026-11-18. To compare models on the user's own voice:
turn on "Keep voice clips", dictate normally, then run `ml/quality/compare_asr.sh` (phone connected). It installs the
debug build next to the daily one, runs `AsrModelComparisonTest` over every kept clip plus a bundled espeak clip
with each registry model (live and one-pass), uninstalls the debug build and prints `ml/quality/compare_asr.py`'s
table: load time, real-time factor (RTF, below 1 keeps up with speech), native memory and PSS, then each clip's
transcripts side by side, plus WER for clips listed in `refs.json` (judge the rest by ear). Make Medium the default only if it
wins clearly, its live RTF on the phone stays well below 1, and the extra memory does not get the keyboard killed.
With two adb entries for the same phone (mDNS + IP), set `ANDROID_SERIAL`. The script keeps the debug app in the
foreground and the screen awake: Vivo's freezer froze the instrumented process (kernel `cgroup.freeze=1`, while
`dumpsys` said not frozen) and the run hung for 12 h. Clips go to the debug app's internal storage, because files adb
writes to its external folder are owned by `shell` and the app cannot read them.
Emulator, espeak clip, 2026-11-18: Small live "re-anchor" (no context) / one-pass "Priyanka"; Medium live
"Priyanka". Native memory 200 vs 326 MB, live RTF 0.66 vs 0.92 (x86 emulator).

**Phone run, 2026-11-19** (Vivo I2401, 14 kept clips + espeak; results in `ml/quality/data/asr-compare/`). Kept
clips are the microphone's raw level (peaks −27 to −43 dBFS on this phone); the app's `AutoGain` lifts them up to
16× before the model. The first run fed raw clips and scored ~22 % WER for both models, with empty one-pass output on
quiet clips: a test artifact. The test now applies `AutoGain` chunk by chunk, as the app does, for both passes:

| | load | live RTF | one-pass RTF | native MB | PSS MB | WER live | WER one-pass |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Small | 433 ms | 0.23 | 0.09 | 236 | 743 | 5.8 % | 6.7 % |
| Medium | 365 ms | 0.53 | 0.20 | 367 | 1000 | 6.7 % | 6.2 % |

WER is over the 9 clips listed in `ml/quality/data/asr-compare/refs.json` (208 words), whose reference text is the
user's chat messages. Those messages were dictated with Small and lightly edited, so the references lean towards
Small's output. Treat the numbers as "both are close", not as a ranking. **Decision (user, 2026-11-19): keep Small as
the default; Medium stays opt-in.** Medium is not clearly more accurate, it uses ~260 MB more process memory, and its
live pass is twice as slow (still well under real time). Medium did get the espeak name right ("Priyanka" vs
"re-anchor") and Small's one-pass did not beat its live pass here, so keep "Refine after speaking" on Compare only
until the quality log says otherwise. Errors both models share ("tiny" → "time", the last word cut short:
"remain") are vocabulary and end-of-speech problems, not model size.

To score a future run, add `"<clip>.wav": "what was said"` lines to `refs.json`; `compare_asr.py` prints WER when the
file exists. `MODELS_DIR=<dir with one folder per model id>` makes the script push local model files instead of
downloading them.

### How to judge "Refine after speaking" (do this after a few days of Compare only)

Needs Developer options > Typing quality log on (and optionally "Keep voice clips"). Then:

    adb pull /sdcard/Android/data/io.github.seshuthota.drift/files/diagnostics ml/quality/data
    python ml/quality/analyze.py ml/quality/data/diagnostics

Read the `== Voice refine ==` section:

1. **How often they differ.** `result:` counts. Mostly `same` means the pass changes little and is not worth the wait
   in Replace mode. `too_short` often means the one-pass decode drops phrases (or the 40 % guard is too strict); look
   at those lines.
2. **Who is right when they differ.** Go through the `differences` list (`[streamed -> one-pass]`). For each, say
   which side matches what was spoken; if voice clips were kept, play the clip whose time matches. Count wins for
   each side. Typical one-pass wins: last word, words at a line break, names. Typical losses: dropped or merged
   phrases on long clips.
3. **What the user fixed.** `backspaces after each` per result. In Compare only the streamed text stayed, so many
   backspaces after `log_only` dictations where the one-pass text looked right = Replace would have saved them.
4. **Cost.** `one-pass time` median/p90. Over ~2 s at p90 is noticeable after every stop; then consider lowering the
   30 s cap (`MAX_REFINE_SAMPLES` in `AsrController`).
5. **Context.** `dictations with field context` is the share where the field already had text. Its effect is not
   logged separately; to measure it, replay kept clips with and without `setContext` in a device test like
   `VoiceRefineDeviceTest`.

Decision: switch the default to Replace (`voice__refine_mode` in `AppPrefs`) only if the one-pass side wins clearly
more often than it loses and p90 time is acceptable. If it loses on long clips only, lower the cap instead of
dropping the feature.

---

## 0. Goal

Replace the `ExternalVoiceInput` hand-off (system voice IME, i.e. Google on most phones) with audio capture and
inference inside Drift, so the audio never leaves the device. The first model is Whisper Tiny; the engine API must
make a second model family or runtime a drop-in.

## 1. Shape of the feature

```
mic key / quick action
        │
        ▼
AsrController (app-scoped) ── AudioRecorder (AudioRecord, 16 kHz mono PCM16)
        │                              │ amplitude + duration StateFlow
        ├── AsrModelManager (install, verify, select, delete)
        ├── AsrEngine (transcribe(pcm) -> AsrResult)     ← replaceable
        └── state flow: Idle / Recording / LoadingModel / Transcribing / Result / Error
        │
        ▼
ImeController.updateState { emitText(result.text) }
```

- Tap the mic action to start; tap again (or the overlay's stop) to finish. Text is committed once, as a single
  `emitText`, with a leading space when the cursor follows a word (same rule as glide).
- Cancel (overlay X / back) drops the buffer; nothing is committed.
- Maximum recording length is a preference (default 30 s) so memory and decode time stay bounded.
- Audio lives in memory only. No files, no logs, no analytics. Diagnostics, if added later, record timings and
  model id only.

## 2. Engine boundary

```kotlin
interface AsrEngine {
    val id: String                      // "whisper.cpp" / "onnx-whisper"
    suspend fun load(model: AsrModel, progress: (Float) -> Unit)
    suspend fun transcribe(pcm: ShortArray, sampleRate: Int): AsrResult
    fun close()
}

data class AsrResult(val text: String, val language: String?, val durationMs: Long, val inferenceMs: Long)
```

Everything engine-specific (mel, tokenizer, decoder heuristics, quantization) stays behind this. The controller
only sees PCM in and text out, so a new engine is one factory entry plus a registry row.

## 3. Candidate first engine

| | whisper.cpp (`whisper-rs`) | ONNX Runtime (Optimum export) |
|---|---|---|
| App code | thin JNI + one native call | mel + BPE tokenizer + decoder/KV-cache loop in Kotlin |
| Native build | new crate, all four ABIs | none (ORT already shipped) |
| Decode features | timestamps, language detect, no-speech, temperature fallback | we re-implement each |
| Model files | one GGML `.bin` | encoder + merged decoder + tokenizer.json |
| Runtime proof | mature on Android/ARM | ORT already used for glide and NWP |
| Desktop testing | `cargo` host build + WAV | JVM ONNX session |

**Decision:** whisper.cpp first (least app code, mature decoding), ORT-Whisper later as the second engine if we
want a pure-Kotlin path. The interface above is identical either way.

Native build plan for `whisper-rs`:

- Depend on `whisper-rs` with its `whisper-cpp` feature so whisper.cpp is built from source by cc/cmake inside
  cargo; keep `--locked` by committing the updated `Cargo.lock`.
- The outer CMake passes only the linker today. whisper.cpp's C/C++ build needs the NDK compilers per target, so
  the cargo invocation must also export `CC_<target>`, `CXX_<target>`, `AR_<target>`, `RANLIB_<target>`,
  `CMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake`, `ANDROID_ABI` and
  `ANDROID_PLATFORM`, and disable OpenMP (`GGML_OPENMP=OFF`).
- Start with `arm64-v8a` and `x86_64` (device + emulator); add the 32-bit ABIs once the engine works. The Gradle
  `abiFilters` are currently open, so the CMake change has to select the ABIs per build until then.
- Host build (`cargo check`/`cargo test`) still works for a WAV round-trip before any Android build, so the decoder
  can be validated on the desktop first.

## 4. Models

- `whisper-tiny` multilingual (GGML q5_1, ~32 MB) — default.
- `whisper-tiny.en` — better English accuracy, English only.
- Later rows: base/small, Moonshine, distil-whisper. The registry is data, not code.

A registry row declares id, display name, engine, language, file list (URL, SHA-256, bytes), license and
attribution. Installing downloads into `files/asr/<modelId>/`, verifies SHA-256, and only then marks it ready.
Deleting removes the directory. Selection is a preference (`voice.modelId`), the engine a second one
(`voice.engineId`), so a model can be swapped without touching settings screens.

Delivery is the open decision: first-use download vs bundled vs extension package. See section 8.

## 5. Permissions and lifecycle

- Add `android.permission.RECORD_AUDIO`. Runtime prompt must come from an Activity; the IME cannot show it.
- Settings → Voice input owns the prompt (activity-result launcher) plus a permission status row.
- The mic action checks `checkSelfPermission`; without it, a toast points at Settings → Voice input.
- Recording stops on `onFinishInputView`, on window hide, and on field change; partial audio is discarded, not
  committed into a different field.
- The `AsrController` is app-scoped like the other controllers, but an engine/model is loaded lazily on the first
  transcription and released after an idle timeout (default 60 s) to keep RAM down.

## 6. UI

- New `ImeActions.LocalVoiceInput` (`floris:voice_input`), separate from `ExternalVoiceInput` so the old hand-off
  stays available. Default quick-action arrangement uses the local one when a model is ready and the permission
  is granted, the external one otherwise.
- Recording overlay above the keyboard: level meter, elapsed time, stop and cancel, "model not installed" state
  with a link to Settings.
- Settings → Voice input: permission row, model list (name, size, language, download/delete/progress), engine
  picker (only when more than one engine is compiled in), max duration, "keep model loaded" toggle.

## 7. Milestones

1. **Core — done.** `AsrEngine`/`AsrResult`, registry, `AsrModelManager` (download, SHA-256, delete, select),
   `AudioRecorder`, `voice` prefs and the `files/asr` root. `AsrModelManagerTest` covers registry validation,
   install/progress, checksum and offline failures, the size-based install check and delete. The controller state
   machine stays with milestone 3.
2. **Whisper Tiny engine — done.** `whisper-rs` 0.16 (`whisper-rs-sys` 0.15) compiled from source, JNI
   `nativeLoad`/`nativeTranscribe`/`nativeFree`, Kotlin `WhisperNative` + `WhisperCppEngine`.
   - Host: `cargo run --release --example transcribe -- <model.bin> jfk.wav en` prints the exact JFK sentence
     in 0.4 s. The registry SHA-256 was checked against the downloaded file and matches.
   - Device: `WhisperCppEngineTest` loads the pushed model and transcribes `jfk.wav` (androidTest asset,
     public-domain sample from whisper.cpp) in 3.6 s on the API 35 emulator; asserts "fellow Americans",
     measured audio and inference time. The test skips when the model is not on the device.
   - Build: the Rust static lib links the shared C++ runtime (`c++_shared`, packaged by AGP), the NDK
     toolchain is passed through a generated wrapper file (`CMAKE_TOOLCHAIN_FILE_<target>`, because the
     cmake crate reads only that variable), and bindgen gets `--sysroot`, `--target` and the clang resource
     include for the ABI. All four ABIs build; `libdrift_native.so` is 3.6–3.8 MB per ABI.
   - Pushing the model for the test (emulator, needs root for the external files dir ownership):
     `adb push ggml-tiny-q5_1.bin /sdcard/Android/data/<applicationId>/files/asr-test/` then
     `chown` to the app uid; a release device will download it through the app instead.
3. **IME integration — done.** `ImeActions.LocalVoiceInput` (`floris:local_voice_input`) toggles
   `AsrController`; the recording/transcribing/error panel is `AsrOverlay` in `ImeRootWindow`; recording
   cancels on field and input-view changes. The default sticky quick action is now the local one (existing
   arrangements keep whatever they had; the external voice IME action is still selectable). The commit is one
   `emitText`, with a leading space after a word and no composing region, so raw editors are safe.
4. **Settings page — done.** `Settings > Voice input`: microphone permission prompt (plus app-settings link),
   model list with download progress/delete/select, and "keep model in memory". `INTERNET` was added to the
   manifest for model downloads only. Verified on the emulator: the 32 MB tiny model downloaded from Hugging
   Face, passed the SHA-256 check, installed and was selected.
5. **Device pass — next.** Needs a human voice: accuracy on a few phrases, record→text latency, RAM, a
   Termux/shell field, and airplane mode after the model is installed.

## 8. Decisions

1. **First engine — decided:** whisper.cpp via `whisper-rs`.
2. **Model delivery — decided:** download on first use (Hugging Face, `ggerganov/whisper.cpp`), SHA-256 verified.
3. **Default model — decided:** multilingual `whisper-tiny` q5_1; `tiny.en` is the second registry row.

## 9. Risks

| Risk | Mitigation |
|---|---|
| Model size and RAM on low-end phones | quantized tiny, lazy load, idle unload, max duration |
| First-load latency | show "loading model" in the overlay, preload on mic press |
| Silent or wrong output in a shell/raw editor | commit one text blob, never composing; field-change cancel |
| Noisy environments, weak tiny model | accept for v1; registry allows base/small |
| Network dependency for model download | SHA-256 + atomic rename + clear offline error; resume is a later improvement |
| Whisper.cpp build per ABI in CI | start with arm64 + x86_64, add 32-bit ABIs after the device pass |
| `RECORD_AUDIO` prompt not shown from the IME | permission lives in Settings; the action deep-links there |
| C++ runtime mismatch from the Rust static lib | `ANDROID_STL=c++_shared` for the module and the generated whisper toolchain; AGP packages `libc++_shared.so` |
| 32-bit bindgen needs target headers | host libclang lacks the NDK resource dir; CMake passes sysroot/target/`-isystem` per ABI |
| New `INTERNET` permission on a privacy-focused keyboard | only the model downloader uses it; audio and transcripts stay local, and the permission is documented in the manifest next to `RECORD_AUDIO` |
