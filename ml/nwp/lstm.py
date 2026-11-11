"""Tiny word-level LSTM next-word model (tied embeddings), trained from scratch.

    python lstm.py train --data ../work/nwp --out ../work/nwp/lstm --vocab 20000 --tokens 150000000
    python lstm.py eval --ckpt ../work/nwp/lstm/best.pt --data ../work/nwp --positions 20000

Ids are the frequency-ordered ids from prepare.py; ids >= --vocab are mapped to <unk>, so the model
never predicts words outside the top-K. Evaluation samples the same positions as ngram.py.
"""

import argparse
import json
import math
import os
import time

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

UNK, BOS, COMMA = 0, 1, 2
N_SPECIAL = 3


class TinyLstm(nn.Module):
    def __init__(self, vocab, emb=128, hidden=256, layers=1, dropout=0.1):
        super().__init__()
        self.emb = nn.Embedding(vocab, emb)
        self.drop = nn.Dropout(dropout)
        self.rnn = nn.LSTM(emb, hidden, num_layers=layers, batch_first=True)
        self.proj = nn.Linear(hidden, emb)
        self.out_bias = nn.Parameter(torch.zeros(vocab))

    def forward(self, ids, last_only=False):
        x, _ = self.rnn(self.drop(self.emb(ids)))
        if last_only:
            x = x[:, -1]
        h = self.proj(self.drop(x))
        return F.linear(h, self.emb.weight, self.out_bias)  # tied output layer


def load(data, name, vocab, limit=None):
    a = np.load(os.path.join(data, f"{name}.npy"), mmap_mode="r")
    a = np.asarray(a[:limit] if limit else a).astype(np.int64)
    a[a >= vocab] = UNK
    return torch.from_numpy(a)


def batches(stream, chat, batch, seq, chat_frac, gen, device):
    """Random contiguous windows; a fraction of rows comes from the conversational corpus."""
    n_chat = int(round(batch * chat_frac))
    while True:
        s1 = torch.randint(0, len(stream) - seq - 1, (batch - n_chat,), generator=gen)
        s2 = torch.randint(0, len(chat) - seq - 1, (n_chat,), generator=gen)
        ar = torch.arange(seq + 1)
        rows = torch.cat([stream[s1[:, None] + ar], chat[s2[:, None] + ar]])
        rows = rows.to(device, non_blocking=True)
        yield rows[:, :-1], rows[:, 1:]


def sample_positions(tokens_full, n, seed=0):
    """Same positions as ngram.evaluate: index i predicts tokens[i + 2]."""
    valid = np.flatnonzero(tokens_full[2:] >= N_SPECIAL)
    rng = np.random.default_rng(seed)
    return rng.choice(valid, size=min(n, len(valid)), replace=False)


@torch.no_grad()
def evaluate(model, tokens_full, vocab, positions, context=32, device="cpu", seed=0):
    model.eval()
    idx = sample_positions(tokens_full, positions, seed)
    toks = tokens_full.astype(np.int64)
    toks_in = toks.copy()
    toks_in[toks_in >= vocab] = UNK
    hits = np.zeros(3)
    for s in range(0, len(idx), 1024):
        chunk = idx[s:s + 1024]
        ctx = np.full((len(chunk), context), BOS, dtype=np.int64)
        for r, i in enumerate(chunk):
            end = i + 2
            win = toks_in[max(0, end - context):end]
            ctx[r, context - len(win):] = win
        logits = model(torch.from_numpy(ctx).to(device), last_only=True)
        logits[:, :N_SPECIAL] = -1e9
        top = logits.topk(5, dim=-1).indices.cpu().numpy()
        tgt = toks[chunk + 2][:, None]
        hits += [(top[:, :1] == tgt).any(1).sum(), (top[:, :3] == tgt).any(1).sum(),
                 (top == tgt).any(1).sum()]
    model.train()
    return hits / len(idx)


