"""Run: py -m pytest backend   (or: py backend/test_app.py)"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from fastapi.testclient import TestClient  # noqa: E402

import app as appmod  # noqa: E402

client = TestClient(appmod.app)
KEYS = {"url", "host", "verdict", "score", "threshold", "factors",
        "impersonates", "guidance", "source", "checked_ms"}


def test_health():
    assert client.get("/health").json()["status"] == "ok"


def test_allowlist_safe():
    r = client.post("/check", json={"url": "https://accounts.google.com/signin"}).json()
    assert KEYS <= r.keys()
    assert r["verdict"] == "safe" and r["source"] == "allowlist"


def test_blocklist_malicious():
    appmod.blocklist.add("evil-test.invalid")
    try:
        r = client.post("/check", json={"url": "http://login.evil-test.invalid/x"}).json()
    finally:
        appmod.blocklist.discard("evil-test.invalid")
    assert r["verdict"] == "malicious" and r["source"] == "blocklist"


def test_model_malicious():
    r = client.post("/check", json={
        "url": "http://paypal.com.secure-login.gq/account/verify"}).json()
    assert KEYS <= r.keys()
    assert r["source"] == "model"
    assert r["verdict"] == "malicious", r
    assert r["impersonates"] == "paypal"
    assert 0 < len(r["factors"]) <= 5
    assert max(f["weight"] for f in r["factors"]) == 1.0


def test_malformed_url_does_not_crash():
    assert client.post("/check", json={"url": "http://[::1/broken"}).status_code == 200


def test_report(tmp_path):
    appmod.REPORT_LOG = tmp_path / "reports.jsonl"
    r = client.post("/report", json={"url": "http://x.tk", "user_verdict": "phishing"})
    assert r.json()["status"] == "recorded"
    assert "x.tk" in appmod.REPORT_LOG.read_text()


if __name__ == "__main__":
    import pytest
    sys.exit(pytest.main([__file__, "-q"]))
