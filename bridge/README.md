# bridge/

Phase 4 deliverable. Will house the Termux-side Node service (fastify + subprocess adapters for `claude` / `gemini` / `codex` CLIs) that exposes `POST /chat`, `GET /health`, `GET /providers`, `POST /reauth` on `127.0.0.1:8787`.

See `../ARCHITECTURE.md` ("Module: bridge/") for the full design.
