# Changelog

All notable changes to AI Keyboard, the privacy-respecting Android IME forked from HeliBoard.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.1] — 2026-05-15

Patch release. Fixes two install-flow paper cuts found post-v0.1.0 and lands the F-Droid metadata images that were placeholder-deferred at v0.1.0.

### Fixed

- **Termux setup wizard URL** (`TermuxSetupWizardScreen.kt`): the bootstrap command shown in the in-app wizard pointed at a placeholder GitHub org (`aikeyboard/ai-keyboard`) that 404s. Now points at `https://bansheebets.com/ai-keyboard/setup.sh`, matching README and the public landing page.
- **`curl … | bash` stdin handling** (`setup/setup.sh`): the one-paste install command recommended by README and bansheebets.com would die at the first `Proceed? [y/N]` prompt because `read` couldn't get input — stdin was consumed by the curl pipe. Setup script now re-opens stdin from `/dev/tty` when piped, so confirm prompts + provider menu + per-CLI OAuth flows work normally. Non-TTY contexts (CI, headless) fall through silently; `--yes` / `--providers` flags remain the correct path there.
- **Wizard and site/README now show the same one-liner install command**: `curl -fsSL https://bansheebets.com/ai-keyboard/setup.sh | bash`.

### Added

- **F-Droid metadata images** are now real artwork:
  - `fastlane/metadata/android/en-US/images/icon.png` (512×512)
  - `fastlane/metadata/android/en-US/images/featureGraphic.png` (1024×500)
  - `fastlane/metadata/android/en-US/images/phoneScreenshots/{1,2,3}.png` (960×2142, portrait)

### Build / signing

- **Release signing config hardening** (`app/app/build.gradle.kts`): two sites previously resolved `keystore.properties` via `rootProject.file(...)`, which pointed at the Gradle root (`app/`) rather than the repo root that BUILD.md, the `.example` template, and `.gitignore` all expect. Hoisted a single `repoRoot.resolve("keystore.properties")` to `android{}` scope and consume it from both `signingConfigs` and `buildTypes`. Without this, the `buildTypes.release` selector silently fell back to `signingConfigs.debug` even with a valid `keystore.properties` in place.
- **Explicit signing-scheme flags**: `enableV1Signing=false`, `enableV2Signing=true`, `enableV3Signing=true`. Belt-and-suspenders against a future AGP minor flipping a scheme off; v1 stays off because `minSdk=29` doesn't need legacy JAR signing; v4 stays off because it's Play-streaming-specific.

### Known issues (unchanged from v0.1.0)

- Always-On FGS re-arm after reboot may not work on Android 14+. Open the keyboard once after boot.
- WhatsApp pack-add rejection on first attempt; debug-only logging diagnoses (Phase 12 §6 / §16.5).
- Per-provider model override UI deferred to v0.2.0.
- Robolectric SDK-36 — 6 upstream tests fail at runner-init.

## [0.1.0] — 2026-05-13

First release. Forked from HeliBoard v3.9 with the following additive features.

### Added

- AI command row (Phase 2) above HeliBoard's suggestion strip with five buttons: persona selector, AI rewrite, Read & Respond, sticker picker launcher, AI settings gear.
- Phase 2.5 chrome polish: defaults HeliBoard's toolbar to `SUGGESTION_STRIP` mode (keeps word predictions, hides the toolbar) and adds a show-toggle in AI Settings → Keyboard layout.
- `AiSettingsActivity` (Phase 2, Compose) — hub-style navigation (Phase 3a) with sections for Personas, Keyboard, Backends, Always-On Read & Respond, Stickers.
- Persona engine (Phase 2) with `Persona { id, name, systemPrompt, fewShots }` and four built-in personas seeded on first run: Default, Concise Editor, Esquire, Flirty.
- `SecureStorage` backed by Tink AEAD over Android Keystore (Phase 3a). Single `files/ai_keyboard_secure.bin` blob, AES-256-GCM. Data-preserving migration from Phase 2's `EncryptedSharedPreferences`.
- Three networking backends behind one `AiClient` interface:
  - **Remote API** (Phases 3a / 3b / 11) — direct HTTPS to Anthropic Messages SSE, Gemini `streamGenerateContent` chunked-JSON, and xAI Grok `/v1/chat/completions` SSE (OpenAI-compatible).
  - **Local LAN** (Phase 10) — user-configurable Ollama (`/api/chat`) or OpenAI-compatible (`/v1/chat/completions`) endpoint. `PublicIpValidator` + warn-before-save dialog for non-RFC1918 cleartext destinations.
  - **Termux Bridge** (Phases 4 / 5a / 5b / 6) — local Node.js service on `127.0.0.1:8787` that spawns user-installed Claude Code, Gemini CLI, or Codex CLI subprocesses. OAuth tokens stay in each CLI's own config dir inside Termux.
