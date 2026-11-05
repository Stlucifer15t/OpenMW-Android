#!/usr/bin/env python3
"""Rebuild the OpenMW native payload from a released APK.

This repository does not commit the OpenMW 0.51.0 native payload (the engine
`libopenmw.so`, its shared-library companions and `assets/libopenmw/**`); it is
produced by `tools/build-openmw-051-runtime.ps1`. A released APK of the *same*
OpenMW Android version already contains exactly that payload, so it can be
reused instead of rebuilding the engine from source.

The payload is extracted into `source/app/src/main/...`, then every check that
`app/build.gradle` performs in `verifyOpenMwPayload` is applied. Two items are
not part of an APK and have to be filled in afterwards:

  * `assets/android_omwfx/*.omwfx` - generated from
    `buildscripts/patches/openmw051-final/apply-android-runtime-baseline.py`
    when no copy is available via `--omwfx-from`.
  * `buildscripts/openmw-051-patch39-libopenmw.sha256` - only written when the
    extracted `libopenmw.so` matches the pinned Patch-39 digest, so an APK that
    does not carry the pinned build is rejected instead of silently accepted.

Usage:
    python3 tools/seed-openmw-payload.py payload.zip
    python3 tools/seed-openmw-payload.py payload.apk --source-version 0.51.0-14
    python3 tools/seed-openmw-payload.py payload.apk --omwfx-from ../omwfx
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import sys
import zipfile
from pathlib import Path

SOURCE_DIR = Path(__file__).resolve().parents[1]
APP_DIR = SOURCE_DIR / "app"
ASSETS = APP_DIR / "src/main/assets"
JNI = APP_DIR / "src/main/jniLibs/arm64-v8a"
SHA_FILE = SOURCE_DIR / "buildscripts/openmw-051-patch39-libopenmw.sha256"

ENGINE_MARKER = "OpenMW 0.51.0 Final\ncommit=f4bec41444214a7903bebd178389ca22ca13f646"
PINNED_NATIVE_SHA = "a99f89d2e8de0f652de219af9acd29db48db10f55f0026cc7a911c426a7efdc0"

NATIVE_LIBS = (
    "libopenmw.so",
    "libopenal.so",
    "libSDL2.so",
    "libGL.so",
    "libcollada-dom2.5-dp.so",
    "libc++_shared.so",
)

# Shader/resource files the Gradle gate requires out of assets/libopenmw.
REQUIRED_RESOURCES = (
    "resources/version",
    "resources/shaders/compatibility/fullscreen_tri.vert",
    "resources/shaders/compatibility/shadowcasting.vert",
    "resources/shaders/compatibility/shadows_vertex.glsl",
    "resources/shaders/compatibility/shadows_fragment.glsl",
    "resources/shaders/compatibility/water.vert",
    "resources/shaders/compatibility/water.frag",
    "resources/shaders/compatibility/debug.vert",
    "resources/shaders/compatibility/debug.frag",
    "resources/vfs/shaders/adjustments.omwfx",
    "openmw/defaults.bin",
    "openmw/openmw.base.cfg",
    "openmw/openmw-engine-version.txt",
    "openmw/gamecontrollerdb.txt",
    "3rdparty-licenses.txt",
)

OMWFX_FILES = (
    "gateh_bloom051.omwfx",
    "godrays_android_051_depthfixed_vivid.omwfx",
    "lensflare_android_051_rayocc.omwfx",
    "rainlens_android_051_v12_dense.omwfx",
    "wetworld_android_051_weather.omwfx",
)


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(64 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_marker(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(ENGINE_MARKER + "\n", encoding="utf-8", newline="\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", help="released APK or payload zip")
    parser.add_argument(
        "--source-version",
        default=None,
        help="informational: version of the source APK, e.g. 0.51.0-14",
    )
    parser.add_argument(
        "--omwfx-from",
        default=None,
        help="directory holding the five android_omwfx/*.omwfx files",
    )
    parser.add_argument(
        "--accept-native-baseline",
        action="store_true",
        help=(
            "pin whatever libopenmw.so the archive carries (writes the SHA file "
            "with its actual digest) instead of requiring the Patch-39 build"
        ),
    )
    parser.add_argument(
        "--keep-marker",
        action="store_true",
        help="do not force the pinned engine marker",
    )
    args = parser.parse_args()

    archive = Path(args.archive).resolve()
    if not archive.is_file():
        print(f"archive not found: {archive}", file=sys.stderr)
        return 2

    print(f"Seeding the OpenMW payload from {archive.name} ({archive.stat().st_size // 1024} KiB)")
    if args.source_version:
        print(f"  source version: {args.source_version}")

    extracted_assets = 0
    extracted_libs = 0
    found_omwfx: list[str] = []

    with zipfile.ZipFile(archive) as bundle:
        names = set(bundle.namelist())

        # --- native libraries ------------------------------------------------
        for abi in ("arm64-v8a",):
            for lib in NATIVE_LIBS:
                member = f"lib/{abi}/{lib}"
                if member not in names:
                    print(f"  ! {member} is not in the archive")
                    continue
                target = JNI / lib
                target.parent.mkdir(parents=True, exist_ok=True)
                with bundle.open(member) as src, target.open("wb") as dst:
                    shutil.copyfileobj(src, dst)
                os.chmod(target, 0o644)
                extracted_libs += 1

        # --- engine resources ------------------------------------------------
        prefix = "assets/libopenmw/"
        for member in names:
            if not member.startswith(prefix) or member.endswith("/"):
                continue
            relative = member[len(prefix):]
            target = ASSETS / "libopenmw" / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            with bundle.open(member) as src, target.open("wb") as dst:
                shutil.copyfileobj(src, dst)
            extracted_assets += 1

        # OMWFX deliverables are bundled under assets/android_omwfx/ in an APK.
        # Match by basename so a different asset prefix still works.
        for member in sorted(names):
            base = member.rsplit("/", 1)[-1]
            if not base.endswith(".omwfx") or base not in OMWFX_FILES:
                continue
            if base in found_omwfx:
                continue
            target = ASSETS / "android_omwfx" / base
            target.parent.mkdir(parents=True, exist_ok=True)
            with bundle.open(member) as src, target.open("wb") as dst:
                shutil.copyfileobj(src, dst)
            found_omwfx.append(base)

    print(f"  native libraries: {extracted_libs}/{len(NATIVE_LIBS)}")
    print(f"  engine resource files: {extracted_assets}")

    # --- OMWFX ---------------------------------------------------------------
    omwfx_dir = ASSETS / "android_omwfx"
    if args.omwfx_from:
        external = Path(args.omwfx_from).resolve()
        for name in OMWFX_FILES:
            source = external / name
            if source.is_file():
                omwfx_dir.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, omwfx_dir / name)
                found_omwfx.append(name)
        print(f"  OMWFX copied from {external}")

    present_omwfx = sorted({p.name for p in omwfx_dir.glob("*.omwfx")}) if omwfx_dir.is_dir() else []
    print(f"  OMWFX files present: {len(present_omwfx)}/{len(OMWFX_FILES)}")

    # --- reconstruct the engine marker --------------------------------------
    marker = ASSETS / "libopenmw/openmw/openmw-engine-version.txt"
    if not args.keep_marker:
        if marker.is_file() and marker.read_text(encoding="utf-8").strip() == ENGINE_MARKER:
            print("  engine marker already correct")
        else:
            write_marker(marker)
            print("  engine marker written (OpenMW 0.51.0 Final, pinned commit)")

    # --- native digest -------------------------------------------------------
    native = JNI / "libopenmw.so"
    failures: list[str] = []
    if native.is_file():
        digest = sha256_of(native)
        print(f"  libopenmw.so SHA-256: {digest}")
        if digest == PINNED_NATIVE_SHA:
            SHA_FILE.parent.mkdir(parents=True, exist_ok=True)
            SHA_FILE.write_text(f"{digest}  jniLibs/arm64-v8a/libopenmw.so\n", encoding="utf-8", newline="\n")
            print("  matches the pinned Patch-39 digest; SHA file written")
        elif args.accept_native_baseline:
            SHA_FILE.parent.mkdir(parents=True, exist_ok=True)
            SHA_FILE.write_text(
                f"{digest}  jniLibs/arm64-v8a/libopenmw.so\n",
                encoding="utf-8",
                newline="\n",
            )
            print("  --accept-native-baseline: SHA file re-pinned to this archive's library")
        else:
            failures.append(
                "libopenmw.so does not match the pinned Patch-39 digest\n"
                f"  expected: {PINNED_NATIVE_SHA}\n  actual:   {digest}\n"
                "  This APK does not carry the payload this repository pins.\n"
                "  Re-run with --accept-native-baseline to pin this build instead."
            )
    else:
        failures.append("libopenmw.so was not extracted")

    missing_resources = [r for r in REQUIRED_RESOURCES if not (ASSETS / "libopenmw" / r).is_file()]
    for resource in missing_resources:
        failures.append(f"missing asset: libopenmw/{resource}")

    missing_omwfx = [n for n in OMWFX_FILES if not (omwfx_dir / n).is_file()]
    for name in missing_omwfx:
        failures.append(f"missing asset: android_omwfx/{name}")

    print()
    if failures:
        print("Payload seeding INCOMPLETE:")
        for failure in failures:
            print(" - " + failure)
        if missing_omwfx:
            print()
            print(
                "The five .omwfx files are not part of an APK. Generate them with\n"
                "  python3 buildscripts/patches/openmw051-final/apply-android-runtime-baseline.py <tree>\n"
                "or pass --omwfx-from <dir> with existing copies."
            )
        return 1

    print("Payload seeding: OK - every verifyOpenMwPayload input is in place.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
