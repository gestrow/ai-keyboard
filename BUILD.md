# Building AI Keyboard

Reference build instructions for AI Keyboard v0.1.0. See `README.md` for the
elevator pitch and feature list; see `ARCHITECTURE.md` for what the modules
actually do.

## Prerequisites

| Component | Version | Notes |
|---|---|---|
| JDK | 21 | Gradle daemon JVM; pinned via `kotlin.jvmToolchain(21)`. JDK 17 also runs the project but Foojay auto-provisioning targets 21. |
| Android SDK | API 36 | `compileSdk = 36`, `targetSdk = 36`. Set `ANDROID_HOME` (Android Studio installs to `~/Library/Android/sdk` on macOS). |
| NDK | 28.0.13004108 | Required for HeliBoard's JNI dictionary engine. AGP downloads on demand. |
| AGP | 8.13.2 | Pinned in `app/build.gradle.kts`. |
| Kotlin | 2.2.21 | Pinned in `app/build.gradle.kts`. |
| Gradle | 8.14 | Wrapper-managed via `gradle/wrapper/gradle-wrapper.properties`. |

To verify your toolchain matches:

```bash
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --list_installed | grep -E "platforms;android-36|ndk;28\.0|build-tools;36"
java -version  # should report 21.x (or 17.x; 25.x system installs are too new for Gradle 8.14)
```

## Quick build (debug, no keystore needed)

```bash
cd app
./gradlew :app:assembleFdroidDebug   # primary release target
./gradlew :app:assemblePlayDebug     # play-flavor (no AccessibilityService)
```

Output APKs land in `app/app/build/outputs/apk/fdroid/debug/` and
`app/app/build/outputs/apk/play/debug/`.

## Release builds

### One-time keystore setup

```bash
mkdir -p release-keys
keytool -genkey -v -keystore release-keys/aikeyboard-release.jks \
    -keyalg RSA -keysize 4096 -validity 36500 \
    -alias aikeyboard-release
```

`-validity 36500` = 100 years; F-Droid recommends long validity to avoid
mid-app-life rekeys. Save the passwords; there is no recovery path if you
lose them — Android refuses to install an APK signed by a different key on
top of an existing install with the same package id.

Copy the example properties file and fill in the real values:

```bash
cp keystore.properties.example keystore.properties
$EDITOR keystore.properties
```

Both `keystore.properties` and the entire `release-keys/` directory are
gitignored; never commit them.

### Build commands

```bash
./gradlew :app:assembleFdroidRelease
./gradlew :app:assemblePlayRelease
```

Output APKs land in `app/app/build/outputs/apk/<flavor>/release/`. With
`keystore.properties` present they are signed by `aikeyboard-release`; if
the file is missing the build prints a Gradle warning and falls back to
debug signing.

### CI / release pipelines

Pass `-PforceReleaseSigning` to convert the missing-keystore-properties
fallback from a Gradle warning into a hard `GradleException`. Use this flag
in any pipeline that publishes APKs externally so a misconfigured secret
injection step can't ship debug-signed.

```bash
./gradlew :app:assembleFdroidRelease -PforceReleaseSigning
```

### Verifying the signature

Use `apksigner`, **not** `jarsigner`. Android APKs use the APK Signature
Scheme v2/v3 (enabled by AGP whenever `minSdk >= 24`; ours is 29).
`jarsigner -verify` only validates the legacy v1 JAR-style signature and
reports "jar verified" on a v2-only APK as a false positive.

```bash
$ANDROID_HOME/build-tools/<version>/apksigner verify --verbose --print-certs \
    app/app/build/outputs/apk/fdroid/release/*.apk
```

Replace `<version>` with the installed build-tools version
(`ls $ANDROID_HOME/build-tools/` to confirm; common values: `36.0.0`,
`36.1.0`). Expected output:

```
Verified using v1 scheme: ...
Verified using v2 scheme: ...
Verified using v3 scheme: ...
Signer #1 certificate ...
Signer #1 certificate SHA-256 digest: <hex>
```

