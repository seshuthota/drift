"""Summarise Drift's on-device typing quality log.

Pull the log from the phone (Developer options > Typing quality log must be on):

    adb pull /sdcard/Android/data/io.github.seshuthota.drift/files/diagnostics ml/quality/data

then run

    python ml/quality/analyze.py ml/quality/data/diagnostics

The data folder is git-ignored: it holds the words you typed. Event format: `QualityLog.kt`.
"""

import argparse
import collections
import difflib
import glob
import json
import os
import statistics
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
DICT = os.path.join(ROOT, "app/src/main/assets/ime/dict/data.json")


def load(folder):
    events = []
    for name in ("quality.1.jsonl", "quality.jsonl"):
        path = os.path.join(folder, name)
        if not os.path.exists(path):
            continue
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if line:
                    try:
                        events.append(json.loads(line))
                    except json.JSONDecodeError:
                        pass
    events.sort(key=lambda e: e.get("t", 0))
    return events


def pct(part, whole):
    return f"{100.0 * part / whole:5.1f}%" if whole else "   n/a"


def glide_report(events, lexicon):
    glides = {e["id"]: e for e in events if e["e"] == "glide"}
    picks = collections.defaultdict(list)
    deleted = set()
    retyped = {}
    for e in events:
        if e["e"] == "glide_pick":
            picks[e["id"]].append(e)
        elif e["e"] == "glide_del":
            deleted.add(e["id"])
        elif e["e"] == "word" and "after_del" in e:
            retyped.setdefault(e["after_del"], e)

    print(f"\n== Glide ({len(glides)} committed) ==")
    if not glides:
        return
    kept = picked = deleted_n = 0
    intended_rank = collections.Counter()
    oov = 0
    by_len = collections.defaultdict(lambda: [0, 0])
    failures = []
    for gid, g in glides.items():
        words = [w.lower() for w in g.get("words", [])]
        top = words[0] if words else ""
        intended = None
        if gid in picks:
            picked += 1
            intended = picks[gid][-1]["w"].lower()
        elif gid in deleted:
            deleted_n += 1
            follow = retyped.get(gid)
            if follow is not None:
                intended = follow["w"].lower()
                if intended == top:
                    # Only the auto space was removed (punctuation followed): the glide was right.
                    deleted_n -= 1
                    kept += 1
                    intended = top
        else:
            kept += 1
            intended = top
        if intended is None:
            continue
        rank = words.index(intended) if intended in words else -1
        intended_rank[rank] += 1
        bucket = min(len(intended), 6)
        by_len[bucket][1] += 1
        if rank == 0:
            by_len[bucket][0] += 1
        else:
            if intended.replace("'", "") not in lexicon and intended not in lexicon:
                oov += 1
            failures.append((intended, words[:3]))

    n = len(glides)
    print(f"kept as committed     {pct(kept, n)}   (best estimate of real top-1)")
    print(f"fixed from the row    {pct(picked, n)}")
    print(f"deleted               {pct(deleted_n, n)}")
    known = sum(intended_rank.values())
    print(f"intended word known for {known} glides:")
    for label, ranks in (("top-1", [0]), ("top-3", [0, 1, 2]), ("top-8", list(range(8)))):
        print(f"  {label:6s} {pct(sum(intended_rank[r] for r in ranks), known)}")
    print(f"  not in candidates {pct(intended_rank[-1], known)}; of all misses, out of dictionary: {oov}")
    print("top-1 by intended length:", ", ".join(
        f"{'6+' if k == 6 else k}: {pct(v[0], v[1]).strip()} of {v[1]}" for k, v in sorted(by_len.items())))
    ms = [g["ms"] for g in glides.values() if "ms" in g]
    if ms:
        print(f"decode ms p50 {statistics.median(ms):.0f}, max {max(ms)}")
    if failures:
        print("sample misses (intended -> top 3):")
        for intended, top3 in failures[:25]:
            print(f"  {intended:15s} -> {top3}")


def words_report(events):
    words = [e for e in events if e["e"] == "word"]
    print(f"\n== Words ({len(words)} finished) ==")
    if not words:
        return
    how = collections.Counter(e["how"] for e in words)
    print("how:", ", ".join(f"{k} {pct(v, len(words)).strip()}" for k, v in how.most_common()))
    with_pred = [e for e in words if e.get("pred")]
    hit1 = sum(1 for e in with_pred if e["w"].lower() == e["pred"][0].lower())
    hit3 = sum(1 for e in with_pred if e["w"].lower() in [p.lower() for p in e["pred"][:3]])
    print(f"next-word row was showing for {len(with_pred)} words: "
          f"right word in slot 1 {pct(hit1, len(with_pred))}, in the row {pct(hit3, len(with_pred))}")
    picks = [e for e in words if e["how"] == "pick"]
    from_prediction = sum(1 for e in picks if not e.get("prefix"))
    print(f"picked from the row: {len(picks)} ({from_prediction} with nothing typed yet)")
    prefix_len = collections.Counter(len(e.get("prefix", "")) for e in picks)
    if picks:
        print("  letters typed before a pick:", dict(sorted(prefix_len.items())))
        print("  slot picked:", dict(sorted(collections.Counter(e.get("rank", -1) for e in picks).items())))
    corrections = how.get("autocorrect", 0)
    undos = sum(1 for e in events if e["e"] == "autocorrect_undo")
    print(f"autocorrections {corrections}, undone {undos} ({pct(undos, corrections).strip()})")
    for e in [e for e in events if e["e"] == "autocorrect_undo"][:15]:
        print(f"  undone: {e['orig']} -> {e['corrected']}")


