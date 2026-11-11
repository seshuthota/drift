"""Lexicon-constrained CTC prefix beam search (pure numpy/python, prototype-grade)."""

import math

import numpy as np

ALPHA = "abcdefghijklmnopqrstuvwxyz"
BLANK = len(ALPHA)


def load_lexicon(path, max_words=None):
    """FrequencyWords format: `word count` per line."""
    entry = {}
    for line in open(path, encoding="utf-8"):
        parts = line.split()
        if len(parts) != 2:
            continue
        w, c = parts[0].lower(), parts[1]
        if not (2 <= len(w) <= 26) or not w.isalpha():
            continue
        entry[w] = (True, math.log1p(float(c)))
        if max_words and len(entry) >= max_words:
            break
    # add every proper prefix so lookups are O(1)
    prefixes = {}
    for w, (_, lf) in entry.items():
        for i in range(1, len(w)):
            prefixes.setdefault(w[:i], (False, 0.0))
    prefixes.update(entry)
    return prefixes


def ctc_beam(log_probs, lexicon, beam=120, top_chars=12, alpha=0.3, beta=0.0):
    """log_probs: [T,27]. Returns list of (word, score) best-first (unique words)."""
    T = log_probs.shape[0]
    beams = {"": (1.0, 0.0)}  # prefix -> (p_blank, p_nonblank)
    for t in range(T):
        p = np.exp(log_probs[t]).astype(np.float64)
        cand = np.argsort(p)[::-1][:top_chars]
        cand = [(int(c), float(p[c])) for c in cand if c != BLANK]
        nxt = {}
        for pref, (pb, pnb) in beams.items():
            tot = pb + pnb
            last = pref[-1] if pref else None
            # blank
            b, nb = nxt.get(pref, (0.0, 0.0))
            nxt[pref] = (b + tot * p[BLANK], nb)
            for c, pc in cand:
                if pc <= 0.0:
                    continue
                ch = ALPHA[c]
                if ch == last:
                    # repeat without blank: stays the same prefix
                    b, nb = nxt.get(pref, (0.0, 0.0))
                    nxt[pref] = (b, nb + pnb * pc)
                    # repeat with an intervening blank: prefix + ch
                    plus = pref + ch
                    if plus in lexicon:
                        b, nb = nxt.get(plus, (0.0, 0.0))
                        nxt[plus] = (b, nb + pb * pc)
                else:
                    plus = pref + ch
                    if plus not in lexicon:
                        continue
                    b, nb = nxt.get(plus, (0.0, 0.0))
                    nxt[plus] = (b, nb + tot * pc)
        items = sorted(nxt.items(), key=lambda kv: kv[1][0] + kv[1][1], reverse=True)
        beams = dict(items[:beam])
    scored = []
    seen = set()
    for pref, (pb, pnb) in beams.items():
        is_word, lf = lexicon.get(pref, (False, 0.0))
        if not is_word or pref in seen:
            continue
        seen.add(pref)
        s = math.log(max(pb + pnb, 1e-30)) + alpha * lf + beta * len(pref)
        scored.append((pref, s))
    scored.sort(key=lambda x: x[1], reverse=True)
    return scored
