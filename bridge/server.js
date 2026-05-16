'use strict';

const Fastify = require('fastify');
const adapters = require('./adapters');

// Pinned to ai-keyboard-bridge package.json — bumped together with each release.
const BRIDGE_VERSION = '0.1.0';
const HOST = '127.0.0.1';
const PORT = 8787;

function createServer(options = {}) {
  // Tests inject mocked adapter factories via options.adapters; production code
  // path uses the real registry.
  const adapterRegistry = options.adapters || adapters;
  const startedAt = Date.now();

  const fastify = Fastify({
    logger: options.logger ?? { level: 'info' },
    disableRequestLogging: false,
  });

  // GET /health — used by IME's setup wizard polling and by users via curl.
  fastify.get('/health', async () => {
    return {
      ok: true,
      version: BRIDGE_VERSION,
      uptimeSeconds: Math.floor((Date.now() - startedAt) / 1000),
      providers: await adapterRegistry.probeAll(),
    };
  });

  // GET /providers — same provider array as /health, returned standalone.
  fastify.get('/providers', async () => {
    return await adapterRegistry.probeAll();
  });

  // POST /chat — SSE stream of normalized {type:"delta"|"done"|"error",...}
  fastify.post('/chat', async (request, reply) => {
    const body = request.body || {};
    const provider = body.provider;
    if (typeof provider !== 'string' || !provider.length) {
      return reply.code(400).send({ error: 'missing provider' });
    }
    const adapter = adapterRegistry.createAdapter(provider);
    if (!adapter) {
      return reply.code(400).send({
        error: `unknown provider: ${provider}`,
        knownProviders: adapterRegistry.listProviders(),
      });
    }
    if (!Array.isArray(body.messages)) {
      return reply.code(400).send({ error: 'missing messages array' });
    }

    reply.raw.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive',
      'X-Accel-Buffering': 'no',
    });

    const sse = sseEmitter(reply.raw);
    const abortController = new AbortController();
    // Listen on the response stream, not the request. Node fires 'close' on
    // IncomingMessage as soon as the request body is fully consumed (which
    // happens before our handler awaits the adapter), so listening on
    // request.raw fires abort() on every call — adapter.chat then short-
    // circuits at its `if (abortSignal?.aborted) return;` and we end the SSE
    // stream with zero events emitted. reply.raw's 'close' fires when the
    // response stream is closed; writableEnded distinguishes "we called end()"
    // (writableEnded === true → normal completion) from "client disconnected
    // mid-stream" (writableEnded === false → abort the subprocess).
    reply.raw.on('close', () => {
      if (!reply.raw.writableEnded) {
        abortController.abort();
      }
    });

    try {
      const available = await adapter.isAvailable();
      if (!available.available) {
        const code = available.reason && available.reason.includes('not authenticated')
          ? 'AUTH_FAILURE' : 'CLI_NOT_FOUND';
        sse.emitError(code, available.reason || 'unavailable');
        sse.emitDone();
        sse.end();
        return reply;
      }
      await adapter.chat(
        { messages: body.messages, system: body.system },
        sse,
        abortController.signal,
      );
    } catch (err) {
      sse.emitError('UNKNOWN', err && err.message ? err.message : 'unknown error');
      sse.emitDone();
    } finally {
      sse.end();
    }
    return reply;
  });

  // POST /reauth — Phase 5b/6 wires this to fire `<cli> /login` in Termux's
  // foreground via the IME's RUN_COMMAND orchestrator. The bridge itself can't
  // open a browser, so Phase 4 returns 501.
  fastify.post('/reauth', async (request, reply) => {
    return reply.code(501).send({
      error: 'not implemented',
      detail: 'reauth is driven from the Android side by firing claude/gemini CLI in Termux foreground. See Phase 5b/6.',
    });
  });

  return fastify;
}

// Wraps a Node http.ServerResponse in the bridge's normalized SSE event API.
// All event payloads are single-line JSON to keep parsing trivial on the
// consumer side; multi-line `data:` would require dedicated reassembly.
function sseEmitter(rawResponse) {
  let ended = false;
  const writeEvent = (obj) => {
    if (ended) return;
    try {
      rawResponse.write(`data: ${JSON.stringify(obj)}\n\n`);
    } catch {
      // socket already closed; treat as ended
      ended = true;
    }
  };
  return {
    emitDelta(text) {
      if (typeof text !== 'string' || text.length === 0) return;
      writeEvent({ type: 'delta', text });
    },
    emitDone() { writeEvent({ type: 'done' }); },
    emitError(code, message) {
      writeEvent({ type: 'error', code: String(code), message: String(message ?? '') });
    },
    end() {
      if (ended) return;
      ended = true;
      try { rawResponse.end(); } catch { /* swallow */ }
    },
  };
}

async function start() {
  const fastify = createServer();
  // Bind only to loopback. Hardcoded — there is no env override on purpose.
  // The bridge is local-only by design; exposing it on 0.0.0.0 would let any
  // app on the LAN talk to the user's authenticated CLIs.
  try {
    await fastify.listen({ port: PORT, host: HOST });
  } catch (err) {
    if (err && err.code === 'EADDRINUSE') {
      fastify.log.error(`port ${PORT} in use; another bridge instance running?`);
    } else {
      fastify.log.error(err);
    }
    process.exit(1);
  }

  const shutdown = async (signal) => {
    fastify.log.info(`received ${signal}, shutting down`);
    try { await fastify.close(); } catch (err) { fastify.log.error(err); }
    process.exit(0);
  };
  process.on('SIGINT', () => shutdown('SIGINT'));
  process.on('SIGTERM', () => shutdown('SIGTERM'));
  process.on('unhandledRejection', (err) => {
    fastify.log.error({ err }, 'unhandled rejection — crashing');
    process.exit(1);
  });
}

if (require.main === module) {
  start();
}

module.exports = { createServer, sseEmitter, BRIDGE_VERSION, HOST, PORT };
