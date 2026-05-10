# Phase 10 — `LocalLanBackend` + RFC1918 network security config

This prompt corresponds to row 10 of `ARCHITECTURE.md`'s phase plan. It's a smaller, focused phase: one new backend implementation, settings UI for it, and a privacy-posture relaxation in `network_security_config.xml` (with code-level compensation).

**Phase 10 delivers:**
- `LocalLanBackend` implementing `AiClient` against a user-configured base URL.
- Two supported API formats: **Ollama** (`/api/chat`, line-delimited JSON streaming) and **OpenAI-compatible** (`/v1/chat/completions`, SSE streaming). Per ARCHITECTURE.md row 10.
- `BackendResolver.LOCAL_LAN` branch wired (currently returns `null`).
- New "Local LAN" entry in `BackendsScreen` + a dedicated edit screen.
- `network_security_config.xml` extended to permit cleartext traffic (privacy-posture trade-off, documented; code-level guardrails are the compensator).
- `PublicIpValidator` — pure helper that classifies a host as loopback / private (RFC1918) / link-local / public / hostname / invalid. Used by the edit-screen UI to warn before saving a public-IP cleartext URL.

**Out of scope for Phase 10 (do not start):**
- IPv6 classification beyond loopback (`::1`) — Phase 12 polish if needed.
- Dynamic per-host cleartext gating (NSC doesn't support runtime config; see §3 below).
- mDNS/`.local` discovery — defer to Phase 12.
- Health-check ping for the Local LAN base URL — defer.
- Codex (Phase 11) and any further bridge work.

Stop when Phase 10's Definition of Done holds. Do not begin Phase 11.

---

## Read these first (do not skip)

1. `/Users/kacper/SDK/apk-dev/ai-keyboard/ARCHITECTURE.md` — row 10 in the build/phase plan + the security-posture section ("`networkSecurityConfig.xml` whitelists localhost cleartext and user-configured LAN IPs only…"). Phase 10 relaxes that aspiration; document the trade-off in `PHASE_10_SUMMARY.md`.
2. `/Users/kacper/SDK/apk-dev/ai-keyboard/PHASE_REVIEW.md` — universal checklist + Phase 10's per-phase acceptance criteria (`LocalLanBackend implements AiClient against user-configured base URL`; `Settings UI: base URL, optional API key, optional model name`; `Compatible with Ollama` + `OpenAI-compatible servers`; `Validation: if user enters a public IP, warn before allowing cleartext`).
3. `/Users/kacper/SDK/apk-dev/ai-keyboard/PHASE_9b_SUMMARY.md` — most recent phase. Carries the cross-flavor build invariants (still applicable: nothing in Phase 10 is flavor-split, but the same Ktor/serialization patterns hold), the lessons-folded-forward list (R8 keep rules for single-call-site singletons; `UnusedResources` lint discipline; `?attr/...` vs `?android:attr/...` for AppCompat-free chrome).

### Files the prompt depends on (verify before editing)

- `app/app/src/main/java/com/aikeyboard/app/ai/client/BackendStrategy.kt:4` — `enum class BackendStrategy { REMOTE_API, LOCAL_LAN, TERMUX_BRIDGE }`. `LOCAL_LAN` is already declared as a placeholder; do NOT rename or reorder.
- `app/app/src/main/java/com/aikeyboard/app/ai/client/BackendResolver.kt:30` — `BackendStrategy.LOCAL_LAN -> null  // Phase 10`. This is the wire-up point.
- `app/app/src/main/java/com/aikeyboard/app/ai/client/remote/RemoteApiBackend.kt` — Anthropic Messages SSE + Gemini chunked-JSON precedent. Phase 10's two parsers mirror these patterns.
- `app/app/src/main/java/com/aikeyboard/app/ai/client/termux/TermuxBridgeBackend.kt` — singleton `httpClient` with OkHttp engine + 30/60s timeouts. Phase 10 reuses the same singleton; do NOT instantiate a second `HttpClient`.
- `app/app/src/main/java/com/aikeyboard/app/ai/storage/SecureData.kt` — back-compat default-valued field precedent. Phase 8's `alwaysOnEnabled`, Phase 6's `selectedBackendStrategy`, Phase 9b's `StickerPack` extensions are the model.
- `app/app/src/main/java/com/aikeyboard/app/ai/ui/BackendsScreen.kt` + `BackendEditScreen.kt` — the existing Backends settings UI. Phase 10 adds a fourth row + a new edit screen.
- `app/app/src/main/res/xml/network_security_config.xml` — Phase 4 set the loopback cleartext rule; Phase 10 extends. The XML format limits dynamic gating (no CIDR / range support); see §3.

---

## §1 — Architectural decisions locked

### 1.1 Two API formats, user-selected

ARCHITECTURE.md mandates compatibility with Ollama and OpenAI-compatible servers. These are two distinct request/response shapes that we cannot auto-detect cleanly across the wide range of self-hosted setups (LM Studio, vLLM, llama.cpp's server, Ollama, etc. all vary in discovery endpoints). User picks via a radio in the edit screen. Default: **Ollama** (most common self-hosted choice in 2026; LM Studio's "OpenAI-compatible mode" is opt-in, Ollama's API is its native one).

```kotlin
enum class LocalLanApiFormat { OLLAMA, OPENAI_COMPATIBLE }
```

Persisted in `SecureData` as `String?` (null defaults to `OLLAMA`) — same pattern as `selectedBackendStrategy` for forward-compat with future formats.

### 1.2 Endpoint paths

- **Ollama:** POST `<base>/api/chat` (NOT `/api/generate` — `/api/chat` accepts a `messages` array with `system` + `user` roles, matching how Phase 3b Anthropic and Gemini both shape their conversations). Streaming via line-delimited JSON.
- **OpenAI-compatible:** POST `<base>/v1/chat/completions`. Streaming via SSE (`data: {…}\n\n` per event, terminator `data: [DONE]`).

The base URL the user enters is the **bare server origin** (e.g., `http://192.168.1.42:11434`). We append the format-specific path; do NOT require the user to type the full path.

### 1.3 Optional API key

Both formats may use a `Authorization: Bearer <key>` header if the server requires one. Many self-hosted servers don't. Field is optional; empty string ⇒ omit the header.

### 1.4 Model name is required

Ollama needs a model name (e.g., `llama3.2`); OpenAI-compatible servers need it too (`gpt-3.5-turbo`, `qwen2.5-coder`, whatever the server registered). Field is required (validated non-empty before save).

### 1.5 Network security config — privacy-posture trade-off

Android's `network_security_config.xml` does NOT support CIDR ranges or wildcards on IP literals — `<domain>` is exact-match only. We cannot statically declare "any 192.168.x.x" or "any RFC1918". The options:

- **Option A**: Enumerate every common LAN IP literal (`192.168.0.1`, `192.168.0.2`, ... ad infinitum). Impractical.
- **Option B**: Set `<base-config cleartextTrafficPermitted="true"/>` globally. Privacy regression at the platform layer; rely on **code-level guardrails** (`LocalLanBackend` only makes cleartext requests to the user-configured URL, validated by `PublicIpValidator`).
- **Option C**: Use OkHttp customization to bypass NSC for specific URLs. Invasive; fights the platform.

**Phase 10 takes Option B.** The compensator is in code:

1. `LocalLanBackend` is the ONLY backend that makes cleartext requests to a user-configured host. `RemoteApiBackend` (Anthropic, Gemini) is HTTPS-only by virtue of the existing `<domain-config>` blocks — those stay in place as defense-in-depth and continue to forbid cleartext for those hostnames even if `base-config` allows it globally.
2. `TermuxBridgeBackend` (Phase 6) targets `127.0.0.1:8787` only — not user-configurable, no privacy leak surface.
3. The edit-screen UI **warns the user before saving any cleartext URL whose host classifies as `PUBLIC`** (via `PublicIpValidator`). User can override the warning explicitly, but they cannot save a public-IP cleartext URL by accident.

Document this trade-off explicitly in `PHASE_10_SUMMARY.md` "Deviations" — it's a known, accepted privacy-posture relaxation, not a regression that should be hidden.

### 1.6 No flavor split

Phase 10 is entirely flavor-agnostic. No `src/fdroid/` or `src/play/` involvement. `LocalLanBackend` lives in `src/main/`, accessible from both flavors. Same as Phase 9a/9b.

### 1.7 No new permissions

`INTERNET` is already declared (Phase 3a). No additional permissions required.

---

## §2 — Files to create

```
app/app/src/main/java/com/aikeyboard/app/ai/client/locallan/
  LocalLanBackend.kt                       # AiClient impl, both formats
  LocalLanApiFormat.kt                     # enum
  LocalLanRequestBuilder.kt                # pure helpers: build JSON bodies
  OllamaStreamParser.kt                    # JSONL → AiStreamEvent flow
  OpenAiCompatStreamParser.kt              # SSE → AiStreamEvent flow

app/app/src/main/java/com/aikeyboard/app/ai/client/locallan/util/
  PublicIpValidator.kt                     # JVM-pure host classifier

app/app/src/main/java/com/aikeyboard/app/ai/ui/locallan/
  LocalLanEditRoute.kt                     # state hoist + navigation
  LocalLanEditScreen.kt                    # Compose form + warn dialog

app/app/src/test/java/com/aikeyboard/app/ai/client/locallan/
  LocalLanRequestBuilderTest.kt            # Ollama + OpenAI body shapes
  OllamaStreamParserTest.kt                # JSONL parsing edge cases
  OpenAiCompatStreamParserTest.kt          # SSE parsing edge cases
  PublicIpValidatorTest.kt                 # IPv4 classification
```

## §3 — Files to modify

```
app/app/src/main/java/com/aikeyboard/app/ai/storage/SecureData.kt
  + 4 new default-valued fields for Local LAN config

app/app/src/main/java/com/aikeyboard/app/ai/storage/SecureStorage.kt
  + getLocalLanBaseUrl/setLocalLanBaseUrl
  + getLocalLanApiFormat/setLocalLanApiFormat (LocalLanApiFormat enum, null → OLLAMA)
  + getLocalLanApiKey/setLocalLanApiKey
  + getLocalLanModelName/setLocalLanModelName

app/app/src/main/java/com/aikeyboard/app/ai/client/BackendResolver.kt
  + LOCAL_LAN branch returns LocalLanBackend if base URL non-empty + model
    non-empty; null otherwise (consistent with REMOTE_API/TERMUX null-on-
    incomplete-config). Keep dispatch trivial — no abstraction layer.

app/app/src/main/java/com/aikeyboard/app/ai/ui/BackendsScreen.kt
  + Add a "Local LAN" provider row (icon + status label like
    "192.168.1.42 · llama3.2" if configured, "Not configured" otherwise).
  + Routes onClick to a new BACKENDS_LOCALLAN_EDIT route.
  + Phase 6's "Active for Rewrite" radio gains a third option (LOCAL_LAN);
    enabled iff getLocalLanBaseUrl + getLocalLanModelName both non-empty
    (mirrors REMOTE_API's "Configure to enable" disabled state).

app/app/src/main/java/com/aikeyboard/app/ai/ui/AiSettingsNavHost.kt
  + AiSettingsRoutes.BACKENDS_LOCALLAN_EDIT = "backends/locallan/edit"
  + composable(BACKENDS_LOCALLAN_EDIT) { LocalLanEditRoute(...) }

app/app/src/main/res/xml/network_security_config.xml
  + Switch base-config to cleartextTrafficPermitted="true"
  + Keep the existing https-only domain-configs for api.anthropic.com /
    generativelanguage.googleapis.com (defense in depth; documented in §6).

app/app/src/main/res/values/ai_strings.xml
  + ai_settings_backend_locallan_* strings (see §11)

app/app/proguard-rules.pro
  + Pre-emptive keep rule for LocalLanBackend (lesson #1 from PHASE_9a:
    single-call-site singleton-style classes have a 3-for-3 history of
    needing keep rules — BackendResolver, ReadRespondPromptBuilder,
    StickerCommitter all needed them. Verify post-build via apkanalyzer
    that the class is present in both flavor release dexes; if R8 inlines
    despite the rule, escalate.)
```

---

## §4 — `SecureData` extensions

Append four fields, all default-valued (back-compat with Phase 1–9b manifest blobs; no schema bump). Place them immediately after `selectedTermuxProvider` to keep backend-related fields grouped:

```kotlin
@Serializable
internal data class SecureData(
    ...
    val selectedTermuxProvider: String? = null,
    // Phase 10: Local LAN backend configuration. All default-valued so existing
    // SecureData blobs decode without migration. localLanApiFormat is String?
    // (not the enum directly) for the same null-defaults-to-OLLAMA pattern as
    // selectedBackendStrategy.
    val localLanBaseUrl: String = "",
    val localLanApiFormat: String? = null,
    val localLanApiKey: String = "",
    val localLanModelName: String = "",
    ...
)
```

`SecureStorage` accessors mirror the Phase 6 / Phase 8 pattern:

```kotlin
fun getLocalLanBaseUrl(): String = load().localLanBaseUrl
fun setLocalLanBaseUrl(url: String) { save(load().copy(localLanBaseUrl = url.trim())) }

fun getLocalLanApiFormat(): LocalLanApiFormat =
    load().localLanApiFormat?.let {
        runCatching { LocalLanApiFormat.valueOf(it) }.getOrNull()
    } ?: LocalLanApiFormat.OLLAMA
fun setLocalLanApiFormat(format: LocalLanApiFormat) {
    save(load().copy(localLanApiFormat = format.name))
}

fun getLocalLanApiKey(): String = load().localLanApiKey
fun setLocalLanApiKey(key: String) { save(load().copy(localLanApiKey = key.trim())) }

fun getLocalLanModelName(): String = load().localLanModelName
fun setLocalLanModelName(name: String) { save(load().copy(localLanModelName = name.trim())) }
```

`runCatching { LocalLanApiFormat.valueOf(...) }.getOrNull()` handles future-renamed enum values gracefully (an unknown stored value falls back to OLLAMA rather than crashing).

---

## §5 — `LocalLanApiFormat.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.client.locallan

enum class LocalLanApiFormat {
    /** Ollama-native: POST <base>/api/chat, line-delimited JSON streaming. */
    OLLAMA,
    /** OpenAI-compatible: POST <base>/v1/chat/completions, SSE streaming. */
    OPENAI_COMPATIBLE,
}
```

---

## §6 — `network_security_config.xml`

Replace the current contents with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
    AI Keyboard network security config.
    Phase 3a: HTTPS-only for the two remote-API providers we ship with.
    Phase 4: cleartext for 127.0.0.1 / localhost (Termux bridge).
    Phase 10: base-config now permits cleartext globally so the user-configured
    Local LAN backend can talk to any RFC1918 / mDNS / hostname destination.
    The privacy compensator is in code: LocalLanBackend is the ONLY backend
    whose URL is user-controlled, and PublicIpValidator warns the user before
    saving a cleartext URL whose host classifies as PUBLIC. The two HTTPS-only
    domain-configs below stay as defense-in-depth and continue to forbid
    cleartext to those hostnames even though base-config allows it elsewhere.
-->
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
    <domain-config cleartextTrafficPermitted="false">
        <domain includeSubdomains="true">api.anthropic.com</domain>
        <domain includeSubdomains="true">generativelanguage.googleapis.com</domain>
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </domain-config>
</network-security-config>
```

The `domain-config` for the cloud providers is RETAINED — it pins them to HTTPS even though base now allows cleartext. The previous `<domain-config cleartextTrafficPermitted="true">` for `127.0.0.1`/`localhost` is REMOVED (it's now subsumed by `base-config`'s permissive default).

This is a deliberate privacy-posture relaxation. Document in `PHASE_10_SUMMARY.md` Deviations:

> **Deviation: `network_security_config.xml` `base-config` switched from `cleartextTrafficPermitted="false"` to `"true"`.** Required because Android NSC does not support CIDR ranges or IP-literal wildcards, so we cannot statically allow only RFC1918. The privacy compensator is `PublicIpValidator` + the warn-before-save dialog: `LocalLanBackend` is the only backend whose URL is user-controlled, and the UI guards against accidentally saving a public-IP cleartext URL.

---

## §7 — `PublicIpValidator.kt`

JVM-pure helper. Classifies the host portion of a URL.

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.client.locallan.util

import java.net.URI

object PublicIpValidator {

    enum class Classification {
        LOOPBACK,        // 127.0.0.0/8 or "localhost" or ::1
        PRIVATE,         // RFC1918: 10/8, 172.16/12, 192.168/16
        LINK_LOCAL,      // 169.254/16 (DHCP fallback) or fe80::/10
        HOSTNAME,        // not an IP literal — DNS resolves at runtime
        PUBLIC,          // routable IPv4 outside the private/loopback ranges
        INVALID,         // can't parse the URL or host
    }

    /** True if the classification doesn't require a public-IP cleartext warning. */
    fun isSafeForCleartext(c: Classification): Boolean = when (c) {
        Classification.LOOPBACK,
        Classification.PRIVATE,
        Classification.LINK_LOCAL,
        Classification.HOSTNAME -> true
        Classification.PUBLIC,
        Classification.INVALID -> false
    }

    /** Pure helper: extracts host from URL string and classifies it. */
    fun classifyUrl(url: String): Classification {
        val host = runCatching { URI(url.trim()).host }.getOrNull()
            ?: return Classification.INVALID
        if (host.isEmpty()) return Classification.INVALID
        return classifyHost(host)
    }

    /** Pure helper: classifies a host string (IP literal or hostname). */
    internal fun classifyHost(host: String): Classification {
        // IPv6 loopback and link-local. Phase 10 v1 doesn't fully classify
        // arbitrary IPv6; everything else with a colon is treated as PUBLIC
        // unless it matches loopback/link-local prefixes.
        if (host == "::1" || host == "[::1]") return Classification.LOOPBACK
        if (host.startsWith("fe80:", ignoreCase = true) ||
            host.startsWith("[fe80:", ignoreCase = true)) return Classification.LINK_LOCAL
        if (host.contains(':')) return Classification.PUBLIC // conservative IPv6 fallback
        if (host.equals("localhost", ignoreCase = true)) return Classification.LOOPBACK
        val octets = host.split('.')
        if (octets.size != 4 || !octets.all { it.toIntOrNull()?.let { v -> v in 0..255 } == true }) {
            return Classification.HOSTNAME
        }
        val a = octets[0].toInt()
        val b = octets[1].toInt()
        return when {
            a == 127 -> Classification.LOOPBACK
            a == 10 -> Classification.PRIVATE
            a == 172 && b in 16..31 -> Classification.PRIVATE
            a == 192 && b == 168 -> Classification.PRIVATE
            a == 169 && b == 254 -> Classification.LINK_LOCAL
            a == 0 || a >= 224 -> Classification.INVALID // 0.x or multicast/reserved
            else -> Classification.PUBLIC
        }
    }
}
```

The `URI(url).host` parse handles `http://1.2.3.4:5678/path` (returns `1.2.3.4`), `http://example.com:443` (returns `example.com`), bracketed IPv6 (returns the bracketed form). Edge cases — empty host, missing scheme, malformed URI — return `INVALID`.

---

## §8 — `LocalLanRequestBuilder.kt`

Pure JVM helpers building the request bodies for both formats. Extracted so the unit tests can exercise body shape without instantiating Ktor.

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.client.locallan

import com.aikeyboard.app.ai.persona.FewShot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class ChatMessage(val role: String, val content: String)

@Serializable
internal data class OllamaChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
)

@Serializable
internal data class OpenAiChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
)

