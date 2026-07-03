#!/usr/bin/env python3
"""
Colorize GuacaMalla logcat lines for the jury demo.

    adb logcat -v time | python3 scripts/logcat_pretty.py

Highlights:
  - OK   (verified frame)   → green
  - DROP (reject cascade)  → red
  - Aware / BLE lifecycle  → cyan
  - everything else        → dim
"""
from __future__ import annotations
import re
import sys

ANSI = {
    "reset":  "\033[0m",
    "dim":    "\033[2m",
    "green":  "\033[32m",
    "red":    "\033[31m",
    "yellow": "\033[33m",
    "cyan":   "\033[36m",
    "bold":   "\033[1m",
}

GUACAMAYA_TAGS = re.compile(
    r"(guacamaya\.(ble|aware|mesh|service)|crypto|proto)"
)

DROP_RE  = re.compile(r"\bDROP\b", re.IGNORECASE)
OK_RE    = re.compile(r"\bOK\b")
AWARE_RE = re.compile(r"(Aware|publish|subscribe|NDP)", re.IGNORECASE)


def colorize(line: str) -> str:
    if not GUACAMAYA_TAGS.search(line):
        return f"{ANSI['dim']}{line.rstrip()}{ANSI['reset']}"

    if DROP_RE.search(line):
        return f"{ANSI['red']}{ANSI['bold']}{line.rstrip()}{ANSI['reset']}"
    if OK_RE.search(line):
        return f"{ANSI['green']}{line.rstrip()}{ANSI['reset']}"
    if AWARE_RE.search(line):
        return f"{ANSI['cyan']}{line.rstrip()}{ANSI['reset']}"
    return f"{ANSI['yellow']}{line.rstrip()}{ANSI['reset']}"


def main() -> int:
    if hasattr(sys.stdin, "reconfigure"):
        sys.stdin.reconfigure(encoding="utf-8", errors="replace")
    for line in sys.stdin:
        sys.stdout.write(colorize(line) + "\n")
        sys.stdout.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
