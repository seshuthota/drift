"""Replay the user's own words through next-word prediction, as the keyboard would have predicted them.

The quality log's `word` events are the finished words in order, with timestamps (no punctuation). Words less than
--gap seconds apart are treated as one message. Before each word the shipping LSTM predicts from the words before it
in the same message, a personal n-gram built only from earlier words is mixed in, and then the word is learned, so
nothing is predicted from the future. The last --holdout share of words (by time) is reported separately: tune on
the rest, judge on that.

    ~/miniconda3/envs/llm/bin/python ml/quality/nwp_replay.py ml/quality/data/diagnostics

Reported per setting: top-1 / top-3 with nothing typed yet, and top-1 after the first letter (completion).
"""

import argparse
import collections
import json
import math
import os
import sys

import numpy as np
import torch

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "nwp"))

from lstm import TinyLstm  # noqa: E402
from personal_eval import CKPT, VOCAB, PersonalNgram  # noqa: E402

BOS, UNK = 1, 0


def load_messages(folder, gap_s):
    events = []
    for name in ("quality.1.jsonl", "quality.jsonl"):
        path = os.path.join(folder, name)
        if os.path.exists(path):
            with open(path, encoding="utf-8") as handle:
                events += [json.loads(line) for line in handle if line.strip()]
    words = [e for e in events if e.get("e") == "word" and e.get("w")]
    words.sort(key=lambda e: e["t"])
    messages, current, last_t = [], [], None
    for e in words:
        if last_t is not None and e["t"] - last_t > gap_s * 1000:
            messages.append(current)
            current = []
        current.append(e["w"].lower().replace("’", "'"))
        last_t = e["t"]
    if current:
        messages.append(current)
    return messages


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("folder")
    ap.add_argument("--gap", type=float, default=60.0, help="seconds between words that start a new message")
    ap.add_argument("--holdout", type=float, default=0.3)
    ap.add_argument("--betas", default="0.3,0.5,0.7,0.9")
    ap.add_argument("--ks", default="0.5,2,5")
    ap.add_argument("--no-context-shares", default="0.1,0.3")
    ap.add_argument("--caches", default="0,0.05,0.1,0.2", help="weights of a recency cache over the last words")
    ap.add_argument("--cache-words", type=int, default=200)
    args = ap.parse_args()

    ck = torch.load(CKPT, map_location="cpu", weights_only=False)
    a = ck["args"]
    lm = TinyLstm(a["vocab"], a["emb"], a["hidden"], a["layers"], 0.0)
    lm.load_state_dict(ck["model"])
    lm.eval()
    vocab = open(VOCAB, encoding="utf-8").read().split("\n")[: a["vocab"]]
    word_id = {w: i for i, w in enumerate(vocab) if i >= 3}

    messages = load_messages(args.folder, args.gap)
    total_words = sum(len(m) for m in messages)
    holdout_from = int(total_words * (1 - args.holdout))
    print(f"{len(messages)} messages, {total_words} words; held out: last {total_words - holdout_from} words")
    in_vocab = sum(1 for m in messages for w in m if w in word_id)
    print(f"words in the LSTM vocabulary: {100 * in_vocab / total_words:.1f}%")

    settings = [("lstm only", None)] + [
        (f"b={b} k={k} nc={nc} c={c}", (float(b), float(k), float(nc), float(c)))
        for b in args.betas.split(",") for k in args.ks.split(",") for nc in args.no_context_shares.split(",")
        for c in args.caches.split(",")
    ]
    recent = collections.deque(maxlen=args.cache_words)
    # tally[(name, split)] = [top1, top3, prefix1_top1, n]
    tally = collections.defaultdict(lambda: [0, 0, 0, 0])
    model = PersonalNgram()
    position = 0
    for message in messages:
        history = ["<s>"]
        ids = [BOS]
        for word in message:
            with torch.no_grad():
                logits = lm(torch.tensor([ids[-16:]]))[0][-1]
                row = torch.log_softmax(logits.float(), dim=-1).numpy()
            top = np.argpartition(-row[3:], 200)[:200] + 3
            lstm_ranked = [vocab[j] for j in top[np.argsort(-row[top])]]
            split = "held out" if position >= holdout_from else "tune"
            pool = list(dict.fromkeys(lstm_ranked + model.candidates(history, limit=50)))
            # Completion needs words outside the top 200 too: the personal model's whole vocabulary.
            completion_pool = pool + [w for w in model.uni if w not in set(pool)]
            n_ctx = model.evidence(history)
            recent_counts = collections.Counter(recent)
            pool += [w for w in recent_counts if w not in set(pool)]
            for name, setting in settings:
                if setting is None:
                    alpha, gamma = 0.0, 0.0
                else:
                    b, k, nc, gamma = setting
                    alpha = b * n_ctx / (n_ctx + k) if n_ctx else (b * nc if model.total else 0.0)
                    if not recent:
                        gamma = 0.0

                def score(w):
                    p_lm = math.exp(row[word_id[w]]) if w in word_id else 0.0
                    p = (1 - alpha) * p_lm + (alpha * model.prob(history, w) if alpha else 0.0)
                    if gamma:
                        p = (1 - gamma) * p + gamma * recent_counts[w] / len(recent)
                    return p

                ranked = sorted(pool, key=score, reverse=True)
                first = [w for w in completion_pool if w[:1] == word[:1]]
                if setting is None:
                    first = [w for w in first if w in word_id]
                best_first = max(first, key=score) if first else None
                t = tally[(name, split)]
                t[0] += ranked[0] == word
                t[1] += word in ranked[:3]
                t[2] += best_first == word
                t[3] += 1
            model.add(history, word)
            recent.append(word)
            history.append(word)
            ids.append(word_id.get(word, UNK))
            position += 1

    print(f"\n{'setting':24s} {'tune top1':>9s} {'top3':>6s} {'1-letter':>8s}   {'held top1':>9s} {'top3':>6s} "
          f"{'1-letter':>8s}")
    rows = []
    for name, _ in settings:
        tu = tally[(name, "tune")]
        ho = tally[(name, "held out")]
        rows.append((name, [x / max(tu[3], 1) for x in tu[:3]], [x / max(ho[3], 1) for x in ho[:3]]))
    rows.sort(key=lambda r: (r[0] != "lstm only", -r[1][1]))
    shipping = [r for r in rows if r[0] == "b=0.3 k=2 nc=0.1 c=0"]
    for name, tu, ho in rows[:12] + shipping:
        print(f"{name:24s} {100 * tu[0]:8.1f}% {100 * tu[1]:5.1f}% {100 * tu[2]:7.1f}%   "
              f"{100 * ho[0]:8.1f}% {100 * ho[1]:5.1f}% {100 * ho[2]:7.1f}%")


if __name__ == "__main__":
    main()
