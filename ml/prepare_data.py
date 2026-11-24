"""Featurize data/faizan.csv -> data/features.parquet."""
from pathlib import Path

import pandas as pd

from features import FEATURE_NAMES, features_vector

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "data" / "faizan.csv"
OUT = ROOT / "data" / "features.parquet"


def main():
    # on_bad_lines=skip: a few rows contain stray commas in the URL
    df = pd.read_csv(SRC, dtype=str, on_bad_lines="skip").dropna(subset=["url", "label"])
    df["url"] = df["url"].str.strip()
    df = df.drop_duplicates(subset="url").reset_index(drop=True)
    df["label"] = df["label"].str.strip().str.lower().map({"bad": 1, "good": 0})
    df = df.dropna(subset=["label"])
    df["label"] = df["label"].astype("int8")

    X = pd.DataFrame([features_vector(u) for u in df["url"]], columns=FEATURE_NAMES)
    out = pd.concat([X, df[["label", "url"]].reset_index(drop=True)], axis=1)
    OUT.parent.mkdir(exist_ok=True)
    out.to_parquet(OUT, index=False)

    print(f"rows: {len(out)}  feature columns: {len(FEATURE_NAMES)}")
    print(f"positive rate (bad): {out['label'].mean():.4f}")
    corr = X.corrwith(out["label"]).abs().sort_values(ascending=False)
    print("\ntop |correlation| with label:")
    print(corr.head(10).round(4).to_string())
    print(f"\nwrote {OUT}")


if __name__ == "__main__":
    main()