internal object LocalLanRequestBuilder {

    /**
     * Builds the messages array from system prompt + few-shot exemplars + the
     * user's input. Order matches Phase 3b's RemoteApiBackend exactly so
     * Ollama and OpenAI servers see the same message sequence as Anthropic /
     * Gemini do — otherwise persona behavior would diverge across backends.
     */
    fun buildMessages(
        input: String,
        systemPrompt: String,
        fewShots: List<FewShot>,
    ): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        if (systemPrompt.isNotBlank()) {
            out += ChatMessage("system", systemPrompt)
        }
        for (shot in fewShots) {
            out += ChatMessage("user", shot.userInput)
            out += ChatMessage("assistant", shot.assistantResponse)
        }
        out += ChatMessage("user", input)
        return out
    }

    fun ollamaBody(model: String, messages: List<ChatMessage>): String =
        JSON.encodeToString(OllamaChatRequest(model = model, messages = messages))

    fun openAiBody(model: String, messages: List<ChatMessage>): String =
        JSON.encodeToString(OpenAiChatRequest(model = model, messages = messages))

    private val JSON = Json { encodeDefaults = true; ignoreUnknownKeys = true }
}
```

---

## §9 — Stream parsers

### `OllamaStreamParser.kt`

Ollama's `/api/chat` returns line-delimited JSON: each line is a self-contained JSON object. The last object has `"done": true` and may carry usage stats; intermediate objects have `"message": {"role": "assistant", "content": "..."}` with the partial response.

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.client.locallan

import com.aikeyboard.app.ai.client.AiStreamEvent
import com.aikeyboard.app.ai.client.ErrorType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class OllamaChatStreamMessage(val role: String? = null, val content: String? = null)

@Serializable
internal data class OllamaChatStreamChunk(
    val message: OllamaChatStreamMessage? = null,
    val done: Boolean = false,
    val error: String? = null,
)

internal object OllamaStreamParser {

    private val JSON = Json { ignoreUnknownKeys = true }

    /**
     * Pure helper: takes the response body as line-delimited JSON, emits
     * AiStreamEvent values via the callback. Caller is responsible for
     * iterating lines (via Ktor's body-as-channel; see LocalLanBackend).
     *
     * Errors on the wire surface as Ollama's "error" field on a chunk;
     * map to AiStreamEvent.Error with ErrorType.NETWORK_FAILURE (no auth
     * concept in Ollama's native response shape).
     */
    fun parseLine(line: String, emit: (AiStreamEvent) -> Unit) {
        if (line.isBlank()) return
        val chunk = runCatching { JSON.decodeFromString<OllamaChatStreamChunk>(line) }.getOrNull()
            ?: return // malformed line — skip silently; PRIVACY: don't log line contents
        chunk.error?.let {
            // PRIVACY: don't echo `it` (server may include prompt fragments). Use static msg.
            emit(AiStreamEvent.Error(ErrorType.NETWORK_FAILURE, "ollama-error"))
            return
        }
        val delta = chunk.message?.content
        if (!delta.isNullOrEmpty()) emit(AiStreamEvent.Delta(delta))
        if (chunk.done) emit(AiStreamEvent.Done)
    }
}
```

