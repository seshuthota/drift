"""Replay the user's logged glides through the app's decoder and tune its rescoring weights.

Each `glide` event in the quality log holds the normalized trace and the key layout it was drawn on. This script
rebuilds the app's input features exactly (NeuralGlideTypingClassifier.featurize), runs the shipped ONNX model
(`app/src/main/assets/swipe/ctc_swipe.onnx`), and ports its prefix beam search, so the beams match the phone's. Only
the final rescoring depends on the weights tuned here:

    score = ln P_ctc + F * ln(1+count)/ln(1+max) - E * endpoint + L * max(ln P_ctx, -10)
    P_ctx = (1 - a) P_lstm + a P_personal,  a = B * n / (n + 2)   (PersonalNgram.alpha)

so the beams are decoded once and every setting is a cheap re-sort. Labels (the word the user meant) follow
`analyze.py`: kept -> the committed word, picked -> the pick, deleted -> the word entered next. The LM context is the
previous words of the same message (words less than --gap seconds apart; the log has no punctuation), and the
personal model learns each intended word after it is scored, so nothing comes from the future.

The last --holdout share of glides (by time) is held out: choose weights on the rest, report both, and compare with
the shipped weights by McNemar on the held-out part.

    ~/miniconda3/envs/llm/bin/python ml/quality/glide_replay.py ml/quality/data/diagnostics
"""

import argparse
import collections
import itertools
import json
import math
import os
import sys

import numpy as np
import onnxruntime as ort
import torch

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "ml", "nwp"))

from lstm import TinyLstm  # noqa: E402
from personal_eval import CKPT, VOCAB, PersonalNgram  # noqa: E402

MODEL = os.path.join(ROOT, "app/src/main/assets/swipe/ctc_swipe.onnx")
DICT = os.path.join(ROOT, "app/src/main/assets/ime/dict/data.json")
ALPHABET = "abcdefghijklmnopqrstuvwxyz"
POINTS, FRAMES, BLANK, CLASSES = 64, 32, 26, 27
BEAM_WIDTH, TOP_CHARS, MIN_CHAR_PROB = 120, 12, 1e-7
LM_FLOOR = 10.0
SHIPPED = {"F": 6.87, "E": 20.0, "L": 0.4, "B": 0.3}
BOS, UNK = 1, 0


def mcnemar(baseline, treatment):
    b = sum(1 for x, y in zip(baseline, treatment) if not x and y)
    c = sum(1 for x, y in zip(baseline, treatment) if x and not y)
    n = b + c
    if n == 0:
        return b, c, 1.0
    p = 2 * sum(math.comb(n, k) for k in range(min(b, c) + 1)) / 2 ** n
    return b, c, min(1.0, p)


def usable(word):
    if not 2 <= len(word) <= 26 or any(c not in ALPHABET for c in word):
        return False
    repeats = sum(1 for i in range(1, len(word)) if word[i] == word[i - 1])
    return len(word) + repeats <= 32


def build_lexicon(counts):
    """Prefix set and word set as in setWordData, plus surface forms for apostrophe words."""
    words, forms = set(), collections.defaultdict(list)
    for w in counts:
        if usable(w):
            words.add(w)
    for w in counts:
        if "'" in w:
            key = w.replace("'", "")
            if usable(key):
                if key in words and key not in forms:
                    forms[key].append(key)
                words.add(key)
                forms[key].append("I" + w[1:] if w.startswith("i'") else w)
    prefixes = {w[:i] for w in words for i in range(1, len(w) + 1)}
    return prefixes, words, forms


def letter_bounds(keys):
    letters = [k for k in keys if k[0] in ALPHABET]
    min_x = min(k[1] - k[3] / 2 for k in letters)
    max_x = max(k[1] + k[3] / 2 for k in letters)
    min_y = min(k[2] - k[4] / 2 for k in letters)
    max_y = max(k[2] + k[4] / 2 for k in letters)
    return min_x, max_x, min_y, max_y


