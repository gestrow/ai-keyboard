# Phase Review Framework

How to grade the output of each phase before writing the next phase's prompt. Apply the universal checklist + the per-phase acceptance criteria. If anything fails, fix it (or queue a follow-up) *before* moving on. Skipping this is how greenfield projects acquire load-bearing tech debt.

## Universal checklist (run after every phase)

### Build & integrity
- [ ] `./gradlew assembleFdroidDebug` succeeds with no warnings about deprecated APIs introduced this phase
- [ ] `./gradlew assemblePlayDebug` succeeds **and** the resulting APK does not contain `ScreenReaderService` references (verify with `./gradlew :app:dependencies` + `apkanalyzer dex packages`)
- [ ] `./gradlew lint` produces no new errors; warnings reviewed and triaged
- [ ] **No new entries added to `lint-baseline.xml`** this phase. Baseline absorbs upstream HeliBoard's inherited issues only; our code must lint clean. Verify with `git diff lint-baseline.xml` — should be empty for any phase after Phase 1.
- [ ] `./gradlew test` passes (unit tests added this phase + all prior tests)
- [ ] No new `TODO` / `FIXME` / `XXX` comments introduced without a tracking note

### Privacy invariants (non-negotiable, check every phase)
- [ ] No new outbound endpoints in `networkSecurityConfig.xml` beyond what the phase explicitly required
- [ ] No analytics, crash reporting, or telemetry libraries added (Firebase, Sentry, Crashlytics, Datadog, etc.)
- [ ] No API keys, OAuth tokens, or persona data written to logs (`adb logcat | grep -i "key\|token\|api"` after smoke test → empty)
- [ ] No new `<uses-permission>` entries beyond what the architecture specifies for this phase
- [ ] If this phase touches `SecureStorage`: data still encrypted at rest (verify by reading the prefs file directly with adb)

### Scope discipline
- [ ] Output matches the phase's acceptance criteria — nothing more, nothing less
- [ ] No speculative abstractions ("we might want this later" interfaces with one implementer)
- [ ] No half-finished features (anything user-visible either works end-to-end or is hidden behind a debug flag)
- [ ] No refactoring of code outside this phase's scope unless required to make the phase work

### Flavor hygiene
- [ ] Code that references a11y / `ScreenReaderService` is guarded with `BuildConfig.ENABLE_A11Y` or lives only in `src/fdroid/`
- [ ] `src/play/AndroidManifest.xml` does **not** declare any service related to a11y
- [ ] No flavor-specific code accidentally promoted to `src/main/`

### Code quality smell tests
- [ ] Comments explain *why*, not *what* (delete narration that restates the code)
- [ ] No dead code, unused imports, or stub functions
- [ ] No backwards-compat shims for code that hasn't shipped yet
- [ ] Function lengths reasonable (no 200-line god-functions introduced)
- [ ] Names match domain terms used in `ARCHITECTURE.md`

## Manual smoke test (run on real device, not emulator)

After every phase that produces a runnable build:

1. Uninstall any prior debug build
2. `./gradlew installFdroidDebug`
3. Enable IME in system settings; select as active keyboard
4. Open a notes app, type into a text field — keyboard appears, basic keys work
5. Open IME settings from the keyboard's gear icon — Compose UI loads, doesn't crash
6. Phase-specific smoke steps (see per-phase section below)
7. `adb logcat -d | grep -E "AndroidRuntime|FATAL|ERROR.*ai-keyboard"` → empty

## Red flags (stop and rethink)

If any of these surface during review, do **not** proceed to the next phase. Fix the root cause first:

- Build only works in one flavor — flavor split is broken
- Tests pass but manual smoke test fails — tests aren't testing the right thing
- A test was modified to pass instead of the code being fixed
- Permissions list grew without a corresponding architecture decision
- A library was added that wasn't in the phase's prompt (especially networking / serialization libraries — high blast radius)
- Generated code (HeliBoard fork) was edited directly instead of via overlay/patch — makes future upstream merges painful
- Compose code introduced inside the keyboard surface itself (not the settings activity) — drift from locked decision #1
- Anything calls `Log.d` / `println` with text that came from `InputConnection` or the screen reader

