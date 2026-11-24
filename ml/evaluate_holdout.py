"""Held-out evaluation of the saved model (no SHAP; metrics + latency only).

Re-creates the exact stratified 80/20 split used in train.py (random_state=42)
and scores only the test portion with backend/model.joblib.
"""
import json
import time
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from sklearn.metrics import average_precision_score, roc_auc_score
from sklearn.model_selection import train_test_split

from features import FEATURE_NAMES, _parse, features_vector

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data" / "features.parquet"
BUNDLE = ROOT / "backend" / "model.joblib"
ALLOWLIST = ROOT / "backend" / "allowlist.txt"
TRAIN_METRICS = ROOT / "docs" / "metrics.json"
OUT = ROOT / "docs" / "holdout_metrics.json"
LATENCY_SAMPLE = 2000


def point_metrics(y, proba, thr):
    pred = proba >= thr
    tp = int((pred & (y == 1)).sum())
    fp = int((pred & (y == 0)).sum())
    fn = int((~pred & (y == 1)).sum())
    tn = int((~pred & (y == 0)).sum())
    prec = tp / (tp + fp) if tp + fp else 0.0
    rec = tp / (tp + fn) if tp + fn else 0.0
    return {
        "threshold": float(thr),
        "accuracy": (tp + tn) / len(y),
        "precision": prec, "recall": rec,
        "f1": 2 * prec * rec / (prec + rec) if prec + rec else 0.0,
        "fpr": fp / (fp + tn) if fp + tn else 0.0,
        "confusion": {"tn": tn, "fp": fp, "fn": fn, "tp": tp},
    }


def load_allowlist():
    out = set()
    for line in ALLOWLIST.read_text(encoding="utf-8").splitlines():
        d = line.split("#", 1)[0].strip().lower()
        if d:
            out.add(d)
    return out


def host_listed(host, domains):
    parts = host.split(".")
    return any(".".join(parts[i:]) in domains for i in range(len(parts)))


def main():
    bundle = joblib.load(BUNDLE)
    model, balanced = bundle["model"], float(bundle["threshold"])
    assert bundle["features"] == FEATURE_NAMES, "feature order mismatch"

    df = pd.read_parquet(DATA)
    _, test = train_test_split(df, test_size=0.2, stratify=df["label"], random_state=42)
    y = test["label"].to_numpy()
    proba = model.predict_proba(test[FEATURE_NAMES])[:, 1]

    # conservative threshold = the one train.py selected (lowest FPR <= 1%)
    conservative = json.loads(TRAIN_METRICS.read_text())["operating_points"]["conservative"]["threshold"]

    points = {"balanced": point_metrics(y, proba, balanced),
              "conservative": point_metrics(y, proba, conservative)}

    sweep = []
    for t in np.round(np.arange(0.30, 0.9501, 0.05), 2):
        m = point_metrics(y, proba, float(t))
        m["flagged_share"] = float((proba >= t).mean())
        sweep.append(m)

    # allowlist short-circuit rate on the test set
    allow = load_allowlist()
    hosts = [_parse(u)[0] for u in test["url"]]
    listed = np.array([bool(h) and host_listed(h, allow) for h in hosts])
    allowlist_stats = {
        "entries": len(allow),
        "short_circuit_rate": float(listed.mean()),
        "short_circuited": int(listed.sum()),
        "of_which_good": int((listed & (y == 0)).sum()),
        "of_which_bad": int((listed & (y == 1)).sum()),
    }

    # per-URL latency: feature extraction + predict (as /check does, without SHAP)
    sample = test["url"].sample(n=LATENCY_SAMPLE, random_state=0).tolist()
    model.predict_proba(pd.DataFrame([features_vector(sample[0])], columns=FEATURE_NAMES))  # warm-up
    lat = []
    for u in sample:
        t0 = time.perf_counter()
        row = pd.DataFrame([features_vector(u)], columns=FEATURE_NAMES)
        model.predict_proba(row)
        lat.append((time.perf_counter() - t0) * 1000)
    latency = {"n": len(lat), "median_ms": float(np.median(lat)),
               "p95_ms": float(np.percentile(lat, 95))}

    result = {
        "split": {"n_test": int(len(y)), "positive_rate": float(y.mean()),
                  "note": "same split as train.py; thresholds were also chosen on this "
                          "split, so threshold-dependent numbers are slightly optimistic"},
        "roc_auc": float(roc_auc_score(y, proba)),
        "pr_auc": float(average_precision_score(y, proba)),
        "operating_points": points,
        "threshold_sweep": sweep,
        "allowlist": allowlist_stats,
        "latency": latency,
    }
    OUT.parent.mkdir(exist_ok=True)
    OUT.write_text(json.dumps(result, indent=2))

    print(f"test rows={len(y)}  positive rate={y.mean():.3f}  "
          f"ROC-AUC={result['roc_auc']:.4f}  PR-AUC={result['pr_auc']:.4f}\n")
    print(f"{'point':13s} {'thr':>6s} {'acc':>7s} {'prec':>7s} {'rec':>7s} {'f1':>7s} {'fpr':>8s}  confusion")
    for n, m in points.items():
        c = m["confusion"]
        print(f"{n:13s} {m['threshold']:6.3f} {m['accuracy']:7.4f} {m['precision']:7.4f} "
              f"{m['recall']:7.4f} {m['f1']:7.4f} {m['fpr']:8.5f}  "
              f"TN={c['tn']} FP={c['fp']} FN={c['fn']} TP={c['tp']}")
    print("\nthreshold sweep:")
    print(f"{'thr':>5s} {'prec':>7s} {'recall':>7s} {'fpr':>8s} {'f1':>7s} {'flagged':>8s}")
    for m in sweep:
        print(f"{m['threshold']:5.2f} {m['precision']:7.4f} {m['recall']:7.4f} "
              f"{m['fpr']:8.5f} {m['f1']:7.4f} {m['flagged_share']:8.3f}")
    a = allowlist_stats
    print(f"\nallowlist ({a['entries']} domains) would short-circuit {a['short_circuit_rate']:.2%} "
          f"of the test set ({a['short_circuited']} URLs: {a['of_which_good']} good, "
          f"{a['of_which_bad']} bad)")
    print(f"latency per URL (features+predict, n={latency['n']}): "
          f"median {latency['median_ms']:.2f} ms, p95 {latency['p95_ms']:.2f} ms")
    print(f"\nwrote {OUT}")


if __name__ == "__main__":
    main()
