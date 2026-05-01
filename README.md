# AI Keyboard

A privacy-respecting Android IME forked from [HeliBoard](https://github.com/Helium314/HeliBoard), with personality-driven AI assistance, optional screen-context awareness via AccessibilityService, and sticker creation. All user data (API keys, agent personas, stickers) stays on-device.

**Status:** Phase 1 scaffold — not yet functional as an AI keyboard. The keyboard installs and types; AI features land in subsequent phases. See [ARCHITECTURE.md](ARCHITECTURE.md) for the full design and phase plan.

## Repo layout

- `app/` — Android IME app (Kotlin, Views + Compose). Forked from HeliBoard v3.9.
- `bridge/` — *(Phase 4)* Termux-side Node.js service for local AI CLI integrations.
- `setup/` — *(Phase 5)* one-paste Termux bootstrap script.

## Build prerequisites

- macOS / Linux (Phase 1 was developed on macOS arm64)
- Android SDK with platform `android-36` and build-tools `36.1.0`
- Android NDK `28.0.13004108`
- JDK 21 (Homebrew: `brew install openjdk@21`). The Gradle daemon JVM is pinned via `app/gradle.properties` because the build host's system JDK 25 is too new for Gradle 8.14.

Set `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) and ensure NDK 28 is installed.

## Build instructions

Two product flavors (per ARCHITECTURE.md decision #5):

```bash
cd app

# F-Droid flavor — full AccessibilityService surface (Phase 7 onwards)
./gradlew :app:assembleFdroidDebug
# → app/app/build/outputs/apk/fdroid/debug/ai-keyboard-3.9-fdroid-debug.apk
# applicationId: com.aikeyboard.app.debug

# Play flavor — no a11y manifest entry, conservative onboarding
./gradlew :app:assemblePlayDebug
# → app/app/build/outputs/apk/play/debug/ai-keyboard-3.9-play-debug.apk
# applicationId: com.aikeyboard.app.play.debug
```

The two debug APKs install side-by-side (different applicationIds), and both coexist with upstream HeliBoard on the same device.

## Lint

```bash
cd app
./gradlew :app:lintFdroidDebug :app:lintPlayDebug
```

A `lint-baseline.xml` absorbs HeliBoard's pre-existing translation gaps and other inherited findings. Phase 12 will revisit and prune.

## License

GPL-3.0, inherited from HeliBoard. See [app/LICENSE](app/LICENSE) and [app/UPSTREAM.md](app/UPSTREAM.md) for provenance.

## Roadmap

See ARCHITECTURE.md ("Build / phase plan"). 14 phases total, each its own context window.
