# From-scratch swipe CTC prototype

Trains a small CTC encoder on the MIT-licensed FUTO swipe dataset and decodes with a
lexicon-constrained prefix beam search. Purpose: establish whether Drift can own its
swipe model on permissively licensed data instead of shipping FUTO's weights.

> Progress report: [`../REPORT.md`](../REPORT.md) · Future plan: [`../PLAN.md`](../PLAN.md)

## Licensing

| Part | License |
|---|---|
| Training data (`futo-org/swipe.futo.org`) | MIT |
| Lexicon (`hermitdave/FrequencyWords`, en_50k) | MIT |
| Code in this directory | Apache-2.0 (project license) |

No FUTO weights, no FUTO model outputs, no GPL `swipe-library` or CleverKeys code.

## Pipeline

```bash
# 1. stream + featurize (resample to 64 points, x/y already in [0,1])
python3 data.py --out work --train-rows 400000 --dev-rows 8000 --test-rows 4000

# 2. train
/home/curious/miniconda3/envs/llm/bin/python train.py \
    --train work/train.npz --dev work/dev.npz --out work/run_400k --epochs 40 --ch 128

# 3. evaluate greedy vs lexicon beam
/home/curious/miniconda3/envs/llm/bin/python eval_beam.py \
    --ckpt work/run_400k/best.pt --npz work/test_rand.npz --beam 120 --alpha 0.4

# unbiased test sample (reservoir over the official test split)
python3 sample_test.py --out work/test_rand.npz --rows 4000

# 4. export + parity check
/home/curious/miniconda3/envs/llm/bin/python export_onnx.py \
    --ckpt work/run_400k/best.pt --out work/ctc_swipe_ch128.onnx
```

`data.py` filters targets to `[a-z]{2,26}` and enforces CTC feasibility
(`len + adjacent repeats <= 32`); ~17% of raw rows are dropped. Trajectories are
already normalized to the keyboard canvas (verified: raw x/y in [0,1]).

## Model

- 1-D residual CNN: stride-2 stem (64 -> 32 frames), 4 blocks with dilations
  (1,2,4,8), 27-class output (a-z + blank).
- ch=96: 152,859 params; ch=128: 269,339 params.
- CTC loss, AdamW, cosine schedule, batch 256.

## Results

Beam: width 120, alpha 0.4 (tuned on dev), 50k-word lexicon. Test rows are a
reservoir sample of the official test split (49,970 rows), untouched during training
and tuning.

| Train rows | Model | Split | Greedy top-1 | Beam top-1 | Beam top-3 | Beam top-5 |
|---|---|---|---|---|---|---|
| 100k | ch96 | dev 8k (head) | 65.7% | 86.4% | 92.2% | 93.3% |
| 100k | ch96 | test 4k (random) | 66.0% | 87.0% | 92.7% | 93.7% |
| 400k | ch128 | dev 8k (head) | 72.9% | 88.5% | 93.0% | 93.8% |
| 400k | ch128 | test 4k (random) | 74.1% | **88.7%** | 93.6% | 94.5% |
| 400k | ch128 | test 4k (random), 147k lexicon | 74.1% | **89.4%** | 95.0% | 95.9% |

Beam width 200 changes nothing (88.7% top-1), so width 120 is not the bottleneck.
ONNX export of the ch128 model is 1.08 MB with argmax parity 100%
(max log-prob diff 9.5e-05).

### Published reference points

| Engine | Test top-1 |
|---|---|
| FUTO encoder only (floor, CleverKeys test-2400) | 79.25% |
| FUTO encoder + refine (ceiling) | 84.83% |
| CleverKeys from-scratch ch192 CTC (their test-2400) | 89.31% |
| CleverKeys geometric SHARK2 | 67.50% |

Different test subsets and lexicon sizes, so treat as scale references, not a
head-to-head benchmark.

## Caveats

- 400k of ~1M train rows; full-split training is the next accuracy lever.
- English QWERTY only; no layout-agnostic slot permutation yet.
- Lexicon size matters: 50k entries leaves 3.8% of test words out of vocabulary; a
  147k trie (top entries of the MIT `en_full` list, alpha=0.8) lifts test top-1 from
  88.7% to 89.4%. The AOSP baseline uses ~147k.
- Beam is prototype Python (~13 ms/row). Kotlin trie beam needed for shipping.
- Greedy is not a shipping decoder; it is included to bound the emissions.
