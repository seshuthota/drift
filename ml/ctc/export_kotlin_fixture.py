"""Export a fixed sample of real FUTO swipe traces for the JVM glide regression test.

The Kotlin test (`NeuralGlideAccuracyTest`) replays these traces through the shipped ONNX model,
the lexicon beam, the frequency prior and the endpoint term. This script also stores Python's
top-3 for each row, computed with the same layout geometry, so the test can assert parity.

The layout below is duplicated in `NeuralGlideAccuracyTest.qwertyLayout()`; keep them in sync.

    python export_kotlin_fixture.py --rows 200 --seed 20261111
"""

import argparse
import hashlib
import json
import math
import os

import numpy as np
import torch

from beam import ctc_beam
from model import SwipeCtc

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
DEFAULT_NPZ = os.path.join(ROOT, "ml/work/dev.npz")
DEFAULT_CKPT = os.path.join(ROOT, "ml/work/run_1m/best.pt")
DEFAULT_DICT = os.path.join(ROOT, "app/src/main/assets/ime/dict/data.json")
DEFAULT_MODEL = os.path.join(ROOT, "app/src/main/assets/swipe/ctc_swipe.onnx")
OUT = os.path.join(ROOT, "app/src/test/resources/glide/neural_dev200.json")

FREQUENCY_WEIGHT = 6.87
ENDPOINT_WEIGHT = 20.0


def layout():
    """Normalized QWERTY letter box: x and y bounds are exactly [0, 1]."""
    keys = {}
    rows = (
        ("qwertyuiop", 0.5, 1.0 / 6.0),
        ("asdfghjkl", 1.0, 0.5),
        ("zxcvbnm", 2.0, 5.0 / 6.0),
    )
    for letters, offset, y in rows:
        for i, ch in enumerate(letters):
            keys[ch] = ((i + offset) / 10.0, y, 0.1, 1.0 / 3.0)
    return keys


def featurize(feat, points=64):
    """Mirror of the Kotlin featurize(): clamp to the unit letter box, drop repeats, resample."""
    xs = np.clip(feat[0].astype(np.float32), 0.0, 1.0)
    ys = np.clip(feat[1].astype(np.float32), 0.0, 1.0)
    keep = np.ones(len(xs), dtype=bool)
    keep[1:] = (xs[1:] != xs[:-1]) | (ys[1:] != ys[:-1])
    xs, ys = xs[keep], ys[keep]
    cumulative = np.concatenate([[0.0], np.cumsum(np.sqrt(np.diff(xs) ** 2 + np.diff(ys) ** 2))])
    total = float(cumulative[-1])
    targets = total * np.arange(points) / (points - 1)
    return np.stack([
        np.interp(targets, cumulative, xs),
        np.interp(targets, cumulative, ys),
    ]).astype(np.float32)


def usable_dictionary(path):
    raw = json.load(open(path, encoding="utf-8"))
    usable = {w: int(c) for w, c in raw.items() if len(w) >= 2 and w.isalpha() and w.islower()}
    return usable


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--npz", default=DEFAULT_NPZ)
    ap.add_argument("--ckpt", default=DEFAULT_CKPT)
    ap.add_argument("--dict", default=DEFAULT_DICT)
    ap.add_argument("--model", default=DEFAULT_MODEL)
    ap.add_argument("--out", default=OUT)
    ap.add_argument("--rows", type=int, default=200)
    ap.add_argument("--seed", type=int, default=20261111)
    args = ap.parse_args()

    usable = usable_dictionary(args.dict)
    lexicon = {}
    for w in usable:
        for i in range(1, len(w)):
            lexicon.setdefault(w[:i], (False, 0.0))
    for w in usable:
        lexicon[w] = (True, 0.0)
    denominator = math.log1p(max(usable.values()))

    data = np.load(args.npz, allow_pickle=True)
    words = [str(w) for w in data["words"]]
    feats = data["feats"].astype(np.float32)
    eligible = [i for i, w in enumerate(words) if w in usable]
    rng = np.random.default_rng(args.seed)
    picked = sorted(int(i) for i in rng.choice(eligible, size=args.rows, replace=False))
    # Mirror NeuralGlideTypingClassifier.featurize(): clamp to the letter box (the layout below makes
    # the box the unit square) and resample to 64 arc-length points. Real traces cross the box edge,
    # so this step changes the decoder input and must happen before the expected scores are computed.
    featurized = np.stack([featurize(feats[i]) for i in picked])

    ck = torch.load(args.ckpt, map_location="cpu", weights_only=False)
    model = SwipeCtc(ch=ck["args"]["ch"])
    model.load_state_dict(ck["model"])
    model.eval()
    with torch.no_grad():
        log_probs = model(torch.from_numpy(featurized)).numpy()

    keys = layout()
    rows = []
    for row, feat, lp in zip(picked, featurized, log_probs):
        word = words[row]
        ranked = ctc_beam(lp, lexicon, beam=120, top_chars=12, alpha=0.0)
        scored = []
        start_x, start_y = float(feat[0, 0]), float(feat[1, 0])
        end_x, end_y = float(feat[0, -1]), float(feat[1, -1])
        for candidate, acoustic in ranked:
            if candidate not in usable:
                continue
            count = usable[candidate]
            prior = FREQUENCY_WEIGHT * math.log1p(count) / denominator
            first, last = keys[candidate[0]], keys[candidate[-1]]
            endpoints = (start_x - first[0]) ** 2 + (start_y - first[1]) ** 2
            endpoints += (end_x - last[0]) ** 2 + (end_y - last[1]) ** 2
            scored.append((candidate, acoustic + prior - ENDPOINT_WEIGHT * endpoints))
        scored.sort(key=lambda item: -item[1])
        top3 = [candidate for candidate, _ in scored[:3]]
        rows.append({
            "word": word,
            "features": [[round(float(v), 6) for v in feat[0]], [round(float(v), 6) for v in feat[1]]],
            "expected": top3,
        })

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    payload = {
        "source": os.path.basename(args.npz),
        "seed": args.seed,
        "checkpoint": os.path.basename(os.path.dirname(args.ckpt)) + "/" + os.path.basename(args.ckpt),
        "model_sha256": hashlib.sha256(open(args.model, "rb").read()).hexdigest(),
        "frequency_weight": FREQUENCY_WEIGHT,
        "endpoint_weight": ENDPOINT_WEIGHT,
        "layout": {ch: [round(x, 4), round(y, 4), w, h] for ch, (x, y, w, h) in keys.items()},
        "rows": rows,
    }
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, separators=(",", ":"))
    top1 = sum(1 for r in rows if r["expected"] and r["expected"][0] == r["word"]) / len(rows)
    print(f"wrote {len(rows)} rows to {args.out} ({os.path.getsize(args.out) / 1024:.0f} KiB), "
          f"python top-1 {top1:.3f}")


if __name__ == "__main__":
    main()
