# bridge/

A local HTTP bridge that spawns user-installed CLI tools (Claude Code, Gemini CLI) and exposes a normalized streaming HTTP API on `127.0.0.1:8787`. The AI Keyboard IME consumes this when the user has selected the Termux backend (Phase 6+).

The bridge has **no AI SDK dependency**. Its only runtime npm package is Fastify. AI inference happens in the spawned subprocesses; OAuth credentials live in each CLI's own config dir and never enter the bridge's memory.

## Prerequisites

- Termux (F-Droid build — Play Store version is dead since 2022)
- Node ≥ 20: `pkg install nodejs`
- `claude` (Claude Code, pinned to 2.1.112 with snapshot + wrapper + autoupdater disabled — see `ARCHITECTURE.md` "Termux CLI compatibility constraints" and `PHASE_REVIEW.md` "Known accepted corner cases")
- `gemini` (Google Gemini CLI: `npm i -g @google/gemini-cli`, then `gemini` interactively to OAuth)
- Each CLI must be authenticated (run interactively once)

Phase 5a's `setup.sh` automates the prereqs.

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
npm test    # node --test test/, mocked subprocesses
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
    { "id": "gemini", "available": false, "reason": "not authenticated (run `gemini` interactively to log in)" }
  ]
}
```

### `GET /providers`

Same array as `/health`'s `providers`.

### `POST /chat`

Request:

```json
{
  "provider": "claude" | "gemini",
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

### `POST /reauth`

Phase 4: returns 501. The bridge cannot open a browser; reauth is driven from the IME side by firing `<cli>` interactively in Termux's foreground via `RUN_COMMAND` (Phase 5b/6).

## Operational notes

- **Loopback only.** The bridge binds `127.0.0.1` (hardcoded — no env override). The host has no first-class secrets, but exposing the bridge would let any app on the LAN reach the user's authenticated CLIs.
- **OAuth tokens never enter the bridge.** Each CLI reads its own config dir (`~/.claude/.credentials.json`, `~/.gemini/oauth_creds.json`) when it spawns.
- **Aggressive content filtering.** Adapters filter to text deltas only; thinking traces, tool-use events, rate-limit notices, and other envelope events are dropped. The IME's use case is text rewriting, not agentic tasks.
- **Stderr is logged; stdout is not.** Subprocess stdout carries user content (model output) and is never written to the bridge's logger.
- **Phase 5a will register the bridge as a `termux-services` service.** Until then, run `node server.js` manually.
