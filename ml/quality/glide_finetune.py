"""Fine-tune the shipped glide model on the user's own logged traces, mixed with FUTO data.

Training data: every labelled glide in the quality log drawn before --cut-t (labels as in `analyze.py`: kept ->
committed word, picked -> the pick, deleted -> the word entered next), featurized exactly like the app. Glides from
--cut-t on are never used here; judge the result on them with `glide_replay.py --model ... --cut-t ...`.

Each epoch mixes the user's traces (repeated --user-repeat times) with --futo-rows random FUTO training rows, so the
model adapts to the user's strokes without forgetting general swiping. FUTO dev greedy accuracy is printed for the
start checkpoint and after every epoch as the forgetting check.

    ~/miniconda3/envs/llm/bin/python ml/quality/glide_finetune.py ml/quality/data/diagnostics --cut-t <t> \\
        --out ml/work/glide_user_ft
"""

import argparse
import json
import os
import sys

import numpy as np
import torch
import torch.nn as nn

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "ml", "ctc"))
sys.path.insert(0, HERE)

from glide_replay import featurize, labels_of, letter_bounds, load_events, usable  # noqa: E402
from model import BLANK, SwipeCtc, greedy_decode, pad_targets  # noqa: E402

WORK = os.path.join(ROOT, "ml", "work")


def user_rows(folder, cut_t):
    events = load_events(folder)
    labels = labels_of(events)
    keys, train, held = None, [], 0
    for e in events:
        if e["e"] == "layout":
            keys = e["keys"]
            continue
        if e["e"] != "glide" or keys is None:
            continue
        target = (labels.get(e["id"]) or "").replace("'", "")
        if not usable(target):
            continue
        feats = featurize(e["pts"], letter_bounds(keys))
        if feats is None:
            continue
        if e["t"] >= cut_t:
            held += 1
            continue
        train.append((feats, target))
    return train, held


@torch.no_grad()
def greedy_acc(model, feats, words, device):
    model.eval()
    preds = []
    for i in range(0, len(feats), 1024):
        preds += greedy_decode(model(feats[i:i + 1024].to(device)).cpu())
    return sum(p == w for p, w in zip(preds, words)) / len(words)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("folder")
    ap.add_argument("--cut-t", type=int, required=True)
    ap.add_argument("--ckpt", default=os.path.join(WORK, "run_1m/best.pt"))
    ap.add_argument("--futo", default=os.path.join(WORK, "train_full.npz"))
    ap.add_argument("--dev", default=os.path.join(WORK, "dev.npz"))
    ap.add_argument("--out", default=os.path.join(WORK, "glide_user_ft"))
    ap.add_argument("--futo-rows", type=int, default=40000)
    ap.add_argument("--user-repeat", type=int, default=10)
    ap.add_argument("--epochs", type=int, default=6)
    ap.add_argument("--lr", type=float, default=3e-4)
    ap.add_argument("--batch", type=int, default=256)
    ap.add_argument("--seed", type=int, default=1234)
    args = ap.parse_args()
    torch.manual_seed(args.seed)
    rng = np.random.default_rng(args.seed)
    device = "cuda" if torch.cuda.is_available() else "cpu"
    os.makedirs(args.out, exist_ok=True)

    train, held = user_rows(args.folder, args.cut_t)
    print(f"user traces: {len(train)} for training, {held} held out (never used here)", flush=True)
    user_feats = torch.from_numpy(np.stack([f for f, _ in train]))
    user_words = [w for _, w in train]

    futo = np.load(args.futo, allow_pickle=True)
    futo_feats_all = futo["feats"]
    futo_words_all = [str(w) for w in futo["words"]]
    dev = np.load(args.dev, allow_pickle=True)
    dev_feats = torch.from_numpy(dev["feats"].astype(np.float32))
    dev_words = [str(w) for w in dev["words"]]

    ck = torch.load(args.ckpt, map_location=device, weights_only=False)
    model = SwipeCtc(ch=ck["args"]["ch"]).to(device)
    model.load_state_dict(ck["model"])
    start_dev = greedy_acc(model, dev_feats, dev_words, device)
    start_user = greedy_acc(model, user_feats, user_words, device)
    print(f"start: FUTO dev greedy {start_dev:.4f}, user train greedy {start_user:.4f}", flush=True)

    criterion = nn.CTCLoss(blank=BLANK, zero_infinity=True, reduction="mean")
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-2)
    log = []
    for epoch in range(1, args.epochs + 1):
        pick = rng.choice(len(futo_words_all), size=args.futo_rows, replace=False)
        feats = torch.cat([
            torch.from_numpy(futo_feats_all[pick].astype(np.float32)),
            user_feats.repeat(args.user_repeat, 1, 1),
        ])
        words = [futo_words_all[i] for i in pick] + user_words * args.user_repeat
        lr = args.lr * 0.5 * (1 + np.cos(np.pi * (epoch - 1) / args.epochs))
        for g in opt.param_groups:
            g["lr"] = lr
        model.train()
        perm = torch.randperm(len(words))
        total = 0.0
        batches = 0
        for i in range(0, len(words), args.batch):
            idx = perm[i:i + args.batch]
            x = feats[idx].to(device)
            targets, lengths = pad_targets([words[j] for j in idx.tolist()], device)
            log_probs = model(x)
            input_lengths = torch.full((x.size(0),), log_probs.size(1), dtype=torch.long, device=device)
            loss = criterion(log_probs.transpose(0, 1), targets, input_lengths, lengths)
            opt.zero_grad(set_to_none=True)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 5.0)
            opt.step()
            total += loss.item()
            batches += 1
        rec = {
            "epoch": epoch, "lr": float(lr), "loss": total / batches,
            "futo_dev_greedy": greedy_acc(model, dev_feats, dev_words, device),
            "user_train_greedy": greedy_acc(model, user_feats, user_words, device),
        }
        log.append(rec)
        print(json.dumps(rec), flush=True)
    torch.save({"model": model.state_dict(), "args": ck["args"], "finetune": vars(args), "log": log,
                "start_futo_dev_greedy": start_dev}, os.path.join(args.out, "best.pt"))
    print(f"saved {os.path.join(args.out, 'best.pt')}")


if __name__ == "__main__":
    main()
