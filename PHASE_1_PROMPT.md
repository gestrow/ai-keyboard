# Phase 1 Prompt — Scaffold + HeliBoard fork + Gradle flavors

> **How to use this prompt:** open a new Claude Code session in `/Users/kacper/SDK/apk-dev/ai-keyboard/`. Tell Claude Code: *"Read `ARCHITECTURE.md` and `PHASE_REVIEW.md`, then execute the prompt in `PHASE_1_PROMPT.md` exactly."* Do not paste this prompt into a chat that already has a long history; start fresh.

---

You are working in `/Users/kacper/SDK/apk-dev/ai-keyboard/`. The repo currently contains only `ARCHITECTURE.md` and `PHASE_REVIEW.md`. Read both before doing anything else; they are authoritative for all decisions.

Your job in this phase is **only** scaffolding. No AI code, no networking, no UI customization beyond what's needed for the build to be obviously distinguishable from upstream HeliBoard. Stop when the acceptance criteria for Phase 1 in `PHASE_REVIEW.md` are met.

## Tasks

### 1. Repo structure

Create top-level directories and stubs:
```
ai-keyboard/
├── app/                    # Will hold the HeliBoard fork
├── bridge/
│   └── README.md           # Just a placeholder noting "Phase 4 deliverable"
├── setup/
│   └── README.md           # Just a placeholder noting "Phase 5 deliverable"
├── ARCHITECTURE.md         # already exists, do not modify
├── PHASE_REVIEW.md         # already exists, do not modify
├── README.md               # write this in step 5
└── .gitignore              # write this in step 5
```

### 2. Initialize git

If not already initialized, `git init`. Create branch `phase/01-scaffold` and do all phase-1 work on this branch. Do not commit to `main`.

### 3. Vendor HeliBoard

Clone HeliBoard at a pinned tag or commit into `app/`. Use the latest stable release tag from https://github.com/Helium314/HeliBoard/releases.

Steps:
- `git clone --depth 1 --branch <latest-stable-tag> https://github.com/Helium314/HeliBoard.git /tmp/heliboard-import`
- Copy contents into `app/` (not as a submodule — we want to be able to modify it)
- Remove `/tmp/heliboard-import/.git` before copying
- Record the upstream version in `app/UPSTREAM.md`:
  ```
  # Upstream provenance
  - Source: https://github.com/Helium314/HeliBoard
  - Tag: <tag>
  - Commit: <full sha>
  - Imported: <YYYY-MM-DD>
  - License: GPL-3.0 (preserved in app/LICENSE)
  ```
- Preserve HeliBoard's `LICENSE` file at `app/LICENSE`

### 4. Rename to our package

HeliBoard uses package `helium314.keyboard` (verify exact string in their manifest). Rename to `com.aikeyboard.app`. The rename surface is wider than just code — HeliBoard uses the package string in places source-only refactoring tools miss:

