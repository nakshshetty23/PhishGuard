#!/usr/bin/env bash
# Download the public labeled URL dataset (columns: url,label with good/bad).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$ROOT/data"

curl -fL --retry 3 \
  -o "$ROOT/data/faizan.csv" \
  "https://raw.githubusercontent.com/faizann24/Using-machine-learning-to-detect-malicious-URLs/master/data/data.csv"

echo "Saved $ROOT/data/faizan.csv ($(wc -l < "$ROOT/data/faizan.csv") lines)"
