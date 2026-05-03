'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');

const claudeMod = require('../adapters/claude');
const geminiMod = require('../adapters/gemini');
const adaptersIndex = require('../adapters');

// Synchronous "spawn" mock. Each call records args and returns a configurable
// fake child process. Real child_process.spawn is async but its emitter shape
// is the only behavior we depend on, so a sync subclass is sufficient.
function fakeSpawn(scripts) {
  const calls = [];
  const spawn = (cmd, args, opts) => {
    calls.push({ cmd, args, opts });
    const script = (typeof scripts === 'function') ? scripts(cmd, args) : scripts[cmd];
    return makeFakeChild(script || { exitCode: 0 });
  };
  spawn.calls = calls;
  return spawn;
}

function makeFakeChild({ stdoutLines = [], stderr = '', exitCode = 0, errorOnSpawn = null }) {
  const child = new EventEmitter();
  child.stdout = new EventEmitter();
  child.stderr = new EventEmitter();
  child.stdin = {
    written: '',
    write(s) { this.written += s; return true; },
    end() { this._ended = true; },
    _ended: false,
  };
  child.kill = () => { child._killed = true; };

  setImmediate(() => {
    if (errorOnSpawn) {
      child.emit('error', errorOnSpawn);
      return;
    }
    for (const line of stdoutLines) {
      child.stdout.emit('data', Buffer.from(line + '\n', 'utf8'));
    }
    if (stderr) child.stderr.emit('data', Buffer.from(stderr, 'utf8'));
    child.emit('close', exitCode);
  });
  return child;
}

function makeSseRecorder() {
  const events = [];
  return {
    events,
    emitDelta(text) { events.push({ type: 'delta', text }); },
    emitDone() { events.push({ type: 'done' }); },
    emitError(code, message) { events.push({ type: 'error', code, message }); },
  };
}

const fakeFs = (existing) => ({
  access: async (p) => {
    if (existing.has(p)) return;
    const err = new Error('ENOENT'); err.code = 'ENOENT'; throw err;
  },
});

// --- Adapter registry --------------------------------------------------------

test('adapters/index lists claude and gemini', () => {
  assert.deepEqual(adaptersIndex.listProviders().sort(), ['claude', 'gemini']);
});

test('adapters/index returns null for unknown provider', () => {
  assert.equal(adaptersIndex.createAdapter('codex'), null);
});

// --- Claude adapter ---------------------------------------------------------

test('claude.extractTextDelta filters to content_block_delta+text_delta only', () => {
  const f = claudeMod.extractTextDelta;
  // Real text_delta — keep
  assert.equal(f({
    type: 'stream_event',
    event: { type: 'content_block_delta', delta: { type: 'text_delta', text: 'hi' } },
  }), 'hi');
  // Thinking_delta — drop
  assert.equal(f({
    type: 'stream_event',
    event: { type: 'content_block_delta', delta: { type: 'thinking_delta', thinking: 'x' } },
  }), null);
  // Signature_delta — drop
  assert.equal(f({
    type: 'stream_event',
    event: { type: 'content_block_delta', delta: { type: 'signature_delta', signature: '..' } },
  }), null);
  // Aggregated assistant message — drop (the per-token deltas already covered it)
  assert.equal(f({ type: 'assistant', message: { content: [{ type: 'text', text: 'hi' }] } }), null);
  // System init / result / rate_limit_event — drop
  assert.equal(f({ type: 'system', subtype: 'init' }), null);
  assert.equal(f({ type: 'result', subtype: 'success', result: 'hi' }), null);
  assert.equal(f({ type: 'rate_limit_event' }), null);
});

