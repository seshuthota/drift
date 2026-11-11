# Swipe CTC prototype — progress report

**Date:** 2026-11-08
**Location:** `ml/ctc/` (code), `ml/work/` (data + checkpoints, gitignored), `ml/results/` (metrics + manifest)
**Status:** prototype complete and evaluated; no Android integration yet.

---

## 1. Goal

Determine whether Drift can own its swipe decoder by training on permissively
licensed data, instead of shipping FUTO's pretrained weights (which carry the FUTO
Model Weights License and an attribution requirement).

**Answer: yes.** A 269k-parameter model trained only on MIT data reaches **89.4 %
top-1 on a random 4,000-row sample of the official test split**, with a 1.08 MB
ONNX export. That is numerically at parity with the best published from-scratch
CleverKeys model (89.31 % on their sealed test-2400) which used 5.6× the parameters
and ~3× the data.

---

## 2. Environment

| Item | Value |
|---|---|
| Python | 3.12.12 (`/home/curious/miniconda3/envs/llm`) |
| PyTorch | 2.10.0+cu128, CUDA available |
| GPU | NVIDIA RTX 5060 Laptop, 8 GB |
| CPU/RAM | 16 cores, 23 GB |
| ONNX / ORT | onnx 1.22.0 / onnxruntime 1.30.0 |
| Parquet | pyarrow 24.0.0 |
| Disk | ~13 GB free on `/` (data cache 109 MB, no venv kept) |

No CUDA install was performed: the existing `llm` conda env was used.

---

## 3. Licensing position

| Asset | License | Status |
|---|---|---|
| `futo-org/swipe.futo.org` dataset | MIT | **used** |
| `futo-org/swipe-negatives` | Apache-2.0 | usable; analyzed, not yet in training |
| `hermitdave/FrequencyWords` en lists | MIT | **used** (50k / 147k lexicons) |
| Code in `ml/ctc/` | Apache-2.0 | project license |
| FUTO swipe weights (`futo-org/futo-swipe`, `swipe-library`) | FUTO weights license / GPL-3.0 | **not used** |
| CleverKeys app + `CleverKeys-ML` | GPL-3.0 / no license | **not used** (research read-only) |

Hard rules applied: no FUTO weights, no FUTO model outputs, no distillation from
FUTO models, no GPL code reuse, no unlicensed repo content.

Dataset revisions used:

- `futo-org/swipe.futo.org` @ `d71bf5fd7f45b3e7c2ed2d76a21b0dbd3b4ba566` (2026-06-25)
- `futo-org/swipe-negatives` @ `ce576c7a8100a011049a11b4ffe721c1634788ab` (2026-04-28)

---

## 4. Data pipeline

### 4.1 Source layout

| Split | File | Size | Rows |
|---|---|---|---|
| train | `train.jsonl` | 5,157.75 MB | ~2.4 M lines (streamed) |
| dev | `dev.jsonl` | 304.26 MB | streamed |
| test | `test.jsonl` | 269.61 MB | 49,970 lines (verified by full scan) |
| collections | `swipe-2..5/*.jsonl` | 105–436 MB each | unfiltered, extra distributions |

`swipe-1` (the official splits) sentences come from Mozilla Common Voice English
Wikipedia text. Collections 2–5 add informal text, misspellings, deliberately
confusable words, and (swipe-5) multiple languages/layouts.

### 4.2 Per-row schema used

`word`, `sentence`, `data=[{t,x,y}...]`, `canvas_width/height`, `session`,
`distance`. Verified empirically: **x/y are already normalized to the keyboard
canvas in [0,1]** (`canvas_*` are reference dimensions only; dividing again is wrong).

### 4.3 Featurization and filtering

