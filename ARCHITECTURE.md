# AI Keyboard — Architecture

A privacy-respecting Android IME with personality-driven AI assistance, optional screen-context awareness, and sticker creation. All user data (API keys, agent personas, stickers) stays on-device.

## Locked decisions

| # | Decision | Choice | Rationale |
|---|---|---|---|
| 1 | UI framework | Views (keyboard surface) + Compose (settings/wizard activities) | Keyboard matches reference IMEs (HeliBoard, AOSP); Compose for normal Activities |
| 2 | Layout base | Fork **HeliBoard** | Inherits autocorrect, gesture typing, multi-language, suggestions strip. GPL-3 acceptable |
| 3 | Screen context | **AccessibilityService as foundation**, with self-imposed press-to-read-once default; "always-on" toggle defaults OFF on every restart, runs as foreground service when active. InputConnection + `ACTION_PROCESS_TEXT` always available regardless | a11y is the differentiator; permission UX self-disciplined to match user expectations |
| 4 | SDK | `compileSdk = 36`, `targetSdk = 36`, `minSdk = 29` | API 36 current; minSdk 29 covers ~95% of devices and matches HeliBoard |
| 5 | Distribution | Two Gradle product flavors: `fdroid` (full a11y, primary) and `play` (no a11y manifest registration, stripped) | F-Droid is the launch target; Play is a leaner secondary build |

## High-level architecture

Three deliverables, one repository:

```
ai-keyboard/
├── app/                    # Android IME app (Kotlin, Views + Compose)
│   ├── src/main/           # Shared source
│   ├── src/fdroid/         # Flavor: full a11y, manifest registers AccessibilityService
│   └── src/play/           # Flavor: no a11y manifest entry, conservative onboarding copy
├── bridge/                 # Node.js Termux-side service (~200 LOC)
│   ├── adapters/
│   │   ├── claude.js       # @anthropic-ai/claude-agent-sdk, OAuth via claude.ai
│   │   ├── gemini.js       # @google/gemini-cli wrapper
│   │   └── codex.js        # @openai/codex wrapper, OAuth via "Sign in with ChatGPT"
│   ├── server.js           # Express/Fastify, POST /chat, GET /health, GET /providers
│   └── package.json
└── setup/
    └── setup.sh            # One-paste bootstrap users run in Termux
```

### Module: `app/` (Android IME)

Major components:

- **`KeyboardService`** (`InputMethodService`) — Forked from HeliBoard. Custom command row added above key area: AI mode toggle, persona selector, "Read & Respond" button, sticker tab.
- **`AiSettingsActivity`** (Compose) — *New* activity dedicated to AI features: persona CRUD, backend selection (remote API key / local LAN / Termux bridge), Termux setup wizard, sticker pack management. Reachable from a dedicated gear icon in the command row. **Coexistence rule:** HeliBoard's existing `SettingsActivity` (Views + `Preference` framework) is preserved unchanged for keyboard preferences (themes, layouts, autocorrect, languages) and remains the activity bound to `android:settingsActivity` in `res/xml/method.xml`. Our Compose activity is purely additive and only handles AI-feature surface area. This avoids invasive surgery on HeliBoard's preference graph in Phase 2 and keeps the upstream merge surface small.
- **`ScreenReaderService`** (`AccessibilityService`, fdroid flavor only) — Window-tree traversal on demand. Two modes:
  - *On-tap* (default): walks `getRootInActiveWindow()` once when the keyboard requests context.
  - *Always-on* (toggle, default OFF every launch): foreground service with persistent notification; emits text deltas to keyboard via local broadcast or `LiveData`.
- **`AiClient`** — Single networking interface. Three backends behind one `BackendStrategy`:
  - `RemoteApiBackend` — Direct HTTPS to Anthropic/Google/OpenAI/xAI with user's API key.
  - `LocalLanBackend` — User-configured IP for self-hosted Ollama / vLLM / LM Studio.
  - `TermuxBridgeBackend` — `http://127.0.0.1:8787` to local Termux bridge.