def voice_report(events, folder):
    sessions = [e for e in events if e["e"] == "voice"]
    print(f"\n== Voice ({len(sessions)} dictations) ==")
    if not sessions:
        return
    edits = [e for e in events if e["e"] == "voice_bs"]
    empty = [s for s in sessions if not any(line.strip() for line in s.get("lines", []))]
    ends = collections.Counter(s.get("end", "?").split(":")[0] for s in sessions)
    print("ended by:", dict(ends))
    print(f"no text at all        {pct(len(empty), len(sessions))}")
    print(f"backspaces within 20 s of a dictation: {len(edits)} "
          f"({len(edits) / len(sessions):.1f} per dictation)")
    for source in sorted({s.get("src", "?") for s in sessions}):
        group = [s for s in sessions if s.get("src", "?") == source]
        peaks = [p for s in group for p in s.get("peak_db", [])]
        gains = [g for s in group for g in s.get("gain", [])]
        words = sum(len(" ".join(s.get("lines", [])).split()) for s in group)
        seconds = sum(s.get("ms", 0) for s in group) / 1000.0
        print(f"[{source}] {len(group)} dictations, {seconds:.0f} s, {words} words "
              f"({60.0 * words / seconds if seconds else 0:.0f} wpm)")
        if peaks:
            loud = sorted(peaks)
            print(f"  per-second peak dBFS: p10 {loud[len(loud) // 10]:.1f}, median {statistics.median(loud):.1f}, "
                  f"p90 {loud[9 * len(loud) // 10]:.1f}")
        if gains:
            print(f"  gain: median {statistics.median(gains):.1f}x, max {max(gains):.1f}x")
    clips = sorted(glob.glob(os.path.join(folder, "voice", "*.wav")))
    print(f"clips kept: {len(clips)}")
    for s in sessions[-10:]:
        text = " / ".join(s.get("lines", []))
        print(f"  {s.get('ms', 0) / 1000:5.1f}s {s.get('end', '?'):18s} {text[:90]}")
    refine_report(sessions, edits)


def word_diff(old, new):
    """The words that changed between two transcripts, as `old -> new` pieces."""
    a, b = old.split(), new.split()
    pieces = []
    for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(a=a, b=b, autojunk=False).get_opcodes():
        if tag != "equal":
            pieces.append(f"[{' '.join(a[i1:i2]) or '-'} -> {' '.join(b[j1:j2]) or '-'}]")
    return " ".join(pieces)


def refine_report(sessions, edits):
    """Streaming vs. the one-pass decode at the end (Settings > Voice input > Refine after speaking)."""
    runs = [s for s in sessions if "refine_result" in s]
    print(f"\n== Voice refine ({len(runs)} of {len(sessions)} dictations) ==")
    if not runs:
        return
    print("result:", dict(collections.Counter(s["refine_result"] for s in runs)))
    times = sorted(s["refine_ms"] for s in runs if "refine_ms" in s)
    if times:
        print(f"one-pass time: median {statistics.median(times):.0f} ms, p90 {times[9 * len(times) // 10]:.0f} ms, "
              f"max {times[-1]} ms")
    # refine_model is logged since 2026-11-25; older runs were decoded by the live model or an unrecorded finisher.
    by_model = collections.defaultdict(list)
    for s in runs:
        by_model[s.get("refine_model", "(not logged)")].append(s)
    for model, group in sorted(by_model.items()):
        model_times = sorted(s["refine_ms"] for s in group if "refine_ms" in s)
        median = f", median {statistics.median(model_times):.0f} ms" if model_times else ""
        print(f"  by {model}: {len(group)} runs{median}, "
              f"{dict(collections.Counter(s['refine_result'] for s in group))}")
    with_context = [s for s in sessions if s.get("ctx_chars", 0) > 0]
    print(f"dictations with field context: {pct(len(with_context), len(sessions))}")
    # Backspaces after a dictation belong to the latest dictation logged before them.
    times_logged = sorted((s["t"], i) for i, s in enumerate(sessions))
    fixes = collections.Counter()
    for e in edits:
        owner = None
        for t, i in times_logged:
            if t <= e["t"]:
                owner = i
            else:
                break
        if owner is not None:
            fixes[owner] += 1
    by_result = collections.defaultdict(list)
    for i, s in enumerate(sessions):
        if "refine_result" in s:
            by_result[s["refine_result"]].append(fixes[i])
    for result, counts in sorted(by_result.items()):
        print(f"  {result:13s} {len(counts):4d} dictations, {statistics.mean(counts):.1f} backspaces after each")
    differing = [s for s in runs if s["refine_result"] not in ("same", "empty", "error", "too_long", "no_text", "superseded")]
    print(f"differences ({len(differing)}), latest first:")
    for s in differing[::-1][:20]:
        streamed = " ".join(s.get("lines", []))
        print(f"  {s['refine_result']:13s} {word_diff(streamed, s.get('refined', ''))[:110]}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("folder", help="the pulled diagnostics folder")
    args = parser.parse_args()
    events = load(args.folder)
    if not events:
        sys.exit(f"no events in {args.folder}")
    with open(DICT, encoding="utf-8") as handle:
        lexicon = set(json.load(handle))
    span_h = (events[-1]["t"] - events[0]["t"]) / 3_600_000
    print(f"{len(events)} events over {span_h:.1f} h")
    glide_report(events, lexicon)
    words_report(events)
    voice_report(events, args.folder)


if __name__ == "__main__":
    main()
