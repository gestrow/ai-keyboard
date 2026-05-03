# Phase 6 Prompt — `TermuxBridgeBackend` + backend-strategy switcher

> **How to use this prompt:** open a fresh Claude Code session in `/Users/kacper/SDK/apk-dev/ai-keyboard/`. Tell it: *"Read `ARCHITECTURE.md`, `PHASE_REVIEW.md`, and the summaries for Phases 1, 2, 2.5, 3a, 3b, 4, 5a, 5b. Then execute the prompt in `PHASE_6_PROMPT.md` exactly. Stop when Phase 6's Definition of Done holds. Do not begin Phase 7."*

---

You are working in `/Users/kacper/SDK/apk-dev/ai-keyboard/`. Phases 1–5b are complete. Read all planning + summary docs first.

This phase finally makes the keyboard's "Rewrite with AI" button speak to the local Termux bridge. After Phase 6, "free Rewrite via your Claude Pro subscription" goes from architecture promise to working feature.

Three deliverables:

1. **`TermuxBridgeBackend`** — sibling to `RemoteApiBackend`, implements `AiClient` against `127.0.0.1:8787/chat`. Parses the bridge's **normalized SSE envelope** (`data: {type:"delta"|"done"|"error", ...}`) — distinct from Anthropic's named-event SSE that `RemoteApiBackend.streamAnthropic` consumes.
2. **Backend-strategy selection** — `SecureStorage` learns `selectedBackendStrategy` (REMOTE_API / TERMUX_BRIDGE) and `selectedTermuxProvider` (one of the bridge's `/providers` ids). `CommandRowController` consults both before constructing a backend.
3. **UI: "Active for Rewrite" radio on `BackendsScreen`** + Termux-CLI sub-selector on the bridge status screen. One row across all backends is the active one; only configured rows can be selected.

## Critical context from prior phases

- **`AiClient` interface** ([app/app/src/main/java/com/aikeyboard/app/ai/client/AiClient.kt](app/app/src/main/java/com/aikeyboard/app/ai/client/AiClient.kt)) is intentionally not sealed — Phase 3a's `RemoteApiBackend` lives in `client/remote/`, Phase 6's `TermuxBridgeBackend` lives in `client/termux/`. The `BackendStrategy` enum already has `REMOTE_API`, `LOCAL_LAN` (Phase 10), `TERMUX_BRIDGE` values declared.
- **Two `Provider` enums coexist on purpose** — `client.Provider` (ANTHROPIC / GOOGLE_GEMINI, keyed by `storageKey`) is the API-key-storage axis; `TermuxOrchestrator.Provider` (CLAUDE / GEMINI, keyed by `cliName`) is the bridge-CLI axis. Phase 5b's summary spells out why: API-storage keys differ from CLI names. **Do not collapse them.** Phase 6's `TermuxBridgeBackend` accepts the CLI provider as a **plain `String`** (matching the bridge's `/providers` `id` field), so adding Codex in Phase 11 needs no refactor here.
- **Bridge `/chat` request shape** (verified from [bridge/server.js:38-52](bridge/server.js#L38-L52) + [bridge/adapters/claude.js](bridge/adapters/claude.js)):
  ```json
  POST http://127.0.0.1:8787/chat
  Content-Type: application/json
  { "provider": "claude" | "gemini",
    "messages": [{"role":"user","content":"..."}],
    "system": "..." }
  ```
- **Bridge SSE envelope** (different from Anthropic/Gemini SSE — flat JSON-per-`data:` line, no `event:` lines):
  ```
  data: {"type":"delta","text":"hello"}

  data: {"type":"delta","text":" world"}

  data: {"type":"done"}
  ```
  Errors:
  ```
  data: {"type":"error","code":"AUTH_FAILURE","message":"claude not authenticated"}
  ```
  **Full bridge error code set** (verified in [bridge/adapters/claude.js](bridge/adapters/claude.js) `classifyCode()` + [bridge/adapters/gemini.js](bridge/adapters/gemini.js) `classifyCode()` + [bridge/server.js:73-77](bridge/server.js#L73-L77)):
  | Bridge code | `ErrorType` |
  | --- | --- |
  | `AUTH_FAILURE` | `AUTH_FAILURE` |
  | `CLI_NOT_FOUND` | `NETWORK_FAILURE` |
  | `RATE_LIMITED` | `RATE_LIMITED` |
  | `TIMEOUT` | `TIMEOUT` |
  | `CLI_CRASHED` | `NETWORK_FAILURE` (CLI died — closest to "service unavailable") |
  | (any other / future) | `UNKNOWN` |
  Mapping the full set is required so user-facing errors like rate-limiting and timeouts route to the correct preview-strip messaging instead of falling through to a generic "unexpected error." Per [server.js:73-77](bridge/server.js#L73-L77) the server itself only emits `AUTH_FAILURE` and `CLI_NOT_FOUND` for availability checks; everything else originates from the adapters' `classifyCode`.
- **`networkSecurityConfig.xml`** already permits cleartext to `127.0.0.1` from Phase 4. No manifest changes needed for Phase 6.
- **`TermuxOrchestrator.fetchProviders()`** ([app/app/src/main/java/com/aikeyboard/app/ai/termux/TermuxOrchestrator.kt:210-215](app/app/src/main/java/com/aikeyboard/app/ai/termux/TermuxOrchestrator.kt#L210)) already returns the `/providers` array — Phase 6's UI consumes this for the CLI sub-selector.
- **`SecureStorage.getSelectedProvider()`** ([app/app/src/main/java/com/aikeyboard/app/ai/storage/SecureStorage.kt:124-135](app/app/src/main/java/com/aikeyboard/app/ai/storage/SecureStorage.kt#L124)) already exists for picking which Remote API provider is active. Phase 6 keeps it; the new strategy field gates whether it's consulted at all.
- **`CommandRowController.onRewriteTap()`** ([app/app/src/main/java/com/aikeyboard/app/ai/commandrow/CommandRowController.kt:75-135](app/app/src/main/java/com/aikeyboard/app/ai/commandrow/CommandRowController.kt#L75)) is the single call site that constructs a backend. Replace its hardcoded `RemoteApiBackend` construction with a backend resolver.
- **No keyboard-surface UI invariants apply** — Phase 6 only edits `BackendsScreen` and the Termux status screen, both Compose Activities. Material3 normally.
- **Bridge code is unchanged** — Phase 4's `/chat`, `/health`, `/providers` already implement everything Phase 6 consumes.

## Tasks

### 1. Branch from Phase 5b

```
git checkout phase/05b-orchestrator-and-wizard
git pull --ff-only
git checkout -b phase/06-termux-backend
```

### 2. Extend `SecureData` and `SecureStorage`

`app/app/src/main/java/com/aikeyboard/app/ai/storage/SecureData.kt` — add two fields:

```kotlin
@Serializable
internal data class SecureData(
    val personas: List<Persona> = emptyList(),
    val activePersonaId: String? = null,
    val apiKeys: Map<String, String> = emptyMap(),
    val selectedProviderKey: String? = null,
    // Phase 6: which strategy is "active for Rewrite". null defaults to REMOTE_API
    // (back-compat for users upgrading from Phase 3b/5b).
    val selectedBackendStrategy: String? = null,
    // Phase 6: when strategy == TERMUX_BRIDGE, which bridge /providers id is active.
    // String to stay loose-coupled from any enum (Codex in Phase 11 needs no schema change).
    val selectedTermuxProvider: String? = null,
)
```

`SecureStorage` adds:

```kotlin
@Synchronized fun getSelectedBackendStrategy(): BackendStrategy =
    load().selectedBackendStrategy?.let { runCatching { BackendStrategy.valueOf(it) }.getOrNull() }
        ?: BackendStrategy.REMOTE_API

@Synchronized fun setSelectedBackendStrategy(strategy: BackendStrategy) {
    save(load().copy(selectedBackendStrategy = strategy.name))
}

@Synchronized fun getSelectedTermuxProvider(): String? =
    load().selectedTermuxProvider?.takeIf { it.isNotEmpty() }

@Synchronized fun setSelectedTermuxProvider(cliName: String?) {
    save(load().copy(selectedTermuxProvider = cliName?.takeIf { it.isNotEmpty() }))
}
```

Migration is automatic — `kotlinx.serialization` defaults make absent fields null, and existing Phase 3b/5b users get `REMOTE_API` from `getSelectedBackendStrategy()`'s null fallback.

### 3. `TermuxBridgeBackend` Kotlin class

New file: `app/app/src/main/java/com/aikeyboard/app/ai/client/termux/TermuxBridgeBackend.kt`

```kotlin
package com.aikeyboard.app.ai.client.termux

class TermuxBridgeBackend(
    private val cliProvider: String,        // "claude" | "gemini"  — matches /providers id
    private val baseUrl: String = TermuxOrchestrator.BRIDGE_BASE,
) : AiClient {

    override fun isAvailable(): Boolean = cliProvider.isNotEmpty()
    // Real reachability is checked at rewrite() time and surfaced via AiStreamEvent.Error.
    // Synchronous availability is the contract that RemoteApiBackend already established.

    override fun rewrite(
        input: String,
        systemPrompt: String,
        fewShots: List<FewShot>,
    ): Flow<AiStreamEvent> = flow {
        val body = buildChatBody(cliProvider, input, systemPrompt, fewShots)
        try {
            httpClient.preparePost("$baseUrl/chat") {
                contentType(ContentType.Application.Json)
                accept(ContentType.Text.EventStream)
                setBody(body)
            }.execute { response ->
                if (response.status.value !in 200..299) {
                    emit(mapHttpError(response.status))
                    return@execute
                }
                streamBridgeSse(response.bodyAsChannel())
            }
        } catch (te: TimeoutCancellationException) {
            // MUST come before the broader CancellationException catch — TimeoutCancellationException
            // IS-A CancellationException, so the inverse order silently drops timeouts (the broader
            // catch fires first, re-throws, and CommandRowController's outer guard against
            // CancellationException turns it into a silent hang).
            emit(AiStreamEvent.Error(ErrorType.TIMEOUT, "Bridge request timed out"))
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // Never include t.message verbatim in the user-facing string when t came from JSON
            // parsing — kotlinx.serialization's JsonDecodingException can include excerpts of the
            // raw `data:` line (model output) in its message. parseEvent() must wrap its own
            // parse in runCatching → BridgeEvent.Unknown to keep that content out of this path.
            emit(
                AiStreamEvent.Error(
                    ErrorType.NETWORK_FAILURE,
                    "Bridge unreachable: ${t.javaClass.simpleName}",
                ),
            )
        }
    }

    private suspend fun FlowCollector<AiStreamEvent>.streamBridgeSse(channel: ByteReadChannel) {
        // Bridge SSE: only `data:` lines, each one a complete JSON object with `type` field.
        // No `event:` lines (unlike Anthropic). Blank lines separate events.
        while (true) {
            val raw = channel.readUTF8Line() ?: break
            val line = raw.trimEnd('\r')
            if (line.isEmpty() || !line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty()) continue

            when (val parsed = parseEvent(data)) {
                is BridgeEvent.Delta -> if (parsed.text.isNotEmpty()) emit(AiStreamEvent.Delta(parsed.text))
                BridgeEvent.Done -> { emit(AiStreamEvent.Done); return }
                is BridgeEvent.Error -> { emit(parsed.toAiEvent()); return }
                BridgeEvent.Unknown -> Unit  // forward-compat with future event types
            }
        }
        // Stream ended without `done` — treat as done so the user sees what was streamed.
        emit(AiStreamEvent.Done)
    }
    // ... companion object with httpClient, parseEvent, buildChatBody, mapHttpError, BridgeEvent
}
```

Implementation notes:
- **HTTP client**: declare a separate `HttpClient` singleton inside `TermuxBridgeBackend.Companion` (Ktor + OkHttp engine), with **long socket timeout** (60s) for SSE — same shape as `RemoteApiBackend.httpClient`. **Do not reuse** `TermuxOrchestrator.httpClient` (its 2s socket timeout is sized for `/health` polling and would kill `/chat` streams instantly).
- **`buildChatBody`**: maps `(input, systemPrompt, fewShots)` → JSON. Few-shots become alternating `{role:user|assistant, content:...}` objects, then the user input as the final message. Optional `system` field. Same shape Phase 3b's `buildAnthropicRequest` uses, except no model field (the bridge picks the CLI's default model). **Known limitation to surface in the summary, not a defect:** the bridge's `claude.js` and `gemini.js` adapters call `lastUserContent(messages)` which returns only the *most recent* user message — few-shots in the array are silently discarded by the bridge. The `system` prompt does propagate. This is a Phase 4 architectural choice (subprocess CLIs don't accept conversation arrays the same way the API does). Phase 6's `buildChatBody` still encodes few-shots faithfully so future bridge improvements can honor them without breaking the wire format. **Behavioral divergence from `RemoteApiBackend`** — flag it in the summary.
- **`mapHttpError`**: 400 (missing provider / unknown provider / missing messages) → `ErrorType.UNKNOWN`; 5xx → `NETWORK_FAILURE`; 501 (not happening from `/chat`, but defensive) → `UNKNOWN`. Don't try to extract a body for a `/chat` error — bridge returns plain `{error: "..."}` on validation failures.
- **`parseEvent`**: **wrap the JSON parse in `runCatching { ... }.getOrElse { return BridgeEvent.Unknown }`** so a malformed `data:` line never propagates an exception whose `message` could contain a fragment of the raw line (model output / auth message). After successful parse, switch on `type`:
  - `"delta"` → `BridgeEvent.Delta(text)`
  - `"done"` → `BridgeEvent.Done`
  - `"error"` → `BridgeEvent.Error(code, message)` where `code` maps per the table in *Critical context* above (`AUTH_FAILURE`, `CLI_NOT_FOUND`, `RATE_LIMITED`, `TIMEOUT`, `CLI_CRASHED`, otherwise `UNKNOWN`)
  - any other type → `BridgeEvent.Unknown` (forward-compat; don't error)
- **Privacy**: do not log `text` or `message` content at any log level. The bridge SSE carries model output and CLI auth failure messages — both potentially sensitive. Likewise the catch-all `Throwable` handler must use `t.javaClass.simpleName`, NOT `t.message` (see comment in the snippet above).
- **Cancellation**: the bridge's HTTP server closes the request on client disconnect, which kills the CLI subprocess (`abortController.abort()` in [bridge/server.js:64-67](bridge/server.js#L64-L67)). `Flow` cancellation propagates correctly through Ktor's `preparePost.execute`; nothing extra needed.

### 4. Backend resolver

New file: `app/app/src/main/java/com/aikeyboard/app/ai/client/BackendResolver.kt`

```kotlin
package com.aikeyboard.app.ai.client

object BackendResolver {
    /**
     * Returns the `AiClient` the user has configured for Rewrite, or null if
     * nothing is currently usable (no API keys configured, or Termux selected
     * but no provider picked). Caller toasts a friendly "configure a backend"
     * message on null.
     */
    fun resolve(
        storage: SecureStorage,
    ): AiClient? = when (storage.getSelectedBackendStrategy()) {
        BackendStrategy.REMOTE_API -> {
            val provider = storage.getSelectedProvider() ?: return null
            RemoteApiBackend(provider, storage)
        }
        BackendStrategy.TERMUX_BRIDGE -> {
            val cli = storage.getSelectedTermuxProvider() ?: return null
            TermuxBridgeBackend(cli)
        }
        BackendStrategy.LOCAL_LAN -> null  // Phase 10
    }
}
```

This object stays trivial; it's a single-call-site dispatch, not an abstraction layer. Unit tests for it are about exercising each branch with mocked storage.

### 5. Wire `CommandRowController`

Replace ([app/app/src/main/java/com/aikeyboard/app/ai/commandrow/CommandRowController.kt:35](app/app/src/main/java/com/aikeyboard/app/ai/commandrow/CommandRowController.kt#L35) + [98-114](app/app/src/main/java/com/aikeyboard/app/ai/commandrow/CommandRowController.kt#L98)):

```kotlin
// Old:
private val backendFactory: (Provider) -> AiClient = { p -> RemoteApiBackend(p, storage) },
// ...
val provider = storage.getSelectedProvider()
if (provider == null) { toast(R.string.ai_rewrite_no_backend); return }
// ...
val backend = backendFactory(provider)
```

with:

```kotlin
// New:
private val backendResolver: () -> AiClient? = { BackendResolver.resolve(storage) },
// ...
val backend = backendResolver()
if (backend == null) { toast(R.string.ai_rewrite_no_backend); return }
// ...
// (drop the `provider`/`backendFactory(provider)` lines)
```

Constructor parameter remains `@JvmOverloads` so existing tests keep working — they just pass a different lambda. Phase 3b's tests that injected `backendFactory` will need a one-line update to inject `backendResolver` instead.

### 6. UI — "Active for Rewrite" radio on `BackendsScreen`

`BackendsScreen` ([app/app/src/main/java/com/aikeyboard/app/ai/ui/BackendsScreen.kt](app/app/src/main/java/com/aikeyboard/app/ai/ui/BackendsScreen.kt)) gets a `RadioButton` on each row's `leadingContent`. Logic:

- For each remote provider row (Anthropic, Gemini): radio is **selectable** iff `provider in configured`. Selecting it calls `storage.setSelectedBackendStrategy(REMOTE_API)` + `storage.setSelectedProvider(provider)`.
- For the Termux Bridge row: radio is **selectable** iff `termuxStatus == BRIDGE_RUNNING && termuxAuthCount > 0` (strict equality — `null` status disables). Selecting it calls `storage.setSelectedBackendStrategy(TERMUX_BRIDGE)`. **Does not** set the CLI provider — user picks that on the status screen. If `selectedTermuxProvider` is null after selection, the status screen auto-picks the first authenticated one and persists.
- The currently-active row's radio is filled. Disabled rows show greyed-out radios with a small helper line ("Configure to enable").
- A small "Active for Rewrite" header above the list makes the radio semantics obvious. Don't use the term "default" — the user is making an explicit choice.

**State + ON_RESUME refresh (important — fixes a stale-status bug otherwise):**
The current `BackendsScreen` ([line 60-65](app/app/src/main/java/com/aikeyboard/app/ai/ui/BackendsScreen.kt#L60)) fetches `termuxStatus` once via `LaunchedEffect(Unit)`, and the existing `ON_RESUME` observer ([line 51-58](app/app/src/main/java/com/aikeyboard/app/ai/ui/BackendsScreen.kt#L51)) only refreshes `configured`. After Phase 6, the user can leave this screen, kill the bridge from the status screen, and return — at which point `termuxStatus` would still be `BRIDGE_RUNNING` until next composition. Fix:
1. Add `var resumeCount by remember { mutableIntStateOf(0) }`.
2. Bump it in the existing lifecycle observer's `ON_RESUME` branch alongside the `configured` refresh.
3. Re-key the existing async `LaunchedEffect` from `LaunchedEffect(Unit)` to `LaunchedEffect(resumeCount)` so `detectStatus()` and (conditionally) `fetchProviders()` re-run on every resume. Also re-derive the active-radio selection from storage at this point.

Derive the active selection from `storage.getSelectedBackendStrategy()` + (`storage.getSelectedProvider()` for REMOTE_API) or (`storage.getSelectedTermuxProvider()` for TERMUX_BRIDGE) on each composition.

### 7. UI — Termux-CLI sub-selector on `TermuxBridgeStatusScreen`

**Two files to update — both must be touched:**
- `app/app/src/main/java/com/aikeyboard/app/ai/ui/termux/TermuxBridgeStatusScreen.kt` — add a `storage: SecureStorage` parameter to its composable signature and add the sub-selector UI.
- `app/app/src/main/java/com/aikeyboard/app/ai/ui/termux/TermuxBridgeRoute.kt` — the call site for `TermuxBridgeStatusScreen`. Obtain a `SecureStorage` instance (the same `LocalContext` + `remember { SecureStorage.getInstance(context) }` pattern used in `BackendsScreen`) and pass it through.

Add **above** the existing per-provider auth-status list:

- Header: "Active CLI for Rewrite"
- For each provider in `fetchProviders()` where `available == true`: a `RadioButton` row with the provider's display name (capitalize the `id`). Selecting calls `storage.setSelectedTermuxProvider(id)`.
- If no provider is authenticated: "Re-authenticate a provider below to enable Termux for Rewrite."
- The radio reflects `storage.getSelectedTermuxProvider()`. **Auto-pick rule:** if `getSelectedBackendStrategy() == TERMUX_BRIDGE && getSelectedTermuxProvider() == null && fetchProviders()` returned at least one available provider, persist the first available one immediately (inside the same `LaunchedEffect` that loaded the providers). Idempotent — if the user returns later with a stored selection, this branch doesn't fire.

### 8. String resources

Add to `app/app/src/main/res/values/strings.xml` (under the AI settings block):

```xml
<string name="ai_settings_backends_active_header">Active for Rewrite</string>
<string name="ai_settings_backends_radio_disabled">Configure to enable</string>
<string name="ai_settings_termux_active_cli_header">Active CLI for Rewrite</string>
<string name="ai_settings_termux_active_cli_none">Re-authenticate a provider below to enable Termux for Rewrite.</string>
```

Keep all strings in the existing AI namespace; do not touch HeliBoard strings.

### 9. Tests

**Pattern to follow:** match the existing `RemoteApiBackendStreamingTest` style — extract the parser/builder helpers as `internal` companion functions and unit-test them directly in pure JVM. **Do NOT** introduce `io.ktor:ktor-client-mock` (it's not in `app/app/build.gradle.kts` and the "no new dependencies" constraint applies).

Add `app/app/src/test/java/com/aikeyboard/app/ai/client/termux/TermuxBridgeBackendTest.kt`:
- `buildChatBody(...)` with system prompt + few-shots → assert resulting JSON has `provider` field, `messages` array (alternating user/assistant for the few-shots, final user message = input), and a `system` field. Cover the no-system-prompt and no-few-shots branches.
- `parseEvent("""{"type":"delta","text":"hello"}""")` → `BridgeEvent.Delta("hello")`.
- `parseEvent` for each error code: `AUTH_FAILURE` → `ErrorType.AUTH_FAILURE`, `CLI_NOT_FOUND` → `ErrorType.NETWORK_FAILURE`, `RATE_LIMITED` → `ErrorType.RATE_LIMITED`, `TIMEOUT` → `ErrorType.TIMEOUT`, `CLI_CRASHED` → `ErrorType.NETWORK_FAILURE`, `WAT_IS_THIS` → `ErrorType.UNKNOWN`.
- `parseEvent("""{"type":"unknown_future_event"}""")` → `BridgeEvent.Unknown` (forward-compat).
- `parseEvent("not json")` → `BridgeEvent.Unknown` (privacy: malformed input must not propagate as exception with content in the message).
- For the streaming loop, expose an internal `streamBridgeSse(channel)` overload that takes a `ByteReadChannel` and returns a list of emitted events. Build the channel from a hand-rolled byte array of `data: ...\n\n` lines (use `ByteReadChannel.invoke(ByteArray)`); run inside `kotlinx-coroutines-test`'s `runTest`. Assert the full event sequence for: happy path (deltas → done); error mid-stream (delta → error → no further events); stream terminated without explicit `done` (synthetic `Done` emitted).

Add `app/app/src/test/java/com/aikeyboard/app/ai/client/BackendResolverTest.kt`:
- REMOTE_API + selected Anthropic + key configured → `RemoteApiBackend` instance.
- REMOTE_API + no provider selected, no keys configured → null.
- TERMUX_BRIDGE + `selectedTermuxProvider = "claude"` → `TermuxBridgeBackend` instance.
- TERMUX_BRIDGE + `selectedTermuxProvider = null` → null.
- LOCAL_LAN → null (Phase 10).

Storage stubbing: `SecureStorage` is an Android-context-bound class but its `getSelectedBackendStrategy` / `getSelectedProvider` / `getSelectedTermuxProvider` methods are pure reads from an in-memory cache after the first load. Use Mockito (already a dependency per Phase 3a) to mock `SecureStorage` for `BackendResolverTest`. The `TermuxBridgeBackendTest` parser/builder tests need no Android plumbing at all.

### 10. Smoke test (Pixel 6 Pro)

Pre-test: device has Phase 5b's IME installed and the Termux bridge running (from Phase 5a/5b smoke test). Both Claude + Gemini authenticated.

#### Backend-switch scenario

1. Install Phase 6 fdroid debug build over Phase 5b: `adb install -r app/app/build/outputs/apk/fdroid/debug/...`
2. Open AI Settings → Backends. Verify:
   - "Active for Rewrite" header above the list.
   - Anthropic row: radio selectable (key configured from Phase 3b), currently selected (back-compat default).
   - Gemini row: radio selectable.
   - Termux Bridge row: radio selectable (bridge running, ≥1 provider authenticated).
3. Tap the Termux Bridge radio. Tap into the row → status screen. Verify:
   - "Active CLI for Rewrite" header with radio per authenticated provider.
   - First authenticated provider auto-selected.
   - Tap Claude radio → persists.
4. Return to a notes app, type "draft an email about being late", select Concise Editor persona, tap Rewrite. Verify:
   - Tokens stream into the preview strip.
   - `adb logcat -d | grep -i "anthropic\|api.anthropic"` shows zero hits during this rewrite (proves we're hitting the bridge, not the remote API).
   - `tcpdump`-style verification optional but encouraged: `adb shell ss -tn | grep 8787` shows an active connection during the stream.
5. Switch back to Anthropic via the Backends radio. Repeat Rewrite. Verify SSE streams from `api.anthropic.com` (network log shows TLS to that host).

#### Bridge-down scenario

1. With Termux selected as active, in Termux: `sv down ai-keyboard-bridge` (or use the IME's "Stop bridge" button on the status screen).
2. Tap Rewrite in a notes app. Verify:
   - Preview strip shows a clear "Bridge unreachable" error message (red text, per Phase 3b's pattern).
   - Keyboard remains usable; no crash.
3. Restart the bridge (`sv up` or "Start the bridge" button). Tap Rewrite again — succeeds.

#### Auth-failure scenario

1. With Termux + Claude selected, in Termux: `mv ~/.claude/.credentials.json ~/.claude/.credentials.json.bak` (simulate revoked OAuth).
2. Tap Rewrite. Verify:
   - Preview strip shows an `AUTH_FAILURE`-flavored error ("Claude not authenticated" or similar).
   - On the status screen, "Re-authenticate Claude" button is offered.
3. Restore creds: `mv ~/.claude/.credentials.json.bak ~/.claude/.credentials.json`. Rewrite succeeds.

#### Privacy invariants

- `adb logcat -d | grep -iE "delta|chat|content|claude|gemini" | grep -v "TermuxOrchestrator\|BackendsScreen"` — no streamed text content visible.
- `adb logcat -d | grep -iE "FATAL|AndroidRuntime"` — empty.

#### Build invariants

- `./gradlew assembleFdroidDebug assemblePlayDebug assembleFdroidRelease assemblePlayRelease` clean.
- `./gradlew lintFdroidDebug lintPlayDebug` clean; `git diff app/app/lint-baseline.xml` empty.
- `./gradlew test` passes; new tests included.
- `apkanalyzer dex packages` on play release APK shows `client.termux.TermuxBridgeBackend` and `client.BackendResolver`.
- No new entries in `networkSecurityConfig.xml` (Phase 4's localhost cleartext is sufficient).
- No new `<uses-permission>` declarations.

### 11. Incidental fix: same catch-order bug exists in `RemoteApiBackend`

While you're working on the catch order in `TermuxBridgeBackend`, fix the parallel bug in [app/app/src/main/java/com/aikeyboard/app/ai/client/remote/RemoteApiBackend.kt:95-98](app/app/src/main/java/com/aikeyboard/app/ai/client/remote/RemoteApiBackend.kt#L95). Currently:

```kotlin
} catch (ce: CancellationException) {
    throw ce
} catch (te: TimeoutCancellationException) {  // dead code
    emit(AiStreamEvent.Error(ErrorType.TIMEOUT, "Request timed out"))
```

Reorder so `TimeoutCancellationException` is caught **before** `CancellationException`. Same root cause as `TermuxBridgeBackend`: timeouts currently surface as silent re-thrown `CancellationException` swallowed by `CommandRowController:130`. Mention the fix explicitly in the summary's "Incidental fixes" section so reviewers see what changed beyond the stated scope.

### 12. Commit

Single commit on `phase/06-termux-backend`:

```
Phase 6: TermuxBridgeBackend + backend-strategy switcher

- TermuxBridgeBackend (Kotlin): AiClient over 127.0.0.1:8787/chat,
  parses bridge's normalized SSE envelope (data: {type:"delta"|...})
  with separate 60s-socket-timeout HttpClient; full bridge error code
  set mapped to ErrorType (AUTH_FAILURE, CLI_NOT_FOUND, RATE_LIMITED,
  TIMEOUT, CLI_CRASHED, UNKNOWN)
- SecureStorage extended with selectedBackendStrategy + selectedTermuxProvider;
  schema is back-compat (null fields default to REMOTE_API)
- BackendResolver.resolve(storage) - single dispatch site for the rewrite path
- CommandRowController switched from hardcoded RemoteApiBackend factory to
  the resolver
- BackendsScreen: "Active for Rewrite" radios per row; selectable iff that
  backend is configured; ON_RESUME re-runs detectStatus() so the radio
  doesn't go stale
- TermuxBridgeStatusScreen: "Active CLI for Rewrite" sub-selector among
  authenticated /providers entries; auto-picks first available on first land
- Incidental fix: TimeoutCancellationException catch order in
  RemoteApiBackend.kt:95-98 (same bug — was silently dropping timeouts)
- Tests: bridge-SSE parse + chat-body shape + resolver dispatch matrix
  (using project's existing static-helper test pattern, no MockEngine)
- Both flavors build clean; smoke-tested end-to-end on Pixel 6 Pro
```

Do not push, do not merge.

## Constraints

- **Do not** modify the bridge code. Phase 4's `/chat`, `/health`, `/providers` are sufficient.
- **Do not** modify `RemoteApiBackend` or its SSE parsing (it handles two distinct named-event shapes; Phase 6's bridge SSE is a third, simpler shape).
- **Do not** collapse `client.Provider` and `TermuxOrchestrator.Provider` — Phase 5b's summary spells out why they're intentionally separate.
- **Do not** add new dependencies. Ktor + kotlinx.serialization + Compose already present.
- **Do not** edit `ARCHITECTURE.md` or `PHASE_REVIEW.md`. Surface findings in the summary.
- **Do not** add a "Test connection" button to the Termux row — Phase 5b's `/health` poll already provides liveness; the rewrite-time error path provides streaming-connectivity feedback.
- **Do not** implement `LOCAL_LAN` strategy beyond the `null` branch in `BackendResolver` — Phase 10 owns it.
- **Do not** log `text` content from bridge SSE deltas, model output, persona prompt content, or auth failure messages at any log level beyond DEBUG-gated diagnostics.
- **Do not** include `Throwable.message` in any user-facing string emitted from `rewrite()`'s catch-all — JSON parse exceptions can carry raw `data:` content. Use `t.javaClass.simpleName` only.
- **Do not** introduce `io.ktor:ktor-client-mock` or any other test dependency. Match the existing project pattern (extract internal helpers, test directly).
- **Do not** pass `SecureStorage` into `TermuxBridgeBackend`'s constructor — the resolver hands the CLI name in already; storage isn't read inside the backend.
- **Do** verify the network-flow on-device: which host is being contacted during a rewrite is the easiest way to confirm backend switching actually works.
- **Do** keep `TermuxBridgeBackend.isAvailable()` synchronous and return `true` when the CLI provider name is non-empty — matches `RemoteApiBackend.isAvailable()`'s "do you have what's needed to *try*" semantics. Reachability errors surface in the Flow.

## Phase 6 summary template

When finished, write `PHASE_6_SUMMARY.md` at repo root, under 70 lines:

```markdown
## Phase 6 Review (carried into Phase 7a prompt)

- **TermuxBridgeBackend surface:** <constructor args, threading, SSE parser shape, error-code mapping table>
- **Storage schema:** <new SecureData fields + SecureStorage methods + back-compat behavior>
- **Backend resolver:** <how the dispatch matrix is wired, where it's called from>
- **UI surface:** <radio placement, selectability rules, state derivation cadence>
- **CommandRowController change:** <one-line summary of the factory→resolver switch>
- **Built:** <terse outcome>
- **Smoke test:** <results: backend-switch / bridge-down / auth-failure / network-flow verification>
- **Deviations from Phase 6 prompt:** <list, or "none">
- **Carried issues:** <list, or "none">
- **Touchpoints for Phase 7a:**
  - `app/app/src/fdroid/java/com/aikeyboard/app/a11y/` — Phase 7a creates ScreenReaderService here (fdroid only)
  - `app/app/src/fdroid/AndroidManifest.xml` — Phase 7a adds the `<service>` declaration here, never in src/main
  - `app/app/src/fdroid/res/xml/accessibility_service_config.xml` — Phase 7a creates this
  - `BuildConfig.ENABLE_A11Y` — Phase 7a's first real consumer of this guard from Phase 1
- **Open questions for human reviewer:** <items>
```

## Definition of done

- `TermuxBridgeBackend` exists at `app/app/src/main/java/com/aikeyboard/app/ai/client/termux/TermuxBridgeBackend.kt`; parses bridge SSE; maps the three documented error codes to `ErrorType`s correctly
- `SecureStorage.getSelectedBackendStrategy()` defaults to `REMOTE_API` when the new field is null (back-compat); `setSelectedBackendStrategy()`, `getSelectedTermuxProvider()`, `setSelectedTermuxProvider()` all functional
- `BackendResolver.resolve(storage)` returns the right `AiClient` for each `(strategy, provider)` combination; null when nothing is usable
- `CommandRowController.onRewriteTap()` calls `BackendResolver.resolve(...)` (or its injected factory) instead of constructing `RemoteApiBackend` directly
- `BackendsScreen` shows "Active for Rewrite" radios; only configured rows are selectable; selection persists across `onResume`
- `TermuxBridgeStatusScreen` shows "Active CLI for Rewrite" sub-radio with auto-pick on first land
- Both flavor APKs build clean (debug + release); lint clean; lint-baseline diff empty
- New unit tests pass; existing unit tests pass after the `backendFactory → backendResolver` rename
- Smoke test on Pixel 6 Pro: backend-switch scenario verified by network-flow inspection (no `api.anthropic.com` hits when Termux is active); bridge-down and auth-failure scenarios surface clear errors; logcat clean
- `PHASE_6_SUMMARY.md` exists at repo root, under 70 lines
- Single commit on `phase/06-termux-backend`, not pushed, not merged

Then stop. Do not begin Phase 7a.
