# Drift typing intelligence — status and plan

**Last updated:** 2026-11-11 (first version 2026-11-08)
**Scope:** glide decoder, next-word prediction, typo correction/autocorrect, and the input pipeline around them
**Companion documents:** `ml/REPORT.md` (swipe CTC prototype), `ml/nwp/README.md` (next-word model)

---

## 0. Where we are

### Shipped in the app (branch `drift/glide-and-next-word`)

| Area | What is in the app | Measured |
|---|---|---|
| Glide decoder | 269k-param CTC encoder (`assets/swipe/ctc_swipe.onnx`, 1.08 MB) + Kotlin lexicon beam over the app dictionary, frequency prior k = 16, statistical decoder as fallback | 89.4 % top-1 / 95.0 % top-3 (Python, 4k FUTO test rows); 89.87 % top-1 with LM fusion (3k test rows) |
| Glide + context | Next-word LM scores glide candidates, λ = 0.4, OOV floor 10 | +0.7 pt top-1 on FUTO sentences |
| Next-word prediction | LSTM h256, 20k vocab, int8 (`assets/nwp/nwp_lstm.int8.onnx`, 3.08 MB), completions while typing | web 16.2 / 26.7 % top-1/top-3, chat 21.4 / 34.4 % |
| Typo correction | `TypoCorrector`: keyboard-aware weighted Damerau-Levenshtein over a trie of the app dictionary, ranked with frequency + next-word LM | 89.1 % top-1 / 96.9 % top-3 on synthetic single typos, ~0.25 ms/word (laptop JVM) |
| Suggestion bar | Corrections and completions on one score scale; best one leads (`thw` → the, `hel` → help, `dont` → don't, `recieve` → receive) | verified on device |
| Spell checker | `spell()` flags unknown words and likely dictionary misspellings, with suggestions | unit-tested only |
| Autocorrect | On space / punctuation, only when confident: non-words with margin ≥ 2.5 over the runner-up, dictionary words only for apostrophes. Backspace right after undoes it and the word is not corrected again. Bold candidate = will autocorrect. Pref `correction__auto_correct` (default on), quick action toggles it | 98.1 % precision / 71.5 % recall on synthetic typos; verified on device |
| Browser omnibox | Inline autocompletion is no longer treated as a user selection, so suggestions keep working in address bars | unit-tested; device run did not trigger an inline completion |
| Short glides | Two-letter words on neighbouring keys (`we`, `as`, `re`) are recognized on release; vertical moves stay taps | verified on device |

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

1. **The app dictionary is noisy.** `ime/dict/data.json` (49,981 words) contains misspellings and junk (`teh`,
   `recieve`, `definately`, `untill`, `thier`, `cna`, `igf`, `thr`, `yoy`) and apostrophe-less forms (`dont`, `im`).
   Its frequencies are all 128–255, so the prior is weak. Effects:
   - glide can output those misspellings;
   - autocorrect cannot fix them, because the rule that spots them (rare, not in the LM vocabulary, beaten by a
     common neighbour by > 2.5) also catches ~600 rare real words (`pout` → put), so they only lead the bar;
   - `spell()` uses that same rule and will underline some rare real words.
   This is the single biggest quality lever left and ties into decision 3 below.
2. **Short glides favour frequent words.** `op` and `ok` both decoded as `of` on device. Very short paths carry
   little shape information and the frequency prior (k = 16) dominates. The intended word is in the bar.
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
   - "Double-space period" pref is shown in settings but not implemented in the K3 input pipeline.
   - The auto-space feedback link points to `github.com/seshuthota/drift/discussions/1935` — an upstream discussion
     number carried over by the rename; the page likely does not exist.
   - `:app:lintDebug` fails with 52 pre-existing errors (mostly `ImpliedQuantity` / `StringFormatInvalid` in
     translated strings); CI does not run lint.
   - The autocorrect switch has no icon, unlike its neighbours.
7. **Licensing/attribution** (deferred by choice): MIT FUTO dataset attribution and the FineWeb (ODC-By) /
   OASST (Apache-2.0) notices need to go into About before a release.

---

## 2. What to focus on next

In recommended order. Each item says what it fixes.

1. **Shipping lexicon (fixes issues 1, 2, part of 3).** Build one cleaned word list with real frequencies and use
   it for glide, corrections, completions and spell check:
   - start from FrequencyWords (MIT) or an AOSP-style list (Apache-2.0), intersect with a spell-checked source,
     drop entries that are one cheap edit from a far more frequent word unless they are attested in the LM corpus;
   - store real log-frequencies (not 128–255) and re-tune the glide prior k and corrector weights on dev;
   - then enable autocorrect for dictionary misspellings that the clean list no longer contains.
   Measurable: glide top-1 on FUTO test, synthetic typo top-1, false-flag rate of `spell()` on a word sample.
2. **Glide data scale (Phase 1 below).** Full 1M-row train split + `swipe-2/3/4`; cheap and still rising.
   Gate: ≥ +0.5 pt over 89.4 % on a 10k sealed sample.
3. **Short-word handling in glide.** Measure top-1 on 2–3-letter words separately; try a length-aware prior or a
   smaller k for short paths. Small, targeted, directly visible to users.
4. **Personal learning (opt-in, on device).** Learn words the user types repeatedly and words they reject via
   autocorrect undo; add them to the lexicon. Biggest lever for chat-style next-word quality per
   `ml/nwp/README.md`, and the safety net for names/slang (issue 3).
5. **Shape-aware reranker (Phase 2).** Targets the 60.5 % of glide errors that are shape-confusable.
6. **Quick fixes:** double-space period, the feedback link, the autocorrect icon.
7. **Later:** multilingual/layout-agnostic glide (Phase 4), licensing notices (issue 7).

---

## 3. Decisions needed

1. **Accuracy bar.** Is 89–91 % glide top-1 enough to ship dataset-only, or is ≥ 92 % required? FUTO's published
   QWERTY number is 92.9/93.5 % top-1 (their split, 3.9 MB model pair).
2. **Lexicon source.** Clean the current app dictionary, or replace it with FrequencyWords (MIT) / an AOSP-style
   list (Apache-2.0)? Now the most important open decision (issue 1).
3. **Autocorrect default.** Keep it on by default (current), or off until the lexicon is cleaned?
4. **Personal learning.** Is on-device learning of typed words acceptable, and should it respect incognito mode
   only, or also be off by default?
5. **Language/layout order** after English QWERTY.
6. **FUTO weights as fallback** if the accuracy bar is missed. Recommendation: stay dataset-only.
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
- **Frozen baselines:** glide 400k/ch128/147k = 89.38 / 95.03 / 95.90 % top-1/3/5; app-dictionary decoder with
  LM λ 0.4 = 89.87 % top-1; typo corrector 89.1 / 96.9 % top-1/3.

---

## 5. Phases (glide decoder)

### Phase 1 — data scale and lexicon · **next**

- Train on the full ~1M-row split with a `distance`-based validity filter; compare 100k / 400k / 1M.
- Add `swipe-2` (informal text, ~28k), `swipe-3` (unique words/misspellings, ~38k), `swipe-4` (QWERTY-confusable,
  ~50k); ablate each.
- Lexicon sweep on the cleaned shipping list (focus item 1).
- Gates: ≥ +0.5 pt with McNemar p < 0.05; no regression on 2–3-char words. Target 90–91 %.

### Phase 2 — shape-aware reranker · planned

- Beam top-K (8–16) → reranker over acoustic score, length, log-frequency, path-to-word shape similarity, FUTO
  `swipe-negatives` similarity (Apache-2.0), context term.
- Start linear/GBDT (easy in Kotlin); MLP only if it plateaus. Never train on FUTO model outputs.
- Gates: recover ≥ 25 % of residual errors; +0.5–1.0 pt top-1; no loss on short words.

### Phase 3 — context LM · **done (v1)**

- LSTM next-word model trained on FineWeb (ODC-By) + OASST (Apache-2.0), shipped int8, fused into glide (λ 0.4)
  and used for completions and correction ranking.
- Remaining: more conversational text, personal learning (focus item 4), measure the fusion gain on chat-style
  sentences.

### Phase 4 — layout-agnostic and multilingual · later

- Slot-permutation training with key centers from layout JSONs; `swipe-5` data (en, de, es, fr, lt, pl…; QWERTY,
  AZERTY, QWERTZ, Dvorak…), single-finger only at first; per-language alphabets and tries.
- Gates: AZERTY/QWERTZ/Dvorak within ~3 pt of QWERTY; de/es/fr ≥ 80–85 % top-1; no QWERTY regression.

### Phase 5 — Android integration · **done (v1)**

- ONNX Runtime on device, Kotlin trie + CTC prefix beam, statistical fallback, LM fusion. Training and app
  normalization match (FUTO's canvas is the letter-key box, as in `NeuralGlideTypingClassifier.featurize`).
- Remaining: on-device latency numbers on a mid-range phone; a Kotlin-path accuracy test (issue 5).

### Phase 6 — hardening and product · in progress

- Done: typo correction, autocorrect with undo, short-glide recognition, omnibox fix.
- Remaining: real-device evaluation, regression suite of golden traces, licensing notices.

---

## 6. Risk register

| Risk | Impact | Mitigation |
|---|---|---|
| Noisy app dictionary | misspellings in glide/suggestions, weak autocorrect | cleaned shipping lexicon (focus 1) |
| Autocorrect mangles names/slang | user frustration | conservative margins, undo + memory, personal learning |
| Synthetic-only typo evaluation | numbers do not match real typing | real typo set; opt-in metrics |
| Web-volunteer vs Drift-user domain shift (glide) | unknown accuracy drop | opt-in device traces |
| Test subset bias (4k) | false progress | 10k sample, 3 seeds, McNemar |
| License contamination (FUTO weights/outputs) | legal | data-only rule; review every artifact |
| OEM gesture handling (edge back) | glides from edge keys cancelled | confirm with real touches; document if OEM-only |
| Multilingual data sparsity | poor non-English accuracy | prioritize by market |
