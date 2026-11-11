# Drift typing intelligence — status and plan

**Last updated:** 2026-11-11 (Termux raw-editor input; first version 2026-11-08)
**Scope:** glide decoder, next-word prediction, typo correction/autocorrect, and the input pipeline around them
**Companion documents:** `ml/REPORT.md` (swipe CTC prototype), `ml/nwp/README.md` (next-word model)

---

## 0. Where we are

### Shipped in the app (branch `drift/glide-and-next-word`)

| Area | What is in the app | Measured |
|---|---|---|
| Glide decoder | 269k-param CTC encoder (`assets/swipe/ctc_swipe.onnx`, 1.08 MB) trained on the full official train split (786,540 rows) + Kotlin lexicon beam over the app dictionary. Prior is `6.87 * ln(1+count)/ln(1+maxCount)` (the dev-tuned `0.4 * ln(1+count)`), minus `20` times the squared distance from the stroke ends to the first and last letters. Statistical decoder is the fallback | 90.50 % top-1 with the endpoint term on the sealed 4k set (90.33 % without it; length 2 stays 98.61 %). The 400k checkpoint on the same rows was 89.38 / 94.17 |
| Glide + context | Next-word LM scores glide candidates, λ = 0.4, OOV floor 10 | +0.7 pt top-1 on FUTO sentences (previous dictionary; fusion was not re-run) |
| Next-word prediction | LSTM h256, 20k vocab, int8 (`assets/nwp/nwp_lstm.int8.onnx`, 3.08 MB), completions while typing. Predictions absent from the shipping dictionary are dropped; the ONNX file and `vocab.txt` are unchanged | web 16.2 / 26.7 % top-1/top-3, chat 21.4 / 34.4 % |
| Typo correction | `TypoCorrector`: keyboard-aware weighted Damerau-Levenshtein over a trie of the app dictionary, ranked with `ln(1+count)/2` + next-word LM | 87.8 % top-1 / 95.5 % top-3 on synthetic single typos (seed 7, 3,000 words), 0.21 ms/word (laptop JVM) |
| Suggestion bar | Corrections and completions on one score scale; best one leads (`thw` → the, `hel` → help, `dont` → don't, `recieve` → receive) | verified on device |
| Spell checker | `spell()` flags unknown words and likely dictionary misspellings, with suggestions | unit-tested only |
| Autocorrect | On space / punctuation, only when confident. Unknown words need margin ≥ 2.5 over the runner-up. A fix that only adds apostrophes (`dont` → `don't`) needs margin ≥ 0.8 against the runner-up, or against the typed word when that word is in the dictionary. Other dictionary words are left as typed. Backspace right after undoes it and the word is not corrected again. Bold candidate = will autocorrect. Pref `correction__auto_correct` (default on), quick action toggles it | Unit tests: `recieve`, `teh`, `dont` correct; `ill`, `id`, `were`, `its`, `pout` stay. The 98.1 % precision / 71.5 % recall figure is from the previous 128–255 dictionary |
| Browser omnibox | Inline autocompletion is no longer treated as a user selection, so suggestions keep working in address bars | unit-tested; device run did not trigger an inline completion |
| Short glides | Two-letter words on neighbouring keys (`we`, `as`, `re`) are recognized on release; vertical moves stay taps. A real finger stroke from `o` to `k` still commits `on` after the endpoint term (preview had `ok` second). Parked | synthetic + one device retest |
| Raw editors (Termux) | `TYPE_NULL` fields get a tail-only commit. Backspace sends `U+007F` and keeps going after Drift's buffer is empty. Enter commits `\n` (Termux makes that a carriage return). Arrows, Home/End, Page Up/Down, forward delete, and word-delete (Ctrl+W) commit the sequences Termux's `KeyHandler` writes for a normal shell. Ctrl+Z is not sent (that byte suspends the job). `RawInputReplaceTest` | installed on I2401. Letters, backspace, and Enter checked on device. Navigation sequences are unit-tested and in that build; not pressed on device in this session |

### Device check (Vivo I2401, Android 16, injected gestures)

- Typed corrections and autocorrect behave as above, including fast bursts (the autocorrect candidate is computed
  synchronously on the separator, so there is no race with async suggestions).
- Glide sentences: 34/34 words on four sentences; one more sentence failed only because the glide started on `q` at
  the screen edge (see issue 4).
- Injected gestures are slow (~57 ms per point), so this checks correctness, not real-finger feel.

### Commits this round

`99732acd` omnibox fix · `920556e5` ML code · `d5fd3bf0` typo correction · `48abdf57` ranking vs completions and
dictionary misspellings · `5852bbbf` autocorrect · `2d286bde` + `7d42e9cc` short glides.

---

## 1. Issues found

Ordered by impact on users.

1. **The shipping dictionary is the cleaned list (wired 2026-11-11).** `ime/dict/data.json` is 60,564 words
   with raw corpus counts (10 … 28,787,591, max `you`). Built by `ml/lexicon.py`: FrequencyWords `en_147k`
   (MIT) intersected with Hunspell en_US/GB/CA/AU, plus app-dictionary words Hunspell accepts and FineWeb+OASST
   attests at least 5 times (contractions; FrequencyWords stores `'t` separately). A word one cheap QWERTY edit
   from a word ≥ 100× more common is dropped unless the corpus attests it. `teh`, `recieve`, `definately`,
   `untill`, `thier`, `dont`, `im` are gone. `don't` / `i'm` are 206,125 / 152,651. `pout` stays at 607.
   1,153 words contain an apostrophe.
   - `getFrequencyForWord` returns `ln(1+count)/ln(1+maxCount)` in 0–1. User-dictionary rows still use `/255`.
   - Neural glide `FREQUENCY_WEIGHT` is 6.87. The statistical fallback formula is unchanged.
   - `TypoCorrector` uses `ln(1+count)/2`. Unknown words autocorrect at margin 2.5. An unknown word whose best
     fix only inserts apostrophes (`dont` → `don't`) uses margin 0.8, because `don` (4,158,644) sits close
     behind `don't` (206,125). Dictionary words change only when the edit adds an apostrophe, and that
     compares with the typed word (`ill`, `pout` stay).
   - Next-word suggestions are filtered to this dictionary. The ONNX model still has ids for removed spellings
     such as `dont` and `recieve`; those are dropped in `LatinLanguageProvider.suggest`.
   - Same 400k checkpoint on `test_rand` (4,000 rows): 89.38% / 94.17% top-1/top-3. Length ≤ 3 is 97.32%
     (previous dictionary 89.98% / 94.67%, length ≤ 3 97.38%).
   - `spell()` still uses the likely-misspelling rule and can underline a rare real word.
2. **Short glides on device still prefer the common word, and a weaker short prior makes the benchmark worse.**
   `op` and `ok` decoded as `of` on device. The sealed 4,000-row set with the shipping prior (k = 0.4) is
   length 2: 98.19% top-1 / 100% top-3 (719 words), length 3: 96.61% / 98.87% (884), length ≥ 4: 84.06% /
   90.70% (2,397). The misses are in the longer words. On dev (8,000 rows) the same prior is 89.39% overall
   and 98.27% on length 2. Lowering the weight for short candidates, and shrinking only the gaps inside a
   length so `of` keeps its boost against long words, both lost accuracy (length 2 down to 97.76–82.73%,
   overall down with them). Where acoustics and frequency disagree on a length-2 target, frequency fixes 10
   and causes 1. Dev contains 3 length-2 targets under 1e6 counts, so `ok` / `op` are almost absent there.
   The uniform prior stays. `ml/ctc/eval_app_dict.py` prints the length buckets and can replay both sweeps.
3. **Real typing data is missing.** Typo numbers come from a synthetic error model (neighbour keys, omissions,
   doubles, swaps, apostrophes). Real error rates, and names or slang outside the dictionary (which autocorrect
   could mangle), are unmeasured. Mitigations in place: no autocorrect of capitalized-mid-sentence or all-caps
   words, backspace undo, "not again" memory per field.
4. **Edge back gesture on this phone.** A glide starting on `q`/`p` (≈ 21 dp from the edge) hides the keyboard on
   the Vivo, with Gboard too. Drift already requests a gesture exclusion; Vivo's gesture handler seems to ignore
   it. Seen with injected touches only — confirm with a real finger before acting.
5. **The shipped Kotlin glide decoder has no laptop test.** Accuracy is measured with the Python copy; the Kotlin
   path is only exercised on device. A JVM test would need the desktop ONNX Runtime on the test classpath plus
   stand-ins for `Context`/`Log` — deferred as not worth it yet.
6. **Smaller bugs noticed:**
   - "Double-space period" is implemented in `ImeController.emitText`. A space typed while the field already
     ends in a letter or digit plus a space is replaced with ". ". Off in password fields. The preference
     stays on by default. Pattern checks: `DoubleSpacePeriodTest`.
   - The auto-space feedback link pointed at `github.com/seshuthota/drift/discussions/1935`, an upstream number
     carried over by the rename. It now points at `florisboard/florisboard` discussion 1935, which is the
     real "auto-space after punctuation" thread.
   - `:app:lintDebug` fails with 52 pre-existing errors (mostly `ImpliedQuantity` / `StringFormatInvalid` in
     translated strings); CI does not run lint.
   - The autocorrect switch uses the spellcheck icon, same idea as the auto-space row beside it.
   - **Termux (and any `TYPE_NULL` editor) — fixed 2026-11-11.** Drift replaces the whole current span on every key via `setComposingRegion` + `commitText`. A normal field rewrites that span. Termux keeps a scratch buffer, writes it to the shell, and clears it, so each key appended the whole word (`h`, `hhe`, `hhehel`). Key events (`deleteSurroundingText`, Enter, arrows) do not reach the shell. Letters send only the new tail. Backspace sends `U+007F` and keeps sending one after the tracked buffer is empty. Enter commits `\n`. Arrows, Home/End, Page Up/Down, and forward delete commit Termux's normal-shell sequences (`ESC [ A` and the rest). Word-delete commits Ctrl+W. Ctrl+Z is not sent.
7. **Licensing/attribution — on the About screen.** Glide training data is the FUTO swipe dataset (MIT).
   Next-word training data is FineWeb (ODC-By) and OpenAssistant Conversations (Apache-2.0).

---

## 2. What to focus on next

In recommended order. Each item says what it fixes.

1. **Shipping lexicon — done.** Wired as issue 1 describes. Synthetic typo seed 7 is 87.77% / 95.47%
   top-1/top-3 (unit-test guards 87% / 95%). Statistical glide unit test, 120-word log-frequency sample:
   clean 0.867 / 1.00 top-1/top-8; noisy 0.633 / 0.792 / 0.900 top-1/3/8. Report: `ml/results/lexicon_report.json`.
2. **Glide data scale — full train split, wired into the app.** `train.jsonl` ends at 939,550 lines
   (786,540 kept after the same word filter as the 400k run; dev and both test caches were hash-excluded,
   2 duplicates dropped). Checkpoint `ml/work/run_1m/best.pt`, same ch128 recipe, 40 epochs, 649 s.
   Dev greedy 75.84% (400k was 72.90%). Shipping dictionary, k = 0.4, sealed 4k: **90.33% / 94.67%**
   top-1/top-3 against 89.38% / 94.17% for the 400k checkpoint. McNemar on the paired rows: 83 only the new
   model got right, 45 only the old one, exact p = 0.001. Length 2/3/≥4 top-1: 98.61% / 96.72% / 85.48%
   (400k: 98.19% / 96.61% / 84.06%). ONNX parity is exact on argmax. That file is now
   `assets/swipe/ctc_swipe.onnx`. `swipe-2/3/4` were ablated and not adopted (below). About 1.9% of
   raw lines have `distance` ≥ 100000; those were not dropped, matching the 400k filter. Report:
   `ml/results/eval_test_rand_1m_appdict.json`.
   - Extra collections, distance ≤ 150 (the body of the official split; the ≥ 100000 pile is 7–12% of
     those files), held out from dev and test, then each added to the 786,540-row split and trained
     with the same ch128 recipe. Kept rows: swipe-2 23,213, swipe-3 34,140, swipe-4 43,909.
   - Dev beam, shipping prior plus endpoint weight 20: full split about 90.08%; +swipe-2 90.11%
     (length 2 98.12%); +swipe-3 89.99% (98.05%); +swipe-4 90.01% (97.98%). None clears +0.5 pt, and
     length 2 is lower, so the app stays on the full split. The sealed set was not used for this.
3. **Short-word prior — measured, left uniform.** Issue 2. The slice that training can still move is length ≥ 4
   (84.06% top-1 on the sealed set).
4. **Personal learning — done, opt-in, off by default.** Pref `correction__personal_learning`. A word that is
   not already in the lexicon is saved on the third commit, and immediately when autocorrect is undone.
   Nothing is recorded in incognito mode. The word is stored in the internal user dictionary (count 100 in
   the in-memory lexicon, so it is known and not autocorrected, and it does not outrank common words) and
   the glide word list is reloaded. `PersonalLearningTest`.
5. **Shape-aware reranker (Phase 2) — dev gate failed, not shipped.** Acoustic top-16 on the 8k dev
   set holds the target on 95.44% of rows (5.35 pt above the 90.09% shipping decoder, and every
   shipping-correct row is already in that pool). The pre-registered pairwise logistic model
   (L2 = 1, 5-fold by target word) scored 89.48% and lost length 2, 3, and ≥4. A light L2 (1e-4)
   reached 90.84% by lifting length ≥4 from 85.63% to 87.15%, and dropped length 2 from 98.34% to
   97.25%, so it misses the short-word gate too. Adding only a DTW term to the frozen shipping
   score gained 0.14 pt (p = 0.28) and also dropped length 2. Report: `ml/results/rerank_shape_dev.json`.
6. **Quick fixes — done.** Double-space period is in the K3 pipeline, and the autocorrect switch has an icon.
   The feedback link was already done (issue 6).
7. **Licensing notices — done.** About shows the FUTO swipe dataset (MIT), FineWeb (ODC-By), and
   OpenAssistant Conversations (Apache-2.0). Multilingual glide (Phase 4) is still later.
8. **Termux raw input — done.** Issue 6. Letters, backspace, Enter, arrows, Home/End, Page Up/Down,
   forward delete, and word-delete. Installed on I2401. Navigation sequences are covered by
   `RawInputReplaceTest` and were not pressed on the phone in this session.

---

## 3. Decisions needed

1. **Accuracy bar — decided.** The Phase 1 target of 90–91 % is met (90.50 % sealed, with the endpoint term).
   The later attempts to push past that (extra swipe files, short-word prior, shape reranker) did not clear
   their gates. This version stays on the shipping model. FUTO's 92.9/93.5 % is a different split and a
   larger model pair, and is not a gate here.
2. **Lexicon source — decided.** FrequencyWords plus Hunspell, keeping corpus-attested app words. Wired into
   `ime/dict/data.json` (issue 1).
3. **Autocorrect default.** Stays on. The cleaned list is what that default now runs against.
4. **Personal learning — decided.** Off by default (opt-in). When on, incognito mode records nothing. Focus item 4.
5. **Language/layout order** after English QWERTY.
6. **FUTO weights — decided.** Stay dataset-only. The accuracy bar in decision 1 was met without them.
7. **Device data collection.** Opt-in capture of swipe traces (no text) to measure domain shift.

---

## 4. Evaluation protocol

- **Sealed test:** keep `test_rand.npz` (seed 42) untouched; expand to a 10,000-row random sample before the next
  model family (95 % CI ≈ ±0.6 pt instead of ±1.1).
- **Seeds:** 3 seeds per comparative claim; McNemar on identical rows for any claimed gain.
- **Report:** top-1/3/5, per-length (especially 2–3 letters), OOV rate, latency, model size.
- **Typo/autocorrect:** `TypoCorrectorTest` synthetic benchmark (seed 7) is a regression guard, not a real-world
  number; add a real typo set when one with a usable licence is found.
- **Device:** re-run the guarded glide sentence check after decoder changes (see project memory for the setup).
- **Frozen baselines:** glide 400k/ch128/147k = 89.38 / 95.03 / 95.90 % top-1/3/5; previous app-dictionary
  decoder with LM λ 0.4 = 89.87 % top-1; typo corrector on the 128–255 dictionary = 89.1 / 96.9 % top-1/3.
- **Wired lexicon (2026-11-11):** same 400k checkpoint, log prior k = 0.4, 60,564-word list = 89.38 / 94.17 %
  top-1/3 on `test_rand` (4k). Typo corrector seed 7 = 87.77 / 95.47 % top-1/3.
- **Full train split (2026-11-11), in the app:** 786,540 rows, same architecture = 90.33 / 94.67 %
  top-1/3 on that sealed 4k set. McNemar exact p = 0.001 against the 400k checkpoint.

---

## 5. Phases (glide decoder)

### Phase 1 — data scale and lexicon · **done**

- Full official train split — done (786,540 kept rows; the file is shorter than the old ~1M estimate).
  Same word filter as the 400k run. Result in focus item 2. The app loads this ONNX.
- `swipe-2/3/4` ablation — done, not adopted. Distance ≤ 150. Dev beam did not move (focus item 2).
- Lexicon sweep — done; the app loads that list (issue 1).
- Gates: ≥ +0.5 pt with McNemar p < 0.05; no regression on 2–3-char words. Target 90–91 %.
  The full-split run cleared this on the sealed 4k set (+0.95 pt, p = 0.001, length 2 and 3 both up).

### Phase 2 — shape-aware reranker · measured, not adopted

- Dev test, 2026-11-11, script `ml/ctc/rerank_shape.py`. Pool is the acoustic beam's first 16 words.
  Features: acoustic score, log-frequency, length, the two endpoint squared distances, and DTW to the
  key-center polyline. No swipe-negatives feature and no context term. 5-fold GroupKFold on the target
  word. The sealed set was not read.
- Oracle top-16 is 95.44%. Shipping decoder on the same rows is 90.09% / length 2 98.34% / length 3
  95.63% / length ≥4 85.63%. Every row the shipping decoder gets right is already inside the pool.
- Pre-registered pairwise logistic, L2 = 1: 89.48% top-1 (−0.61 pt; McNemar p = 0.002 the other way).
  Length 2/3/≥4: 97.69% / 94.96% / 85.05%. Gate failed.
- Light L2 (1e-4), not the pre-registered setting: 90.84% (+0.75 pt, p = 2e-5) with length ≥4 at 87.15%,
  but length 2 falls to 97.25%. Same gates fail.
- DTW coefficient on the frozen shipping score: 90.23% (+0.14 pt, p = 0.28), length 2 at 97.33%.
- An MLP is not the next step. The linear model did not plateau at a gain that keeps short words.
  Gates that were required: +0.5 pt top-1, length 2 at or above 98.34%, length 3 not below the shipping decoder.

### Phase 3 — context LM · **done (v1)**

- LSTM next-word model trained on FineWeb (ODC-By) + OASST (Apache-2.0), shipped int8, fused into glide (λ 0.4)
  and used for completions and correction ranking.
- Remaining: more conversational text, and a measurement of the fusion gain on chat-style sentences.
  Personal learning is focus item 4 and does not retrain this model.

### Phase 4 — layout-agnostic and multilingual · later

- Slot-permutation training with key centers from layout JSONs; `swipe-5` data (en, de, es, fr, lt, pl…; QWERTY,
  AZERTY, QWERTZ, Dvorak…), single-finger only at first; per-language alphabets and tries.
- Gates: AZERTY/QWERTZ/Dvorak within ~3 pt of QWERTY; de/es/fr ≥ 80–85 % top-1; no QWERTY regression.

### Phase 5 — Android integration · **done (v1)**

- ONNX Runtime on device, Kotlin trie + CTC prefix beam, statistical fallback, LM fusion. Training and app
  normalization match (FUTO's canvas is the letter-key box, as in `NeuralGlideTypingClassifier.featurize`).
- Remaining: on-device latency numbers on a mid-range phone; a Kotlin-path accuracy test (issue 5).

### Phase 6 — hardening and product · in progress

- Done: typo correction, autocorrect with undo, short-glide recognition, omnibox fix, personal learning
  (focus item 4), About licensing (issue 7), double-space period, Termux raw input (focus item 8).
- Remaining: a regression suite of golden traces. Real-finger confirmation of the edge-back gesture (issue 4)
  and a Kotlin-path accuracy test (issue 5) are still deferred. Phase 3 still wants more conversational
  next-word text and a chat-sentence fusion measurement. Phase 4 (multilingual glide) has not started.
  Decisions still open: language order (5), opt-in device traces (7). The sealed set stays at 4,000 rows
  until the next model family.

---

## 6. Risk register

| Risk | Impact | Mitigation |
|---|---|---|
| Dictionary coverage | names and slang are absent; subtitle counts inflate `don` over `don't` | cleaned list is shipped (issue 1); apostrophe margin 0.8; undo + memory; personal learning |
| Autocorrect mangles names/slang | user frustration | conservative margins, undo + memory, personal learning |
| Synthetic-only typo evaluation | numbers do not match real typing | real typo set; opt-in metrics |
| Web-volunteer vs Drift-user domain shift (glide) | unknown accuracy drop | opt-in device traces |
| Test subset bias (4k) | false progress | 10k sample, 3 seeds, McNemar |
| License contamination (FUTO weights/outputs) | legal | data-only rule; review every artifact |
| OEM gesture handling (edge back) | glides from edge keys cancelled | confirm with real touches; document if OEM-only |
| Multilingual data sparsity | poor non-English accuracy | prioritize by market |