def featurize(points, bounds):
    min_x, max_x, min_y, max_y = bounds
    w, h = max_x - min_x, max_y - min_y
    xs, ys = [], []
    for x, y in points:
        x = min(max((x - min_x) / w, 0.0), 1.0)
        y = min(max((y - min_y) / h, 0.0), 1.0)
        if xs and x == xs[-1] and y == ys[-1]:
            continue
        xs.append(x)
        ys.append(y)
    n = len(xs)
    if n < 2:
        return None
    cum = [0.0]
    for i in range(1, n):
        cum.append(cum[-1] + math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]))
    total = cum[-1]
    if total <= 1e-6:
        return None
    feats = np.zeros((2, POINTS), dtype=np.float32)
    seg = 0
    for i in range(POINTS):
        target = total * i / (POINTS - 1)
        while seg < n - 2 and cum[seg + 1] < target:
            seg += 1
        span = cum[seg + 1] - cum[seg]
        t = (target - cum[seg]) / span if span > 1e-9 else 0.0
        feats[0, i] = xs[seg] + (xs[seg + 1] - xs[seg]) * t
        feats[1, i] = ys[seg] + (ys[seg + 1] - ys[seg]) * t
    return feats


def beam_search(log_probs, prefixes, words):
    """Port of NeuralGlideTypingClassifier.beamSearch up to the final scoring: (word, ln P) pairs."""
    beams = {"": [1.0, 0.0]}
    for frame in range(FRAMES):
        probs = np.exp(log_probs[frame].astype(np.float64))
        chars = [c for c in np.argsort(-probs[:BLANK], kind="stable")[:TOP_CHARS] if probs[c] >= MIN_CHAR_PROB]
        blank = probs[BLANK]
        nxt = {}
        for prefix, (pb, pnb) in beams.items():
            total = pb + pnb
            last = prefix[-1] if prefix else None
            nxt.setdefault(prefix, [0.0, 0.0])[0] += total * blank
            for c in chars:
                ch = ALPHABET[c]
                p = probs[c]
                ext = prefix + ch
                if ch == last:
                    nxt.setdefault(prefix, [0.0, 0.0])[1] += pnb * p
                    if ext in prefixes:
                        nxt.setdefault(ext, [0.0, 0.0])[1] += pb * p
                elif ext in prefixes:
                    nxt.setdefault(ext, [0.0, 0.0])[1] += total * p
        if len(nxt) > BEAM_WIDTH:
            nxt = dict(sorted(nxt.items(), key=lambda kv: -(kv[1][0] + kv[1][1]))[:BEAM_WIDTH])
        beams = nxt
    return [(w, math.log(s[0] + s[1])) for w, s in beams.items() if w in words and s[0] + s[1] > 0]


def endpoint_penalty(word, feats, centers):
    if len(word) < 2 or word[0] not in centers or word[-1] not in centers:
        return 0.0
    fx, fy = centers[word[0]]
    lx, ly = centers[word[-1]]
    return ((feats[0, 0] - fx) ** 2 + (feats[1, 0] - fy) ** 2 +
            (feats[0, -1] - lx) ** 2 + (feats[1, -1] - ly) ** 2)


def load_events(folder):
    events = []
    for name in ("quality.1.jsonl", "quality.jsonl"):
        path = os.path.join(folder, name)
        if os.path.exists(path):
            with open(path, encoding="utf-8") as handle:
                events += [json.loads(line) for line in handle if line.strip()]
    return sorted(events, key=lambda e: e["t"])


