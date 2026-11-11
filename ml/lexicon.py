"""Build a cleaned English word list from FrequencyWords, measured against the app dictionary.

The app list (``ime/dict/data.json``) mixes real words with misspellings and stores
frequencies only in 128–255. This script builds a candidate and writes it; it does
not replace the asset.

A word is kept when Hunspell accepts it (en_US/GB/CA/AU) and either FrequencyWords
or the next-word training corpus attests it. Counts come from FrequencyWords.
Contractions are absent there (``don't`` was tokenized as ``don`` + ``'t``), so those
take their count from the training corpus, scaled onto the FrequencyWords range.
A word one cheap edit from another word at least ``--ratio`` times more common is
dropped unless the training corpus attests it.

    python3 ml/lexicon.py

FrequencyWords: ``ml/work/en_147k.txt``. Training counts: ``ml/work/nwp/{vocab.txt,
web_train.npy, chat_train.npy}``.
"""

import argparse
import json
import math
import os
import re
import subprocess

import numpy as np

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
APP_DICT = os.path.join(ROOT, "app/src/main/assets/ime/dict/data.json")
FW_147 = os.path.join(ROOT, "ml/work/en_147k.txt")
NWP = os.path.join(ROOT, "ml/work/nwp")
DICTS = ("en_US", "en_GB", "en_CA", "en_AU")

# Known junk called out in ml/PLAN.md, plus a few of the same kind.
PROBES = (
    "teh", "recieve", "definately", "untill", "thier", "cna", "igf", "thr", "yoy",
    "dont", "im", "don't", "i'm", "ok", "of", "op", "pout", "put", "colour", "color",
    "favourite", "favorite", "receive", "until", "their", "the",
)

WORD_RE = re.compile(r"[a-z]+(?:'[a-z]+)*")


def load_frequency_words(path):
    counts = {}
    for line in open(path, encoding="utf-8"):
        parts = line.split()
        if len(parts) != 2:
            continue
        word, raw = parts[0].lower().replace("\u2019", "'"), parts[1]
        if WORD_RE.fullmatch(word) is None or not (1 <= len(word) <= 26):
            continue
        try:
            count = int(raw)
        except ValueError:
            continue
        counts[word] = max(counts.get(word, 0), count)
    return counts


def load_hunspell():
    accepted = set()
    for name in DICTS:
        text = subprocess.check_output(["aspell", "-d", name, "dump", "master"], text=True, errors="replace")
        for line in text.splitlines():
            word = line.strip().lower().replace("\u2019", "'")
            if WORD_RE.fullmatch(word) and 1 <= len(word) <= 26:
                accepted.add(word)
    return accepted


def load_lm_counts():
    """Token counts over the app dictionary, from the prepared FineWeb + OASST arrays."""
    vocab = open(os.path.join(NWP, "vocab.txt"), encoding="utf-8").read().splitlines()
    total = np.zeros(len(vocab), dtype=np.int64)
    for name in ("web_train.npy", "chat_train.npy"):
        arr = np.load(os.path.join(NWP, name))
        total += np.bincount(arr, minlength=len(vocab))
    return {word: int(total[i]) for i, word in enumerate(vocab) if i >= 3 and total[i] > 0}


def qwerty_neighbors():
    rows = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]
    centers = {}
    for y, row in enumerate(rows):
        stagger = (0.5, 1.0, 2.0)[y]
        for x, ch in enumerate(row):
            centers[ch] = (x + stagger, float(y))
    neighbors = {c: set() for c in centers}
    for a, (ax, ay) in centers.items():
        for b, (bx, by) in centers.items():
            if a != b and math.hypot(ax - bx, ay - by) <= 1.2:
                neighbors[a].add(b)
    return neighbors


