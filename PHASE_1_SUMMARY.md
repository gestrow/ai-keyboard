## Phase 1 Review (carried into Phase 2 prompt)

- **Built:** HeliBoard tag v3.9 (commit `9893224`) vendored into `app/`. Package renamed across 350+ files; both `fdroid` and `play` debug APKs build clean (`com.aikeyboard.app.debug` and `com.aikeyboard.app.play.debug`, both label "AI Keyboard debug"). Lint passes via baseline. compileSdk/targetSdk = 36, minSdk = 29. Distinct adaptive launcher icon ("AIK" lettermark) so it coexists with upstream HeliBoard. No real device available for adb smoke test — manual install must happen on the user's hardware.

- **Deviations from architecture / Phase 1 prompt:**
  - **AGP 8.13.2** kept (HeliBoard's pinned version, supports compileSdk 36 since AGP 8.9+). Not bumped to AGP 9.2.0 — rationale: AGP 9 is a major version with removed APIs that HeliBoard's build hasn't been validated against; staying within the 8.x line keeps Phase 1 risk-bounded. **Open question for human reviewer:** acceptable, or bump to AGP 9.2.0 + Gradle 9.4.1 + Kotlin 2.3.21 in a follow-up?
  - **Kotlin 2.2.21** kept (HeliBoard's pinned version; ≥ 2.0.x ✓). Same rationale.
  - **`namespace = "com.aikeyboard.app.latin"`** (not `com.aikeyboard.app` as the prompt's bullet suggested). HeliBoard's `applicationId` and `namespace` were two different strings; the namespace held the full source-package prefix so manifest partial class names like `.spellcheck.X` could resolve. Setting namespace to the bare `com.aikeyboard.app` would have required rewriting every `<service>` / `<activity>` partial name in the manifest. Setting it to `com.aikeyboard.app.latin` matches HeliBoard's working pattern under our prefix. `applicationId` is `com.aikeyboard.app` as specified.
  - **`org.gradle.java.home`** is a hardcoded macOS Homebrew path (`/opt/homebrew/opt/openjdk@21/...`). The user has JDK 25 system-wide which Gradle 8.14 cannot run on; JDK 21 was installed via `brew install openjdk@21`. CI / cross-platform contributors will need their own JDK 21 path. Phase 12 will replace this with Foojay-managed daemon-JVM auto-provisioning.
  - **`lint-baseline.xml`** added because the SDK 35 → 36 bump escalated 64 inherited `MissingTranslation` errors plus a long tail of `ObsoleteSdkInt` / `UnusedResources` / `Untranslatable` findings to error severity. None of these were introduced by Phase 1 changes; all are upstream HeliBoard's. Phase 12 will prune the baseline.

- **Carried issues:**
  - 29 case-insensitive `Helium314` matches remain (case-sensitive `helium314` is **zero** — package rename is complete). The remaining matches are: GitHub issue URLs in source-code comments (historical traceability), `app/UPSTREAM.md` provenance link to upstream repo, `app/layouts.md` wiki link, and one entry in `app/app/src/main/assets/known_dict_hashes.txt` (data file). Removing these would destroy attribution / commit metadata; the spec's literal `grep -ri` would flag them but they are not code references.
  - Localized `strings.xml` files in ~10 languages still contain the brand string "HeliBoard" inside translated phrasings of `ime_settings`, `spell_checker_service_name`, etc. Canonical English (`donottranslate.xml::english_ime_name`) is now "AI Keyboard"; localized rebrand can wait for translation contributions.
  - HeliBoard's older mipmap PNGs (`ic_launcher.png` in mdpi/hdpi/.../xxxhdpi) are still HeliBoard's — they're unused on minSdk 29+ devices (the adaptive icon takes over) but are dead weight in the APK. Phase 12 polish can prune.
  - Deprecation warnings emitted by Gradle 8.14: "Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0." Tied to AGP 8.13.2's plugin code; will resolve when we bump to AGP 9.x.
  - C++ deprecation warnings in HeliBoard's JNI dictionary engine (DISALLOW_ASSIGNMENT_OPERATOR + implicit copy ctor) — pre-existing upstream, harmless, not tracked.

- **Touchpoints for Phase 2:**
  - `app/app/src/main/java/com/aikeyboard/app/latin/LatinIME.java` — the IME service entry point; this is where the "command row" gets added above the existing key area.
  - `app/app/src/main/java/com/aikeyboard/app/keyboard/KeyboardActionListenerImpl.kt` — wires UI taps to IME actions; new command-row buttons hook here.
  - `app/app/src/main/res/xml/method.xml` — IME meta-data; HeliBoard's `SettingsActivity` is bound here (preserve untouched per ARCHITECTURE.md).
  - `app/app/src/main/AndroidManifest.xml` — register the new `AiSettingsActivity` here as a separate activity.
  - `app/app/build.gradle.kts` — Compose, Material3, navigation-compose already declared (see `app/COMPOSE_USAGE.md`); no new deps needed for Phase 2.
  - `app/app/src/main/java/com/aikeyboard/app/settings/` — HeliBoard's Compose settings live here; **do not modify**. Phase 2's `AiSettingsActivity` is a parallel activity, not an extension.

- **Open questions for the human reviewer:**
  1. AGP/Kotlin version: stay on HeliBoard's 8.13.2 / 2.2.21, or bump to 9.2.0 / 2.3.21 (and Gradle 9.4.1)? See "Deviations" above.
  2. `namespace = "com.aikeyboard.app.latin"` (preserves HeliBoard's pattern) vs. `"com.aikeyboard.app"` (matches Phase 1 prompt literally, requires manifest rewrite). I picked the former.
  3. The hardcoded Homebrew JDK 21 path in `gradle.properties` is brittle for non-macOS contributors; OK to defer to Phase 12 or fix sooner?
