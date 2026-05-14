# AI Keyboard — Privacy Disclosure

Last updated: 2026-05-13 (v0.1.0).

This page describes what data AI Keyboard collects, where it is stored, and what gets sent over the network. It corresponds to the Android `targetSdk=36` data-safety taxonomy.

## What we store on your device

| Category | Where | Encrypted at rest |
|---|---|---|
| API keys (Anthropic / Gemini / xAI) | `files/ai_keyboard_secure.bin` | Yes — Tink AEAD (AES-256-GCM), master keyset wrapped by Android Keystore |
| Persona definitions (system prompts + few-shots) | same blob | Yes — same scheme |
| Backend selection (Remote API / Local LAN / Termux Bridge) | same blob | Yes — same scheme |
| Local LAN config (base URL, optional API key, model name) | same blob | Yes — same scheme |
| Sticker pack manifest + image bytes | `files/stickers/` | No — sticker images are not sensitive |
| `toolbar_mode` and other UI preferences | `<package>_preferences.xml` | No — pure UI prefs |
| Read & Respond consent flag | secure blob | Yes — same scheme |
| Always-On enabled flag | secure blob | Yes — same scheme |

We do **not** store: keystroke history, typed-text logs, AI request bodies, AI response bodies, screen-content captures, IME usage stats.

## What is sent over the network

AI Keyboard talks to **only** the backend you explicitly selected in `Backends`:

| Backend | Endpoint | TLS | What goes |
|---|---|---|---|
| Remote API — Anthropic | `https://api.anthropic.com` | HTTPS only | Your input text + active persona prompt + API key |
| Remote API — Gemini | `https://generativelanguage.googleapis.com` | HTTPS only | Same |
| Remote API — xAI Grok | `https://api.x.ai` | HTTPS only | Same |
| Local LAN | Your configured URL | HTTP or HTTPS (your choice) | Same |
| Termux Bridge | `http://127.0.0.1:8787` (loopback only) | Cleartext on loopback only | Same; the bridge then spawns a local CLI subprocess |

Cleartext HTTP is allowed **only** for:
- The loopback host `127.0.0.1` (Termux bridge).
- RFC1918 private ranges (`10/8`, `172.16/12`, `192.168/16`) and link-local (`169.254/16`) for Local LAN backends.
- `*.local` mDNS hostnames for Local LAN backends.

Cleartext to public IPs triggers a warn-before-save dialog in the Local LAN edit screen. The cloud-provider domains (Anthropic, Gemini, xAI) are HTTPS-only via Network Security Config; cleartext to them is impossible regardless of base configuration.

## What is NOT sent

- No telemetry, no analytics, no crash reporting (we don't run any servers).
- No keystroke logging.
- No "usage" pings, no install-time beacons, no opt-out flags (there's nothing to opt out of).
- No advertising identifiers consulted.
- Read & Respond's screen-context walk runs **only when you tap the Read & Respond button** (or the Quick Settings tile while Always-On is enabled). Never automatically.
- Always-On Read & Respond defaults OFF on every app process restart; you must re-enable explicitly each time.

## AccessibilityService scope (fdroid flavor)

The fdroid build's `ScreenReaderService` is an opt-in `AccessibilityService` with these constraints:

- Zero events subscribed (`accessibilityEventTypes=""`). The service does not auto-react to anything.
- `getRootInActiveWindow()` is walked **once per tap** when you press Read & Respond. The result is a structural text list plus the cursor position; nothing is persisted.
- Foreground service mode (Always-On) runs with a high-priority persistent notification so you always know it's active. The kill switch on the keyboard's command row disables a11y context-reading instantly.
- The play build does not register the service at all (verified via `apkanalyzer manifest print`).

## OAuth tokens (Termux backend)

If you use the Termux bridge, the CLIs (Claude Code, Gemini CLI, Codex) store their OAuth tokens in their own config directories inside Termux's sandbox (`~/.claude/.credentials.json`, `~/.gemini/oauth_creds.json`, `~/.codex/auth.json`). The bridge spawns the CLI as a subprocess; tokens never enter the bridge's memory and never enter the IME's memory. The IME side has no way to read these files.

## What gets into logs

`adb logcat` may surface structural messages from AI Keyboard. By design these contain only:
- Boolean flags (e.g. `alwaysOnEnabled=true`)
- Exception class names (e.g. `BackgroundServiceStartNotAllowedException`)
- Public Android constants (intent action strings)
- Integer counts and structural identifiers

We do not log API keys, URLs with query parameters, request bodies, response bodies, prompts, model output, screen content, or stack-trace `message` fields. Phase 12 §14 ran a final repository-wide audit; any new logging added afterwards must follow the same constraints (`PHASE_REVIEW.md` "Privacy invariants").

## Where the source lives

GPL-3.0-only. Full source at the project's GitHub. The release APK build is reproducible per `BUILD.md` (Phase 12 §16.8); F-Droid's index publishes built-from-source APKs.

## Reporting concerns

If you find a privacy regression, please file a GitHub issue with the reproduction steps. We treat any leak of user content, API key, OAuth token, or URL into logs / network / persistent storage outside the intended scope as a release-blocker.
