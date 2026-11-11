"""Re-measure glide + next-word fusion with the artifacts the app ships.

`fuse_eval.py` produced the original λ = 0.4 choice with the 400k checkpoint, the h512 LSTM and the
previous dictionary, and without the endpoint term. This script uses the shipping setup:

  * CTC `ml/work/run_1m/best.pt` (the 1.08 MB ONNX is this checkpoint),
  * LSTM `ml/work/nwp/lstm_v20k/best.pt` (h256, the shipped int8 model),
  * `app/src/main/assets/ime/dict/data.json` (60,564 words, raw counts),
  * prior 6.87, endpoint weight 20, LM floor 10.

Two measurements:

  futo  — real FUTO dev/test swipes with their own sentence as the LM context.
  chat  — OASST prompts as the context, paired with a real FUTO swipe of the next word. The acoustic
          score is real but the context comes from another corpus, so this measures the fusion gain
          on chat-style text, not an end-to-end accuracy.

    python fuse_shipping.py --rows 3000 --chat-rows 3000
"""

import argparse
import json
import math
import os
import random
import re
import sys

import numpy as np
import torch

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "ctc"))
sys.path.insert(0, HERE)
from beam import ctc_beam  # noqa: E402
from fuse_eval import K_FREQ, ROOT, collect, lm_context_ids  # noqa: E402
from lstm import TinyLstm  # noqa: E402
from model import SwipeCtc  # noqa: E402
import prepare  # noqa: E402

ENDPOINT_WEIGHT = 20.0
LM_FLOOR = 10.0
LAMBDAS = (0.0, 0.2, 0.4, 0.6, 0.8)
AZ = re.compile(r"[a-z]{2,26}")
WORD = re.compile(r"[a-z']+")
BUCKETS = ("len=2", "len=3", "len>=4")


def bucket_of(word):
    return "len=2" if len(word) == 2 else "len=3" if len(word) == 3 else "len>=4"


