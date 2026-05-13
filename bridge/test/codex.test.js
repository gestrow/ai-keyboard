'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');

const codexMod = require('../adapters/codex');

// Synchronous "spawn" mock. Each call records args and returns a configurable
// fake child process — same shape as bridge/test/adapters.test.js so the test
// surface area stays consistent across adapters.
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

// --- Pure helpers --------------------------------------------------------

test('codex.extractTextDelta accepts agent_message and agent_message_delta only', () => {
  const f = codexMod.extractTextDelta;
  assert.equal(f({ type: 'agent_message', content: { text: 'hi' } }), 'hi');
  assert.equal(f({ type: 'agent_message_delta', content: { text: ' world' } }), ' world');
  // Flat `text` field also tolerated (codex patch releases may vary)
  assert.equal(f({ type: 'agent_message', text: 'flat' }), 'flat');
  // Terminal events — not deltas
  assert.equal(f({ type: 'task_complete' }), null);
  assert.equal(f({ type: 'agent_message_done' }), null);
  // Tool-use, thinking, planner — dropped
  assert.equal(f({ type: 'tool_use', content: { text: 'should-not-appear' } }), null);
  assert.equal(f({ type: 'thinking', content: { text: 'inner thought' } }), null);
  // Empty / missing text — drop
  assert.equal(f({ type: 'agent_message', content: { text: '' } }), null);
  assert.equal(f({ type: 'agent_message_delta' }), null);
  // Non-object — drop
  assert.equal(f(null), null);
  assert.equal(f('not an object'), null);
});

test('codex.isTerminalEvent recognises both terminal event names', () => {
  assert.equal(codexMod.isTerminalEvent({ type: 'task_complete' }), true);
  assert.equal(codexMod.isTerminalEvent({ type: 'agent_message_done' }), true);
  assert.equal(codexMod.isTerminalEvent({ type: 'agent_message' }), false);
  assert.equal(codexMod.isTerminalEvent({ type: 'error' }), false);
});

test('codex.flattenMessages prepends System line when system arg present', () => {
  const out = codexMod.flattenMessages(
    [{ role: 'user', content: 'rewrite this' }],
    'be terse',
  );
  assert.match(out, /^System: be terse/);
  assert.match(out, /User: rewrite this/);
});

test('codex.flattenMessages omits System line when system is empty', () => {
  const out = codexMod.flattenMessages(
    [{ role: 'user', content: 'hi' }],
    '',
  );
  assert.equal(out, 'User: hi');
});

test('codex.flattenMessages handles empty messages array', () => {
  assert.equal(codexMod.flattenMessages([], ''), '');
  assert.equal(codexMod.flattenMessages([], 'sys'), 'System: sys');
});

test('codex.flattenMessages preserves multi-turn ordering with role headers', () => {
  const out = codexMod.flattenMessages(
    [
      { role: 'user', content: 'u1' },
      { role: 'assistant', content: 'a1' },
      { role: 'user', content: 'u2' },
    ],
    '',
  );
  assert.equal(out, 'User: u1\n\nAssistant: a1\n\nUser: u2');
});

test('codex.classifyCode maps 401 / unauthorized to AUTH_FAILURE', () => {
  assert.equal(codexMod.classifyCode('Error: unauthorized (401)\n'), 'AUTH_FAILURE');
  assert.equal(codexMod.classifyCode('please log in via codex login\n'), 'AUTH_FAILURE');
});

test('codex.classifyCode maps quota / 429 to RATE_LIMITED', () => {
  assert.equal(codexMod.classifyCode('Error: 429 rate limit exceeded\n'), 'RATE_LIMITED');
  assert.equal(codexMod.classifyCode('quota exceeded for the day\n'), 'RATE_LIMITED');
});

// --- isAvailable --------------------------------------------------------

test('codex adapter detects missing CLI on PATH', async () => {
  const spawn = fakeSpawn({
    which: { stdoutLines: [], exitCode: 1 },
  });
  const adapter = codexMod.createCodexAdapter({
    spawn,
    fs: fakeFs(new Set()),
    env: { HOME: '/home/u' },
  });
  const status = await adapter.isAvailable();
  assert.equal(status.available, false);
  assert.match(status.reason, /not on PATH/i);
});

test('codex adapter detects missing auth.json', async () => {
  const spawn = fakeSpawn({
    which: { stdoutLines: ['/usr/bin/codex'], exitCode: 0 },
  });
  const adapter = codexMod.createCodexAdapter({
    spawn,
    fs: fakeFs(new Set()),
    env: { HOME: '/home/u' },
  });
  const status = await adapter.isAvailable();
  assert.equal(status.available, false);
  assert.match(status.reason, /not authenticated/i);
});