- `applicationId` in `app/build.gradle.kts` → `com.aikeyboard.app`
- `namespace` in `android { }` block → `com.aikeyboard.app`
- All Kotlin/Java package directories under `app/src/main/java/` and `app/src/main/kotlin/` renamed to `com/aikeyboard/app/...`
- All `package` declarations in source files updated
- `<manifest package=...>` removed if present (newer AGP infers from `namespace`)
- Every `res/xml/` file scanned for `helium314` references — `FileProvider` paths, `BackupAgent` configs, search descriptors, `accessibility_service_config` (HeliBoard doesn't have one but check anyway)
- `AndroidManifest.xml` `android:authorities` strings (FileProvider, ContentProvider, syncadapter) updated
- All `BuildConfig`, `R.` import statements in Kotlin sources updated
- App display name in `strings.xml` → `AI Keyboard`
- App icon: replace launcher icon with a distinct placeholder (a flat colored square with "AIK" is fine; final icon in Phase 12)

**Verification step:** run `grep -ri "helium314" app/ --exclude-dir=build --exclude-dir=.gradle` — must return zero results. If anything matches, fix it before continuing.

**Do NOT touch HeliBoard's existing `SettingsActivity` or `Preference` framework code.** Phase 2 will add a parallel `AiSettingsActivity` (Compose) for AI-specific features. HeliBoard's settings stay bound to `android:settingsActivity` in `res/xml/method.xml` for keyboard preferences. Surgery on HeliBoard's preference graph is explicitly out of scope and will only happen if a future phase proves it's necessary.

Goal: APK can be installed alongside upstream HeliBoard without conflict, distinguishable in launcher and IME picker.

### 5. SDK + Gradle config (do this carefully — most likely failure point)

**Pin AGP and Kotlin to specific versions, not "latest":**

Before editing anything, use the context7 MCP server to look up the **current stable Android Gradle Plugin version that supports `compileSdk = 36`**. Query: `resolve-library-id` for "android gradle plugin", then `query-docs` for SDK 36 compatibility. Pin to whatever that returns. Do **not** guess. As of writing this prompt, AGP 8.7 supports SDK 35 and AGP 8.9+ adds SDK 36 support — but verify before pinning.

Once verified, in `app/build.gradle.kts`:
- `compileSdk = 36`
- `targetSdk = 36`
- `minSdk = 29`
- AGP version: pinned to the exact version returned by context7 lookup
- Kotlin: pinned to current stable (≥ 2.0.x) per context7 lookup
- Java toolchain: `kotlin { jvmToolchain(21) }` — the user has JDK 25 system-wide but AGP officially supports JDK 17–21. The Gradle JVM toolchain mechanism will auto-provision JDK 21 regardless of `JAVA_HOME`.

In `app/gradle.properties`, add:
```
org.gradle.parallel=true
org.gradle.caching=true
android.useAndroidX=true
android.nonTransitiveRClass=true
android.enableR8.fullMode=false
```

The R8 flag is for Phase 12's reproducible-build requirement — leaving full mode off makes builds more deterministic across machines. Document any other reproducibility flags as a TODO in `app/UPSTREAM.md` for Phase 12 to revisit.

**Add product flavors:**
```kotlin
android {
    flavorDimensions += "distribution"
    productFlavors {
        create("fdroid") {
            dimension = "distribution"
            buildConfigField("boolean", "ENABLE_A11Y", "true")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "ENABLE_A11Y", "false")
            applicationIdSuffix = ".play"
            versionNameSuffix = "-play"
        }
    }
    buildFeatures {
        buildConfig = true
    }
}
```

Both flavors must build. The play flavor's APK must have a different `applicationId` so users can install both side-by-side for testing.

### 5b. Document HeliBoard's Compose usage (one-line task, but do it)

Before Phase 2 ships a Compose `AiSettingsActivity`, we need to know if HeliBoard already pulls in Compose. Run:
```
grep -r "androidx.compose" app/src/ app/build.gradle.kts | head -20
```
Write findings to `app/COMPOSE_USAGE.md`:
- "HeliBoard does not use Compose" → Phase 2 adds Compose dependencies fresh
- "HeliBoard uses Compose at version X for Y" → Phase 2 must align to that version to avoid duplicate-classes errors

This is a 5-minute task that prevents a 2-hour debugging session in Phase 2.

### 6. Repo-root files

Write `README.md` (concise — under 80 lines):
- One-line project description
- Status: "Phase 1 scaffold; not yet functional as an AI keyboard"
- Build instructions for both flavors
- Link to ARCHITECTURE.md
- License note (GPL-3.0 inherited from HeliBoard)

Write `.gitignore`:
- `**/build/`
- `**/.gradle/`
- `**/local.properties`
- `**/*.iml`
- `.idea/`
- `.DS_Store`
- `app/release/` (will hold release APKs later)

### 7. Verify builds

From repo root:
- `cd app && ./gradlew assembleFdroidDebug` → succeeds
- `cd app && ./gradlew assemblePlayDebug` → succeeds
- `cd app && ./gradlew lint` → no new errors (HeliBoard's existing warnings are acceptable)

If the Gradle wrapper isn't present in the imported HeliBoard, regenerate with `gradle wrapper --gradle-version <current>`.

### 8. Smoke test (manual)

If a real device is available via `adb devices`, install and verify:
- `cd app && ./gradlew installFdroidDebug`
- Open *Settings → System → Languages & input → On-screen keyboard → Manage keyboards*
- Verify "AI Keyboard" appears in the list
- Enable it, switch to it in any text field, verify keys render and type
- Repeat with `installPlayDebug` (uninstall fdroid build first or install on a different device)

If no device is available, document this in your phase summary and skip the manual smoke test.

### 9. Commit

Single commit on `phase/01-scaffold` with message:
```
Phase 1: scaffold + HeliBoard fork + Gradle flavors

- Imported HeliBoard <tag> into app/
- Renamed package to com.aikeyboard.app
- Added fdroid/play product flavors
- compileSdk=36, targetSdk=36, minSdk=29
- Both flavors build clean
```

Do not push, do not merge to main, do not open a PR. Leave the branch for the user to review.

## Constraints

- **Do not** add any AI / networking / persona / a11y code in this phase. That is later phases.
- **Do not** modify HeliBoard's keyboard rendering, layouts, or input logic in this phase. Only structural changes (package rename, flavors, manifest tweaks for the rename).
- **Do not** add libraries beyond what HeliBoard already pulls in.
- **Do not** edit `ARCHITECTURE.md` or `PHASE_REVIEW.md` (they are spec; if you find an error, surface it in your phase summary instead of editing).
- **Do** preserve HeliBoard's existing test suite as-is. If a test breaks due to the package rename, fix the test's import paths to match.

## Phase summary template (write at end of phase)

When finished, write `PHASE_1_SUMMARY.md` at repo root:

```markdown
## Phase 1 Review (carried into Phase 2 prompt)

- **Built:** <terse outcome — e.g. "HeliBoard tag 3.x.y vendored, package renamed, both flavors build, smoke-tested on Pixel 7">
- **Deviations from architecture:** <list, or "none">
- **Carried issues:** <anything not blocking but worth flagging>
- **Touchpoints for Phase 2:**
  - `app/src/main/java/com/aikeyboard/app/<keyboard service file>` — where the command row will be added
  - `app/src/main/res/layout/<keyboard layout>` — XML layout to extend
  - `app/build.gradle.kts` — flavor config to reference
- **Open questions for the human reviewer:** <anything where you guessed and they should sanity-check>
```

This file is the input to Phase 2. Keep it under 50 lines. Honest > comprehensive.

## Definition of done

All of these must hold:

- `./gradlew assembleFdroidDebug && ./gradlew assemblePlayDebug` from `app/` both succeed
- `grep -ri "helium314" app/ --exclude-dir=build --exclude-dir=.gradle` returns zero results
- `app/UPSTREAM.md` exists with version provenance and any reproducibility TODOs
- `app/COMPOSE_USAGE.md` exists, documenting whether HeliBoard already uses Compose
- AGP and Kotlin versions are pinned to specific values (not Gradle dynamic resolution)
- `gradle.properties` contains the reproducibility flags listed in step 5
- fdroid debug APK installs on a device (if available), shows up as "AI Keyboard" in IME picker, types into a text field
- `PHASE_1_SUMMARY.md` exists at repo root, follows the template, under 50 lines
- `phase/01-scaffold` branch contains a single clean commit, not pushed, not merged to main

Then stop. Do not begin Phase 2.