- **`SecureStorage`** — Wraps `EncryptedSharedPreferences` with master key in Android Keystore. Stores API keys, persona definitions (system prompts + optional few-shots), backend config.
- **`StickerEngine`** — Imports user images, normalizes to WhatsApp constraints (512×512 WebP, ≤100KB static), exposes via `FileProvider` for `COMMIT_CONTENT` API + WhatsApp sticker pack intent.
- **`TermuxOrchestrator`** — Sends `com.termux.RUN_COMMAND` intents to Termux for bridge install/restart/health-check/re-auth flows.

### Module: `bridge/` (Termux Node service)

Single Node service the user runs in Termux. Listens on `127.0.0.1:8787`. Adapter pattern:

```
POST /chat
{ "provider": "claude" | "gemini" | "codex",
  "messages": [...],
  "system": "...",
  "stream": true }

→ SSE stream of text deltas
```

Each adapter spawns the user-installed CLI as a subprocess in stream-json mode and pipes JSONL in/out. The bridge has **no AI SDK dependency** — its only npm runtime dependency is `fastify` (or `express`) for the HTTP layer. The AI inference happens in the spawned `claude` / `gemini` / `codex` processes that the user already authenticated. OAuth tokens live in each CLI's own config dir under Termux home; the bridge never touches them.

This subprocess approach is uniform across providers, avoids dependency drift between the bridge and whatever CLI version the user has installed, and means the bridge can be shipped as a tiny static-asset bundle.

### Module: `setup/setup.sh`

Single bootstrap the user pastes once in Termux:

1. Writes `allow-external-apps = true` to `~/.termux/termux.properties`, reloads
2. `pkg install -y nodejs git termux-api`
3. Interactive provider menu (whiptail or plain stdin)
4. For each selected: `npm i -g <cli>` and runs login command (opens browser)
5. Drops `bridge/` from a release URL, installs deps, registers as termux-service
6. If Termux:Boot detected, adds `~/.termux/boot/start-bridge`
7. Starts bridge, prints success + instructions to return to keyboard app

After this, all further bridge management (provider add/remove, re-auth, restart) is driven by `TermuxOrchestrator` via `RUN_COMMAND` intents — no further pasting.

## IPC contracts

### IME ↔ Bridge (HTTP, loopback only)

`POST /chat` — described above. Streamed Server-Sent Events response.

`GET /health` → `{ "ok": true, "providers": ["claude","gemini"], "uptime": 3421 }`

`GET /providers` → `[{ "id": "claude", "authenticated": true, "model": "claude-sonnet-4-6" }, ...]`

`POST /reauth` → `{ "provider": "claude" }` triggers CLI's login flow in Termux's foreground.

### IME ↔ Termux (intent broadcast)

Uses `com.termux.RUN_COMMAND` intent with extras for command path, args, stdout/stderr file destinations, and a result intent for completion.

**Reliability caveat:** the `RUN_COMMAND` intent has had policy turbulence across Termux versions and Android background-execution restrictions. **Phase 4 must include an experimental validation step** (a tiny test Activity that fires a `RUN_COMMAND` intent and verifies it actually executed) before Phase 5 builds `TermuxOrchestrator` on top of it. If validation fails, fallback options to evaluate: (a) a long-lived Termux foreground service that exposes a Unix domain socket, (b) shipping the bootstrap as a one-time setup with the bridge as a `boot` service that auto-starts and never needs intent-driven control thereafter, with bridge-side endpoints for management instead of intents.

### IME ↔ AccessibilityService (intra-app)

Bound service or `LiveData` flow. Keyboard requests `requestScreenContext()`; service walks the active window's accessibility node tree, filters to text-bearing nodes (`TextView`, `EditText`, custom views with `contentDescription`), returns serialized text + structural hints (which node is the current input field, which is "above" it conversationally).

## Permissions and onboarding flow

