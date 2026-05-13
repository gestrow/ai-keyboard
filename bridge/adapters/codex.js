'use strict';

const childProcess = require('node:child_process');
const fsPromises = require('node:fs/promises');
const path = require('node:path');

// OpenAI Codex adapter (Phase 11). Spawns `codex exec --json "<prompt>"` and
// translates the JSONL event stream to the bridge's normalized SSE shape.
//
// Codex differs from Claude Code in two ways the adapter has to bridge:
//   1. `codex exec` is single-turn (one prompt per invocation). The bridge's
//      /chat endpoint accepts a `messages` array, so flattenMessages folds the
//      history into a single role-tagged prompt string.
//   2. There is no autoupdater — npm i -g @openai/codex@<pinned> is durable, so
//      no snapshot+wrapper dance is needed (vs Claude Code's PHASE_REVIEW.md
//      "Known accepted corner cases" entry).
//
// Error codes reuse the established set (`CLI_NOT_FOUND` / `CLI_CRASHED` /
// `AUTH_FAILURE` / `RATE_LIMITED` / `TIMEOUT`) so the IME-side
// `TermuxBridgeBackend.mapBridgeErrorCode` routes them to the correct
// preview-strip messaging without a new branch.

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
    const binary = await whichCodex(spawn, env);
    if (!binary) {
      return { available: false, reason: 'codex CLI not on PATH (run setup.sh)' };
    }
    const authPath = defaultAuthPath(env);
    const hasAuth = await fileExists(fs, authPath);
    const hasApiKeyEnv = Boolean(env.OPENAI_API_KEY);
    if (!hasAuth && !hasApiKeyEnv) {
      return {
        available: false,
        reason: 'codex not authenticated (run `codex login` interactively)',
      };
    }
    return { available: true };
  }

  async function chat({ messages, system }, sse, abortSignal) {
    if (abortSignal?.aborted) return;

    const prompt = flattenMessages(messages || [], system);
    const child = spawn('codex', [...REQUIRED_FLAGS, prompt], {
      env: { ...env, NO_COLOR: '1' },
      stdio: ['ignore', 'pipe', 'pipe'],
    });

    let killed = false;
    const onAbort = () => {
      killed = true;
      try { child.kill('SIGTERM'); } catch { /* swallow */ }
    };
    abortSignal?.addEventListener?.('abort', onAbort, { once: true });
    const cleanup = () => {
      abortSignal?.removeEventListener?.('abort', onAbort);
    };

    let buffer = '';
    let sawDone = false;
    let sawError = false;

    child.stdout.on('data', (chunk) => {
      buffer += chunk.toString('utf8');
      let idx;
      while ((idx = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, idx).trim();
        buffer = buffer.slice(idx + 1);
        if (line.length === 0) continue;
        const event = parseLine(line);
        if (!event) continue;
        const delta = extractTextDelta(event);
        if (delta !== null) {
          sse.emitDelta(delta);
          continue;
        }
        if (isTerminalEvent(event)) {
          sawDone = true;
          sse.emitDone();
          continue;
        }
        if (isErrorEvent(event)) {
          sawError = true;
          // PRIVACY: don't echo evt.message — server-side error text may include
          // prompt fragments. Use a static structural string.
          sse.emitError('CLI_CRASHED', 'codex reported an error event');
          continue;
        }
        // tool_use, thinking, planner, session_start, etc — dropped silently.
        // Phase 11 supports text-only rewriting (matching Claude/Gemini scope).
      }
    });

    // stderr may contain auth diagnostics or prctl/DNS failure traces; classify
    // a short tail for the exit-code mapping but never echo it verbatim.
    let stderrTail = '';
    child.stderr.on('data', (chunk) => {
      stderrTail = (stderrTail + chunk.toString('utf8')).slice(-2048);
    });

    return new Promise((resolve) => {
      child.on('error', (err) => {
        cleanup();
        if (killed) { resolve(); return; }
        sse.emitError('CLI_NOT_FOUND', `codex spawn failed: ${err.message}`);
        resolve();
      });
      child.on('close', (code) => {
        cleanup();
        if (killed) { resolve(); return; }
        if (sawDone || sawError) { resolve(); return; }
        // Flush any trailing partial line so a final delta isn't lost.
        if (buffer.length > 0) {
          const event = parseLine(buffer.trim());
          if (event) {
            const delta = extractTextDelta(event);
            if (delta !== null) sse.emitDelta(delta);
          }
        }
        if (code === 0) {
          sse.emitDone();
        } else {
          const reason = classifyStderr(stderrTail) || `exit ${code}`;
          sse.emitError(classifyCode(stderrTail), `codex CLI failed: ${reason}`);
        }
        resolve();
      });
    });
  }

  return { isAvailable, chat };
}