def train(args):
    device = "cuda" if torch.cuda.is_available() else "cpu"
    torch.manual_seed(args.seed)
    gen = torch.Generator().manual_seed(args.seed)
    os.makedirs(args.out, exist_ok=True)
    stream = load(args.data, "web_train", args.vocab, args.tokens)
    chat = load(args.data, "chat_train", args.vocab)
    val_full = np.load(os.path.join(args.data, "web_val.npy"))
    model = TinyLstm(args.vocab, args.emb, args.hidden, args.layers, args.dropout).to(device)
    n_params = sum(p.numel() for p in model.parameters())
    tokens_per_step = args.batch * args.seq
    steps = args.steps or len(stream) // tokens_per_step
    print(f"params={n_params:,} steps={steps} device={device}", flush=True)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=0.01)
    warm = min(1000, steps // 20)
    sched = torch.optim.lr_scheduler.LambdaLR(
        opt, lambda s: min(1.0, (s + 1) / warm) * 0.5 * (1 + math.cos(math.pi * min(s / steps, 1.0)))
    )
    it = batches(stream, chat, args.batch, args.seq, args.chat_frac, gen, device)
    best, t0, running = 0.0, time.time(), 0.0
    for step in range(1, steps + 1):
        x, y = next(it)
        with torch.autocast(device_type="cuda", dtype=torch.bfloat16, enabled=device == "cuda"):
            logits = model(x)
        loss = F.cross_entropy(logits.float().reshape(-1, args.vocab), y.reshape(-1), ignore_index=UNK)
        opt.zero_grad(set_to_none=True)
        loss.backward()
        nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        opt.step()
        sched.step()
        running += loss.item()
        if step % args.eval_every == 0 or step == steps:
            web = evaluate(model, val_full, args.vocab, 5000, device=device)
            rec = {"step": step, "loss": running / args.eval_every, "web_val_top1": web[0],
                   "web_val_top3": web[1], "secs": round(time.time() - t0)}
            running = 0.0
            print(json.dumps(rec), flush=True)
            with open(os.path.join(args.out, "metrics.jsonl"), "a") as f:
                f.write(json.dumps(rec) + "\n")
            if web[1] >= best:
                best = web[1]
                torch.save({"model": model.state_dict(), "args": vars(args), "step": step},
                           os.path.join(args.out, "best.pt"))
    print(f"done best web_val top3={best:.4f} secs={time.time() - t0:.0f}", flush=True)


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    t = sub.add_parser("train")
    t.add_argument("--data", required=True)
    t.add_argument("--out", required=True)
    t.add_argument("--vocab", type=int, default=20000)
    t.add_argument("--tokens", type=int, default=150_000_000)
    t.add_argument("--emb", type=int, default=128)
    t.add_argument("--hidden", type=int, default=256)
    t.add_argument("--layers", type=int, default=1)
    t.add_argument("--dropout", type=float, default=0.1)
    t.add_argument("--batch", type=int, default=256)
    t.add_argument("--seq", type=int, default=64)
    t.add_argument("--chat-frac", type=float, default=0.15)
    t.add_argument("--lr", type=float, default=3e-3)
    t.add_argument("--steps", type=int, default=0)
    t.add_argument("--eval-every", type=int, default=2000)
    t.add_argument("--seed", type=int, default=1234)
    e = sub.add_parser("eval")
    e.add_argument("--ckpt", required=True)
    e.add_argument("--data", required=True)
    e.add_argument("--positions", type=int, default=20000)
    e.add_argument("--sets", nargs="*", default=["web_test", "chat_test"])
    args = ap.parse_args()
    if args.cmd == "train":
        train(args)
    else:
        device = "cuda" if torch.cuda.is_available() else "cpu"
        ck = torch.load(args.ckpt, map_location=device)
        a = ck["args"]
        model = TinyLstm(a["vocab"], a["emb"], a["hidden"], a["layers"], 0.0).to(device)
        model.load_state_dict(ck["model"])
        for name in args.sets:
            full = np.load(os.path.join(args.data, f"{name}.npy"))
            t1, t3, t5 = evaluate(model, full, a["vocab"], args.positions, device=device)
            print(f"{name}: top1={t1:.2%} top3={t3:.2%} top5={t5:.2%}", flush=True)


if __name__ == "__main__":
    main()
