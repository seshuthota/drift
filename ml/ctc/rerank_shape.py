"""Dev-only feasibility test for a linear shape reranker.

Protocol from the 2026-11-11 Opus 5.5 review. Candidate pool is the acoustic
beam's first 16 finished words (alpha 0, same order ``ctc_beam`` returns).
The shipping score is not used to pick that pool.

Ceiling: fraction of dev rows whose target is in that pool. Fitting starts
only when this oracle top-1 is at least 0.5 pt above the full-beam shipping
decoder (k=0.4, endpoint weight 20). The sealed set is not read.

Features, per candidate: acoustic score, log1p(count), length, squared
distance of the stroke start to the first letter, squared distance of the
stroke end to the last letter, and DTW between the 64-point stroke and the
key-center polyline. No swipe-negatives feature and no context feature.

The ranker is pairwise logistic loss with L2 strength 1 on z-scored
features, fit by L-BFGS. 5-fold GroupKFold keeps every row of a target word
in one fold. Dev gates, all required: +0.5 pt top-1 over the full-beam
shipping decoder on these same rows, length 2 at or above 98.34%, length 3
at or above that decoder's length-3 top-1. Two extra fits are reported and
do not change the gate: the same model at L2 1e-4, and a single DTW
coefficient added to the frozen shipping score.
"""

import argparse
import json
import math
import os
import pickle

import numba
import numpy as np
from scipy.optimize import minimize
from scipy.stats import binomtest
from sklearn.model_selection import GroupKFold

from eval_app_dict import DEFAULT_DICT, load_usable, log_freq

FEATURE_NAMES = (
    "acoustic",
    "log_freq",
    "length",
    "start_sq",
    "end_sq",
    "dtw",
)
REG = 1.0
POOL = 16
LEN2_FLOOR = 0.9834


@numba.njit
def dtw_mean(stroke, poly):
    """Euclidean DTW divided by (len(stroke) + len(poly)). Identical paths score 0."""
    n = stroke.shape[0]
    m = poly.shape[0]
    inf = 1.0e18
    prev = np.empty(m + 1)
    curr = np.empty(m + 1)
    for j in range(m + 1):
        prev[j] = inf
    prev[0] = 0.0
    for i in range(1, n + 1):
        curr[0] = inf
        sx = stroke[i - 1, 0]
        sy = stroke[i - 1, 1]
        for j in range(1, m + 1):
            dx = sx - poly[j - 1, 0]
            dy = sy - poly[j - 1, 1]
            cost = math.sqrt(dx * dx + dy * dy)
            best = prev[j]
            if curr[j - 1] < best:
                best = curr[j - 1]
            if prev[j - 1] < best:
                best = prev[j - 1]
            curr[j] = cost + best
        for j in range(m + 1):
            prev[j] = curr[j]
    return prev[m] / (n + m)


def check_dtw():
    line = np.stack((np.linspace(0.0, 1.0, 8), np.zeros(8)), axis=1)
    same = dtw_mean(line, line)
    shifted = line.copy()
    shifted[:, 1] += 0.25
    moved = dtw_mean(line, shifted)
    if not (same < 1.0e-9 and moved > 0.05):
        raise RuntimeError(f"DTW self-check failed: same={same} shifted={moved}")


def loss_and_grad(weights, diffs, reg):
    margins = diffs @ weights
    # log(1 + exp(-m)); grad w.r.t. m is -1 / (1 + exp(m)).
    loss = float(np.mean(np.logaddexp(0.0, -margins))) + 0.5 * reg * float(weights @ weights)
    per = -np.exp(-np.logaddexp(0.0, margins))
    grad = (diffs.T @ per) / diffs.shape[0] + reg * weights
    return loss, grad


