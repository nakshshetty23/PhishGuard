"""Refresh backend/blocklist.txt from a public phishing feed (detection use only).

Merges both sources (either may fail; the other is still used):
  - OpenPhish community feed (fresh; one phishing URL per line)
  - mitchellkrogza/Phishing.Database ACTIVE domain list (large)

Extracts hostnames, de-duplicates them, drops anything on our allowlist (the
allowlist tier runs first anyway, and a feed entry like a shared-hosting page
must never block a whole trusted domain), and writes the result.
"""
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from features import SHORTENERS, _parse

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "backend" / "blocklist.txt"
ALLOWLIST = ROOT / "backend" / "allowlist.txt"

FEEDS = [
    ("OpenPhish", "https://openphish.com/feed.txt"),
    ("Phishing.Database ACTIVE",
     "https://raw.githubusercontent.com/mitchellkrogza/Phishing.Database/master/"
     "phishing-domains-ACTIVE.txt"),
]
MAX_BYTES = 100 * 1024 * 1024  # refuse absurdly large responses


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "phishguard-blocklist-updater/1.0"})
    with urllib.request.urlopen(req, timeout=60) as r:
        data = r.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES:
        raise ValueError("feed too large")
    return data.decode("utf-8", errors="replace")


def load_allowlist():
    out = set()
    for line in ALLOWLIST.read_text(encoding="utf-8").splitlines():
        d = line.split("#", 1)[0].strip().lower()
        if d:
            out.add(d)
    return out


def allowlisted(host, allow):
    parts = host.split(".")
    return any(".".join(parts[i:]) in allow for i in range(len(parts)))


def hosts_from(text):
    hosts = set()
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        host = _parse(line)[0].strip(".")
        if host and "." in host and len(host) <= 253:
            hosts.add(host)
    return hosts


# Bare shared-hosting / platform domains. A feed entry for one of these EXACT
# names would block every customer site on the platform, so it is skipped
# (individual subdomains such as evil.pages.dev are still kept).
SHARED_PLATFORMS = {
    "pages.dev", "workers.dev", "github.io", "netlify.app", "vercel.app",
    "web.app", "firebaseapp.com", "blogspot.com", "weebly.com", "wixsite.com",
    "000webhostapp.com", "glitch.me", "herokuapp.com", "azurewebsites.net",
    "r2.dev", "ngrok.io", "ngrok-free.app", "duckdns.org", "webflow.io",
    "square.site", "godaddysites.com", "mybluehost.me", "repl.co", "framer.app",
    # user-content hosts found as bare entries in the ACTIVE list
    "dweb.link", "w3s.link", "firebasestorage.googleapis.com", "telegra.ph",
    "plesk.page",
} | SHORTENERS  # e.g. the feed lists bit.ly itself; blocking it blocks every bit.ly link


def main():
    allow = load_allowlist()
    merged, used = set(), []
    for name, url in FEEDS:
        try:
            hosts = hosts_from(fetch(url))
        except Exception as e:  # network error, HTTP error, bad size
            print(f"{name}: failed ({e})", file=sys.stderr)
            continue
        print(f"{name}: {len(hosts)} hosts")
        if hosts:
            merged |= hosts
            used.append((name, url))
    if not merged:
        print("All feeds failed; blocklist left unchanged.", file=sys.stderr)
        return 1

    kept = sorted(h for h in merged
                  if not allowlisted(h, allow) and h not in SHARED_PLATFORMS)
    dropped = len(merged) - len(kept)
    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    sources = "; ".join(f"{n} ({u})" for n, u in used)
    header = (f"# Known-bad hosts merged from: {sources}\n"
              f"# Updated {stamp}. {len(kept)} unique hosts; {dropped} skipped "
              f"(allowlisted or bare shared-hosting platform).\n"
              f"# Regenerate with: py ml/update_blocklist.py\n")
    OUT.write_text(header + "\n".join(kept) + "\n", encoding="utf-8")
    print(f"merged {len(merged)} unique hosts -> wrote {len(kept)} to {OUT} "
          f"({dropped} skipped)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
