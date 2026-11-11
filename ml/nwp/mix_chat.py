"""Build a source-balanced chat token array for the NWP model.

`prepare.py` concatenates every chat source, so a large instruction-style corpus (UltraChat, 41M
tokens) drowns OASST (0.9M). This script tokenizes OASST first, then adds UltraChat only up to a
token cap, in the vocabulary order of an existing prepared directory.

    python mix_chat.py --prepared ../work/nwp_chat --oasst ../work/nwp/raw/2023-04-12_oasst_ready.messages.jsonl.gz \
        --oasst-extra ../work/nwp/raw2/2023-11-05_oasst2_ready.messages.jsonl.gz \
        --ultrachat ../work/nwp/raw2/train_sft-00000-of-00003-a3ecf92756993583.parquet \
        --ultrachat ../work/nwp/raw2/train_sft-00001-of-00003-0a1804bcb6ae68c6.parquet \
        --ultrachat ../work/nwp/raw2/train_sft-00002-of-00003-ee46ed25cfae92c6.parquet \
        --ultra-tokens 5000000 --out ../work/nwp_chat/chat_train_mix
"""

import argparse
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import prepare  # noqa: E402


def tokenize(text, word_to_id):
    text = text.lower().replace("’", "'").replace("‘", "'")
    out = [prepare.BOS]
    for token in prepare.TOKEN_RE.findall(text):
        c = token[0]
        if c == "\n" or c in ".!?":
            if out[-1] != prepare.BOS:
                out.append(prepare.BOS)
        elif c == ",":
            if out[-1] not in (prepare.BOS, prepare.COMMA):
                out.append(prepare.COMMA)
        else:
            out.append(word_to_id.get(token, prepare.UNK))
    if out[-1] != prepare.BOS:
        out.append(prepare.BOS)
    return out


def encode(texts, word_to_id, budget=None):
    out = []
    for text in texts:
        out.extend(tokenize(text, word_to_id))
        if budget is not None and len(out) >= budget:
            return out[:budget]
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--prepared", required=True, help="directory with vocab.txt in frequency order")
    ap.add_argument("--oasst", required=True)
    ap.add_argument("--oasst-extra", nargs="*", default=[])
    ap.add_argument("--ultrachat", nargs="*", default=[])
    ap.add_argument("--ultra-tokens", type=int, default=5_000_000)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    vocab = open(os.path.join(args.prepared, "vocab.txt"), encoding="utf-8").read().split("\n")
    word_to_id = {w: i for i, w in enumerate(vocab) if i >= len(prepare.SPECIALS)}
    oasst_train, _ = prepare.oasst_texts(args.oasst)
    for path in args.oasst_extra:
        oasst_train.extend(prepare.oasst_extra_texts(path))
    oasst = encode(oasst_train, word_to_id)
    ultra = encode(prepare.ultrachat_texts(args.ultrachat), word_to_id, budget=args.ultra_tokens)
    chat = np.asarray(oasst + ultra, dtype=np.uint16)
    np.save(args.out + ".npy", chat)
    print(f"oasst {len(oasst):,} tokens, ultrachat {len(ultra):,} tokens, total {len(chat):,}")


if __name__ == "__main__":
    main()
