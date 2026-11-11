"""Interpolated Kneser-Ney trigram next-word model, built with numpy, then pruned for the phone.

    python ngram.py build --data ../work/nwp --tokens 200000000 --out ../work/nwp/kn3.npz \
        --min3 3 --min2 2
    python ngram.py eval --model ../work/nwp/kn3.npz --data ../work/nwp --positions 20000

Sentence-initial context is (<s>, <s>). Discounts are estimated from counts-of-counts before
pruning; backoff weights are computed on the unpruned model (ARPA-style), so pruning only drops
probability mass, which does not matter for ranking.
"""

import argparse
import os
import time

import numpy as np

UNK, BOS, COMMA = 0, 1, 2
N_SPECIAL = 3


def load_tokens(data, names, limit=None):
    arrs = []
    for name in names:
        a = np.load(os.path.join(data, f"{name}.npy"), mmap_mode="r")
        arrs.append(np.asarray(a[:limit] if limit else a))
    return np.concatenate(arrs) if len(arrs) > 1 else arrs[0]


def context_u(tokens):
    """Left-most context word per trigram position, with (x, <s>) contexts mapped to (<s>, <s>)."""
    u = tokens[:-2].copy()
    u[tokens[1:-1] == BOS] = BOS
    return u


def unique_counts(keys):
    """In-place sort, then (unique keys, counts)."""
    keys.sort()
    starts = np.flatnonzero(np.concatenate(([True], keys[1:] != keys[:-1])))
    counts = np.diff(np.append(starts, len(keys))).astype(np.int64)
    return keys[starts], counts


def discount(counts):
    n1 = int((counts == 1).sum())
    n2 = int((counts == 2).sum())
    return n1 / max(n1 + 2 * n2, 1)


def group(ctx):
    """Sorted context array -> (unique contexts, start offsets incl. final end)."""
    starts = np.flatnonzero(np.concatenate(([True], ctx[1:] != ctx[:-1])))
    return ctx[starts], np.append(starts, len(ctx))


