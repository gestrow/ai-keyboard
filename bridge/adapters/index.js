'use strict';

// Provider adapter registry. Each adapter spawns the user-installed CLI as a
// subprocess and translates its protocol-specific stream output into the
// bridge's normalized SSE shape ({type:"delta"|"done"|"error",...}).

const { createClaudeAdapter } = require('./claude');
const { createGeminiAdapter } = require('./gemini');

const FACTORIES = {
  claude: createClaudeAdapter,
  gemini: createGeminiAdapter,
};

function listProviders() {
  return Object.keys(FACTORIES);
}

function createAdapter(provider, deps = {}) {
  const factory = FACTORIES[provider];
  if (!factory) return null;
  return factory(deps);
}

// Probe every provider for availability. Returns the array consumed by /health
// and /providers ({id, available, version?, reason?}).
async function probeAll(deps = {}) {
  const results = [];
  for (const id of listProviders()) {
    const adapter = createAdapter(id, deps);
    const status = await adapter.isAvailable();
    results.push({ id, ...status });
  }
  return results;
}

module.exports = {
  listProviders,
  createAdapter,
  probeAll,
};
