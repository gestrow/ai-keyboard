## Phase 5a Review (carried into Phase 5b prompt)

- **Pinned versions:** Claude Code `2.1.112` (last cli.js entry, per ARCHITECTURE.md / PHASE_REVIEW.md "Known accepted corner cases"), Gemini CLI `latest`, Node `≥ 20` (Termux ships v25), bridge fastify `5.8.5` from Phase 4 — unchanged. Setup script is `setup/setup.sh` (598 lines, under 600-line cap).

- **Idempotency strategy:** every step does its own state probe before acting.
  - `set_allow_external_apps` → `grep -Fxq` then append.
  - `install_termux_packages` → `dpkg -s` per-package, install only the missing.
  - `install_claude_code_if_selected` → npm uninstall + reinstall pinned + verify cli.js exists + snapshot to `~/claude-code-pinned/` + wrapper at `~/bin/claude` + `ln -sf` into `$PREFIX/bin` + best-effort `claude config set autoUpdates false` + `--version` matches `2.1.112`. The `DISABLE_AUTOUPDATER=1` marker lines in `~/.profile` and `~/.bashrc` are appended only if not already present (verbatim match).
  - `deploy_bridge` → wipe + recopy + `npm install --omit=dev` + `node -e require()` smoke load. Always replaces (so redeploys with new bridge code work).
  - `register_services` → only installs `termux-services` if `sv` missing; `mkdir -p` + writing `run` and `log/run` is content-stable; `ensure_runsvdir_running` starts `runsvdir` via `setsid` only if not already running (auto-recovery for the first-session-after-install case).
  - `start_bridge` uses `sv restart` (not `sv up`) so redeploys actually bounce the running node process — first-run-correct because runit treats restart-of-down as start.

- **OAuth flow handling:** per provider, the script prints a preamble, detects existing creds at `~/.claude/.credentials.json` / `~/.gemini/oauth_creds.json`, then prompts "Enter to launch / s+Enter to skip". Skip is no-op iff already authenticated; otherwise warns and continues. Foreground launch is bare `claude` / `gemini` with stdio passthrough so the user can complete browser OAuth. Post-launch we re-check the creds file existence; non-zero CLI exits (Ctrl+C) are tolerated.

- **Built:** `setup/setup.sh` (executable, `bash -n` clean) + `setup/README.md` (78 lines, under 80-line cap, documents `adb push` dev workflow, hosted-quick-start placeholder for Phase 12, re-run / uninstall sections).

- **Smoke test (Pixel 6 Pro `1B101FDEE0006U`, fresh Termux):**
  - **Fresh-install:** ran end-to-end through pkg install → Claude Code 2.1.112 with snapshot+wrapper+symlink+`DISABLE_AUTOUPDATER=1` → `gemini` install → both OAuths completed → bridge deployed → `termux-services` auto-installed → `runsvdir` auto-started via the new fallback → first `/health` probe returned `{"ok":true,"version":"0.1.0","providers":[{"id":"claude","available":true},{"id":"gemini","available":true}]}`. Two cosmetic warnings on first run (`sv-enable returned non-zero`, `sv up returned non-zero` in the pre-fix version) were benign — runit auto-starts services without a `down` file as soon as `runsvdir` notices them, so `sv-enable` had nothing to remove and `sv up` raced an already-up service.
  - **Idempotent re-run** with skip-OAuth: every step short-circuited cleanly, ended with the same `/health` payload.
  - **Redeploy** (added comment to `bridge/server.js`, `adb push bridge`, re-ran with `s` at OAuth prompts): final `/health` reported `uptimeSeconds: 2` — proof the running node process was bounced and re-spawned with the new code on disk. (Pre-fix version reported `uptimeSeconds: 304`, exposing the `sv up` bug; the `sv restart` switch resolved it.)

- **Deviations from Phase 5a prompt:**
  - Banner numbers steps as 1/9 … 9/9 rather than the 6 high-level chunks the prompt named. Same surface area, finer logging.
  - `usage()` string trimmed and `print_banner` / `print_success` compressed to keep the script under 600 lines while preserving every required behavior.
  - **`ensure_runsvdir_running` added** beyond the prompt's task list — without it, fresh installs that auto-install `termux-services` in the same Termux session (the documented happy path) fail at `sv up` because `runsvdir` is started by `$PREFIX/etc/profile.d/start-services.sh` only on the *next* Termux session. The `setsid runsvdir -P $PREFIX/var/service` fallback restores the supervisor immediately, with a graceful "fully restart Termux" message if even that fails.
  - **`sv restart` instead of `sv up`** in `start_bridge` — required to make redeploy actually take effect (see smoke test above). Equivalent to start when the service is currently down.
  - Service uses both `run` and `log/run` (svlogd → `$PREFIX/var/log/sv/ai-keyboard-bridge/current`) so the log location promised in Phase 4's carry-over is real.
  - Minor: bash `whiptail`-vs-stdin choice fell to plain stdin numbered menu (whiptail isn't reliably available on Termux; the prompt allowed either path).

- **Carried issues:**
  - Fresh-install logs two cosmetic `!` warnings (`sv-enable`, and on first run `sv restart` may also warn briefly while runsv is attaching) before recovering and probing `/health` successfully. Considered noise rather than a bug — Phase 5b's orchestrator will read `/health` directly, so these warnings are dev-facing only. Possible Phase 12 polish: detect "already enabled" state and skip the warn.
  - `sv-enable` fails non-zero on first run because the service has no `down` file (and termux-services' `sv-enable` removes that file). Benign — runsvdir auto-starts the service the moment its `runsv` per-service supervisor attaches.
  - All Phase 1/2/2.5/3a/3b/4 carry-overs unchanged.

- **Touchpoints for Phase 5b:**
  - `setup/setup.sh` — `TermuxOrchestrator` will fire `RUN_COMMAND` intents at `bash $HOME/setup.sh ...` (or a similarly-installed copy). The `--providers` and `--yes` flags are designed to be orchestrator-driven; `--bridge-source` will need a stable on-device path (Phase 5b decides where).
  - `bridge/server.js` `/health` and `/providers` — IME wizard polls these (Phase 5a leaves them unchanged from Phase 4).
  - `bridge/server.js` `/reauth` returns 501. Phase 5b/6 will either implement it bridge-side or extend `setup.sh` with a `--reauth <provider>` flag the orchestrator fires via `RUN_COMMAND`.
  - `ensure_runsvdir_running` and the `sv restart` choice — Phase 5b's orchestrator can rely on the bridge always being supervised after a setup run; a separate "is the bridge alive" probe is still valuable post-reboot before Termux:Boot is installed.

- **Open questions for human reviewer:**
  1. The two cosmetic `!` warnings on first run — silence them with first-run detection, or leave for Phase 12 polish?
  2. `--reauth <provider>` flag on `setup.sh` vs implementing `/reauth` in the bridge — preference? My read: bridge-side is cleaner because it doesn't require IME-side intent plumbing for every CLI's quirks, but it can't open browsers, so the Termux-side path is unavoidable for first-time OAuth — possibly both.
  3. `sv restart` bounces the bridge on every `setup.sh` invocation. Negligible cost, but if Phase 5b's orchestrator runs the script for non-deploy reasons (health-check, reauth), needless restarts could blip in-flight requests. Should `setup.sh` grow a `--no-restart` flag?