## Keyboard-surface UI invariants (apply to any phase that adds UI on the IME view)

Adopted in Phase 2.5 after device testing in Phase 2 surfaced both. Any phase that adds new UI on the keyboard surface (not in `AiSettingsActivity`) must hold these invariants:

- [ ] **Runtime color sourcing.** UI sources colors from HeliBoard's `Settings.getValues().mColors` with `ColorType.*` semantic types (`STRIP_BACKGROUND`, `TOOL_BAR_KEY`, `KEY_TEXT`, etc.) — **not** from app-level theme attrs (`?android:attr/textColorPrimary`, etc.). Theme attrs resolve against the IME's app theme, which doesn't match the user's selected keyboard theme; result is invisible/illegible icons on most HeliBoard themes. Reference: `SuggestionStripView.kt` already does it correctly.
- [ ] **Touchable region extension.** Any view added *above* HeliBoard's `strip_container` must update the inset calculation in `LatinIME.onComputeInsets` (subtract the new view's height from `visibleTopY`) **or** reuse the existing command row's host. Otherwise taps on the new UI fall through to the underlying app and dismiss the keyboard. The Phase 2 `onComputeInsets` patch is the template; a single `visibleTopY` calculation is shared for all above-strip UI — extend it, don't duplicate it.

Phases this applies to: 3 (AI streaming preview strip), 7b (Read & Respond integration if any new strip UI), 8 (kill-switch indicator), 9 (sticker preview surface).

## Phase output evaluation rubric

For each phase, score 0–3 on these dimensions. Anything below a 2 means iterate before moving on:

| Dimension | 0 | 1 | 2 | 3 |
|---|---|---|---|---|
| **Functional completeness** | Doesn't build / crashes | Builds, partial happy path | Happy path works, edge cases not covered | Happy path + documented edge cases handled |
| **Privacy posture** | Telemetry / leaks introduced | Permissions creeping | Permissions match architecture | Permissions match + active hardening (e.g. `android:allowBackup="false"`) |
| **Flavor discipline** | Play flavor broken | Cross-flavor leakage | Both flavors clean | Both flavors clean + flavor differences tested |
| **Code quality** | Sprawling, untyped, comment-heavy | Works but fragile | Idiomatic Kotlin, scoped well | Idiomatic + extensible + well-tested |
| **Scope discipline** | Multiple features beyond prompt | Some unprompted refactoring | Stays in scope | In scope + flagged future work as TODOs in tracking, not in code |

## Feeding review into the next prompt

After scoring a phase, the review note for the next phase's prompt should include:

