#!/data/data/com.termux/files/usr/bin/bash
# AI Keyboard bridge setup (Phase 5a). Idempotent — every step checks current
# state before acting; re-runs reconcile to the same end-state. Privacy: never
# logs API keys, OAuth tokens, persona content, or model output.
# `set -e` deliberately omitted; use explicit `if !...; then` for retry safety.

set -uo pipefail

# Pinned versions. The Claude Code pin is load-bearing: ≥ 2.1.113 ships
# glibc-only and breaks on Termux's Bionic libc. See PHASE_REVIEW.md
# "Known accepted corner cases" — Phase 12 rechecks before each release.
CLAUDE_CODE_VERSION="2.1.112"
GEMINI_CLI_VERSION="latest"
NODE_REQUIRED_MAJOR=20

TERMUX_PREFIX="/data/data/com.termux/files/usr"
HOME_BIN="$HOME/bin"
CLAUDE_SNAPSHOT="$HOME/claude-code-pinned"
BRIDGE_DEST="$HOME/ai-keyboard-bridge"
SERVICE_DIR="$TERMUX_PREFIX/var/service/ai-keyboard-bridge"
SERVICE_LOG_DIR="$TERMUX_PREFIX/var/log/sv/ai-keyboard-bridge"
TERMUX_PROPS="$HOME/.termux/termux.properties"
BOOT_HOOK_DIR="$HOME/.termux/boot"

SUPPORTED_PROVIDERS=(claude gemini)
SELECTED_PROVIDERS=()
BRIDGE_SOURCE=""
ASSUME_YES=0
PROVIDERS_VIA_FLAG=0

# Resolve script's own directory for `--bridge-source` auto-detection.
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
DEFAULT_BRIDGE_SOURCE="$(dirname "$SCRIPT_DIR")/bridge"

