#!/usr/bin/env python3
"""Emit fixed diagnostic categories, never build log contents or environment values."""
from __future__ import annotations

import sys
from pathlib import Path


def categories(text: str, exit_code: int) -> list[str]:
    lower = text.lower()
    found = []
    if exit_code in (137, 143) or any(s in lower for s in (
        "outofmemoryerror", "out of memory", "java heap space", "metaspace", "daemon disappeared",
    )):
        found.append("Resource pressure or terminated build process may be involved")
    if any(s in lower for s in ("compilation error", "compilation failed", "e: file:", "e: /")):
        found.append("Compiler reported an error")
    if any(s in lower for s in ("could not resolve", "could not download", "connection timed out", "unknownhostexception")):
        found.append("Dependency resolution or network failure may be involved")
    if any(s in lower for s in ("android resource linking failed", "failed to compile values resource")):
        found.append("Android resource processing reported an error")
    if any(s in lower for s in ("keystore was tampered", "keystore file not found", "failed to read key")):
        found.append("Signing configuration may need attention")
    return found or ["No known failure category was found in the captured build output"]


def main() -> int:
    try:
        path = Path(sys.argv[1])
        code = int(sys.argv[2])
        # Keep memory bounded even if the build output is unexpectedly large.
        with path.open("rb") as source:
            source.seek(0, 2)
            source.seek(max(0, source.tell() - 8_000_000))
            text = source.read(8_000_000).decode("utf-8", errors="replace")
        for category in categories(text, code):
            print(f"::error title=Android build diagnostic::{category}. Build output was not copied here.")
    except Exception:
        print("::error title=Android build diagnostic::Unable to classify the build failure.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
