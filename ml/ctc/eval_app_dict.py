"""Evaluate a checkpoint exactly as the Android decoder scores it: the app dictionary
(`app/src/main/assets/ime/dict/data.json`, 0-255 frequencies) and a linear prior
`ln P(word) + k * freq/255`. Sweeps k so it can be tuned on dev and confirmed on test.

    python eval_app_dict.py --ckpt ../work/run_400k/best.pt --npz ../work/dev.npz --rows 4000
    python eval_app_dict.py --ckpt ../work/run_400k/best.pt --npz ../work/test_rand.npz --k 16

Words containing apostrophes are glided as their letters on device (`dont` -> `don't`); the
CTC test targets are a-z only, so this script evaluates the a-z key forms.
"""

import argparse
import json
import os
import re

import numpy as np
import torch

from beam import ctc_beam
from model import SwipeCtc

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DEFAULT_DICT = os.path.join(ROOT, "app/src/main/assets/ime/dict/data.json")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ckpt", required=True)
    ap.add_argument("--npz", required=True)
    ap.add_argument("--dict", default=DEFAULT_DICT)
    ap.add_argument("--rows", type=int, default=0)
    ap.add_argument("--beam", type=int, default=120)
    ap.add_argument("--k", type=float, nargs="*", default=[0, 4, 8, 12, 16, 20, 24, 32])
    args = ap.parse_args()

    raw = json.load(open(args.dict, encoding="utf-8"))
    usable = {w: v for w, v in raw.items() if re.fullmatch(r"[a-z]{2,26}", w)}
    lexicon = {}
    for w in usable:
        for i in range(1, len(w)):
            lexicon.setdefault(w[:i], (False, 0.0))
    for w in usable:
        lexicon[w] = (True, 0.0)

    ck = torch.load(args.ckpt, map_location="cpu")
    model = SwipeCtc(ch=ck["args"]["ch"])
    model.load_state_dict(ck["model"])
    model.eval()
    data = np.load(args.npz, allow_pickle=True)
    feats = data["feats"].astype(np.float32)
    words = [str(w) for w in data["words"]]
    if args.rows:
        feats, words = feats[: args.rows], words[: args.rows]
    with torch.no_grad():
        log_probs = model(torch.from_numpy(feats)).numpy()

    # alpha=0: the beam returns raw ln P(word); the frequency prior is applied afterwards,
    # which is what the Kotlin decoder does.
    beams = [ctc_beam(lp, lexicon, beam=args.beam, alpha=0.0) for lp in log_probs]
    n = len(words)
    oov = sum(w not in usable for w in words) / n
    print(f"{args.npz} rows={n} dict_words={len(usable)} oov={oov:.2%}")
    for k in args.k:
        t1 = t3 = 0
        for ranked, w in zip(beams, words):
            rescored = sorted(ranked, key=lambda ws: -(ws[1] + k * usable[ws[0]] / 255.0))
            top = [x for x, _ in rescored[:3]]
            t1 += bool(top) and top[0] == w
            t3 += w in top
        print(f"k={k:5.1f} top1={t1 / n:.2%} top3={t3 / n:.2%}", flush=True)


if __name__ == "__main__":
    main()
