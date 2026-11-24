"""Train the LightGBM phishing-URL model and write metrics + joblib bundle."""
import json
from pathlib import Path

import joblib
import lightgbm as lgb
import numpy as np
import pandas as pd
import shap
from sklearn.metrics import (average_precision_score, precision_recall_curve,
                             roc_auc_score, roc_curve)
from sklearn.model_selection import train_test_split

from features import FEATURE_NAMES

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data" / "features.parquet"
MODEL_OUT = ROOT / "backend" / "model.joblib"
METRICS_OUT = ROOT / "docs" / "metrics.json"


def point_metrics(y, proba, thr):
    pred = proba >= thr
    tp = int((pred & (y == 1)).sum())
    fp = int((pred & (y == 0)).sum())
    fn = int((~pred & (y == 1)).sum())
    tn = int((~pred & (y == 0)).sum())
    prec = tp / (tp + fp) if tp + fp else 0.0
    rec = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * prec * rec / (prec + rec) if prec + rec else 0.0
    fpr = fp / (fp + tn) if fp + tn else 0.0
    return {"threshold": float(thr), "precision": prec, "recall": rec, "f1": f1,
            "fpr": fpr, "confusion": {"tn": tn, "fp": fp, "fn": fn, "tp": tp}}


def pick_thresholds(y, proba):
    # conservative: lowest FPR <= 1% while maximizing recall
    fpr, tpr, roc_thr = roc_curve(y, proba)
    ok = np.where(fpr <= 0.01)[0]
    i = ok[np.argmax(tpr[ok])]  # tpr is non-decreasing, so this is the last ok point
    conservative = roc_thr[i]

    prec, rec, pr_thr = precision_recall_curve(y, proba)
    prec, rec = prec[:-1], rec[:-1]  # align with pr_thr
    f1 = 2 * prec * rec / np.clip(prec + rec, 1e-12, None)
    balanced = pr_thr[np.argmax(f1)]

    ok = np.where(rec >= 0.90)[0]
    sensitive = pr_thr[ok[np.argmax(prec[ok])]]
    return {"conservative": float(conservative), "balanced": float(balanced),
            "sensitive": float(sensitive)}


def main():
    df = pd.read_parquet(DATA)
    X, y = df[FEATURE_NAMES], df["label"].to_numpy()
    X_tr, X_te, y_tr, y_te = train_test_split(
        X, y, test_size=0.2, stratify=y, random_state=42)

    n_pos, n_neg = int(y_tr.sum()), int((y_tr == 0).sum())
    model = lgb.LGBMClassifier(
        n_estimators=500, learning_rate=0.05, num_leaves=64, subsample=0.9,
        subsample_freq=1,  # LightGBM ignores `subsample` unless bagging_freq > 0
        colsample_bytree=0.9, scale_pos_weight=n_neg / n_pos,
        importance_type="gain", random_state=42, n_jobs=-1, verbose=-1)
    model.fit(X_tr, y_tr)

    proba = model.predict_proba(X_te)[:, 1]
    roc_auc = float(roc_auc_score(y_te, proba))
    pr_auc = float(average_precision_score(y_te, proba))

    thr = pick_thresholds(y_te, proba)
    points = {name: point_metrics(y_te, proba, t) for name, t in thr.items()}

    imp = pd.Series(model.booster_.feature_importance("gain"), index=FEATURE_NAMES)
    imp = (imp / imp.sum()).sort_values(ascending=False)
    importances = [{"feature": k, "gain_share": float(v)} for k, v in imp.items()]

    sample = X_te.sample(n=3000, random_state=42)
    sv = shap.TreeExplainer(model).shap_values(sample)
    if isinstance(sv, list):  # older shap: [neg, pos]
        sv = sv[1]
    sv = np.asarray(sv)
    mean_abs = pd.Series(np.abs(sv).mean(axis=0), index=FEATURE_NAMES)
    shap_top = [{"feature": k, "mean_abs_shap": float(v)}
                for k, v in mean_abs.sort_values(ascending=False).head(12).items()]

    metrics = {
        "n_train": len(X_tr), "n_test": len(X_te),
        "test_positive_rate": float(y_te.mean()),
        "roc_auc": roc_auc, "pr_auc": pr_auc,
        "operating_points": points,
        "feature_importance_gain": importances,
        "shap_top12": shap_top,
    }

    MODEL_OUT.parent.mkdir(exist_ok=True)
    METRICS_OUT.parent.mkdir(exist_ok=True)
    joblib.dump({"model": model, "threshold": thr["balanced"],
                 "features": FEATURE_NAMES}, MODEL_OUT)
    METRICS_OUT.write_text(json.dumps(metrics, indent=2))

    print(f"train={len(X_tr)} test={len(X_te)}  ROC-AUC={roc_auc:.4f}  PR-AUC={pr_auc:.4f}")
    print(f"\n{'point':13s} {'thr':>7s} {'prec':>7s} {'recall':>7s} {'f1':>7s} {'fpr':>8s}  confusion")
    for name, m in points.items():
        c = m["confusion"]
        print(f"{name:13s} {m['threshold']:7.4f} {m['precision']:7.4f} {m['recall']:7.4f} "
              f"{m['f1']:7.4f} {m['fpr']:8.5f}  TN={c['tn']} FP={c['fp']} FN={c['fn']} TP={c['tp']}")
    print("\ngain importance (share of total):")
    for d in importances:
        print(f"  {d['feature']:22s} {d['gain_share']:.4f}")
    print("\nSHAP top-12 (mean |SHAP|, 3000-row test sample):")
    for d in shap_top:
        print(f"  {d['feature']:22s} {d['mean_abs_shap']:.4f}")
    print(f"\nwrote {MODEL_OUT}\nwrote {METRICS_OUT}")


if __name__ == "__main__":
    main()
