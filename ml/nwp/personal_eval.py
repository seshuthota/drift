"""Does a personal n-gram cache improve next-word prediction for one writer?

Replays each OASST2 writer's own English prompts in time order. Before every word, the shipping LSTM (h256, 20k
vocab) predicts the next word; a personal model built only from that writer's earlier text is mixed in; then the
true word is added to the personal model, as the keyboard would after the user commits it. Only messages created
after the OASST1 export (2023-04-12) are used, so the LSTM never saw them in training.

Personal model (mirrored by `PersonalNgram.kt`): word counts, bigrams and trigrams inside sentences, combined with
Witten-Bell interpolation. Mixture: P = (1 - a) P_lstm + a P_user, a = beta * n / (n + k), where n is how often the
previous word has been seen followed by anything. Candidates are the LSTM top 20 plus the personal model's
followers, so words outside the LSTM vocabulary (names) can be predicted.

    ~/miniconda3/envs/llm/bin/python personal_eval.py --min-words 800 --users 40
"""

import argparse
import collections
import gzip
import json
import math
import os
import random
import re

import numpy as np
import torch

from lstm import TinyLstm

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
OASST2 = os.path.join(ROOT, "ml/work/nwp/raw2/2023-11-05_oasst2_ready.messages.jsonl.gz")
CKPT = os.path.join(ROOT, "ml/work/nwp/lstm_v20k/best.pt")
VOCAB = os.path.join(ROOT, "app/src/main/assets/nwp/vocab.txt")
CUTOFF = "2023-04-12"
TOKEN_RE = re.compile(r"[a-z0-9]+(?:'[a-z]+)?|[.!?]+|,|\n")
UNK, BOS, COMMA = 0, 1, 2


def tokens_of(text):
    """App tokenisation (NwpText): words, '<s>' at sentence ends, ',' kept. Digits become '<unk>'."""
    text = text.lower().replace("’", "'").replace("‘", "'")
    out = ["<s>"]
    for tok in TOKEN_RE.findall(text):
        c = tok[0]
        if c == "\n" or c in ".!?":
            if out[-1] != "<s>":
                out.append("<s>")
        elif c == ",":
            if out[-1] not in ("<s>", ","):
                out.append(",")
        elif any(ch.isdigit() for ch in tok):
            out.append("<unk>")
        else:
            out.append(tok)
    return out


class PersonalNgram:
    def __init__(self):
        self.uni = collections.Counter()
        self.total = 0
        self.bi = collections.defaultdict(collections.Counter)
        self.tri = collections.defaultdict(collections.Counter)

    @staticmethod
    def context(history):
        """The previous two tokens inside the sentence; commas are skipped, '<s>' starts a sentence."""
        words = [t for t in history if t != ","]
        p1 = words[-1] if words else "<s>"
        p2 = words[-2] if len(words) >= 2 and p1 != "<s>" else "<s>"
        return p2, p1

    def add(self, history, word):
        p2, p1 = self.context(history)
        self.uni[word] += 1
        self.total += 1
        self.bi[p1][word] += 1
        self.tri[(p2, p1)][word] += 1

    def evidence(self, history):
        _, p1 = self.context(history)
        return sum(self.bi[p1].values()) if p1 in self.bi else 0

    def prob(self, history, word):
        p2, p1 = self.context(history)
        p = self.uni[word] / self.total if self.total else 0.0
        for table, key in ((self.bi, p1), (self.tri, (p2, p1))):
            followers = table.get(key)
            if not followers:
                continue
            n = sum(followers.values())
            types = len(followers)
            lam = n / (n + types)
            p = lam * followers[word] / n + (1 - lam) * p
        return p

    def candidates(self, history, limit=20):
        p2, p1 = self.context(history)
        out = []
        for table, key in ((self.tri, (p2, p1)), (self.bi, p1)):
            if key in table:
                out += [w for w, _ in table[key].most_common(limit)]
        return out