if [[ -t 1 ]]; then
    C_RESET=$'\033[0m'; C_BOLD=$'\033[1m'; C_DIM=$'\033[2m'
    C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'; C_RED=$'\033[31m'; C_BLUE=$'\033[34m'
else
    C_RESET=""; C_BOLD=""; C_DIM=""; C_GREEN=""; C_YELLOW=""; C_RED=""; C_BLUE=""
fi

log_step()  { printf '\n%s== %s%s\n' "$C_BOLD$C_BLUE" "$1" "$C_RESET"; }
log_info()  { printf '%s%s\n'        "$C_DIM" "$1$C_RESET"; }
log_ok()    { printf '%s✓ %s%s\n'   "$C_GREEN" "$1" "$C_RESET"; }
log_skip()  { printf '%s↷ %s%s\n'   "$C_DIM" "$1" "$C_RESET"; }
log_warn()  { printf '%s! %s%s\n'   "$C_YELLOW" "$1" "$C_RESET" >&2; }
log_error() { printf '%s✗ %s%s\n'   "$C_RED" "$1" "$C_RESET" >&2; }
die()       { log_error "$1"; exit "${2:-1}"; }

ensure_line_in_file() {
    # Append $line to $file iff a verbatim match isn't already there.
    local file="$1" line="$2"
    if [[ ! -f "$file" ]]; then
        mkdir -p "$(dirname "$file")"
        : > "$file"
    fi
    if ! grep -Fxq -- "$line" "$file" 2>/dev/null; then
        printf '%s\n' "$line" >> "$file"
        return 0
    fi
    return 1
}

provider_selected() {
    local p="$1" s
    for s in "${SELECTED_PROVIDERS[@]:-}"; do
        [[ "$s" == "$p" ]] && return 0
    done
    return 1
}

trim() {
    local s="$1"
    s="${s#"${s%%[![:space:]]*}"}"
    s="${s%"${s##*[![:space:]]}"}"
    printf '%s' "$s"
}

node_major() {
    if ! command -v node >/dev/null 2>&1; then
        return 0
    fi
    node --version 2>/dev/null | sed -nE 's/^v([0-9]+).*/\1/p'
}

# Probe http://127.0.0.1:8787/health using node's built-in fetch (Node ≥ 18).
# Echoes the response body on success, returns non-zero on timeout/failure.
probe_health() {
    local timeout_secs="${1:-10}" start now elapsed body
    start=$(date +%s)
    while true; do
        now=$(date +%s)
        elapsed=$((now - start))
        if (( elapsed >= timeout_secs )); then
            return 1
        fi
        if body=$(node -e '
            const ac = new AbortController();
            const t = setTimeout(() => ac.abort(), 2000);
            fetch("http://127.0.0.1:8787/health", { signal: ac.signal })
                .then(r => r.text())
                .then(t => { process.stdout.write(t); })
                .catch(() => process.exit(1))
                .finally(() => clearTimeout(t));
        ' 2>/dev/null); then
            printf '%s\n' "$body"
            return 0
        fi
        sleep 1
    done
}

usage() {
    cat <<'EOF'
Usage: bash setup.sh [OPTIONS]

  --bridge-source DIR  bridge/ tree to deploy (default: <script_dir>/../bridge)
  --providers LIST     comma list (claude,gemini); skips interactive menu
  -y, --yes            skip the initial confirm; OAuth prompts still pause
  -h, --help           print this and exit

Idempotent — re-run any time. Codex is Phase 11.
EOF
}

parse_args() {
    while (( $# > 0 )); do
        case "$1" in
            --bridge-source)   shift; [[ $# -gt 0 ]] || die "--bridge-source requires a path"; BRIDGE_SOURCE="$1"; shift ;;
            --bridge-source=*) BRIDGE_SOURCE="${1#*=}"; shift ;;
            --providers)       shift; [[ $# -gt 0 ]] || die "--providers requires a comma list"; parse_providers_arg "$1"; shift ;;
            --providers=*)     parse_providers_arg "${1#*=}"; shift ;;
            -y|--yes)          ASSUME_YES=1; shift ;;
            -h|--help)         usage; exit 0 ;;
            *)                 die "Unknown argument: $1 (try --help)" ;;
        esac
    done
    [[ -n "$BRIDGE_SOURCE" ]] || BRIDGE_SOURCE="$DEFAULT_BRIDGE_SOURCE"
}

parse_providers_arg() {
    PROVIDERS_VIA_FLAG=1
    SELECTED_PROVIDERS=()
    local raw="$1" part
    IFS=',' read -ra parts <<< "$raw"
    for part in "${parts[@]}"; do
        part=$(trim "$part")
        case "$part" in
            "") ;;
            claude|gemini) SELECTED_PROVIDERS+=("$part") ;;
            codex) die "Codex is Phase 11 — not part of 5a's setup script" ;;
            *) die "Unknown provider: $part (supported: claude, gemini)" ;;
        esac
    done
    [[ ${#SELECTED_PROVIDERS[@]} -gt 0 ]] || die "--providers list was empty"
}

require_termux() {
    if [[ ! -d "$TERMUX_PREFIX" ]] || ! command -v pkg >/dev/null 2>&1; then
        die "This script must run inside Termux. \$TERMUX_PREFIX ($TERMUX_PREFIX) not found or 'pkg' missing."
    fi
    log_ok "Running inside Termux ($TERMUX_PREFIX)"
}

print_banner() {
    cat <<EOF

${C_BOLD}AI Keyboard — Termux bridge setup${C_RESET}
Pins: Claude Code v${CLAUDE_CODE_VERSION}, Gemini CLI ${GEMINI_CLI_VERSION}, Node ≥ ${NODE_REQUIRED_MAJOR}.
Steps: termux.properties, pkg install, provider OAuth, bridge → ${BRIDGE_DEST},
termux-services unit + optional Termux:Boot hook, /health probe.
Safe to re-run.

EOF
}

confirm_proceed_or_exit() {
    if (( ASSUME_YES == 1 )); then log_skip "Confirmation skipped (--yes)"; return 0; fi
    printf '%sProceed? [y/N] %s' "$C_BOLD" "$C_RESET"
    local reply
    read -r reply || die "stdin closed before confirmation"
    case "$reply" in
        y|Y|yes|YES) ;;
        *) log_info "Aborted by user."; exit 0 ;;
    esac
}

set_allow_external_apps() {
    log_step "1/9 allow-external-apps in $TERMUX_PROPS"
    if ensure_line_in_file "$TERMUX_PROPS" "allow-external-apps = true"; then
        if command -v termux-reload-settings >/dev/null 2>&1; then
            termux-reload-settings || log_warn "termux-reload-settings exited non-zero (continuing)"
        fi
        log_ok "Wrote allow-external-apps = true"
    else
        log_skip "allow-external-apps already set"
    fi
}