test('codex adapter reports available when CLI + auth.json present', async () => {
  const spawn = fakeSpawn({
    which: { stdoutLines: ['/usr/bin/codex'], exitCode: 0 },
  });
  const adapter = codexMod.createCodexAdapter({
    spawn,
    fs: fakeFs(new Set(['/home/u/.codex/auth.json'])),
    env: { HOME: '/home/u' },
  });
  const status = await adapter.isAvailable();
  assert.equal(status.available, true);
});

test('codex adapter accepts OPENAI_API_KEY env in lieu of auth.json', async () => {
  const spawn = fakeSpawn({
    which: { stdoutLines: ['/usr/bin/codex'], exitCode: 0 },
  });
  const adapter = codexMod.createCodexAdapter({
    spawn,
    fs: fakeFs(new Set()),
    env: { HOME: '/home/u', OPENAI_API_KEY: 'sk-test' },
  });
  const status = await adapter.isAvailable();
  assert.equal(status.available, true);
});

// --- chat: happy path ---------------------------------------------------

test('codex adapter translates a canned exec --json transcript to delta events', async () => {
  const transcript = [
    JSON.stringify({ type: 'session_start', session_id: 'abc' }),
    JSON.stringify({ type: 'agent_message_delta', content: { text: 'Hi' } }),
    JSON.stringify({ type: 'agent_message_delta', content: { text: ' there' } }),
    JSON.stringify({ type: 'task_complete' }),
  ];
  const spawn = fakeSpawn({
    which: { stdoutLines: ['/usr/bin/codex'], exitCode: 0 },
    codex: { stdoutLines: transcript, exitCode: 0 },
  });
  const adapter = codexMod.createCodexAdapter({
    spawn,
    fs: fakeFs(new Set(['/home/u/.codex/auth.json'])),
    env: { HOME: '/home/u' },
  });
  const sse = makeSseRecorder();
  await adapter.chat(
    { messages: [{ role: 'user', content: 'say hi' }], system: 'be terse' },
    sse,
  );
  assert.deepEqual(sse.events, [
    { type: 'delta', text: 'Hi' },
    { type: 'delta', text: ' there' },
    { type: 'done' },
  ]);
  // Verify spawn args: exec --json with role-tagged prompt
  const codexCall = spawn.calls.find((c) => c.cmd === 'codex');
  assert.deepEqual(codexCall.args.slice(0, 2), ['exec', '--json']);
  assert.match(codexCall.args[2], /System: be terse/);
  assert.match(codexCall.args[2], /User: say hi/);
});

test('codex adapter emits delta for agent_message (non-delta) full-text event', async () => {
  const transcript = [
    JSON.stringify({ type: 'agent_message', content: { text: 'one-shot reply' } }),
    JSON.stringify({ type: 'task_complete' }),
  ];
  const spawn = fakeSpawn({
    which: { stdoutLines: ['/usr/bin/codex'], exitCode: 0 },
    codex: { stdoutLines: transcript, exitCode: 0 },
  });
  const adapter = codexMod.createCodexAdapter({
    spawn,
    fs: fakeFs(new Set(['/home/u/.codex/auth.json'])),
    env: { HOME: '/home/u' },
  });
  const sse = makeSseRecorder();
  await adapter.chat(
    { messages: [{ role: 'user', content: 'x' }], system: '' },
    sse,
  );
  assert.deepEqual(sse.events, [
    { type: 'delta', text: 'one-shot reply' },
    { type: 'done' },
  ]);
});

test('codex adapter drops tool_use, thinking, planner events silently', async () => {
  const transcript = [
    JSON.stringify({ type: 'thinking', content: { text: 'inner thought' } }),
    JSON.stringify({ type: 'tool_use', content: { name: 'shell' } }),
    JSON.stringify({ type: 'planner', content: { text: 'plan' } }),
    JSON.stringify({ type: 'agent_message_delta', content: { text: 'visible' } }),
    JSON.stringify({ type: 'task_complete' }),
  ];
  const spawn = fakeSpawn({
    which: { stdoutLines: ['/usr/bin/codex'], exitCode: 0 },
    codex: { stdoutLines: transcript, exitCode: 0 },
  });
  const adapter = codexMod.createCodexAdapter({
    spawn,
    fs: fakeFs(new Set(['/home/u/.codex/auth.json'])),
    env: { HOME: '/home/u' },
  });
  const sse = makeSseRecorder();
  await adapter.chat(
    { messages: [{ role: 'user', content: 'x' }], system: '' },
    sse,
  );
  // Hidden events must not surface in the SSE stream.
  assert.deepEqual(sse.events, [
    { type: 'delta', text: 'visible' },
    { type: 'done' },
  ]);
});

// --- chat: error paths --------------------------------------------------