def load_writers(min_words, max_users, seed):
    by_user = collections.defaultdict(list)
    with gzip.open(OASST2, "rt", encoding="utf-8") as handle:
        for line in handle:
            m = json.loads(line)
            if m.get("role") != "prompter" or m.get("lang") != "en":
                continue
            if m.get("created_date", "") < CUTOFF:
                continue
            by_user[m["user_id"]].append((m["created_date"], m["text"]))
    writers = []
    for user, messages in by_user.items():
        messages.sort()
        words = sum(len(t.split()) for _, t in messages)
        if words >= min_words:
            writers.append((user, [t for _, t in messages]))
    random.Random(seed).shuffle(writers)
    return writers[:max_users]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--min-words", type=int, default=800)
    ap.add_argument("--users", type=int, default=40)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--betas", default="0.3,0.5,0.7")
    ap.add_argument("--ks", default="2,5,10")
    args = ap.parse_args()

    ck = torch.load(CKPT, map_location="cpu", weights_only=False)
    a = ck["args"]
    lm = TinyLstm(a["vocab"], a["emb"], a["hidden"], a["layers"], 0.0)
    lm.load_state_dict(ck["model"])
    lm.eval()
    vocab = open(VOCAB, encoding="utf-8").read().split("\n")[: a["vocab"]]
    word_id = {w: i for i, w in enumerate(vocab) if i >= 3}

    writers = load_writers(args.min_words, args.users, args.seed)
    print(f"writers: {len(writers)}, words: {sum(len(' '.join(m).split()) for _, m in writers)}")
    settings = [(float(b), float(k)) for b in args.betas.split(",") for k in args.ks.split(",")]
    # tallies[name] = [top1, top3, n] for all words and for the second half of each writer's stream
    tallies = collections.defaultdict(lambda: [0, 0, 0])
    per_writer = collections.defaultdict(list)

    for user, messages in writers:
        model = PersonalNgram()
        stream_positions = sum(sum(1 for t in tokens_of(m)[1:] if t not in ("<s>", ",")) for m in messages)
        seen = 0
        writer_tally = collections.defaultdict(lambda: [0, 0])
        for text in messages:
            toks = tokens_of(text)
            ids = [BOS if t == "<s>" else COMMA if t == "," else word_id.get(t, UNK) for t in toks]
            with torch.no_grad():
                logits = lm(torch.tensor([ids[-512:] if len(ids) > 512 else ids]))[0]
                logp = torch.log_softmax(logits.float(), dim=-1).numpy()
            offset = max(0, len(ids) - 512)
            for i in range(1, len(toks)):
                target = toks[i]
                history = toks[:i]
                if target in ("<s>", ",", "<unk>"):
                    model_history_word = None
                else:
                    model_history_word = target
                if model_history_word is not None and i - 1 - offset >= 0:
                    seen += 1
                    late = seen > stream_positions / 2
                    row = logp[i - 1 - offset]
                    top = np.argpartition(-row[3:], 20)[:20] + 3
                    lstm_top = [vocab[j] for j in top[np.argsort(-row[top])]]
                    names = [("lstm", None)] + [(f"mix b={b} k={k:g}", (b, k)) for b, k in settings]
                    pool = list(dict.fromkeys(lstm_top + model.candidates(history)))
                    n_ctx = model.evidence(history)
                    for name, setting in names:
                        if setting is None:
                            ranked = lstm_top
                        else:
                            b, k = setting
                            alpha = b * n_ctx / (n_ctx + k) if n_ctx else b * 0.1 * (model.total > 0)

                            def score(w):
                                p_lm = math.exp(row[word_id[w]]) if w in word_id else 0.0
                                return (1 - alpha) * p_lm + alpha * model.prob(history, w)

                            ranked = sorted(pool, key=score, reverse=True)
                        hit1 = ranked[0] == target
                        hit3 = target in ranked[:3]
                        for bucket in (name, name + " | 2nd half" if late else None):
                            if bucket is None:
                                continue
                            t = tallies[bucket]
                            t[0] += hit1
                            t[1] += hit3
                            t[2] += 1
                        writer_tally[name][0] += hit1
                        writer_tally[name][1] += 1
                if target not in ("<s>", ","):
                    model.add(history, target)
        for name, (h, n) in writer_tally.items():
            per_writer[name].append(h / n if n else 0.0)

    print(f"{'setting':34s} {'top1':>7s} {'top3':>7s} {'n':>7s}")
    for name in sorted(tallies, key=lambda s: (" | " in s, s != "lstm", s)):
        t1, t3, n = tallies[name]
        print(f"{name:34s} {100 * t1 / n:6.2f}% {100 * t3 / n:6.2f}% {n:7d}")
    base = per_writer["lstm"]
    for name, values in per_writer.items():
        if name == "lstm":
            continue
        better = sum(1 for x, y in zip(values, base) if x > y)
        print(f"{name:34s} top-1 better for {better}/{len(values)} writers")


if __name__ == "__main__":
    main()