- Filter targets to `^[a-z]{2,26}$` (lowercased; punctuation, apostrophes, uppercase dropped).
- CTC feasibility: `len(word) + adjacent_repeats <= 32` (T'=32 frames after the stride-2 stem).
- Resample each trajectory by arc length to **64 points** → `[2, 64]` float16.
- ~16.4 % of raw rows are dropped by the word/feasibility filter and bad paths.

### 4.4 Cached datasets

| Cache | Rows | Note |
|---|---|---|
| `ml/work/train_400k.npz` | 400,000 | 478,470 lines scanned; 78,468 filtered; 2 dedup |
| `ml/work/dev.npz` | 8,000 | head of dev split, content-deduped against train |
| `ml/work/test.npz` | 4,000 | head of test split |
| `ml/work/test_rand.npz` | 4,000 | **reservoir sample of all 49,970 test lines, seed 42** |

The 100k training cache was overwritten when the 400k cache was moved into place;
the run-1 checkpoint remains. The first 100k rows of `train_400k.npz` reproduce the
same stream with negligible dedup differences.

**Headline evaluation uses `test_rand.npz`** (unbiased); head-of-file splits are
reported for completeness only. Exact-trajectory hash dedup found 1 duplicate across
the 100k train/dev/test caches and 2 train-internal duplicates in the 400k cache; no
test trajectory matched a training trajectory.

---

## 5. Models

Common architecture (`model.py`):

```text
input [B,2,64]
  Conv1d(2→ch, k=5, stride=2, pad=2) + BatchNorm + GELU   → [B,ch,32]
  4 × residual block: Conv1d(ch→ch, k=3, dilation=d) + BN + GELU + Conv1d(ch→ch,1)
                      added to input, d ∈ {1,2,4,8}
  Conv1d(ch→27, 1) → log_softmax → [B,32,27]   (a–z, blank = 26)
```

| Run | ch | Params | Checkpoint |
|---|---|---|---|
| run1 | 96 | 152,859 | `ml/work/run1/best.pt` (627 KB) |
| run_400k | 128 | 269,339 | `ml/work/run_400k/best.pt` (1.07 MB) |

Training recipe (identical except ch):

| Setting | Value |
|---|---|
| Loss | CTC (`zero_infinity=True`, mean reduction), blank 26 |
| Optimizer | AdamW, lr 3e-3, wd 1e-2 |
| Schedule | 500-step warmup + cosine to 0 |
| Batch | 256 |
| Epochs | 40 |
| Grad clip | 5.0 |
| Seed | 1234 |
| Device | RTX 5060 (`cuda`) |
| Time | run1 82 s; run_400k 361 s |

Selection: best dev **greedy** accuracy checkpoint (run1: epoch 31, 65.74 %;
run_400k: epoch 35, 72.90 %). Training curves are preserved in
`ml/results/train_100k_metrics.jsonl` and `train_400k_metrics.jsonl`.

---

## 6. Decoding

1. **Greedy**: per-frame argmax → collapse repeats → drop blanks. Used as a bound
   on emission quality, not as a shipping decoder.
2. **Lexicon prefix beam** (`beam.py`): standard CTC prefix beam over a prefix
   dictionary, width 120, top-12 non-blank chars per frame, final score
   `log P(prefix) + α·log1p(freq) + β·len`, β=0.

| Lexicon | Lines | α |
|---|---|---|
| `ml/work/en_50k.txt` (FrequencyWords) | 50,000 | 0.4 |
| `ml/work/en_147k.txt` (top of `en_full`, 1,656,996 lines) | 147,000 | 0.8 |

α was tuned on 500 dev rows only. Beam width 200 did not change test top-1
(88.72 % → 88.72 %), so width 120 is not limiting.

---

## 7. Results

### 7.1 Headline

| Train rows | Model | Split | Greedy t1 | Beam t1 | Beam t3 | Beam t5 |
|---|---|---|---|---|---|---|
| 100k | ch96 | dev 8k (head) | 65.7 % | 86.4 % | 92.2 % | 93.3 % |
| 100k | ch96 | test 4k (head) | 64.8 % | 86.2 % | 92.2 % | 93.2 % |
| 100k | ch96 | test 4k (random) | 66.0 % | 87.0 % | 92.7 % | 93.7 % |
| 400k | ch128 | dev 8k (head) | 72.9 % | 88.5 % | 93.0 % | 93.8 % |
| 400k | ch128 | test 4k (random, 50k lex) | 74.1 % | 88.7 % | 93.6 % | 94.5 % |
| 400k | ch128 | test 4k (random, 147k lex) | 74.1 % | **89.4 %** | 95.0 % | 95.9 % |

Beam width 200 on the 50k-lexicon setting: 88.72 / 93.63 / 94.53.

### 7.2 Per word length (400k model, 2,000 random test rows, 50k lexicon, α=0.4)

| Length | Rows | Beam t1 | Greedy t1 |
|---|---|---|---|
| 2–3 | 787 | 97.7 % | 96.4 % |
| 4–5 | 493 | 87.2 % | 77.3 % |
| 6–8 | 514 | 80.7 % | 52.7 % |
| 9+ | 206 | 80.1 % | 37.4 % |

The beam recovers most long-word emission damage; the remaining gap is lexicon and
context, not raw trajectory decoding.

### 7.3 Lexicon coverage effect (2,000 random test rows, 50k lexicon)

| Metric | Value |
|---|---|
| OOV rate | 3.8 % (75/2000) |
| Beam t1 on in-vocabulary words | **92.4 %** |
| Overall beam t1 | 88.9 % |
| Upper bound if every OOV word were recovered | 96.3 % |

Switching to the 147k lexicon moved full-test top-1 from 88.72 % to 89.38 %
(+0.65 pt), confirming lexicon coverage was the dominant confound in comparisons.

### 7.4 Latency and size

| Item | Value |
|---|---|
| ONNX model | `ml/work/ctc_swipe_ch128.onnx`, 1,079,012 B (fp32) |
| ONNX parity | max abs log-prob diff 9.54e-05, argmax match 100 % (32 rows) |
| Encoder, batch 1, ORT CPU (laptop) | 0.167 ms mean, 0.185 ms p90 |
| Python decoder, end-to-end (inference + beam) | ~13 ms/row (prototype) |

These are laptop numbers; phone latency and memory must be measured on device.

### 7.5 Error analysis

Top confusions (beam, random test): `its→is` (6), `four→for` (3), `process→proves`
(2), `three→the` (2), `or→it`, `leech→lech`, `heel→hell`, `by→be`.
Long-tail failures are names missing from the 50k lexicon (`asheville→agile`,
`mokranjacs→moran`) and long-word emissions (`ticket→river`).

Cross-check against FUTO's hard negatives (`swipe-negatives`, 143,794 words):

| Metric | Value |
|---|---|
| Test target words covered by the table | 97.7 % |
| Total beam errors (2,000 rows) | 205 |
| Errors whose prediction is in the target's hard-negative list | **124 (60.5 %)** |

This is the evidence that a shape-aware reranker is the next highest-value lever.

---

## 8. Reference comparison (not head-to-head)

| Engine | Test top-1 | Test set | Notes |
|---|---|---|---|
| Drift 400k/ch128/147k | **89.4 %** | random 4k of official test | 269k params, 400k rows, single seed |
| CleverKeys ship `phaseM_kd_fresh_w1` | 89.31 % (config B) / 88.93 % (config A) | sealed test-2400 | 1.51M params, ~1.16M rows, 3 seeds, tuned preset |
| CleverKeys ch128 | 87.92 % | sealed test-2400 | 689k params |
| FUTO encoder + refine (ceiling) | 84.83 % | test-2400 | external reference |
| FUTO encoder only (floor) | 79.25 % | test-2400 | external reference |
| CleverKeys transformer (removed) | 74.62 % | test-2400 | superseded |
| CleverKeys SHARK2 | 67.50 % | test-2400 | geometric engine |

Different test sets, lexicons and selection protocols: the comparison is a scale
reference, not a controlled benchmark. Cross-test noise of ±1 pt and seed noise
(~1 pt at their own measurement) exceed the nominal difference.

---

## 9. Artifacts

All hashes and sizes: `ml/results/MANIFEST.txt`.

| Artifact | Path |
|---|---|
| Training caches | `ml/work/train_400k.npz`, `dev.npz`, `test.npz`, `test_rand.npz` |
| Checkpoints | `ml/work/run1/best.pt`, `ml/work/run_400k/best.pt` |
| ONNX | `ml/work/ctc_swipe_ch128.onnx` |
| Lexicons | `ml/work/en_50k.txt`, `ml/work/en_147k.txt` |
| Eval JSONs | `ml/results/eval_*.json` |
| Training curves | `ml/results/train_*_metrics.jsonl` |
| Prediction dumps | `ml/results/dev_preds_100k.txt`, `dev_preds_400k.txt` |
| Hard negatives parquet (analysis only) | `/tmp/swipe_negatives.parquet` (re-downloadable; sha in manifest) |

Nothing under `ml/` is committed to git yet; the working tree also contains
unrelated user modifications outside `ml/`.

---

## 10. Reproduction

```bash
# data (streams from HF; MIT)
python3 ml/ctc/data.py --out ml/work --train-rows 400000 --dev-rows 8000 --test-rows 4000
python3 ml/ctc/sample_test.py --out ml/work/test_rand.npz --rows 4000

# train
/home/curious/miniconda3/envs/llm/bin/python ml/ctc/train.py \
  --train ml/work/train.npz --dev ml/work/dev.npz \
  --out ml/work/run_400k --epochs 40 --ch 128

# evaluate (50k and 147k lexicons)
/home/curious/miniconda3/envs/llm/bin/python ml/ctc/eval_beam.py \
  --ckpt ml/work/run_400k/best.pt --npz ml/work/test_rand.npz --alpha 0.4
/home/curious/miniconda3/envs/llm/bin/python ml/ctc/eval_beam.py \
  --ckpt ml/work/run_400k/best.pt --npz ml/work/test_rand.npz \
  --lex ml/work/en_147k.txt --max-words 147000 --alpha 0.8

# export + parity
/home/curious/miniconda3/envs/llm/bin/python ml/ctc/export_onnx.py \
  --ckpt ml/work/run_400k/best.pt --out ml/work/ctc_swipe_ch128.onnx
```

---

## 11. Known limitations

- 400k of ~1 M train rows; data scaling was still improving accuracy.
- English QWERTY only; no layout-agnostic slot permutation and no multilingual model.
- 50k/147k FrequencyWords lexicon, not the app's own dictionary; lexicon is the
  largest measured confound (0.65 pt from 50k → 147k).
- Single seed; no significance testing yet.
- Test evidence is a 4,000-row random subset (±~1.1 pt 95 % CI), not the full split.
- Python beam (~13 ms/row) is not representative of shipping decode cost.
- No context/next-word LM; homophone and short-word errors remain.
- Dataset is web volunteers, not Drift users; domain shift unmeasured.

---

## 12. Android integration (experimental, 2026-11-08)

The 400k/ch128 checkpoint is wired into the Drift keyboard's glide pipeline and
installed on the test device (`io.github.seshuthota.drift.debug`).

### 12.1 Changes

| File | Change |
|---|---|
| `gradle/libs.versions.toml` | `onnxruntime = "1.22.0"`, `onnxruntime-android` library |
| `app/build.gradle.kts` | `implementation(libs.onnxruntime.android)` |
| `app/src/main/assets/swipe/ctc_swipe.onnx` | exported model (1,079,012 B) |
| `.../gesture/NeuralGlideTypingClassifier.kt` | ONNX session, 64-point featurizer, CTC prefix beam, compact trie, hybrid fallback |
| `.../gesture/GlideTypingManager.kt` | classifier is now `HybridGlideTypingClassifier(context)` |

The hybrid classifier implements the existing `GlideTypingClassifier` interface, so
the rest of the glide pipeline (preview, trail, commit, auto-space, capitalization) is
unchanged.

### 12.2 Runtime details

- ONNX Runtime Android 1.22.0, CPU EP, model loaded from assets as bytes.
- Lexicon = the active subtype's word list (built-in dictionary: 49,981 words),
  converted to a compact prefix trie; frequency from the app's 0–1 scale, scored as
  `0.8 * ln(1 + 255 * freq)`.
- Beam width 120, top-12 characters per frame, blank = 26.
- Gesture points and key geometry are remapped from the keyboard box to the a–z key
  bounding box before resampling, matching the training normalization.
- Fallback: statistical template classifier if the model fails to load or returns no
  candidates.
- Diagnostics: `adb logcat -s DriftGlide` (this vivo device suppresses app logs after
  the first IME bind, so verification was functional).

### 12.3 On-device verification (I2401, Android 16, 1440×3168)

Driven with `adb shell input motionevent` over Drift's QWERTY layout:

| Intended path | Committed text | Result |
|---|---|---|
| t → h → e | `the` | correct |
| w → e | `we` / `We` | correct, auto-capitalized |
| k → e → y → b → o → a → r → d | `keyboard` | correct |
| h → e → l → o (single l) | `help` | expected: path spells "helo" |

No crashes. Debug APK grew from ~41 MB to ~116 MB, dominated by ONNX Runtime native
libraries for all ABIs.

### 12.4 Known limits of this build

- English QWERTY only; other layouts/languages will decode against the same a–z model.
- Dictionary is the built-in ~50k list; the app's frequency data is coarse (0–255).
- First glide after IME start pays session-load cost.
- Debug build, not signed/release-optimized; no per-ABI split.
- No on-device latency instrumentation yet (Python prototype measured ~13 ms/row;
  laptop encoder 0.167 ms).

### 12.5 On-device scoring fix (2026-11-08)

The app dictionary stores frequencies as 128-255, so the original device prior
`0.8 * ln(1 + 255 * freq)` spanned only ~0.55 nats and was effectively off. Evaluated
offline with the exact device setup (`ml/ctc/eval_app_dict.py`, app dictionary,
48,505 a-z words, 3.1 % OOV on test):

| Prior | dev 4k top-1 | test_rand 2k top-1 | test_rand 2k top-3 |
|---|---|---|---|
| none | 87.83 % | 88.95 % | 93.95 % |
| old device formula | — | 89.20 % | 94.15 % |
| linear, k = 16 (`ln P + 16 * freq`) | 89.83 % | **90.35 %** | 94.65 % |

k was tuned on dev only (plateau 12-20); 16 is now `FREQUENCY_WEIGHT` in
`NeuralGlideTypingClassifier`.

Also changed on device:

- Contractions are glideable: `don't`, `i'm`, ... are decoded from their letters
  (`dont`, `im`) and offered with the plain spelling, most frequent first; `i'…` is shown as `I'…`.
- After a glide commits, every candidate stays in the smartbar as a
  `GlideSuggestionCandidate`; tapping one replaces the committed word (only if it is
  still directly before the cursor). Typing a key or moving the cursor dismisses them.
- Glide counts as "suggestions on", so candidates show even with word/emoji suggestions off.