install_termux_packages() {
    log_step "2/9 Installing Termux packages"
    local pkgs=(nodejs git termux-api which ripgrep)
    local missing=()
    local p
    for p in "${pkgs[@]}"; do
        if ! dpkg -s "$p" >/dev/null 2>&1; then
            missing+=("$p")
        fi
    done
    if (( ${#missing[@]} == 0 )); then
        log_skip "All required packages already installed (${pkgs[*]})"
    else
        log_info "Installing: ${missing[*]}"
        if ! pkg update -y >/dev/null 2>&1; then
            log_warn "pkg update returned non-zero — continuing with install"
        fi
        if ! pkg install -y "${missing[@]}"; then
            die "pkg install failed for: ${missing[*]} — check connectivity and retry"
        fi
        log_ok "Installed ${missing[*]}"
    fi

    local major
    major=$(node_major)
    if [[ -z "$major" ]]; then
        die "node is missing after pkg install — bailing"
    fi
    if (( major < NODE_REQUIRED_MAJOR )); then
        die "node v$major found, but bridge requires Node ≥ v$NODE_REQUIRED_MAJOR. Run \`pkg upgrade nodejs\` and retry."
    fi
    log_ok "node v$(node --version | sed 's/^v//') ≥ v$NODE_REQUIRED_MAJOR"
}

select_providers() {
    log_step "3/9 Selecting providers"
    if (( PROVIDERS_VIA_FLAG == 1 )); then
        log_skip "Providers set via --providers: ${SELECTED_PROVIDERS[*]}"
        return 0
    fi

    cat <<EOF

  1) Both Claude and Gemini  ${C_DIM}[recommended]${C_RESET}
  2) Claude only
  3) Gemini only

EOF
    local choice
    while true; do
        printf '%sChoose 1-3 (default 1): %s' "$C_BOLD" "$C_RESET"
        if ! read -r choice; then
            choice=""
        fi
        case "$choice" in
            ""|1) SELECTED_PROVIDERS=(claude gemini); break ;;
            2)    SELECTED_PROVIDERS=(claude); break ;;
            3)    SELECTED_PROVIDERS=(gemini); break ;;
            *)    log_warn "Invalid choice. Enter 1, 2, or 3." ;;
        esac
    done
    log_ok "Will install: ${SELECTED_PROVIDERS[*]}"
}

install_claude_code_if_selected() {
    log_step "4/9 Claude Code (v$CLAUDE_CODE_VERSION pinned)"
    if ! provider_selected claude; then
        log_skip "Claude not selected"
        return 0
    fi

    # Disable the autoupdater FIRST — write to BOTH ~/.profile (login-shell
    # source) and ~/.bashrc (RUN_COMMAND subshells). Belt and suspenders.
    local marker="export DISABLE_AUTOUPDATER=1  # ai-keyboard: pin Claude Code (do not remove)"
    local wrote=0
    ensure_line_in_file "$HOME/.profile" "$marker" && wrote=1
    ensure_line_in_file "$HOME/.bashrc"  "$marker" && wrote=1
    if (( wrote == 1 )); then
        log_ok "DISABLE_AUTOUPDATER=1 exported from ~/.profile and ~/.bashrc"
    else
        log_skip "DISABLE_AUTOUPDATER already set in ~/.profile and ~/.bashrc"
    fi
    export DISABLE_AUTOUPDATER=1  # this shell also needs it for the install below

    # Clear any clobbered prior install (idempotent — exits 0 if not installed).
    log_info "Clearing any prior @anthropic-ai/claude-code install"
    npm uninstall -g @anthropic-ai/claude-code >/dev/null 2>&1 || true

    log_info "npm i -g @anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}"
    if ! npm i -g "@anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}"; then
        die "npm install failed for @anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}. If the version was pulled from the registry, try the next-lower 2.1.x and file an issue."
    fi

    local npm_root
    npm_root=$(npm root -g 2>/dev/null)
    [[ -n "$npm_root" ]] || die "could not resolve \`npm root -g\`"
    local cli_js="$npm_root/@anthropic-ai/claude-code/cli.js"
    [[ -f "$cli_js" ]] || die "Expected $cli_js not found after install — pinned version may have dropped cli.js. See ARCHITECTURE.md Termux compat section."
    log_ok "cli.js present at $cli_js"

    # Snapshot outside npm's reach so a future autoupdate can't clobber it.
    log_info "Snapshotting install to $CLAUDE_SNAPSHOT"
    rm -rf "$CLAUDE_SNAPSHOT"
    cp -r "$npm_root/@anthropic-ai/claude-code" "$CLAUDE_SNAPSHOT" \
        || die "failed to snapshot Claude Code to $CLAUDE_SNAPSHOT"
    [[ -f "$CLAUDE_SNAPSHOT/cli.js" ]] || die "snapshot is missing cli.js"

    # Wrapper at ~/bin/claude pointing at the snapshot (not npm's path).
    mkdir -p "$HOME_BIN"
    cat > "$HOME_BIN/claude" <<'WRAPPER_EOF'
#!/data/data/com.termux/files/usr/bin/bash
# AI Keyboard wrapper for Claude Code v2.1.112. Snapshot at
# ~/claude-code-pinned/ is npm-untouched so the autoupdater can't clobber it.
exec node "$HOME/claude-code-pinned/cli.js" "$@"
WRAPPER_EOF
    chmod +x "$HOME_BIN/claude"
    log_ok "Wrapper at $HOME_BIN/claude"

    # Symlink into $PREFIX/bin (always on PATH; rc-file PATH export isn't
    # reliable across login-shell vs RUN_COMMAND-subshell paths).
    ln -sf "$HOME_BIN/claude" "$TERMUX_PREFIX/bin/claude" \
        || die "failed to symlink $HOME_BIN/claude into $TERMUX_PREFIX/bin"
    log_ok "Symlinked $TERMUX_PREFIX/bin/claude → $HOME_BIN/claude"

    # Best-effort: older versions lack `claude config`; env flag is real defense.
    if claude config set -g autoUpdates false >/dev/null 2>&1; then
        log_ok "claude config: autoUpdates=false"
    else
        log_skip "claude config set autoUpdates=false (unsupported on this version — env flag covers it)"
    fi

    local version_out
    version_out=$(claude --version 2>&1) || die "claude --version failed; wrapper/symlink broken"
    if [[ "$version_out" == *"$CLAUDE_CODE_VERSION"* ]]; then
        log_ok "claude --version: $(printf '%s' "$version_out" | head -1)"
    else
        die "claude --version='$version_out', expected to contain $CLAUDE_CODE_VERSION — investigate wrapper/symlink/snapshot."
    fi
}

