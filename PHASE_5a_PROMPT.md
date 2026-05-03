# Phase 5a Prompt — `setup.sh` Bootstrap (Termux-side only, no IME)

> **How to use this prompt:** open a fresh Claude Code session in `/Users/kacper/SDK/apk-dev/ai-keyboard/`. Tell it: *"Read `ARCHITECTURE.md`, `PHASE_REVIEW.md`, and the summaries for Phases 1, 2, 2.5, 3a, 3b, 4. Then execute the prompt in `PHASE_5a_PROMPT.md` exactly. Stop when Phase 5a's Definition of Done holds. Do not begin Phase 5b."*

---

You are working in `/Users/kacper/SDK/apk-dev/ai-keyboard/`. Phases 1–4 are complete. Read all planning + summary docs before any code work — they document the Termux/Claude Code constraints (Bionic libc, version pinning to v2.1.112, snapshot pattern, autoupdater disabled) that this script automates.

This phase delivers **one shell script: `setup/setup.sh`**. The IME side is unchanged; Phase 5b builds the `TermuxOrchestrator` and IME setup wizard. Phase 5a's script is a single file the user pastes into Termux, and the bridge ends up running. **No Android code in this phase at all.**

## Critical context from prior phases

- **Bridge** lives in `bridge/` from Phase 4. Subprocess adapters (`adapters/claude.js`, `adapters/gemini.js`), Fastify server on `127.0.0.1:8787`. Setup script deploys it into the user's Termux home.
- **Claude Code Termux constraint** (per `ARCHITECTURE.md`'s Termux CLI compatibility section + `PHASE_REVIEW.md`'s known corner cases): pin v2.1.112, snapshot to `~/claude-code-pinned/`, wrapper script at `~/bin/claude` execing `node $HOME/claude-code-pinned/cli.js "$@"`, symlink into `/data/data/com.termux/files/usr/bin/claude`, `DISABLE_AUTOUPDATER=1` exported from `~/.profile` AND `~/.bashrc`.
- **Gemini CLI** has no Termux constraints; just `npm i -g @google/gemini-cli` works.
- **Codex CLI** is Phase 11 territory — not part of 5a's provider menu.
- **`allow-external-apps = true`** must be in `~/.termux/termux.properties` for Phase 5b's `RUN_COMMAND` orchestration to work. Setup script writes it now since it's a one-liner that doesn't matter until Phase 5b but is annoying to do later.
- **Termux-services + Termux:Boot** are optional but recommended add-ons that auto-start the bridge. Detect them and integrate; don't require them.

## User prerequisites (executor cannot fulfill)

- [ ] Termux installed (F-Droid or GitHub APK)
- [ ] Phase 4 branch checked out OR the bridge source available somewhere reachable from Termux
- [ ] Pixel 6 Pro `1B101FDEE0006U` plugged in via USB

The script itself bootstraps everything else (Node, git, CLIs, OAuth, bridge install, service registration). The user does still have to complete browser OAuth flows interactively when each CLI prompts.

## Tasks

### 1. Branch from Phase 4

```
git checkout phase/04-termux-bridge
git pull --ff-only
git checkout -b phase/05a-setup-script
```

### 2. `setup/setup.sh` — single shell script, idempotent

Replace the placeholder `setup/README.md` from Phase 1. Create `setup/setup.sh`. Aim for **under 600 lines of bash including comments and pretty-printing**.

Idempotency rule: every step must check current state before acting. Re-running the script on a partially- or fully-installed system is a normal flow (user kicked off setup, lost network, retries). The script must finish in the same end-state regardless of how many times it's run.

#### Required structure

```bash
#!/data/data/com.termux/files/usr/bin/bash
# AI Keyboard bridge setup script (Phase 5a)
# Idempotent. Run inside Termux. Re-run any time to reconcile state.

set -uo pipefail

# Pinned versions — bump deliberately, never via `npm update`
CLAUDE_CODE_VERSION="2.1.112"   # last version with cli.js JS entry; ≥ 2.1.113 broken on Termux's Bionic libc
GEMINI_CLI_VERSION="latest"     # no known Termux compat issues
NODE_REQUIRED_MAJOR=20

# Paths
TERMUX_PREFIX="/data/data/com.termux/files/usr"
HOME_BIN="$HOME/bin"
CLAUDE_SNAPSHOT="$HOME/claude-code-pinned"
BRIDGE_DEST="$HOME/ai-keyboard-bridge"
TERMUX_PROPS="$HOME/.termux/termux.properties"

# Argument parsing: --bridge-source <path> tells the script where to copy bridge/ from.
# If not provided, default to <script_dir>/../bridge (assumes script is run from a checked-out repo)
# ... [full arg parsing here]

main() {
    require_termux                  # bail if not in Termux
    print_banner                    # one-screen welcome + what's about to happen
    confirm_proceed_or_exit         # y/n; can be skipped with --yes
    set_allow_external_apps         # ~/.termux/termux.properties
    install_termux_packages         # nodejs, git, termux-api, ripgrep, which
    select_providers                # interactive checkbox menu; --providers comma-list to skip
    install_claude_code_if_selected # full pin + snapshot + wrapper + symlink + DISABLE_AUTOUPDATER
    install_gemini_cli_if_selected  # simple npm install
    run_oauth_flows                 # interactive `claude` / `gemini` for each selected provider
    deploy_bridge                   # copy or download bridge/ → $BRIDGE_DEST; npm install
    register_services               # termux-services service + Termux:Boot hook if available
    start_bridge                    # sv-enable + sv up; verify /health responds
    print_success                   # final summary, what to do next
}

main "$@"
```