def build(args):
    t0 = time.time()
    tokens = load_tokens(args.data, ["web_train"], args.tokens)
    chat = load_tokens(args.data, ["chat_train"])
    # Up-weight conversational text by repetition.
    tokens = np.concatenate([tokens] + [chat] * args.chat_weight)
    V = int(open(os.path.join(args.data, "vocab.txt")).read().count("\n"))
    print(f"tokens={len(tokens):,} V={V}", flush=True)

    keys = context_u(tokens).astype(np.int64)
    keys *= V
    keys += tokens[1:-1]
    keys *= V
    keys += tokens[2:]
    del tokens
    tri, c3 = unique_counts(keys)
    del keys
    print(f"trigram types={len(tri):,} ({time.time() - t0:.0f}s)", flush=True)
    D3 = discount(c3)

    # Trigram level: context (u,v) -> sum of counts and number of types.
    ctx3_all, off3_all = group(tri // V)
    tot3 = np.add.reduceat(c3, off3_all[:-1])
    types3 = np.diff(off3_all)
    gamma3 = D3 * types3 / tot3

    # Bigram level with continuation counts N1+(. v w) = number of trigram types ending in (v, w).
    big, n2 = unique_counts(tri % (V * V))
    D2 = discount(n2)
    ctx2_all, off2_all = group(big // V)
    tot2 = np.add.reduceat(n2, off2_all[:-1])
    types2 = np.diff(off2_all)
    gamma2 = D2 * types2 / tot2

    # Unigram continuation: number of bigram types ending in w.
    n1 = np.bincount((big % V).astype(np.int64), minlength=V).astype(np.float64)
    n1[:N_SPECIAL] = 0
    p1 = (n1 + 0.01) / (n1 + 0.01).sum()

    # Discounted (interpolation-free) parts, then prune by raw/continuation count.
    ctx3_of = np.repeat(np.arange(len(ctx3_all)), types3)
    pw3 = np.maximum(c3 - D3, 0) / tot3[ctx3_of]
    keep3 = (c3 >= args.min3) & ((tri % V) >= N_SPECIAL)
    ctx2_of = np.repeat(np.arange(len(ctx2_all)), types2)
    pw2 = np.maximum(n2 - D2, 0) / tot2[ctx2_of]
    keep2 = (n2 >= args.min2) & ((big % V) >= N_SPECIAL)

    def pack(ctx_all, gamma, ctx_of, keys, pw, keep):
        kept_ctx = np.unique(ctx_of[keep])
        remap = np.full(len(ctx_all), -1, dtype=np.int64)
        remap[kept_ctx] = np.arange(len(kept_ctx))
        offsets = np.searchsorted(remap[ctx_of[keep]], np.arange(len(kept_ctx) + 1))
        return {
            "ctx": ctx_all[kept_ctx].astype(np.uint32),
            "gamma": gamma[kept_ctx].astype(np.float32),
            "off": offsets.astype(np.uint32),
            "word": (keys[keep] % V).astype(np.uint16),
            "p": pw[keep].astype(np.float32),
        }

    m3 = pack(ctx3_all, gamma3, ctx3_of, tri, pw3, keep3)
    m2 = pack(ctx2_all, gamma2, ctx2_of, big, pw2, keep2)
    np.savez(
        args.out, V=V, p1=p1.astype(np.float32), D2=D2, D3=D3,
        **{f"t_{k}": v for k, v in m3.items()}, **{f"b_{k}": v for k, v in m2.items()},
    )
    # On-device estimate: word uint16 + prob uint8 per entry; ctx key + offset + gamma per context.
    est = (len(m3["word"]) + len(m2["word"])) * 3 + (len(m3["ctx"]) + len(m2["ctx"])) * 9 + V * 2
    print(
        f"D2={D2:.3f} D3={D3:.3f} kept trigrams={len(m3['word']):,} ctx={len(m3['ctx']):,} "
        f"bigrams={len(m2['word']):,} ctx={len(m2['ctx']):,} est_device_size={est / 1e6:.1f} MB "
        f"({time.time() - t0:.0f}s)",
        flush=True,
    )


class KN3:
    def __init__(self, path):
        z = np.load(path)
        self.V = int(z["V"])
        self.p1 = z["p1"].astype(np.float64)
        self.t = {k[2:]: z[k] for k in z.files if k.startswith("t_")}
        self.b = {k[2:]: z[k] for k in z.files if k.startswith("b_")}

    @staticmethod
    def _row(level, key):
        i = np.searchsorted(level["ctx"], key)
        if i < len(level["ctx"]) and level["ctx"][i] == key:
            s, e = level["off"][i], level["off"][i + 1]
            return float(level["gamma"][i]), level["word"][s:e], level["p"][s:e]
        return None

    def distribution(self, u, v):
        p = self.p1.copy()
        row = self._row(self.b, v)
        if row:
            g, w, pw = row
            p *= g
            p[w] += pw
        row = self._row(self.t, u * self.V + v)
        if row:
            g, w, pw = row
            p *= g
            p[w] += pw
        p[:N_SPECIAL] = 0
        return p


def evaluate(model, tokens, positions, seed=0):
    """Top-1/3/5 next-word accuracy at random positions whose target is a dictionary word."""
    u = context_u(tokens)
    v, w = tokens[1:-1], tokens[2:]
    valid = np.flatnonzero(w >= N_SPECIAL)
    rng = np.random.default_rng(seed)
    idx = rng.choice(valid, size=min(positions, len(valid)), replace=False)
    hits = np.zeros(3)
    for i in idx:
        p = model.distribution(int(u[i]), int(v[i]))
        top = np.argpartition(-p, 5)[:5]
        top = top[np.argsort(-p[top])]
        t = w[i]
        hits += [t == top[0], t in top[:3], t in top[:5]]
    return hits / len(idx)


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    b = sub.add_parser("build")
    b.add_argument("--data", required=True)
    b.add_argument("--out", required=True)
    b.add_argument("--tokens", type=int, default=200_000_000)
    b.add_argument("--chat-weight", type=int, default=5)
    b.add_argument("--min3", type=int, default=3)
    b.add_argument("--min2", type=int, default=2)
    e = sub.add_parser("eval")
    e.add_argument("--model", required=True)
    e.add_argument("--data", required=True)
    e.add_argument("--positions", type=int, default=20000)
    e.add_argument("--sets", nargs="*", default=["web_test", "chat_test"])
    args = ap.parse_args()
    if args.cmd == "build":
        build(args)
    else:
        model = KN3(args.model)
        for name in args.sets:
            t1, t3, t5 = evaluate(model, load_tokens(args.data, [name]), args.positions)
            print(f"{name}: top1={t1:.2%} top3={t3:.2%} top5={t5:.2%}", flush=True)


if __name__ == "__main__":
    main()
