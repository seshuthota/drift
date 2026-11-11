"""Evaluate a trained checkpoint: greedy vs lexicon beam top-1/3/5.

    python eval_beam.py --ckpt work/run1/best.pt --npz work/dev.npz --lex /tmp/en_50k.txt --limit 2000
"""

import argparse
import json

import numpy as np
import torch

from beam import ctc_beam, load_lexicon
from model import BLANK, SwipeCtc, greedy_decode


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ckpt", required=True)
    ap.add_argument("--npz", default="work/dev.npz")
    ap.add_argument("--lex", default="/tmp/en_50k.txt")
    ap.add_argument("--max-words", type=int, default=0)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--beam", type=int, default=120)
    ap.add_argument("--alpha", type=float, default=0.3)
    ap.add_argument("--beta", type=float, default=0.0)
    ap.add_argument("--out", default="")
    args = ap.parse_args()

    device = "cuda" if torch.cuda.is_available() else "cpu"
    ck = torch.load(args.ckpt, map_location=device)
    model = SwipeCtc(ch=ck["args"]["ch"]).to(device)
    model.load_state_dict(ck["model"])
    model.eval()

    d = np.load(args.npz, allow_pickle=True)
    feats = torch.from_numpy(d["feats"].astype(np.float32))
    words = [str(w) for w in d["words"]]
    if args.limit:
        feats, words = feats[: args.limit], words[: args.limit]

    lexicon = load_lexicon(args.lex, max_words=args.max_words or None)
    print(f"lexicon prefixes={len(lexicon)} rows={len(words)}", flush=True)

    greedy_hits = 0
    beam_top1 = beam_top3 = beam_top5 = 0
    rows = []
    with torch.no_grad():
        for i in range(0, len(feats), 256):
            out = model(feats[i : i + 256].to(device)).cpu().numpy()
            for j, lp in enumerate(out):
                idx = i + j
                w = words[idx]
                greedy = greedy_decode(torch.from_numpy(lp)[None])[0]
                greedy_hits += int(greedy == w)
                ranked = ctc_beam(
                    lp, lexicon, beam=args.beam, alpha=args.alpha, beta=args.beta
                )
                top = [w2 for w2, _ in ranked[:5]]
                beam_top1 += int(bool(top) and top[0] == w)
                beam_top3 += int(w in top[:3])
                beam_top5 += int(w in top[:5])
                if idx < 40:
                    rows.append({"word": w, "greedy": greedy, "beam": top[:5]})
            if (i // 256) % 10 == 0:
                print(f"{min(i + 256, len(feats))}/{len(feats)}", flush=True)

    n = len(words)
    res = {
        "rows": n,
        "greedy_top1": greedy_hits / n,
        "beam_top1": beam_top1 / n,
        "beam_top3": beam_top3 / n,
        "beam_top5": beam_top5 / n,
        "beam": args.beam,
        "alpha": args.alpha,
        "beta": args.beta,
        "lexicon": args.lex,
        "ckpt": args.ckpt,
    }
    print(json.dumps(res, indent=2), flush=True)
    if args.out:
        with open(args.out, "w") as f:
            json.dump({"summary": res, "examples": rows}, f, indent=2)


if __name__ == "__main__":
    main()