test('claude adapter translates a canned stream-json transcript to delta events', async () => {
  const transcript = [
    JSON.stringify({ type: 'system', subtype: 'init' }),
    JSON.stringify({ type: 'stream_event', event: { type: 'message_start' } }),
    JSON.stringify({ type: 'stream_event', event: { type: 'content_block_delta', delta: { type: 'thinking_delta', thinking: 'ignored' } } }),
    JSON.stringify({ type: 'stream_event', event: { type: 'content_block_delta', delta: { type: 'text_delta', text: 'Hi' } } }),
    JSON.stringify({ type: 'stream_event', event: { type: 'content_block_delta', delta: { type: 'text_delta', text: '!' } } }),
    JSON.stringify({ type: 'stream_event', event: { type: 'message_stop' } }),
    JSON.stringify({ type: 'result', subtype: 'success', result: 'Hi!' }),
  ];
  const spawn = fakeSpawn({
    which: { stdoutLines: ['/usr/bin/claude'], exitCode: 0 },
    claude: { stdoutLines: transcript, exitCode: 0 },
  });
  const adapter = claudeMod.createClaudeAdapter({
    spawn,
    fs: fakeFs(new Set(['/home/u/.claude/.credentials.json'])),
    env: { HOME: '/home/u' },
  });
  const sse = makeSseRecorder();
  await adapter.chat({ messages: [{ role: 'user', content: 'say hi' }], system: 'be terse' }, sse);
  assert.deepEqual(sse.events, [
    { type: 'delta', text: 'Hi' },
    { type: 'delta', text: '!' },
    { type: 'done' },
  ]);
  // Verify CLI flags include stream-json + verbose + system prompt
  const claudeCall = spawn.calls.find((c) => c.cmd === 'claude');
  assert.ok(claudeCall.args.includes('--input-format'));
  assert.ok(claudeCall.args.includes('stream-json'));
  assert.ok(claudeCall.args.includes('--verbose'));
  assert.ok(claudeCall.args.includes('--include-partial-messages'));
  assert.ok(claudeCall.args.includes('--append-system-prompt'));
  assert.ok(claudeCall.args.includes('be terse'));
});

test('claude adapter detects missing CLI on PATH and reports CLI_NOT_FOUND', async () => {
  const spawn = fakeSpawn({
    which: { stdoutLines: [], exitCode: 1 },
  });
  const adapter = claudeMod.createClaudeAdapter({
    spawn,
    fs: fakeFs(new Set()),
    env: { HOME: '/home/u' },
  });
  const status = await adapter.isAvailable();
  assert.equal(status.available, false);
  assert.match(status.reason, /not on PATH/i);
});

test('claude adapter detects missing OAuth credentials and reports not authenticated', async () => {
  const spawn = fakeSpawn({
    which: { stdoutLines: ['/usr/bin/claude'], exitCode: 0 },
  });
  const adapter = claudeMod.createClaudeAdapter({
    spawn,
    fs: fakeFs(new Set()), // no credential file
    env: { HOME: '/home/u' },  // no env API key either
  });
  const status = await adapter.isAvailable();
  assert.equal(status.available, false);
  assert.match(status.reason, /not authenticated/i);
});

test('claude adapter accepts ANTHROPIC_API_KEY env in lieu of credentials file', async () => {
  const spawn = fakeSpawn({
    which: { stdoutLines: ['/usr/bin/claude'], exitCode: 0 },
  });
  const adapter = claudeMod.createClaudeAdapter({
    spawn,
    fs: fakeFs(new Set()),
    env: { HOME: '/home/u', ANTHROPIC_API_KEY: 'sk-ant-test' },
  });
  const status = await adapter.isAvailable();
  assert.equal(status.available, true);
});

test('claude adapter abort signal kills the subprocess', async () => {
  // Use a script that never emits stdout/close — the abort is what tears it down.
  const child = makeFakeChild({ stdoutLines: [], exitCode: 0 });
  // Suppress the auto-emit by overriding the inner setImmediate machinery
  const spawn = (cmd) => {
    if (cmd === 'which') {
      return makeFakeChild({ stdoutLines: ['/usr/bin/claude'], exitCode: 0 });
    }
    // Block: don't auto-close. We'll signal abort instead.
    const blocking = new EventEmitter();
    blocking.stdout = new EventEmitter();
    blocking.stderr = new EventEmitter();
    blocking.stdin = { write() {}, end() {} };
    blocking.kill = () => { blocking._killed = true; setImmediate(() => blocking.emit('close', 143)); };
    return blocking;
  };
  const adapter = claudeMod.createClaudeAdapter({
    spawn,
    fs: fakeFs(new Set(['/home/u/.claude/.credentials.json'])),
    env: { HOME: '/home/u' },
  });
  const sse = makeSseRecorder();
  const ac = new AbortController();
  const chatPromise = adapter.chat(
    { messages: [{ role: 'user', content: 'x' }], system: '' },
    sse, ac.signal,
  );
  setImmediate(() => ac.abort());
  await chatPromise;
  // No error emitted (we suppressed the cleanup-error path on abort)
  assert.deepEqual(sse.events.filter((e) => e.type !== 'done'), []);
});

test('claude adapter classifies stderr "rate limit" as RATE_LIMITED', () => {
  assert.equal(claudeMod.classifyCode('Error: rate limit exceeded (429)\n'), 'RATE_LIMITED');
});

test('claude adapter classifies stderr "not authenticated" as AUTH_FAILURE', () => {
  assert.equal(claudeMod.classifyCode('Error: not authenticated, please log in\n'), 'AUTH_FAILURE');
});

// --- Gemini adapter ---------------------------------------------------------