install_gemini_cli_if_selected() {
    log_step "5/9 Gemini CLI"
    if ! provider_selected gemini; then
        log_skip "Gemini not selected"
        return 0
    fi
    log_info "npm i -g @google/gemini-cli@${GEMINI_CLI_VERSION}"
    if ! npm i -g "@google/gemini-cli@${GEMINI_CLI_VERSION}"; then
        die "npm install failed for @google/gemini-cli — check connectivity and retry"
    fi
    if ! command -v gemini >/dev/null 2>&1; then
        die "gemini binary not on PATH after install — npm misconfigured?"
    fi
    local v
    v=$(gemini --version 2>&1 | head -1 || true)
    log_ok "gemini installed: ${v:-<no version output>}"
}

run_oauth_flows() {
    log_step "6/9 OAuth flows"
    if provider_selected claude; then
        run_one_oauth claude "$HOME/.claude/.credentials.json" \
            "Claude Code launches interactively. Sign in at claude.ai, then /exit (or Ctrl+C) to return."
    fi
    if provider_selected gemini; then
        run_one_oauth gemini "$HOME/.gemini/oauth_creds.json" \
            "Gemini CLI launches interactively. Sign in with Google, then /quit (or Ctrl+C) to return."
    fi
}

run_one_oauth() {
    local cli="$1" creds_path="$2" preamble="$3"
    local short="${creds_path/#$HOME/\~}"
    printf '\n%sProvider: %s%s\n%s\n\n' "$C_BOLD" "$cli" "$C_RESET" "$preamble"

    local already=0
    if [[ -f "$creds_path" ]]; then
        already=1
        log_info "Existing credentials at $short — auth already complete."
    fi

    printf '%sEnter to launch %s, s+Enter to skip: %s' "$C_BOLD" "$cli" "$C_RESET"
    local choice
    if ! read -r choice; then choice="s"; fi
    case "$choice" in
        s|S|skip|SKIP)
            if (( already == 1 )); then log_ok "Skipped (already authenticated)"
            else log_warn "Skipped — bridge will report '$cli: not authenticated' until you re-run \`$cli\`."
            fi
            return 0
            ;;
    esac

    # Foreground launch — stdio passes through so the user can do OAuth.
    log_info "Launching: $cli"
    "$cli" || log_warn "$cli exited non-zero (Ctrl+C? — verifying auth state anyway)"

    if [[ -f "$creds_path" ]]; then
        log_ok "$cli authenticated (credentials at $short)"
    else
        log_warn "$cli completed but no credentials file at $short — re-run and pick this provider again."
    fi
}

