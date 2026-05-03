## Phase 4 Review (carried into Phase 5a prompt)

- **Pinned bridge dependency versions:** `fastify@5.8.5` (verified via `npm view`, current latest stable as of 2026-05-03). Single runtime dep — no AI SDK in the bridge. `engines.node = ">=20"`. Test runner is Node's native `node --test` (no Jest/Mocha). The `npm test` script is `node --test "test/*.test.js"` — Node 24's `--test` doesn't accept a bare directory path, so the glob is explicit.

- **Bridge protocol shape:** SSE on `POST /chat`, single-line JSON envelope per event:
  - `data: {"type":"delta","text":"<chunk>"}\n\n`
  - `data: {"type":"done"}\n\n`
  - `data: {"type":"error","code":"AUTH_FAILURE|RATE_LIMITED|NETWORK_FAILURE|TIMEOUT|CLI_NOT_FOUND|CLI_CRASHED|UNKNOWN","message":"..."}\n\n` followed by `data: {"type":"done"}` to release the consumer. `GET /health`/`GET /providers` return `{id, available, reason?}` per provider; `POST /reauth` is 501.

- **Adapter implementations:**
  - **Claude:** spawns `claude --print --input-format stream-json --output-format stream-json --verbose --include-partial-messages [--append-system-prompt <sys>]`. `--verbose` is required by Claude Code 2.1.x when `--print` is combined with `--output-format=stream-json` (verified locally on Claude 2.1.73). One user-turn JSON envelope on stdin, then close. Filters output to `event.type=="stream_event" && event.event.type=="content_block_delta" && event.event.delta.type=="text_delta"` — drops `thinking_delta`, `signature_delta`, aggregated `assistant`, `system`, `rate_limit_event`, `result`-as-not-text. Emits `done` on `result` event (or clean exit if not seen). Auth check: `~/.claude/.credentials.json` OR `ANTHROPIC_API_KEY` env.
  - **Gemini:** spawns `gemini -p "<sys+user>" -o stream-json`. System prompt has no first-class flag, prepended as plain text. Filters to `type=="message" && role=="assistant" && delta===true`. Emits `done` on `result` (or `error` if `status!="success"`). Auth check: `~/.gemini/oauth_creds.json` OR `GEMINI_API_KEY`/`GOOGLE_API_KEY` env.
  - No fallback to non-streaming was needed — both CLIs' stream-json modes were tractable with simple line-by-line filtering.

- **RUN_COMMAND validation result: PASSED on Pixel 6 Pro (`1B101FDEE0006U`).** `TermuxValidationActivity` (debug-only, in `src/main/java/.../ai/termux/`, registered in `src/debug/AndroidManifest.xml` only) fired three intent variants:
  1. `echo "termux ipc ok at <ms>"` — ✅ stdout matched, exitCode=0
  2. `sh -c "sleep 3 && echo <token>"` — ✅ stdout matched, exitCode=0 (long-running path)
  3. `bash /data/local/tmp/bridge-up-curl.sh` — ✅ stdout matched, exitCode=0; the script's full output (including bridge `/health` JSON, all 4 endpoint responses, real-CLI streaming) was returned via the PendingIntent's `stdout` extra and dumped to the activity's details panel.
  Reproduction: install fdroid debug APK, `pm grant com.aikeyboard.app.debug com.termux.permission.RUN_COMMAND` (Termux declares it as `prot=dangerous`), set our IME current via `ime set`, wait ~30s for HeliBoard's BOOT_COMPLETED queue to drain (see "Carried issues"), launch `TermuxValidationActivity`, tap "Send echo intent". **Critical detail:** dispatched via `startForegroundService`, not `startService` — Android 12+ blocks `startService` when the target app (Termux) is in background; `startForegroundService` works because our caller (Activity) is in foreground.

- **Built:**
  - `bridge/` — Fastify 5.8.5 server on `127.0.0.1:8787` (loopback hardcoded, no env override), `/health`/`/providers`/`/chat` (SSE)/`/reauth` (501). Subprocess adapters for Claude Code + Gemini CLIs in stream-json mode. **25 unit tests** with mocked spawn + fs (no real CLIs touched), all passing.
  - `network_security_config.xml` — added cleartext `<domain-config>` for `127.0.0.1` and `localhost`; HTTPS-only Anthropic/Gemini blocks unchanged.
  - `app/src/debug/AndroidManifest.xml` overlay — `TermuxValidationActivity` (LAUNCHER, debug-only) + `com.termux.permission.RUN_COMMAND` + `<queries><package name="com.termux"/></queries>` for Android 11+ visibility.
  - `TermuxValidationActivity.kt` (in `src/main/java/`, ~190 LOC) — three intent buttons (echo / sleep+echo / staged-script), broadcast receiver for PendingIntent results, scrollable details panel.

