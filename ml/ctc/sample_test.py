"""Reservoir-sample random rows from the official test split for unbiased eval."""

import argparse
import hashlib
import json
import random
import urllib.request

import numpy as np

from data import URLS, row_feature

ALPHA = "abcdefghijklmnopqrstuvwxyz"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="work/test_rand.npz")
    ap.add_argument("--rows", type=int, default=4000)
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    rng = random.Random(args.seed)
    seen = set()
    kept = []
    n = 0
    for line in urllib.request.urlopen(URLS["test"], timeout=180):
        n += 1
        try:
            obj = json.loads(line)
        except json.JSONDecodeError:
            continue
        got = row_feature(obj)
        if got is None:
            continue
        w, feat = got
        h = hashlib.blake2b(
            np.round(feat, 4).tobytes() + w.encode(), digest_size=8
        ).hexdigest()
        if h in seen:
            continue
        seen.add(h)
        if len(kept) < args.rows:
            kept.append((feat.astype(np.float16), w))
        else:
            j = rng.randrange(n)
            if j < args.rows:
                kept[j] = (feat.astype(np.float16), w)

    feats = np.stack([f for f, _ in kept])
    words = np.array([w for _, w in kept], dtype=object)
    np.savez(args.out, feats=feats, words=words)
    print(f"scanned={n} kept={len(kept)} -> {args.out}", flush=True)


if __name__ == "__main__":
    main()