| Flavor | Permission | When prompted |
|---|---|---|
| Both | `BIND_INPUT_METHOD` | Implicit when user enables IME in system settings |
| Both | `INTERNET` | Manifest-declared, granted automatically |
| Both | `READ_MEDIA_IMAGES` (API 33+) | When user opens sticker import |
| fdroid | `BIND_ACCESSIBILITY_SERVICE` | User toggles in System Settings → Accessibility (manual; cannot be programmatically requested). Onboarding wizard walks them through. Android 14+ "restricted settings" extra step required for sideloaded builds — wizard handles. |
| fdroid | `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | Declared; activated when user enables "always-on" screen reading |
| Both (optional) | `com.termux.permission.RUN_COMMAND` | When user opts into Termux backend setup |

### First-run onboarding sequence

1. Welcome + privacy summary (data stays local; list every endpoint contacted)
2. Enable IME (deep-link to system settings)
3. Choose backend strategy:
   - "Remote API" → API key entry per provider
   - "Local LAN" → IP/port/key entry
   - "Termux bridge (recommended for power users)" → wizard
4. Optional: enable AccessibilityService (fdroid only) — walked through with screenshots
5. Optional: create first persona (templates: Default, Flirty, Esquire, Concise Editor)

## Security posture

- API keys and OAuth tokens never leave device (the latter never even enter the IME — they live in CLI config dirs inside Termux)
- No analytics, no crash reporting that phones home (use only on-device logs unless user explicitly opts in)
- `networkSecurityConfig.xml` whitelists localhost cleartext and user-configured LAN IPs only; everything else HTTPS. **Phase 4 creates this file with at minimum the `127.0.0.1` cleartext domain entry** (Phase 6 depends on it for SSE streaming over loopback to the Termux bridge); Phase 10 expands it to user-configured RFC1918 ranges.
- Foreground notification mandatory while always-on screen reading is active
- Kill switch in keyboard's command row instantly disables a11y context-reading until next manual enable

## Build flavors

```kotlin
// app/build.gradle.kts (excerpt)
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
        }
    }
}
```

`src/fdroid/AndroidManifest.xml` adds the `<service>` for `ScreenReaderService`. `src/play/AndroidManifest.xml` omits it. Source code references guard `BuildConfig.ENABLE_A11Y` so the play flavor compiles without dead service references.

## Termux CLI compatibility constraints

The Termux backend's value depends on user-installed CLIs (`claude`, `gemini`, eventually `codex`) being functional inside Termux's Bionic-libc environment. Each has its own compatibility story:

- **Claude Code (`@anthropic-ai/claude-code`):** ≥ v2.1.113 ships a glibc-only native binary that cannot run on Termux. We pin to **v2.1.112** (the last version with a `cli.js` JS entry point), **snapshot the install into `~/claude-code-pinned/`** (so it's outside npm's reach), and ship a wrapper script at `~/bin/claude` that invokes `node $HOME/claude-code-pinned/cli.js "$@"`. **Critical: Claude Code has a built-in autoupdater that silently `npm i -g`s itself to latest on every launch — we disable it via `DISABLE_AUTOUPDATER=1` in shell init files**, otherwise the pinned install gets clobbered within minutes of first use. The wrapper-points-at-snapshot pattern is belt-and-suspenders against future autoupdater behavior changes. Phase 5a's setup script automates all of this. Post-v1.0 release checklist mandates rechecking whether Anthropic has restored a JS fallback or shipped an aarch64-Bionic build (tracked in `PHASE_REVIEW.md` "Known accepted corner cases" + Phase 12 acceptance criteria).
- **Gemini CLI (`@google/gemini-cli`):** pure Node, runs natively on Termux. No special handling required.
- **Codex CLI (`@openai/codex`, Phase 11):** install method to be validated when Phase 11 lands; if it has a similar Bionic incompatibility, Phase 11's prompt will document the workaround.

**Forward-looking maintenance commitment:** every project release that ships any change to the bridge or setup script must verify that the pinned CLI versions are still the most-recent-compatible. The compatibility check is part of every release's smoke test; the pinned version is recorded in `setup/setup.sh` as the source of truth for the install command.

## Out of scope for v1

- TTS read-aloud of screen content (planned v2)
- MediaProjection screenshot fallback (planned v2 if a11y proves insufficient)
- Cloud sync of personas (explicitly out — privacy mandate)
- Multi-device shared API keys (out — privacy mandate)
- Custom keyboard layouts beyond what HeliBoard ships (out for v1)
- Voice input (out for v1; HeliBoard already has language support)

## Build / phase plan

Splits applied after scrutiny: phases 5 and 7 are each split into two halves to keep prompts atomic and reviewable. Final count is 14 phases, not 12.

| Phase | Deliverable | Approx scope |
|---|---|---|
| 1 | Repo scaffold, HeliBoard fork import, Gradle flavors (fdroid/play), reproducible-build flags, AGP/Kotlin/JDK pinning | 1 prompt |
| 2 | Command row UI, persona model + `SecureStorage`, `AiSettingsActivity` (Compose) skeleton — additive only, HeliBoard's settings untouched | 1 prompt |
| 2.5 | Chrome polish: default HeliBoard's `ToolbarMode = SUGGESTION_STRIP` (keep predictions, hide its toolbar); add show-toggle in `AiSettingsActivity`; codify keyboard-surface invariants (runtime `Colors`, inset region) into `PHASE_REVIEW.md`. Inserted post-Phase-2 because device testing surfaced chrome density and theme-contrast issues that affect every subsequent phase's UI. | 1 prompt |
| 3a | Storage modernization + provider client infrastructure. **Migrate `SecureStorage` off deprecated `EncryptedSharedPreferences` to Tink-backed `EncryptedFile`** (data-preserving, Phase 2 personas survive upgrade install). API key CRUD on the new storage. New `BackendsScreen` Compose route. **Hub-style settings nav refactor** (start destination = `SettingsHubScreen` listing Personas / Keyboard / Backends). `AiClient` interface + `BackendStrategy` enum declared. `RemoteApiBackend` skeleton with HTTPS client (Ktor) + proguard keep rules. `INTERNET` permission + `networkSecurityConfig.xml` with HTTPS-only entries for `api.anthropic.com` and `generativelanguage.googleapis.com`. **No actual streaming yet.** | 1 prompt |
| 3b | End-to-end Rewrite. Anthropic Messages SSE streaming + Gemini `streamGenerateContent` chunked-JSON streaming inside `RemoteApiBackend`. **Streaming preview strip** added above HeliBoard's suggestion strip — first user of the keyboard-surface UI invariants from Phase 2.5. `LatinIME.onComputeInsets` calculation extended for the new view. "Rewrite with AI" command-row button wired end-to-end: `InputConnection` text → active persona's prompt → selected backend → preview strip → commit on tap. Cancel-on-typing. Distinct user-facing errors per failure type (network / auth / no-key / rate-limit / timeout). | 1 prompt |
| 4 | Bridge: Node project (fastify + subprocess adapters), Claude + Gemini adapters, /chat /health /providers endpoints, SSE; **also: `networkSecurityConfig.xml` with localhost cleartext entry**; **also: experimental `RUN_COMMAND` validation harness in a debug Activity** | 1 prompt |
| 5a | `setup.sh` bootstrap — complete and testable on a real device in Termux, no IME involvement yet | 1 prompt |
| 5b | `TermuxOrchestrator` Kotlin class + IME's Termux setup wizard with health polling | 1 prompt |
| 6 | `TermuxBridgeBackend` wired into `AiClient`; provider switcher in settings; re-auth flow via `RUN_COMMAND` | 1 prompt |
| 7a | `ScreenReaderService` (fdroid flavor only) — on-tap window-tree read, no UI integration, exposed via bound service interface | 1 prompt |
| 7b | "Read & Respond" command row button wired to the service; a11y onboarding wizard with Android 14+ restricted-settings flow | 1 prompt |
| 8 | Always-on toggle with foreground service notification (fdroid only, `FOREGROUND_SERVICE_SPECIAL_USE` declared in `src/fdroid/AndroidManifest.xml` only), default-off-on-restart, kill switch | 1 prompt |
| 9 | Sticker engine — import + normalize to 512×512 WebP. **Two distinct insertion paths:** `COMMIT_CONTENT` via `InputContentInfoCompat` (Gboard-style, Telegram et al.) AND WhatsApp's separate sticker-pack `Intent` with `contents.json` manifest. Implement both. | 1–2 prompts |
| 10 | `LocalLanBackend` for self-hosted models, expand `networkSecurityConfig.xml` with RFC1918 cleartext entries + user host validation | 1 prompt |
| 11 | OpenAI Codex adapter in bridge + IME provider entry; defer Grok pending CLI availability | 1 prompt |
| 12 | Polish: onboarding wizard, error states, health diagnostics, README, screenshots, F-Droid metadata, signing key generation, reproducible-build verification, signed release | 1 prompt |

Each phase is its own context window. Start by reading this file and the previous phase's `PHASE_N_SUMMARY.md`.
