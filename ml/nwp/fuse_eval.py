"""Tune glide + next-word LM fusion on FUTO swipes with their sentence context.

score(word) = ln P_ctc(word) + K * ln(1+count) / ln(1+maxCount) + lam * ln P_lm(word | words before it)

Words outside the LM's 20k vocabulary get ln P_lm = --floor. lam/floor are tuned on dev rows, then
reported on a random test sample.

    python fuse_eval.py --rows 3000
"""

import argparse
import json
import math
import os
import random
import re
import sys
import urllib.request

import numpy as np
import torch

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "ctc"))
sys.path.insert(0, HERE)
from beam import ctc_beam  # noqa: E402
from data import URLS, row_feature  # noqa: E402
from lstm import TinyLstm  # noqa: E402
from model import SwipeCtc  # noqa: E402
import prepare  # noqa: E402

ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
# 0.4 * ln(1 + max count) on the shipping list. The prior is K * ln(1+count) / ln(1+max).
K_FREQ = 6.87


def collect(split, rows, seed=None):
    """Head rows (dev) or a reservoir sample (test) with context = sentence words before word_idx."""
    kept, n = [], 0
    rng = random.Random(seed)
    for line in urllib.request.urlopen(URLS[split], timeout=180):
        obj = json.loads(line)
        got = row_feature(obj)
        if got is None or obj.get("potentially_invalid_sentence"):
            continue
        n += 1
        words = obj["sentence"].split()
        ctx = " ".join(words[: obj["word_idx"]])
        item = (got[1], got[0], ctx)
        if len(kept) < rows:
            kept.append(item)
            if seed is None and len(kept) == rows:
                break
        else:
            j = rng.randrange(n)
            if j < rows:
                kept[j] = item
    return kept


def lm_context_ids(ctx, dict_words, freq_id):
    ids = prepare.tokenize(ctx)
    if len(ids) > 1 and ids[-1] == prepare.BOS and not ctx.rstrip().endswith((".", "!", "?")):
        ids = ids[:-1]
    out = []
    for x in ids:
        out.append(x if x < 3 else freq_id.get(dict_words[x - 3], 0))
    return out[-32:]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rows", type=int, default=3000)
    ap.add_argument("--work", default=os.path.join(ROOT, "ml/work"))
    args = ap.parse_args()
    dev_ = "cuda" if torch.cuda.is_available() else "cpu"

    raw = json.load(open(os.path.join(ROOT, "app/src/main/assets/ime/dict/data.json")))
    usable = {w: v for w, v in raw.items() if re.fullmatch(r"[a-z]{2,26}", w)}
    log_max = math.log1p(max(usable.values()))
    lex = {}
    for w in usable:
        for i in range(1, len(w)):
            lex.setdefault(w[:i], (False, 0.0))
    for w in usable:
        lex[w] = (True, 0.0)

    ck = torch.load(os.path.join(args.work, "run_400k/best.pt"), map_location=dev_)
    ctc = SwipeCtc(ch=ck["args"]["ch"]).to(dev_)
    ctc.load_state_dict(ck["model"])
    ctc.eval()
    lk = torch.load(os.path.join(args.work, "nwp/lstm_v20k_h512/best.pt"), map_location=dev_)
    a = lk["args"]
    lm = TinyLstm(a["vocab"], a["emb"], a["hidden"], a["layers"], 0.0).to(dev_)
    lm.load_state_dict(lk["model"])
    lm.eval()
    vocab = open(os.path.join(args.work, "nwp/vocab.txt")).read().split("\n")[: a["vocab"]]
    freq_id = {w: i for i, w in enumerate(vocab) if i >= 3}
    dict_words = prepare.load_dict_words()
    prepare._init(dict_words)

    def prepare_split(items):
        feats = torch.from_numpy(np.stack([f for f, _, _ in items]).astype(np.float32)).to(dev_)
        with torch.no_grad():
            lps = ctc(feats).cpu().numpy()
        beams = [ctc_beam(lp, lex, beam=120, alpha=0.0) for lp in lps]
        ctx = np.full((len(items), 32), prepare.BOS, dtype=np.int64)
        for r, (_, _, c) in enumerate(items):
            ids = lm_context_ids(c, dict_words, freq_id)
            ctx[r, 32 - len(ids):] = ids
        with torch.no_grad():
            lmlp = torch.log_softmax(lm(torch.from_numpy(ctx).to(dev_), last_only=True).float(), -1).cpu().numpy()
        return beams, lmlp, [w for _, w, _ in items]

    def score(beams, lmlp, words, lam, floor, kf=K_FREQ):
        t1 = t3 = 0
        for r, (ranked, w) in enumerate(zip(beams, words)):
            def s(item):
                word, ac = item
                i = freq_id.get(word)
                lmv = lmlp[r, i] if i is not None else -floor
                return ac + kf * math.log1p(usable[word]) / log_max + lam * lmv
            top = [x for x, _ in sorted(ranked, key=s, reverse=True)[:3]]
            t1 += bool(top) and top[0] == w
            t3 += w in top
        return t1 / len(words), t3 / len(words)

    print("collecting dev/test rows ...", flush=True)
    dev = prepare_split(collect("dev", args.rows))
    test = prepare_split(collect("test", args.rows, seed=42))
    grid = [(lam, fl, kf) for lam in (0.0, 0.2, 0.4, 0.6, 0.8, 1.0) for fl in (10.0, 14.0)
            for kf in (0.0, 4.0, 8.0, 12.0, 16.0)]
    res = {g: score(*dev, *g) for g in grid}
    for g, (t1, t3) in res.items():
        print(f"dev lam={g[0]:.1f} floor={g[1]:4.1f} K={g[2]:4.1f} top1={t1:.2%} top3={t3:.2%}", flush=True)
    best = max(res, key=lambda g: res[g][0])
    for g in [(0.0, 10.0, K_FREQ), (0.4, 10.0, K_FREQ), best]:
        t1, t3 = score(*test, *g)
        print(f"TEST lam={g[0]:.1f} floor={g[1]:4.1f} K={g[2]:4.1f} top1={t1:.2%} top3={t3:.2%}", flush=True)


if __name__ == "__main__":
    main()
