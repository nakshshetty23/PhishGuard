import filecmp
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

from features import FEATURE_NAMES, extract_features, features_vector  # noqa: E402


@pytest.mark.parametrize("url", [
    "http://[::1/broken",                      # malformed IPv6 -> urlparse ValueError
    "http://host:notaport/x",                  # invalid port
    "http://192.168.1.77:8080/login.php",      # IP host
    "http://3232235777/a",                     # decimal-encoded IP
    "https://xn--pple-43d.com/account",        # punycode host
    "",                                        # empty
    "   ",
    "///",
    "a" * 5000,
])
def test_no_crash_and_all_features(url):
    f = extract_features(url)
    assert list(f.keys()) == FEATURE_NAMES
    vec = features_vector(url)
    assert len(vec) == len(FEATURE_NAMES) == 27
    assert all(isinstance(v, float) for v in vec)


def test_ip_host_flagged():
    assert extract_features("http://192.168.1.77:8080/login.php")["has_ip"] == 1
    assert extract_features("http://192.168.1.77:8080/login.php")["has_port"] == 1


def test_punycode_flagged():
    assert extract_features("https://xn--pple-43d.com/")["has_punycode"] == 1
    assert extract_features("https://apple.com/")["has_punycode"] == 0


def test_missing_scheme_gets_http():
    assert extract_features("example.org/a")["no_https"] == 1
    assert extract_features("https://example.org/a")["no_https"] == 0


def test_backend_copy_is_identical():
    root = Path(__file__).resolve().parent.parent
    assert filecmp.cmp(root / "ml" / "features.py", root / "backend" / "features.py", shallow=False)