def check_grad():
    rng = np.random.default_rng(0)
    diffs = rng.normal(size=(30, len(FEATURE_NAMES)))
    weights = rng.normal(size=len(FEATURE_NAMES))
    _, analytic = loss_and_grad(weights, diffs, 0.01)
    base, _ = loss_and_grad(weights, diffs, 0.01)
    numeric = np.zeros_like(weights)
    step = 1.0e-6
    for i in range(weights.shape[0]):
        moved = weights.copy()
        moved[i] += step
        bumped, _ = loss_and_grad(moved, diffs, 0.01)
        numeric[i] = (bumped - base) / step
    err = float(np.max(np.abs(numeric - analytic)))
    if err > 1.0e-5:
        raise RuntimeError(f"gradient check failed: {err}")


def letter_centers(feats, words):
    buckets = {ch: [] for ch in "abcdefghijklmnopqrstuvwxyz"}
    for feat, word in zip(feats, words):
        if word.isalpha() and word:
            buckets[word[0]].append((float(feat[0, 0]), float(feat[1, 0])))
            buckets[word[-1]].append((float(feat[0, -1]), float(feat[1, -1])))
    shipping = {}
    shape = {}
    thin = {}
    for char, pts in buckets.items():
        thin[char] = len(pts)
        if not pts:
            continue
        xs, ys = zip(*pts)
        center = (float(np.median(xs)), float(np.median(ys)))
        shape[char] = center
        if len(pts) >= 30:
            shipping[char] = center
    missing = [ch for ch in buckets if ch not in shape]
    if missing:
        raise RuntimeError(f"no endpoint samples for {''.join(missing)}")
    return shipping, shape, thin


def endpoint_sq(word, feat, centers):
    if not word or word[0] not in centers or word[-1] not in centers:
        return 0.0, 0.0
    sx, sy = float(feat[0, 0]), float(feat[1, 0])
    ex, ey = float(feat[0, -1]), float(feat[1, -1])
    fx, fy = centers[word[0]]
    lx, ly = centers[word[-1]]
    return (sx - fx) ** 2 + (sy - fy) ** 2, (ex - lx) ** 2 + (ey - ly) ** 2


def polyline(word, shape_centers):
    return np.array([shape_centers[ch] for ch in word], dtype=np.float64)


def rates(hits, words):
    n = len(words)
    out = {"all": float(np.mean(hits)) if n else 0.0}
    for name, pred in (
        ("len=2", lambda w: len(w) == 2),
        ("len=3", lambda w: len(w) == 3),
        ("len>=4", lambda w: len(w) >= 4),
    ):
        mask = np.array([pred(w) for w in words])
        out[name] = float(np.mean(hits[mask])) if mask.any() else None
        out[name + "_n"] = int(mask.sum())
    return out


def fit_weights(diffs, reg):
    result = minimize(
        loss_and_grad,
        np.zeros(diffs.shape[1]),
        args=(diffs, reg),
        method="L-BFGS-B",
        jac=True,
        options={"maxiter": 200},
    )
    if not result.success:
        raise RuntimeError(result.message)
    return result.x


def pair_rows(train_idx, gold_in_pool, pool_words, words, blocks):
    """Difference vectors, winner minus each other candidate, for rows whose target is in the pool."""
    diffs = []
    for i in train_idx:
        if not gold_in_pool[i]:
            continue
        block = blocks(i)
        gold_at = pool_words[i].index(words[i])
        winner = block[gold_at]
        for j in range(block.shape[0]):
            if j != gold_at:
                diffs.append(winner - block[j])
    return np.stack(diffs)