def labels_of(events):
    glides = {e["id"]: e for e in events if e["e"] == "glide"}
    picks, deleted, retyped = {}, set(), {}
    for e in events:
        if e["e"] == "glide_pick":
            picks[e["id"]] = e["w"].lower()
        elif e["e"] == "glide_del":
            deleted.add(e["id"])
        elif e["e"] == "word" and "after_del" in e:
            retyped.setdefault(e["after_del"], e["w"].lower())
    out = {}
    for gid, g in glides.items():
        top = (g.get("words") or [""])[0].lower()
        if gid in picks:
            out[gid] = picks[gid]
        elif gid in deleted:
            if gid in retyped:
                out[gid] = retyped[gid]
        else:
            out[gid] = top
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("folder")
    ap.add_argument("--gap", type=float, default=60.0)
    ap.add_argument("--holdout", type=float, default=0.3)
    ap.add_argument("--model", default=MODEL, help="ONNX glide model to replay (default: the shipped one)")
    ap.add_argument("--cut-t", type=int, default=0,
                    help="hold out glides from this timestamp on (default: the last --holdout share)")
    ap.add_argument("--sweep", action="store_true", help="also search the rescoring weights")
    ap.add_argument("--held-only", action="store_true", help="decode only held-out glides (needs --cut-t)")
    ap.add_argument("--save-hits", help="write held-out top-1 hits (shipped weights) by glide time, to compare models")
    ap.add_argument("--compare", help="a --save-hits file from another model: McNemar against it on held-out glides")
    args = ap.parse_args()

    events = load_events(args.folder)
    labels = labels_of(events)
    counts = {w: int(c) for w, c in json.load(open(DICT, encoding="utf-8")).items() if w}
    log_max = math.log1p(max(counts.values()))
    prefixes, words, forms = build_lexicon(counts)
    session = ort.InferenceSession(args.model, providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name

    ck = torch.load(CKPT, map_location="cpu", weights_only=False)
    a = ck["args"]
    lm = TinyLstm(a["vocab"], a["emb"], a["hidden"], a["layers"], 0.0)
    lm.load_state_dict(ck["model"])
    lm.eval()
    vocab = open(VOCAB, encoding="utf-8").read().split("\n")[: a["vocab"]]
    lm_id = {w: i for i, w in enumerate(vocab) if i >= 3}

    # Walk the word stream in time order: decode each labelled glide, then learn the intended word.
    keys, rows = None, []
    personal = PersonalNgram()
    history, ids, last_t = ["<s>"], [BOS], None
    glides = {e["id"]: e for e in events if e["e"] == "glide"}
    agree = decoded = 0
    for e in events:
        if e["e"] == "layout":
            keys = e["keys"]
            continue
        if e["e"] != "word":
            continue
        if last_t is not None and e["t"] - last_t > args.gap * 1000:
            history, ids = ["<s>"], [BOS]
        last_t = e["t"]
        word = e["w"].lower()
        gid = e.get("id")
        if e["how"] == "glide" and gid in glides and keys is not None:
            target = labels.get(gid)
            g = glides[gid]
            bounds = letter_bounds(keys)
            feats = featurize(g["pts"], bounds)
            if feats is not None and target and not (args.held_only and g["t"] < args.cut_t):
                lp = session.run(None, {input_name: feats[None]})[0].reshape(FRAMES, CLASSES)
                beams = beam_search(lp, prefixes, words)
                min_x, max_x, min_y, max_y = bounds
                centers = {
                    k[0]: (min(max((k[1] - min_x) / (max_x - min_x), 0), 1), min(max((k[2] - min_y) / (max_y - min_y), 0), 1))
                    for k in keys if k[0] in ALPHABET
                }
                with torch.no_grad():
                    logits = lm(torch.tensor([ids[-16:]]))[0][-1]
                    lm_row = torch.log_softmax(logits.float(), -1).numpy()
                cands = []
                for w, acoustic in beams:
                    for form in forms.get(w, [w]):
                        key = form.lower()
                        freq = math.log1p(counts.get(key, 0)) / log_max if counts.get(key, 0) > 0 else 0.0
                        i = lm_id.get(key)
                        p_lm = math.exp(lm_row[i]) if i is not None else 0.0
                        cands.append((key, acoustic, freq, endpoint_penalty(w, feats, centers), p_lm,
                                      personal.prob(history, key)))
                n_ctx = personal.evidence(history)
                rows.append({"target": target, "cands": cands, "n_ctx": n_ctx, "total": personal.total,
                             "logged_top": (g.get("words") or [""])[0].lower(), "t": g["t"]})
                decoded += 1
            word = target or word
        personal.add(history, word)
        history.append(word)
        ids.append(lm_id.get(word, UNK))

    def rank(row, F, E, L, B):
        if row["total"] == 0:
            alpha = 0.0
        else:
            alpha = B * row["n_ctx"] / (row["n_ctx"] + 2.0) if row["n_ctx"] else B * 0.1

        def score(c):
            _, acoustic, freq, endpoint, p_lm, p_user = c
            mixed = (1 - alpha) * p_lm + alpha * p_user
            ctx = max(math.log(mixed), -LM_FLOOR) if mixed > 0 else -LM_FLOOR
            return acoustic + F * freq - E * endpoint + L * ctx

        best = {}
        for c in row["cands"]:
            s = score(c)
            if c[0] not in best or s > best[c[0]]:
                best[c[0]] = s
        return sorted(best, key=best.get, reverse=True)

    for row in rows:
        agree += rank(row, **SHIPPED)[:1] == [row["logged_top"]]
    print(f"{len(rows)} labelled glides replayed; shipped weights reproduce the phone's top word for "
          f"{100 * agree / max(len(rows), 1):.1f}%")
    cut_t = args.cut_t or rows[int(len(rows) * (1 - args.holdout))]["t"]
    tune = [r for r in rows if r["t"] < cut_t]
    held = [r for r in rows if r["t"] >= cut_t]
    print(f"model {os.path.relpath(args.model, ROOT)}; held out from t={cut_t}")

    def hits(subset, setting):
        return [rank(r, *setting)[:1] == [r["target"]] for r in subset]

    grid = [] if not args.sweep else list(itertools.product((4.0, 5.5, 6.87, 8.5, 10.0), (10.0, 20.0, 30.0), (0.2, 0.4, 0.6, 0.8, 1.0),
                                  (0.0, 0.3, 0.7)))
    shipped = (SHIPPED["F"], SHIPPED["E"], SHIPPED["L"], SHIPPED["B"])
    results = [(sum(hits(tune, shipped)) / max(len(tune), 1), shipped)] if not args.sweep else []
    for setting in grid:
        t = hits(tune, setting)
        results.append((sum(t) / max(len(t), 1), setting))
    results.sort(reverse=True)
    base_tune = sum(hits(tune, shipped)) / max(len(tune), 1)
    base_held = hits(held, shipped)
    if args.save_hits:
        json.dump({str(r["t"]): h for r, h in zip(held, base_held)}, open(args.save_hits, "w"))
    if args.compare:
        other = json.load(open(args.compare))
        pairs = [(other[str(r["t"])], h) for r, h in zip(held, base_held) if str(r["t"]) in other]
        b, c, p = mcnemar([x for x, _ in pairs], [y for _, y in pairs])
        print(f"held out vs {args.compare}: {100 * sum(x for x, _ in pairs) / len(pairs):.1f}% -> "
              f"{100 * sum(y for _, y in pairs) / len(pairs):.1f}% top-1, fixed {b}, broke {c}, McNemar p={p:.4f}")
    print(f"tune {len(tune)} / held out {len(held)} glides")
    print(f"shipped F={shipped[0]} E={shipped[1]} L={shipped[2]} B={shipped[3]}: "
          f"tune top-1 {100 * base_tune:.1f}%, held top-1 {100 * sum(base_held) / len(held):.1f}%")
    print(f"\n{'F':>5s} {'E':>5s} {'L':>4s} {'B':>4s}  {'tune':>6s}  {'held':>6s}  held vs shipped (b/c, p)")
    for score, setting in results[:10]:
        h = hits(held, setting)
        b, c, p = mcnemar(base_held, h)
        print(f"{setting[0]:5.2f} {setting[1]:5.0f} {setting[2]:4.1f} {setting[3]:4.1f}  {100 * score:5.1f}%  "
              f"{100 * sum(h) / len(h):5.1f}%  +{b}/-{c}, p={p:.3f}")
    reachable = sum(1 for r in rows if r["target"] in {c[0] for c in r["cands"]})
    print(f"\nintended word among the beam's words: {100 * reachable / len(rows):.1f}% (ceiling for rescoring)")


if __name__ == "__main__":
    main()
