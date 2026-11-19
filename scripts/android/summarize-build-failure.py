#!/usr/bin/env python3
"""Emit fixed diagnostic categories, never build log contents or environment values."""
from __future__ import annotations

import re
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
    if "there were failing tests" in lower:
        found.append("Gradle reported failing unit tests")
    if "no tests found for given includes" in lower:
        found.append("Gradle found no tests for the selected filters")
    return found or ["No known failure category was found in the captured build output"]


def compiler_locations(text: str, root: Path) -> list[tuple[str, int]]:
    """Only existing checkout source paths and integer line numbers, never diagnostics."""
    root = root.resolve()
    prefix = root / "android/src/android/app/src"
    pattern = re.compile(r"^e: (?:file://)?(/[a-zA-Z0-9_./-]+[.]kt):(\d{1,6}):\d{1,6}(?:\s|$)", re.MULTILINE)
    locations: list[tuple[str, int]] = []
    for match in pattern.finditer(text):
        file = Path(match.group(1)).resolve()
        line = int(match.group(2))
        if line < 1 or not file.is_relative_to(prefix) or not file.is_file():
            continue
        location = (file.relative_to(root).as_posix(), line)
        if location not in locations:
            locations.append(location)
        if len(locations) == 12:
            break
    return locations


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
        for file, line in compiler_locations(text, Path(__file__).resolve().parents[2]):
            print(f"::error file={file},line={line},title=Android Kotlin compile::Compiler error at this source location.")
    except Exception:
        print("::error title=Android build diagnostic::Unable to classify the build failure.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
