# Building the APK on GitHub

`app/build.gradle` refuses to build until the OpenMW 0.51.0 native payload is
present (`verifyOpenMwPayload` runs from `preBuild`). That payload — the engine
`libopenmw.so`, its companion libraries and the engine resources — is
**generated, never committed**.

## Default path: reuse the reference APK

The payload is the same as the one inside a released APK of this version.
`0.51.0-14` of `Andiweli/OpenMW-Android` (the upstream of this fork, matching
the `versionCode 5114` the gate demands) is downloaded on the runner, the
payload is extracted out of it, and the APK is reassembled with the Java/Kotlin
UI, resources and assets of this branch:

```
Actions → Android build → Run workflow     (payload_source = upstream)
```

`tools/seed-openmw-payload.py` extracts it and re-applies every check the Gradle
gate performs. Since the reference APK cannot carry the local Patch-39 digest,
the workflow passes `--accept-native-baseline`, which re-pins
`buildscripts/openmw-051-patch39-libopenmw.sha256` to the digest of the
extracted `libopenmw.so` (printed in the log). Switch that input off to require
the exact local build instead.

| Job | What it needs | What it does |
|---|---|---|
| **Kotlin compile check** | nothing | compiles every Kotlin/Java source of `mainlineDebug` |
| **Build APK** | a payload archive | seeds the payload, re-verifies it, runs `assembleMainlineDebug`, uploads `openmw-android-apk` |

The compile job passes `-x verifyOpenMwPayload -x removeRetiredWaterAssets`
because `preBuild` depends on the payload gate; the APK job runs the real
`assembleMainlineDebug` and therefore keeps the gate enforced. If Gradle fails,
the first errors are printed as `::error::` annotations on the run page, so they
are readable without the job log.

## Downloading the APK

Every successful run attaches `openmw-android-apk` to the run's **Artifacts**
section (download requires a GitHub login). For a link that works anywhere, run
the workflow with `publish_release = true`: the APK is then attached to a
`apk-<version>-<run>` release and can be fetched from

```
https://github.com/Stlucifer15t/OpenMW-Android/releases/latest
```

The build is debug-signed (`assembleMainlineDebug`), arm64-v8a only.

## Other payload source: a release asset in this repo

Set `payload_source = release-asset` and attach `openmw-051-payload.zip` to a
release in this repository (produced by the steps below).

## Producing the payload by hand (Windows + WSL + NDK)

```powershell
cd source
powershell -ExecutionPolicy Bypass -File tools\build-openmw-051-runtime.ps1
# ... then whatever native post-processing your gate expects:
powershell -ExecutionPolicy Bypass -File tools\apply-openmw-051-patch39-release-log-cleanup.ps1
powershell -ExecutionPolicy Bypass -File tools\pack-openmw-payload.ps1
```

`pack-openmw-payload.ps1` writes `source\out\openmw-051-payload.zip` containing

```
app/src/main/jniLibs/arm64-v8a/{libopenmw,libopenal,libSDL2,libGL,libcollada-dom2.5-dp,libc++_shared}.so
app/src/main/assets/libopenmw/**
app/src/main/assets/android_omwfx/*.omwfx
buildscripts/openmw-051-patch39-libopenmw.sha256
```

with paths relative to `source\`, which is exactly where the workflow extracts it.

## Publishing it as a release asset

```bash
gh release create payload-5114 --title "OpenMW 0.51.0-14 native payload" \
  source/out/openmw-051-payload.zip
```

The file must keep the name `openmw-051-payload.zip` (or pass a different name
through the `payload_asset` input when running the workflow manually).

## Running the workflow

Push any commit, open a pull request, or run it by hand:

```
Actions → Android build → Run workflow
```

The finished APK is attached to the run as the `openmw-android-apk` artifact.

## Checking a payload by hand

```bash
python3 source/tools/verify-openmw-payload.py          # exit 1 when incomplete
python3 source/tools/verify-openmw-payload.py --report # report only
```

It prints every missing or stale item in exactly the form the Gradle gate
reports it, including the pinned engine marker
(`OpenMW 0.51.0 Final / commit=f4bec414…`) and the `libopenmw.so` SHA-256 from
`buildscripts/openmw-051-patch39-libopenmw.sha256`.
