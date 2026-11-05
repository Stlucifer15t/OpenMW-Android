#!/usr/bin/env python3
"""Re-run the app/build.gradle build gates locally, without Gradle.

`source/app/build.gradle` fails the build on a long list of payload, launcher,
localization and OMWFX invariants (`verifyOpenMwPayload` and the tasks that run
from `preBuild`). Reproducing them here means a CI round-trip is not needed to
find the next violation.

This mirrors the checks; it does not replace Gradle. Exit code 1 means the real
build would fail at one of these gates.

Usage:
    python3 tools/check-build-gates.py
    python3 tools/check-build-gates.py --verbose
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

SOURCE_DIR = Path(__file__).resolve().parents[1]
APP = SOURCE_DIR / "app"
ASSETS = APP / "src/main/assets"
RES = APP / "src/main/res"

problems: list[str] = []
checked = 0


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.is_file() else ""


def need(condition: bool, message: str) -> None:
    global checked
    checked += 1
    if not condition:
        problems.append(message)


def need_contains(text: str, needle: str, message: str) -> None:
    need(needle in text, message)


def need_not_contains(text: str, needle: str, message: str) -> None:
    need(needle not in text, message)


def string_keys(path: Path) -> set[str]:
    return set(re.findall(r'<string\s+name="([^"]+)"', read(path)))


def array_counts(path: Path) -> dict[str, int]:
    text = read(path)
    counts: dict[str, int] = {}
    for match in re.finditer(r'(?s)<string-array\s+name="([^"]+)"[^>]*>(.*?)</string-array>', text):
        counts[match.group(1)] = len(re.findall(r"<item(?:\s[^>]*)?>", match.group(2)))
    return counts


def check_payload() -> None:
    gradle = read(APP / "build.gradle")
    block = re.search(r"def requiredOpenMwPayload\s*=\s*\[(.*?)\n\]", gradle, re.S)
    need(block is not None, "requiredOpenMwPayload block not found in app/build.gradle")
    if block:
        for entry in re.findall(r"file\('([^']+)'\)", block.group(1)):
            need((APP / entry).is_file(), f"payload file missing: {entry}")

    marker = read(ASSETS / "libopenmw/openmw/openmw-engine-version.txt").strip()
    need(
        marker == "OpenMW 0.51.0 Final\ncommit=f4bec41444214a7903bebd178389ca22ca13f646",
        f"engine marker wrong: {marker.replace(chr(10), ' | ')}",
    )
    resources = read(ASSETS / "libopenmw/resources/version").strip()
    need(resources.startswith("0.51.0"), f"resources/version is not 0.51.0: {resources}")


def check_launcher() -> None:
    main = read(APP / "src/main/java/ui/activity/MainActivity.kt")
    for token in (
        '"shaders/compatibility/shadows_vertex.glsl"',
        "OPENMW_ANDROID_051_GRAZING_SHADOW_PAIR_SYNC_V2",
        "migrateOpenMw051SettingsPreferences()",
        "private fun controllerTriggerThresholds(): Pair<Int, Int>",
        "private fun applyOpenMw051LauncherSettings()",
        "private fun applyOpenMw051RuntimeSettings()",
        'OMWFX_RECOMMENDED_CHAIN.joinToString(",")',
        '"controller trigger press" to triggerPress.toString()',
        '"controller trigger release" to triggerRelease.toString()',
        '"point lighting" to if (groundcoverPointLighting) "true" else "false"',
        '"pref_omw051_controller_trigger_thresholds"',
        '"gs_groundcover_point_lighting"',
        '"gs_groundcover_enabled"',
        '"gs_groundcover_density"',
        '"gs_groundcover_rendering_distance"',
        'text = "ARM64 \\u2022 v${BuildConfig.VERSION_NAME}"',
        "OpenMW 0.51 Android release runtime:",
        "setContentView(createOpaqueLoadingPanel())",
        "restoreOriginalLauncherWindowBackground()",
    ):
        need_contains(main, token, f"MainActivity.kt lost: {token}")
    need_not_contains(main, '"paging" to if(prefs.getString("gs_groundcover_handling"', "MainActivity uses retired groundcover paging")
    need_not_contains(main, '"timeplayed" to', 'MainActivity reintroduced "timeplayed"')
    need_not_contains(main, "Gate H5a", "MainActivity still logs the Gate H5a diagnostic")

    styles = read(RES / "values/styles.xml")
    for token in (
        '<item name="android:windowBackground">',
        '<item name="android:colorBackground">',
        '<item name="android:windowIsTranslucent">',
    ):
        need_not_contains(styles, token, f"styles.xml reintroduced {token}")

    fragment = read(APP / "src/main/java/ui/fragments/FragmentSettings.kt")
    for token in (
        "prepareControllerTriggerPreference()",
        "R.string.controller_trigger_custom_values",
        'sharedPref.getString("pref_shadersDir_v2", "") == "modified"',
        'sharedPref.getInt(getString(R.string.theme), 0) == 3',
    ):
        need_contains(fragment, token, f"FragmentSettings.kt lost: {token}")

    game = read(APP / "src/main/java/ui/activity/GameActivity.kt")
    for token in (
        "OPENMW_ANDROID_051_LAUNCHER_AUDIT_V1",
        'Os.setenv("OPENMW_GLES_VERSION", "2", true)',
        'Os.setenv("LIBGL_ES", "2", true)',
    ):
        need_contains(game, token, f"GameActivity.kt lost: {token}")
    need_not_contains(game, 'graphicsLibrary != "gles1"', 'GameActivity restored the gles1 special case')

    settings_xml = read(RES / "xml/settings.xml")
    need_contains(settings_xml, 'android:key="pref_controls"', "settings.xml lost pref_controls")
    need_contains(settings_xml, 'android:key="pref_hide_controls"', "settings.xml lost pref_hide_controls")
    need_not_contains(settings_xml, 'android:key="pref_graphicsLibrary_v2"', "settings.xml reintroduced pref_graphicsLibrary_v2")
    order = [settings_xml.find(k) for k in ("pref_omw051_controller_trigger_thresholds", "pref_controls", "pref_hide_controls")]
    need(all(p >= 0 for p in order) and order == sorted(order),
         "settings.xml must keep pref_omw051_controller_trigger_thresholds < pref_controls < pref_hide_controls")

    engine_xml = read(RES / "xml/gs_engine.xml")
    for key in ("gs_groundcover_density", "gs_groundcover_rendering_distance", "gs_groundcover_point_lighting"):
        need_contains(engine_xml, f'android:key="{key}"', f"gs_engine.xml lost {key}")
    need_not_contains(engine_xml, 'android:key="gs_groundcover_handling"', "gs_engine.xml reintroduced gs_groundcover_handling")

    strings = read(RES / "values/strings.xml")
    for preset in ("30720,26624", "24576,20480", "16384,12288"):
        need_contains(strings, "<item>" + preset + "</item>", f"strings.xml lost controller-trigger preset {preset}")
    need_contains(
        strings,
        '<string name="pref_nohighp_summary">Uses medium instead of high precision; available only with the Modified shader preset</string>',
        "strings.xml changed pref_nohighp_summary",
    )


def check_localization() -> None:
    base = string_keys(RES / "values/strings.xml")
    base_prefs = string_keys(RES / "values/launcher_preferences.xml")
    base_arrays = array_counts(RES / "values/strings.xml")
    required_arrays = [
        "pref_encoding_entries",
        "gs_factor_strength_into_hand_to_hand_combat_entries",
        "gs_show_owned_entries",
        "pref_omw051_controller_trigger_sensitivity_entries",
        "pref_textureShrinking_entries",
        "pref_shadersDir_entries",
        "pref_mouse_mode_entries",
        "gs_shadow_quality_entries",
        "gs_shadow_distance_entries",
    ]
    sc_base = string_keys(SOURCE_DIR / "storagechooser/src/main/res/values/strings.xml")

    for locale in ("de", "fr", "pl", "ru", "sv"):
        general = RES / f"values-{locale}/strings.xml"
        prefs = RES / f"values-{locale}/launcher_preferences.xml"
        need(general.is_file() and prefs.is_file(), f"locale {locale} is missing strings.xml/launcher_preferences.xml")
        missing = sorted(base - string_keys(general))
        need(not missing, f"locale {locale} missing general keys: {', '.join(missing)}")
        missing_prefs = sorted(base_prefs - string_keys(prefs))
        need(not missing_prefs, f"locale {locale} missing preference keys: {', '.join(missing_prefs)}")
        counts = array_counts(general)
        bad = [n for n in required_arrays if counts.get(n) != base_arrays.get(n)]
        need(not bad, f"locale {locale} invalid arrays: {', '.join(bad)}")
        sc_locale = string_keys(SOURCE_DIR / f"storagechooser/src/main/res/values-{locale}/strings.xml")
        missing_sc = sorted(sc_base - sc_locale)
        need(not missing_sc, f"locale {locale} missing StorageChooser keys: {', '.join(missing_sc)}")


def check_omwfx() -> None:
    shaders = {
        "android_omwfx/gateh_bloom051.omwfx": [
            "uniform_float uThreshold {\n    default = 0.30;",
            "uniform_float uSkyFactor {\n    default = 0.60;",
            "uniform_float uRadius {\n    default = 0.55;",
            "uniform_float uStrength {\n    default = 0.35;",
        ],
        "android_omwfx/lensflare_android_051_rayocc.omwfx": [
            'version = "2.1-051-rayocc";',
        ],
        "android_omwfx/godrays_android_051_depthfixed_vivid.omwfx": [
            "for (int i = 0; i < 16; i += 1)",
            'version = "3.4-051-depthfixed-vivid-softglare";',
        ],
        "android_omwfx/wetworld_android_051_weather.omwfx": [
            'version = "5.1-051-weather-puddles-mali-safe";',
        ],
    }
    forbidden = {
        "android_omwfx/lensflare_android_051_rayocc.omwfx": ["omw_GetLinearDepth(", "omw_GetDepth(", "Disable_SunGlare"],
        "android_omwfx/godrays_android_051_depthfixed_vivid.omwfx": ["omw_GetLinearDepth(", "Disable_SunGlare"],
        "android_omwfx/rainlens_android_051_v12_dense.omwfx": ["rainHash3051(", "teardropLayer051(", "omw_GetDepth(", "omw_GetLinearDepth("],
        "android_omwfx/wetworld_android_051_weather.omwfx": ["dFdx(", "dFdy(", "sin("],
    }
    for relative, tokens in shaders.items():
        text = read(ASSETS / relative)
        need(bool(text), f"missing shader: {relative}")
        for token in tokens:
            need_contains(text, token, f"{relative} lost: {token[:60]}")
    for relative, tokens in forbidden.items():
        text = read(ASSETS / relative)
        for token in tokens:
            need_not_contains(text, token, f"{relative} contains forbidden: {token}")

    water = read(ASSETS / "libopenmw/resources/shaders/compatibility/water.frag")
    for token in (
        "OPENMW_ANDROID_051_WETWORLD_WATER_MASK",
        "#if @wetWorldWaterMask",
        "gl_FragData[0].a = 0.0;",
        "rainCombined(position.xy/1000.0, waterTimer)",
    ):
        need_contains(water, token, f"water.frag lost: {token}")

    patcher = read(SOURCE_DIR / "buildscripts/patches/openmw051-final/apply-android-runtime-baseline.py")
    for token in (
        'defineMap["wetWorldWaterMask"] = "1";',
        "release_log_marker = 'OpenMW 0.51 Android renderer:'",
        "CPU sun-occlusion diagnostic logging was not removed",
        "repetitive shadow diagnostic was not removed",
    ):
        need_contains(patcher, token, f"android runtime patcher lost: {token}")


def check_shadow_shaders() -> None:
    shadow_vert = read(ASSETS / "libopenmw/resources/shaders/compatibility/shadows_vertex.glsl")
    shadow_frag = read(ASSETS / "libopenmw/resources/shaders/compatibility/shadows_fragment.glsl")
    shadow_cast = read(ASSETS / "libopenmw/resources/shaders/compatibility/shadowcasting.vert")
    debug_vert = read(ASSETS / "libopenmw/resources/shaders/compatibility/debug.vert")
    debug_frag = read(ASSETS / "libopenmw/resources/shaders/compatibility/debug.frag")
    for name, text in (("shadows_vertex.glsl", shadow_vert), ("shadows_fragment.glsl", shadow_frag)):
        for token in (
            "OPENMW_ANDROID_051_GRAZING_SHADOW_FIX_V1",
            "varying vec2 shadowReceiverPlaneSlope@shadow_texture_unit_index;",
        ):
            need_contains(text, token, f"{name} lost: {token}")
    for name, text, token in (
        ("shadowcasting.vert", shadow_cast, "uniform bool useDiffuseMapForShadowAlpha ="),
        ("shadowcasting.vert", shadow_cast, "uniform bool alphaTestShadows ="),
        ("debug.vert", debug_vert, "uniform bool useAdvancedShader ="),
        ("debug.frag", debug_frag, "uniform bool useAdvancedShader ="),
    ):
        need_not_contains(text, token, f"{name} contains a GL4ES-breaking uniform initializer: {token}")
    for token in ("OPENMW_ANDROID_051_GLES2_NATIVE_SHADOW_CLIPPING",):
        need_contains(shadow_cast, token, f"shadowcasting.vert lost: {token}")
    need_not_contains(shadow_frag, "uniform sampler2DShadow", "shadows_fragment.glsl must not use sampler2DShadow")
    need_not_contains(shadow_frag, "shadow2DProj(", "shadows_fragment.glsl must not use shadow2DProj")
    need_not_contains(shadow_cast, "gl_Position.z = clamp(gl_Position.z, -gl_Position.w, gl_Position.w);",
                      "shadowcasting.vert restored the manual z clamp")


def main() -> int:
    check_payload()
    check_launcher()
    check_localization()
    check_omwfx()
    check_shadow_shaders()

    print(f"checked {checked} build-gate conditions")
    if problems:
        print(f"\n{len(problems)} problem(s) - the Gradle build would fail:\n")
        for problem in problems:
            print(" - " + problem)
        return 1
    print("all build gates satisfied")
    return 0


if __name__ == "__main__":
    sys.exit(main())
