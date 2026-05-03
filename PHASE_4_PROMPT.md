# Phase 4 Prompt — Termux Node Bridge + RUN_COMMAND Validation

> **How to use this prompt:** open a fresh Claude Code session in `/Users/kacper/SDK/apk-dev/ai-keyboard/`. Tell it: *"Read `ARCHITECTURE.md`, `PHASE_REVIEW.md`, `PHASE_1_SUMMARY.md`, `PHASE_2_SUMMARY.md`, `PHASE_2.5_SUMMARY.md`, `PHASE_3a_SUMMARY.md`, and `PHASE_3b_SUMMARY.md`, then execute the prompt in `PHASE_4_PROMPT.md` exactly. Stop when Phase 4's Definition of Done holds. Do not begin Phase 5a."* Start fresh.

---

You are working in `/Users/kacper/SDK/apk-dev/ai-keyboard/`. Phases 1–3b are complete. Read all planning + summary docs before any code.

This phase introduces a **new top-level deliverable**: a Node.js HTTP bridge that runs inside Termux on the user's Android device, spawns the user-installed `claude` / `gemini` CLIs as subprocesses, and exposes a normalized streaming HTTP API on `127.0.0.1:8787`. The IME does not yet consume this bridge — Phase 6 wires `TermuxBridgeBackend` as a peer of `RemoteApiBackend`. Phase 4 is foundation:

1. **Bridge codebase** in `bridge/` — Node + Fastify, subprocess adapters for Claude Code and Gemini CLIs, SSE streaming
2. **Android-side network policy** — extend the existing `network_security_config.xml` to permit cleartext on `127.0.0.1`/`localhost`
3. **Validation harness** — debug-only `TermuxValidationActivity` that fires a `com.termux.RUN_COMMAND` intent and reports whether Termux IPC works on this device. **This is a go/no-go for Phase 5.** If `RUN_COMMAND` is broken, ARCHITECTURE.md's fallback paths (long-lived foreground service + Unix socket, or boot-service-only model) must be evaluated before Phase 5a begins.

## Critical context from prior phases

