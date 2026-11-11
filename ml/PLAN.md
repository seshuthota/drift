# Swipe decoder — future plan for review

**Date:** 2026-11-08
**Baseline:** 89.4 % test top-1 (269k params, 1.08 MB ONNX, 400k MIT rows, 147k lexicon)
**Companion document:** `ml/REPORT.md` (what was built and measured)

---

## 0. Status and framing

The dataset-only route is viable: a 400k-row, 269k-parameter CTC model is at parity
with the best public from-scratch model (89.31 %), which used 5.6× the parameters and
~3× the data. Remaining gains are engineering and data, not a licensing dead end.

Two independent levers are already identified by measurement:

1. **Lexicon coverage** — 50k → 147k words gave +0.65 pt; 3.8 % of test words were OOV at 50k.
2. **Shape-confusable errors** — 60.5 % of current beam errors have the predicted
   word in FUTO's shape hard-negative list for the target word.

What is *not* yet known: how far full-split data goes, how much a reranker recovers,
how much a context LM recovers, and what multilingual/layout-agnostic training costs
in accuracy.

---

## 1. Decisions needed from reviewer

1. **Accuracy bar.** Is 89–91 % test top-1 enough to ship dataset-only, or is ≥92 %
   required? FUTO's published QWERTY number is 92.9/93.5 % top-1 (their split, their
   3.9 MB model pair); reaching it is plausible but unproven for us.
2. **Language/layout order.** English QWERTY only first, then which: German, French,
   Spanish, Italian? (Drift is currently English.)
3. **Lexicon strategy.** Ship our app dictionary, bundle a 147k AOSP-style trie
   (Apache-2.0), or a FrequencyWords-derived list (MIT)? This changes both accuracy and
   decode cost.
4. **FUTO weights as fallback.** Accept the attribution notice only if the accuracy bar
   is missed? Recommendation: stay dataset-only and revisit only if we fail the bar.
5. **Context-LM scope.** Build our own (ODC-By/CC BY-SA corpora) or reuse FUTO's
   English context LM (notice + English-only)?
6. **Device data collection.** Is opt-in capture of swipe traces (no text logging) allowed?
   This is the only way to measure and close domain shift.
7. **Priority.** Data scale, reranker, or multilingual first? Recommendation: data
   scale, then reranker, then context LM, then multilingual.

---

## 2. Evaluation protocol (do before any new training)

- **Sealed test:** keep `test_rand.npz` (seed 42) untouched. Before the next model
  family, expand to a **10,000-row random sample** (95 % CI ≈ ±0.6 pt instead of ±1.1).
- **Seeds:** 3 seeds for every comparative claim; report mean ± sd.
- **Paired tests:** McNemar on identical rows for any claimed +accuracy.
- **Report always:** top-1/3/5, per-length, OOV rate, per-lexicon accuracy, encoder
  latency (ORT CPU), model size.
- **Freeze the baseline:** 400k/ch128/147k/α0.8 = 89.38 % top-1, 95.03 % top-3,
  95.90 % top-5 on `test_rand.npz`.

---

## 3. Phase 1 — data scale and lexicon (highest expected value, ~1–2 days)

**Goal:** find the data ceiling before adding complexity.

- Train on the full ~1 M-row train split (5.16 GB JSONL) with a `distance`-based
  validity filter; compare 100k / 400k / 1M at fixed architecture and lexicon.
- Add the collection runs with filtering:
  - `swipe-2`: informal text (Amazon reviews, TV dialogue) — ~28k swipes.
  - `swipe-3`: unique words, Urban Dictionary/OpenWebText, misspellings — ~38k.
  - `swipe-4`: deliberately QWERTY-confusable words — ~50k.
  - Ablate each corpus's contribution; prior evidence says mix dominates size.
- Lexicon sweep: 50k / 147k / 200k; then the actual app dictionary.
- **Gates:** ≥ +0.5 pt over 89.4 % with McNemar p < 0.05; no regression on 2–3-char
  words. Target 90–91 %.
- **Effort:** prep ~1 h, 15–25 min per training run, 1–2 days total.

---

## 4. Phase 2 — shape-aware reranker (~3–5 days)

**Goal:** attack the measured 60.5 % of errors that are shape-confusable words.

- Candidate generation: beam top-K (K = 8–16) instead of top-1.
- Reranker features (start simple, then learned):
  - acoustic log-prob / beam score,
  - word length and unigram log-frequency,
  - shape similarity between the swipe path and candidate word geometry,
  - FUTO `swipe-negatives` hard-negative similarity (`neg_sims`) — Apache-2.0 data,
  - optionally a context term.
- Two implementation options:
  - **(a) Linear / GBDT reranker** over the features above — fast, interpretable,
    easy to ship in Kotlin.
  - **(b) Small MLP/transformer** over candidate embeddings — higher ceiling, more work.
- Training signal: real swipes as positives; negatives from the FUTO hard-negative
  table plus beam confusions. **Do not use FUTO model outputs** (license contamination).
- **Gates:** recover ≥ 25 % of residual beam errors; +0.5–1.0 pt test top-1; no loss on
  short words. Evaluate on the sealed sample.
- **Risks:** vocabulary skew (Wikipedia), QWERTY-only negatives, reranker overfitting
  to the 4k test — mitigate with seeds and dev-only tuning.

---

## 5. Phase 3 — context / next-word LM (~1–2 weeks)

**Goal:** fix homophones and short function words (`four/for`, `its/is`, `three/the`).

- Baseline: n-gram (KenLM or a backoff model) trained on permissive corpora;
  extend to a small transformer (4–8 M params, quantized) if the n-gram plateaus.