test('gemini.extractTextDelta accepts assistant-delta lines only', () => {
  const f = geminiMod.extractTextDelta;
  // Real assistant delta — keep
  assert.equal(f({ type: 'message', role: 'assistant', delta: true, content: 'hi' }), 'hi');
  // User echo — drop
  assert.equal(f({ type: 'message', role: 'user', content: 'hi' }), null);
  // Final aggregated assistant (no delta:true) — drop to avoid duplication
  assert.equal(f({ type: 'message', role: 'assistant', content: 'hi' }), null);
  // Init and result — drop
  assert.equal(f({ type: 'init', session_id: 'x' }), null);
  assert.equal(f({ type: 'result', status: 'success' }), null);
});

test('gemini adapter translates canned stream-json transcript to deltas', async () => {
  const transcript = [
    JSON.stringify({ type: 'init', session_id: 'abc', model: 'gemini-2.5-flash' }),
    JSON.stringify({ type: 'message', role: 'user', content: 'say hi' }),
    JSON.stringify({ type: 'message', role: 'assistant', delta: true, content: 'hi' }),
    JSON.stringify({ type: 'message', role: 'assistant', delta: true, content: '!' }),
    JSON.stringify({ type: 'result', status: 'success' }),
  ];
  const spawn = fakeSpawn({
    which: { stdoutLines: ['/usr/bin/gemini'], exitCode: 0 },
    gemini: { stdoutLines: transcript, exitCode: 0 },
  });
  const adapter = geminiMod.createGeminiAdapter({
    spawn,
    fs: fakeFs(new Set(['/home/u/.gemini/oauth_creds.json'])),
    env: { HOME: '/home/u' },
  });
  const sse = makeSseRecorder();
  await adapter.chat({ messages: [{ role: 'user', content: 'say hi' }], system: 'be terse' }, sse);
  assert.deepEqual(sse.events, [
    { type: 'delta', text: 'hi' },
    { type: 'delta', text: '!' },
    { type: 'done' },
  ]);
  // Verify spawn args: -p with system prepended, -o stream-json
  const geminiCall = spawn.calls.find((c) => c.cmd === 'gemini');
  assert.ok(geminiCall.args.includes('-p'));
  const promptIdx = geminiCall.args.indexOf('-p');
  assert.match(geminiCall.args[promptIdx + 1], /be terse/);
  assert.match(geminiCall.args[promptIdx + 1], /say hi/);
  assert.ok(geminiCall.args.includes('-o'));
  assert.ok(geminiCall.args.includes('stream-json'));
});

test('gemini adapter reports CLI_CRASHED on result.status != success', async () => {
  const transcript = [
    JSON.stringify({ type: 'init' }),
    JSON.stringify({ type: 'result', status: 'error' }),
  ];
  const spawn = fakeSpawn({
    which: { stdoutLines: ['/usr/bin/gemini'], exitCode: 0 },
    gemini: { stdoutLines: transcript, exitCode: 0 },
  });
  const adapter = geminiMod.createGeminiAdapter({
    spawn,
    fs: fakeFs(new Set(['/home/u/.gemini/oauth_creds.json'])),
    env: { HOME: '/home/u' },
  });
  const sse = makeSseRecorder();
  await adapter.chat({ messages: [{ role: 'user', content: 'x' }], system: '' }, sse);
  assert.equal(sse.events[0].type, 'error');
  assert.equal(sse.events[0].code, 'CLI_CRASHED');
});

test('gemini adapter detects missing CLI as CLI_NOT_FOUND', async () => {
  const spawn = fakeSpawn({
    which: { stdoutLines: [], exitCode: 1 },
  });
  const adapter = geminiMod.createGeminiAdapter({
    spawn,
    fs: fakeFs(new Set()),
    env: { HOME: '/home/u' },
  });
  const status = await adapter.isAvailable();
  assert.equal(status.available, false);
  assert.match(status.reason, /not on PATH/i);
});

test('gemini adapter accepts GEMINI_API_KEY env in lieu of OAuth file', async () => {
  const spawn = fakeSpawn({
    which: { stdoutLines: ['/usr/bin/gemini'], exitCode: 0 },
  });
  const adapter = geminiMod.createGeminiAdapter({
    spawn,
    fs: fakeFs(new Set()),
    env: { HOME: '/home/u', GEMINI_API_KEY: 'AIzaTest' },
  });
  const status = await adapter.isAvailable();
  assert.equal(status.available, true);
});

test('gemini adapter classifies stderr quota as RATE_LIMITED', () => {
  assert.equal(geminiMod.classifyCode('Error: quota exceeded\n'), 'RATE_LIMITED');
});
