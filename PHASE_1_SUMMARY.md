## Phase 1 Review (carried into Phase 2 prompt)

- **Built:** HeliBoard tag v3.9 (commit `9893224`) vendored into `app/`. Package renamed across 350+ files; both `fdroid` and `play` debug APKs build clean (`com.aikeyboard.app.debug` and `com.aikeyboard.app.play.debug`). Lint passes via baseline. compileSdk/targetSdk = 36, minSdk = 29. Distinct adaptive launcher icon ("AIK" lettermark) so it coexists with upstream HeliBoard.

- **Smoke-tested on Pixel 6 Pro (`raven`, device id `1B101FDEE0006U`):** both flavors install side-by-side, both appear in IME picker as distinct entries, both type cleanly. Logcat during a minute of typing in each flavor shows no `FATAL` / `AndroidRuntime` exceptions referencing `com.aikeyboard.app` — only harmless `D ActivityThread: Package [...] reported as REPLACED` lines emitted by other system processes during `adb install -r`. Architecture invariant verified via `pm list packages` — two distinct applicationIds confirmed.

- **Deviations from architecture / Phase 1 prompt:**
  - **AGP 8.13.2** kept (HeliBoard's pinned version, supports compileSdk 36 since AGP 8.9+). Not bumped to AGP 9.2.0 — rationale: AGP 9 is a major version with removed APIs that HeliBoard's build hasn't been validated against; staying within the 8.x line keeps Phase 1 risk-bounded.
  - **Kotlin 2.2.21** kept (HeliBoard's pinned version; ≥ 2.0.x ✓). Same rationale.
  - **`namespace = "com.aikeyboard.app.latin"`** (not bare `com.aikeyboard.app`). HeliBoard's `namespace` held the source-package prefix for relative class-name resolution in the manifest. Setting it to the bare value would have required rewriting every `<service>` / `<activity>` partial name. `applicationId` is `com.aikeyboard.app` as specified. **Implication for Phase 2:** new modules (`AiSettingsActivity`, `AiClient`, etc.) should live in sibling packages — `com.aikeyboard.app.ai.*`, `com.aikeyboard.app.bridge.*`, `com.aikeyboard.app.a11y.*` — not under `.latin.*` (HeliBoard's territory).
  - **`org.gradle.java.home`** is a hardcoded macOS Homebrew path (`/opt/homebrew/opt/openjdk@21/...`). System JDK 25 is too new for Gradle 8.14. Phase 12 will replace this with Foojay-managed daemon-JVM auto-provisioning.
  - **`lint-baseline.xml`** absorbs 64 inherited `MissingTranslation` errors + tail (`ObsoleteSdkInt`, `UnusedResources`, etc.) escalated by the SDK 36 bump. All upstream HeliBoard's, none introduced by Phase 1. Phase 12 will prune.
  - **FileProvider authority pattern modernized.** HeliBoard hardcoded the provider authority into a string resource overridden per buildType (`src/debug/res/values/gesture_data.xml`), which produced `INSTALL_FAILED_CONFLICTING_PROVIDER` when both flavors were installed side-by-side because flavors share buildType-only overrides. Switched to manifest placeholder `${applicationId}.provider` plus `BuildConfig.APPLICATION_ID + ".provider"` in `Share.kt`. Deleted the now-unused `gesture_data_provider_authority` string resource and its two buildType overrides. **Implication for Phase 9:** sticker `FileProvider` should follow the same `${applicationId}.<authority>` pattern, not HeliBoard's older buildType-resource-override style.
  - **IME label distinguished per variant.** Added `app/src/playDebug/res/values/strings.xml` overriding `english_ime_name` to "AI Keyboard Play debug". Without this both flavors+debug ended up with `src/debug/`'s "AI Keyboard debug" since AGP source-set merging order is `main → flavor → buildType → variant` — buildType beats flavor. Variant-level (`<flavor><BuildType>`) source sets override both. Future buildtypes (`playRelease`, `playNouserlib`) will need their own labels if used for distribution; not blocking now.

- **Carried issues:**
  - 29 case-insensitive `Helium314` matches remain (case-sensitive `helium314` is **zero**). All are GitHub URLs / provenance / data files — correct upstream attribution that must stay. PHASE_REVIEW.md updated to require case-sensitive grep going forward.
  - Localized `strings.xml` files in ~10 languages still contain the brand string "HeliBoard" inside translated phrasings. Canonical English is now "AI Keyboard"; localized rebrand can wait.
  - HeliBoard's older mipmap PNGs are unused on minSdk 29+ but still in the APK. Phase 12 polish can prune.
  - **Gesture / swipe typing is non-functional** — HeliBoard cannot redistribute Google's proprietary glide-typing library. Users supply their own `.so` file via HeliBoard's settings. NOT a Phase 1 regression; expected upstream behavior. PHASE_REVIEW.md's Phase 1 smoke test updated to note this.
  - Deprecation warnings from Gradle 8.14 (incompatibility with Gradle 9.0): tied to AGP 8.13.2; resolves on AGP 9.x bump.
  - C++ deprecation warnings in HeliBoard's JNI dictionary engine — pre-existing upstream, harmless.

- **Touchpoints for Phase 2:**
  - `app/app/src/main/java/com/aikeyboard/app/latin/LatinIME.java` — the IME service entry point; this is where the "command row" gets added above the existing key area.
  - `app/app/src/main/java/com/aikeyboard/app/keyboard/KeyboardActionListenerImpl.kt` — wires UI taps to IME actions; new command-row buttons hook here.
  - `app/app/src/main/res/xml/method.xml` — IME meta-data; HeliBoard's `SettingsActivity` is bound here (preserve untouched per ARCHITECTURE.md).
  - `app/app/src/main/AndroidManifest.xml` — register the new `AiSettingsActivity` here as a separate activity.
  - `app/app/build.gradle.kts` — Compose, Material3, navigation-compose already declared (see `app/COMPOSE_USAGE.md`); no new deps needed for Phase 2.
  - `app/app/src/main/java/com/aikeyboard/app/settings/` — HeliBoard's Compose settings live here; **do not modify**. Phase 2's `AiSettingsActivity` is a parallel activity, not an extension.

- **Open questions for the human reviewer (resolved during smoke test):**
  1. ~~AGP/Kotlin version: stay on HeliBoard's 8.13.2 / 2.2.21~~ — **Resolved:** stay on HeliBoard's pinned versions; bump deferred.
  2. ~~`namespace = "com.aikeyboard.app.latin"`~~ — **Resolved:** keep, with Phase 2+ additions in sibling packages (`.ai.*`, `.bridge.*`, `.a11y.*`).
  3. ~~Hardcoded Homebrew JDK 21 path~~ — **Resolved:** acceptable for now; Phase 12 will switch to Foojay auto-provisioning.