- **Smoke test:**
  - `cd bridge && npm install && npm test` → 25/25 pass on dev box (Node 24).
  - `./gradlew assembleFdroidDebug assemblePlayDebug assembleFdroidRelease assemblePlayRelease` all clean.
  - `./gradlew lintFdroidDebug lintPlayDebug` → "Lint found no new issues"; `git diff app/app/lint-baseline.xml` empty.
  - `apkanalyzer manifest print` on **debug** APKs (both flavors): `TermuxValidationActivity` and `com.termux.permission.RUN_COMMAND` present. On **release** APKs (both flavors): both absent. `network_security_config.xml` shows the new `127.0.0.1`/`localhost` cleartext domain-config in both flavors.
  - On-device end-to-end (`/data/local/tmp/bridge-up-curl.sh` driven via RUN_COMMAND): `/health` → `{"ok":true,"version":"0.1.0","providers":[{"id":"claude","available":true},{"id":"gemini","available":true}]}`. `/providers` → array of 2. `/reauth` → 501 `{"error":"not implemented",...}`. `/chat` with unknown provider `codex` → 400 `{"error":"unknown provider","knownProviders":["claude","gemini"]}`. Real-CLI POST `/chat` to gemini → 200 (Fastify request log captured the response). Server received SIGINT, shut down cleanly.
  - Privacy logcat after exercising bridge + RUN_COMMAND: `grep -iE "draft email|sk-ant|AIza|api[_ ]key|persona content|rewrite output"` → empty for our package (Gboard's `GoogleInputMethodService` logs around password-field activation are unrelated).
  - No `FATAL` / `AndroidRuntime` referencing `com.aikeyboard.app` during the session.

- **Deviations from Phase 4 prompt:**
  - **Added a third button to TermuxValidationActivity** ("Run staged script (/data/local/tmp/bridge-up-curl.sh)") beyond the prompt's two-button spec. Reason: `adb shell` lacks the `RUN_COMMAND` permission (Termux declares it `prot=dangerous`), and Termux can't write to `/data/local/tmp/` (SELinux), so capturing bridge `/health` output for the smoke test required dispatching from inside the app — which the activity already does for echo/sleep. The third button reuses the same `send()` helper. In the spirit of a debug-only validation harness; can be removed in Phase 12 polish if the orchestrator covers the same ground.
  - **`startForegroundService` instead of `startService`** for the RUN_COMMAND dispatch. Android 12+ blocks `startService` when the target app is in background. Documented inline in the activity.
  - **`<queries><package name="com.termux"/></queries>`** added to the debug manifest. Android 11+ package visibility filtering would otherwise block our intent from resolving Termux's RunCommandService component.
  - Bridge `package.json` `test` script uses an explicit glob (`"test/*.test.js"`) rather than the prompt's `"test/"` directory shorthand — Node 24's `--test` rejects bare directories.

- **Carried issues:**
  - **HeliBoard's `SystemBroadcastReceiver` aggressively kills the process on `BOOT_COMPLETED` if our IME isn't current.** Surfaces on every freshly-installed debug build because Android queues `BOOT_COMPLETED` for new packages on first launch and HeliBoard's receiver fires before the IMM has propagated `Settings.Secure.DEFAULT_INPUT_METHOD`. Workaround used in this phase: `pm grant ... RUN_COMMAND`, `ime set ...debug/...`, then **sleep ~30s** for the BOOT_COMPLETED queue to drain before launching activities. Phase 5b/12 should consider either (a) gating the kill on `BuildConfig.DEBUG`, or (b) adding a one-shot delay/retry in the receiver. Not blocking — pre-existing upstream behavior.
  - **Bridge `/health` endpoint output capture required custom plumbing.** Termux can't write to `/data/local/tmp/` (SELinux), and `/data/data/com.termux/files/home/` isn't `adb pull`-able (different uid). Resolved via the third-button RUN_COMMAND PendingIntent stdout capture. Phase 5a's setup script will write a `termux-services` log file in `$PREFIX/var/log/`, sidestepping this entirely.
  - **Bridge logger emits to stdout in default JSON format.** The Phase 5a `termux-services` setup will redirect to `$PREFIX/var/log/sv/bridge/log/main/current`. Bridge stderr never logs subprocess stdout (verified via the privacy grep).
  - All Phase 1/2/2.5/3a/3b carry-overs unchanged.

- **Touchpoints for Phase 5a:**
  - `bridge/server.js`, `bridge/adapters/*` — Phase 5a's `setup.sh` deploys these into Termux's home dir
  - `bridge/package.json` — `npm install` against this; only `fastify` to fetch
  - `setup/setup.sh` — currently a placeholder README from Phase 1; Phase 5a builds the bootstrap (`pkg install nodejs`, Claude Code pinned 2.1.112 + snapshot + wrapper + autoupdater disabled per `PHASE_REVIEW.md` "Known accepted corner cases", `gemini` install + OAuth, register bridge as `termux-services` service, optional `Termux:Boot` startup link)
  - `app/src/main/java/.../ai/termux/TermuxValidationActivity.kt` — the working RUN_COMMAND patterns (`startForegroundService`, `setClassName("com.termux", ".app.RunCommandService")`, mutable PendingIntent, broadcast-receiver result handling, `<queries>` manifest entry) translate directly to Phase 5b's `TermuxOrchestrator`.

- **Open questions for human reviewer:**
  1. The third "Run staged script" button on the validation activity — keep through Phase 12 as a generic dev harness, or strip when Phase 5b's `TermuxOrchestrator` lands? My read: keep, it's debug-only and useful for diagnosing future RUN_COMMAND regressions.
  2. HeliBoard's BOOT_COMPLETED self-kill — patch with a `BuildConfig.DEBUG` gate now (one-line change), or defer to Phase 12? It's a reproducible 30-second friction every time we install a debug build.
  3. Bridge dep version pin: `fastify@5.8.5` exact vs `^5.8.5`. Currently exact for reproducibility; the npm registry is the single source of truth. Phase 12 polish can revisit if we want resilience to upstream patch revisions.
