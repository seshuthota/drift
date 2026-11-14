# Drift local voice input (ASR) — design and plan

**Status:** decisions taken, milestone 1 (core) landed; Whisper engine next
**Scope:** on-device speech-to-text for the IME, Whisper Tiny first, engine and model replaceable
**Out of scope for v1:** streaming/partial transcripts, punctuation restoration model, standalone VAD model,
  translation, hands-free/always-listening modes

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
2. **Whisper Tiny engine — next.** Cross-compile `whisper-rs`, JNI load/transcribe/free, then transcribe a WAV on
   the host before wiring Android.
3. **IME integration.** Mic action, overlay, permission gate, commit/cancel, lifecycle stops.
4. **Settings page.** Model management UI and permission prompt.
5. **Device pass.** Accuracy on a few phrases, latency (record→text), RAM, airplane-mode run, Termux/shell field
   safety (raw editor: commit text only, no composing).

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
