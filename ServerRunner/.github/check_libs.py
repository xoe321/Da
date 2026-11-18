"""Filter requirements to packages with Chaquopy Android or pure-Python wheels."""
import json
import re
import urllib.request

REQ = "app/requirements.txt"
CHAQUO = "https://chaquo.com/pypi-13.1/{}/"
DEPS = {
    "fastapi": ["pydantic"],
    "pydantic": ["pydantic-core"],
    "argon2-cffi": ["argon2-cffi-bindings"],
    "cryptography": ["cffi"],
    "slowapi": ["fastapi"],
}


def get(url):
    try:
        with urllib.request.urlopen(url, timeout=30) as response:
            return response.read().decode("utf-8", "replace")
    except Exception:
        return None


cache = {}


def ok(package):
    package = re.sub(r"[-_.]+", "-", package).lower()
    if package in cache:
        return cache[package]

    result = (False, "no Android wheel / not pure-Python")
    html = get(CHAQUO.format(package))
    if html and re.search(r"cp311[^\"<>]*arm64_v8a", html):
        result = (True, "Chaquopy native wheel")
    else:
        metadata = get("https://pypi.org/pypi/{}/json".format(package))
        if metadata:
            try:
                urls = json.loads(metadata)["urls"]
                if any(item["filename"].endswith("-none-any.whl") for item in urls):
                    result = (True, "pure Python")
            except (KeyError, TypeError, ValueError):
                pass

    cache[package] = result
    return result


def check(package, checked=None):
    if checked is None:
        checked = set()
    normalized = re.sub(r"[-_.]+", "-", package).lower()
    if normalized in checked:
        return True, "dependency already checked"
    checked.add(normalized)

    good, reason = ok(normalized)
    if not good:
        return False, reason

    for dependency in DEPS.get(normalized, []):
        dependency_ok, dependency_reason = check(dependency, checked)
        if not dependency_ok:
            return False, "needs {}: {}".format(dependency, dependency_reason)
    return True, reason


keep = []
report = []
with open(REQ, encoding="utf-8") as requirements:
    for line in requirements:
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            keep.append(line.rstrip())
            continue

        name = re.split(r"[\[<>=!~ ;]", stripped)[0]
        included, reason = check(name)
        if included:
            keep.append(line.rstrip())
        report.append(
            "{:<8} {:<16} {}".format(
                "INCLUDED" if included else "DROPPED", name, reason
            )
        )

with open(REQ, "w", encoding="utf-8") as requirements:
    requirements.write("\n".join(keep) + "\n")
with open("libs-report.txt", "w", encoding="utf-8") as report_file:
    report_file.write("\n".join(report) + "\n")

print("\n".join(report))