### `OpenAiCompatStreamParser.kt`

OpenAI-compatible servers stream SSE: each event is `data: {…}\n\n`, terminated by `data: [DONE]`. Each `{…}` has a `choices[].delta.content` field. Same precedent as Phase 3b's Anthropic parser, but the event-payload shape is OpenAI's, not Anthropic's.

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.client.locallan

import com.aikeyboard.app.ai.client.AiStreamEvent
import com.aikeyboard.app.ai.client.ErrorType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class OpenAiDelta(val content: String? = null)

@Serializable
internal data class OpenAiChoice(val delta: OpenAiDelta? = null, val finish_reason: String? = null)

@Serializable
internal data class OpenAiChunk(val choices: List<OpenAiChoice> = emptyList())

internal object OpenAiCompatStreamParser {

    private val JSON = Json { ignoreUnknownKeys = true }
    private const val DATA_PREFIX = "data:"
    private const val DONE_SENTINEL = "[DONE]"

    /**
     * Pure helper: takes a single SSE line ("data: {...}" or "data: [DONE]")
     * and emits AiStreamEvent. Caller iterates lines from Ktor's body channel
     * and calls this for each.
     *
     * SSE comments (lines starting with ":") and empty lines are no-ops.
     */
    fun parseLine(line: String, emit: (AiStreamEvent) -> Unit) {
        val trimmed = line.trimStart()
        if (trimmed.isEmpty() || trimmed.startsWith(":")) return
        if (!trimmed.startsWith(DATA_PREFIX)) return
        val payload = trimmed.removePrefix(DATA_PREFIX).trimStart()
        if (payload == DONE_SENTINEL) {
            emit(AiStreamEvent.Done)
            return
        }
        val chunk = runCatching { JSON.decodeFromString<OpenAiChunk>(payload) }.getOrNull()
            ?: return // malformed — skip; PRIVACY: don't log payload
        val delta = chunk.choices.firstOrNull()?.delta?.content
        if (!delta.isNullOrEmpty()) emit(AiStreamEvent.Delta(delta))
        // Only TERMINAL finish_reason values mark the stream as complete.
        // Intermediate values like "tool_calls" or "content_filter" can appear
        // on chunks that aren't the last one (especially on multi-step
        // function-calling servers); treating those as Done would terminate
        // the stream early. The [DONE] sentinel above is the universal
        // closer; finish_reason is a fallback for servers that omit it.
        val finish = chunk.choices.firstOrNull()?.finish_reason
        if (finish == "stop" || finish == "length") emit(AiStreamEvent.Done)
    }
}
```

Both parsers are pure JVM — no Android, no Ktor, no Robolectric. Direct unit tests via String inputs.

---

## §10 — `LocalLanBackend.kt`

Implements `AiClient`. Reuses the existing `httpClient` singleton from `TermuxBridgeBackend.kt:169` (do NOT instantiate a second `HttpClient`). Uses Ktor's body-as-channel for streaming.

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.client.locallan

import android.util.Log
import com.aikeyboard.app.ai.client.AiClient
import com.aikeyboard.app.ai.client.AiStreamEvent
import com.aikeyboard.app.ai.client.ErrorType
import com.aikeyboard.app.ai.client.termux.TermuxBridgeBackend // for httpClient singleton
import com.aikeyboard.app.ai.persona.FewShot
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class LocalLanBackend(
    private val baseUrl: String,
    private val apiFormat: LocalLanApiFormat,
    private val apiKey: String,
    private val modelName: String,
) : AiClient {

    /**
     * Mirrors the BackendResolver gate: a backend is "available" when it has
     * the minimum config to make a request. Without this, Phase 10 would
     * fail to compile (AiClient.kt:12 declares isAvailable as abstract).
     */
    override fun isAvailable(): Boolean =
        baseUrl.isNotEmpty() && modelName.isNotEmpty()

    override fun rewrite(
        input: String,
        systemPrompt: String,
        fewShots: List<FewShot>,
    ): Flow<AiStreamEvent> = callbackFlow {
        val messages = LocalLanRequestBuilder.buildMessages(input, systemPrompt, fewShots)
        try {
            when (apiFormat) {
                LocalLanApiFormat.OLLAMA -> streamOllama(this, messages)
                LocalLanApiFormat.OPENAI_COMPATIBLE -> streamOpenAi(this, messages)
            }
        } catch (t: Throwable) {
            // Order matters: TimeoutCancellationException IS-A CancellationException;
            // catch it first so a network timeout surfaces as an error instead of
            // silently swallowed by the cancellation re-throw. Same precedent as
            // Phase 6's TermuxBridgeBackend.
            if (t is TimeoutCancellationException) {
                trySend(AiStreamEvent.Error(ErrorType.TIMEOUT, "locallan-timeout"))
            } else if (t !is kotlinx.coroutines.CancellationException) {
                // PRIVACY: log type only — server-returned strings may include the
                // prompt or user input. Same precedent as Phase 7b's stream catch.
                Log.w(TAG, "LocalLan stream failed: ${t.javaClass.simpleName}")
                trySend(AiStreamEvent.Error(ErrorType.NETWORK_FAILURE, "locallan-failure"))
            }
        }
        // OkHttp's coroutine engine cancels the underlying Call when the
        // scope is cancelled (no explicit teardown needed here). The empty
        // awaitClose body is required by the callbackFlow contract.
        awaitClose { }
    }

    private suspend fun streamOllama(
        scope: ProducerScope<AiStreamEvent>,
        messages: List<ChatMessage>,
    ) {
        val url = baseUrl.trimEnd('/') + "/api/chat"
        val body = LocalLanRequestBuilder.ollamaBody(modelName, messages)
        TermuxBridgeBackend.httpClient.preparePost(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
            if (apiKey.isNotEmpty()) headers { append("Authorization", "Bearer $apiKey") }
        }.execute { response ->
            if (response.status != HttpStatusCode.OK) {
                scope.trySend(AiStreamEvent.Error(mapHttpError(response.status), "locallan-http"))
                return@execute
            }
            val channel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                OllamaStreamParser.parseLine(line) { scope.trySend(it) }
            }
        }
    }

    private suspend fun streamOpenAi(
        scope: ProducerScope<AiStreamEvent>,
        messages: List<ChatMessage>,
    ) {
        val url = baseUrl.trimEnd('/') + "/v1/chat/completions"
        val body = LocalLanRequestBuilder.openAiBody(modelName, messages)
        TermuxBridgeBackend.httpClient.preparePost(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
            if (apiKey.isNotEmpty()) headers { append("Authorization", "Bearer $apiKey") }
        }.execute { response ->
            if (response.status != HttpStatusCode.OK) {
                scope.trySend(AiStreamEvent.Error(mapHttpError(response.status), "locallan-http"))
                return@execute
            }
            val channel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                OpenAiCompatStreamParser.parseLine(line) { scope.trySend(it) }
            }
        }
    }

    private fun mapHttpError(status: HttpStatusCode): ErrorType = when (status.value) {
        401, 403 -> ErrorType.AUTH_FAILURE
        429 -> ErrorType.RATE_LIMITED
        in 500..599 -> ErrorType.NETWORK_FAILURE
        else -> ErrorType.UNKNOWN
    }

    companion object {
        private const val TAG = "LocalLanBackend"
    }
}
```