deploy_bridge() {
    log_step "7/9 Deploying bridge to $BRIDGE_DEST"

    if [[ ! -d "$BRIDGE_SOURCE" ]]; then
        die "Bridge source directory not found: $BRIDGE_SOURCE
Pass --bridge-source <dir> pointing at a checked-out bridge/ tree, or push it
to the device first:
    adb push bridge /data/local/tmp/
    bash setup.sh --bridge-source /data/local/tmp/bridge"
    fi
    if [[ ! -f "$BRIDGE_SOURCE/server.js" || ! -f "$BRIDGE_SOURCE/package.json" ]]; then
        die "$BRIDGE_SOURCE doesn't look like a bridge dir (missing server.js or package.json)"
    fi
    log_info "Source: $BRIDGE_SOURCE"

    # Clean-slate copy: source's node_modules may carry arch-mismatched binaries
    # from the dev box, so we drop them and let `npm install` rebuild for Termux.
    rm -rf "$BRIDGE_DEST"
    mkdir -p "$BRIDGE_DEST"
    cp -r "$BRIDGE_SOURCE/." "$BRIDGE_DEST/" || die "failed to copy bridge into $BRIDGE_DEST"
    rm -rf "$BRIDGE_DEST/node_modules"

    log_info "Installing bridge dependencies (npm install --omit=dev)"
    ( cd "$BRIDGE_DEST" && npm install --omit=dev ) \
        || die "npm install failed in $BRIDGE_DEST — check connectivity and retry"

    # Smoke-test load (server.js's start() only runs when require.main===module).
    if ! node -e 'require(process.argv[1])' "$BRIDGE_DEST/server.js" >/dev/null 2>&1; then
        die "node could not load $BRIDGE_DEST/server.js — check the deploy"
    fi
    log_ok "Bridge deployed and loadable"
}

ensure_runsvdir_running() {
    # termux-services' supervisor (runsvdir) is started by
    # $PREFIX/etc/profile.d/start-services.sh on Termux session start. On a
    # fresh install — or on any session that predates the install —
    # runsvdir isn't running yet, and `sv up` / `sv-enable` are no-ops.
    # Start it ourselves with setsid so it survives our shell exit.
    if pgrep -f "runsvdir.*var/service" >/dev/null 2>&1; then
        return 0
    fi
    log_info "Starting runsvdir (termux-services supervisor)"
    setsid "$TERMUX_PREFIX/bin/runsvdir" -P "$TERMUX_PREFIX/var/service" \
        >/dev/null 2>&1 < /dev/null &
    disown 2>/dev/null || true
    sleep 1
    if pgrep -f "runsvdir.*var/service" >/dev/null 2>&1; then
        log_ok "runsvdir started"
    else
        die "runsvdir failed to start. Try fully closing and reopening Termux, then re-run this script."
    fi
}