test('codex adapter tolerates non-JSON heartbeat lines on stdout', async () => {
  const transcript = [
    // Some Codex releases interleave stderr-ish prctl warnings on stdout
    // before the JSONL starts; the parser must skip these without crashing.
    'warning: prctl(PR_SET_DUMPABLE) returned EPERM',
    JSON.stringify({ type: 'agent_message_delta', content: { text: 'ok' } }),
    JSON.stringify({ type: 'task_complete' }),
  ];
  const spawn = fakeSpawn({
    which: { stdoutLines: ['/usr/bin/codex'], exitCode: 0 },
    codex: { stdoutLines: transcript, exitCode: 0 },
  });
  const adapter = codexMod.createCodexAdapter({
    spawn,
    fs: fakeFs(new Set(['/home/u/.codex/auth.json'])),
    env: { HOME: '/home/u' },
  });
  const sse = makeSseRecorder();
  await adapter.chat(
    { messages: [{ role: 'user', content: 'x' }], system: '' },
    sse,
  );
  // Only the parsed delta + done should be emitted.
  assert.deepEqual(sse.events, [
    { type: 'delta', text: 'ok' },
    { type: 'done' },
  ]);
});

test('codex adapter emits error code on type:error event without echoing message', async () => {
  const transcript = [
    // PRIVACY: server-side error text may include prompt fragments. The adapter
    // must emit a static structural string, not echo evt.message verbatim.
    JSON.stringify({ type: 'error', message: 'context window exceeded: "draft email..."' }),
  ];
  const spawn = fakeSpawn({
    which: { stdoutLines: ['/usr/bin/codex'], exitCode: 0 },
    codex: { stdoutLines: transcript, exitCode: 0 },
  });
  const adapter = codexMod.createCodexAdapter({
    spawn,
    fs: fakeFs(new Set(['/home/u/.codex/auth.json'])),
    env: { HOME: '/home/u' },
  });
  const sse = makeSseRecorder();
  await adapter.chat(
    { messages: [{ role: 'user', content: 'x' }], system: '' },
    sse,
  );
  assert.equal(sse.events.length, 1);
  assert.equal(sse.events[0].type, 'error');
  assert.equal(sse.events[0].code, 'CLI_CRASHED');
  // Static message — does NOT contain the prompt fragment from evt.message.
  assert.equal(sse.events[0].message, 'codex reported an error event');
  assert.doesNotMatch(sse.events[0].message, /draft email/);
});

test('codex adapter classifies nonzero exit code as CLI_CRASHED', async () => {
  const spawn = fakeSpawn({
    which: { stdoutLines: ['/usr/bin/codex'], exitCode: 0 },
    codex: { stdoutLines: [], stderr: 'fatal: something went wrong', exitCode: 1 },
  });
  const adapter = codexMod.createCodexAdapter({
    spawn,
    fs: fakeFs(new Set(['/home/u/.codex/auth.json'])),
    env: { HOME: '/home/u' },
  });
  const sse = makeSseRecorder();
  await adapter.chat(
    { messages: [{ role: 'user', content: 'x' }], system: '' },
    sse,
  );
  assert.equal(sse.events.length, 1);
  assert.equal(sse.events[0].type, 'error');
  assert.equal(sse.events[0].code, 'CLI_CRASHED');
});

test('codex adapter classifies prctl-style stderr as CLI_CRASHED (regression for #6757)', async () => {
  // Per openai/codex#6757, some Termux+proot setups have codex die during
  // prctl(PR_SET_DUMPABLE, 0). The adapter must not crash on the stderr —
  // it should classify the non-zero exit and emit a CLI_CRASHED error.
  const spawn = fakeSpawn({
    which: { stdoutLines: ['/usr/bin/codex'], exitCode: 0 },
    codex: {
      stdoutLines: [],
      stderr: 'thread main panicked at prctl(PR_SET_DUMPABLE, 0): EPERM\n',
      exitCode: 134,
    },
  });
  const adapter = codexMod.createCodexAdapter({
    spawn,
    fs: fakeFs(new Set(['/home/u/.codex/auth.json'])),
    env: { HOME: '/home/u' },
  });
  const sse = makeSseRecorder();
  await adapter.chat(
    { messages: [{ role: 'user', content: 'x' }], system: '' },
    sse,
  );
  assert.equal(sse.events[0].type, 'error');
  assert.equal(sse.events[0].code, 'CLI_CRASHED');
});

test('codex adapter spawn error surfaces as CLI_NOT_FOUND', async () => {
  const spawn = (cmd) => {
    if (cmd === 'which') {
      return makeFakeChild({ stdoutLines: ['/usr/bin/codex'], exitCode: 0 });
    }
    return makeFakeChild({ errorOnSpawn: new Error('ENOENT'), exitCode: 1 });
  };
  const adapter = codexMod.createCodexAdapter({
    spawn,
    fs: fakeFs(new Set(['/home/u/.codex/auth.json'])),
    env: { HOME: '/home/u' },
  });
  const sse = makeSseRecorder();
  await adapter.chat(
    { messages: [{ role: 'user', content: 'x' }], system: '' },
    sse,
  );
  assert.equal(sse.events.length, 1);
  assert.equal(sse.events[0].type, 'error');
  assert.equal(sse.events[0].code, 'CLI_NOT_FOUND');
});
