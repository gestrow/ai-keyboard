# Phase 3a Prompt — Storage Modernization + Provider Client Infrastructure

> **How to use this prompt:** open a fresh Claude Code session in `/Users/kacper/SDK/apk-dev/ai-keyboard/`. Tell it: *"Read `ARCHITECTURE.md`, `PHASE_REVIEW.md`, `PHASE_1_SUMMARY.md`, `PHASE_2_SUMMARY.md`, and `PHASE_2.5_SUMMARY.md`, then execute the prompt in `PHASE_3a_PROMPT.md` exactly. Stop when Phase 3a's Definition of Done holds. Do not begin Phase 3b."* Start fresh.

---

You are working in `/Users/kacper/SDK/apk-dev/ai-keyboard/`. Phases 1, 2, and 2.5 are complete. Read all the planning docs before any code work.

This is the largest infrastructure phase yet but produces **no end-user-visible AI behavior** — that's Phase 3b. Your job is to:

1. **Migrate `SecureStorage`** off the deprecated `EncryptedSharedPreferences` to a Tink-backed encrypted file, **preserving Phase 2 personas** for users who upgrade-install.
2. **Add API key CRUD** on the new storage.
3. **Refactor settings nav** to a hub-style start destination listing Personas / Keyboard / Backends.
4. **Build a `BackendsScreen`** Compose route with API key entry per provider.
5. **Declare the `AiClient` interface + `BackendStrategy` enum**, plus a `RemoteApiBackend` skeleton with HTTPS client (Ktor) wired up but no real call paths.
6. **Add `INTERNET` permission + HTTPS-only `networkSecurityConfig.xml`**.

Phase 3b will then implement the actual Anthropic + Gemini streaming and the user-facing Rewrite flow, on top of this foundation.

## Critical context from prior phases

