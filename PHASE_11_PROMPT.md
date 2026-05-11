# Phase 11 — OpenAI Codex adapter (bridge) + Grok API-key entry (RemoteApi)

This prompt corresponds to row 11 of `ARCHITECTURE.md`'s phase plan. Dual scope per `PHASE_REVIEW.md`'s Phase 11 "Done means":

**A) Codex via the Termux bridge** — the main deliverable:
- `bridge/adapters/codex.js` spawns the user-installed `codex` CLI in `codex exec --json` mode and translates JSONL events to the bridge's normalized SSE shape (`{type:"delta"|"done"|"error",...}`).
- `setup/setup.sh` extended to install `@openai/codex`, install a `resolv.conf` shim (Termux-specific gotcha), and run Codex's device-code OAuth flow.
- `TermuxOrchestrator.Provider` enum gains `CODEX("codex")`; the existing `TermuxBridgeBackend` already routes any string provider id through to the bridge — no Kotlin client refactor needed (Phase 6's deliberate design).

**B) Grok via `RemoteApiBackend`** — the smaller addition:
- `Provider.XAI_GROK` enum entry (`api.x.ai/v1/chat/completions`, OpenAI-compatible wire format, Bearer auth).
- `RemoteApiBackend` gains a third dispatch branch routing to a new `streamGrok` private method; the stream parser is `client.locallan.OpenAiCompatStreamParser` from Phase 10 (declared `internal object`, accessible across sibling packages within the same module).
- `network_security_config.xml` adds `api.x.ai` to the HTTPS-only `<domain-config>` block.
- `BackendsScreen` automatically renders the new row (it iterates `Provider.entries`).

**Out of scope:**
- A Grok-via-Termux-bridge path — there is no first-party Grok CLI. Documented as deferred until xAI ships one.
- Codex tool-use / function-calling features. Phase 11 supports `codex exec` text rewriting only (matching the existing Claude/Gemini adapter scope).
- Web search / image generation / any Codex feature beyond plain prompt-to-text completion.
- Refactoring `RemoteApiBackend` toward a more abstract "OpenAI-compatible" backend; that's Phase 12 polish if needed.

Stop when Phase 11's Definition of Done holds. Do not begin Phase 12.

---

## Read these first (do not skip)

1. `/Users/kacper/SDK/apk-dev/ai-keyboard/ARCHITECTURE.md` — row 11 in the build/phase plan + the bridge architecture section ("Each adapter spawns the user-installed CLI as a subprocess in stream-json mode and pipes JSONL in/out") + the "Termux CLI compatibility constraints" section (the Claude Code pinning precedent; Codex has a different shape — see §1 below).
2. `/Users/kacper/SDK/apk-dev/ai-keyboard/PHASE_REVIEW.md` — Phase 11 per-phase acceptance criteria.
3. `/Users/kacper/SDK/apk-dev/ai-keyboard/PHASE_10_SUMMARY.md` — most recent phase. Carries the lessons-folded-forward list (lint suppression with inline justification, `@SerialName` for snake_case JSON fields, Kotlin expression-body limitation around `return null`, test-file refactoring discipline) — apply all of these.
4. **Existing adapter precedent:** `bridge/adapters/claude.js` is the closest template for Codex. Its general shape — `whichXxx` PATH lookup, credentials-file existence check, `isAvailable()` returning `{available, version?, reason?}`, `chat({messages, system})` spawning the CLI with `stream-json`-style I/O, JSON-line parser stitched into the bridge's normalized SSE — maps directly to Codex.
5. **Existing setup.sh:** `setup/setup.sh` already has the provider-menu plumbing (`SUPPORTED_PROVIDERS` array, `parse_providers_arg`, `select_providers`, `install_<name>_if_selected` per-provider functions). It also has a `codex)` stub at line 157 (`die "Codex is Phase 11 — not part of 5a's setup script"`) and at line 273 (`# Codex installer deferred to Phase 11`). Phase 11 removes both stubs and wires in the real path.
6. **Existing `TermuxOrchestrator`:** the `Provider` nested enum at line 56 currently has `CLAUDE("claude")` and `GEMINI("gemini")`. Phase 11 adds `CODEX("codex")`. The `reauthProvider` function dispatches on this enum.

---

## §1 — Architectural decisions locked

### 1.1 Codex package shape is fundamentally simpler than Claude Code

Based on the Phase 11 pre-investigation (documented in this section; verifiable via the upstream sources cited):

- `@openai/codex` (npm) ships **a thin JS shim plus vendored Rust binaries** inside the npm tarball. There is **no postinstall script**, no HTTP downloader, no autoupdater. `bin/codex.js` does platform detection and `spawn()`s a pre-bundled binary from `vendor/<target-triple>/codex/codex`. The Android branch maps to `aarch64-unknown-linux-musl` (statically linked musl — Bionic-compatible in practice).
- **There is NO autoupdater.** Plain `npm i -g @openai/codex@<pinned>` is durable; npm won't silently re-fetch latest. None of Claude Code's snapshot-into-`~/claude-code-pinned/`, wrapper-script-pointing-at-snapshot, `DISABLE_AUTOUPDATER=1` dance is needed. **Don't add it speculatively.**
- **`codex exec --json` prints JSONL events to stdout** — exactly the newline-delimited-JSON subprocess shape the bridge already consumes for Claude/Gemini.
- **Device-code OAuth is supported** (`codex login` with the device-code flow): print verification URL + one-time code, user authenticates on any device. Better than Claude Code's loopback flow — no `termux-open` dependency.

