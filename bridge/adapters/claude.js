'use strict';

const childProcess = require('node:child_process');
const fsPromises = require('node:fs/promises');
const path = require('node:path');

// Claude Code adapter. Spawns the user-installed `claude` binary in stream-json
// mode and translates content_block_delta events with text_delta payloads into
// the bridge's normalized {type:"delta",text} shape.
//
// Why this shape: Claude Code's stream-json carries a lot of envelope events
// (system init, thinking deltas, tool use, rate-limit events). The IME's only
// use case in Phase 4 is text rewriting, so we filter aggressively to text
// content blocks; everything else is dropped.

const REQUIRED_FLAGS = [
  '--print',
  '--input-format', 'stream-json',
  '--output-format', 'stream-json',
  '--verbose',
  '--include-partial-messages',
];

function homeDir(env) {
  return env.HOME || env.USERPROFILE || '/data/data/com.termux/files/home';
}

function defaultCredentialsPath(env) {
  return path.join(homeDir(env), '.claude', '.credentials.json');
}

async function fileExists(fs, p) {
  try {
    await fs.access(p);
    return true;
  } catch {
    return false;
  }
}

async function whichClaude(spawn, env) {
  return new Promise((resolve) => {
    const child = spawn('which', ['claude'], { env });
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

function createClaudeAdapter(deps = {}) {
  const spawn = deps.spawn || childProcess.spawn;
  const fs = deps.fs || fsPromises;
  const env = deps.env || process.env;

  async function isAvailable() {
    const binary = await whichClaude(spawn, env);
    if (!binary) {
      return { available: false, reason: 'claude CLI not on PATH' };
    }
    const credsPath = defaultCredentialsPath(env);
    const hasCreds = await fileExists(fs, credsPath);
    const hasApiKeyEnv = Boolean(env.ANTHROPIC_API_KEY);
    if (!hasCreds && !hasApiKeyEnv) {
      return {
        available: false,
        reason: 'not authenticated (run `claude` interactively to log in)',
      };
    }
    return { available: true };
  }

  async function chat({ messages, system }, sse, abortSignal) {
    if (abortSignal?.aborted) return;

    const args = [...REQUIRED_FLAGS];
    if (system && typeof system === 'string' && system.length > 0) {
      args.push('--append-system-prompt', system);
    }

    const child = spawn('claude', args, {
      env,
      stdio: ['pipe', 'pipe', 'pipe'],
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

    // Single user-turn input on stdin; the CLI errors if we leave it open.
    const userText = lastUserContent(messages);
    const userEnvelope = {
      type: 'user',
      message: { role: 'user', content: userText },
    };
    try {
      child.stdin.write(JSON.stringify(userEnvelope) + '\n');
      child.stdin.end();
    } catch (err) {
      cleanup();
      sse.emitError('CLI_CRASHED', `claude stdin write failed: ${err.message}`);
      return;
    }

    let buffer = '';
    let sawDone = false;

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
        if (event.type === 'result') {
          sawDone = true;
          sse.emitDone();
          continue;
        }
        // Tool-use, system, rate_limit_event, thinking_delta — ignored.
      }
    });

    // stderr is CLI diagnostics, not user content. Forwarded to bridge logger
    // by server.js via a plain string error code on non-zero exit.
    let stderrTail = '';
    child.stderr.on('data', (chunk) => {
      stderrTail = (stderrTail + chunk.toString('utf8')).slice(-2048);
    });

    return new Promise((resolve) => {
      child.on('error', (err) => {
        cleanup();
        if (killed) { resolve(); return; }
        sse.emitError('CLI_NOT_FOUND', `claude spawn failed: ${err.message}`);
        resolve();
      });
      child.on('close', (code) => {
        cleanup();
        if (killed) { resolve(); return; }
        if (sawDone) { resolve(); return; }
        if (code === 0) {
          sse.emitDone();
        } else {
          const reason = classifyStderr(stderrTail) || `exit ${code}`;
          sse.emitError(classifyCode(stderrTail), `claude CLI failed: ${reason}`);
        }
        resolve();
      });
    });
  }

  return { isAvailable, chat };
}

function lastUserContent(messages) {
  if (!Array.isArray(messages)) return '';
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i];
    if (m && m.role === 'user' && typeof m.content === 'string') return m.content;
  }
  return '';
}

function parseLine(line) {
  try { return JSON.parse(line); } catch { return null; }
}

// Returns the text fragment if event is a text_delta, otherwise null.
function extractTextDelta(event) {
  if (!event || event.type !== 'stream_event') return null;
  const inner = event.event;
  if (!inner || inner.type !== 'content_block_delta') return null;
  const delta = inner.delta;
  if (!delta || delta.type !== 'text_delta') return null;
  return typeof delta.text === 'string' ? delta.text : null;
}

function classifyCode(stderr) {
  const s = stderr.toLowerCase();
  if (s.includes('not authenticated') || s.includes('please log in') || s.includes('credential')) {
    return 'AUTH_FAILURE';
  }
  if (s.includes('rate limit') || s.includes('429')) return 'RATE_LIMITED';
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
  createClaudeAdapter,
  // exported for unit tests
  extractTextDelta,
  classifyCode,
};
