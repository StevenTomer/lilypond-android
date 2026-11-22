#!/usr/bin/env python3
"""Print the newest LilyPond source release (stable or development).

  tools/latest-lilypond.py            # e.g. 2.27.5
  tools/latest-lilypond.py --stable   # newest even-numbered series, e.g. 2.26.0
"""
import re
import sys
import urllib.request

BASE = "https://lilypond.org/download/source/"


def fetch(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "lilypond-android-ci"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read().decode("utf-8", "replace")


def key(v: str):
    return tuple(int(x) for x in v.split("."))


def main():
    stable_only = "--stable" in sys.argv
    series = sorted(set(re.findall(r'href="v(\d+\.\d+)/"', fetch(BASE))), key=key)
    if stable_only:
        series = [s for s in series if int(s.split(".")[1]) % 2 == 0]
    for s in reversed(series):
        versions = set(re.findall(r"lilypond-(\d+\.\d+\.\d+)\.tar\.gz", fetch(f"{BASE}v{s}/")))
        if versions:
            print(max(versions, key=key))
            return
    sys.exit("no LilyPond release found")


if __name__ == "__main__":
    main()