/**
 * Folds the bridge's messages array into a single role-tagged prompt.
 * `codex exec` consumes one prompt per invocation, so multi-turn history is
 * concatenated with role headers (mirrors the "single-turn ↔ multi-turn"
 * bridging in the Anthropic Claude Code adapter, but here applied to every
 * call rather than only when needed). Differs from claude.js's
 * lastUserContent(messages) in that the full conversation flows through.
 */
function flattenMessages(messages, system) {
  const lines = [];
  if (system && typeof system === 'string' && system.length > 0) {
    lines.push(`System: ${system}`);
  }
  if (Array.isArray(messages)) {
    for (const m of messages) {
      if (!m || typeof m.content !== 'string' || m.content.length === 0) continue;
      const role = typeof m.role === 'string' && m.role.length > 0 ? m.role : 'user';
      const cap = role[0].toUpperCase() + role.slice(1);
      lines.push(`${cap}: ${m.content}`);
    }
  }
  return lines.join('\n\n');
}

function parseLine(line) {
  try { return JSON.parse(line); } catch { return null; }
}

// Returns the text fragment if event is an agent message (full or delta).
// Names mirror codex v0.42.0's `exec --json` event schema:
// agent_message / agent_message_delta carry the assistant text payload.
// Both `content.text` and a flat `text` field are tolerated so minor schema
// variations across patch releases of v0.42.x don't break the parser.
function extractTextDelta(event) {
  if (!event || typeof event !== 'object') return null;
  const type = event.type;
  if (type !== 'agent_message' && type !== 'agent_message_delta') return null;
  const inner = event.content;
  const text = (inner && typeof inner === 'object' && typeof inner.text === 'string')
    ? inner.text
    : (typeof event.text === 'string' ? event.text : null);
  if (typeof text !== 'string' || text.length === 0) return null;
  return text;
}

function isTerminalEvent(event) {
  const t = event && event.type;
  return t === 'task_complete' || t === 'agent_message_done';
}

function isErrorEvent(event) {
  return event && event.type === 'error';
}

function classifyCode(stderr) {
  const s = stderr.toLowerCase();
  if (s.includes('not authenticated') || s.includes('please log in') ||
      s.includes('credential') || s.includes('unauthorized') || s.includes('401')) {
    return 'AUTH_FAILURE';
  }
  if (s.includes('rate limit') || s.includes('429') || s.includes('quota')) {
    return 'RATE_LIMITED';
  }
  if (s.includes('timed out') || s.includes('timeout')) return 'TIMEOUT';
  if (s.includes('command not found') || s.includes('enoent')) return 'CLI_NOT_FOUND';
  return 'CLI_CRASHED';
}

function classifyStderr(stderr) {
  const trimmed = stderr.trim();
  if (!trimmed) return null;
  return trimmed.split('\n').slice(-3).join(' | ');
}

module.exports = {
  createCodexAdapter,
  // exported for unit tests
  flattenMessages,
  extractTextDelta,
  isTerminalEvent,
  isErrorEvent,
  classifyCode,
};