- **Bridge runs in Termux's process, not Android's.** It cannot read the IME's `SecureStorage`. It uses each CLI's own OAuth-flowed credentials, which live in `~/.config/<cli>/` inside Termux's home directory — invisible to the Android app.
- **No AI SDK dependency in the bridge.** The bridge is a thin HTTP-to-subprocess proxy. Its only runtime npm dep is the HTTP framework (Fastify). The actual AI inference happens in `claude` / `gemini` CLI subprocesses the user authenticated separately.
- **Bridge SSE format is normalized**, not provider-specific. Both Anthropic's named-event SSE and Gemini's data-only chunked-JSON get translated by adapters into a single shape the IME consumes (described below). Phase 6's `TermuxBridgeBackend` parses this single shape regardless of which CLI is upstream.
- **Termux on Play Store is dead** since Nov 2022. Users (and the executor) install from F-Droid (https://f-droid.org/packages/com.termux/) or GitHub releases. This is documented user-facing friction; not Phase 4's job to fix.
- **Phase 5a will automate** Termux setup (install Node, install CLIs, OAuth, register the bridge as a `termux-services` service). Phase 4's bridge runs **manually** during testing — `node server.js` in a Termux session.
- **`network_security_config.xml`** already exists from Phase 3a with HTTPS-only entries for Anthropic + Gemini. You **add to** it, not recreate it.

## User prerequisites for the on-device smoke test

The executor cannot reasonably do these for the user. Confirm with the human reviewer that the following are in place on the Pixel 6 Pro **before running the device-side smoke test**:

- [ ] Termux installed (F-Droid or GitHub APK)
- [ ] In Termux: `pkg update && pkg install -y nodejs git`
- [ ] In Termux: `npm i -g @anthropic-ai/claude-code @google/gemini-cli`
- [ ] In Termux: `claude` run once interactively to complete the OAuth flow (browser opens, user logs in to claude.ai)
- [ ] In Termux: `gemini` run once interactively to complete OAuth (browser opens, user logs in to a Google account)

If the user has not done these, the *bridge unit tests* still pass (subprocess is mocked), the *bridge `/health` endpoint* still responds, but the *real-CLI streaming integration test* must be skipped and documented. Do **not** block Phase 4 ship on user prerequisites you can't fulfill — surface the gap in the summary.

## Tasks

### 1. Branch from Phase 3b

```
git checkout phase/03b-streaming-rewrite
git pull --ff-only          # in case the cleanup commits we discussed have landed
git checkout -b phase/04-termux-bridge
```

### 2. Bridge project structure

Create:

```
bridge/
├── package.json
├── package-lock.json
├── server.js
├── adapters/
│   ├── index.js          # provider registry
│   ├── claude.js         # spawns Claude Code CLI
│   └── gemini.js         # spawns Gemini CLI
├── README.md             # how to run standalone in Termux for development
├── .gitignore            # node_modules, *.log
└── test/
    ├── server.test.js
    └── adapters.test.js
```

The Phase 1 placeholder `bridge/README.md` exists already; replace it with a real one.

### 3. `package.json`

Pin to specific stable versions (verify via context7 for current). Minimal runtime deps:

```json
{
  "name": "ai-keyboard-bridge",
  "version": "0.1.0",
  "private": true,
  "description": "Local HTTP bridge between AI Keyboard IME and Termux-hosted CLI tools (Claude Code, Gemini CLI)",
  "main": "server.js",
  "scripts": {
    "start": "node server.js",
    "test": "node --test test/"
  },
  "dependencies": {
    "fastify": "<pinned>"
  },
  "devDependencies": {},
  "engines": {
    "node": ">=20"
  }
}
```

No AI SDK packages. Native Node test runner (`node --test`) — no Jest/Mocha.

### 4. HTTP server (`server.js`)

Fastify-based, listens on **`127.0.0.1:8787`** (loopback only — `host: '127.0.0.1'`, never `0.0.0.0`).

Endpoints:

#### `GET /health`

```json
{
  "ok": true,
  "version": "0.1.0",
  "uptimeSeconds": 1234,
  "providers": [
    { "id": "claude", "available": true,  "version": "0.1.x" },
    { "id": "gemini", "available": false, "reason": "gemini CLI not on PATH" }
  ]
}
```

`available` is determined by checking whether the CLI binary exists on PATH (e.g., `which claude`) and whether the OAuth credential file exists in the expected location. If a CLI is on PATH but not OAuth'd, mark `available: false` and set `reason: "not authenticated"`.

#### `GET /providers`

Same shape as `/health`'s `providers` array, returned standalone for clients that just want the list.

#### `POST /chat`

Request body (JSON):
```json
{
  "provider": "claude" | "gemini",
  "system": "<optional system prompt string>",
  "messages": [
    { "role": "user", "content": "the input text to rewrite" }
  ]
}
```

Response: SSE (`Content-Type: text/event-stream`). Normalized event shape — each event is one `data: {...}` line followed by a blank line:

```
data: {"type":"delta","text":"the next chunk of text"}

data: {"type":"delta","text":" more text"}

data: {"type":"done"}
```

Or on error:
```
data: {"type":"error","code":"AUTH_FAILURE","message":"Claude CLI is not authenticated. Run `claude` interactively in Termux to log in."}

data: {"type":"done"}
```

`code` is one of: `NETWORK_FAILURE`, `AUTH_FAILURE`, `RATE_LIMITED`, `TIMEOUT`, `CLI_NOT_FOUND`, `CLI_CRASHED`, `UNKNOWN`. These map cleanly to the IME's `ErrorType` enum on the Phase 6 consumer side.

#### `POST /reauth`

Request: `{ "provider": "claude" }`

For Phase 4: not implemented (returns 501 Not Implemented with explanatory body). Phase 5b/6 wires this to fire `claude /login` in Termux's foreground via the IME's `RUN_COMMAND` orchestrator — the bridge itself can't open a browser.

#### Error handling and lifecycle

- Listening on a port already in use: log a clear error message ("port 8787 in use; another bridge instance running?") and exit non-zero
- Graceful shutdown on `SIGINT` / `SIGTERM`: stop accepting new requests, abort in-flight subprocesses, close server, `process.exit(0)`
- Unhandled promise rejections: log + crash (let `termux-services` restart it in Phase 5a's setup; for Phase 4 manual runs, the user re-runs `node server.js`)
- All log output goes to stdout (Fastify's logger) — `termux-services` redirects to a log file in Phase 5a

### 5. Subprocess adapters

Common base in `adapters/index.js`:

```js
// pseudocode
class Adapter {
  constructor(provider) { this.provider = provider; }
  async isAvailable() { /* PATH check + auth file check */ }
  async chat(messages, system, sseStream, abortSignal) { /* implement */ }
}
```

Each adapter is responsible for:
1. Validating the CLI is on PATH
2. Spawning the CLI subprocess with appropriate flags for stream-json IO
3. Writing the user's input to the subprocess stdin in the format that CLI expects
4. Reading the subprocess stdout, line by line, parsing each line as JSON
5. Filtering to **text deltas only** (ignore tool-use events, system messages, metadata)
6. Translating to the bridge's normalized SSE shape and emitting via `sseStream.write()`
7. Handling subprocess exit (`Done` on clean exit, `Error` with `CLI_CRASHED` on non-zero exit)
8. Honoring `abortSignal` — if the HTTP client aborts the request, kill the subprocess

#### `adapters/claude.js`

Spawns `claude --print --input-format stream-json --output-format stream-json --include-partial-messages` (verify exact flags via `claude --help` from a recent Claude Code release; Anthropic's stream-json protocol may have evolved — pin to whatever the current stable CLI accepts).

Input writing: a single user-turn JSON object on stdin, then close stdin. The Claude Agent SDK / Claude Code CLI documents the input event shape (look up via context7 if uncertain).

Output parsing: each output event has a `type` field. The events that carry text content are typically `assistant` messages with `content[].type === "text"` blocks, or `partial_assistant` / `content_block_delta` events depending on the protocol version. Filter to text content, emit as `{"type":"delta","text":<content>}`. On the terminal `result` event (or process exit), emit `{"type":"done"}`.

Tool-use events are ignored entirely for Phase 4 — the IME use case is text rewriting, not agentic tasks.

#### `adapters/gemini.js`

Same pattern. Spawn `gemini --output-format stream-json` (or whatever flags the current Gemini CLI exposes — check `gemini --help`). Pipe the user's text to stdin.

The Gemini CLI stream output protocol is different from Claude Code's. Its event shape will likely include text-bearing events alongside other metadata. Filter to text deltas; ignore metadata.

If either CLI's stream-json mode is significantly more complex than expected, fall back to **non-streaming subprocess invocation**: spawn `claude --print "<text>"` (or equivalent), capture stdout, emit the entire response as a single `delta` followed by `done`. Document the fallback choice in the summary; Phase 12 polish can revisit for true token-by-token streaming.

### 6. Tests (`test/`)

Use Node's native test runner (`node --test`).

#### `test/server.test.js`

Unit tests with **mocked adapters** — no real subprocess spawning:

- `GET /health` returns the expected shape with mock adapter availability
- `GET /providers` returns the providers array
- `POST /chat` with a mocked adapter that emits 3 deltas + done — verify the bridge writes 4 SSE events in correct order
- `POST /chat` with unknown provider → 400 with clear error body
- `POST /chat` with mocked adapter that throws → bridge emits `{"type":"error","code":"UNKNOWN",...}` then `{"type":"done"}`, never crashes
- Localhost-only enforcement: starting the server with `host: '0.0.0.0'` should be impossible (verify by reading the bind address back from `server.address()`)

#### `test/adapters.test.js`

Adapter logic tests (still mock `child_process.spawn`):

- Claude adapter translates a stream of canned stdout JSON lines to the expected delta sequence
- Gemini adapter does the same with its different protocol
- Adapters detect missing CLI on PATH and return appropriate `CLI_NOT_FOUND` error
- Adapters detect missing OAuth credentials (mock filesystem) and return `AUTH_FAILURE`
- Abort signal causes adapter to kill the subprocess

Aim for ~15 tests total. Don't write integration tests against the real CLI — those happen on-device in the smoke test.

### 7. Bridge README (`bridge/README.md`)

Concise — under 100 lines. Cover:

- One-paragraph purpose: "The bridge spawns user-installed CLI tools (Claude Code, Gemini CLI) and exposes a normalized streaming HTTP API on localhost. The AI Keyboard IME consumes this when the user has selected the Termux backend (Phase 6+)."
- Prerequisites (Termux + Node + the two CLIs + OAuth)
- How to run standalone for development: `cd bridge && npm install && node server.js`
- How to test: `npm test`
- Endpoint reference (link to or duplicate the `/health` `/providers` `/chat` `/reauth` shapes)
- Notes: bridge has no AI SDK dependency; OAuth tokens never enter the bridge; bridge listens on loopback only; Phase 5a will automate the Termux setup

### 8. `network_security_config.xml` extension

Edit `app/app/src/main/res/xml/network_security_config.xml`. Phase 3a's structure is:

```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="false"> ... </base-config>
    <domain-config cleartextTrafficPermitted="false">
        <domain ...>api.anthropic.com</domain>
        <domain ...>generativelanguage.googleapis.com</domain>
        ...
    </domain-config>
</network-security-config>
```

**Add a new `<domain-config>` block** for localhost cleartext (do NOT modify the existing HTTPS-only blocks):

```xml
<domain-config cleartextTrafficPermitted="true">
    <domain includeSubdomains="false">127.0.0.1</domain>
    <domain includeSubdomains="false">localhost</domain>
</domain-config>
```

This permits cleartext HTTP only for the loopback addresses. The IME's HTTPS calls to Anthropic/Gemini remain HTTPS-only. Phase 10 expands this with RFC1918 ranges for `LocalLanBackend` (don't pre-empt that here).

### 9. `TermuxValidationActivity` (debug-only)

This is an Android-side validation harness, not part of the bridge.

Create `app/app/src/main/java/com/aikeyboard/app/ai/termux/TermuxValidationActivity.kt`. Gate the manifest declaration behind a build-type-specific manifest overlay so it only ships in debug builds — add it to **`app/app/src/debug/AndroidManifest.xml`** (create the overlay if it doesn't exist), not the main manifest.

Activity does:

1. On create, fire a `com.termux.RUN_COMMAND` intent with extras:
   - `com.termux.RUN_COMMAND_PATH = "/data/data/com.termux/files/usr/bin/echo"`
   - `com.termux.RUN_COMMAND_ARGUMENTS = arrayOf("termux ipc ok at " + System.currentTimeMillis())`
   - `com.termux.RUN_COMMAND_BACKGROUND = true`
   - `com.termux.RUN_COMMAND_RESULT_PENDING_INTENT = <PendingIntent that returns to this activity with the stdout>`
2. Display a status: "Sending intent..." → "Termux executed echo, stdout matches: ✅" or "❌ <reason>"
3. Show a "Try again" button
4. Show a button "Run a long-running command (sleep 3 + echo)" for testing background tracking
5. Display the full intent extras / result extras in a scrollable text view for debugging

Manifest declaration in the debug overlay:
```xml
<activity
    android:name="com.aikeyboard.app.ai.termux.TermuxValidationActivity"
    android:exported="true"
    android:label="Termux IPC Validation (debug)"
    android:theme="@style/platformActivityTheme">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

This gives debug builds a second launcher icon ("Termux IPC Validation") so you can run the harness without a release-shippable entry point.

The activity also requires the permission `com.termux.permission.RUN_COMMAND`. Declare it in the **debug** manifest only:
```xml
<uses-permission android:name="com.termux.permission.RUN_COMMAND" />
```

Verify after build: `apkanalyzer manifest print` on the debug APK shows the permission, on the release APK it does not.

### 10. Smoke test

#### Build verification (Mac)

```bash
cd app
./gradlew assembleFdroidDebug assemblePlayDebug
./gradlew lint
```

```bash
cd ../bridge
npm install
npm test
```

All clean. Bridge tests >= 15 cases passing.

#### Bridge on-device test (requires Termux + CLIs + OAuth per prerequisites)

Push the bridge source to the device. Either:
- `adb push bridge /data/local/tmp/bridge` then in Termux `cp -r /data/local/tmp/bridge ~/bridge`
- Or `cd ~ && git clone <local repo via termux-setup-storage>` (more complex)
- Or just `cd ~ && mkdir bridge && cd bridge && cat > server.js` and paste — only viable for tiny scripts

Document whichever path you used.

In Termux:
```bash
cd ~/bridge
npm install            # only fastify, fast
node server.js
```

In a second Termux session:
```bash
curl -s http://127.0.0.1:8787/health | head
# expect: {"ok":true,"version":"0.1.0",...,"providers":[{"id":"claude","available":true,...},...]}

curl -s http://127.0.0.1:8787/providers
# expect: array

# Real streaming test (only if CLIs are OAuth'd):
curl -N -s -X POST http://127.0.0.1:8787/chat \
  -H "Content-Type: application/json" \
  -d '{"provider":"claude","system":"Reply with one short sentence.","messages":[{"role":"user","content":"Say hi."}]}'
# expect: at least one data: {"type":"delta",...} line, then data: {"type":"done"}
```

Verify localhost-only:
```bash
curl -s http://0.0.0.0:8787/health  # should fail (connection refused or 404)
ip addr | grep wlan                 # find the device's LAN IP
# Try the LAN IP from another machine on the same network — should fail
```

#### Android RUN_COMMAND validation (requires Termux + `allow-external-apps=true` in `~/.termux/termux.properties`)

```bash
cd app
./gradlew installFdroidDebug
adb -s 1B101FDEE0006U shell am start -n com.aikeyboard.app.debug/com.aikeyboard.app.ai.termux.TermuxValidationActivity
```

The activity launches. Tap the "Send intent" button. Termux should briefly show a notification (or run silently, depending on Termux's config) and the activity reports success with the `echo` output.

**If this fails:** capture the exact failure mode and **STOP**. Do not proceed to commit. The user must decide whether to:
- Adjust Termux's `allow-external-apps` setting
- Verify Termux:API addon is installed (some `RUN_COMMAND` paths need it)
- Pivot ARCHITECTURE.md to one of the fallback IPC mechanisms (long-lived foreground service + Unix socket; or boot-service-only with bridge-side endpoints for management)

The fallback decision is a planning-doc change, not an execution change. Surface it to the human reviewer; do not amend ARCHITECTURE.md unilaterally.

#### Privacy logcat check

After exercising the bridge from a curl call:
```bash
adb -s 1B101FDEE0006U logcat -d | grep -i "<sample input text>"
```
Should be empty. The bridge runs in Termux, not Android — it shouldn't log to `logcat` at all. The only `logcat` entries from the test session should be the `TermuxValidationActivity`'s status updates (which never log user text).

#### Both flavors

The bridge work is flavor-agnostic. The `TermuxValidationActivity` is debug-only; verify both `assembleFdroidDebug` and `assemblePlayDebug` succeed and that **both flavors' debug APKs include the validation activity** (it's not a flavor split, just a buildType split).

`apkanalyzer manifest print` on each debug APK should show the activity; on the release APKs (when we eventually build them in Phase 12), it should not.

### 11. Commit

Single commit on `phase/04-termux-bridge`:

```
Phase 4: Termux Node bridge + RUN_COMMAND validation harness

- bridge/ : Fastify HTTP server on 127.0.0.1:8787 with /health,
  /providers, /chat (SSE), /reauth (501)
- Subprocess adapters for Claude Code and Gemini CLIs in stream-json mode
- Normalized SSE event shape: {type:"delta"|"done"|"error",...}
- 15+ unit tests (Node native test runner) with mocked subprocesses;
  no AI SDK runtime dependency
- network_security_config.xml: cleartext domain-config added for
  127.0.0.1 + localhost (Anthropic/Gemini blocks unchanged)
- TermuxValidationActivity (debug-only, src/debug/ overlay) fires
  com.termux.RUN_COMMAND intent and verifies stdout — go/no-go
  for Phase 5's RUN_COMMAND-based orchestrator
- Both flavors build clean; bridge tests pass; on-device validation
  succeeded on Pixel 6 Pro (or: documented failure path)
```

Do not push, do not merge.

## Constraints

- **Do not** add any AI SDK npm packages to the bridge — Fastify is the only runtime dep.
- **Do not** import or use any of the IME's Kotlin code from the bridge — they're separate processes with no shared state.
- **Do not** modify `RemoteApiBackend`, `AiClient`, or any other Phase 3 code.
- **Do not** wire the bridge into the IME yet — that's Phase 6.
- **Do not** edit `ARCHITECTURE.md` or `PHASE_REVIEW.md`. If `RUN_COMMAND` validation fails, surface to the human reviewer; the planning-doc pivot is their call.
- **Do not** ship `TermuxValidationActivity` outside debug builds. Verify with `apkanalyzer`.
- **Do not** log subprocess stdout content (the actual model output) at any log level. Subprocess stderr can be logged for debugging since it's CLI diagnostic output, not user content.
- **Do** mirror Phase 3b's normalized event shape on the bridge SSE side — Phase 6's consumer code will be much simpler if the bridge speaks a single protocol regardless of upstream provider.
- **Do** keep the bridge under ~600 lines of JavaScript total. If you need more than that for adapter logic, re-evaluate whether you're filtering aggressively enough (only text deltas, ignore everything else).

## Phase 4 summary template

When finished, write `PHASE_4_SUMMARY.md` at repo root, under 70 lines:

```markdown
## Phase 4 Review (carried into Phase 5a prompt)

- **Pinned bridge dependency versions:** <Fastify exact version, Node engine constraint>
- **Bridge protocol shape:** <normalized SSE event format documented>
- **Adapter implementations:** <Claude flags used, Gemini flags used, any fallback to non-streaming, exact stream-json field paths consumed>
- **RUN_COMMAND validation result:** <success/failure, exact reproduction steps, any environment quirks>
- **Built:** <terse outcome>
- **Smoke test:** <results across all device-side checks; note user prerequisites status>
- **Deviations from Phase 4 prompt:** <list, or "none">
- **Carried issues:** <list, or "none">
- **Touchpoints for Phase 5a:**
  - `bridge/server.js` and `bridge/adapters/*` — Phase 5a's setup.sh deploys these into Termux's home directory
  - `bridge/package.json` — Phase 5a runs `npm install` against this
  - `setup/setup.sh` — currently a placeholder README from Phase 1; Phase 5a builds the bootstrap
- **Open questions for human reviewer:** <items>
```

## Definition of done

- `./gradlew assembleFdroidDebug assemblePlayDebug` both succeed
- `cd bridge && npm test` passes (15+ tests)
- Bridge runs in Termux on Pixel 6 Pro, `/health` returns the expected JSON
- `network_security_config.xml` extended; `apkanalyzer` confirms localhost cleartext domain present in both flavor APKs
- `TermuxValidationActivity` exists in debug builds only (verified via `apkanalyzer`); release-flavor APKs do not contain it
- **Either:** `RUN_COMMAND` validation succeeded on the Pixel — `echo` output captured and matches; **or:** failure mode is documented in the summary with sufficient detail for the human reviewer to make the planning pivot.
- Real-CLI streaming test passed for at least one provider (or skipped with documented prerequisite gap)
- Privacy logcat grep empty for any user-input or model-output content
- `PHASE_4_SUMMARY.md` exists at repo root, under 70 lines
- Single commit on `phase/04-termux-bridge`, not pushed, not merged

Then stop. Do not begin Phase 5a.
