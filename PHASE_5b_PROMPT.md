# Phase 5b Prompt — `TermuxOrchestrator` + IME Termux Setup Wizard

> **How to use this prompt:** open a fresh Claude Code session in `/Users/kacper/SDK/apk-dev/ai-keyboard/`. Tell it: *"Read `ARCHITECTURE.md`, `PHASE_REVIEW.md`, and the summaries for Phases 1, 2, 2.5, 3a, 3b, 4, 5a. Then execute the prompt in `PHASE_5b_PROMPT.md` exactly. Stop when Phase 5b's Definition of Done holds. Do not begin Phase 6."*

---

You are working in `/Users/kacper/SDK/apk-dev/ai-keyboard/`. Phases 1–5a are complete. Read all planning + summary docs first.

This phase wires Phase 5a's Termux setup script into the IME via:

1. A small **`--reauth <provider>` extension** to `setup.sh` (limited mode that only runs interactive OAuth for one provider; skips bridge deploy / package install / etc.)
2. A new Kotlin **`TermuxOrchestrator`** class that fires `com.termux.RUN_COMMAND` intents with `PendingIntent` callbacks
3. A new Compose **Termux Setup Wizard** reachable from the existing `BackendsScreen`, alongside Anthropic and Gemini API key entry — **Termux becomes a third backend choice**, with its own multi-step setup UX
4. **Background bridge health-polling** via Ktor, using `127.0.0.1:8787/health` and `/providers`

The user-facing payoff: tap a button in keyboard settings, follow a wizard, end up with the bridge running. No more manual `adb push` + Termux command pastes.

## Critical context from prior phases

