"""Evaluate a checkpoint exactly as the Android decoder scores it.

The shipping dictionary stores raw counts. ``--prior log`` (automatic when the max count
is above 255) scores ``k * log1p(count)``, which is what
``FREQUENCY_WEIGHT * ln(1+count)/ln(1+maxCount)`` is for one fixed weight. ``--prior flat``
scores the old ``k * count/255``.

``--short-k`` lowers that weight for candidates of length ``<= --short-max``. ``--within``
shrinks gaps inside that length while the most common word of the length keeps ``--k``.
Dev on 2026-11-11 kept a uniform k of 0.4; both variants lowered length-2 and overall top-1,
so the shipping decoder does not use them.

    python eval_app_dict.py --ckpt ../work/run_400k/best.pt --npz ../work/dev.npz \
        --prior log --k 0.4 --short-max 2 3 --short-k 0 0.1 0.2 0.4

Words containing apostrophes are glided as their letters on device (`dont` -> `don't`); the
CTC test targets are a-z only, so this script evaluates the a-z key forms. A ``.txt`` lexicon
is ``word count`` (FrequencyWords / ``ml/lexicon.py``).
"""

import argparse
import json
import math
import os
import pickle
import re

import numpy as np
import torch

from beam import ctc_beam
from model import SwipeCtc

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DEFAULT_DICT = os.path.join(ROOT, "app/src/main/assets/ime/dict/data.json")
AZ = re.compile(r"[a-z]{2,26}")


def load_usable(path):
    if path.endswith(".json"):
        raw = json.load(open(path, encoding="utf-8"))
        return {w: int(v) for w, v in raw.items() if AZ.fullmatch(w)}
    usable = {}
    for line in open(path, encoding="utf-8"):
        parts = line.split()
        if len(parts) != 2 or AZ.fullmatch(parts[0]) is None:
            continue
        usable[parts[0]] = int(parts[1])
    return usable