def summarize(pred_hit, baseline_hit, words, baseline):
    rerank = rates(pred_hit, words)
    only_new = int(np.sum(pred_hit & ~baseline_hit))
    only_old = int(np.sum(baseline_hit & ~pred_hit))
    p_value = float(binomtest(only_new, only_new + only_old, 0.5).pvalue) if only_new + only_old else 1.0
    passed = (
        rerank["all"] >= baseline["all"] + 0.005
        and rerank["len=2"] >= LEN2_FLOOR
        and rerank["len=3"] >= baseline["len=3"]
    )
    return {
        "rates": rerank,
        "delta_top1": rerank["all"] - baseline["all"],
        "only_rerank_correct": only_new,
        "only_shipping_correct": only_old,
        "mcnemar_exact_p_dev": p_value,
        "gates": {
            "plus_0_5pt": rerank["all"] >= baseline["all"] + 0.005,
            "len2_at_least_98_34": rerank["len=2"] >= LEN2_FLOOR,
            "len3_not_below_baseline": rerank["len=3"] >= baseline["len=3"],
        },
        "passed": passed,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--npz", default=os.path.join(os.path.dirname(__file__), "..", "work", "dev.npz"))
    ap.add_argument("--beams", default="/tmp/dev_beams_1m.pkl")
    ap.add_argument("--dict", default=DEFAULT_DICT)
    ap.add_argument("--k", type=float, default=0.4)
    ap.add_argument("--endpoint-weight", type=float, default=20.0)
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "..", "results", "rerank_shape_dev.json"))
    args = ap.parse_args()

    check_dtw()
    check_grad()
    usable = load_usable(args.dict)
    data = np.load(args.npz, allow_pickle=True)
    words = [str(w) for w in data["words"]]
    feats = data["feats"].astype(np.float32)
    beams = pickle.load(open(args.beams, "rb"))
    if not (len(words) == len(feats) == len(beams)):
        raise RuntimeError(f"row mismatch words={len(words)} feats={len(feats)} beams={len(beams)}")
    n = len(words)
    shipping_centers, shape_centers, thin = letter_centers(feats, words)
    print("letter endpoint samples:", " ".join(f"{ch}:{thin[ch]}" for ch in "abcdefghijklmnopqrstuvwxyz"))
    print(f"shipping centers (>30)={len(shipping_centers)} shape centers={len(shape_centers)}")

    pool_words = []
    pool_acoustic = []
    gold_in_pool = np.zeros(n, dtype=bool)
    baseline_hit = np.zeros(n, dtype=bool)
    for i, (ranked, word, feat) in enumerate(zip(beams, words, feats)):
        taken = [(cand, score) for cand, score in ranked if cand in usable][:POOL]
        pool_words.append([cand for cand, _ in taken])
        pool_acoustic.append(np.array([score for _, score in taken], dtype=np.float64))
        gold_in_pool[i] = word in pool_words[-1]
        best_word = None
        best_score = -1.0e300
        for cand, acoustic in ranked:
            if cand not in usable:
                continue
            start_sq, end_sq = endpoint_sq(cand, feat, shipping_centers)
            score = acoustic + args.k * log_freq(usable[cand], "log") - args.endpoint_weight * (start_sq + end_sq)
            if score > best_score:
                best_score = score
                best_word = cand
        baseline_hit[i] = best_word == word

    oracle = float(np.mean(gold_in_pool))
    baseline = rates(baseline_hit, words)
    errors = ~baseline_hit
    error_in_pool = float(np.mean(gold_in_pool[errors])) if errors.any() else None
    correct_outside = int(np.sum(baseline_hit & ~gold_in_pool))
    headroom = oracle - baseline["all"]
    print(
        f"rows={n} baseline top1={baseline['all']:.4%} len2={baseline['len=2']:.4%} "
        f"len3={baseline['len=3']:.4%} len>=4={baseline['len>=4']:.4%}"
    )
    print(
        f"acoustic top-{POOL} oracle={oracle:.4%} headroom={headroom * 100:.2f} pt  "
        f"share of baseline errors still in the pool={error_in_pool:.4%}  "
        f"baseline-correct rows outside the pool={correct_outside}"
    )

    report = {
        "npz": os.path.abspath(args.npz),
        "beams": os.path.abspath(args.beams),
        "rows": n,
        "pool": POOL,
        "k": args.k,
        "endpoint_weight": args.endpoint_weight,
        "reg": REG,
        "features": list(FEATURE_NAMES),
        "baseline": baseline,
        "oracle_top16": oracle,
        "headroom": headroom,
        "baseline_errors_with_target_in_pool": error_in_pool,
        "baseline_correct_outside_pool": correct_outside,
        "letter_endpoint_samples": thin,
    }
    if headroom < 0.005:
        report["status"] = "stopped_ceiling"
        report["reason"] = (
            f"acoustic top-{POOL} oracle is {headroom * 100:.2f} pt above the shipping decoder, "
            "under the 0.5 pt needed to justify a fit"
        )
        write_report(args.out, report)
        print("STOP", report["reason"])
        return

    print("ceiling clears 0.5 pt; building features")
    features = np.zeros((n, POOL, len(FEATURE_NAMES)), dtype=np.float64)
    ship = np.zeros((n, POOL), dtype=np.float64)
    pool_n = np.zeros(n, dtype=np.int32)
    for i, (cands, acoustics, feat) in enumerate(zip(pool_words, pool_acoustic, feats)):
        stroke = np.stack((feat[0].astype(np.float64), feat[1].astype(np.float64)), axis=1)
        pool_n[i] = len(cands)
        for j, (cand, acoustic) in enumerate(zip(cands, acoustics)):
            start_sq, end_sq = endpoint_sq(cand, feat, shipping_centers)
            features[i, j, 0] = acoustic
            features[i, j, 1] = log_freq(usable[cand], "log")
            features[i, j, 2] = float(len(cand))
            features[i, j, 3] = start_sq
            features[i, j, 4] = end_sq
            features[i, j, 5] = dtw_mean(stroke, polyline(cand, shape_centers))
            ship[i, j] = (
                acoustic
                + args.k * features[i, j, 1]
                - args.endpoint_weight * (start_sq + end_sq)
            )
    restricted = np.zeros(n, dtype=bool)
    for i in range(n):
        choice = int(np.argmax(ship[i, : pool_n[i]]))
        restricted[i] = pool_words[i][choice] == words[i]
    report["shipping_score_inside_acoustic_top16"] = rates(restricted, words)
    print(
        "shipping score restricted to the acoustic pool "
        f"top1={report['shipping_score_inside_acoustic_top16']['all']:.4%}"
    )

    groups = np.array(words)
    folds = list(GroupKFold(n_splits=5).split(np.zeros(n), groups=groups))

    def cross_validate(score_of, label):
        pred_hit = np.zeros(n, dtype=bool)
        extras = []
        for fold, (train_idx, val_idx) in enumerate(folds):
            weights, scored = score_of(train_idx)
            extras.append(weights)
            for i in val_idx:
                choice = int(np.argmax(scored(i)))
                pred_hit[i] = pool_words[i][choice] == words[i]
            print(f"{label} fold {fold} val_top1={pred_hit[val_idx].mean():.4%}")
        summary = summarize(pred_hit, baseline_hit, words, baseline)
        summary["fold_params"] = extras
        print(
            f"{label} cv top1={summary['rates']['all']:.4%} ({summary['delta_top1'] * 100:+.2f} pt) "
            f"len2={summary['rates']['len=2']:.4%} len3={summary['rates']['len=3']:.4%} "
            f"len>=4={summary['rates']['len>=4']:.4%} "
            f"only new={summary['only_rerank_correct']} only shipping={summary['only_shipping_correct']} "
            f"p={summary['mcnemar_exact_p_dev']:.4g}"
        )
        return summary

    def joint(reg):
        def score_of(train_idx):
            stacked = np.concatenate([features[i, : pool_n[i]] for i in train_idx], axis=0)
            mean = stacked.mean(axis=0)
            std = stacked.std(axis=0)
            std[std < 1.0e-8] = 1.0

            def block(i):
                return (features[i, : pool_n[i]] - mean) / std

            weights = fit_weights(pair_rows(train_idx, gold_in_pool, pool_words, words, block), reg)
            return weights.tolist(), lambda i, weights=weights, block=block: block(i) @ weights

        return score_of

    def added_shape():
        """Keep the shipping score fixed and learn a DTW coefficient on top."""
        def score_of(train_idx):
            dtw_vals = np.concatenate([features[i, : pool_n[i], 5] for i in train_idx])
            center = float(dtw_vals.mean())
            scale = float(dtw_vals.std()) or 1.0

            def columns(i):
                raw = ship[i, : pool_n[i]]
                dtw = (features[i, : pool_n[i], 5] - center) / scale
                return np.stack((raw, dtw), axis=1)

            # Margins are shipping_diff + beta * dtw_diff. Shipping stays at coefficient 1.
            diffs = pair_rows(train_idx, gold_in_pool, pool_words, words, columns)
            ship_diff = diffs[:, 0]
            dtw_diff = diffs[:, 1]

            def one(beta_vec):
                margins = ship_diff + beta_vec[0] * dtw_diff
                loss = float(np.mean(np.logaddexp(0.0, -margins))) + 0.5 * 1.0e-4 * float(beta_vec @ beta_vec)
                per = -np.exp(-np.logaddexp(0.0, margins))
                grad = np.array([float(np.mean(per * dtw_diff)) + 1.0e-4 * beta_vec[0]])
                return loss, grad

            beta = float(minimize(one, np.zeros(1), method="L-BFGS-B", jac=True).x[0])
            return [beta], lambda i, beta=beta: ship[i, : pool_n[i]] + beta * (
                (features[i, : pool_n[i], 5] - center) / scale
            )

        return score_of

    print("fitting pre-registered joint model, L2=1")
    joint_fit = cross_validate(joint(REG), "joint-l2-1")
    print("fitting joint model again with L2=1e-4, to see whether the penalty caused the miss")
    light_fit = cross_validate(joint(1.0e-4), "joint-l2-1e-4")
    print("fitting DTW coefficient on top of the frozen shipping score")
    shape_fit = cross_validate(added_shape(), "ship-plus-dtw")
    passed = joint_fit["passed"]
    report.update(
        {
            "status": "dev_gate_passed" if passed else "dev_gate_failed",
            "rerank_cv": joint_fit["rates"],
            "delta_top1": joint_fit["delta_top1"],
            "only_rerank_correct": joint_fit["only_rerank_correct"],
            "only_shipping_correct": joint_fit["only_shipping_correct"],
            "mcnemar_exact_p_dev": joint_fit["mcnemar_exact_p_dev"],
            "fold_weights_zscored": joint_fit["fold_params"],
            "mean_weights_zscored": np.mean(joint_fit["fold_params"], axis=0).tolist(),
            "gates": joint_fit["gates"],
            "diagnostic_joint_l2_1e-4": {
                "rates": light_fit["rates"],
                "delta_top1": light_fit["delta_top1"],
                "mcnemar_exact_p_dev": light_fit["mcnemar_exact_p_dev"],
                "mean_weights_zscored": np.mean(light_fit["fold_params"], axis=0).tolist(),
                "passed_same_gates": light_fit["passed"],
            },
            "diagnostic_shipping_plus_dtw": {
                "rates": shape_fit["rates"],
                "delta_top1": shape_fit["delta_top1"],
                "mcnemar_exact_p_dev": shape_fit["mcnemar_exact_p_dev"],
                "fold_dtw_beta": shape_fit["fold_params"],
                "passed_same_gates": shape_fit["passed"],
                "note": "Not the pre-registered model. Shipping score stays fixed; only the DTW coefficient is learned.",
            },
        }
    )
    write_report(args.out, report)
    print(f"status={report['status']}")


def write_report(path, report):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(report, handle, indent=2)
        handle.write("\n")
    print("wrote", path)


if __name__ == "__main__":
    main()
