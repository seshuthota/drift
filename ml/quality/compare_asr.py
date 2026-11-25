"""Print AsrModelComparisonTest results: per model timing and memory, then each clip's transcripts side by side.

    python ml/quality/compare_asr.py ml/quality/data/asr-compare/results.json

When `refs.json` sits next to the results ({"<clip>.wav": "what was actually said", ...}), the word error rate of each
model and pass is printed over the clips that have a reference. For the others, judge which line matches what was
said (play the clip from ml/quality/data/diagnostics/voice/ if needed).
"""

import collections
import json
import os
import re
import sys


def words(text):
    return re.sub(r"[^a-z0-9' ]", " ", text.lower().replace("-", " ")).split()


def edit_distance(ref, hyp):
    row = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        diag, row[0] = row[0], i
        for j, h in enumerate(hyp, 1):
            diag, row[j] = row[j], min(row[j] + 1, row[j - 1] + 1, diag + (r != h))
    return row[-1]


def one_pass_models(rows):
    """Models with no live pass (Parakeet / Fluid Speech Pro): their streamed text is always empty."""
    return {m for m in {r["model"] for r in rows} if all(r["stream_ms"] == 0 for r in rows if r["model"] == m)}


def wer_report(rows, refs):
    one_pass_only = one_pass_models(rows)
    totals = collections.defaultdict(lambda: [0, 0])
    for r in rows:
        if r["clip"] not in refs:
            continue
        ref = words(refs[r["clip"]])
        for key in ("streamed", "one_pass"):
            if key == "streamed" and r["model"] in one_pass_only:
                continue
            total = totals[(r["model"], key)]
            total[0] += edit_distance(ref, words(r[key]))
            total[1] += len(ref)
    if not totals:
        return
    clips = len({r["clip"] for r in rows if r["clip"] in refs})
    print(f"Word error rate over {clips} clips with a reference:")
    for (model, key), (errors, count) in totals.items():
        print(f"  {model:32s} {key:9s} {errors / count:6.1%}  ({errors}/{count} words)")
    print()


def main(path):
    with open(path, encoding="utf-8") as handle:
        rows = json.load(handle)
    models = list(dict.fromkeys(r["model"] for r in rows))
    one_pass_only = one_pass_models(rows)
    print(f"{len(rows)} runs, models: {', '.join(models)}\n")
    print(f"{'model':32s} {'load':>7s} {'stream RTF':>10s} {'one-pass RTF':>12s} {'native MB':>9s} {'PSS MB':>7s}")
    for model in models:
        group = [r for r in rows if r["model"] == model]
        audio = sum(r["audio_ms"] for r in group) or 1
        stream_rtf = "-" if model in one_pass_only else f"{sum(r['stream_ms'] for r in group) / audio:.2f}"
        print(f"{model:32s} {group[0]['load_ms']:6d}ms {stream_rtf:>10s} "
              f"{sum(r['one_pass_ms'] for r in group) / audio:12.2f} {max(r['native_mb'] for r in group):9.0f} "
              f"{max(r.get('pss_mb', 0) for r in group):7.0f}")
    print("\nRTF = processing time / audio time (below 1 keeps up with speech).\n")
    refs_path = os.path.join(os.path.dirname(path), "refs.json")
    if os.path.exists(refs_path):
        with open(refs_path, encoding="utf-8") as handle:
            wer_report(rows, json.load(handle))
    by_clip = collections.defaultdict(dict)
    for r in rows:
        by_clip[r["clip"]][r["model"]] = r
    differ = 0
    for clip, runs in by_clip.items():
        texts = {(r["streamed"], r["one_pass"]) for r in runs.values()}
        if len(texts) > 1:
            differ += 1
        print(f"{clip} ({next(iter(runs.values()))['audio_ms'] / 1000:.1f}s){'' if len(texts) > 1 else '  [all agree]'}")
        for model, r in runs.items():
            short = model.replace("moonshine-", "").replace("-streaming-en", "")
            if model in one_pass_only:
                print(f"  {short:7s} 1pass: {r['one_pass']}")
                continue
            print(f"  {short:7s} live: {r['streamed']}")
            if r["one_pass"] != r["streamed"]:
                print(f"  {short:7s} 1pass: {r['one_pass']}")
    print(f"\n{differ} of {len(by_clip)} clips differ between models or passes.")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "ml/quality/data/asr-compare/results.json")