- Streaming preview strip (Phase 3b) above the suggestion strip; tokens stream in real time; tap to commit replaces input field text via `InputConnection`.
- AccessibilityService-driven Read & Respond (Phase 7a / 7b, fdroid flavor only). On-tap window-tree walk only; consent activity gates first use; press-to-read-once default. Onboarding wizard for Android 14+ restricted-settings flow.
- Always-On Read & Respond foreground service (Phase 8, fdroid flavor) with persistent notification, Quick Settings tile, in-memory toggle default-OFF on every process restart, and kill switch.
- Sticker engine (Phase 9a) — import via system photo picker (no `READ_MEDIA_IMAGES` permission needed; AndroidX `PickMultipleVisualMedia` handles the grant); normalize to 512×512 WebP ≤100KB; insert via `COMMIT_CONTENT` for Telegram/Signal/Discord.
- WhatsApp sticker pack contract (Phase 9b) — `WhatsAppStickerContentProvider`, tray-icon picker, publisher field, per-sticker emoji editor, `StickerPackValidator` preflight chips, `AddToWhatsAppHelper` intent assembly.
- On-keyboard sticker picker with per-pack tabs (Phase 9b), inline error chip (Phase 9b), and auto-dismiss on input-field changes.
- Termux setup wizard (Phase 5b) with health polling + per-provider re-auth.
- `setup/setup.sh` (Phase 5a / 11) — single-paste Termux bootstrap installing Node, Claude Code (pinned 2.1.112 + autoupdater disabled + JS-wrapper for Bionic compat), Gemini CLI, and Codex (pinned 0.42.0 for the prctl regression openai/codex#6757). DNS resolver shim for `/etc/resolv.conf`.
- Bridge SSE protocol (Phase 4 / 11) with normalized `{type:"delta"|"done"|"error"}` envelope; 45 unit tests with mocked subprocesses.
- Health diagnostics screen (Phase 12 §10) reachable from `AiSettings → Backends → Run diagnostics`. Reports IME enabled, IME selected as current, AccessibilityService bound (fdroid only), backend configured, and bridge providers. Copy-to-clipboard report contains only structural state — no API keys, URLs, prompts, or response bodies.
- In-keyboard error banner (Phase 12 §5) above the command row, replacing the prior Toast that was hidden behind the keyboard surface.

### Default models in v0.1.0

These are hard-coded for v0.1.0. A per-provider override field is planned for v0.2.0.

| Provider | Default model |
|---|---|
| Anthropic | `claude-sonnet-4-6` |
| Gemini | `gemini-2.5-flash` |
| xAI Grok | `grok-2-latest` |

### Carried from HeliBoard 3.9 unchanged

- Autocorrect, gesture typing, multi-language support, suggestion strip.
- Keyboard themes (light / dark / Material You) and layouts.
- Spell checker.
- HeliBoard's existing settings activity (reachable via the launcher icon as escape hatch from AI Settings).

### Privacy

- No telemetry, no analytics, no ads (we don't run any servers).
- API keys stored in Android Keystore (Tink AEAD).
- All AI requests go directly to the user-selected backend; no proxy.
- Network Security Config restricts cleartext HTTP to RFC1918 ranges + loopback only; cloud-provider domains are HTTPS-only.
- See [docs/PRIVACY.md](docs/PRIVACY.md) for the full disclosure.

### Known issues

- **Always-On FGS re-arm after reboot** may not work on Android 14+ due to background-FGS-start restrictions. Workaround: open the keyboard once after boot. Phase 12 §4 added diagnostic logging to surface the root cause; if the OS restriction is the cause, the documented re-arm flow stands for v0.1.0.
- **WhatsApp pack-add rejection** on first attempt has been observed in Phase 9b smoke; Phase 12 §6 added debug-only logging of the rejecting `callingPackage` to diagnose. The `COMMIT_CONTENT` sticker insertion path (Telegram / Signal / Discord) is unaffected.
- **Model-override UI** is deferred to v0.2.0. v0.1.0 uses hard-coded defaults per provider (`claude-sonnet-4-6`, `gemini-2.5-flash`, `grok-2-latest`).
- **Robolectric SDK-36** — 6 upstream HeliBoard tests fail at runner-init because Robolectric 4.14 lacks SDK-36 jars. Tracked in `PHASE_REVIEW.md`; awaiting Robolectric upstream.
- **Reproducible builds** — verified once during Phase 12 §16.8; bumping AGP / dependencies will need re-verification.
- **`EncryptedSharedPreferences` migration shim** is still in `SecureStorage` for Phase 2 → Phase 3a upgrades; v0.2.0 will remove it once we're confident no users have unmigrated state.
- **Direct-boot SecureStorage retry** — if `App.onCreate` runs while the device is locked, Tink's Android Keystore is unavailable and the migration's try/catch swallows the failure. The singleton recovers on the next IME process recycle. Acceptable per `PHASE_REVIEW.md` "Known accepted corner cases".

## [Unreleased]

- Per-provider model-override field in `BackendsScreen` (defaults to `Provider.defaultModel`).
- `OpenAiCompatRemoteBackend` refactor — consolidates the Grok branch + a future DeepSeek / Mistral La Plateforme provider into one HTTPS-only OpenAI-compatible backend.
- Multi-pack creation flow polish (Phase 9b carry-over).
- Pack tab background contrast cleanup (Phase 9b carry-over).
- HeliBoard emoji-key gap (Phase 9a carry-over; upstream issue).
- Robolectric SDK-36 investigation.
- `LocalLifecycleOwner` migration to `androidx.lifecycle.compose`.
- `EncryptedSharedPreferences` shim removal.
- AGP 8.14 → 8.14.5, Compose BOM 2025.11.01 → 2026.05.00, colorpicker-compose 1.1.3 → 1.1.4 (held during v0.1.0 reproducible-build verification).
- Codex CLI version bump past 0.42.0 if upstream resolves the prctl regression openai/codex#6757 on Termux.
- Multi-provider parallel install in `setup.sh`.
- Fastlane / GitHub Actions release pipeline.