References: [openai/codex repo](https://github.com/openai/codex), [codex-cli/package.json](https://github.com/openai/codex/blob/main/codex-cli/package.json), [bin/codex.js](https://github.com/openai/codex/blob/main/codex-cli/bin/codex.js), [device_code_auth.rs](https://github.com/openai/codex/blob/main/codex-rs/login/src/device_code_auth.rs).

### 1.2 Pin Codex version conservatively

Two known Termux issues:
- **v0.43+ regression**: `prctl(PR_SET_DUMPABLE, 0)` fails under Termux+proot ([openai/codex#6757](https://github.com/openai/codex/issues/6757)). Native Termux may also be affected — not confirmed across the long tail.
- **DNS gotcha**: Termux has no `/etc/resolv.conf` by default; Codex's Rust networking stack fails with `Stream disconnected before completion` ([openai/codex#11809](https://github.com/openai/codex/issues/11809)).

**Pin to v0.42.0** as the documented last-known-working version. The version string lives in a top-of-file constant in `setup.sh` (`CODEX_VERSION="0.42.0"`) so Phase 12 release-prep can re-evaluate. Future releases may fix the regression — the pinning is conservative, not eternal.

**Empirical fallback:** the setup script runs `codex --version` after install and fails fast with a clear error message if the binary doesn't even launch (citing the issue numbers above). This catches the case where the pinned binary still doesn't work on a specific Termux build.

### 1.3 Install a `resolv.conf` shim before first run

The DNS gotcha is independent of Codex version. If `$PREFIX/etc/resolv.conf` doesn't exist, the setup script writes `nameserver 1.1.1.1\nnameserver 8.8.8.8\n` into it. Idempotent: if the file already exists with content, leave it alone (don't clobber the user's custom DNS config). This is a TERMUX-WIDE fix that also benefits other Rust-based Termux CLIs (vlog: this may help future phases too).

### 1.4 Codex subprocess invocation

Use `codex exec --json "<prompt>"` (NOT `codex chat` — the `exec` subcommand is the non-interactive shape; `chat` is the REPL). The `--json` flag prints JSONL event lines. The bridge adapter parses these line-by-line and emits the bridge's normalized SSE shape.

**Important difference from Claude Code:** Codex `exec --json` does NOT support interactive multi-turn conversation; each invocation is a single prompt-response. The bridge's `/chat` endpoint accepts a `messages` array — for Codex, the adapter concatenates the conversation history into a single prompt with role tags (per the [Codex exec event schema docs](https://github.com/openai/codex/tree/main/docs)), since `codex exec` consumes one prompt at a time. This mirrors how the Anthropic Claude Code adapter handles single-turn ↔ multi-turn mismatch.

### 1.5 Grok = third provider in `RemoteApiBackend`, OpenAI-compat wire format

Grok uses `https://api.x.ai/v1/chat/completions` (OpenAI-compatible). The existing `RemoteApiBackend` has two branches (Anthropic Messages SSE, Gemini chunked-JSON); Phase 11 adds a third (`XAI_GROK` → OpenAI-compat SSE). Reuse Phase 10's `OpenAiCompatStreamParser`:

```kotlin
// In RemoteApiBackend.kt (or RemoteApiRequest.kt):
import com.aikeyboard.app.ai.client.locallan.OpenAiCompatStreamParser
```

The parser is `internal object` in `com.aikeyboard.app.ai.client.locallan`. `internal` is module-scoped; the app is a single Gradle module; cross-package access works. **Do not copy-paste the parser into a new file** — reuse it. (If Phase 12 later refactors the OpenAI-compat helpers to a shared package, that's a clean follow-up. Don't pre-emptively refactor in Phase 11.)

### 1.6 No flavor split

Phase 11 is entirely flavor-agnostic. Same as Phase 9a/9b/10.

### 1.7 No new permissions

`INTERNET` already declared. No new manifest entries.

---

## §2 — Files to create

```
bridge/adapters/
  codex.js                        # OpenAI Codex CLI adapter

bridge/test/
  codex.test.js                   # unit tests for the new adapter
                                   # NOTE: must live at bridge/test/, NOT
                                   # bridge/test/adapters/. The package.json
                                   # test script glob is "test/*.test.js"
                                   # (non-recursive); a subdirectory placement
                                   # would be silently skipped by the runner.

app/app/src/test/java/com/aikeyboard/app/ai/client/remote/
  GrokRequestTest.kt              # Grok URL/headers/body shape
```

## §3 — Files to modify

```
bridge/adapters/index.js
  + register `codex: createCodexAdapter` in FACTORIES

setup/setup.sh
  + CODEX_VERSION="0.42.0" constant at top
  + SUPPORTED_PROVIDERS=(claude gemini codex) [was: (claude gemini)]
  + parse_providers_arg: accept "codex" (remove the die stub at line 157)
  + select_providers: add menu entry "4) codex only" and "5) all three"
  + install_codex_if_selected() — new per-provider installer
  + ensure_resolv_conf() — new DNS shim helper, called before any provider install
  + --reauth parser at line 137: accept "codex"
  + Update the "Steps" log line in §preflight to mention codex

app/app/src/main/java/com/aikeyboard/app/ai/termux/TermuxOrchestrator.kt
  + Provider.CODEX("codex") enum value
  + reauthProvider: dispatch the CODEX case (mirrors CLAUDE/GEMINI)
  + Any UI strings the orchestrator references for CODEX (none expected,
    but verify when reading TermuxOrchestrator.kt)

app/app/src/main/java/com/aikeyboard/app/ai/client/Provider.kt
  + XAI_GROK enum entry (displayName "xAI Grok", storageKey "xai_grok",
    defaultModel "grok-2-latest", apiKeyHelpUrl "https://console.x.ai")

app/app/src/main/java/com/aikeyboard/app/ai/client/remote/RemoteApiBackend.kt
  + when (provider) { ... XAI_GROK -> streamGrok(...) }
  + new private streamGrok() method (POST api.x.ai/v1/chat/completions,
    Bearer apiKey header, body = OpenAI chat-completions shape, response
    parsed via OpenAiCompatStreamParser.parseLine line-by-line)

app/app/src/main/java/com/aikeyboard/app/ai/client/remote/RemoteApiRequest.kt
  + (if request-building lives here) grokBody(model, messages) helper

app/app/src/main/res/xml/network_security_config.xml
  + add <domain includeSubdomains="true">api.x.ai</domain> to the existing
    cleartextTrafficPermitted="false" domain-config block (alongside
    api.anthropic.com and generativelanguage.googleapis.com).

app/app/src/main/res/values/ai_strings.xml
  + (if Provider.displayName / apiKeyHelpUrl are stringRes-backed)
    add ai_provider_xai_grok_name / _help_url
  + (if TermuxOrchestrator.Provider has user-facing strings)
    add equivalent for CODEX
  Verify before editing — Provider.kt uses inline string literals for
  displayName/apiKeyHelpUrl (read Phase 3a's pattern).
```

No changes to `proguard-rules.pro` are expected (Grok reuses existing
infrastructure; Codex is in the bridge, not in Kotlin).

---

## §4 — `bridge/adapters/codex.js`

Mirror `bridge/adapters/claude.js` structure. Key differences:

- **Credentials probe path:** `~/.codex/auth.json` (Codex's device-code OAuth deposits a token here).
- **`whichCodex` PATH lookup:** same `which` subprocess as claude.js.
- **Subprocess command:** `codex exec --json "<prompt>"`. The bridge's `messages` array is flattened into a single prompt with role tags before passing.
- **JSONL event shape:** Codex `exec --json` emits events like:
  ```
  {"type":"agent_message","content":{"text":"Hello"}}
  {"type":"agent_message_delta","content":{"text":" world"}}
  {"type":"task_complete"}
  ```
  Map `agent_message` and `agent_message_delta` → bridge's `{type:"delta",text}`. Map `task_complete` → `{type:"done"}`. Everything else (tool-use events, thinking events) is dropped — Phase 11 supports text-only rewriting only.

  **NOTE:** the exact event names are extracted from [codex-rs/exec/src/cli.rs](https://github.com/openai/codex/blob/main/codex-rs/exec/src/cli.rs) and [codex-rs/exec/src/event_processor.rs](https://github.com/openai/codex/blob/main/codex-rs/exec/src/event_processor.rs). **Verify the event names against the v0.42.0 source before finalizing the parser** — if they've changed in a non-backwards-compatible way, the adapter must match v0.42.0's schema specifically.

### Skeleton

**Important — calling convention** (verified at `bridge/adapters/claude.js:77`): `chat()` takes `(payload, sse, abortSignal)`, not `(payload, signal, emit)`. The second argument is an `sse` object with named methods `emitDelta(text)`, `emitDone()`, and `emitError(code, message)` — NOT a raw `emit({type, ...})` function. Do NOT call `emit({type:'delta',...})` — that's wrong shape and will runtime-`TypeError` because `emit` won't be a function on the real `sse` object. Same calling convention is used in `gemini.js`; the test harness in `bridge/test/adapters.test.js` creates a `makeSseRecorder()` exposing `emitDelta`/`emitDone`/`emitError` methods.

```javascript
'use strict';

const childProcess = require('node:child_process');
const fsPromises = require('node:fs/promises');
const path = require('node:path');

const REQUIRED_FLAGS = ['exec', '--json'];

function homeDir(env) {
  return env.HOME || env.USERPROFILE || '/data/data/com.termux/files/home';
}

function defaultAuthPath(env) {
  return path.join(homeDir(env), '.codex', 'auth.json');
}

async function fileExists(fs, p) {
  try {
    await fs.access(p);
    return true;
  } catch {
    return false;
  }
}

async function whichCodex(spawn, env) {
  return new Promise((resolve) => {
    const child = spawn('which', ['codex'], { env });
    let out = '';
    child.stdout.on('data', (chunk) => { out += chunk.toString('utf8'); });
    child.on('error', () => resolve(null));
    child.on('close', (code) => {
      if (code !== 0) return resolve(null);
      const p = out.trim().split('\n')[0];
      resolve(p || null);
    });
  });
}

function createCodexAdapter(deps = {}) {
  const spawn = deps.spawn || childProcess.spawn;
  const fs = deps.fs || fsPromises;
  const env = deps.env || process.env;

  async function isAvailable() {
    const codex = await whichCodex(spawn, env);
    if (!codex) {
      return { available: false, reason: 'codex CLI not on PATH (run setup.sh)' };
    }
    const authPath = defaultAuthPath(env);
    if (!(await fileExists(fs, authPath))) {
      return { available: false, reason: 'codex not authenticated (run: codex login)' };
    }
    return { available: true };
  }

  /**
   * Flattens the bridge's messages array into a single prompt string.
   * Codex exec accepts one prompt per invocation (vs Claude Code's
   * stream-json multi-turn). Note: this differs from claude.js which
   * uses lastUserContent(messages) — Codex gets the full conversation
   * history in the prompt so multi-turn context is preserved across
   * the role boundary.
   */
  function flattenMessages(messages, system) {
    const lines = [];
    if (system) lines.push(`System: ${system}`);
    for (const m of messages) {
      if (typeof m.content === 'string' && m.content.length > 0) {
        const role = m.role || 'user';
        lines.push(`${role[0].toUpperCase()}${role.slice(1)}: ${m.content}`);
      }
    }
    return lines.join('\n\n');
  }

  async function chat({ messages, system }, sse, abortSignal) {
    const prompt = flattenMessages(messages || [], system);
    const child = spawn('codex', [...REQUIRED_FLAGS, prompt], {
      env: { ...env, NO_COLOR: '1' },
      signal: abortSignal, // abort signal from /chat handler's request-close hook
    });

    let buffer = '';
    const handleLine = (line) => {
      if (!line) return;
      let evt;
      try {
        evt = JSON.parse(line);
      } catch {
        // Codex sometimes emits non-JSON heartbeat lines; skip silently.
        return;
      }
      // Map Codex events to the bridge's sse-method calling convention.
      // Names should be verified against v0.42.0 source before merge:
      // https://github.com/openai/codex/blob/v0.42.0/codex-rs/exec/src/event_processor.rs
      // (Pin to the v0.42.0 tag, not main — schema may have evolved.)
      switch (evt.type) {
        case 'agent_message':
        case 'agent_message_delta': {
          const text = evt.content?.text ?? evt.text;
          if (typeof text === 'string' && text.length > 0) {
            sse.emitDelta(text);
          }
          break;
        }
        case 'task_complete':
        case 'agent_message_done':
          sse.emitDone();
          break;
        case 'error':
          // PRIVACY: don't echo evt.message — server text may include prompt
          // fragments. Use a static code + static structural message.
          sse.emitError('CODEX_ERROR', 'codex-error');
          break;
        // Tool-use, thinking, planner events: dropped silently (Phase 11
        // supports text rewriting only).
      }
    };

    child.stdout.on('data', (chunk) => {
      buffer += chunk.toString('utf8');
      let nl;
      while ((nl = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, nl);
        buffer = buffer.slice(nl + 1);
        handleLine(line.trim());
      }
    });

    // Codex prints prctl/auth failures on stderr. Don't echo to bridge consumer
    // (privacy: stderr may contain file paths or prompt fragments). Discard.
    child.stderr.on('data', () => { /* intentionally discarded */ });

    return new Promise((resolve) => {
      child.on('close', (code) => {
        if (buffer.length > 0) handleLine(buffer.trim());
        if (code !== 0) {
          sse.emitError('CODEX_EXIT_NONZERO', `exit-${code}`);
        }
        sse.emitDone();
        resolve();
      });
      child.on('error', () => {
        sse.emitError('CODEX_SPAWN_FAILED', 'spawn-failed');
        sse.emitDone();
        resolve();
      });
    });
  }

  return { isAvailable, chat, flattenMessages };
}

module.exports = { createCodexAdapter };
```

The signature `chat({messages, system}, sse, abortSignal)` and the `sse.emitX()` calling convention match `claude.js` and `gemini.js` exactly. Verified against `server.js`'s `adapter.chat(...)` call site at the `/chat` handler.

---

## §5 — `bridge/adapters/index.js`

One-line registration:

```javascript
const { createCodexAdapter } = require('./codex');

const FACTORIES = {
  claude: createClaudeAdapter,
  gemini: createGeminiAdapter,
  codex: createCodexAdapter,   // NEW
};
```

`listProviders()` automatically picks up the new entry. `probeAll()` calls `isAvailable()` on each — Codex's probe returns `{available:false, reason:"codex CLI not on PATH"}` until setup.sh runs.

---

## §6 — `setup/setup.sh` extensions

### 6.1 Top-of-file constants

```bash
CODEX_VERSION="0.42.0"  # Pinned: pre-prctl regression (#6757). Re-evaluate in Phase 12.
SUPPORTED_PROVIDERS=(claude gemini codex)
```

### 6.2 Remove the Phase 11 stubs

- Line 157: change `codex) die "Codex is Phase 11 — not part of 5a's setup script" ;;` to `codex) SELECTED_PROVIDERS+=("codex") ;;`
- Line 273 (or thereabouts): remove the `# Codex installer deferred to Phase 11` comment and replace with the real `install_codex_if_selected` function (§6.4).

### 6.3 `ensure_resolv_conf` helper

Call this BEFORE any provider's install function (the DNS issue affects Codex specifically but is a global Termux gotcha):

```bash
ensure_resolv_conf() {
    local resolv="$PREFIX/etc/resolv.conf"
    if [[ -f "$resolv" && -s "$resolv" ]]; then
        log_skip "DNS resolver config already present at $resolv"
        return 0
    fi
    log_info "Installing DNS resolver shim at $resolv (Termux has no resolv.conf by default; Codex's Rust networking stack requires one — see openai/codex#11809)"
    mkdir -p "$PREFIX/etc"
    cat > "$resolv" <<'EOF'
nameserver 1.1.1.1
nameserver 8.8.8.8
EOF
    log_info "DNS shim written"
}
```

Call from the main flow before the provider-install block. Idempotent: re-runs are no-ops if the file is non-empty.

### 6.4 `install_codex_if_selected` function

```bash
install_codex_if_selected() {
    log_step "Codex install"
    if ! provider_selected codex; then
        log_skip "codex not selected"
        return 0
    fi
    log_info "npm i -g @openai/codex@${CODEX_VERSION}"
    if ! npm i -g "@openai/codex@${CODEX_VERSION}"; then
        die "npm install failed for @openai/codex@${CODEX_VERSION}. The pinned version is the last known to launch on Termux without the prctl regression; if it's been removed from npm, file an issue."
    fi
    # Empirical Termux launch check — fail fast if the binary doesn't even
    # exec on this device. Per openai/codex#6757, some Termux+proot setups
    # crash on prctl(PR_SET_DUMPABLE, 0).
    if ! codex --version >/dev/null 2>&1; then
        die "codex --version failed. This Termux install may hit the prctl regression (openai/codex#6757). Try Termux outside proot, or pin to an even older version (e.g., 0.41.0)."
    fi
    log_info "codex --version: $(codex --version 2>&1 | head -1)"
}
```

### 6.5 OAuth function for Codex

Codex's device-code OAuth is the recommended path. The flow:

```bash
codex_login_interactive() {
    log_info "Starting codex device-code login. A URL and code will appear; open the URL on any device and paste the code."
    if ! codex login; then
        die "codex login failed. Make sure you completed the device-code flow at the printed URL."
    fi
    if [[ ! -f "$HOME/.codex/auth.json" ]]; then
        die "codex login claimed success but $HOME/.codex/auth.json is missing."
    fi
    log_info "codex authenticated; auth.json present"
}
```

Integrate into the main OAuth dispatch (after claude/gemini, mirroring the existing per-provider login function calls).

### 6.6 `--reauth` parser

Line 137 currently:
```bash
--reauth) shift; [[ $# -gt 0 ]] || die "..."; REAUTH_PROVIDER="$1"; shift ;;
```

The validation message (`"--reauth requires a provider name (claude|gemini)"`) needs updating to include codex. The `case` block that handles `$REAUTH_PROVIDER` later in the file gains a `codex)` branch that calls `codex_login_interactive`.

### 6.7 Interactive menu

Currently option 1 ("both Claude and Gemini") is the default (`""|1` case). Phase 11 expands the menu to four options and shifts the default to all-three:

```bash
echo "  1) claude + gemini only"
echo "  2) claude only"
echo "  3) gemini only"
echo "  4) codex only"
echo "  5) all three: claude + gemini + codex [recommended]"
```

Update the `case` block so `""|5` is the all-three default (Enter selects it), and option 1's prompt no longer claims "[recommended]". The previous label "both Claude and Gemini" is wrong with three providers — rename to "claude + gemini only".

### 6.8 Step counter renumbering — explicit list

The script currently has 9 `log_step "N/9 ..."` calls labeled 1/9 through 9/9. Phase 11 adds two new steps (`ensure_resolv_conf` is folded into the Codex install step or its own depending on flow; document below). Final layout: 10 steps total, all `log_step "N/10 ..."`. **All nine existing `/9` strings must be renumbered**; don't miss any:

```
1/10 termux.properties              (unchanged)
2/10 pkg install                    (unchanged)
3/10 Selecting providers            (unchanged)
4/10 DNS resolver shim              (NEW — ensure_resolv_conf, before any installer)
5/10 Claude Code install            (was 4/9)
6/10 Gemini CLI install             (was 5/9)
7/10 Codex install                  (NEW — install_codex_if_selected)
8/10 Provider OAuth                 (was 6/9; includes claude/gemini/codex)
9/10 Deploy bridge                  (was 7/9)
10/10 Start bridge service          (was 8/9 + 9/9 merged, OR keep 9 and bump everything)
```

Verify the exact existing labels by grepping `log_step` before editing. Make every renumber explicit.

### 6.9 Main-flow integration

- `ensure_resolv_conf` is called as its own step (4/10) before any provider installer — it's a Termux-wide fix that benefits future Rust-based CLIs too, not Codex-specific.
- `install_codex_if_selected` runs after `install_gemini_cli_if_selected` (step 7/10).
- `codex_login_interactive` is invoked from the existing OAuth dispatch block (step 8/10), alongside `claude_login_interactive` / `gemini_login_interactive`.

### 6.10 `bridge/test/adapters.test.js` assertions need updating

The existing test file (Phase 4 wrote it) currently asserts:

```javascript
test('adapters/index lists claude and gemini', () => {
  assert.deepEqual(adaptersIndex.listProviders().sort(), ['claude', 'gemini']);
});
test('adapters/index returns null for unknown provider', () => {
  assert.equal(adaptersIndex.createAdapter('codex'), null);    // <-- this now returns a real adapter
});
```

Both assertions break after Phase 11 registers `codex` in FACTORIES. Updates required:
- Rename the first test name to `lists claude, codex, and gemini`; change the expected array to `['claude', 'codex', 'gemini']`.
- Replace the `createAdapter('codex')` assertion with a different unknown-provider name (e.g., `'nonexistent'`) so the test continues to exercise the unknown-provider path.

These edits are part of the Phase 11 PR; don't ship without them.

---

## §7 — `TermuxOrchestrator.Provider` enum

Two-line addition to the existing enum at `TermuxOrchestrator.kt:56`:

```kotlin
enum class Provider(val cliName: String) {
    CLAUDE("claude"),
    GEMINI("gemini"),
    CODEX("codex"),   // NEW
}
```

`reauthProvider(provider)` (line 169) calls `--reauth <cliName>` — the new `CODEX("codex")` value works automatically because `cliName` is the parameter the bash script's `--reauth` parser expects. **No other refactor needed** — `TermuxBridgeBackend.cliProvider` is a plain `String` (Phase 6 deliberate design), so adding a new provider id surfaces automatically once the bridge's `/providers` endpoint lists it.

---

## §8 — Grok wiring

### 8.1 `Provider.kt` extension

```kotlin
enum class Provider(
    val displayName: String,
    val storageKey: String,
    val defaultModel: String,
    val apiKeyHelpUrl: String,
) {
    ANTHROPIC(...),
    GOOGLE_GEMINI(...),
    XAI_GROK(                                 // NEW
        displayName = "xAI Grok",
        storageKey = "xai_grok",
        defaultModel = "grok-2-latest",
        apiKeyHelpUrl = "https://console.x.ai",
    );
    ...
}
```

Note: `Provider.entries` is iterated by `BackendsScreen` to render rows; the new row appears automatically. Storage round-trip via `getApiKey(Provider.XAI_GROK)` already works because Phase 3a's `SecureStorage.apiKeys: Map<String, String>` keys by `storageKey`.

### 8.2 `RemoteApiBackend.kt` extension

The class currently has TWO `when (provider)` expressions, both of which become non-exhaustive once `XAI_GROK` is added — Kotlin requires exhaustiveness for enum-typed `when` used as an expression. Compile-error if you miss either:

**Location 1: `rewrite()` at line ~90** — dispatches the stream parser inside `execute { }`:

```kotlin
when (provider) {
    Provider.ANTHROPIC -> streamAnthropic(response.bodyAsChannel())
    Provider.GOOGLE_GEMINI -> streamGemini(response.bodyAsChannel())
    Provider.XAI_GROK -> streamGrok(response.bodyAsChannel())   // NEW
}
```

**Location 2: `buildRequest()` at line ~214** — returns a `RemoteApiRequest` per provider:

```kotlin
return when (provider) {
    Provider.ANTHROPIC -> buildAnthropicRequest(input, systemPrompt, fewShots, key, model)
    Provider.GOOGLE_GEMINI -> buildGeminiRequest(input, systemPrompt, fewShots, key, model)
    Provider.XAI_GROK -> buildGrokRequest(input, systemPrompt, fewShots, key, model)   // NEW
}
```

`streamGrok` mirrors `streamAnthropic` exactly (read it before writing — it's already the proven pattern for "parse SSE from a `ByteReadChannel` and emit AiStreamEvent through the surrounding `flow { }` builder"). The new code:

```kotlin
import com.aikeyboard.app.ai.client.locallan.OpenAiCompatStreamParser
import com.aikeyboard.app.ai.persona.FewShot
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.FlowCollector

// Add this as a sibling of streamAnthropic/streamGemini in the same class.
// FlowCollector receiver is in scope because rewrite() uses `flow { }`
// (NOT callbackFlow — that's LocalLanBackend's pattern).
private suspend fun FlowCollector<AiStreamEvent>.streamGrok(channel: ByteReadChannel) {
    while (!channel.isClosedForRead) {
        val line = channel.readUTF8Line() ?: break
        OpenAiCompatStreamParser.parseLine(line) { event ->
            // The parser's lambda is invoked synchronously by parseLine — we're
            // still inside the FlowCollector suspend context, so emit() works.
            // Use a local mutable list buffer if the line emits multiple events;
            // OpenAiCompatStreamParser.parseLine emits 0..2 events per line so
            // direct emission is safe (no reentrancy concern).
            // emit can't be called from inside a non-suspend lambda — restructure:
            // collect events from parseLine into a list, then emit outside.
        }
    }
}
```

**Restructure the inner lambda — the parseLine callback isn't a suspend context.** `OpenAiCompatStreamParser.parseLine(line, emit: (AiStreamEvent) -> Unit)` takes a non-suspend callback (verified at `OpenAiCompatStreamParser.kt:24` — the `emit` parameter is `(AiStreamEvent) -> Unit`, not `suspend (...)`). To bridge into the surrounding `FlowCollector.emit()` (which IS suspend), collect events into a local `mutableListOf` and emit them after the lambda returns:

```kotlin
private suspend fun FlowCollector<AiStreamEvent>.streamGrok(channel: ByteReadChannel) {
    while (!channel.isClosedForRead) {
        val line = channel.readUTF8Line() ?: break
        val pending = mutableListOf<AiStreamEvent>()
        OpenAiCompatStreamParser.parseLine(line) { event -> pending += event }
        for (event in pending) emit(event)
    }
}
```

**`buildGrokRequest`** — new static helper in `RemoteApiBackend.kt`'s companion (alongside `buildAnthropicRequest` / `buildGeminiRequest` for testability — `RemoteApiBackendTest.kt` already tests those as static companion methods, and `GrokRequestTest.kt` follows the same pattern):

```kotlin
internal fun buildGrokRequest(
    input: String,
    systemPrompt: String,
    fewShots: List<FewShot>,
    key: String,
    model: String,
): RemoteApiRequest {
    val messages = com.aikeyboard.app.ai.client.locallan.LocalLanRequestBuilder
        .buildMessages(input, systemPrompt, fewShots)
    val body = com.aikeyboard.app.ai.client.locallan.LocalLanRequestBuilder
        .openAiBody(model = model, messages = messages)
    return RemoteApiRequest(
        url = "https://api.x.ai/v1/chat/completions",
        headers = mapOf("Authorization" to "Bearer $key"),
        body = body,
    )
}
```

The `RemoteApiBackend` constructor's `model: String = provider.defaultModel` parameter (line 54) provides the model — **do NOT call `storage.getModelOverride(...)`; that accessor does not exist in `SecureStorage`.** The model resolution flows: constructor param → `provider.defaultModel` (= `"grok-2-latest"` for `XAI_GROK`).

The existing `httpClient` companion singleton on `RemoteApiBackend` (NOT `TermuxBridgeBackend.httpClient`) is the one to use — `streamAnthropic` and `streamGemini` already use it via the class's `httpClient.preparePost(...)` call. Don't import from the Termux subpackage.

### 8.3 `mapHttpError` already exists

`RemoteApiBackend` already has a `private suspend fun mapHttpError(status: HttpStatusCode, response: HttpResponse): AiStreamEvent.Error` (line 188). Grok's HTTP error mapping reuses it directly — **do not duplicate from `LocalLanBackend`.**

### 8.4 `network_security_config.xml`

Add `api.x.ai` to the existing HTTPS-only domain-config block:

```xml
<domain-config cleartextTrafficPermitted="false">
    <domain includeSubdomains="true">api.anthropic.com</domain>
    <domain includeSubdomains="true">generativelanguage.googleapis.com</domain>
    <domain includeSubdomains="true">api.x.ai</domain>     <!-- NEW -->
    <trust-anchors>
        <certificates src="system" />
    </trust-anchors>
</domain-config>
```

---

## §9 — Strings

Inspect `ai_strings.xml` first. If `Provider` entries currently use inline string literals (`displayName = "Anthropic Claude"`), then Phase 11 follows that pattern — no new strings. If they reference `stringRes` keys, add corresponding entries:

```xml
<string name="ai_provider_xai_grok_name">xAI Grok</string>
<string name="ai_provider_xai_grok_help_url" translatable="false">https://console.x.ai</string>
```

`TermuxOrchestrator.Provider.CODEX` may also need a user-facing string IF the orchestrator surfaces provider names in UI (it likely doesn't — Phase 6 routes provider strings through the `cliProvider` plain string field). Verify by grepping `Provider.CLAUDE.cliName` usages.

---

## §10 — Tests

### 10.1 `bridge/test/codex.test.js`

Mirror `claude.test.js` (read it first; the structure is `tap`-style or jest-style depending on how Phase 4 set it up — match exactly).

Tests:

- `isAvailable returns false when which fails`
- `isAvailable returns false when ~/.codex/auth.json is absent`
- `isAvailable returns true when both PATH and auth file present`
- `chat emits deltas from agent_message events`
- `chat emits deltas from agent_message_delta events`
- `chat emits done on task_complete`
- `chat drops tool_use and thinking events silently`
- `chat handles non-JSON stderr without crashing` (regression for the prctl error case)
- `chat handles nonzero exit code by emitting CODEX_EXIT_NONZERO error`
- `flattenMessages prepends System line when system arg present`
- `flattenMessages handles empty messages array`

### 10.2 `app/app/src/test/java/com/aikeyboard/app/ai/client/remote/GrokRequestTest.kt`

Pure JVM (no Ktor instantiation):

- `streamGrok body shape matches openAiBody output`
- `Bearer header set from storage.getApiKey(XAI_GROK)`
- `URL is https://api.x.ai/v1/chat/completions`
- `model defaults to grok-2-latest when no override stored`
- `NO_API_KEY error when no key configured`

If the existing `RemoteApiBackend` tests use mocked `HttpClient`, follow that pattern; if they're integration-only and skip-on-CI, do the same.

**Tally target: ~16 new tests (11 bridge codex + 5 Grok) on top of 151 prior = ~167 AI-module unit tests (Kotlin) + ~N+11 bridge unit tests.**

---

## §11 — Definition of Done

All four flavor/buildtype builds clean (`assembleFdroidDebug assemblePlayDebug assembleFdroidRelease assemblePlayRelease`). `lintFdroidDebug lintPlayDebug` produce only pre-existing carry-overs. `git diff app/app/lint-baseline.xml` empty.

Bridge tests pass: run `npm test` inside `bridge/` (which expands to `node --test "test/*.test.js"` per `bridge/package.json:10` — the non-recursive glob is why `codex.test.js` must live at `bridge/test/`, not `bridge/test/adapters/`).

Functional invariants:

1. `bridge` exposes Codex via `/providers` when `codex` is on PATH AND `~/.codex/auth.json` exists.
2. `POST /chat { provider: "codex", messages: [...] }` streams `{type:"delta"}` events as Codex emits them, terminates with `{type:"done"}`.
3. `setup.sh --providers codex` installs `@openai/codex@0.42.0`, writes the `resolv.conf` shim if missing, runs device-code login, leaves the bridge healthy with Codex listed in `/providers`.
4. `setup.sh --providers codex,claude,gemini` installs all three sequentially.
5. `setup.sh --reauth codex` re-runs Codex's device-code OAuth without touching other providers.
6. `TermuxOrchestrator.Provider.CODEX` value present; `reauthProvider(CODEX)` succeeds against the running bridge.
7. `Provider.XAI_GROK` enum entry renders as a row in `BackendsScreen`; storing an API key + selecting it as Active for Rewrite produces a successful end-to-end Rewrite against `api.x.ai`.
8. Privacy: `grep "Log\\." app/app/src/main/java/com/aikeyboard/app/ai/client/remote/RemoteApiBackend.kt` shows only `t.javaClass.simpleName` and static structural strings for the new Grok branch. The Codex adapter (bridge) does no logging that includes prompt/response text.

Dex invariants (verify via `apkanalyzer dex packages` on fdroid + play release APKs):

| Class | fdroid | play |
|---|---|---|
| `com.aikeyboard.app.ai.client.Provider$XAI_GROK` | present | present |
| `com.aikeyboard.app.ai.termux.TermuxOrchestrator$Provider$CODEX` | present | present |
| `streamGrok` reachable from `RemoteApiBackend` (verify R8 doesn't inline it out — the public `rewrite` method's `when` branch is the only call site; if missing, add a `-keep ... streamGrok(...)` rule per Phase 6/7b/9a/10 precedent) | present | present |

Manifest invariants:

- ZERO new `<uses-permission>` entries.
- ZERO new `<service>` / `<provider>` / `<receiver>` / `<activity>` entries.
- `network_security_config.xml` adds `api.x.ai` to the HTTPS-only domain-config; `base-config` unchanged from Phase 10 (`cleartextTrafficPermitted="true"`, inline `tools:ignore="InsecureBaseConfiguration"`).

Tests: ~16 new unit tests pass; 6 pre-existing Robolectric SDK-36 failures unchanged.

Carry-overs unchanged (gesture lib, deprecated migrations, lint baseline 64 inherited entries, Phase 4 `ObsoleteSdkInt`, **Phase 12 polish list from PHASE_10_SUMMARY.md** — WhatsApp validator file-existence check, BootReceiver post-reboot, multi-pack creation UX gap, pack tab background contrast, HeliBoard emoji-key gap, etc.). None block Phase 11.

---

## §12 — Smoke test scenarios (deferred to human reviewer)

Phase 11 requires Termux + a real Codex device-code login + the bridge running. Same precedent as Phase 4/5b/6.

1. **Fresh Codex install:** wipe Termux, run `setup.sh --providers codex` paste-flow. Expect: `resolv.conf` shim installed, npm install succeeds, `codex --version` smoke-test passes, device-code URL printed, user completes OAuth elsewhere, `auth.json` present, bridge starts, `curl http://127.0.0.1:8787/providers` shows `{id: "codex", available: true}`.
2. **Codex chat happy path:** with bridge running and Codex authenticated, switch the IME to Termux backend + Codex provider, run a Rewrite. Tokens stream.
3. **Codex unauthenticated:** revoke `~/.codex/auth.json` (delete it), `/providers` shows `{id: "codex", available: false, reason: "codex not authenticated (run: codex login)"}`. Rewrite via Codex shows the unauthenticated error.
4. **prctl regression smoke:** if the device is in proot mode, the `codex --version` smoke-test in `setup.sh` should fail clearly with the issue #6757 reference. (Hard to test without a proot environment; document the failure-mode message.)
5. **`--reauth codex`:** re-run `setup.sh --reauth codex`, complete device-code flow, verify `/providers` reflects updated auth.
6. **Grok happy path:** configure an xAI API key in `BackendsScreen` (paste a real key obtained from console.x.ai), select xAI Grok as Active for Rewrite, run a Rewrite. Tokens stream from `api.x.ai/v1/chat/completions`.
7. **Grok no-key:** delete the Grok API key, attempt Rewrite — clear `NO_API_KEY` error.
8. **Grok auth failure:** save an invalid key, attempt Rewrite — `AUTH_FAILURE` error from 401/403.
9. **Privacy logcat (Grok path):** `adb logcat | grep -i grok` — no URLs, no body content, no `t.message` ever.
10. **Privacy logcat (Codex bridge):** `cat $HOME/.termux/.bridge.log` (or wherever bridge logs go) — no prompt content, no response content, no auth tokens.

Document outcomes in `PHASE_11_SUMMARY.md`.

---

## §13 — Open questions for human reviewer (carry into Phase 12 prompt)

1. **Codex version pinning re-evaluation.** The pin to v0.42.0 is conservative. Phase 12 release-prep should test the latest stable on the user's actual Termux build and unpin to the most-recent-working version (or document that v0.42.0 is the durable target).
2. **`resolv.conf` shim collision risk.** If Termux ever ships with its own resolv.conf (it doesn't today, but the Termux team has discussed it), our shim becomes a no-op (good) or conflicts (unlikely — file is checked for non-empty before write). Phase 12 release-prep should re-check.
3. **Codex event-name stability across versions.** v0.42.0's `agent_message` / `agent_message_delta` / `task_complete` event shape is the basis for the adapter. If Phase 12 unpins to a later version where event names changed, the adapter parser needs updating. Worth pinning a contract test against actual Codex `exec --json` output.
4. **Grok model name evolution.** `grok-2-latest` is the v1 default. xAI has historically renamed models (e.g., `grok-1`, `grok-2-public`, etc.). Phase 12 polish can add a model-override field per provider in `BackendsScreen` (currently Anthropic and Gemini also use hard-coded defaults — same gap).
5. **Refactor toward an "OpenAI-compatible" backend abstraction.** Phase 10's `LocalLanBackend` (OPENAI_COMPATIBLE mode) and Phase 11's Grok branch both target the OpenAI-compat wire format. If Phase 12 adds a 4th OpenAI-compat provider (e.g., DeepSeek, Mistral La Plateforme), it'd be cleaner to factor a `OpenAiCompatRemoteBackend` that takes (URL, model, key) — same shape as `LocalLanBackend` but HTTPS-only. Defer until a real third API-key provider lands.
6. **Bridge `--reauth` for Codex affecting other providers.** The current setup-script structure runs `codex login` in foreground. If a Claude or Gemini session is mid-stream when the user fires `--reauth codex`, there's a race. Not a Phase 11 concern; `TermuxOrchestrator` should serialize re-auth requests through its own queue (it likely already does via Kotlin's coroutine scoping). Worth verifying.

---

## §14 — Coding-style invariants (carry-overs from prior phases)

- **Comments explain *why*, not *what*.**
- **No new `Log.*` calls that include URLs, request/response bodies, prompts, API keys, or any `t.message`.** Use `t.javaClass.simpleName` and static structural strings. Privacy invariant since Phase 7b.
- **Bridge adapter must not log prompt or response text.** stderr is discarded silently (per §4's `child.stderr.on('data', () => {})`). If diagnostic logging is needed, gate behind an env var (`BRIDGE_DEBUG=1`) and prefer structural counts over content.
- **Lint suppression with inline justification** when intentional security/privacy trade-offs are made (Phase 10 precedent: `tools:ignore="InsecureBaseConfiguration"`). The Grok additions don't require any new suppressions; flag any new lint output before adding suppression.
- **`@SerialName("snake_case")` on camelCase Kotlin fields** when JSON wire format uses snake_case. Codex JSON uses `task_complete` etc. — if we model those events as Kotlin data classes in the Codex adapter (we don't; the adapter is in `bridge/` which is Node.js, not Kotlin), this rule doesn't apply. For the Grok request body, follow Phase 10's `OpenAiChatRequest` precedent (already snake-case-free).
- **Don't double-declare code blocks across prose snippets and structural sketches.**
- **Don't list strings without a confirmed call site in the same prompt.** Same UnusedResources risk.
- **Single-dispatch navigation pattern** (not applicable in Phase 11 — no new Compose routes).
- **R8 keep rule for single-call-site dispatch methods:** the new `streamGrok` method on `RemoteApiBackend` is reached from a single `when` branch. The 3-for-3 precedent (BackendResolver, ReadRespondPromptBuilder, StickerCommitter, LocalLanBackend) says expect-and-verify. After build, run `apkanalyzer dex packages` on the fdroid release APK and confirm `streamGrok` appears (it'll show up indirectly via `RemoteApiBackend`'s methods list). If absent, add:
  ```
  -keepclassmembers class com.aikeyboard.app.ai.client.remote.RemoteApiBackend {
      private *** streamGrok(...);
  }
  ```

---

## Handoff

When DoD holds, write `PHASE_11_SUMMARY.md` mirroring Phase 10's summary structure (sections: what was built, deviations, dex invariants, manifest invariants, builds/lint, tests, privacy invariants, smoke deferred, carry-overs, open questions, touchpoints for next phase). Commit on a new branch `phase/11-codex-grok`. Stop. Do not begin Phase 12 (the final polish + release phase).
