#!/usr/bin/env python3
"""Verify the debug-only side-by-side APK against the normal APK, without installing either."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

TEST_ID = "ai.tama.assistant.tamav2test"
NORMAL_ID = "ai.tama.assistant"
TEST_LABEL = "Tama V2 Test"
ALIASES = {"MainActivityIconAuto", "MainActivityIconLight", "MainActivityIconDark"}


def run(*args: str) -> str:
    try:
        return subprocess.run(args, check=True, capture_output=True, text=True).stdout
    except subprocess.CalledProcessError as exc:
        detail = " ".join((exc.stderr or exc.stdout or "").split())[:700]
        raise ValueError(
            f"{Path(args[0]).name} failed with exit {exc.returncode}: {detail}"
        ) from exc


def package(badging: str) -> str:
    match = re.search(r"^package: name='([^']+)'", badging, re.MULTILINE)
    if not match:
        raise ValueError("APK package missing from badging")
    return match.group(1)


def authorities(tree: str) -> set[str]:
    matches = re.findall(r'android:authorities\([^)]*\)="([^"]+)"', tree)
    return {value for match in matches for value in match.split(";") if value}


def validate(test_badging: str, normal_badging: str, test_tree: str, normal_tree: str) -> None:
    if package(test_badging) != TEST_ID or package(normal_badging) != NORMAL_ID:
        raise ValueError("Unexpected APK package IDs")
    if f"application-label:'{TEST_LABEL}'" not in test_badging:
        raise ValueError("The parallel APK application label is not distinct")
    if not re.search(r"^launchable-activity: .*label='Tama V2 Test'", test_badging, re.MULTILINE):
        raise ValueError("The default launcher alias does not have the test label")
    if "application-debuggable" not in test_badging:
        raise ValueError("Parallel APK is not debuggable")
    native = re.search(r"^native-code: (.+)$", test_badging, re.MULTILINE)
    if not native or re.findall(r"'([^']+)'", native.group(1)) != ["arm64-v8a"]:
        raise ValueError("Parallel APK is not arm64-v8a-only")
    test_authorities, normal_authorities = authorities(test_tree), authorities(normal_tree)
    expected = {f"{TEST_ID}.shizuku", f"{TEST_ID}.fileprovider"}
    if not expected <= test_authorities or not normal_authorities:
        raise ValueError("A required provider authority is missing")
    if test_authorities & normal_authorities:
        raise ValueError("The two APKs have conflicting provider authorities")
    # Every selectable icon uses a distinct label, not just the default alias.
    blocks = re.split(r"(?m)^\s*E: activity-alias\b[^\n]*\n", test_tree)[1:]
    found = set()
    for block in blocks:
        attrs = re.split(r"(?m)^\s*E: ", block, maxsplit=1)[0]
        name = re.search(r'android:name\([^)]*\)="([^"]+)"', attrs)
        label = re.search(r'android:label\([^)]*\)="([^"]+)"', attrs)
        if name and name.group(1).split(".")[-1] in ALIASES:
            if not label or label.group(1) != TEST_LABEL:
                raise ValueError("A launcher alias lacks the distinct test label")
            found.add(name.group(1).split(".")[-1])
    if found != ALIASES:
        raise ValueError("Not all launcher aliases were found")
    if f"{TEST_ID}.permission.ALARM" not in test_tree:
        raise ValueError("The parallel signature permission is missing")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--normal-apk", type=Path, required=True)
    parser.add_argument("--out-dir", type=Path, required=True)
    args = parser.parse_args()
    test_badging = run(args.aapt, "dump", "badging", str(args.apk))
    normal_badging = run(args.aapt, "dump", "badging", str(args.normal_apk))
    test_tree = run(args.aapt, "dump", "xmltree", str(args.apk), "AndroidManifest.xml")
    normal_tree = run(args.aapt, "dump", "xmltree", str(args.normal_apk), "AndroidManifest.xml")
    validate(test_badging, normal_badging, test_tree, normal_tree)
    signature = run(args.apksigner, "verify", "--verbose", "--print-certs", str(args.apk))
    cert = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)", signature)
    if not cert or len(cert.group(1)) != 64:
        raise ValueError("Could not verify the APK signing certificate")
    digest = hashlib.sha256(args.apk.read_bytes()).hexdigest()
    args.out_dir.mkdir(parents=True, exist_ok=True)
    metadata = {
        "applicationId": TEST_ID,
        "label": TEST_LABEL,
        "abi": "arm64-v8a",
        "debuggable": True,
        "sha256": digest,
        "certificateSha256": cert.group(1).lower(),
        "sizeBytes": args.apk.stat().st_size,
        "authorities": sorted(authorities(test_tree)),
    }
    (args.out_dir / "tama-test-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    (args.out_dir / "tama-test.sha256").write_text(f"{digest}  {args.apk.name}\n")
    (args.out_dir / "tama-test-signature.txt").write_text(signature)
    print(
        f"::notice title=TAMA_PARALLEL_APK::sha256={digest} cert={cert.group(1).lower()} size={metadata['sizeBytes']}"
    )
    print("Verified distinct package, labels, ABI, signature permission and provider authorities.")


if __name__ == "__main__":
    main()
