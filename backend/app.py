"""PhishGuard API. Run: uvicorn app:app --app-dir backend"""
import json
import logging
import time
from datetime import datetime, timezone
from pathlib import Path

import joblib
import pandas as pd
import shap
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from features import BRANDS, _parse, _registered_domain, _is_ip, features_vector

HERE = Path(__file__).resolve().parent
CAUTION_THRESHOLD = 0.45
RESERVED = {"example.com", "example.org", "example.net", "localhost"}
RESERVED_SUFFIXES = (".test", ".invalid", ".localhost", ".example")

log = logging.getLogger("phishguard")
if not log.handlers:
    _h = logging.StreamHandler()
    _h.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
    log.addHandler(_h)
    log.setLevel(logging.INFO)

LABELS = {
    "url_length": "Unusually long web address",
    "hostname_length": "Very long site name",
    "path_length": "Long path after the site name",
    "num_dots": "Many dots in the address",
    "num_hyphens": "Many hyphens in the address",
    "num_at": "Contains an '@' symbol",
    "num_question": "Contains a query string",
    "num_percent": "Heavy use of encoded (%) characters",
    "num_ampersand": "Many '&' parameters",
    "num_equals": "Many '=' parameters",
    "num_underscore": "Many underscores",
    "num_slash": "Many slashes (deeply nested path)",
    "num_digits": "Many digits in the address",
    "digit_ratio": "High proportion of digits",
    "num_subdomains": "Many subdomains",
    "has_ip": "Uses a raw IP address instead of a name",
    "has_punycode": "Uses look-alike (punycode) characters",
    "suspicious_tld": "Suspicious domain ending",
    "no_https": "Not using HTTPS",
    "has_port": "Uses an unusual port number",
    "num_query_params": "Many query parameters",
    "longest_token_len": "Contains a very long random-looking word",
    "url_entropy": "Random-looking characters in the address",
    "brand_in_subdomain": "Brand name used in a subdomain",
    "brand_not_in_domain": "Brand name appears but isn't the real site",
    "is_shortener": "Link-shortener hides the real destination",
    "double_slash_in_path": "Double slash inside the path",
}


def _load_domains(path):
    out = set()
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            d = line.split("#", 1)[0].strip().lower()
            if d and d not in RESERVED and not d.endswith(RESERVED_SUFFIXES):
                out.add(d)
    return out


def _host_listed(host, domains):
    """True if host equals a listed domain or is a subdomain of one."""
    parts = host.split(".")
    return any(".".join(parts[i:]) in domains for i in range(len(parts)))


bundle = joblib.load(HERE / "model.joblib")
model, THRESHOLD, FEATURES = bundle["model"], float(bundle["threshold"]), bundle["features"]
explainer = shap.TreeExplainer(model)
allowlist = _load_domains(HERE / "allowlist.txt")
blocklist = _load_domains(HERE / "blocklist.txt")
REPORT_LOG = HERE / "reports.jsonl"
log.info("loaded model (%d features), %d allowlisted, %d blocklisted",
         len(FEATURES), len(allowlist), len(blocklist))

app = FastAPI(title="PhishGuard")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"],
                   allow_headers=["*"])


class CheckRequest(BaseModel):
    url: str = Field(min_length=1, max_length=4096)
    html: str | None = None  # accepted for future use; not scored (URL-only model)


class ReportRequest(BaseModel):
    url: str = Field(min_length=1, max_length=4096)
    user_verdict: str = Field(max_length=32)


def impersonated_brand(host, path):
    if not host or _is_ip(host):
        return None
    labels = [l for l in host.split(".") if l]
    reg = ".".join(_registered_domain(labels))
    sub = ".".join(labels[: len(labels) - len(_registered_domain(labels))])
    where = sub + "/" + path.lower()
    for b in BRANDS:
        if b in where and b not in reg:
            return b
    return None


def explain(row):
    sv = explainer.shap_values(row)
    if isinstance(sv, list):
        sv = sv[1]
    contrib = sv[0]
    pos = sorted(((c, f) for c, f in zip(contrib, FEATURES) if c > 0), reverse=True)[:5]
    if not pos:
        return []
    top = pos[0][0]
    return [{"feature": f, "label": LABELS.get(f, f), "weight": round(float(c / top), 3),
             "value": float(row[f].iloc[0])} for c, f in pos]


GUIDANCE = {
    "malicious": "Do not open this link or enter any personal information. "
                 "If you already did, change the affected passwords.",
    "caution": "This link looks unusual. Avoid entering passwords or payment "
               "details unless you reach the site by typing its address yourself.",
    "safe": "No phishing signals strong enough to warn about. "
            "Still be careful with unexpected requests for passwords or payment.",
}


def _response(url, host, verdict, score, factors, impersonates, source, t0, guidance=None):
    ms = round((time.perf_counter() - t0) * 1000, 2)
    log.info("check host=%s verdict=%s score=%.3f source=%s checked_ms=%.2f",
             host, verdict, score, source, ms)
    return {"url": url, "host": host, "verdict": verdict, "score": round(score, 4),
            "threshold": THRESHOLD, "factors": factors, "impersonates": impersonates,
            "guidance": guidance or GUIDANCE[verdict], "source": source,
            "checked_ms": ms}


@app.get("/health")
def health():
    return {"status": "ok", "model_features": len(FEATURES),
            "allowlist": len(allowlist), "blocklist": len(blocklist)}


@app.post("/check")
def check(req: CheckRequest):
    t0 = time.perf_counter()
    host, _, path, _, _ = _parse(req.url)
    if host and not _is_ip(host):
        if _host_listed(host, allowlist):
            return _response(req.url, host, "safe", 0.0, [], None, "allowlist", t0,
                             "This is a well-known trusted site.")
        if _host_listed(host, blocklist):
            return _response(req.url, host, "malicious", 1.0, [], impersonated_brand(host, path),
                             "blocklist", t0, GUIDANCE["malicious"] +
                             " This site is on a known-bad list.")
    elif host and host in blocklist:
        return _response(req.url, host, "malicious", 1.0, [], None, "blocklist", t0)

    row = pd.DataFrame([features_vector(req.url)], columns=FEATURES)
    score = float(model.predict_proba(row)[0, 1])
    verdict = ("malicious" if score >= THRESHOLD
               else "caution" if score >= CAUTION_THRESHOLD else "safe")
    factors = explain(row) if verdict != "safe" or score > 0.2 else []
    return _response(req.url, host, verdict, score, factors,
                     impersonated_brand(host, path), "model", t0)


@app.post("/report")
def report(req: ReportRequest):
    rec = {"ts": datetime.now(timezone.utc).isoformat(), "url": req.url,
           "user_verdict": req.user_verdict}
    with REPORT_LOG.open("a", encoding="utf-8") as f:
        f.write(json.dumps(rec) + "\n")
    return {"status": "recorded"}
