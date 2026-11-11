"""Train the baseline CTC swipe encoder and report word accuracy.

    python train.py --train work/train.npz --dev work/dev.npz --out work/run1 --epochs 40
"""

import argparse
import json
import os
import time

import numpy as np
import torch
import torch.nn as nn

from model import BLANK, SwipeCtc, greedy_decode, pad_targets


def load(path, device, limit=None):
    d = np.load(path, allow_pickle=True)
    feats = torch.from_numpy(d["feats"].astype(np.float32))
    words = [str(w) for w in d["words"]]
    if limit:
        feats, words = feats[:limit], words[:limit]
    return feats.to(device), words


@torch.no_grad()
def evaluate(model, feats, words, batch=512, device="cpu"):
    model.eval()
    correct = 0
    preds = []
    for i in range(0, len(feats), batch):
        out = model(feats[i : i + batch])
        preds.extend(greedy_decode(out.cpu()))
    for p, w in zip(preds, words):
        correct += int(p == w)
    return correct / max(len(words), 1), preds


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--train", default="work/train.npz")
    ap.add_argument("--dev", default="work/dev.npz")
    ap.add_argument("--out", default="work/run1")
    ap.add_argument("--epochs", type=int, default=40)
    ap.add_argument("--batch", type=int, default=256)
    ap.add_argument("--lr", type=float, default=3e-3)
    ap.add_argument("--wd", type=float, default=1e-2)
    ap.add_argument("--ch", type=int, default=96)
    ap.add_argument("--warmup", type=int, default=500)
    ap.add_argument("--smoke", type=int, default=0, help="limit train rows")
    ap.add_argument("--eval-every", type=int, default=1)
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    device = "cuda" if torch.cuda.is_available() else "cpu"
    torch.manual_seed(1234)

    feats, words = load(args.train, device, args.smoke or None)
    dev_feats, dev_words = load(args.dev, device)
    print(f"train={len(words)} dev={len(dev_words)} device={device}", flush=True)

    model = SwipeCtc(ch=args.ch).to(device)
    n_params = sum(p.numel() for p in model.parameters())
    print(f"params={n_params}", flush=True)

    criterion = nn.CTCLoss(blank=BLANK, zero_infinity=True, reduction="mean")
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=args.wd)

    steps_per_epoch = (len(words) + args.batch - 1) // args.batch
    total_steps = steps_per_epoch * args.epochs
    warmup = min(args.warmup, max(total_steps // 20, 1))

    def lr_at(step):
        if step < warmup:
            return args.lr * (step + 1) / warmup
        p = (step - warmup) / max(total_steps - warmup, 1)
        return args.lr * 0.5 * (1 + np.cos(np.pi * p))

    metrics_path = os.path.join(args.out, "metrics.jsonl")
    best = 0.0
    step = 0
    t0 = time.time()
    for epoch in range(1, args.epochs + 1):
        model.train()
        perm = torch.randperm(len(words), device=device)
        tot_loss = 0.0
        n_batches = 0
        for i in range(0, len(words), args.batch):
            idx = perm[i : i + args.batch]
            x = feats[idx]
            batch_words = [words[j] for j in idx.tolist()]
            targets, target_lengths = pad_targets(batch_words, device)
            log_probs = model(x)
            input_lengths = torch.full(
                (x.size(0),), log_probs.size(1), dtype=torch.long, device=device
            )
            loss = criterion(
                log_probs.transpose(0, 1), targets, input_lengths, target_lengths
            )
            opt.zero_grad(set_to_none=True)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 5.0)
            lr = lr_at(step)
            for g in opt.param_groups:
                g["lr"] = lr
            opt.step()
            step += 1
            tot_loss += loss.item()
            n_batches += 1

        rec = {"epoch": epoch, "loss": tot_loss / max(n_batches, 1), "lr": lr_at(step)}
        if epoch % args.eval_every == 0 or epoch == args.epochs:
            acc, preds = evaluate(model, dev_feats, dev_words, device=device)
            rec["dev_greedy_acc"] = acc
            rec["secs"] = round(time.time() - t0, 1)
            if acc >= best:
                best = acc
                torch.save(
                    {"model": model.state_dict(), "args": vars(args), "epoch": epoch,
                     "dev_greedy_acc": acc},
                    os.path.join(args.out, "best.pt"),
                )
            if epoch == args.epochs:
                with open(os.path.join(args.out, "dev_preds.txt"), "w") as f:
                    for w, p in zip(dev_words, preds):
                        f.write(f"{w}\t{p}\n")
        print(json.dumps(rec), flush=True)
        with open(metrics_path, "a") as f:
            f.write(json.dumps(rec) + "\n")

    print(f"done best_dev_greedy_acc={best:.4f} secs={time.time() - t0:.0f}", flush=True)


if __name__ == "__main__":
    main()