#### Detailed step requirements

**`require_termux`:** Detect we're inside Termux (`$TERMUX_PREFIX` exists, `pkg` command available). If not, print clear error and exit 1.

**`set_allow_external_apps`:** Idempotent grep+append. Already-set is a no-op.
```bash
mkdir -p "$(dirname "$TERMUX_PROPS")"
touch "$TERMUX_PROPS"
if ! grep -q '^allow-external-apps' "$TERMUX_PROPS"; then
    echo 'allow-external-apps = true' >> "$TERMUX_PROPS"
    termux-reload-settings
fi
```

**`install_termux_packages`:** `pkg update` then `pkg install -y nodejs git termux-api which ripgrep`. Verify `node --version` returns ≥ v20 after install (older Node has known issues with our adapters per Phase 4's findings — actually current Termux nodejs ships v25). Bail with a clear message if version is too old.

**`select_providers`:** Interactive menu. Use `whiptail` if available (it's in `dialog` package — install on demand if user picks interactive path) OR a numbered stdin loop. Output is a bash array `SELECTED_PROVIDERS=(claude gemini)` or subset.

If `--providers <comma-list>` was passed on command line, skip the menu and use that.

**`install_claude_code_if_selected`:**
1. Disable autoupdater FIRST (write to `~/.profile` AND `~/.bashrc` if not already; idempotent)
2. `npm uninstall -g @anthropic-ai/claude-code 2>/dev/null` (clears any clobbered install)
3. `npm i -g @anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}`
4. Verify `cli.js` exists at the expected npm path; bail with clear error if not (means version was pulled from registry; tell user to file an issue and try fallback versions)
5. Snapshot: `rm -rf "$CLAUDE_SNAPSHOT"; cp -r "$(npm root -g)/@anthropic-ai/claude-code" "$CLAUDE_SNAPSHOT"`
6. Wrapper script at `$HOME_BIN/claude` (heredoc + chmod +x)
7. Symlink: `ln -sf "$HOME_BIN/claude" "$TERMUX_PREFIX/bin/claude"`
8. `claude config set -g autoUpdates false 2>/dev/null` (best-effort; older versions don't support this)
9. Verify: `claude --version` returns `2.1.112`. Bail with diagnostic if not.

**`install_gemini_cli_if_selected`:**
1. `npm i -g @google/gemini-cli`
2. Verify: `gemini --version` returns something
3. No wrapper or snapshot needed — Gemini CLI runs natively on Termux

**`run_oauth_flows`:** For each selected provider:
- Print clear instruction: "We'll now run `claude` interactively. Sign in to claude.ai when the browser opens. Type `/exit` to return here when done."
- Prompt user: "Press Enter to continue, or 's' to skip if already authenticated"
- If proceeding: run the CLI interactively (foreground)
- After it returns: verify auth by running `claude /status` (or equivalent) or by checking the credential file's existence
- Same flow for `gemini auth login`

OAuth flows MUST be runnable separately. If the user skips this step (e.g., already authenticated), the rest of the script should still complete.

**`deploy_bridge`:**
- Source path: from `--bridge-source` argument, or auto-detect `$(dirname "$0")/../bridge` if running from a checked-out repo
- Destination: `$BRIDGE_DEST`
- `rm -rf "$BRIDGE_DEST"` (clean slate; idempotent)
- `cp -r "$BRIDGE_SOURCE" "$BRIDGE_DEST"`
- `cd "$BRIDGE_DEST" && npm install --omit=dev` (production-only deps; Phase 4's tests don't need to ship)
- Verify `node "$BRIDGE_DEST/server.js" --help` doesn't crash (or just check `node -e "require('$BRIDGE_DEST/server.js')"` doesn't throw)

**`register_services`:**
1. Check if `termux-services` is installed (`which sv`); if not, `pkg install -y termux-services`
2. Create service at `$TERMUX_PREFIX/var/service/ai-keyboard-bridge/run`:
   ```bash
   #!/data/data/com.termux/files/usr/bin/sh
   exec node $HOME/ai-keyboard-bridge/server.js 2>&1
   ```
3. `chmod +x` the run script
4. `sv-enable ai-keyboard-bridge`
5. If `$HOME/.termux/boot/` exists (Termux:Boot is installed), create `$HOME/.termux/boot/start-ai-keyboard-bridge`:
   ```bash
   #!/data/data/com.termux/files/usr/bin/sh
   termux-wake-lock
   sv up ai-keyboard-bridge
   ```
   `chmod +x` it. Print message recommending Termux:Boot if not detected.

**`start_bridge`:**
1. `sv up ai-keyboard-bridge`
2. Wait up to 10 seconds for `/health` to respond (curl loop with timeout)
3. On success: print the `/health` JSON output
4. On failure: print logs from `$TERMUX_PREFIX/var/log/sv/ai-keyboard-bridge/` (if termux-services made one) and bail with diagnostic

**`print_success`:** One-screen summary:
```
✓ Bridge installed and running at http://127.0.0.1:8787
✓ Providers: claude (authenticated), gemini (authenticated)

Next:
  - Health: curl http://127.0.0.1:8787/health
  - Restart: sv up ai-keyboard-bridge
  - Stop: sv down ai-keyboard-bridge
  - Logs: tail -f /data/data/com.termux/files/usr/var/log/sv/ai-keyboard-bridge/current

Open the AI Keyboard app's settings to switch your active backend
to "Termux bridge" (Phase 6+).
```

#### Error handling

Every step that can fail should:
- Print a clear, actionable error message ("Failed to install nodejs. Run `pkg update` and retry.")
- Exit non-zero so termux-services doesn't blindly proceed
- Where possible, suggest the next remediation step

Avoid `set -e` if it makes idempotent retries harder. Prefer explicit `if !...; then` checks.

### 3. `setup/README.md`

Replace the Phase 1 placeholder with a real README under 80 lines:

- One-paragraph purpose
- Prerequisites (Termux installed; Pixel device or any Android with Termux)
- Quick start: paste this in Termux:
  ```bash
  cd $(mktemp -d)
  curl -fsSL https://raw.githubusercontent.com/<repo>/<branch>/setup/setup.sh > setup.sh
  curl -fsSL https://raw.githubusercontent.com/<repo>/<branch>/bridge.tar.gz | tar xz
  bash setup.sh --bridge-source ./bridge
  ```
  (Note: actual hosted URL is Phase 12 polish; for Phase 5a, document the manual `adb push` flow as the development path. Both go in the README.)
- Manual development path: `adb push setup/setup.sh /data/local/tmp/`, `adb push bridge /data/local/tmp/`, in Termux: `cp /data/local/tmp/setup.sh ~/setup.sh; bash ~/setup.sh --bridge-source /data/local/tmp/bridge`
- Re-running: idempotent, safe
- Updating: re-run after `git pull` of the project
- Uninstall: `sv-disable ai-keyboard-bridge && rm -rf ~/ai-keyboard-bridge ~/claude-code-pinned ~/bin/claude $TERMUX_PREFIX/bin/claude`

### 4. Smoke test (real device, all on Pixel 6 Pro)

Manual end-to-end test, two scenarios.

**Fresh-install scenario** (validates the happy path):

1. **Reset Termux to clean state** (skip if your Termux is already fresh):
   - Long-press Termux icon → App info → Storage → Clear data; reopen Termux to re-init
2. Install Termux from F-Droid if not already (Termux has been wiped to test fresh)
3. Push setup script + bridge to device:
   ```
   adb push setup/setup.sh /data/local/tmp/
   adb push bridge /data/local/tmp/
   ```
4. Open Termux, run:
   ```bash
   bash /data/local/tmp/setup.sh --bridge-source /data/local/tmp/bridge
   ```
5. **Expected behavior:**
   - Confirms it's running in Termux
   - Installs nodejs, git, termux-api, etc.
   - Prompts for provider selection (claude + gemini both selected)
   - Installs Claude Code v2.1.112 with the snapshot+wrapper+symlink+autoupdater-disabled pattern; verify `claude --version` prints `2.1.112` afterward
   - Installs Gemini CLI; verify `gemini --version` prints
   - Runs OAuth flows (you complete browser sign-ins)
   - Deploys bridge to `~/ai-keyboard-bridge/`
   - Registers as termux-services service
   - If you have Termux:Boot, adds the boot hook
   - Starts bridge; `/health` responds with `{"ok":true,...}`
6. **Verify auto-restart:** `sv down ai-keyboard-bridge`, then `sv up ai-keyboard-bridge`. `/health` responds again.
7. **Verify idempotency:** re-run the entire setup script. Should complete without errors and end in the same state.

**Re-run-after-bridge-update scenario** (validates updates):

1. With a working install from above, modify `bridge/server.js` (e.g., add a comment), commit
2. `adb push bridge /data/local/tmp/`
3. Re-run the setup script with the same arguments
4. **Expected:** bridge gets redeployed to `~/ai-keyboard-bridge/` with the new code; service restarts; `/health` works

**Auth-already-complete scenario** (validates skip-OAuth path):

1. With Claude + Gemini already authenticated, re-run the setup script
2. When prompted "Press Enter to continue or 's' to skip", press 's' for each provider
3. **Expected:** script completes without re-running OAuth flows; bridge ends up running

### 5. Commit

Single commit on `phase/05a-setup-script`:

```
Phase 5a: Termux bootstrap script (setup.sh)

- One-paste setup.sh that bootstraps the Termux bridge from scratch:
  Termux package install, Claude Code v2.1.112 + snapshot + wrapper +
  autoupdater-disabled per architecture constraints, Gemini CLI install,
  interactive OAuth flows, bridge deployment, termux-services registration,
  optional Termux:Boot hook
- Idempotent: re-running reconciles state (covers retries after lost
  network, bridge updates, OAuth-already-done cases)
- Real-device tested on Pixel 6 Pro from clean Termux state through
  /health verification; service restart and idempotent re-run also verified
- No Android-side changes; Phase 5b wires this into the IME wizard
```

Do not push, do not merge.

## Constraints

- **Do not** modify any Android code in this phase. No Kotlin, no manifest, no resource changes.
- **Do not** modify the bridge code from Phase 4. If you find a bug, surface in the summary.
- **Do not** add Codex CLI to the provider menu — Phase 11.
- **Do not** edit `ARCHITECTURE.md` or `PHASE_REVIEW.md`. Surface findings.
- **Do not** require sudo, root, or any privileged operation. Termux runs as a regular Android app.
- **Do not** download arbitrary binaries from the network. Bridge source comes from `--bridge-source` (local), CLIs come from npm (their official source). The script's only network-fetching is `npm install` and Termux's `pkg`.
- **Do not** log API keys, OAuth tokens, or any user content. Logging "installed @anthropic-ai/claude-code@2.1.112" is fine; logging "$ANTHROPIC_API_KEY" or anything from the OAuth flow output is not.
- **Do** test on a real Termux session, not on macOS. Bash dialect differences (Termux uses GNU bash; macOS may use older bash by default) can bite. Verify on the device.
- **Do** keep the script under 600 lines including comments. If it's longer, you're trying to do too much — split helper logic into separate scripts in `setup/`.

## Phase 5a summary template

When finished, write `PHASE_5a_SUMMARY.md` at repo root, under 60 lines:

```markdown
## Phase 5a Review (carried into Phase 5b prompt)

- **Pinned versions:** <Claude Code, Gemini CLI default, Node minimum, fastify (from Phase 4)>
- **Idempotency strategy:** <how each step checks state before acting; what it does on re-run>
- **OAuth flow handling:** <how the script handles authenticated vs unauthenticated states; skip-already-done UX>
- **Built:** <terse outcome>
- **Smoke test:** <fresh-install / re-run / skip-OAuth scenarios; results>
- **Deviations from Phase 5a prompt:** <list, or "none">
- **Carried issues:** <list, or "none">
- **Touchpoints for Phase 5b:**
  - `setup/setup.sh` — `TermuxOrchestrator` will fire RUN_COMMAND intents at this script's commands (install, restart, reauth, healthcheck)
  - `bridge/server.js` `/health` and `/providers` — IME wizard polls these
  - `bridge/server.js` `/reauth` — Phase 5b/6 implements this; Phase 4 returned 501. Phase 5b might extend setup.sh to expose a `--reauth <provider>` flag the orchestrator can fire.
- **Open questions for human reviewer:** <items>
```

## Definition of done

- `setup/setup.sh` exists, is executable, runs successfully on a clean Termux install on Pixel 6 Pro `1B101FDEE0006U`
- After running: bridge is running as a service; `/health` returns `{"ok":true,...}` with both providers authenticated (or with whichever providers the user selected)
- Idempotent re-run from a fully-installed state completes without errors and end-state is identical
- Re-deploy scenario (push updated `bridge/`, re-run script) successfully replaces the bridge code and restarts the service
- Skip-OAuth scenario (re-run with auth already complete) does not trigger redundant browser flows
- `setup/README.md` documents quick-start and the `adb push` development workflow
- `PHASE_5a_SUMMARY.md` exists at repo root, under 60 lines
- Single commit on `phase/05a-setup-script`, not pushed, not merged

Then stop. Do not begin Phase 5b.
