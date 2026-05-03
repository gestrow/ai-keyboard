'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createServer } = require('../server');

// Mock adapter registry — keeps tests fast and deterministic. Each mock chat
// implementation drives a script of emitDelta/emitError/emitDone calls.
function buildMockRegistry({ probe, chats }) {
  return {
    probeAll: async () => probe,
    listProviders: () => probe.map((p) => p.id),
    createAdapter(provider) {
      const script = chats[provider];
      if (!script) return null;
      return {
        isAvailable: async () => ({ available: true }),
        chat: async (req, sse, signal) => script(req, sse, signal),
      };
    },
  };
}

async function startTestServer(adapters) {
  const server = createServer({ adapters, logger: false });
  await server.listen({ host: '127.0.0.1', port: 0 });
  const address = server.server.address();
  return {
    server,
    url: `http://127.0.0.1:${address.port}`,
    address,
    async close() { await server.close(); },
  };
}

async function readSseLines(response) {
  const text = await response.text();
  return text
    .split('\n')
    .filter((line) => line.startsWith('data: '))
    .map((line) => JSON.parse(line.slice(6)));
}

test('GET /health returns shape with provider availability', async () => {
  const reg = buildMockRegistry({
    probe: [
      { id: 'claude', available: true },
      { id: 'gemini', available: false, reason: 'not authenticated' },
    ],
    chats: {},
  });
  const { url, close } = await startTestServer(reg);
  try {
    const res = await fetch(`${url}/health`);
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.ok, true);
    assert.equal(typeof body.version, 'string');
    assert.equal(typeof body.uptimeSeconds, 'number');
    assert.deepEqual(body.providers, [
      { id: 'claude', available: true },
      { id: 'gemini', available: false, reason: 'not authenticated' },
    ]);
  } finally { await close(); }
});

test('GET /providers returns the providers array', async () => {
  const reg = buildMockRegistry({
    probe: [{ id: 'claude', available: true }],
    chats: {},
  });
  const { url, close } = await startTestServer(reg);
  try {
    const res = await fetch(`${url}/providers`);
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.deepEqual(body, [{ id: 'claude', available: true }]);
  } finally { await close(); }
});

test('POST /chat with mocked 3-delta adapter writes 4 events in order', async () => {
  const reg = buildMockRegistry({
    probe: [{ id: 'claude', available: true }],
    chats: {
      claude: async (_req, sse) => {
        sse.emitDelta('hello');
        sse.emitDelta(' ');
        sse.emitDelta('world');
        sse.emitDone();
      },
    },
  });
  const { url, close } = await startTestServer(reg);
  try {
    const res = await fetch(`${url}/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ provider: 'claude', messages: [{ role: 'user', content: 'hi' }] }),
    });
    assert.equal(res.status, 200);
    const events = await readSseLines(res);
    assert.deepEqual(events, [
      { type: 'delta', text: 'hello' },
      { type: 'delta', text: ' ' },
      { type: 'delta', text: 'world' },
      { type: 'done' },
    ]);
  } finally { await close(); }
});

test('POST /chat with unknown provider returns 400 with knownProviders', async () => {
  const reg = buildMockRegistry({
    probe: [{ id: 'claude', available: true }],
    chats: {},
  });
  const { url, close } = await startTestServer(reg);
  try {
    const res = await fetch(`${url}/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ provider: 'codex', messages: [{ role: 'user', content: 'x' }] }),
    });
    assert.equal(res.status, 400);
    const body = await res.json();
    assert.match(body.error, /codex/);
    assert.deepEqual(body.knownProviders, ['claude']);
  } finally { await close(); }
});

test('POST /chat with missing provider returns 400', async () => {
  const reg = buildMockRegistry({
    probe: [{ id: 'claude', available: true }],
    chats: {},
  });
  const { url, close } = await startTestServer(reg);
  try {
    const res = await fetch(`${url}/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ messages: [{ role: 'user', content: 'x' }] }),
    });
    assert.equal(res.status, 400);
  } finally { await close(); }
});

test('POST /chat where adapter throws emits error+done, server stays up', async () => {
  const reg = buildMockRegistry({
    probe: [{ id: 'claude', available: true }],
    chats: {
      claude: async () => { throw new Error('boom'); },
    },
  });
  const { url, close } = await startTestServer(reg);
  try {
    const res = await fetch(`${url}/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ provider: 'claude', messages: [{ role: 'user', content: 'x' }] }),
    });
    const events = await readSseLines(res);
    assert.equal(events.length, 2);
    assert.equal(events[0].type, 'error');
    assert.equal(events[0].code, 'UNKNOWN');
    assert.match(events[0].message, /boom/);
    assert.equal(events[1].type, 'done');

    // Verify the server is still responding after the error path.
    const health = await fetch(`${url}/health`);
    assert.equal(health.status, 200);
  } finally { await close(); }
});

test('POST /chat short-circuits when adapter reports unavailable', async () => {
  const reg = {
    probeAll: async () => [{ id: 'claude', available: false, reason: 'not authenticated' }],
    listProviders: () => ['claude'],
    createAdapter: () => ({
      isAvailable: async () => ({ available: false, reason: 'not authenticated' }),
      chat: async () => assert.fail('chat must not be called'),
    }),
  };
  const { url, close } = await startTestServer(reg);
  try {
    const res = await fetch(`${url}/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ provider: 'claude', messages: [{ role: 'user', content: 'x' }] }),
    });
    const events = await readSseLines(res);
    assert.equal(events[0].type, 'error');
    assert.equal(events[0].code, 'AUTH_FAILURE');
    assert.equal(events[1].type, 'done');
  } finally { await close(); }
});

test('POST /reauth returns 501 not implemented', async () => {
  const reg = buildMockRegistry({ probe: [], chats: {} });
  const { url, close } = await startTestServer(reg);
  try {
    const res = await fetch(`${url}/reauth`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ provider: 'claude' }),
    });
    assert.equal(res.status, 501);
    const body = await res.json();
    assert.match(body.error, /not implemented/i);
  } finally { await close(); }
});

test('Server bind address is loopback (127.0.0.1)', async () => {
  const reg = buildMockRegistry({ probe: [], chats: {} });
  const { server, address, close } = await startTestServer(reg);
  try {
    assert.equal(address.address, '127.0.0.1');
    // Confirm the server.address API does not silently report 0.0.0.0.
    assert.notEqual(address.address, '0.0.0.0');
    assert.notEqual(address.address, '::');
    // Also confirm the close handler is functional.
    assert.equal(typeof server.close, 'function');
  } finally { await close(); }
});