- **Namespace:** new code under `com.aikeyboard.app.ai.*` (sibling to HeliBoard's `.latin.*`). `BuildConfig` and `R` import from `com.aikeyboard.app.latin`.
- **HeliBoard Compose deps already declared** — Material3, navigation-compose, lifecycle-viewmodel-compose. Don't add new Compose libs.
- **HeliBoard's settings (`com.aikeyboard.app.latin.settings.*`) and IME core (`.latin.LatinIME`, `.keyboard.MainKeyboardView`) are off-limits** — do not modify in this phase.
- **Phase 2's `SecureStorage`** lives at `com.aikeyboard.app.ai.storage.SecureStorage` and stores personas in `EncryptedSharedPreferences` under file `ai_keyboard_secure` (which produces `shared_prefs/ai_keyboard_secure.prefs.xml` on disk). Your migration reads from that file once.
- **Phase 2.5's `FirstRunDefaults`** at `com.aikeyboard.app.ai.setup.FirstRunDefaults` is the central place for first-run logic. The migration check belongs alongside or invoked from there — your call.
- **Phase 2.5's `KeyboardChromeScreen`** is currently reachable via top-app-bar action on `PersonaListScreen`. **That entry point goes away** — the new hub is the entry for everything.

## Tasks

### 1. Branch from Phase 2.5

```
git checkout phase/02.5-chrome-polish
git pull --ff-only
git checkout -b phase/03a-storage-and-backends-infra
```

### 2. Add dependencies (verify versions via context7 before pinning)

Use the context7 MCP tool to look up current stable versions for:

- `com.google.crypto.tink:tink-android` — Tink for Android (AEAD primitives + `AndroidKeysetManager`)
- `io.ktor:ktor-client-okhttp` — Ktor HTTP client engine
- `io.ktor:ktor-client-content-negotiation` — content negotiation plugin
- `io.ktor:ktor-serialization-kotlinx-json` — kotlinx.serialization integration

`androidx.security:security-crypto:1.1.0` is **already declared** from Phase 2; keep it for one-time migration, mark for removal in a comment.

`kotlinx.serialization` is already declared from Phase 2 — verify and reuse.

Pin all four new versions to specific stable releases. Document chosen versions in `PHASE_3a_SUMMARY.md`.

### 3. SecureStorage modernization

Rewrite `SecureStorage` (same package, same public API where possible) to use a single Tink-encrypted file.

**Storage location:** `context.filesDir/ai_keyboard_secure.bin` (app-private internal storage; encrypted ciphertext).

**Encryption:** Tink AEAD with AES-256-GCM. Master keyset wrapped by `android-keystore://ai_keyboard_master_key` via `AndroidKeysetManager`. Keyset metadata stored in `SharedPreferences` named `ai_keyboard_keyset_prefs`. Standard pattern from Tink's Android documentation.

**Data model** — single `@Serializable` envelope:

```kotlin
@Serializable
data class SecureData(
    val personas: List<Persona> = emptyList(),
    val activePersonaId: String? = null,
    val apiKeys: Map<String, String> = emptyMap(),  // provider name → key string
)
```

**Public API** (extend Phase 2's surface; do not break existing callers):

```kotlin
class SecureStorage private constructor(context: Context) {
    // existing — keep these
    fun getPersonas(): List<Persona>
    fun savePersona(persona: Persona)
    fun deletePersona(id: String)
    fun getActivePersonaId(): String
    fun setActivePersonaId(id: String)

    // new in 3a
    fun getApiKey(provider: Provider): String?
    fun saveApiKey(provider: Provider, key: String)
    fun deleteApiKey(provider: Provider)
    fun getConfiguredProviders(): Set<Provider>

    companion object {
        fun getInstance(context: Context): SecureStorage  // singleton, applicationContext
    }
}
```

`Provider` enum lives in a new file `com.aikeyboard.app.ai.client.Provider.kt`:

```kotlin
enum class Provider(val displayName: String) {
    ANTHROPIC("Anthropic Claude"),
    GOOGLE_GEMINI("Google Gemini");
    // OPENAI_CODEX, GROK come later
}
```

**Migration logic** — top of `SecureStorage` constructor or a separate `migrateFromEncryptedSharedPreferencesIfNeeded(context)` method:

```kotlin
@Suppress("DEPRECATION")
private fun migrateFromEncryptedSharedPreferencesIfNeeded(context: Context) {
    val oldPrefsFile = File(context.dataDir, "shared_prefs/ai_keyboard_secure.prefs.xml")
    val newFile = File(context.filesDir, "ai_keyboard_secure.bin")
    if (!oldPrefsFile.exists()) return  // fresh install — nothing to migrate
    if (newFile.exists()) return         // already migrated

    try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val oldPrefs = EncryptedSharedPreferences.create(
            context, "ai_keyboard_secure", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        val personasJson = oldPrefs.getString("personas_json", null)
        val activeId = oldPrefs.getString("active_persona_id", null)

        val personas = if (personasJson != null) {
            Json.decodeFromString<List<Persona>>(personasJson)
        } else emptyList()

        // Write new file FIRST, only delete old file after success
        writeEncryptedFile(SecureData(personas = personas, activePersonaId = activeId))
        oldPrefsFile.delete()
        Log.i(TAG, "Migrated SecureStorage from EncryptedSharedPreferences to Tink AEAD")
    } catch (e: Exception) {
        // Don't drop user data on migration failure: preserve old file so a future
        // launch can retry. User keeps an empty new store this run; old data still recoverable.
        Log.e(TAG, "SecureStorage migration failed; old file preserved", e)
    }
}
```

**Privacy invariant:** never log persona content, API keys, prompt text, or any user data. Migration logs only the count of personas migrated, never their fields.

**Tests** — add unit tests at `app/app/src/test/java/com/aikeyboard/app/ai/storage/SecureStorageTest.kt` for the JSON envelope round-trip and the API key CRUD. Tink itself doesn't test cleanly without instrumentation, so the migration path is verified manually via the smoke test, not unit-tested.

### 4. AiClient interface + BackendStrategy + sealed events

New package `com.aikeyboard.app.ai.client`:

```kotlin
// AiClient.kt
sealed interface AiClient {
    fun isAvailable(): Boolean
    fun rewrite(
        input: String,
        systemPrompt: String,
        fewShots: List<FewShot> = emptyList(),
    ): Flow<AiStreamEvent>
}

// BackendStrategy.kt
enum class BackendStrategy { REMOTE_API, LOCAL_LAN, TERMUX_BRIDGE }

// AiStreamEvent.kt
sealed interface AiStreamEvent {
    data class Delta(val text: String) : AiStreamEvent
    data object Done : AiStreamEvent
    data class Error(val type: ErrorType, val message: String) : AiStreamEvent
}

// ErrorType.kt
enum class ErrorType {
    NETWORK_FAILURE,
    AUTH_FAILURE,
    NO_API_KEY,
    RATE_LIMITED,
    TIMEOUT,
    NOT_IMPLEMENTED,   // returned by 3a stub
    UNKNOWN,
}
```

### 5. RemoteApiBackend skeleton

New file `com.aikeyboard.app.ai.client.remote.RemoteApiBackend.kt`:

- Implements `AiClient`
- Constructor takes a `Provider` and a reference to `SecureStorage` (for API key lookup)
- `isAvailable()` returns `true` if `secureStorage.getApiKey(provider) != null`
- **Request building is real** for both providers:
  - Anthropic: `POST https://api.anthropic.com/v1/messages` with headers `x-api-key`, `anthropic-version: 2023-06-01`, `content-type: application/json`. Body includes `model` (use `claude-sonnet-4-6` as default — but expose as `provider.defaultModel` so 3b can override), `system`, `messages: [{role:"user", content: input}]` plus prepended fewShots, `stream: true`, `max_tokens: 4096`.
  - Gemini: `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent?alt=sse&key={apiKey}`. Body: `{contents: [...], systemInstruction: {parts: [{text: systemPrompt}]}}`. Default model: `gemini-2.5-flash` (verify current naming via context7 if Google has updated since).
- **Response handling is the 3b deliverable.** `rewrite()` returns:

  ```kotlin
  override fun rewrite(...): Flow<AiStreamEvent> = flow {
      val key = secureStorage.getApiKey(provider)
      if (key == null) {
          emit(AiStreamEvent.Error(ErrorType.NO_API_KEY, "${provider.displayName} API key not configured"))
          return@flow
      }
      // 3b: actually make the request and stream the response.
      // Phase 3a stub:
      emit(AiStreamEvent.Error(ErrorType.NOT_IMPLEMENTED, "Streaming not implemented yet (Phase 3b)"))
  }
  ```

- The HTTP request-building code is fully implemented and unit-tested (verify the body shape matches both APIs by writing a test that calls a `buildAnthropicRequest(...)` / `buildGeminiRequest(...)` helper and asserts on the serialized JSON).
- `HttpClient` is a singleton in a `companion object` of `RemoteApiBackend`: Ktor `HttpClient(OkHttp) { install(ContentNegotiation) { json(...) } ; install(HttpTimeout) { ... } }`. 30s connect timeout, 60s socket timeout (long for streaming).

### 6. Hub-style settings nav refactor

Replace `PersonaListScreen` as start destination of `AiSettingsNavHost` with a new `SettingsHubScreen`:

- Title: "AI Keyboard Settings"
- Three rows with chevrons: Personas / Keyboard / Backends, each with a one-line description and an icon
- Tapping a row navigates to its dedicated route
- The Phase 2.5 top-app-bar `actions` slot on `PersonaListScreen` (the "advanced settings" gear that linked to `KeyboardChromeScreen`) is **removed** — that path now lives in the hub.

Routes after refactor:
- `hub` (start destination) → `SettingsHubScreen`
- `personas/list` → existing screen, unchanged interior
- `personas/edit/{id?}` → existing screen, unchanged interior
- `keyboard/chrome` → existing `KeyboardChromeScreen`, unchanged
- `backends/list` → new `BackendsScreen`
- `backends/edit/{provider}` → new `BackendEditScreen`

`PersonaListScreen` and `KeyboardChromeScreen` may need their `TopAppBar` updated to add a back-arrow that pops to `hub` (Compose nav handles this if you add a nav-up callback).

### 7. BackendsScreen + BackendEditScreen

`BackendsScreen` (Compose):
- Reads `SecureStorage.getConfiguredProviders()` on each composition
- Renders a list of all `Provider` enum values
- Each row: provider icon (use a HeliBoard existing drawable or add a simple vector for each), provider name, status text ("Configured" or "Not configured"), chevron
- Tap → navigates to `backends/edit/{provider}`

`BackendEditScreen` (Compose):
- Read `provider` from nav args
- Title: provider's `displayName` ("Anthropic Claude" / "Google Gemini")
- `OutlinedTextField` for API key:
  - `visualTransformation = PasswordVisualTransformation()` by default
  - Trailing icon button toggles password ↔ plain visibility
  - `keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false)`
- Initial value: `SecureStorage.getApiKey(provider)` (mask never displayed unmasked unless user toggles)
- "Save" button enabled when text is non-empty and changed; calls `SecureStorage.saveApiKey(provider, key)` then pops to backends list
- "Delete" button (text or outlined button, not destructive prominence) shown only when currently configured; calls `SecureStorage.deleteApiKey(provider)` then pops
- **No "Test connection" button** in 3a (that's 3b)
- Help text below the field: provider-specific link to where to get an API key (`https://console.anthropic.com/settings/keys` for Anthropic, `https://aistudio.google.com/apikey` for Gemini)

Strings live in `ai_strings.xml`.

### 8. Manifest + network security config

In `app/src/main/AndroidManifest.xml`, **before `<application>`**:
```xml
<uses-permission android:name="android.permission.INTERNET" />
```

Verify HeliBoard does not already declare it (per Phase 2's note). If they did, no duplicate needed.

Add `android:networkSecurityConfig="@xml/network_security_config"` to the `<application>` element.

Create `app/src/main/res/xml/network_security_config.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
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

Phase 4 will add a `127.0.0.1` cleartext domain-config; do not pre-empt it here.

### 9. Proguard keep rules

Append to `app/app/proguard-rules.pro` (existing Tink rules from Phase 2 already there):

```
# Ktor + OkHttp
-dontwarn io.ktor.**
-keep class io.ktor.client.engine.okhttp.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclasseswithmembers class * { @kotlinx.serialization.KSerializer <fields>; }
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclassmembers class ** { *** Companion; }
-keepclasseswithmembers class ** { kotlinx.serialization.KSerializer serializer(...); }
```

### 10. Smoke test (real device, both flavors)

The migration is the highest-risk change in this phase. Test it deliberately:

**Migration test (do NOT skip):**
1. Confirm the device currently has Phase 2.5 fdroid debug installed, with at least one custom persona (create one in settings if needed)
2. **`adb install -r`** (no uninstall) the new fdroid debug APK
3. Open keyboard → tap persona dropdown → verify the four built-ins **plus** any custom persona you created in Phase 2 are still listed
4. `adb shell run-as com.aikeyboard.app.debug ls files/` → `ai_keyboard_secure.bin` exists
5. `adb shell run-as com.aikeyboard.app.debug ls shared_prefs/` → `ai_keyboard_secure.prefs.xml` is **gone**
6. `adb shell run-as com.aikeyboard.app.debug cat files/ai_keyboard_secure.bin | xxd | head -5` → binary ciphertext, no readable persona names
7. `adb logcat -d | grep "Migrated SecureStorage"` → exactly one log line confirming the migration ran

**Fresh-install test:**
8. `adb uninstall com.aikeyboard.app.debug`, then `adb install` the new fdroid debug
9. Open keyboard → four built-in personas seeded; no `ai_keyboard_secure.prefs.xml` anywhere
10. Open settings → hub shows three rows (Personas / Keyboard / Backends) with descriptions
11. Navigate to Backends → both providers shown as "Not configured"
12. Tap Anthropic → enter `sk-ant-test-key-12345` → Save → returns to backends list with "Configured" status
13. Toggle the "show key" eye icon in the edit screen — verify masked vs plain state cycle
14. Force-stop app, reopen keyboard, navigate to Backends → Anthropic still "Configured" (key persisted)
15. Tap Anthropic → Delete → returns to backends list with "Not configured"
16. Tap Anthropic again → field is empty, no leftover state

**Network-traffic test:**
17. `adb shell dumpsys netstats detail | grep -A2 com.aikeyboard.app.debug` shows zero (or near-zero) bytes sent for the test session — Phase 3a should make NO network calls
18. The settings UI's "Save API key" path does not call any API — verify by intercepting with `adb shell settings put global http_proxy 192.0.2.0:9999` (an unreachable proxy) before testing — if Save still works without timeouts, no call was attempted

**Architecture invariants:**
19. `apkanalyzer apk packages app/build/outputs/apk/play/debug/ai-keyboard-3.9-play-debug.apk | grep -i accessibility` — empty (Phase 8 is when a11y arrives, and only in fdroid)
20. `apkanalyzer manifest print app/build/outputs/apk/fdroid/debug/ai-keyboard-3.9-fdroid-debug.apk | grep -E "permission|networkSecurityConfig"` — shows INTERNET permission and the network_security_config reference

**Both flavors:**
21. Repeat the fresh-install + network-traffic tests on the play flavor (`com.aikeyboard.app.play.debug`) after uninstalling+installing
22. Logcat clean of `FATAL` / `AndroidRuntime` referencing `com.aikeyboard.app` for both

### 11. Commit

Single commit on `phase/03a-storage-and-backends-infra`:

```
Phase 3a: storage modernization + provider client infrastructure

- SecureStorage migrated to Tink AEAD on a single encrypted file in filesDir
- Data-preserving migration from Phase 2's EncryptedSharedPreferences;
  old prefs file deleted only after successful new file write
- API key CRUD added; Provider enum (Anthropic, Google Gemini)
- Hub-style settings nav: SettingsHubScreen as start destination,
  Personas/Keyboard/Backends as sibling routes
- BackendsScreen + BackendEditScreen for masked API key entry
- AiClient interface + BackendStrategy + AiStreamEvent declared
- RemoteApiBackend skeleton with real HTTPS request builders for both
  Anthropic Messages and Gemini streamGenerateContent
- Ktor HTTP client (OkHttp engine) configured; proguard keep rules added
- INTERNET permission + HTTPS-only networkSecurityConfig
- No actual network calls; rewrite() stub emits NOT_IMPLEMENTED
- Both flavors build clean; smoke-tested on Pixel 6 Pro
```

Do not push, do not merge.

## Constraints

- **Do not** make any actual network calls. `RemoteApiBackend.rewrite()` returns the `NOT_IMPLEMENTED` stub.
- **Do not** add the streaming preview strip — that's Phase 3b.
- **Do not** wire the "Rewrite with AI" command-row button to anything real. The Phase 2 stub log stays.
- **Do not** modify the command row code (`com.aikeyboard.app.ai.commandrow.*`).
- **Do not** modify `LatinIME.java` or any HeliBoard keyboard-rendering code.
- **Do not** modify HeliBoard's settings package (`com.aikeyboard.app.latin.settings.*`).
- **Do not** add any AccessibilityService / Termux / a11y code.
- **Do not** edit `ARCHITECTURE.md` or `PHASE_REVIEW.md` — surface findings in the summary instead.
- **Do not** log API keys, persona content, prompt text, or any user data at any log level.
- **Do** preserve the `androidx.security:security-crypto` dependency for the migration; mark in a code comment that Phase 12 should remove it once we're confident no users have unmigrated state.

## Phase 3a summary template

When finished, write `PHASE_3a_SUMMARY.md` at repo root, under 60 lines:

```markdown
## Phase 3a Review (carried into Phase 3b prompt)

- **Pinned dependency versions:** <Tink, Ktor, Ktor content-negotiation, Ktor kotlinx-json — exact versions and why>
- **Storage layer:** <single Tink-encrypted file location, keyset wrapping mechanism, any deviations from prompt>
- **Migration result on test device:** <number of personas preserved, log line confirming, any edge cases hit>
- **Built:** <terse outcome>
- **Smoke test:** <results across all 22 steps>
- **Deviations from Phase 3a prompt:** <list, or "none">
- **Carried issues:** <list, or "none">
- **Touchpoints for Phase 3b:**
  - `app/app/src/main/java/com/aikeyboard/app/ai/client/remote/RemoteApiBackend.kt` — implement actual streaming here
  - `app/app/src/main/java/com/aikeyboard/app/ai/commandrow/CommandRowController.kt` — wire Rewrite button
  - `app/app/src/main/res/layout/main_keyboard_frame.xml` — preview strip insertion point
  - `app/app/src/main/java/com/aikeyboard/app/latin/LatinIME.java::onComputeInsets` — extend for preview strip height (same calculation Phase 2 introduced)
- **Open questions for human reviewer:** <items where you guessed and they should sanity-check>
```

## Definition of done

- `./gradlew assembleFdroidDebug assemblePlayDebug` both succeed
- `./gradlew lint` passes; `git diff lint-baseline.xml` empty
- All 22 smoke-test steps pass on Pixel 6 Pro `1B101FDEE0006U`
- Migration test step (#3): Phase 2 personas survive upgrade install
- Network-traffic test (#17): zero bytes sent in the entire test session
- `PHASE_3a_SUMMARY.md` exists at repo root, under 60 lines
- Single commit on `phase/03a-storage-and-backends-infra`, not pushed, not merged

Then stop. Do not begin Phase 3b.