def log_freq(count, kind):
    if kind == "flat":
        return count / 255.0
    return math.log1p(count)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ckpt", required=True)
    ap.add_argument("--npz", required=True)
    ap.add_argument("--dict", default=DEFAULT_DICT)
    ap.add_argument("--prior", choices=("flat", "log"), default=None)
    ap.add_argument("--rows", type=int, default=0)
    ap.add_argument("--beam", type=int, default=120)
    ap.add_argument("--k", type=float, nargs="*", default=None)
    ap.add_argument("--short-k", type=float, nargs="*", default=None,
                    help="frequency weight for words of length <= --short-max; --k is the weight for longer words")
    ap.add_argument("--short-max", type=int, nargs="*", default=None,
                    help="letter counts that take --short-k (default 2 and 3 when --short-k is set)")
    ap.add_argument("--within", type=float, nargs="*", default=None,
                    help="for words of length <= --short-max, shrink gaps toward the most common word of "
                         "that length: score uses within*log1p(count) + (k-within)*peak. The peak word keeps --k")
    ap.add_argument("--save-beams", default=None)
    ap.add_argument("--load-beams", default=None)
    ap.add_argument("--save-top1", default=None, help="write target<TAB>prediction for the last config")
    ap.add_argument("--endpoint-weight", type=float, default=0.0,
                    help="subtract this times squared distance from stroke ends to letter centers")
    ap.add_argument("--outside", default=None, help="word-count file; count top-1 predictions missing from it")
    args = ap.parse_args()

    usable = load_usable(args.dict)
    outside = None if args.outside is None else set(load_usable(args.outside))
    kind = args.prior
    if kind is None:
        kind = "log" if usable and max(usable.values()) > 255 else "flat"
    if args.k is None:
        args.k = [0, 0.4, 0.8, 1.0, 1.2] if kind == "log" else [0, 4, 8, 12, 16, 20, 24, 32]
    lexicon = {}
    for w in usable:
        for i in range(1, len(w)):
            lexicon.setdefault(w[:i], (False, 0.0))
    for w in usable:
        lexicon[w] = (True, 0.0)

    data = np.load(args.npz, allow_pickle=True)
    words = [str(w) for w in data["words"]]
    feats = data["feats"].astype(np.float32)
    if args.rows:
        words = words[: args.rows]
        feats = feats[: args.rows]
    if args.load_beams:
        beams = pickle.load(open(args.load_beams, "rb"))
        if args.rows:
            beams = beams[: args.rows]
    else:
        ck = torch.load(args.ckpt, map_location="cpu", weights_only=False)
        model = SwipeCtc(ch=ck["args"]["ch"])
        model.load_state_dict(ck["model"])
        model.eval()
        with torch.no_grad():
            log_probs = model(torch.from_numpy(feats)).numpy()
        # alpha=0: the beam returns raw ln P(word); the frequency prior is applied afterwards,
        # which is what the Kotlin decoder does.
        beams = [ctc_beam(lp, lexicon, beam=args.beam, alpha=0.0) for lp in log_probs]
        if args.save_beams:
            pickle.dump(beams, open(args.save_beams, "wb"))
    n = len(words)
    oov = sum(w not in usable for w in words) / n
    buckets = {
        "all": [i for i in range(n)],
        "len=2": [i for i, w in enumerate(words) if len(w) == 2],
        "len=3": [i for i, w in enumerate(words) if len(w) == 3],
        "len>=4": [i for i, w in enumerate(words) if len(w) >= 4],
    }
    counts = " ".join(f"{name}={len(idxs)}" for name, idxs in buckets.items())
    print(f"{args.npz} rows={n} dict_words={len(usable)} prior={kind} oov={oov:.2%} {counts}")

    peak = {}
    for word, count in usable.items():
        length = len(word)
        value = log_freq(count, kind)
        if value > peak.get(length, 0.0):
            peak[length] = value
    long_k_default = args.k[0] if args.k else (0.4 if kind == "log" else 16.0)
    if args.within:
        short_maxes = args.short_max or [2, 3]
        # (long_k, within, short_max). within == long_k is today's uniform prior.
        configs = [(long_k_default, w, m) for m in short_maxes for w in args.within]
    elif args.short_k:
        short_maxes = args.short_max or [2, 3]
        configs = [(long_k_default, sk, m) for m in short_maxes for sk in args.short_k]
    else:
        configs = [(k, k, 0) for k in args.k]

    centers = {}
    if args.endpoint_weight:
        buckets_xy = {c: [] for c in "abcdefghijklmnopqrstuvwxyz"}
        for feat, word in zip(feats, words):
            if word.isalpha() and word:
                buckets_xy[word[0]].append((float(feat[0, 0]), float(feat[1, 0])))
                buckets_xy[word[-1]].append((float(feat[0, -1]), float(feat[1, -1])))
        for char, pts in buckets_xy.items():
            if len(pts) >= 30:
                xs, ys = zip(*pts)
                centers[char] = (float(np.median(xs)), float(np.median(ys)))

    def endpoint_cost(word, feat):
        if not args.endpoint_weight or not word or word[0] not in centers or word[-1] not in centers:
            return 0.0
        sx, sy = float(feat[0, 0]), float(feat[1, 0])
        ex, ey = float(feat[0, -1]), float(feat[1, -1])
        fx, fy = centers[word[0]]
        lx, ly = centers[word[-1]]
        return (sx - fx) ** 2 + (sy - fy) ** 2 + (ex - lx) ** 2 + (ey - ly) ** 2

    def added_prior(word, count, long_k, within, short_max):
        base = log_freq(count, kind)
        if args.within and short_max and len(word) <= short_max:
            # Most common word of this length keeps the full prior. Rarer words of the same
            # length move toward it, so shape can choose `ok` over `of` without giving the
            # short stroke away to a longer word.
            return within * base + (long_k - within) * peak[len(word)]
        if short_max and len(word) <= short_max:
            return within * base
        return long_k * base

    top1_rows = []
    for long_k, within, short_max in configs:
        totals = {name: [0, 0] for name in buckets}
        missing = 0
        top1_rows = []
        for ranked, w, feat in zip(beams, words, feats):
            rescored = sorted(
                (
                    (
                        word,
                        score + added_prior(word, usable[word], long_k, within, short_max)
                        - args.endpoint_weight * endpoint_cost(word, feat),
                    )
                    for word, score in ranked
                    if word in usable
                ),
                key=lambda ws: -ws[1],
            )
            top = [x for x, _ in rescored[:3]]
            top1_rows.append((w, top[0] if top else ""))
            hit1 = bool(top) and top[0] == w
            hit3 = w in top
            if hit1:
                totals["all"][0] += 1
            if hit3:
                totals["all"][1] += 1
            length_name = "len=2" if len(w) == 2 else "len=3" if len(w) == 3 else "len>=4" if len(w) >= 4 else None
            if length_name is not None:
                totals[length_name][0] += hit1
                totals[length_name][1] += hit3
            if outside is not None and top and top[0] not in outside:
                missing += 1
        parts = []
        for name, idxs in buckets.items():
            t1, t3 = totals[name]
            denom = len(idxs)
            if denom:
                parts.append(f"{name} top1={t1 / denom:.2%} top3={t3 / denom:.2%}")
        if outside is not None:
            parts.append(f"top1-not-in-cleaned={missing / n:.2%}")
        label = f"k={long_k:5.2f}"
        if short_max and args.within:
            label += f" within={within:5.2f} short_max={short_max}"
        elif short_max:
            label += f" short_k={within:5.2f} short_max={short_max}"
        print(label + "  " + "  ".join(parts), flush=True)
    if args.save_top1 and top1_rows:
        with open(args.save_top1, "w", encoding="utf-8") as handle:
            for target, pred in top1_rows:
                handle.write(f"{target}\t{pred}\n")


if __name__ == "__main__":
    main()
