#!/usr/bin/env python3
"""Emit fixed diagnostic categories, never build log contents or environment values."""
from __future__ import annotations

import re
import sys
from pathlib import Path
from xml.etree import ElementTree


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


def unit_test_locations(reports: Path, root: Path) -> list[tuple[str, int]]:
    """Only checkout test paths and line numbers; no report messages, names or traces."""
    root = root.resolve()
    base = root / "android/src/android/app/src"
    source_dirs = [base / variant / "java" for variant in ("testTamaTest", "testDebug", "test")]
    locations = []
    for report in sorted(reports.glob("TEST-*.xml")):
        try:
            if report.stat().st_size > 8_000_000:
                continue
            suite = ElementTree.parse(report)
        except (ElementTree.ParseError, OSError):
            continue
        for case in suite.iter("testcase"):
            failure = case.find("failure")
            classname = case.get("classname", "").partition("$")[0]
            if failure is None or not re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]*(?:[.][A-Za-z_][A-Za-z_0-9]*)+", classname):
                continue
            source = Path(*classname.split(".")).with_suffix(".kt")
            file = next((path for directory in source_dirs
                         if (path := (directory / source).resolve()).is_relative_to(directory) and path.is_file()), None)
            if file is None:
                continue
            lines = file.read_text(encoding="utf-8").splitlines()
            frame = re.search(
                rf"^\s*at {re.escape(classname)}[.$][^(\r\n]*\({re.escape(file.name)}:(\d{{1,6}})\)",
                failure.text or "", re.MULTILINE,
            )
            line = int(frame.group(1)) if frame else 0
            if not 1 <= line <= len(lines):
                test_name = case.get("name", "").removesuffix("()")
                method = re.compile(rf"\bfun\s+(?:`{re.escape(test_name)}`|{re.escape(test_name)})\s*\(")
                line = next((i for i, source_line in enumerate(lines, 1) if method.search(source_line)), 1)
            location = (file.relative_to(root).as_posix(), line)
            if location not in locations:
                locations.append(location)
            if len(locations) == 12:
                return locations
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
        root = Path(__file__).resolve().parents[2]
        for file, line in compiler_locations(text, root):
            print(f"::error file={file},line={line},title=Android Kotlin compile::Compiler error at this source location.")
        if len(sys.argv) > 3:
            for file, line in unit_test_locations(Path(sys.argv[3]), root):
                print(f"::error file={file},line={line},title=Android JUnit::Unit test failed at this source location.")
    except Exception:
        print("::error title=Android build diagnostic::Unable to classify the build failure.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