- Data (license-verified):
  - `HuggingFaceFW/fineweb`, `fineweb-2` (multilingual) — ODC-By,
  - `allenai/dolma`, `HuggingFaceTB/smollm-corpus` — ODC-By,
  - `roneneldan/TinyStories` — CDLA-Sharing-1.0,
  - `wikimedia/wikipedia` — CC BY-SA 3.0 + GFDL.
- Fusion in the beam: `score = acoustic + α·logfreq + λ·log P_lm(word | history)`.
  Tune α/λ on dev only.
- **Gates:** +1–2 pt top-1; added decode latency < 5 ms; LM ≤ 30 MB quantized.
- Note: FUTO's context LM is English-only and adds the weights-license notice;
  training our own keeps the dataset-only position and supports other languages.

---

## 6. Phase 4 — layout-agnostic and multilingual (~1–2 weeks)

**Goal:** one model family for Drift's layouts and languages.

- Implement **slot permutation** training: randomly permute key slots per sample and
  feed key centers from layout JSONs, so the encoder learns layout geometry rather
  than a fixed alphabet order.
- Data: `swipe-5` (59,247 swipes) with `language`, `layout`, `dual_finger` columns:
  - layouts: `qwerty`, `azerty`, `qwertz`, `german`, `spanish`, `dvorak`,
    `lithuanian_qwerty`, `shavian`, `toki_pona`, `clearflow`, `kasroz`;
  - languages: en, de, es, fr, lt, pl, shavian, toki pona;
  - exclude `dual_finger != 0` initially; revisit two-finger swipes later.
- Per-language alphabets and diacritics (de: ä ö ü ß; fr: é è ê à ç; es: ñ; pl/lt
  diacritics); per-layout lexicon tries.
- **Gates:** AZERTY/QWERTZ/Dvorak within ~3 pt of QWERTY; de/es/fr initial ≥ 80–85 %
  top-1; no QWERTY regression.
- **Risks:** sparse per-language data, larger class sets, layout metadata only in
  `swipe-5` (the official splits are English QWERTY).

---

## 7. Phase 5 — Android integration (~1–2 weeks)

- **Runtime:** ONNX Runtime Mobile + XNNPACK (MIT/Apache-2.0); the model already
  exports with dynamic batch and 100 % argmax parity. ExecuTorch remains an option if
  we later want FUTO `.pte` compatibility.
- **Decoder:** Kotlin trie + CTC prefix beam, clean-room from the validated algorithm
  (not the GPL `swipe-library`); golden tests against Python emission/beam outputs.
- **Budgets:** encoder ≤ 5 ms on mid-range devices, decode ≤ 10 ms, model ≤ 2 MB,
  memory ≤ 30 MB.
- **Fallback:** current template/geometric decoder for unsupported layouts/languages;
  feature flag for rollout.
- **Instrumentation:** opt-in latency and error proxies; no keystroke/text logging.

---

## 8. Phase 6 — hardening and product (~1 week + ongoing)

- Real-device evaluation on opt-in traces; domain adaptation if the gap is real.
- Autocorrect integration (optional): `simonko912/autocorrect-16M` is Apache-2.0 but
  synthetic; validate quality before trusting it.
- Regression suite: golden traces, per-length and per-layout accuracy, latency.
- Licensing/notices: MIT dataset attribution in About; Apache-2.0 notice for the
  hard-negative table if shipped.

---

## 9. Risk register

| Risk | Impact | Mitigation |
|---|---|---|
| Web-volunteer vs Drift-user domain shift | unknown accuracy drop | opt-in device traces, evaluate early |
| Test subset bias (4k) | false progress | 10k sample, 3 seeds, McNemar |
| Lexicon mismatch with app dictionary | accuracy/UX | sweep app dictionary before shipping |
| Multilingual data sparsity | poor non-English accuracy | prioritize by market, targeted collection |
| License contamination (FUTO weights/outputs) | legal | enforce data-only rule; review every artifact |
| Prototype beam ≠ device latency | bad UX | Kotlin port + device benchmarks early |
| ONNX op coverage on phones | deployment failure | validate ORT/XNNPACK on target devices |
| Overfitting reranker/LM to dev | inflated numbers | sealed test, dev-only tuning |

---

## 10. Rough milestones

| Milestone | Contents | Effort (estimate) |
|---|---|---|
| M1 | 1 M-row model, lexicon sweep, corpus mix | 1–2 days |
| M2 | shape reranker | 3–5 days |
| M3 | context LM | 1–2 weeks |
| M4 | layout-agnostic + multilingual | 1–2 weeks |
| M5 | Android runtime + Kotlin decoder + benchmarks | 1–2 weeks |

Effort estimates exclude review/QA iterations and are measured in working days, not
calendar weeks.

---

## 11. Immediate next actions (if approved)

1. Expand the sealed test sample to 10k random rows; freeze the 89.4 % baseline.
2. Pull the full 1 M train split + `swipe-2/3/4` with distance filtering; run the
   learning-curve ablation.
3. Lexicon sweep (50k / 147k / 200k); decide the shipping lexicon.
4. Build the reranker dataset from beam top-K + FUTO hard negatives; prototype (a).
5. Decide the accuracy bar and the FUTO-fallback question in §1.

---

## 12. Recommendation

Proceed with **Phase 1 then Phase 2** before the context LM: data scale is cheap and
still rising, and the reranker targets a measured 60 % of remaining errors with
already-licensed data. The LM is the largest single accuracy lever but also the most
expensive to build and ship; it should follow once the swipe emissions and reranker
are stable.
