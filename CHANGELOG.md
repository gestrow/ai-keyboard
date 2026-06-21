# Changelog

All notable changes to AI Keyboard, the privacy-respecting Android IME forked from HeliBoard.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Planned

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

## [0.1.4] — 2026-06-20

Patch release. Migrates the install/bootstrap domain from `bansheebets.com` to `nullreg.com`. The canonical `/repos/ai-keyboard/setup.sh` path is unchanged; only the host moves.

### Changed

- **Install domain `bansheebets.com` → `nullreg.com`**: the in-app Termux setup wizard's baked bootstrap command (`TermuxSetupWizardScreen.kt`), the README one-paste install command, and the wizard's developer-escape-hatch note (`ai_strings.xml`) now point at `https://nullreg.com/repos/ai-keyboard/setup.sh`. No behavior change; the old host retires with the domain.

## [0.1.3] — 2026-06-14

Patch release. Fixes a Termux bridge `/chat` regression that returned an empty body for every request, fixes the install hang on Android 14+, moves the install URL to its canonical `/repos/` path, and lands the first batch of in-app polish (prompt framing, preview-strip dismiss button, real launcher icon).

### Fixed

- **Bridge `/chat` silently dropped every request** (`bridge/server.js`): Node fires `close` on the `IncomingMessage` as soon as the request body is fully consumed — before the `/chat` handler awaits the adapter — so the pre-handler `request.raw.on('close')` listener fired `abortController.abort()` on every POST. Each adapter's `if (abortSignal?.aborted) return;` then short-circuited, exiting via `finally → sse.end()` with no events emitted, so every chat request returned HTTP 200 with an empty body in ~20 ms (claude, gemini, and codex all affected). Now listens on `reply.raw.on('close')` and only aborts when `writableEnded` is false, which distinguishes a real mid-stream client disconnect from our own `end()`. Verified end-to-end on a Pixel 9 Pro.
- **Termux bridge install hang after `pkg install`** (`TermuxSetupWizardScreen.kt`, `README.md`): the v0.1.1 one-liner `curl … | bash` orphaned the curl process when `setup.sh` re-opened stdin from `/dev/tty` — bash detached fd 0 from the pipe, curl got EPIPE on the unread tail (`curl: (23) Failure writing output to destination, passed N returned 0`), and bash hung at /dev/tty with no more script to execute. Reproduced on Pixel 9 Pro / Android 14+; worked on Pixel 6 only because the script was small enough to fit entirely in the pipe buffer before the redirect. Wizard and README now use the two-step form `curl -fsSL URL -o $HOME/ai-keyboard-setup.sh && bash $HOME/ai-keyboard-setup.sh`, which lets bash read the script from a regular file with fd 0 staying on the controlling TTY throughout. The `setup.sh` `exec </dev/tty` guard from v0.1.1 stays in place: it is a no-op on the new path (fd 0 is already a TTY) and defense-in-depth for users who paste the old one-liner from the cached `bansheebets.com` landing page or third-party docs.
- **Stale "URL isn't hosted yet" dev note** (`ai_strings.xml` `termux_wizard_step_deploy_dev_note`): the wizard's deploy-step note still claimed the bootstrap URL was a placeholder, which has been false since v0.1.0. Rewritten as an honest developer-escape-hatch note for testing local `setup.sh` edits via `adb push`.

### Changed

- **Install URL moved to the canonical `/repos/` path** (`README.md`, `TermuxSetupWizardScreen.kt`): the one-paste README install command and the in-app wizard's `BOOTSTRAP_COMMAND` now fetch `https://bansheebets.com/repos/ai-keyboard/setup.sh` instead of `https://bansheebets.com/ai-keyboard/setup.sh`. bansheebets.com 308-redirects the old path to the new one, so existing installs keep working; new releases call the canonical URL directly. The two-step `curl -o … && bash …` form is unchanged, and the GitHub-raw fallback + release-asset paths are untouched.
- **Rewrite path now has explicit task framing** (`RewritePromptBuilder.kt` (new), `CommandRowController.kt`): the Rewrite command previously sent the input with no task framing — the persona's `systemPrompt` was the only steering. Input is now wrapped with "Rewrite the following text in the {persona.name} voice, preserving the original point of view (1st/2nd/3rd person) and tense. Return only the rewritten text." Pinned by `RewritePromptBuilderTest` (4 cases).
- **Read & Respond prompt** (`ReadRespondPromptBuilder.kt`): adds persona-name injection, an instruction to "ignore UI chrome like empty text fields, button labels, hint text, and timestamps — only respond to conversational content" to filter a11y-captured widget noise, and explicit POV/tense preservation. `ReadRespondPromptBuilderTest` assertions updated.

### Added

- **Preview strip keeps its dismiss (✕) button after the AI stream completes** (`PreviewStripView.markDone()`): the button was previously hidden once the stream finished, leaving no way to discard a completed suggestion without committing it. It now stays visible alongside the "tap to commit" hint.
- **Real launcher icon** replacing HeliBoard's leftover "AIK" lettering, across all five mipmap densities (48 / 72 / 96 / 144 / 192 px, both `ic_launcher` and `ic_launcher_round`), using the fastlane `icon.png` artwork.

### Known regressions / accepted trade-offs

- **Adaptive-icon resources removed** in favor of raster-only mipmaps (`mipmap-anydpi-v26/ic_launcher{,_round}.xml`, `drawable/ic_launcher_foreground.xml`, `drawable-v24/ic_launcher_background.xml`). Raster-only is fine at `minSdk = 29`; accepted trade-offs are themed-icon loss on Android 13+ and square corners on launchers without a circular mask.

### Known issues (unchanged from v0.1.1)

- Always-On FGS re-arm after reboot may not work on Android 14+. Open the keyboard once after boot.
- WhatsApp pack-add rejection on first attempt; debug-only logging diagnoses (Phase 12 §6 / §16.5).
- Per-provider model override UI deferred to v0.2.0.
- Robolectric SDK-36 — 6 upstream tests fail at runner-init.

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
