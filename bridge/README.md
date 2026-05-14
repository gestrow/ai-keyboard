# bridge/

A local HTTP bridge that spawns user-installed CLI tools (Claude Code, Gemini CLI, OpenAI Codex) and exposes a normalized streaming HTTP API on `127.0.0.1:8787`. The AI Keyboard IME consumes this when the user has selected the Termux backend (Phase 6+).

The bridge has **no AI SDK dependency**. Its only runtime npm package is Fastify. AI inference happens in the spawned subprocesses; OAuth credentials live in each CLI's own config dir and never enter the bridge's memory.

## Prerequisites

- Termux (F-Droid build — Play Store version is dead since 2022)
- Node ≥ 20: `pkg install nodejs`
- `claude` (Claude Code, pinned to 2.1.112 with snapshot + wrapper + autoupdater disabled — see `ARCHITECTURE.md` "Termux CLI compatibility constraints" and `PHASE_REVIEW.md` "Known accepted corner cases")
- `gemini` (Google Gemini CLI: `npm i -g @google/gemini-cli`, then `gemini` interactively to OAuth)
- `codex` (OpenAI Codex, pinned to 0.42.0; see Codex prerequisites below)
- Each CLI must be authenticated (run interactively once)

Phase 5a's `setup.sh` automates the prereqs. xAI Grok is not exposed through the bridge — it is served by `RemoteApiBackend` (direct HTTPS to `api.x.ai`) and therefore does not appear in `/providers`.

### Codex prerequisites

- Install: `npm i -g @openai/codex@0.42.0`. The version pin matches `setup.sh`'s `CODEX_VERSION` constant. v0.43+ regressed with the prctl issue tracked at openai/codex#6757; do not bump without re-running `setup.sh` end-to-end and the §16.10 smoke.
- Auth: `codex login` — device-code flow that stores credentials at `~/.codex/auth.json`. Alternative: `OPENAI_API_KEY` env var (the adapter accepts either).
- Verify: `which codex` (should resolve to `$PREFIX/bin/codex` after `npm i -g`) and `codex --version` (should report 0.42.0).
- Termux note: if Codex 0.42 stops working post-Termux update or Bionic ABI change, see the prctl regression at openai/codex#6757 — the workaround is to hold at 0.42 (which `setup.sh` already pins) until an upstream fix lands.

## Run standalone (development)

```bash
cd bridge
npm install
node server.js
```

In another shell:

```bash
curl -s http://127.0.0.1:8787/health
curl -N -X POST http://127.0.0.1:8787/chat \
  -H "Content-Type: application/json" \
  -d '{"provider":"gemini","system":"Reply with one short sentence.","messages":[{"role":"user","content":"Say hi."}]}'
```

## Run tests

```bash
npm test    # node --test test/*.test.js — 45 tests, mocked subprocesses
```

## Endpoints

### `GET /health`

```json
{
  "ok": true,
  "version": "0.1.0",
  "uptimeSeconds": 42,
  "providers": [
    { "id": "claude", "available": true },
    { "id": "codex",  "available": false, "reason": "codex CLI not on PATH (run setup.sh)" },
    { "id": "gemini", "available": false, "reason": "not authenticated (run `gemini` interactively to log in)" }
  ]
}
```

### `GET /providers`

Same array as `/health`'s `providers`. Provider ids surface as `claude`, `gemini`, `codex` (alphabetical).

### `POST /chat`

Request:

```json
{
  "provider": "claude" | "gemini" | "codex",
  "system": "<optional>",
  "messages": [{ "role": "user", "content": "..." }]
}
```

Response: `text/event-stream`, normalized event shape:

```
data: {"type":"delta","text":"first chunk"}

data: {"type":"delta","text":" more text"}

data: {"type":"done"}
```

Errors are emitted as `{"type":"error","code":"AUTH_FAILURE"|"RATE_LIMITED"|"NETWORK_FAILURE"|"TIMEOUT"|"CLI_NOT_FOUND"|"CLI_CRASHED"|"UNKNOWN","message":"..."}`, followed by `{"type":"done"}` to release the consumer.

Note: the bridge does NOT proxy xAI Grok. xAI is served by the IME's `RemoteApiBackend` over direct HTTPS to `api.x.ai` (Phase 11) and is configurable from the IME's Backends settings independently of `/providers`.

### `POST /reauth`

Phase 4: returns 501. The bridge cannot open a browser; reauth is driven from the IME side by firing `<cli>` interactively in Termux's foreground via `RUN_COMMAND` (Phase 5b/6).

## Operational notes

- **Loopback only.** The bridge binds `127.0.0.1` (hardcoded — no env override). The host has no first-class secrets, but exposing the bridge would let any app on the LAN reach the user's authenticated CLIs.
- **OAuth tokens never enter the bridge.** Each CLI reads its own config dir (`~/.claude/.credentials.json`, `~/.gemini/oauth_creds.json`, `~/.codex/auth.json`) when it spawns.
- **Aggressive content filtering.** Adapters filter to text deltas only; thinking traces, tool-use events, planner steps, rate-limit notices, and other envelope events are dropped. The IME's use case is text rewriting, not agentic tasks.
- **Stderr is logged; stdout is not.** Subprocess stdout carries user content (model output) and is never written to the bridge's logger. Stderr summaries are bounded to the last few lines and used only to classify exit codes into structural error codes.
- **Phase 5a registers the bridge as a `termux-services` service**, started on first run; `setup.sh` arranges the auto-start hook. For development without `setup.sh`, run `node server.js` manually.