1. **What was built** (1–2 sentences, the actual outcome — not what you hoped for)
2. **Deviations from architecture** (if any — what changed and why)
3. **Known issues carried forward** (anything not blocking but worth knowing)
4. **Files most relevant for next phase** (so the next prompt doesn't have to re-explore)

Template:
```
## Phase N Review (carried into Phase N+1 prompt)
- Built: <terse outcome>
- Deviations: <list, or "none">
- Carried issues: <list, or "none">
- Touchpoints for next phase: <file paths>
```

## Per-phase acceptance criteria

### Phase 1 — Scaffold + HeliBoard fork + Gradle flavors

**Done means:**
- Repo has top-level `app/`, `bridge/`, `setup/` directories
- `app/` is a working fork of HeliBoard pinned to a specific commit (recorded in `app/UPSTREAM.md`)
- Two product flavors (`fdroid`, `play`) declared in `app/build.gradle.kts`; both build to APKs
- `compileSdk = 36`, `targetSdk = 36`, `minSdk = 29`
- **AGP version pinned** to a specific stable release that supports SDK 36 (verified via context7 lookup for current Android Gradle Plugin)
- **Kotlin version pinned** to current stable (≥ 2.0.x)
- **JDK pinned via `gradle.properties`**: `org.gradle.java.home` set explicitly to a JDK 21 install path, OR `kotlin { jvmToolchain(21) }` in `app/build.gradle.kts` so Gradle auto-provisions JDK 21 regardless of the system `JAVA_HOME` (user has JDK 25 system-wide; do not rely on it)
- **Reproducible-build flags** in `gradle.properties`: `android.enableR8.fullMode=false` plus the standard reproducibility set (`org.gradle.parallel=true`, `org.gradle.caching=true`); a TODO note in `app/UPSTREAM.md` if full byte-identical reproducibility requires more config — Phase 12 will finish this
- `applicationId` is your namespace (not HeliBoard's); rename complete in **manifest, package directories, Gradle, all `res/xml/` files, and any string-based authorities (`FileProvider`, `ContentProvider`)**
- **Case-sensitive** grep for `helium314` in `app/` source (excluding upstream-attribution comments and provenance docs) returns zero results. Use `grep -r "helium314" app/ --exclude-dir=build --exclude-dir=.gradle` (no `-i` flag). URL references to `github.com/Helium314/...` and the provenance pointer in `UPSTREAM.md` are correct attribution and **must** stay.
- HeliBoard's existing `SettingsActivity` and `Preference` framework usage is **preserved untouched** (Phase 2 will add a parallel `AiSettingsActivity`, not modify the existing one)
- A `COMPOSE_USAGE.md` note in `app/` documents whether HeliBoard already uses Compose anywhere (some recent versions do); this informs Phase 2's dependency strategy
- App icon + name distinguish from upstream HeliBoard so they can coexist on a device
- README.md at repo root with build instructions
- `.gitignore` covers `build/`, `local.properties`, `*.iml`, `.idea/`, `.gradle/`
- Phase 1 commit on its own branch, not main

**Smoke test:**
- Install fdroid debug APK on a device; verify keyboard renders and types into a notes app
- Install play debug APK alongside (different `applicationId` permits side-by-side install) and verify same
- Confirm app shows up in *Settings → System → Languages & input → On-screen keyboard*
- Logcat during typing is clean (no `FATAL EXCEPTION` or `AndroidRuntime` crash referencing `com.aikeyboard.app`); harmless system messages like `Package [...] reported as REPLACED, but missing application info` are not failures — they're emitted by other apps' `ActivityThread` whenever `adb install -r` runs, regardless of our code

**Known HeliBoard upstream behaviors that are NOT Phase 1 regressions:**
- **Gesture/swipe typing is non-functional** out of the box. HeliBoard cannot redistribute Google's proprietary glide-typing library; users supply their own `.so` file via HeliBoard's settings. Verify the absence by checking against an unmodified upstream HeliBoard install if there's any doubt.
- Inherited lint warnings absorbed by `lint-baseline.xml` (translations, etc.)

**Out of scope this phase (don't accept if introduced):**
- Any AI / networking code
- Any persona / settings UI
- Any AccessibilityService implementation
- Termux integration

### Phase 2 — Command row UI + persona model + secure storage

**Done means:**
- HeliBoard's keyboard layout extended with a "command row" above the top key row
- Command row contains: persona dropdown (placeholder data), AI mode toggle, "Read & Respond" button (no-op for now), sticker tab launcher (no-op)
- `Persona` Kotlin data class: id, name, systemPrompt, optional fewShots
- `SecureStorage` wraps `EncryptedSharedPreferences` with a Keystore master key; CRUD methods for personas
- Compose-based `SettingsActivity` reachable from a long-press on the command row gear; lists personas, allows add/edit/delete
- Default personas seeded on first run: Default, Concise Editor, Esquire, Flirty (system prompts only, no real LLM yet)

**Smoke test:**
- Add a persona in settings, kill the app, reopen — persona persists
- Inspect `/data/data/<package>/shared_prefs/` via `adb shell run-as` — file is encrypted (binary, not readable)
- Persona dropdown in command row reflects the persisted list

### Phase 2.5 — Chrome polish (toolbar default + show toggle)

Inserted after Phase 2 because device testing revealed chrome density and theme-contrast issues that affect every subsequent UI phase. Narrow scope by design: defer everything that isn't toolbar mode + toggle + invariant codification.

**Done means:**
- HeliBoard's `Defaults.PREF_TOOLBAR_MODE` (or equivalent first-run pref-default mechanism) set to `SUGGESTION_STRIP` — keeps word predictions visible, hides HeliBoard's gear/mic/clipboard/undo/redo toolbar by default
- Show-toggle added to `AiSettingsActivity` ("Show HeliBoard toolbar"); flips between `SUGGESTION_STRIP` (off) and `EXPANDABLE` (on, HeliBoard's original default). Writes to HeliBoard's existing `Settings.PREF_TOOLBAR_MODE` SharedPreferences key — **persistent** across keyboard restarts (distinct from privacy-axis toggles in Phase 8 which mandate default-off-on-restart)
- Toggle state survives app process kill + relaunch
- HeliBoard's full settings remain reachable through the launcher icon (escape hatch — users can still access advanced HeliBoard prefs)
- Both flavors build; lint clean; no new lint-baseline entries
- Smoke test on Pixel 6 Pro: fresh install of fdroid debug → command row visible, HeliBoard toolbar hidden, suggestions still visible. Toggle on → HeliBoard toolbar appears below suggestions. Toggle off → returns to default. Restart app → toggle state preserved.

**Out of scope this phase (don't accept if introduced):**
- Chevron/swipe toggle on the command row (deferred; settings-only is the v1 path)
- Visual density tuning of our command row (defer to Phase 12 polish unless it's a regression from Phase 2)
- Any AI / networking / a11y / Termux code
- Touching `SecureStorage` (Phase 3 owns that)

**Smoke test additions:**
- Verify `getSharedPreferences("com.aikeyboard.app_preferences", MODE_PRIVATE).getString("toolbar_mode", null)` (or whatever HeliBoard's actual pref key is — find it before writing the prompt) reads `SUGGESTION_STRIP` after first launch with no user intervention
- Verify the toggle in settings actually flips this pref value (`adb shell run-as ... cat shared_prefs/...` before and after)

### Phase 3 — RemoteApiBackend (Anthropic + Google) end-to-end + SecureStorage modernization

**Done means:**
- **`SecureStorage` migrated off deprecated `EncryptedSharedPreferences`.** Replacement target: Tink-backed `EncryptedFile` for keysets + Jetpack DataStore for non-secret state, OR direct Keystore `KeyGenerator` + AES-GCM blob storage. Both personas (Phase 2 data) and API keys (Phase 3 new data) end up on the new storage. **Migration must preserve existing persona data** for users who installed Phase 2 first — read old prefs once, write new format, delete old prefs file.
- `AiClient` interface defined; `BackendStrategy` enum with `REMOTE_API`, `LOCAL_LAN`, `TERMUX_BRIDGE`
- `RemoteApiBackend` implements Anthropic Messages API and Gemini `generateContent` with streaming
- API key entry UI in settings (one field per provider, masked); keys stored in (modernized) `SecureStorage`
- `android.permission.INTERNET` declared in `app/src/main/AndroidManifest.xml` (HeliBoard ships without it, per Phase 2's note)
- "Rewrite with AI" button in command row: takes current text-field content via `InputConnection`, sends to selected provider with selected persona's system prompt, streams response into a preview strip, commits on tap
- Streaming preview strip lives **above** the suggestion strip (alongside our command row) — verify it follows the keyboard-surface UI invariants (runtime `Colors`, inset region updated)
- Errors surface to user as toast or strip (no silent failures)
- HTTPS client (Ktor or OkHttp): pinned version, dedicated `-keep` rules added to `proguard-rules.pro`

**Smoke test:**
- Fresh install on a device with no prior Phase 2 install: API keys + personas both work
- Upgrade install over a Phase 2 build: existing personas survive the storage migration (verify via `adb shell run-as ... ls shared_prefs/` shows the new file format and old `ai_keyboard_secure.prefs.xml` is deleted)
- Type "this is a draft email" in any text field, tap Rewrite, verify a real LLM response appears in the preview strip
- Disable network mid-stream — verify graceful error message, no crash
- Clear API key, attempt Rewrite — verify clear "no key configured" message
- Tap on the streaming preview strip area while it's showing — keyboard does not dismiss (verifies inset region was extended for it, per the keyboard-surface UI invariants)

### Phase 4 — Termux bridge (Node) for Claude + Gemini

**Done means:**
- `bridge/package.json` with pinned versions of **only** `fastify` (or `express`) — no AI SDK dependency. Adapters spawn the user-installed CLI (`claude`, `gemini`) as a subprocess and pipe stream-json
- `bridge/server.js` exposes `POST /chat`, `GET /health`, `GET /providers`, `POST /reauth`
- Adapters: `bridge/adapters/claude.js`, `bridge/adapters/gemini.js`, both subprocess-based
- SSE streaming from `/chat` works end-to-end
- Server listens on `127.0.0.1:8787` only (verify with `netstat`); refuses connections from `0.0.0.0`
- README in `bridge/` explains running standalone (without setup.sh) for development
- **`app/src/main/res/xml/network_security_config.xml` created** with cleartext domain entry for `127.0.0.1` and `localhost`; referenced from `AndroidManifest.xml` `application` element. Phase 6 will depend on this.
- **`RUN_COMMAND` validation harness:** a debug-only `TermuxValidationActivity` (gated behind `BuildConfig.DEBUG`) that fires a `com.termux.RUN_COMMAND` intent for `echo "termux ipc ok"` and reports success/failure. Manual run on real device required as part of phase smoke test. If this fails, **STOP**: revisit ARCHITECTURE.md's Termux IPC fallback options before Phase 5 begins.

**Smoke test (in Termux on a device, manually for now):**
- `node server.js` → `/health` returns ok
- `curl -N -X POST http://127.0.0.1:8787/chat -d '{"provider":"claude",...}'` → streams a response
- Run `TermuxValidationActivity` from a real device with Termux installed and `allow-external-apps=true` — verify the intent fires and the bridge log file contains the expected echo output

### Phase 5a — setup.sh bootstrap (Termux-side only, no IME)

**Done means:**
- `setup/setup.sh` is one paste-and-run, idempotent (safe to re-run)
- Writes `allow-external-apps = true` to `~/.termux/termux.properties`, runs `termux-reload-settings`
- `pkg install -y nodejs git termux-api`
- Interactive provider menu (whiptail or plain numbered stdin) — Phase 5a supports Claude + Gemini only; Codex deferred to Phase 11
- For each selected: `npm i -g <cli>` then runs the CLI's login command, blocks until user completes browser flow, verifies auth
- Downloads `bridge/` from a release URL (or copies from a local path during development), runs `npm install`
- Registers bridge as a `termux-services` service; if `Termux:Boot` package is detected, also writes `~/.termux/boot/start-bridge`
- Starts bridge, prints `127.0.0.1:8787/health` curl confirmation
- Script handles common failure paths gracefully: no internet, npm install failure, login timeout — clear error messages, no half-installed state

**Smoke test (real device, in Termux only — no IME yet):**
- Fresh Termux install: paste bootstrap, follow prompts, end with healthy bridge
- Re-run on already-set-up device: idempotent, doesn't break working state
- Reboot phone: if `Termux:Boot` installed, bridge restarts automatically; if not, document the manual step

### Phase 5b — TermuxOrchestrator + IME Termux setup wizard

**Done means:**
- `TermuxOrchestrator` Kotlin class wraps `com.termux.RUN_COMMAND` intents — verified working from Phase 4's validation harness
- Methods: `bootstrapInstall()`, `restartBridge()`, `healthCheck()`, `reauthProvider(name)`
- Compose-based "Termux Setup" wizard in `AiSettingsActivity`: detects Termux installed → shows bootstrap command + copy button + QR code → user pastes in Termux → wizard polls `127.0.0.1:8787/health` until 200 or 5-min timeout
- Wizard handles: Termux not installed (deep-link to F-Droid), `allow-external-apps` not set (specific instructions, link to docs), bridge not running (offer to restart via `RUN_COMMAND`)
- All long polling cancellable; no orphaned coroutines on screen exit

**Smoke test:**
- Fresh device, no Termux: wizard's "install Termux" deep-link works
- Termux installed but no bootstrap run: wizard polls, then succeeds when user completes paste
- Force-stop bridge from Termux mid-session: wizard's "restart bridge" button via `RUN_COMMAND` revives it

### Phase 6 — TermuxBridgeBackend + provider switcher

**Done means:**
- `TermuxBridgeBackend` implements `AiClient` against `127.0.0.1:8787`
- Settings has "Backend strategy" radio: Remote API / Local LAN (placeholder) / Termux bridge
- Provider selector populated from `/providers` endpoint when Termux backend selected
- Re-auth flow: settings button per provider → fires `RUN_COMMAND` intent that runs `<cli> /login` in Termux foreground, then re-checks `/providers`

**Smoke test:**
- Switch backend to Termux mid-conversation, repeat Rewrite — uses Termux bridge instead of API
- Trigger re-auth, complete browser login, return — provider shows authenticated again

### Phase 7a — ScreenReaderService (fdroid only), bound-service interface, no UI

**Done means:**
- `ScreenReaderService` in `app/src/fdroid/java/com/aikeyboard/app/a11y/`; `<service>` declaration only in `app/src/fdroid/AndroidManifest.xml`
- `app/src/fdroid/res/xml/accessibility_service_config.xml`: `canRetrieveWindowContent=true`, `accessibilityEventTypes` minimized (no auto-events), `notificationTimeout=100ms`, `description` and `summary` strings written
- Service exposes `requestScreenContext()` (via bound service `IBinder` or singleton `LiveData` for in-process consumption) that walks `getRootInActiveWindow()` once and returns a `ScreenContext` data class: serialized text nodes + structural hints (which node is the current focused input field, which nodes are "above" it conversationally)
- All `BuildConfig.ENABLE_A11Y` guards in shared code reference this service safely
- `play` flavor build succeeds; `apkanalyzer dex packages` on play APK confirms no `a11y/` package present
- **No UI changes in Phase 7a** — service is callable but nothing in the keyboard surface invokes it yet

**Smoke test:**
- Enable service in system settings on fdroid debug build
- Trigger `requestScreenContext()` from a debug Activity; verify returned text matches what's on screen
- Service does not log any text content (`adb logcat | grep -i "context\|text"` shows only structural metadata)
- Disable service, retrigger — graceful failure with explicit error type returned

### Phase 7b — "Read & Respond" wiring + a11y onboarding wizard

**Done means:**
- "Read & Respond" command row button (placeholder from Phase 2) wired: tap → calls `ScreenReaderService.requestScreenContext()` → packages context + current input field text → sends to selected backend with persona prompt → streams reply into preview strip → commit on tap
- Onboarding wizard in `AiSettingsActivity` (fdroid only) walks user through enabling a11y:
  - Step 1: Explain what data the service will read and the press-to-read default
  - Step 2: Open System Settings → Accessibility deep-link
  - Step 3: For Android 14+ sideloaded builds: explicit instructions for "App info → ⋮ menu → Allow restricted settings" (with screenshot)
  - Step 4: Poll `AccessibilityManager.getEnabledAccessibilityServiceList()` to detect when user completes
- Errors surfaced clearly: service not enabled, service crashed mid-read, context too large for selected backend's context window
- `play` flavor: button still present in command row but tapping it shows "AI screen reading not available in this build" toast and prompts to install fdroid build (or reverts to Rewrite-only behavior — pick one in implementation)

**Smoke test:**
- Open an email thread, tap Read & Respond — keyboard suggests a reply that references thread content
- Tap repeatedly — service only walks tree on tap (verify with logcat)
- Onboarding flow on Android 14+ sideloaded device — restricted-settings step actually works
- `play` flavor build: tap Read & Respond → fallback behavior, no crash, no missing-class errors

### Phase 8 — Always-on toggle + foreground service + kill switch

**Done means:**
- "Always-on screen reading" toggle in `AiSettingsActivity`, defaults OFF on every app process restart (use in-memory flag, **not** persisted to `SecureStorage` or any prefs file — verify by killing app process and re-launching)
- When enabled: `ScreenReaderService` runs as foreground service with persistent notification
- **Permission scoping (critical for play flavor compatibility):** `<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>` and `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>` declared **only in `app/src/fdroid/AndroidManifest.xml`** — never in `src/main/`. Verify with `apkanalyzer manifest print` on the play APK: neither permission appears.
- `<service android:foregroundServiceType="specialUse">` element with `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="explanation:on-demand AI screen context for accessibility-driven reply assistance"/>` — also fdroid-only manifest
- Notification: high-priority channel, persistent, "Stop" action that flips toggle off and stops foreground service. Notification text states "AI screen reading is active" so user always knows.
- Command row has visible "kill switch" indicator when always-on is active (red dot or similar)
- Always-on emits text deltas via `LiveData` / `SharedFlow` that the keyboard consumes; coalesced (not per-event) to limit event spam

**Smoke test:**
- Enable always-on, restart phone — verify it's OFF on next launch
- Enable always-on, force-stop the app — service dies, notification gone, no orphaned process
- Long session of always-on (>30 min) — no memory leak (heap stable in profiler)

### Phase 9 — Sticker engine (two distinct protocols)

**Done means:**
- "Sticker tab" launches picker that imports images via `READ_MEDIA_IMAGES`
- Imports normalized to 512×512 WebP ≤100KB (resize + compress); animated WebP support out of scope for v1
- Stickers stored in app-private storage; exposed via `FileProvider` with explicit `<paths>` config
- Pack management UI: rename, delete, reorder, set tray icon (96×96 PNG)

**Two separate insertion paths — both required:**

1. **`COMMIT_CONTENT` via `InputContentInfoCompat`** — Gboard-style insertion for Telegram, Discord, Signal, etc. Implemented in `KeyboardService.onUpdateSelection` flow. Verify each target app declares a matching `EditorInfo.contentMimeTypes` of `image/webp` or `image/png`.

2. **WhatsApp sticker pack `Intent`** — separate code path. WhatsApp does NOT use `COMMIT_CONTENT`. Build a pack manifest in WhatsApp's required format:
   - `contents.json` with `identifier`, `name`, `publisher`, `tray_image_file`, and `stickers` array (each with `image_file` + `emojis`)
   - All sticker files copied/symlinked into the WhatsApp-readable directory exposed via `FileProvider`
   - Intent: `Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK")` with extras `sticker_pack_id`, `sticker_pack_authority`, `sticker_pack_name`

**Smoke test:**
- Import a 4MP photo — sticker output is 512×512 WebP, file size <100KB
- Insert sticker into Telegram (COMMIT_CONTENT path) — appears as proper sticker
- Insert sticker into Signal (COMMIT_CONTENT path) — works
- Add pack to WhatsApp via the explicit "Add to WhatsApp" button — pack appears in WhatsApp sticker tray, individual stickers send correctly
- Verify `FileProvider` paths do not expose anything beyond the sticker directory (`adb shell run-as` and check `<paths>`)

### Phase 10 — LocalLanBackend + network security config

**Done means:**
- `LocalLanBackend` implements `AiClient` against user-configured base URL
- Settings UI: base URL, optional API key, optional model name
- `networkSecurityConfig.xml` allows cleartext only for: `127.0.0.1`, RFC1918 ranges (`10/8`, `172.16/12`, `192.168/16`), and the user-configured host
- Validation: if user enters a public IP, warn before allowing cleartext
- Compatible with Ollama (`/api/generate`), OpenAI-compatible servers (`/v1/chat/completions`)

**Smoke test:**
- Point at local Ollama on LAN — works
- Point at LM Studio — works
- Try public IP without HTTPS — warning shown

### Phase 11 — OpenAI Codex adapter (bridge) + IME provider entry

**Done means:**
- `bridge/adapters/codex.js` wraps `@openai/codex` with stream-json IO
- `setup.sh` provider menu includes Codex
- IME provider list reflects Codex when authenticated
- Grok: documented as deferred, API-key-only entry in `RemoteApiBackend` only

**Smoke test:**
- Run setup.sh fresh, select Codex, complete OAuth — bridge `/providers` shows it
- Switch to Codex in IME, run Rewrite — works

### Phase 12 — Polish + signed F-Droid build

**Done means:**
- Onboarding wizard handles every entry path (no Termux, no a11y, only API keys, etc.)
- All user-visible errors have clear messages and recovery paths
- Health diagnostics screen: shows IME enabled state, a11y state, backend reachability, bridge version
- `README.md` has install instructions, screenshots, privacy explanation
- `fastlane/metadata/android/` populated for F-Droid auto-publish
- Reproducible build verified: `./gradlew assembleFdroidRelease` produces byte-identical APK on two machines
- Tagged release `v0.1.0`

**Smoke test:**
- Brand-new device, follow README from scratch — keyboard works without manual debugging
- F-Droid build metadata validates with `fdroidserver`
