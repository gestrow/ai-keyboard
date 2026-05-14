# AI Keyboard

AI Keyboard is a privacy-respecting Android keyboard with personality-driven AI assistance, optional screen-context awareness, and sticker creation. Forked from [HeliBoard](https://github.com/Helium314/HeliBoard).

[![license](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

## Features

- Forked from HeliBoard: full autocorrect, gesture typing, multi-language, suggestion strip
- **AI rewrite**: highlight or capture text, ask AI to refine, replace inline. Streams tokens into a preview strip above the keyboard; tap to commit.
- **Read & Respond** (F-Droid build): optional screen-context summarization for thoughtful replies via AccessibilityService. Off by default; press-to-read-once; explicit user consent.
- **Sticker engine**: import any image, normalize to 512×512 WebP, send via `COMMIT_CONTENT` to Telegram/Signal/Discord or via WhatsApp's separate pack-add intent.
- **Three backends, your choice:**
  - **Remote API keys (BYOK)** — Anthropic Claude, Google Gemini, xAI Grok. Direct HTTPS, no proxy.
  - **Local LAN** — point at your own Ollama / vLLM / LM Studio server.
  - **Termux bridge** — run Claude Code, Codex, or Gemini CLI locally on the same device.

## Install

- **F-Droid**: <_index entry pending v0.1.0 publication_>
- **GitHub Releases**: signed APKs attached to each tagged release.
- **Build from source**: see [BUILD.md](BUILD.md).

Two flavors ship:
- `fdroid` — primary, full AccessibilityService surface.
- `play` — leaner secondary build, no AccessibilityService manifest registration.

## Privacy

- No telemetry, no analytics, no ads.
- API keys stored in Android Keystore (Tink AEAD over an encrypted on-device blob).
- All AI requests go directly to your selected backend; nothing transits through our infrastructure (we don't run any).
- Always-On Read & Respond defaults OFF on every restart; you re-enable it explicitly.
- Network security config restricts cleartext HTTP to LAN backends only; cloud providers are HTTPS-only.
- Source code: GPL-3.0-only.
- See [docs/PRIVACY.md](docs/PRIVACY.md) for the full disclosure.

## Backends

**Remote API keys** is the most direct path: paste a key into `Backends → Anthropic` (or Gemini / xAI), pick the provider's radio, tap Rewrite. Tokens stream from the cloud provider straight to the keyboard surface; the key never leaves your device.

**Local LAN** is for users running their own model locally (Ollama, vLLM, LM Studio, etc.). Enter the base URL + model name; cleartext is allowed only for RFC1918 ranges and the loopback hosts. Public IPs trigger a warn-before-save dialog.

**Termux bridge** is the most private option: install Termux from F-Droid, run `setup.sh` once, and the bridge spawns Claude Code / Codex / Gemini CLI subprocesses locally. OAuth tokens stay in each CLI's own config dir inside Termux's sandbox.

## Setup

1. Install from F-Droid or GitHub Releases (or build from source).
2. Enable AI Keyboard under **System Settings → System → Languages & input → On-screen keyboard**.
3. Switch to AI Keyboard via the keyboard picker (notification chip when a text field is focused).
4. Open AI Keyboard's gear icon (rightmost in the command row) → **Backends** → configure one of the three options.
5. (Optional) Create or customize a persona in **Personas** for tone-tuned rewrites.

## Build from source

See [BUILD.md](BUILD.md) for the full toolchain matrix and signing instructions.

Quick start:

```bash
cd app
./gradlew :app:assembleFdroidDebug
adb install app/app/build/outputs/apk/fdroid/debug/ai-keyboard-0.1.0-fdroid-debug.apk
```

**Upgrading from a pre-v0.1.0 dev build:** if you previously installed an `ai-keyboard-3.9-*-debug.apk` (versionCode 3901), Android refuses the v0.1.0 install (versionCode 1) as a downgrade. Run `adb uninstall com.aikeyboard.app.debug` first. From v0.1.0 onwards versionCode increments monotonically; `adb install -r` will work normally.

## Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md). Key decisions: HeliBoard fork, Views + Compose split, three backends, AccessibilityService opt-in.

The codebase is organized as three deliverables:
- `app/` — the Android IME (Kotlin, Views + Compose).
- `bridge/` — the Termux Node.js service (~200 LOC, single runtime dep).
- `setup/` — the one-paste Termux bootstrap script.

## Known issues (v0.1.0)

- Always-On Read & Respond's foreground-service chip may not re-post after device reboot on Android 14+ due to background-start restrictions. Workaround: open the keyboard once after boot, the chip re-arms. Investigation in progress for v0.2.0.
- WhatsApp may reject a freshly-built sticker pack on the first "Add to WhatsApp" attempt; debug-logging added in v0.1.0 to surface the rejection reason for the v0.2.0 fix.
- xAI Grok and several other model defaults are hard-coded (`grok-2-latest`, `claude-3-5-sonnet-latest`, `gemini-2.0-flash-latest`). A per-provider model-override UI is planned for v0.2.0.
- Six Robolectric SDK-36 tests fail at compile time. Documented in [PHASE_REVIEW.md](PHASE_REVIEW.md); waiting on Robolectric upstream.

See [CHANGELOG.md](CHANGELOG.md) for the full release notes.

## License

GPL-3.0-only. See [LICENSE](LICENSE) for the full text and [NOTICE](NOTICE) for third-party attributions.

## Acknowledgements

Forked from [HeliBoard](https://github.com/Helium314/HeliBoard), which forked from AOSP LatinIME. The bridge spawns Anthropic's Claude Code CLI, OpenAI's Codex CLI, and Google's Gemini CLI as subprocesses — none of those CLIs are bundled in the APK.
