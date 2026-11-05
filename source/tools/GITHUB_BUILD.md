# Building the APK on GitHub

`app/build.gradle` refuses to build until the OpenMW 0.51.0 native payload is
present (`verifyOpenMwPayload` runs from `preBuild`). That payload — the engine
`libopenmw.so` plus the engine resources — is **generated, never committed**:
it is several hundred megabytes and is produced by `tools/build-openmw-051-runtime.ps1`
(WSL + Android NDK, tens of minutes to a couple of hours).

`.github/workflows/android-build.yml` therefore works in two modes:

| Job | What it needs | What it does |
|---|---|---|
| **Kotlin compile check** | nothing | compiles every Kotlin/Java source of `mainlineDebug` |
| **Native payload status** | nothing | checks whether the payload release asset exists |
| **Build APK** | the payload zip | unpacks it, re-verifies it, runs `assembleMainlineDebug`, uploads the APK |

If the payload asset is missing, the APK job is skipped with a warning rather
than failing on the Gradle gate.

## 1. Produce the payload (once, on Windows)

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

## 2. Publish it as a release asset

```bash
gh release create payload-5114 --title "OpenMW 0.51.0-14 native payload" \
  source/out/openmw-051-payload.zip
```

The file must keep the name `openmw-051-payload.zip` (or pass a different name
through the `payload_asset` input when running the workflow manually).

## 3. Run the workflow

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
