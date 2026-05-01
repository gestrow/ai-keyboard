# setup/

Phase 5 deliverable. Will house `setup.sh` — the one-paste-and-run Termux bootstrap that installs `nodejs`/`git`/`termux-api`, the user-selected provider CLIs (`claude`, `gemini`, `codex`), and registers the bridge as a `termux-services` unit (with optional `Termux:Boot` autostart).

See `../ARCHITECTURE.md` ("Module: setup/setup.sh") for the full design.