**Cross-package httpClient access**: `TermuxBridgeBackend.httpClient` is declared at `TermuxBridgeBackend.kt:169` as `val httpClient: HttpClient by lazy { ... }` inside `companion object` with **no visibility modifier — meaning it is `public` by default** (Kotlin companion-object members default to public). **No visibility change is required.** Reference it directly as `TermuxBridgeBackend.httpClient`. Do NOT modify Phase 6 code.

---

## §11 — `BackendResolver.kt` extension

Replace the `LOCAL_LAN -> null` line with the actual dispatch:

```kotlin
BackendStrategy.LOCAL_LAN -> {
    val baseUrl = storage.getLocalLanBaseUrl()
    val model = storage.getLocalLanModelName()
    if (baseUrl.isEmpty() || model.isEmpty()) return null
    LocalLanBackend(
        baseUrl = baseUrl,
        apiFormat = storage.getLocalLanApiFormat(),
        apiKey = storage.getLocalLanApiKey(),
        modelName = model,
    )
}
```

Same null-on-incomplete-config pattern as REMOTE_API and TERMUX_BRIDGE branches. Caller toasts "configure a backend".

---

## §12 — `BackendsScreen.kt` extension

Read the existing screen first (`BackendsScreen.kt:43`). The current signature:

```kotlin
fun BackendsScreen(
    onBack: () -> Unit,
    onEditProvider: (Provider) -> Unit,
    onOpenTermuxBridge: () -> Unit,
)
```