Record the SHA-256 digest somewhere safe — it's what users use to verify
sideloaded APKs match the F-Droid build.

## Upgrading from a pre-v0.1.0 dev build

If you previously installed an `ai-keyboard-3.9-*-debug.apk` (versionCode
3901), Android will refuse the v0.1.0 install (versionCode 1) as a
downgrade. Run:

```bash
adb uninstall com.aikeyboard.app.debug
# or, for the play flavor:
adb uninstall com.aikeyboard.app.play.debug
```

…before installing v0.1.0. From v0.1.0 onwards `versionCode` increments
monotonically and `adb install -r` will work normally.

## Reproducible build verification

Phase 12 §16.8 (smoke test). Build on machine #1:

```bash
cd /path/to/ai-keyboard
./gradlew clean
./gradlew :app:assembleFdroidRelease
sha256sum app/app/build/outputs/apk/fdroid/release/*.apk > /tmp/sha-machine1.txt
```

Fresh checkout on machine #2 at the same commit / JDK 21 / SDK 36 / NDK
28.0:

```bash
git clone <repo> /tmp/aikeyboard-fresh
cd /tmp/aikeyboard-fresh
git checkout phase/12-polish-release  # or the v0.1.0 tag
./gradlew clean
./gradlew :app:assembleFdroidRelease
sha256sum app/app/build/outputs/apk/fdroid/release/*.apk
```

Expected: identical hash. If they differ, use `diffoscope <apk1> <apk2>`
to see what bytes differ; common culprits include the signing block (use
`apksigner --in-source` to strip), DEX timestamp embedding, and
MANIFEST.MF resource ordering.

Reproducible-build status for v0.1.0 is documented in `PHASE_REVIEW.md`
Phase 12 row.

## Bridge build (Termux Node.js service)

```bash
cd bridge
npm install
npm test  # 45 tests across the three adapters
```

The bridge has a single runtime dependency (`fastify`) and ships as a
static-asset bundle pushed by `setup/setup.sh` into the user's Termux
home dir.

## Lint

```bash
cd app
./gradlew :app:lintFdroidDebug :app:lintPlayDebug
```

Expected: 0 new errors. Three accepted v0.1.0 warnings remain
(`AndroidGradlePluginVersion`, `GradleDependency` for the Compose BOM, and
`NewerVersionAvailable` for colorpicker-compose). The
`TermuxValidationActivity.kt:199 ObsoleteSdkInt` warning was fixed in
Phase 12 §7. `lint-baseline.xml` is curated to absorb upstream HeliBoard
inheritance only.

## Tests

```bash
cd app
./gradlew :app:testFdroidDebugUnitTest    # 162 AI-module JVM tests
./gradlew :app:testPlayDebugUnitTest      # same set; flavor-agnostic
cd ../bridge
node --test "test/*.test.js"              # 45 bridge tests
```

The 6 pre-existing Robolectric SDK-36 failures (`SubtypeTest`,
`ParserTest`, `XLinkTest`, `InputLogicTest`, `StringUtilsTest`,
`SuggestTest`) are upstream HeliBoard tests that fail at runner-init
because Robolectric 4.14 doesn't ship SDK-36 jars. Tracked in
`PHASE_REVIEW.md` as deferred to v0.2.0.

## Phase 12 §8.1 internal mechanics

The release `signingConfigs` block reads `keystore.properties` at the
**repo root**, not under `app/`. `rootProject.file("keystore.properties")`
resolves the path; the same `keystore.path` value (relative) resolves
against repo root too. If you set up paths relative to `app/` you'll get
`Keystore file does not exist` at build time.

The `-PforceReleaseSigning` Gradle property is the CI safety net. A naked
release build with no keystore falls back to debug signing with a Gradle
warning so contributors aren't blocked locally; the flag converts that to
a build failure so a release pipeline can't accidentally ship
debug-signed.

F-Droid's build server will use F-Droid's own keystore, not ours. The
keystore configured here is for sideloaded distribution + GitHub Releases.
