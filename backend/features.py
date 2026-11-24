"""Lexical URL features (pure stdlib). Computed from the URL string only.

FEATURE_NAMES defines the model's input vector order. This file is copied to
backend/features.py so training and inference stay identical.
"""
import ipaddress
import math
import re
from collections import Counter, OrderedDict
from urllib.parse import urlparse

FEATURE_NAMES = [
    "url_length", "hostname_length", "path_length",
    "num_dots", "num_hyphens", "num_at", "num_question", "num_percent",
    "num_ampersand", "num_equals", "num_underscore", "num_slash",
    "num_digits", "digit_ratio", "num_subdomains",
    "has_ip", "has_punycode", "suspicious_tld", "no_https", "has_port",
    "num_query_params", "longest_token_len", "url_entropy",
    "brand_in_subdomain", "brand_not_in_domain",
    "is_shortener", "double_slash_in_path",
]

SUSPICIOUS_TLDS = {"tk", "ml", "ga", "cf", "gq", "xyz", "top", "click", "zip", "work"}
BRANDS = ("paypal", "apple", "google", "microsoft", "amazon", "facebook",
          "netflix", "instagram", "whatsapp", "linkedin", "dropbox", "chase",
          "wellsfargo", "bankofamerica", "icloud", "outlook", "office365")
SHORTENERS = {"bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd",
              "buff.ly", "rebrand.ly", "cutt.ly", "shorturl.at", "tiny.cc"}
# second-level labels that make the registered domain 3 labels (e.g. foo.co.uk)
SLD_LABELS = {"co", "com", "org", "net", "gov", "edu", "ac"}

_TOKEN_SPLIT = re.compile(r"[^A-Za-z0-9]+")


def _parse(url):
    """Return (hostname, port_present, path, query), tolerant of malformed input."""
    url = (url or "").strip()
    if "://" not in url:
        url = "http://" + url.lstrip("/")
    try:
        p = urlparse(url)
    except ValueError:
        return "", False, "", "", url
    try:
        host = p.hostname or ""
    except ValueError:
        host = ""
    try:
        has_port = p.port is not None
    except ValueError:
        has_port = True  # a port was present but invalid
    return host.lower(), has_port, p.path or "", p.query or "", url


def _is_ip(host):
    try:
        ipaddress.ip_address(host)
        return True
    except ValueError:
        pass
    # decimal / hex single-number IPs, e.g. 3232235777 or 0xC0A80001
    return bool(re.fullmatch(r"\d{8,10}|0x[0-9a-f]{8}", host))


def _registered_domain(labels):
    if len(labels) <= 2:
        return labels
    if len(labels[-1]) == 2 and labels[-2] in SLD_LABELS:
        return labels[-3:]
    return labels[-2:]


def _entropy(s):
    if not s:
        return 0.0
    n = len(s)
    return -sum(c / n * math.log2(c / n) for c in Counter(s).values())


def extract_features(url):
    host, has_port, path, query, full = _parse(url)
    ip = _is_ip(host)
    labels = [l for l in host.split(".") if l] if host and not ip else []
    reg = _registered_domain(labels)
    sub_labels = labels[: len(labels) - len(reg)] if labels else []
    subdomain = ".".join(sub_labels)
    reg_name = ".".join(reg)
    tld = labels[-1] if labels else ""

    rest = subdomain + "/" + path  # where brands are suspicious
    brand_sub = any(b in subdomain for b in BRANDS)
    brand_misplaced = any(b in rest and b not in reg_name for b in BRANDS)

    n = len(full)
    digits = sum(ch.isdigit() for ch in full)
    tokens = [t for t in _TOKEN_SPLIT.split(full) if t]
    scheme_https = full.lower().startswith("https://")

    f = OrderedDict()
    f["url_length"] = n
    f["hostname_length"] = len(host)
    f["path_length"] = len(path)
    f["num_dots"] = full.count(".")
    f["num_hyphens"] = full.count("-")
    f["num_at"] = full.count("@")
    f["num_question"] = full.count("?")
    f["num_percent"] = full.count("%")
    f["num_ampersand"] = full.count("&")
    f["num_equals"] = full.count("=")
    f["num_underscore"] = full.count("_")
    f["num_slash"] = full.count("/")
    f["num_digits"] = digits
    f["digit_ratio"] = digits / n if n else 0.0
    f["num_subdomains"] = len(sub_labels)
    f["has_ip"] = int(ip)
    f["has_punycode"] = int("xn--" in host)
    f["suspicious_tld"] = int(tld in SUSPICIOUS_TLDS)
    f["no_https"] = int(not scheme_https)
    f["has_port"] = int(has_port)
    f["num_query_params"] = len([q for q in query.split("&") if q]) if query else 0
    f["longest_token_len"] = max((len(t) for t in tokens), default=0)
    f["url_entropy"] = _entropy(full)
    f["brand_in_subdomain"] = int(brand_sub)
    f["brand_not_in_domain"] = int(brand_misplaced)
    f["is_shortener"] = int(reg_name in SHORTENERS or host in SHORTENERS)
    f["double_slash_in_path"] = int("//" in path)
    return f


def features_vector(url):
    f = extract_features(url)
    return [float(f[k]) for k in FEATURE_NAMES]


assert len(FEATURE_NAMES) == 27
assert list(extract_features("x.com").keys()) == FEATURE_NAMES


if __name__ == "__main__":
    samples = [
        "https://www.google.com/search?q=hello",
        "http://paypal.com.secure-login.example.tk/verify?id=1&session=2",
        "http://192.168.1.77:8080/login.php",
        "bit.ly/3abcXYZ",
        "https://xn--pple-43d.com/account//update",
        "http://[::1/broken",          # malformed: invalid IPv6 -> ValueError
        "http://host:notaport/x",     # malformed port
        "",
    ]
    for s in samples:
        print(f"\n{s!r}")
        for k, v in extract_features(s).items():
            if v:
                print(f"  {k:22s} {v:.3f}" if isinstance(v, float) else f"  {k:22s} {v}")