The "Active for Rewrite" selector is **not** a separate radio group at the top — each `ListItem` has a `leadingContent { RadioButton(...) }` whose `selected` state is computed per-row from `activeStrategy` (and `activeRemoteProvider` for REMOTE_API rows). Phase 10 adds a fourth row with its own embedded RadioButton.

### Required changes

1. **Add a parameter** to the screen signature:

   ```kotlin
   fun BackendsScreen(
       onBack: () -> Unit,
       onEditProvider: (Provider) -> Unit,
       onOpenTermuxBridge: () -> Unit,
       onOpenLocalLanEdit: () -> Unit,   // NEW
   )
   ```

2. **Insert the Local LAN row** between the Remote API provider rows and the Termux Bridge row (matches `BackendStrategy` enum order: `REMOTE_API → LOCAL_LAN → TERMUX_BRIDGE`). Render it as a single `ListItem` styled identically to the Termux row, with:
   - **Leading**: `RadioButton(selected = activeStrategy == BackendStrategy.LOCAL_LAN, enabled = localLanSelectable, onClick = { ... })`
   - **Headline**: `stringResource(R.string.ai_settings_backend_locallan_title)` (= "Local LAN")
   - **Supporting text**: status string (see below)
   - **Trailing chevron**: same as Termux row
   - `Modifier.clickable { onOpenLocalLanEdit() }`

3. **Compute `localLanSelectable`** alongside the existing `termuxSelectable` in the same on-resume / `LaunchedEffect` block:

   ```kotlin
   val localLanSelectable = storage.getLocalLanBaseUrl().isNotEmpty() &&
                            storage.getLocalLanModelName().isNotEmpty()
   ```

4. **RadioButton onClick handler** — mirrors the Termux row exactly:

   ```kotlin
   onClick = {
       storage.setSelectedBackendStrategy(BackendStrategy.LOCAL_LAN)
       activeStrategy = BackendStrategy.LOCAL_LAN
   }
   ```

5. **Supporting text** (status string), computed inline:
   - Both empty → `R.string.ai_settings_backend_locallan_desc_unconfigured` ("Not configured")
   - `baseUrl` non-empty + `modelName` non-empty → `R.string.ai_settings_backend_locallan_status_configured` interpolated as `"<host extracted from baseUrl> · <model>"`. Use `URI(baseUrl).host` (or the URL string itself if parsing fails) for the host display.
   - `baseUrl` non-empty, `modelName` empty → `R.string.ai_settings_backend_locallan_status_model_required` interpolated with the host.

---

## §13 — `LocalLanEditRoute.kt` + `LocalLanEditScreen.kt`

Compose state hoist + form. Mirror `BackendEditScreen` patterns precisely (same TopAppBar, same OutlinedTextField + Save button approach). Read `app/app/src/main/java/com/aikeyboard/app/ai/ui/BackendEditScreen.kt` first.

### `LocalLanEditRoute.kt` (route shell)

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.ui.locallan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.aikeyboard.app.ai.client.locallan.LocalLanApiFormat
import com.aikeyboard.app.ai.storage.SecureStorage

