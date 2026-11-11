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


def content_hash(feat, word):
    rounded = np.round(np.asarray(feat, dtype=np.float32), 4)
    return hashlib.blake2b(rounded.tobytes() + str(word).encode(), digest_size=8).hexdigest()


def exclude_npz(path, seen):
    data = np.load(path, allow_pickle=True)
    words = [str(w) for w in data["words"]]
    for feat, word in zip(data["feats"], words):
        seen.add(content_hash(feat, word))
    print(f"excluded {len(words)} hashes from {path} (seen={len(seen)})", flush=True)


def stream(url, want, seen, stats, tag, log_every=20000, max_lines=None, max_distance=None):
    """Collect up to `want` accepted rows, skipping hashes already in `seen`.

    `want` of None reads the split until EOF.
    """
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
        if max_distance is not None:
            dist = obj.get("distance")
            if dist is None or float(dist) > max_distance:
                stats["distance_dropped"] += 1
                continue
        got = row_feature(obj)
        if got is None:
            stats["filtered"] += 1
            continue
        w, feat = got
        h = content_hash(feat, w)
        if h in seen:
            stats["dedup"] += 1
            continue
        seen.add(h)
        feats.append(feat.astype(np.float16))
        words.append(w)
        if "distance" in obj:
            stats["distance_n"] += 1
            stats["distance_sum"] += float(obj["distance"])
            dist = float(obj["distance"])
            if dist > stats["distance_max"]:
                stats["distance_max"] = dist
        stats["kept"] += 1
        if want is not None and len(feats) >= want:
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
    ap.add_argument("--splits", default="train,dev,test",
                    help="comma-separated subset of train,dev,test")
    ap.add_argument("--all-train", action="store_true",
                    help="read the train split until EOF instead of --train-rows")
    ap.add_argument("--exclude", action="append", default=[],
                    help="npz whose trajectories are skipped (hold out dev and test)")
    ap.add_argument("--out-file", default=None,
                    help="exact output path when building a single split")
    ap.add_argument("--url", default=None, help="stream this jsonl instead of a named split")
    ap.add_argument("--max-distance", type=float, default=None,
                    help="drop rows whose distance is missing or above this")
    args = ap.parse_args()

    seen = set()
    for path in args.exclude:
        exclude_npz(path, seen)
    stats = {
        "kept": 0, "filtered": 0, "dedup": 0, "bad_json": 0,
        "distance_n": 0, "distance_sum": 0.0, "distance_max": 0.0,
        "distance_dropped": 0,
    }
    wanted = [s.strip() for s in args.splits.split(",") if s.strip()]
    plan = []
    if "train" in wanted:
        plan.append(("train", None if args.all_train else args.train_rows, args.train_max_lines))
    if "dev" in wanted:
        plan.append(("dev", args.dev_rows, None))
    if "test" in wanted:
        plan.append(("test", args.test_rows, None))
    if args.url:
        if not args.out_file:
            raise SystemExit("--url requires --out-file")
        plan = [("url", None if args.all_train else args.train_rows, args.train_max_lines)]
    if args.out_file and len(plan) != 1:
        raise SystemExit("--out-file requires exactly one split")
    for split, want, max_lines in plan:
        if want is not None and want <= 0:
            continue
        url = args.url or URLS[split]
        feats, words = stream(
            url, want, seen, stats, split, max_lines=max_lines, max_distance=args.max_distance,
        )
        path = args.out_file or f"{args.out}/{split}.npz"
        np.savez(path, feats=feats, words=words)
        print(f"saved {path}: feats={feats.shape} words={len(words)}", flush=True)
    printable = dict(stats)
    if stats["distance_n"]:
        printable["distance_mean"] = stats["distance_sum"] / stats["distance_n"]
    print(json.dumps(printable), flush=True)


if __name__ == "__main__":
    main()