def one_cheap_edits(word, neighbors):
    """Deletes, inserts, adjacent transposes, and neighbour-key substitutions."""
    letters = "abcdefghijklmnopqrstuvwxyz'"
    out = set()
    for i in range(len(word)):
        out.add(word[:i] + word[i + 1:])
        for ch in letters:
            out.add(word[:i] + ch + word[i:])
            if ch != word[i] and (word[i] == "'" or ch == "'" or ch in neighbors.get(word[i], ())):
                out.add(word[:i] + ch + word[i + 1:])
    for ch in letters:
        out.add(word + ch)
    for i in range(len(word) - 1):
        if word[i] != word[i + 1]:
            out.add(word[:i] + word[i + 1] + word[i] + word[i + 2:])
    out.discard(word)
    out.discard("")
    return out


def write_asset(counts, path):
    """Compact `word → count` JSON in the shape of `ime/dict/data.json`."""
    ordered = {w: c for w, c in sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))}
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(ordered, fh, separators=(", ", ": "))
        fh.write("\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--frequency-words", default=FW_147)
    ap.add_argument("--out", default=os.path.join(ROOT, "ml/work/lexicon_candidate.txt"))
    ap.add_argument("--report", default=os.path.join(ROOT, "ml/results/lexicon_report.json"))
    ap.add_argument("--ratio", type=float, default=100.0,
                    help="drop a word this many times rarer than a one-edit neighbour")
    ap.add_argument("--attest-min", type=int, default=5, help="training-corpus count that keeps a near-miss")
    ap.add_argument("--asset", default=None, help="also write a word→count JSON for the app dictionary")
    ap.add_argument("--from-candidate", default=None,
                    help="skip the rebuild and write --asset from this word-count file")
    args = ap.parse_args()

    if args.from_candidate:
        counts = {}
        for line in open(args.from_candidate, encoding="utf-8"):
            word, raw = line.split()
            counts[word] = int(raw)
        destination = args.asset or APP_DICT
        write_asset(counts, destination)
        print(f"wrote {destination} ({len(counts)} words)")
        return

    print("loading FrequencyWords, hunspell lists and training counts...", flush=True)
    counts = load_frequency_words(args.frequency_words)
    accepted = load_hunspell()
    lm_counts = load_lm_counts()
    app = {k.lower(): v for k, v in json.load(open(APP_DICT, encoding="utf-8")).items()}
    neighbors = qwerty_neighbors()

    # Put training-corpus counts onto the FrequencyWords scale via shared plain words.
    ratios = []
    for word, lm_count in lm_counts.items():
        fw = counts.get(word)
        if fw and "'" not in word and lm_count >= 1000:
            ratios.append(fw / lm_count)
    ratios.sort()
    scale = ratios[len(ratios) // 2] if ratios else 1.0

    def attested(word):
        return lm_counts.get(word, 0) >= args.attest_min

    spelled = {w: c for w, c in counts.items() if w in accepted or w in ("a", "i")}
    # Real app words Hunspell accepts but FrequencyWords cut off, when the corpus uses them.
    # Contractions live here: FrequencyWords split "don't" into "don" and "'t".
    added_from_app = {}
    for word in app:
        if word in spelled or word not in accepted or not attested(word):
            continue
        if WORD_RE.fullmatch(word) is None:
            continue
        spelled[word] = max(1, round(lm_counts[word] * scale))
        added_from_app[word] = spelled[word]

    dropped = []
    kept_because_attested = []
    for word, count in spelled.items():
        if len(word) < 3 or "'" in word:
            continue
        richer = None
        for edit in one_cheap_edits(word, neighbors):
            other = spelled.get(edit, 0)
            if other >= args.ratio * count and (richer is None or other > richer[1]):
                richer = (edit, other)
        if richer is None:
            continue
        record = {"word": word, "count": count, "near": richer[0], "near_count": richer[1],
                  "lm_count": lm_counts.get(word, 0)}
        if attested(word):
            kept_because_attested.append(record)
        else:
            dropped.append(record)

    dropped_words = {r["word"] for r in dropped}
    candidate = {w: c for w, c in spelled.items() if w not in dropped_words}

    app_alpha = {w: c for w, c in app.items() if WORD_RE.fullmatch(w)}
    app_junk = sorted(w for w in app_alpha if w not in accepted and w not in ("a", "i"))
    app_real_missing = sorted(w for w in app_alpha if w in accepted and w not in candidate)
    candidate_not_in_app = sorted(w for w in candidate if w not in app)

    def show(rows, n=25):
        rows = sorted(rows, key=lambda r: r["near_count"] / max(r["count"], 1), reverse=True)
        return rows[:n]

    report = {
        "frequency_words": len(counts),
        "hunspell_union": len(accepted),
        "spellchecked_pool": len(spelled),
        "dropped_unattested_near_miss": len(dropped),
        "kept_attested_near_miss": len(kept_because_attested),
        "candidate": len(candidate),
        "candidate_with_apostrophe": sum("'" in w for w in candidate),
        "app_dictionary": len(app),
        "app_not_in_hunspell": len(app_junk),
        "app_real_words_missing_from_candidate": len(app_real_missing),
        "candidate_words_not_in_app": len(candidate_not_in_app),
        "lm_words_attested": sum(1 for w in lm_counts if lm_counts[w] >= args.attest_min),
        "lm_scale_fw_over_lm": round(scale, 4),
        "added_from_app": len(added_from_app),
        "added_contractions": sum("'" in w for w in added_from_app),
        "probes": {
            w: {
                "app_freq": app.get(w),
                "fw_count": counts.get(w),
                "lm_count": lm_counts.get(w, 0),
                "hunspell": w in accepted or w in ("a", "i"),
                "candidate": candidate.get(w),
            }
            for w in PROBES
        },
        "dropped_sample": show(dropped),
        "attested_near_miss_sample": show(kept_because_attested),
        "app_junk_sample": app_junk[:40],
        "app_real_missing_sample": app_real_missing[:40],
        "count_quantiles": quantile_summary(candidate),
    }

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as fh:
        for word, count in sorted(candidate.items(), key=lambda kv: (-kv[1], kv[0])):
            fh.write(f"{word} {count}\n")
    with open(args.report, "w", encoding="utf-8") as fh:
        json.dump(report, fh, indent=2)
        fh.write("\n")

    print(f"frequency words: {report['frequency_words']}")
    print(f"spellchecked pool: {report['spellchecked_pool']}")
    print(f"LM scale (FW/corpus, median): {report['lm_scale_fw_over_lm']}")
    print(f"added from the app dict because the corpus attests them: {report['added_from_app']} "
          f"({report['added_contractions']} with an apostrophe)")
    print(f"dropped near-miss (corpus count < {args.attest_min}): {report['dropped_unattested_near_miss']}")
    print(f"kept near-miss (corpus attests them):                 {report['kept_attested_near_miss']}")
    print(f"candidate: {report['candidate']}  apostrophe forms: {report['candidate_with_apostrophe']}")
    print(f"app dict {report['app_dictionary']}: {report['app_not_in_hunspell']} fail hunspell, "
          f"{report['app_real_words_missing_from_candidate']} real words absent from candidate, "
          f"{report['candidate_words_not_in_app']} candidate words new to the app")
    print("probes:")
    for word, info in report["probes"].items():
        print(f"  {word:12} app={info['app_freq']!s:>5} fw={info['fw_count']!s:>10} "
              f"lm={info['lm_count']:8} spell={info['hunspell']!s:5} candidate={info['candidate']!s:>10}")
    print(f"wrote {args.out}")
    print(f"wrote {args.report}")
    if args.asset:
        write_asset(candidate, args.asset)
        print(f"wrote {args.asset}")


def quantile_summary(counts):
    values = sorted(counts.values())
    if not values:
        return {}

    def at(q):
        return values[min(len(values) - 1, int(q * (len(values) - 1)))]

    return {
        "min": values[0],
        "p10": at(0.10),
        "p50": at(0.50),
        "p90": at(0.90),
        "max": values[-1],
        "log1p_min": round(math.log1p(values[0]), 3),
        "log1p_max": round(math.log1p(values[-1]), 3),
    }


if __name__ == "__main__":
    main()