register_services() {
    log_step "8/9 Registering termux-services unit"

    if ! command -v sv >/dev/null 2>&1; then
        log_info "termux-services not installed — installing"
        pkg install -y termux-services || die "pkg install termux-services failed"
    fi

    mkdir -p "$SERVICE_DIR" "$SERVICE_DIR/log" "$SERVICE_LOG_DIR"

    # Main run script. HOME + PATH set explicitly: runit starts services with
    # a minimal env, and adapters need HOME for ~/.claude/.credentials.json.
    cat > "$SERVICE_DIR/run" <<RUN_EOF
#!/data/data/com.termux/files/usr/bin/sh
exec 2>&1
export HOME=$HOME
export PATH=$TERMUX_PREFIX/bin:$HOME_BIN:\$PATH
export DISABLE_AUTOUPDATER=1
cd "\$HOME/ai-keyboard-bridge"
exec node server.js
RUN_EOF
    chmod +x "$SERVICE_DIR/run"

    # Log run script — svlogd rotates by size (default 1 MB × 10 files); -tt
    # prefixes lines with TAI64N timestamps. Output → $SERVICE_LOG_DIR/current.
    cat > "$SERVICE_DIR/log/run" <<LOG_EOF
#!/data/data/com.termux/files/usr/bin/sh
exec svlogd -tt $SERVICE_LOG_DIR
LOG_EOF
    chmod +x "$SERVICE_DIR/log/run"

    log_ok "Service registered at $SERVICE_DIR"
    log_info "Logs will be written to $SERVICE_LOG_DIR/current"

    ensure_runsvdir_running

    # runsvdir polls for new services every ~5s; wait for the per-service
    # `runsv` supervisor to attach (it creates supervise/ok) before issuing
    # sv commands, otherwise `sv up` fails with 'unable to open supervise/ok'.
    local waited=0
    while (( waited < 15 )); do
        [[ -e "$SERVICE_DIR/supervise/ok" ]] && break
        sleep 1
        waited=$((waited + 1))
    done
    if [[ ! -e "$SERVICE_DIR/supervise/ok" ]]; then
        log_warn "runsv did not attach to $SERVICE_DIR within ${waited}s — sv commands may fail"
    fi

    # `sv-enable` is idempotent — symlinks the service into the active
    # service directory iff not already linked.
    if ! sv-enable ai-keyboard-bridge >/dev/null 2>&1; then
        log_warn "sv-enable returned non-zero — service may already be enabled (continuing)"
    else
        log_ok "Service enabled (sv-enable ai-keyboard-bridge)"
    fi

    # Termux:Boot autostart — opt-in, only if companion app is installed
    # (it creates ~/.termux/boot/ on first launch). termux-wake-lock keeps
    # the service responsive when the screen is off.
    if [[ -d "$BOOT_HOOK_DIR" ]]; then
        cat > "$BOOT_HOOK_DIR/start-ai-keyboard-bridge" <<'BOOT_EOF'
#!/data/data/com.termux/files/usr/bin/sh
termux-wake-lock
sv up ai-keyboard-bridge
BOOT_EOF
        chmod +x "$BOOT_HOOK_DIR/start-ai-keyboard-bridge"
        log_ok "Termux:Boot autostart hook at $BOOT_HOOK_DIR/start-ai-keyboard-bridge"
    else
        log_info "Termux:Boot not detected — install from F-Droid for boot-time bridge autostart."
    fi
}

start_bridge() {
    log_step "9/9 Starting bridge and probing /health"

    # `sv restart`, not `sv up`: redeploys must bounce the running node
    # process so the new on-disk server.js actually takes effect. On first
    # run runit treats restart-of-down as start.
    if ! sv restart ai-keyboard-bridge >/dev/null 2>&1; then
        log_warn "sv restart returned non-zero — checking status anyway"
    fi
    sleep 2  # let runit spawn the supervised process before /health probe

    log_info "Probing http://127.0.0.1:8787/health (up to 10s)..."
    local body
    if body=$(probe_health 10); then
        log_ok "Bridge is responding"
        printf '%s%s%s\n' "$C_DIM" "$body" "$C_RESET"
        # Sanity-check ok=true. The body is JSON; do a lightweight contains check.
        case "$body" in
            *'"ok":true'*) ;;
            *) log_warn "Body received but \"ok\":true not present — check provider auth state above" ;;
        esac
    else
        log_error "Bridge /health did not respond within 10s"
        local current_log="$SERVICE_LOG_DIR/current"
        if [[ -f "$current_log" ]]; then
            printf '\n%sLast 30 log lines from %s:%s\n' "$C_DIM" "$current_log" "$C_RESET"
            tail -n 30 "$current_log" || true
        else
            log_info "No log file yet at $current_log — service may not have started. Try: sv status ai-keyboard-bridge"
        fi
        die "Bridge failed to start. See logs above; remediate and re-run this script."
    fi
}

print_success() {
    cat <<EOF

${C_BOLD}${C_GREEN}✓ Setup complete.${C_RESET}
  Bridge:   http://127.0.0.1:8787
  Restart:  sv up ai-keyboard-bridge
  Stop:     sv down ai-keyboard-bridge
  Status:   sv status ai-keyboard-bridge
  Logs:     tail -f $SERVICE_LOG_DIR/current
  Re-run:   bash $0   ${C_DIM}# idempotent${C_RESET}

Pick "Termux bridge" as the active backend in AI Keyboard's settings (Phase 6+).

EOF
}

main() {
    parse_args "$@"
    require_termux
    print_banner
    confirm_proceed_or_exit
    set_allow_external_apps
    install_termux_packages
    select_providers
    install_claude_code_if_selected
    install_gemini_cli_if_selected
    run_oauth_flows
    deploy_bridge
    register_services
    start_bridge
    print_success
}

main "$@"
