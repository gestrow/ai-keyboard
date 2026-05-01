# Upstream provenance

- Source: https://github.com/Helium314/HeliBoard
- Tag: v3.9
- Commit: 98932242f54b3da14618eb81a90cd9f8eda10f84
- Imported: 2026-04-28
- License: GPL-3.0 (preserved in [LICENSE](LICENSE); also LICENSE-Apache-2.0, LICENSE-CC-BY-SA-4.0)

## Local changes vs upstream

Phase 1 changes are intentionally minimal and structural:

- Package rename: every reference to upstream's two-segment package prefix (350+ files) → `com.aikeyboard.app.*`. Source directories `app/src/{main,test}/java/<old prefix>/...` moved to `.../java/com/aikeyboard/app/...`. `applicationId = "com.aikeyboard.app"`; `namespace = "com.aikeyboard.app.latin"` (preserves HeliBoard's two-level namespace pattern under our prefix so manifest partial class names continue to resolve). See the Phase 1 commit diff for the exact upstream package string.
- `compileSdk` 35 → 36, `targetSdk` 35 → 36, `minSdk` 21 → 29 (per ARCHITECTURE.md decision #4).
- Two product flavors added: `fdroid` (`ENABLE_A11Y=true`) and `play` (`ENABLE_A11Y=false`, `applicationIdSuffix=".play"`). No fdroid-only sources yet — Phase 7 adds those.
- Display name "HeliBoard" → "AI Keyboard" (canonical English string in `donottranslate.xml`; debug-flavor strings updated). Localized translations of secondary strings (`ime_settings`, `spell_checker_service_name`) still mention HeliBoard in some locales — left for translation contributors.
- Distinct adaptive launcher icon (white "AIK" lettermark on teal→indigo gradient) so the app coexists with upstream HeliBoard on a device.
- `kotlin { jvmToolchain(21) }` added; Foojay toolchain resolver registered in `settings.gradle`. `org.gradle.java.home` pinned to `/opt/homebrew/opt/openjdk@21/...` so the Gradle daemon runs on JDK 21 (the user has JDK 25 system-wide, which Gradle 8.14 cannot run on).
- `gradle.properties`: added `org.gradle.parallel=true`, `org.gradle.caching=true`, `android.nonTransitiveRClass=true`, `android.enableR8.fullMode=false` (per Phase 1 reproducibility-flag spec).
- `lint-baseline.xml` generated to absorb 64 inherited `MissingTranslation` errors and a long tail of other upstream lint findings (see issue counts in baseline file). The escalation appears to be triggered by the `compileSdk = 35 → 36` bump; HeliBoard upstream did not need a baseline because their stricter lint config matched their target SDK.

## Untouched (preserved as upstream)

- HeliBoard's `SettingsActivity` (Views + `Preference` framework). Phase 2 will add a parallel `AiSettingsActivity` (Compose) for AI-feature surface area.
- Keyboard rendering, layouts, input logic, autocorrect, gesture typing, multi-language support.
- Native JNI dictionary engine.
- The `com.android.inputmethod.*` AOSP packages (Apache-2.0 vendor code; only their cross-imports of upstream's package prefix were updated by the package rename).

## Phase 12 reproducible-build TODOs

Phase 12 will close these out:

- Decide whether to switch to Foojay-managed Gradle daemon JDK so contributors don't need a manual brew install. Currently `org.gradle.java.home` is hardcoded to a Homebrew Apple Silicon path — fine for the primary developer, fragile for CI / other contributors.
- Verify byte-identical output between two machines for `assembleFdroidRelease` (the spec's reproducibility-verified release).
- Possibly add `android.experimental.enableNewResourceShrinker.preciseShrinking=true` if it improves determinism without breaking minified builds.
- Pin `ndkVersion` (currently `28.0.13004108`) and confirm NDK toolchain is reproducible across platforms (Phase 1 builds were on darwin-x86_64 NDK toolchain).