- **Phase 4's `TermuxValidationActivity`** (debug-only) proved `RUN_COMMAND` IPC works on the test device. Phase 5b's `TermuxOrchestrator` is the production-ready version of that wiring — same intent shape, `PendingIntent` for results, accept `allow-external-apps = true` is already set (Phase 5a's setup.sh writes it).
- **Phase 5a's setup.sh** at `setup/setup.sh` (598 lines) already supports `--providers`, `--yes`, and `--bridge-source` flags. Phase 5b extends it with `--reauth <provider>` only — no other behavior changes.
- **The script's on-device path** matters. For Phase 5b we standardize on **`$HOME/ai-keyboard-setup.sh`** in Termux. The wizard's initial-install step instructs the user to copy it there exactly once (via `curl ... > $HOME/ai-keyboard-setup.sh` from a placeholder URL, OR via the `adb push` fallback documented in `setup/README.md`); after that, the orchestrator fires `RUN_COMMAND` for `bash $HOME/ai-keyboard-setup.sh ...` for all subsequent actions.
- **Phase 3a's `BackendsScreen`** has Anthropic and Gemini rows with API-key edit screens. Phase 5b adds **Termux Bridge** as a third row. Tapping it opens the wizard if not yet configured, or a status screen if configured.
- **Phase 2.5's keyboard-surface UI invariants** apply only to the keyboard surface (key area + command row + preview strip). The wizard is part of `AiSettingsActivity`, a normal Compose Activity, so use Material3 normally. No runtime `Colors` requirement here.
- **Network security config** already permits cleartext to `127.0.0.1` from Phase 4. Ktor calls to localhost work without further configuration.
- **No bridge code changes** beyond what's already in place. The bridge's `/reauth` stays 501; we orchestrate via `RUN_COMMAND` instead.

## Tasks

### 1. Branch from Phase 5a

```
git checkout phase/05a-setup-script
git pull --ff-only
git checkout -b phase/05b-orchestrator-and-wizard
```

### 2. Extend `setup/setup.sh` with `--reauth <provider>` (limited mode)

Add a new flag `--reauth <provider>` that, when present:
- Skips: `install_termux_packages`, `install_claude_code_if_selected`, `install_gemini_cli_if_selected`, `deploy_bridge`, `register_services`, `start_bridge`, `print_success`
- Runs: only the existing OAuth function for the one named provider, using the existing detection-then-launch logic (find the cred file path; if missing or `--force`, run `claude` or `gemini` interactively)
- Validates: `<provider>` is one of `claude` or `gemini`; bails with usage message if not

Implementation pattern:
```bash
if [[ -n "${REAUTH_PROVIDER:-}" ]]; then
    require_termux
    case "$REAUTH_PROVIDER" in
        claude) run_oauth_for_claude ;;
        gemini) run_oauth_for_gemini ;;
        *) echo "Unknown provider: $REAUTH_PROVIDER. Valid: claude, gemini." >&2; exit 2 ;;
    esac
    exit 0
fi
```

Existing OAuth functions stay as they are; just call them in isolation. The `--reauth` path does **not** restart the bridge — credentials are read from disk on each CLI subprocess spawn, so existing in-flight bridge sessions pick up new auth automatically on next request.

Update `setup/README.md` with one short section documenting `--reauth`.

### 3. `TermuxOrchestrator` Kotlin class

New file: `app/app/src/main/java/com/aikeyboard/app/ai/termux/TermuxOrchestrator.kt`

```kotlin
package com.aikeyboard.app.ai.termux

class TermuxOrchestrator(private val context: Context) {

    enum class Status {
        TERMUX_NOT_INSTALLED,
        TERMUX_INSTALLED_NOT_CONFIGURED,   // allow-external-apps=true not yet present
        SCRIPT_NOT_DEPLOYED,                // ai-keyboard-setup.sh not at $HOME
        BRIDGE_NOT_RUNNING,
        BRIDGE_RUNNING,
    }

    suspend fun detectStatus(): Status

    fun launchFDroidTermuxPage()             // Intent.ACTION_VIEW → F-Droid
    fun launchTermuxApp()                    // launches Termux's main activity

    /**
     * Fires a RUN_COMMAND intent invoking `bash $HOME/ai-keyboard-setup.sh` with the given args.
     * The optional callback receives stdout/stderr from the script and an exit code.
     * Termux may run the script in foreground (visible) or background depending on intent extras.
     */
    suspend fun runSetupScript(args: List<String>, foreground: Boolean = true): RunResult

    /** Convenience: runSetupScript(listOf("--reauth", provider.cliName), foreground = true) */
    suspend fun reauthProvider(provider: Provider): RunResult

    /**
     * Polls 127.0.0.1:8787/health every 1s for up to maxSeconds.
     * Emits status updates (timestamps, partial responses, failures) so the wizard can show progress.
     */
    fun observeBridgeHealth(maxSeconds: Int = 600): Flow<HealthState>

    suspend fun fetchProviders(): List<ProviderStatus>     // GET /providers; null on bridge unreachable

    sealed interface HealthState {
        data object Polling : HealthState
        data class Healthy(val providers: List<ProviderStatus>, val uptimeSeconds: Long) : HealthState
        data class Unreachable(val attemptedAtMs: Long) : HealthState
    }

    data class RunResult(val exitCode: Int, val stdout: String, val stderr: String)

    data class ProviderStatus(val id: String, val available: Boolean, val reason: String?)
}
```

Implementation notes:
- **`detectStatus()`**: in order — `PackageManager` check for `com.termux`; if installed, file existence at `$HOME/ai-keyboard-setup.sh` via `RUN_COMMAND` echo probe (or just attempt setup and let it fail gracefully); ping `/health` via Ktor.
- **`runSetupScript()`**: use Phase 4's `RUN_COMMAND` plumbing. Returns `RunResult` via `PendingIntent` callback. Use `suspendCancellableCoroutine` to bridge the callback into a suspend function. Timeout after some sane interval (3 min default; OAuth flows can take longer if the user is slow on the browser).
- **`observeBridgeHealth()`**: cold `Flow` using Ktor `HttpClient` (already declared from Phase 3a). 1-second polling interval. Emits `Polling` → eventually `Healthy(...)` or `Unreachable(...)`. Cancellable via standard Flow mechanics.
- **Privacy**: never log RUN_COMMAND result stdout/stderr (could contain CLI prompt text). Surface in the UI only.
- **Threading**: all I/O on `Dispatchers.IO`; suspend functions throughout. UI calls from `viewModelScope` or `LaunchedEffect`.

Singleton accessor pattern: `TermuxOrchestrator.getInstance(context)` using `applicationContext`.

### 4. Termux Setup Wizard (Compose, multi-step)

Add a new sub-flow under the existing `BackendsScreen` route. The composables live at `app/app/src/main/java/com/aikeyboard/app/ai/ui/termux/`.

#### Update `BackendsScreen`

Add a third row below Anthropic and Gemini:

- **Termux Bridge** — shows current status from `TermuxOrchestrator.detectStatus()`:
  - "Bridge running — N providers authenticated" (BRIDGE_RUNNING)
  - "Bridge not running — Tap to set up" (BRIDGE_NOT_RUNNING / SCRIPT_NOT_DEPLOYED / TERMUX_INSTALLED_NOT_CONFIGURED)
  - "Termux not installed — Tap to install" (TERMUX_NOT_INSTALLED)

Tapping the row navigates to a new route `backends/termux` which routes to either the wizard (if not configured) or the status/management screen (if already running).

#### `TermuxSetupWizardScreen` (Compose)

Driven by `TermuxOrchestrator.detectStatus()`. Renders one of these sub-screens depending on status:

**Step 1 — Termux not installed**
- Explain what Termux is and why we need it (one short paragraph; reference user's privacy story)
- "Install Termux from F-Droid" button → `launchFDroidTermuxPage()`
- "Already installed via GitHub APK" button → re-runs detection
- "Cancel" returns to BackendsScreen

**Step 2 — Termux installed, setup script not yet deployed**
- Explain: "We need to copy our setup script into Termux. This is a one-time step."
- Show a code block with the bootstrap command for the user to paste:
  ```
  curl -fsSL <PLACEHOLDER_URL> > $HOME/ai-keyboard-setup.sh && bash $HOME/ai-keyboard-setup.sh
  ```
  (For Phase 5b's testing — since we have no hosted URL yet — the wizard shows the manual development workflow as a fallback note: "If the curl URL above isn't yet hosted, run `adb push setup/setup.sh /data/local/tmp/` from your dev machine, then in Termux: `cp /data/local/tmp/setup.sh $HOME/ai-keyboard-setup.sh && bash $HOME/ai-keyboard-setup.sh`.")
- Copy-to-clipboard button for the bootstrap command
- "Open Termux" button → `launchTermuxApp()`
- "I've completed setup, check now" button → re-runs detection
- Polls `observeBridgeHealth(maxSeconds = 600)` in the background; auto-advances to step 4 on success

**Step 3 — Setup script deployed but bridge not running**
- "Start the bridge" button → fires `runSetupScript(args = emptyList(), foreground = true)`
- Shows progress: "Running setup..." with a spinner
- Polls `observeBridgeHealth` while the script runs
- On success → advances to step 4
- On failure → shows the captured stderr (truncated; offer "Copy log to clipboard")

**Step 4 — Bridge running (success)**
- Green checkmark, "Termux backend ready"
- List of providers and their auth status from `fetchProviders()`
- "Done" button returns to BackendsScreen

#### `TermuxBridgeStatusScreen` (post-setup management)

Shown when `detectStatus()` returns `BRIDGE_RUNNING`. Reachable from BackendsScreen → Termux Bridge row.

- Top: "Bridge running" with uptime, version
- Per provider (Claude, Gemini):
  - Auth status (✓ / ✗)
  - "Re-authenticate" button → `reauthProvider(...)` (fires `RUN_COMMAND` for `setup.sh --reauth <provider>` in foreground; user completes browser; wizard polls `/providers` until state changes)
- "Restart bridge" button → fires `runSetupScript(emptyList())` (full re-run; idempotent, ends with restart per Phase 5a's `sv restart`)
- "Stop bridge" button → fires `RUN_COMMAND` for `sv-disable ai-keyboard-bridge && sv down ai-keyboard-bridge`
- All long polling cancellable on screen exit (no orphaned coroutines)

### 5. Wire into AiSettingsNavHost

Add the new routes:
- `backends/termux` → `TermuxSetupWizardScreen` or `TermuxBridgeStatusScreen` based on detected status
- The existing hub layout from Phase 3a remains; Backends now has three rows (Anthropic / Gemini / Termux Bridge)

### 6. Smoke test (Pixel 6 Pro)

#### Setup-from-scratch scenario

1. Wipe Termux app data: Android Settings → Apps → Termux → Storage → Clear data
2. Reinstall Termux from F-Droid
3. Install fresh fdroid debug build of the IME (`adb install -r app/.../ai-keyboard-3.9-fdroid-debug.apk`)
4. Open keyboard, navigate to AI Settings → Backends → Termux Bridge
5. Wizard shows step 1 ("Termux not installed" if Termux's data was just wiped, or step 2 if Termux is fresh-install but no script)
6. Follow steps as the wizard guides
7. Verify wizard auto-advances to "Bridge running" after setup completes
8. Backends screen now shows "Bridge running — 2 providers authenticated"

#### Re-auth scenario

1. From the Termux Bridge status screen, tap "Re-authenticate Claude"
2. Termux opens in foreground with `claude` running
3. Complete the browser sign-in flow
4. Return to the IME; wizard's polling detects the cred file change → "Claude authenticated ✓"
5. Repeat for Gemini

#### Restart scenario

1. From status screen, tap "Restart bridge"
2. Termux runs setup.sh (limited cycle — packages already installed, snapshot already exists, all idempotent fast paths)
3. Bridge restart confirmed via uptime drop in `/health` payload
4. Wizard returns to status screen, no errors

#### Stop scenario

1. Tap "Stop bridge"
2. Verify bridge is no longer responding on 127.0.0.1:8787 (curl from Termux: `curl -m 2 http://127.0.0.1:8787/health` should fail/timeout)
3. Backends row reverts to "Bridge not running — Tap to set up"

#### Architecture invariants

- `apkanalyzer manifest print` on the play APK confirms no `com.termux.permission.RUN_COMMAND` — that lives only in the debug overlay from Phase 4 + production manifest entry from Phase 5b. Wait: Phase 5b's production manifest needs the permission too, since the orchestrator runs in release builds. **Add `com.termux.permission.RUN_COMMAND` to the main manifest** (not just debug). Verify it's in both flavor APKs.
- `<queries><package name="com.termux"/></queries>` already in debug manifest from Phase 4; needs to be in main manifest now too for production package detection.
- `apkanalyzer dex` on play APK shows `TermuxOrchestrator` and the new wizard composables — production wiring is real.
- Privacy logcat after a full setup + reauth cycle: no API keys, no OAuth tokens, no command-line stdout containing user content (some CLI prompt text is OK in stderr; avoid stdout).

### 7. Commit

Single commit on `phase/05b-orchestrator-and-wizard`:

```
Phase 5b: TermuxOrchestrator + IME Termux Setup Wizard

- setup.sh extended with --reauth <provider> flag (limited mode that
  skips bridge deploy and only runs the OAuth flow for one provider)
- TermuxOrchestrator (Kotlin): RUN_COMMAND intent plumbing with
  PendingIntent → suspend-function bridging, Ktor health/providers
  polling, status detection
- Termux Setup Wizard (Compose): multi-step flow under BackendsScreen,
  status-driven sub-screens (install Termux / deploy script / start
  bridge / running)
- Termux Bridge Status Screen: re-auth per provider, restart, stop
- Manifest: com.termux.permission.RUN_COMMAND + <queries> for
  com.termux moved to main manifest (was debug-only from Phase 4)
- Both flavors build clean; smoke-tested end-to-end on Pixel 6 Pro
```

Do not push, do not merge.

## Constraints

- **Do not** modify the bridge code (Phase 4 territory). `/reauth` stays 501.
- **Do not** modify `RemoteApiBackend` or any other Phase 3 client code. The Termux backend's `AiClient` impl is Phase 6, not 5b.
- **Do not** add the keyboard-surface UI invariants (runtime Colors, inset extension). The wizard runs in `AiSettingsActivity`, not on the keyboard surface.
- **Do not** modify HeliBoard's settings package or LatinIME core.
- **Do not** add new dependencies — Ktor and Compose are already in place.
- **Do not** edit `ARCHITECTURE.md` or `PHASE_REVIEW.md`. Surface findings in the summary.
- **Do not** log `RunResult.stdout` or `RunResult.stderr` content at any log level beyond DEBUG-gated diagnostics. The script's stderr can contain interactive prompt content.
- **Do** verify the on-device flow end-to-end. The wizard is a UX-heavy phase; unit tests can't catch the experience issues.
- **Do** make polling cancellable. Compose's `LaunchedEffect` + structured concurrency handles this if you don't fight it.

## Phase 5b summary template

When finished, write `PHASE_5b_SUMMARY.md` at repo root, under 70 lines:

```markdown
## Phase 5b Review (carried into Phase 6 prompt)

- **Orchestrator surface:** <list of public methods, threading model, callback-to-suspend bridging approach>
- **Wizard navigation:** <route structure, step transitions, status detection cadence>
- **Manifest changes:** <RUN_COMMAND permission, queries, what moved from debug to main>
- **setup.sh extension:** <how --reauth integrates with existing OAuth functions; what it skips>
- **Built:** <terse outcome>
- **Smoke test:** <results: from-scratch / re-auth / restart / stop>
- **Deviations from Phase 5b prompt:** <list, or "none">
- **Carried issues:** <list, or "none">
- **Touchpoints for Phase 6:**
  - `app/app/src/main/java/com/aikeyboard/app/ai/client/` — Phase 6 adds `TermuxBridgeBackend` here
  - `TermuxOrchestrator.observeBridgeHealth()` — Phase 6's backend can subscribe to this for "is bridge alive" status
  - `bridge/server.js` `/chat` endpoint — Phase 6's `TermuxBridgeBackend` consumes this with the same SSE shape Phase 4 documented
- **Open questions for human reviewer:** <items>
```

## Definition of done

- `setup.sh --reauth <provider>` works in Termux: completes OAuth for the named provider, no bridge restart, no other setup steps run
- `TermuxOrchestrator` Kotlin class exists; `detectStatus()`, `runSetupScript()`, `reauthProvider()`, `observeBridgeHealth()`, `fetchProviders()` all functional
- Termux Setup Wizard reachable via AiSettings → Backends → Termux Bridge; full from-scratch flow ends with the bridge running
- Termux Bridge Status Screen reachable when bridge is running; re-auth, restart, stop all work via `RUN_COMMAND`
- Both flavor APKs build clean; lint clean; lint-baseline diff empty
- `apkanalyzer` confirms `com.termux.permission.RUN_COMMAND` is in BOTH fdroid and play production APKs (not just debug)
- Smoke test on Pixel 6 Pro: from-scratch + re-auth + restart + stop scenarios all pass; logcat clean
- `PHASE_5b_SUMMARY.md` exists at repo root, under 70 lines
- Single commit on `phase/05b-orchestrator-and-wizard`, not pushed, not merged

Then stop. Do not begin Phase 6.