@Composable
fun LocalLanEditRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val storage = remember { SecureStorage.getInstance(context) }
    LocalLanEditScreen(
        initialBaseUrl = storage.getLocalLanBaseUrl(),
        initialApiFormat = storage.getLocalLanApiFormat(),
        initialApiKey = storage.getLocalLanApiKey(),
        initialModelName = storage.getLocalLanModelName(),
        onBack = onBack,
        onSave = { baseUrl, format, apiKey, model ->
            storage.setLocalLanBaseUrl(baseUrl)
            storage.setLocalLanApiFormat(format)
            storage.setLocalLanApiKey(apiKey)
            storage.setLocalLanModelName(model)
        },
    )
}
```

### `LocalLanEditScreen.kt` (form)

Five fields:

1. **Base URL** — `OutlinedTextField`, single-line, hint `"http://192.168.1.42:11434"`. Required (non-empty).
2. **API format** — radio group, two options: "Ollama (default)" and "OpenAI-compatible".
3. **Model name** — `OutlinedTextField`, single-line, hint `"llama3.2"`. Required.
4. **API key (optional)** — `OutlinedTextField` with `visualTransformation = PasswordVisualTransformation()` and a show/hide toggle (mirror the existing Anthropic/Gemini API-key field pattern).
5. **Save button** — bottom of screen.

**Public-IP guard:** before calling `onSave`, the screen calls `PublicIpValidator.classifyUrl(baseUrl)`. If `!isSafeForCleartext(classification)`, render an `AlertDialog` with title "Public IP without HTTPS" and body explaining the privacy risk, with "Save anyway" / "Cancel" buttons. Only on "Save anyway" does the actual `onSave` callback fire.

```kotlin
// abridged; full screen code is left to executor — mirror BackendEditScreen.
@Composable
fun LocalLanEditScreen(
    initialBaseUrl: String,
    initialApiFormat: LocalLanApiFormat,
    initialApiKey: String,
    initialModelName: String,
    onBack: () -> Unit,
    onSave: (String, LocalLanApiFormat, String, String) -> Unit,
) {
    var baseUrl by remember { mutableStateOf(initialBaseUrl) }
    var format by remember { mutableStateOf(initialApiFormat) }
    var apiKey by remember { mutableStateOf(initialApiKey) }
    var modelName by remember { mutableStateOf(initialModelName) }
    var pendingPublicIpConfirm by remember { mutableStateOf(false) }

    val saveAction = {
        val classification = PublicIpValidator.classifyUrl(baseUrl)
        if (PublicIpValidator.isSafeForCleartext(classification)) {
            onSave(baseUrl, format, apiKey, modelName)
            onBack()
        } else {
            pendingPublicIpConfirm = true
        }
    }

    // Scaffold + form + Save button (calls saveAction)
    // ... (mirror BackendEditScreen's structure precisely)

    if (pendingPublicIpConfirm) {
        AlertDialog(
            onDismissRequest = { pendingPublicIpConfirm = false },
            title = { Text(stringResource(R.string.ai_settings_locallan_public_ip_title)) },
            text = { Text(stringResource(R.string.ai_settings_locallan_public_ip_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingPublicIpConfirm = false
                    onSave(baseUrl, format, apiKey, modelName)
                    onBack()
                }) { Text(stringResource(R.string.ai_settings_locallan_save_anyway)) }
            },
            dismissButton = {
                // Reuse existing R.string.ai_settings_persona_cancel ("Cancel") —
                // do not add a Phase-10-specific cancel string (it would lint as
                // UnusedResources, same precedent as Phase 9b's four dropped strings).
                TextButton(onClick = { pendingPublicIpConfirm = false }) {
                    Text(stringResource(R.string.ai_settings_persona_cancel))
                }
            },
        )
    }
}
```

The Save button must be **disabled** when `baseUrl` or `modelName` is empty (post-trim). Same UX as `BackendEditScreen`.

---

## §14 — `AiSettingsNavHost.kt` extension

Three edits needed (the third is easy to miss):

```kotlin
// (1) In AiSettingsRoutes:
const val BACKENDS_LOCALLAN_EDIT = "backends/locallan/edit"

// (2) Add a new composable in the NavHost composable list:
composable(AiSettingsRoutes.BACKENDS_LOCALLAN_EDIT) {
    LocalLanEditRoute(onBack = { nav.popBackStack() })
}

// (3) Update the EXISTING BackendsScreen call site (currently around
//     AiSettingsNavHost.kt:84-91) to pass the new onOpenLocalLanEdit lambda:
composable(AiSettingsRoutes.BACKENDS_LIST) {
    BackendsScreen(
        onBack = { nav.popBackStack() },
        onEditProvider = { provider ->
            nav.navigate(AiSettingsRoutes.editBackendRoute(provider.storageKey))
        },
        onOpenTermuxBridge = { nav.navigate(AiSettingsRoutes.BACKENDS_TERMUX) },
        onOpenLocalLanEdit = { nav.navigate(AiSettingsRoutes.BACKENDS_LOCALLAN_EDIT) },  // NEW
    )
}
```

Without edit (3), the build fails: `BackendsScreen` now requires a fifth parameter (per §12). No path arguments on the new route — single-screen edit, no provider-specific variant.

---

## §15 — Strings (`ai_strings.xml`)

```xml
<!-- Phase 10: LocalLanBackend -->
<string name="ai_settings_backend_locallan_title">Local LAN</string>
<string name="ai_settings_backend_locallan_desc_unconfigured">Not configured</string>
<!-- "host · model" — interpolated at render time -->
<string name="ai_settings_backend_locallan_status_configured">%1$s · %2$s</string>
<string name="ai_settings_backend_locallan_status_model_required">%1$s · model required</string>

<string name="ai_settings_locallan_edit_title">Local LAN backend</string>
<string name="ai_settings_locallan_base_url_label">Base URL</string>
<string name="ai_settings_locallan_base_url_hint">http://192.168.1.42:11434</string>
<string name="ai_settings_locallan_format_label">API format</string>
<string name="ai_settings_locallan_format_ollama">Ollama (default)</string>
<string name="ai_settings_locallan_format_openai">OpenAI-compatible</string>
<string name="ai_settings_locallan_model_label">Model name</string>
<string name="ai_settings_locallan_model_hint">llama3.2</string>
<string name="ai_settings_locallan_apikey_label">API key (optional)</string>
<string name="ai_settings_locallan_apikey_help">Some servers (LM Studio, vLLM with auth) require a Bearer token. Leave blank if your server doesn\'t.</string>
<string name="ai_settings_locallan_save">Save</string>

<string name="ai_settings_locallan_public_ip_title">Public IP without HTTPS</string>
<string name="ai_settings_locallan_public_ip_body">The address you entered is a public IP. Cleartext traffic to public hosts is a privacy risk — your prompts and the model\'s replies are sent unencrypted across the internet. Only continue if you control the destination network.</string>
<string name="ai_settings_locallan_save_anyway">Save anyway</string>

