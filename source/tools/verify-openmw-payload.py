#!/usr/bin/env python3
"""Verify the OpenMW 0.51.0 Android native payload that Gradle requires.

`app/build.gradle` refuses to assemble any variant until the consolidated
OpenMW 0.51.0 Final runtime payload exists (task `verifyOpenMwPayload`).
That native payload is generated, not committed: `tools/build-openmw-051-runtime.ps1`
compiles OpenMW and writes it into

    app/src/main/jniLibs/arm64-v8a/*.so
    app/src/main/assets/libopenmw/**
    app/src/main/assets/android_omwfx/*.omwfx

so a fresh clone (and a fresh CI runner) has none of it. This script reports
exactly which pieces are missing, which is what you need when a build stops
on `verifyOpenMwPayload`.

Usage:
    python3 tools/verify-openmw-payload.py            # exit 1 when incomplete
    python3 tools/verify-openmw-payload.py --report    # never fails, just reports
    python3 tools/verify-openmw-payload.py --allow-missing
"""

from __future__ import annotations

import argparse
import hashlib
import re
import sys
from pathlib import Path

SOURCE_DIR = Path(__file__).resolve().parents[1]
APP_DIR = SOURCE_DIR / "app"
BUILD_GRADLE = APP_DIR / "build.gradle"

REQUIRED_ENGINE_MARKER = (
    "OpenMW 0.51.0 Final\ncommit=f4bec41444214a7903bebd178389ca22ca13f646"
)
EXPECTED_RESOURCE_PREFIX = "0.51.0"
# Relative to source/, kept in sync with tools/build-openmw-051-runtime.ps1 step
# "apply-openmw-051-patch39-release-log-cleanup.ps1".
NATIVE_SHA_FILE = SOURCE_DIR / "buildscripts" / "openmw-051-patch39-libopenmw.sha256"


def declared_payload_files() -> list[Path]:
    """Read the required payload list straight out of app/build.gradle."""
    text = BUILD_GRADLE.read_text(encoding="utf-8")
    match = re.search(r"def requiredOpenMwPayload\s*=\s*\[(.*?)\n\]", text, re.S)
    if not match:
        raise SystemExit(
            "Could not find `requiredOpenMwPayload` in app/build.gradle; "
            "the build gate was refactored, update this script."
        )
    entries = re.findall(r"file\('([^']+)'\)", match.group(1))
    return [APP_DIR / entry for entry in entries]


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(64 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def check() -> list[str]:
    """Return a list of human readable failures; empty means the payload is ok."""
    failures: list[str] = []

    missing = [path for path in declared_payload_files() if not path.exists()]
    for path in missing:
        failures.append(
            "missing: " + str(path.relative_to(SOURCE_DIR)).replace("\\", "/")
        )

    marker_file = APP_DIR / "src/main/assets/libopenmw/openmw/openmw-engine-version.txt"
    if marker_file.is_file():
        marker = marker_file.read_text(encoding="utf-8").replace("\r\n", "\n").strip()
        if marker != REQUIRED_ENGINE_MARKER:
            failures.append(
                "engine marker mismatch in "
                + str(marker_file.relative_to(SOURCE_DIR)).replace("\\", "/")
                + "\n  expected: "
                + REQUIRED_ENGINE_MARKER.replace("\n", " | ")
                + "\n  actual:   "
                + marker.replace("\n", " | ")
            )

    if not NATIVE_SHA_FILE.is_file():
        failures.append(
            "missing: "
            + str(NATIVE_SHA_FILE.relative_to(SOURCE_DIR)).replace("\\", "/")
            + " (run tools/apply-openmw-051-patch39-release-log-cleanup.ps1)"
        )
    else:
        expected = NATIVE_SHA_FILE.read_text(encoding="utf-8").split()[0].strip()
        native_lib = APP_DIR / "src/main/jniLibs/arm64-v8a/libopenmw.so"
        if native_lib.is_file():
            actual = sha256_of(native_lib)
            if actual.lower() != expected.lower():
                failures.append(
                    "stale native library: src/main/jniLibs/arm64-v8a/libopenmw.so\n"
                    "  expected SHA-256: "
                    + expected
                    + "\n  actual SHA-256:   "
                    + actual
                )

    resources_version = APP_DIR / "src/main/assets/libopenmw/resources/version"
    if resources_version.is_file():
        version = resources_version.read_text(encoding="utf-8").strip()
        if not version.startswith(EXPECTED_RESOURCE_PREFIX):
            failures.append(
                "resources are not from "
                + EXPECTED_RESOURCE_PREFIX
                + ": resources/version is '"
                + version
                + "'"
            )

    return failures


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--allow-missing",
        "--report",
        dest="allow_missing",
        action="store_true",
        help="report problems but always exit 0",
    )
    args = parser.parse_args()

    failures = check()
    if not failures:
        print("OpenMW 0.51.0 native payload: OK")
        return 0

    print("OpenMW 0.51.0 native payload: INCOMPLETE")
    print()
    for failure in failures:
        print(" - " + failure)
    print()
    print(
        "The payload is generated, never committed. On Windows run\n"
        "  tools\\build-openmw-051-runtime.ps1\n"
        "then\n"
        "  tools\\pack-openmw-payload.ps1\n"
        "and publish the resulting zip as the GitHub release asset described in\n"
        "  tools/GITHUB_BUILD.md"
    )
    return 0 if args.allow_missing else 1


if __name__ == "__main__":
    sys.exit(main())
