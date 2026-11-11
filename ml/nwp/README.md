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

## Glide + LM fusion (`fuse_shipping.py`)

Re-measured 2026-11-11 with the shipping artifacts: full-split CTC (`run_1m`), h256 LSTM (`lstm_v20k`), the
60,564-word dictionary with raw counts, prior 6.87, endpoint weight 20, LM floor 10.
Score = `ln P_ctc + 6.87 * ln(1+count)/ln(1+max) - 20 * endpoint + λ * ln P_lm(word | context)`.

| Context | n | λ = 0 top-1 | λ = 0.4 top-1 | Δ | McNemar p |
|---|---|---|---|---|---|
| FUTO dev | 3,000 | 90.93 | **91.40** | +0.47 | 0.029 |
| FUTO test | 3,000 | 89.00 | **89.87** | +0.87 | 0.0009 |
| OASST chat | 3,000 | 94.07 | **95.33** | +1.26 | 1e-8 |

λ = 0.4 is still the FUTO peak (dev at 0.6 / 0.8: 91.27 / 90.97; test: 89.60 / 89.43). Chat prefers
λ = 0.6–0.8 (95.60 / 96.57), so the single shipped λ trades some chat gain for FUTO safety.

FUTO rows use their own sentence as the context. Chat pairs OASST prompts with a real FUTO dev swipe of the
next word: the acoustic score is real, the context is chat-style, so this measures fusion ranking rather than
end-to-end chat accuracy. Report: `ml/results/fuse_shipping.json`.

The previous run (`fuse_eval.py`, 400k checkpoint, h512 LSTM, old 128–255 dictionary, K = 16) gave +0.7 pt on
FUTO with no endpoint term; λ = 0.4 carried over unchanged.

## More conversational data (2026-11-11, not adopted)

Two permissively licensed corpora were added: OASST2 prompts (Apache-2.0; the tree bucket used by the OASST1
test split is dropped) and every user turn of UltraChat 200k (MIT). Prepared with `prepare.py --oasst-extra
--ultrachat`; the source-balanced variant is built by `mix_chat.py`.

| Training data | web_test top-1/3/5 | chat_test top-1/3/5 |
|---|---|---|
| Shipped h256: 0.38M OASST tokens, 15 % chat | 16.23 / 26.73 / 32.06 | **21.43 / 34.41 / 41.07** |
| + UltraChat/OASST2, 47.6M chat tokens, 15 % chat | 16.55 / 27.40 / 32.61 | 19.27 / 31.22 / 37.35 |
| UltraChat capped at 5M, 25 % chat | **16.57 / 27.12 / 32.48** | 20.34 / 33.31 / 39.83 |

UltraChat is instruction-style and dominates any mixture it joins; the OASST test set is style-matched to the
old 100 %-OASST sampling. Web accuracy gains ~0.3 pt, but the only chat benchmark regresses, so the shipped
model stays. The next conversational-data attempt needs a real-user corpus (WildChat is ODC-BY) or a broader
chat benchmark before a model swap can be justified.
