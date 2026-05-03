# setup/

One-paste Termux bootstrap for the AI Keyboard bridge. Installs Node, Git,
the Claude Code + Gemini CLIs, runs each provider's interactive OAuth flow,
deploys the bridge, registers it as a `termux-services` unit, and (if
Termux:Boot is installed) wires up an autostart hook on phone reboot.

## Prerequisites

- [Termux](https://f-droid.org/packages/com.termux/) from F-Droid (the Play
  Store build is dead since 2022).
- The repo's `bridge/` directory reachable from Termux — during development,
  pushed via `adb`.

## Quick start (development workflow)

From the dev box, with the device connected via USB:

```bash
adb push setup/setup.sh /data/local/tmp/
adb push bridge          /data/local/tmp/
```

Inside Termux on the device:

```bash
bash /data/local/tmp/setup.sh --bridge-source /data/local/tmp/bridge
```

You'll confirm the plan, pick providers (Claude / Gemini / both), and
complete each CLI's OAuth in a browser. Press `s` + Enter to skip a
provider's OAuth if it's already authenticated. The bridge ends up
supervised by `runit` on `127.0.0.1:8787`.

```bash
sv status ai-keyboard-bridge
tail -f $PREFIX/var/log/sv/ai-keyboard-bridge/current
```

## Hosted quick start (Phase 12 placeholder)

After v0.1.0 ships, this section will document a one-liner of the form:

```bash
curl -fsSL https://raw.githubusercontent.com/<org>/ai-keyboard/<tag>/setup/setup.sh > setup.sh
curl -fsSL https://github.com/<org>/ai-keyboard/releases/download/<tag>/bridge.tar.gz | tar xz
bash setup.sh --bridge-source ./bridge
```

The release asset doesn't exist yet — Phase 12 wires up the build. Until
then, use the `adb push` path above.

## Re-running

The script is idempotent — re-run any time:

- After a bridge update (push new `bridge/`, re-run): destination is wiped
  and redeployed, service restarts.
- After a transient `npm` / network failure: completed steps are skipped,
  failed ones retry.
- `--yes` skips the confirmation prompt; `--providers claude,gemini` skips
  the selection menu. OAuth prompts always pause for input.

## Uninstall

```bash
sv-disable ai-keyboard-bridge && sv down ai-keyboard-bridge
rm -rf $PREFIX/var/service/ai-keyboard-bridge \
       $PREFIX/var/log/sv/ai-keyboard-bridge \
       ~/ai-keyboard-bridge ~/claude-code-pinned ~/bin/claude \
       $PREFIX/bin/claude ~/.termux/boot/start-ai-keyboard-bridge
sed -i '/ai-keyboard: pin Claude Code/d' ~/.profile ~/.bashrc
npm uninstall -g @anthropic-ai/claude-code @google/gemini-cli
```

See `ARCHITECTURE.md` ("Termux CLI compatibility constraints") and
`PHASE_REVIEW.md` ("Known accepted corner cases") for why Claude Code is
pinned to v2.1.112 with a snapshot + wrapper + `DISABLE_AUTOUPDATER=1`.
