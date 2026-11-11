"""Stream the MIT FUTO swipe dataset, featurize and cache to npz.

Usage:
    python data.py --out work --train-rows 120000 --dev-rows 8000 --test-rows 4000

Row schema (per JSON line): word, data=[{t,x,y}...], canvas_width/height.
x/y are already normalized to the keyboard canvas in [0,1].
"""

import argparse
import hashlib
import json
import re
import sys
import time
import urllib.request

import numpy as np

ALPHA = "abcdefghijklmnopqrstuvwxyz"
BLANK = len(ALPHA)
T_POINTS = 64
T_FRAMES = 32  # after stride-2 stem
MAX_TARGET = T_FRAMES

BASE = "https://huggingface.co/datasets/futo-org/swipe.futo.org/resolve/main"
URLS = {
    "train": f"{BASE}/train.jsonl",
    "dev": f"{BASE}/dev.jsonl",
    "test": f"{BASE}/test.jsonl",
}

WORD_RE = re.compile(r"^[a-z]{2,26}$")


def resample(points, n=T_POINTS):
    pts = np.asarray(points, dtype=np.float32)
    if pts.ndim != 2 or pts.shape[0] < 2:
        return None
    d = np.sqrt(((pts[1:] - pts[:-1]) ** 2).sum(1))
    keep = np.concatenate([[True], d > 1e-6])
    pts = pts[keep]
    if pts.shape[0] < 2:
        return None
    seg = np.sqrt(((pts[1:] - pts[:-1]) ** 2).sum(1))
    cum = np.concatenate([[0.0], np.cumsum(seg)])
    if cum[-1] <= 1e-6:
        return None
    s = np.linspace(0.0, cum[-1], n, dtype=np.float32)
    x = np.interp(s, cum, pts[:, 0]).astype(np.float32)
    y = np.interp(s, cum, pts[:, 1]).astype(np.float32)
    return np.stack([x, y])


def valid_word(word):
    w = word.lower()
    if not WORD_RE.fullmatch(w):
        return None
    repeats = sum(1 for i in range(1, len(w)) if w[i] == w[i - 1])
    if len(w) + repeats > MAX_TARGET:
        return None
    return w


def row_feature(obj):
    w = valid_word(obj["word"])
    if w is None:
        return None
    pts = [(p["x"], p["y"]) for p in obj["data"]]
    feat = resample(pts)
    if feat is None:
        return None
    return w, feat


def stream(url, want, seen, stats, tag, log_every=20000, max_lines=None):
    """Collect up to `want` accepted rows, skipping hashes already in `seen`."""
    feats, words = [], []
    t0 = time.time()
    n = 0
    https = urllib.request.urlopen(url, timeout=120)
    for line in https:
        n += 1
        if max_lines and n > max_lines:
            break
        try:
            obj = json.loads(line)
        except json.JSONDecodeError:
            stats["bad_json"] += 1
            continue
        got = row_feature(obj)
        if got is None:
            stats["filtered"] += 1
            continue
        w, feat = got
        h = hashlib.blake2b(
            np.round(feat, 4).tobytes() + w.encode(), digest_size=8
        ).hexdigest()
        if h in seen:
            stats["dedup"] += 1
            continue
        seen.add(h)
        feats.append(feat.astype(np.float16))
        words.append(w)
        stats["kept"] += 1
        if len(feats) >= want:
            break
        if n % log_every == 0:
            el = time.time() - t0
            print(
                f"[{tag}] lines={n} kept={len(feats)} "
                f"{n / max(el, 1e-9):.0f} lines/s",
                flush=True,
            )
    https.close()
    print(
        f"[{tag}] done lines={n} kept={len(feats)} in {time.time() - t0:.1f}s",
        flush=True,
    )
    if not feats:
        return np.zeros((0, 2, T_POINTS), np.float16), np.array([], dtype=object)
    return np.stack(feats), np.array(words, dtype=object)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="work")
    ap.add_argument("--train-rows", type=int, default=120_000)
    ap.add_argument("--dev-rows", type=int, default=8_000)
    ap.add_argument("--test-rows", type=int, default=4_000)
    ap.add_argument("--train-max-lines", type=int, default=None)
    args = ap.parse_args()

    seen = set()
    stats = {"kept": 0, "filtered": 0, "dedup": 0, "bad_json": 0}
    for split, want, max_lines in (
        ("train", args.train_rows, args.train_max_lines),
        ("dev", args.dev_rows, None),
        ("test", args.test_rows, None),
    ):
        if want <= 0:
            continue
        feats, words = stream(URLS[split], want, seen, stats, split, max_lines=max_lines)
        path = f"{args.out}/{split}.npz"
        np.savez(path, feats=feats, words=words)
        print(f"saved {path}: feats={feats.shape} words={len(words)}", flush=True)
    print(json.dumps(stats), flush=True)


if __name__ == "__main__":
    main()
