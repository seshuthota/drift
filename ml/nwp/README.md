# Next-word prediction (NWP) — from-scratch prototype

**Date:** 2026-11-08. Code here, data/checkpoints in `ml/work/nwp/` (gitignored).

## Data

| Source | License | Use |
|---|---|---|
| `HuggingFaceFW/fineweb` `sample/10BT/000_00000.parquet` @ `9bb295dd` | ODC-By | web text: 300M train tokens, 2.3M val, 2.4M test (document split) |
| `OpenAssistant/oasst1` `2023-04-12_oasst_ready.messages` @ `fdf72ae0` | Apache-2.0 | English user prompts as "chat": 378k train / 45k test tokens (split by conversation tree) |

`prepare.py`: lowercase, `’`→`'`, vocabulary = the app dictionary (`ime/dict/data.json`, 49,981 words incl.
contractions) + `<unk>`, `<s>` (sentence/line boundary), `,` (context only). Ids are ordered by training
frequency. `<unk>` rate ≈ 4.8 % (web), 3.5 % (chat). Top-20k words cover 97.3 % of in-vocab tokens.

## Metric

Top-k next-word accuracy at 20,000 random held-out positions whose target is a dictionary word (`<unk>`,
`<s>`, `,` are never predicted). Same positions (seed 0) for every model. Context = all previous tokens
(n-gram: last 2; LSTM: last 32 in PyTorch eval, last 16 in the ONNX check).

## Results

| Model | Phone size | Web top-1 / top-3 / top-5 | Chat top-1 / top-3 / top-5 |
|---|---|---|---|
| KN trigram, min counts 3/2 | 47.9 MB | 16.9 / 28.1 / 33.6 | 19.3 / 31.9 / 37.6 |
| KN trigram, 8/4 | 17.6 MB | 16.6 / 27.4 / 32.9 | 19.2 / 31.6 / 37.1 |
| KN trigram, 20/8 | 8.2 MB | 16.1 / 26.7 / 32.1 | 19.0 / 31.2 / 36.6 |
| KN trigram, 50/20 | 2.9 MB | 15.6 / 25.7 / 31.0 | 18.3 / 30.3 / 35.2 |
| LSTM h256, 150M tokens (`lstm_v20k`) | 3.08 MB int8 | 16.2 / 26.7 / 32.1 | **21.4 / 34.4 / 41.1** |
| LSTM h512, 300M tokens (`lstm_v20k_h512`) | 4.04 MB int8 | **17.8 / 29.0 / 34.9** | 21.0 / 34.1 / 40.5 |

LSTM: 20k-word tied embedding (128-d), 1-layer LSTM (256 or 512), projection to 128, ~3.0M / ~4.0M params;
AdamW 3e-3, cosine, batch 256 × 64 tokens, 15 % of rows from the chat corpus, bf16 autocast on an RTX 5060.
Training time 9 / 21 min.

ONNX (`export_onnx.py`, single shared embedding table, dynamic int8 on MatMul/Gather/LSTM): int8 matches fp32
within noise (5k positions, 16-token context). Laptop CPU, 1 thread, T=16: h256 0.20 ms, h512 0.31 ms.

## Observations

- The LSTM beats the trigram on chat at a third of the size; the trigram needs ~48 MB to approach the
  LSTM on web text.
- h512 + 2× data gains +2.3 pt web top-3 but nothing on chat. The chat corpus is tiny: at 15 % of rows the
  h512 run sees it ~120 times, so it is overfit; data and capacity are also confounded between the two runs.
- More conversational text is the main lever for chat quality; on-device personal learning is the other.

## Reproduce

```bash
cd ml/work/nwp
python ../../nwp/prepare.py --fineweb raw/sample/10BT/000_00000.parquet \
  --oasst raw/2023-04-12_oasst_ready.messages.jsonl.gz --out .
python ../../nwp/ngram.py build --data . --out kn3_m20_8.npz --tokens 200000000 --chat-weight 20 --min3 20 --min2 8
python ../../nwp/ngram.py eval --model kn3_m20_8.npz --data .
python ../../nwp/lstm.py train --data . --out lstm_v20k_h512 --vocab 20000 --hidden 512 --tokens 300000000
python ../../nwp/lstm.py eval --ckpt lstm_v20k_h512/best.pt --data .
cd ../../nwp && python export_onnx.py --ckpt ../work/nwp/lstm_v20k_h512/best.pt --data ../work/nwp \
  --out ../work/nwp/nwp_lstm_h512   # needs protobuf: run without -I in the llm env
```

## Glide + LM fusion (`fuse_eval.py`)

FUTO swipes with their sentence context (`word_idx`): 3,000 head-of-dev rows for tuning, 3,000 reservoir-sampled
test rows (seed 42). Score = `ln P_ctc + K * freq/255 + λ * ln P_lm(word | previous words)`, out-of-vocabulary
LM words get `-floor`.

| Setting | dev top-1 / top-3 | test top-1 / top-3 |
|---|---|---|
| λ = 0 (K = 16, frequency only) | 90.00 / 94.40 | 89.17 / 93.40 |
| **λ = 0.4, floor 10, K = 16** (shipped) | 90.73 / 94.63 | **89.87 / 93.57** |

λ ≥ 0.7 hurts; jointly re-tuning K moves dev by ≤ 0.2 pt (noise). The gain is small on FUTO's Wikipedia
sentences; homophone/function-word confusions are more frequent in chat, where the effect should be larger
(unmeasured).