def mcnemar(baseline, treatment):
    """Exact two-sided McNemar on paired hit lists (b: only treatment right, c: only baseline right)."""
    only_treatment = sum(1 for a, b in zip(baseline, treatment) if not a and b)
    only_baseline = sum(1 for a, b in zip(baseline, treatment) if a and not b)
    n = only_treatment + only_baseline
    if n == 0:
        return {"b": 0, "c": 0, "p": 1.0}
    p = 2 * sum(math.comb(n, k) for k in range(min(only_treatment, only_baseline) + 1)) / 2 ** n
    return {"b": only_treatment, "c": only_baseline, "p": min(1.0, p)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rows", type=int, default=3000)
    ap.add_argument("--chat-rows", type=int, default=3000)
    ap.add_argument("--work", default=os.path.join(ROOT, "ml/work"))
    ap.add_argument("--out", default=os.path.join(ROOT, "ml/results/fuse_shipping.json"))
    args = ap.parse_args()
    device = "cuda" if torch.cuda.is_available() else "cpu"

    raw = json.load(open(os.path.join(ROOT, "app/src/main/assets/ime/dict/data.json"), encoding="utf-8"))
    usable = {w: int(c) for w, c in raw.items() if AZ.fullmatch(w)}
    log_max = math.log1p(max(usable.values()))
    lexicon = {}
    for w in usable:
        for i in range(1, len(w)):
            lexicon.setdefault(w[:i], (False, 0.0))
    for w in usable:
        lexicon[w] = (True, 0.0)

    ck = torch.load(os.path.join(args.work, "run_1m/best.pt"), map_location=device, weights_only=False)
    ctc = SwipeCtc(ch=ck["args"]["ch"]).to(device)
    ctc.load_state_dict(ck["model"])
    ctc.eval()
    lk = torch.load(os.path.join(args.work, "nwp/lstm_v20k/best.pt"), map_location=device, weights_only=False)
    a = lk["args"]
    lm = TinyLstm(a["vocab"], a["emb"], a["hidden"], a["layers"], 0.0).to(device)
    lm.load_state_dict(lk["model"])
    lm.eval()
    vocab = open(os.path.join(args.work, "nwp/vocab.txt"), encoding="utf-8").read().split("\n")[: a["vocab"]]
    lm_id = {w: i for i, w in enumerate(vocab) if i >= 3}
    dict_words = prepare.load_dict_words()
    prepare._init(dict_words)

    def infer(feats):
        with torch.no_grad():
            lps = ctc(torch.from_numpy(np.asarray(feats, dtype=np.float32)).to(device)).cpu().numpy()
        return [ctc_beam(lp, lexicon, beam=120, alpha=0.0) for lp in lps]

    def lm_scores(contexts):
        ids = np.full((len(contexts), 32), prepare.BOS, dtype=np.int64)
        for r, ctx in enumerate(contexts):
            got = lm_context_ids(ctx, dict_words, lm_id)
            ids[r, 32 - len(got):] = got
        with torch.no_grad():
            logits = lm(torch.from_numpy(ids).to(device), last_only=True).float()
            return torch.log_softmax(logits, -1).cpu().numpy()

    def letter_centers(feats, words):
        buckets = {c: [] for c in "abcdefghijklmnopqrstuvwxyz"}
        for feat, word in zip(feats, words):
            if word.isalpha() and word:
                buckets[word[0]].append((float(feat[0, 0]), float(feat[1, 0])))
                buckets[word[-1]].append((float(feat[0, -1]), float(feat[1, -1])))
        return {
            c: (float(np.median([p[0] for p in pts])), float(np.median([p[1] for p in pts])))
            for c, pts in buckets.items() if len(pts) >= 30
        }

    def endpoint_cost(word, feat, centers):
        if word[0] not in centers or word[-1] not in centers:
            return 0.0
        sx, sy = float(feat[0, 0]), float(feat[1, 0])
        ex, ey = float(feat[0, -1]), float(feat[1, -1])
        fx, fy = centers[word[0]]
        lx, ly = centers[word[-1]]
        return (sx - fx) ** 2 + (sy - fy) ** 2 + (ex - lx) ** 2 + (ey - ly) ** 2

    def evaluate(beams, lmlp, words, feats, centers):
        rows = []
        for r, (ranked, feat) in enumerate(zip(beams, feats)):
            candidates = []
            for word, acoustic in ranked:
                if word not in usable:
                    continue
                base = acoustic + K_FREQ * math.log1p(usable[word]) / log_max
                candidates.append((word, base - ENDPOINT_WEIGHT * endpoint_cost(word, feat, centers), r))
            rows.append((words[r], candidates))
        denominators = {name: 0 for name in BUCKETS}
        for target, _ in rows:
            denominators[bucket_of(target)] += 1

        result = {}
        hit1_by_lambda = {}
        for lam in LAMBDAS:
            hits = {name: [0, 0] for name in BUCKETS}
            total1 = total3 = 0
            hit1_rows = []
            for target, candidates in rows:
                def fused(item):
                    word, base, r = item
                    i = lm_id.get(word)
                    lmv = lmlp[r, i] if i is not None else -LM_FLOOR
                    return base + lam * lmv

                top = [w for w, _, _ in sorted(candidates, key=fused, reverse=True)[:3]]
                hit1 = bool(top) and top[0] == target
                hit3 = target in top
                total1 += hit1
                total3 += hit3
                hit1_rows.append(hit1)
                hits[bucket_of(target)][0] += hit1
                hits[bucket_of(target)][1] += hit3
            hit1_by_lambda[lam] = hit1_rows
            n = len(rows)
            result[f"{lam:.1f}"] = {
                "top1": total1 / n,
                "top3": total3 / n,
                "buckets": {
                    name: {
                        "n": denominators[name],
                        "top1": hits[name][0] / denominators[name] if denominators[name] else 0.0,
                        "top3": hits[name][1] / denominators[name] if denominators[name] else 0.0,
                    }
                    for name in BUCKETS
                },
            }
        result["mcnemar_0.4_vs_0.0"] = mcnemar(hit1_by_lambda[0.0], hit1_by_lambda[0.4])
        return result

    print("futo: collecting rows ...", flush=True)
    futo = {}
    for name, items in (("dev", collect("dev", args.rows)), ("test", collect("test", args.rows, seed=42))):
        feats = np.stack([f for f, _, _ in items]).astype(np.float32)
        words = [w for _, w, _ in items]
        contexts = [c for _, _, c in items]
        futo[name] = evaluate(infer(feats), lm_scores(contexts), words, feats, letter_centers(feats, words))
        print(f"futo {name}: {len(words)} rows", flush=True)

    print("chat: pairing OASST contexts with FUTO swipes ...", flush=True)
    oasst = os.path.join(args.work, "nwp/raw/2023-04-12_oasst_ready.messages.jsonl.gz")
    _, chat_test = prepare.oasst_texts(oasst)
    dev = np.load(os.path.join(args.work, "dev.npz"), allow_pickle=True)
    dev_words = [str(w) for w in dev["words"]]
    dev_feats = dev["feats"].astype(np.float32)
    trace = {}
    for feat, word in zip(dev_feats, dev_words):
        trace.setdefault(word, feat)

    pairs = []
    for text in chat_test:
        lower = text.lower().replace("’", "'")
        for match in WORD.finditer(lower):
            target = match.group()
            if target in usable and target in trace:
                pairs.append((lower[: match.start()], target))
    rng = random.Random(7)
    rng.shuffle(pairs)
    pairs = pairs[: args.chat_rows]
    targets = sorted({target for _, target in pairs})
    by_word = dict(zip(targets, infer(np.stack([trace[w] for w in targets]))))
    chat_beams = [by_word[target] for _, target in pairs]
    chat_words = [target for _, target in pairs]
    chat_feats = np.stack([trace[w] for w in chat_words])
    centers = letter_centers(dev_feats, dev_words)
    chat = evaluate(chat_beams, lm_scores([ctx for ctx, _ in pairs]), chat_words, chat_feats, centers)
    print(f"chat: {len(pairs)} pairs, {len(targets)} distinct targets", flush=True)

    payload = {"rows": args.rows, "chat_rows": len(pairs), "futo": futo, "chat": chat}
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    json.dump(payload, open(args.out, "w", encoding="utf-8"), indent=2)
    for name in ("dev", "test"):
        for lam in ("0.0", "0.4"):
            print(f"futo {name} lam={lam} top1={futo[name][lam]['top1']:.4f} top3={futo[name][lam]['top3']:.4f}")
        print(f"futo {name} mcnemar {futo[name]['mcnemar_0.4_vs_0.0']}")
    for lam in ("0.0", "0.4"):
        print(f"chat lam={lam} top1={chat[lam]['top1']:.4f} top3={chat[lam]['top3']:.4f}")
    print(f"chat mcnemar {chat['mcnemar_0.4_vs_0.0']}")
    print(f"wrote {args.out}")


if __name__ == "__main__":
    main()