<!-- Phase 6 radio gains a third option -->
<string name="ai_settings_backends_radio_locallan">Local LAN</string>
```

---

## §16 — R8 keep rules

Append to `proguard-rules.pro`:

```
# Phase 10: LocalLanBackend has multiple methods called from BackendResolver
# (constructor + rewrite). R8 should not need this keep rule, but the
# 9a/9b precedent (BackendResolver, ReadRespondPromptBuilder, StickerCommitter
# all needed similar rules despite plausible-looking call-site analysis) says
# verify-and-keep. After build, run apkanalyzer dex packages on the fdroid
# release APK; if LocalLanBackend is missing, this rule is the recovery.
-keep class com.aikeyboard.app.ai.client.locallan.LocalLanBackend {
    public <init>(...);
    public *** rewrite(...);
}
```

---

## §17 — Tests

Four JVM-only unit tests. None require Robolectric.

### `PublicIpValidatorTest.kt`

```kotlin
class PublicIpValidatorTest {
    @Test fun loopback_ipv4_classifiesLoopback() { ... }
    @Test fun localhost_classifiesLoopback() { ... }
    @Test fun ipv6Loopback_classifiesLoopback() { ... }
    @Test fun rfc1918_10_classifiesPrivate() { ... }
    @Test fun rfc1918_172_lowEdge_classifiesPrivate() { ... }   // 172.16.0.1
    @Test fun rfc1918_172_highEdge_classifiesPrivate() { ... }  // 172.31.255.254
    @Test fun rfc1918_172_15_isPublic() { ... }                 // 172.15.x.x is NOT private
    @Test fun rfc1918_172_32_isPublic() { ... }                 // 172.32.x.x is NOT private
    @Test fun rfc1918_192_168_classifiesPrivate() { ... }
    @Test fun linkLocal_classifiesLinkLocal() { ... }
    @Test fun publicIp_classifiesPublic() { ... }                // 8.8.8.8
    @Test fun hostname_classifiesHostname() { ... }              // ollama.example.com
    @Test fun multicast_classifiesInvalid() { ... }              // 224.x.x.x
    @Test fun emptyHost_classifiesInvalid() { ... }
    @Test fun classifyUrl_extractsHostFromFullUrl() { ... }      // http://1.2.3.4:8080/path
    @Test fun classifyUrl_handlesNoScheme() { ... }
}
```

### `LocalLanRequestBuilderTest.kt`

```kotlin
class LocalLanRequestBuilderTest {
    @Test fun buildMessages_systemPromptFirst() { ... }
    @Test fun buildMessages_skipsBlankSystemPrompt() { ... }
    @Test fun buildMessages_fewShotsBeforeUser() { ... }
    @Test fun ollamaBody_serializesShape() { /* assert "model", "messages", "stream":true */ }
    @Test fun openAiBody_serializesShape() { /* same */ }
}
```

### `OllamaStreamParserTest.kt`

```kotlin
class OllamaStreamParserTest {
    @Test fun deltaChunk_emitsDelta() { ... }
    @Test fun doneChunk_emitsDone() { ... }
    @Test fun errorChunk_emitsErrorWithStaticMessage() { ... }   // verify privacy: static msg, not server's text
    @Test fun blankLine_isNoOp() { ... }
    @Test fun malformedJson_isSilentlySkipped() { ... }          // privacy: don't log line
}
```

### `OpenAiCompatStreamParserTest.kt`

```kotlin
class OpenAiCompatStreamParserTest {
    @Test fun dataLine_emitsDelta() { ... }
    @Test fun doneSentinel_emitsDone() { ... }                   // "data: [DONE]"
    @Test fun finishReasonStop_emitsDone() { ... }               // finish_reason="stop"
    @Test fun finishReasonLength_emitsDone() { ... }             // finish_reason="length"
    @Test fun finishReasonToolCalls_doesNotEmitDone() { ... }    // intermediate, must NOT close stream
    @Test fun sseComment_isNoOp() { ... }                        // ":heartbeat" lines
    @Test fun blankLine_isNoOp() { ... }
    @Test fun emptyDataPayload_isNoOp() { ... }                  // "data: " (space-only heartbeat)
    @Test fun malformedJson_isSilentlySkipped() { ... }
    @Test fun nonDataPrefixedLine_isNoOp() { ... }               // event: ... lines
}
```

**Tally target: 34 new tests + 112 prior = 146 passing AI-module unit tests.** (16 PublicIp + 5 RequestBuilder + 5 Ollama + 8 OpenAi-compat. The OpenAi parser test count grew by 3 vs. an initial draft to cover finish_reason narrowing — see §9's `finishReasonStop`/`finishReasonLength`/`finishReasonToolCalls` tests.)

---

## §18 — Definition of Done

All four flavor/buildtype builds clean (`assembleFdroidDebug assemblePlayDebug assembleFdroidRelease assemblePlayRelease`). `lintFdroidDebug lintPlayDebug` produce only the pre-existing carry-overs (Phase 4 `ObsoleteSdkInt`, AGP/dependency version warnings). `git diff app/app/lint-baseline.xml` empty.

Functional invariants:

1. `LocalLanBackend(baseUrl, OLLAMA, "", "llama3.2").rewrite(...)` builds the correct POST to `<base>/api/chat` with the correct JSON body shape, parses line-delimited JSON streaming responses, emits `Delta` per token + `Done` on completion.
2. `LocalLanBackend(..., OPENAI_COMPATIBLE, key, model).rewrite(...)` posts to `/v1/chat/completions` with `Authorization: Bearer <key>` (key non-empty) or no auth header (key empty), parses SSE, emits `[DONE]` → `Done`.
3. `BackendResolver.resolve(storage)` returns `LocalLanBackend` when `selectedBackendStrategy == LOCAL_LAN` AND `baseUrl + modelName` are both non-empty; returns `null` otherwise.
4. `PublicIpValidator.classifyUrl("http://192.168.1.42:11434")` returns `PRIVATE`; `"http://8.8.8.8"` returns `PUBLIC`; `"http://localhost"` returns `LOOPBACK`; `"http://my-pi.local"` returns `HOSTNAME`.
5. The edit screen warns before saving a `PUBLIC` URL; the user can override.
6. `network_security_config.xml` `base-config` is `cleartextTrafficPermitted="true"`; the two cloud-provider `<domain-config>` blocks (Anthropic, Gemini) remain `cleartextTrafficPermitted="false"`.
7. Privacy: `grep "Log\\." app/app/src/main/java/com/aikeyboard/app/ai/client/locallan/` returns only `Log.w` calls with `t.javaClass.simpleName` (no URLs, no body content, no `t.message`).

Dex invariants — verify with:

```bash
apkanalyzer dex packages app/app/build/outputs/apk/fdroid/release/ai-keyboard-3.9-fdroid-release.apk | grep "ai\.client\.locallan\|ai\.ui\.locallan"
apkanalyzer dex packages app/app/build/outputs/apk/play/release/ai-keyboard-3.9-play-release.apk | grep "ai\.client\.locallan\|ai\.ui\.locallan"
```

All seven classes in the table below must appear in BOTH outputs:

| Class | fdroid | play |
|---|---|---|
| `com.aikeyboard.app.ai.client.locallan.LocalLanBackend` | present | present |
| `com.aikeyboard.app.ai.client.locallan.OllamaStreamParser` | present | present |
| `com.aikeyboard.app.ai.client.locallan.OpenAiCompatStreamParser` | present | present |
| `com.aikeyboard.app.ai.client.locallan.LocalLanRequestBuilder` | present | present |
| `com.aikeyboard.app.ai.client.locallan.util.PublicIpValidator` | present | present |
| `com.aikeyboard.app.ai.ui.locallan.LocalLanEditScreenKt` | present | present |
| `com.aikeyboard.app.ai.ui.locallan.LocalLanEditRouteKt` | present | present |

Manifest invariants:

- ZERO new `<uses-permission>` entries in either flavor.
- ZERO new `<service>` / `<provider>` / `<receiver>` / `<activity>` entries in either flavor.
- `network_security_config.xml` `base-config` permits cleartext (deliberate; documented in Deviations).

Tests: 30+ new JVM unit tests pass; 6 pre-existing Robolectric SDK-36 failures unchanged.

Carry-overs unchanged (gesture lib, deprecated migrations, lint baseline 64 inherited entries, Phase 4 `ObsoleteSdkInt`, **Phase 12 polish list from PHASE_9b_SUMMARY.md** — including the WhatsApp validator failure, `BootReceiver` post-reboot issue, multi-pack creation UX gap, pack tab background contrast, and HeliBoard emoji-key gap). None of these block Phase 10.

---

## §19 — Smoke test scenarios (deferred to human reviewer)

Same precedent as Phase 5b/6/7a/7b/8/9a/9b. Phase 10 requires a real LAN setup with Ollama or LM Studio reachable from the device.

1. **Ollama happy path:** point at a local Ollama install on a PC at `http://<lan-ip>:11434`, select Ollama format, model name `llama3.2` (or whatever model is `ollama list`-ed), select Local LAN as Active for Rewrite. Type "draft a polite decline email" with Concise Editor persona, tap Rewrite. Tokens stream into the preview strip.
2. **OpenAI-compat happy path:** point at LM Studio's local server (default `http://<lan-ip>:1234`) in OpenAI mode, model name from LM Studio's loaded model. Repeat Rewrite. Tokens stream.
3. **Empty model name:** enter base URL but leave model empty → Save button disabled.
4. **Empty base URL:** enter model but leave base URL empty → Save button disabled.
5. **Public-IP warning:** enter `http://8.8.8.8:8080` → Save shows the warning dialog. Tap Cancel → URL not saved. Tap Save anyway → URL saved.
6. **Hostname URL:** enter `http://my-pi.local:11434` → Save proceeds without warning (hostname classified as `HOSTNAME`, treated as safe).
7. **API key path (Bearer):** with LM Studio configured to require auth, enter the key in the field. Verify request succeeds. Clear key → request returns 401 → user-facing `AUTH_FAILURE` error.
8. **Backend not running:** point at a port nothing is listening on → request fails with `NETWORK_FAILURE`.
9. **Backend strategy switching mid-conversation:** switch from REMOTE_API to LOCAL_LAN, repeat Rewrite — uses the new backend.
10. **Cleartext request actually goes through:** verify with `tcpdump` on the LAN host that the request arrived (NSC didn't block it).
11. **Privacy logcat:** run a session, grep `Log.` on the `locallan` package — only `t.javaClass.simpleName` and static structural strings should appear.

Document outcomes in `PHASE_10_SUMMARY.md`. If any test fails, fix and re-verify before claiming DoD.

---

## §20 — Open questions for human reviewer (carry into Phase 11 prompt)

1. **NSC permissive base-config — does this raise any F-Droid metadata flags?** F-Droid's index sometimes warns on `usesCleartextTraffic="true"`. Phase 12 release-prep should verify that `network_security_config.xml`'s permissive base-config doesn't trigger an F-Droid anti-feature flag (or, if it does, that the in-code guardrails are sufficient justification for an explicit anti-feature listing).
2. **Health check / "Test connection" button.** Phase 3a explicitly deferred this for `BackendEditScreen`; Phase 10 does the same. Phase 12 polish: add a "Test connection" button that fires a small request (e.g., `GET /api/tags` for Ollama, `GET /v1/models` for OpenAI-compat) and surfaces a green/red status. Defer to 12 unless smoke testing shows users get stuck at "configured but not actually reachable".
3. **mDNS / `.local` discovery.** Some users will type `pi.local` and expect it to work; others will type the static IP. The classifier handles both (HOSTNAME vs IP-literal), but actual mDNS resolution requires `android.permission.CHANGE_NETWORK_STATE` and explicit NSD setup. Out of scope for v1; if users complain, Phase 12 polish.
4. **Auto-detection between Ollama and OpenAI-compat formats.** Phase 10 makes the user pick. An auto-detect path (try `/api/tags`; if 200, Ollama; if 404, fall back to OpenAI-compat) is feasible but failure-prone across the long tail of self-hosted servers. Defer to Phase 12 if the radio choice produces support friction.
5. **IPv6 classification beyond loopback.** Phase 10's `PublicIpValidator` treats most IPv6 as `PUBLIC` conservatively. If users run Ollama on IPv6 (`fc00::/7` ULA, for instance), they get the warn-before-save dialog. Acceptable v1; Phase 12 can add full IPv6 classification.
6. **Ollama `keep_alive` parameter.** Ollama's `/api/chat` accepts an optional `keep_alive` field (e.g., `"5m"` to keep the model loaded for 5 minutes). Phase 10 omits it (server uses default). If users see slow first-token latency, Phase 12 can expose this as an advanced field.

---

## §21 — Coding-style invariants (carry-overs from prior phases)

- **Comments explain *why*, not *what*.** Delete narration that restates the code.
- **No new `Log.d` / `Log.w` calls that include URLs, request/response bodies, model names, or any `t.message`.** Use `t.javaClass.simpleName` and static structural strings only. Privacy invariant since Phase 7b.
- **No new `<uses-permission>` entries.** Phase 10 doesn't need any.
- **No backwards-compat shims** for unreleased states. The `localLanApiFormat: String?` nullable is a real back-compat hook for Phase 9b-and-earlier blobs; do NOT introduce migration code that "upgrades" old null values to "OLLAMA" eagerly. Keep the null → OLLAMA fallback in `getLocalLanApiFormat()` and let the value persist as null until the user explicitly saves.
- **Don't double-declare code blocks across prose snippets and structural sketches** (lesson from Phase 9b's `tabAdapter` duplication).
- **Don't list strings without a confirmed call site in the same prompt** (lesson from Phase 9b's four `UnusedResources` strings).
- **Prefer `androidx.core.graphics.scale` (`UseKtx` lint compliant).** Not directly applicable in Phase 10, but the rule stands for any future bitmap work.
- **Single-dispatch navigation:** when a `LaunchedEffect` can drive `onBack`, the action handler should NOT also call it directly (lesson from Phase 9b's `onDeletePack` cleanup).
- **R8 keep rule for single-call-site singleton-style classes:** `LocalLanBackend` has one call site (`BackendResolver`). The 9a/9b precedent (3-for-3) says expect-and-verify. The keep rule in §16 is pre-emptive; verify post-build that the class is actually present in both flavor release dexes via `apkanalyzer dex packages`.

---

## Handoff

When DoD holds, write `PHASE_10_SUMMARY.md` mirroring the Phase 9b summary's structure (sections: what was built, deviations, dex invariants, manifest invariants, builds/lint, tests, privacy invariants, smoke deferred, carry-overs, open questions, touchpoints for next phase). Commit on a new branch `phase/10-locallan-rfc1918`. Stop. Do not begin Phase 11.
