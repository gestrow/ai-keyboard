# Phase 7b — Read & Respond Wiring + Consent Flow

You are working on `phase/07b-read-respond-wiring`, branched from `phase/07a-screen-reader-service` (commit `29e2a19`). This phase wires the `Read & Respond` button on the IME command row to the AccessibilityService that Phase 7a deployed, builds the prompt, streams through `BackendResolver`, and renders into the existing preview strip — plus a one-time consent flow for the screen-content-leaves-device privacy decision.

Stop when this phase's Definition of Done holds. **Do not begin Phase 8.**

## Required reading

Before touching code, read in this order:
1. `ARCHITECTURE.md` — the locked architecture; Phase 7b row says "Read & Respond wiring + onboarding for restricted-settings gate."
2. `PHASE_REVIEW.md` — Phase 7b acceptance criteria.
3. `PHASE_7a_SUMMARY.md` — what Phase 7a shipped, including the singleton-handle pattern that Phase 7b's wiring depends on.
4. `PHASE_6_SUMMARY.md` — `BackendResolver.resolve(storage)` is Phase 7b's dispatch entry point.
5. `PHASE_5b_SUMMARY.md` — `TermuxOrchestrator` and the bridge backend exist and are reachable; nothing here changes that.
6. Read the existing `CommandRowController.onRewriteTap` body in `app/app/src/main/java/com/aikeyboard/app/ai/commandrow/CommandRowController.kt` — Phase 7b's `onReadRespondTap` mirrors its lifecycle (job cancellation, preview-strip streaming, error rendering).

## Goals

1. The IME's `Read & Respond` button, currently a placeholder log at [CommandRowController.kt:181](app/app/src/main/java/com/aikeyboard/app/ai/commandrow/CommandRowController.kt#L181), performs a full read-walk-stream-render cycle.
2. A flavor-split `A11yProxy` indirection lets `src/main/` code call into the Phase 7a service without referencing `ScreenReaderService` directly — removing the `BuildConfig.ENABLE_A11Y` guard's last reason to exist in `CommandRowController`.
3. A first-run consent flow surfaces the "screen content leaves your device" privacy implication before the first Read & Respond fires, persists the decision, and never re-prompts.
4. Service-not-enabled is surfaced as a soft failure that auto-opens System Settings → Accessibility, with a toast hint about the Android 14+ restricted-settings gate.
5. The play flavor's behavior is unchanged from Phase 7a: tap → toast "not supported in Play build."
6. Privacy invariant from Phase 7a holds in 7b: `aboveInputText` and `focusedInputText` are NEVER logged at any level, even on error paths.

## §1 — `A11yProxy` (split between fdroid + play source sets)

**Why it exists:** Phase 7a's `ScreenReaderService` lives in `src/fdroid/`. `CommandRowController` lives in `src/main/`. A direct import would not compile in the play flavor. Phase 7a worked around this with a `BuildConfig.ENABLE_A11Y` guard plus a `Log.d` placeholder. Phase 7b needs to actually call `requestScreenContext()`, so it routes through a flavor-specific proxy that's present in both source sets but only does real work in fdroid.

### Create `app/app/src/fdroid/java/com/aikeyboard/app/ai/a11y/A11yProxy.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.a11y

import com.aikeyboard.app.a11y.ScreenReaderService

/**
 * Phase 7b indirection: routes Read & Respond's screen-walk request from
 * `src/main/` code to Phase 7a's `ScreenReaderService` without leaking the
 * fdroid-only class name into shared code.
 *
 * The play flavor ships a no-op variant of this object that returns
 * BUILD_DOES_NOT_SUPPORT; the dex layer thus never references
 * ScreenReaderService in play APKs.
 */
object A11yProxy {
    fun requestScreenContext(): ScreenReaderResult {
        val service = ScreenReaderService.instance
            ?: return ScreenReaderResult.Failure.SERVICE_NOT_ENABLED
        return service.requestScreenContext()
    }
}
```

### Create `app/app/src/play/java/com/aikeyboard/app/ai/a11y/A11yProxy.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.a11y

/**
 * Phase 7b play-flavor stub. The play APK does not ship an
 * AccessibilityService (see ARCHITECTURE.md Phase 7a row), so all Read &
 * Respond invocations short-circuit here. Returning a typed Failure rather
 * than throwing means the caller's `when` can render a toast and exit
 * cleanly — no crash, no log of internal class names.
 */
object A11yProxy {
    fun requestScreenContext(): ScreenReaderResult =
        ScreenReaderResult.Failure.BUILD_DOES_NOT_SUPPORT
}
```

**Source-set selection:** AGP picks one `A11yProxy.kt` per build variant based on flavor. Both files share the same package and class name, so `src/main/` code imports `com.aikeyboard.app.ai.a11y.A11yProxy` and the right impl gets linked. Confirmed AGP behavior; no `expect`/`actual` or service-locator dance.

**Critical:** do NOT add an `A11yProxy.kt` to `src/main/`. AGP errors out with "duplicate class" if both main and a flavor source set declare the same FQN.

## §2 — `ReadRespondPromptBuilder` (src/main, JVM-pure)

### Create `app/app/src/main/java/com/aikeyboard/app/ai/a11y/ReadRespondPromptBuilder.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.a11y

import com.aikeyboard.app.ai.persona.Persona

/**
 * Pure prompt builder for Read & Respond. Takes a ScreenContext snapshot and
 * the user's typed-but-uncommitted hint, returns the (input, systemPrompt)
 * pair that gets fed to `AiClient.rewrite(...)`.
 *
 * Truncation: aboveInputText is truncated to the LAST `MAX_CONTEXT_CHARS`
 * characters before being inserted into the prompt. "Last" because in chat
 * UIs the most recent message is at the bottom of the visible area and is the
 * most relevant context for "respond to this." Truncation preserves message
 * boundaries when convenient (drop from the start of the truncated window
 * back to the next newline) but never re-orders content.
 *
 * Privacy: this object does no logging at all. The input strings flow
 * straight into the returned pair; the call site is responsible for keeping
 * those values out of logs.
 */
object ReadRespondPromptBuilder {

    const val MAX_CONTEXT_CHARS = 4_000

    /**
     * @param aboveInputText the screen content above the focused input (the
     *   "what we're responding to" context); must not be blank — caller must
     *   short-circuit with a toast before calling here if it is.
     * @param focusedInputText the user's current uncommitted text in the
     *   underlying app's input field, or empty if they haven't typed yet.
     * @param persona the active persona (system prompt + voice).
     * @return Pair(input, systemPrompt) for AiClient.rewrite. fewShots stays
     *   empty — Read & Respond is a one-shot generation, not a rewrite.
     */
    fun build(
        aboveInputText: String,
        focusedInputText: String,
        persona: Persona,
    ): Pair<String, String> {
        val context = truncateFromStart(aboveInputText, MAX_CONTEXT_CHARS)

        val input = buildString {
            append("I'm reading this on my screen:\n---\n")
            append(context)
            append("\n---\n\n")
            if (focusedInputText.isNotBlank()) {
                append("I've started typing my response: \"")
                append(focusedInputText)
                append("\"\n\n")
                append("Continue or rewrite my response in my voice. ")
            } else {
                append("Compose a response in my voice. ")
            }
            append("Don't repeat what's already visible above. ")
            append("Just the response text, ready to send.")
        }

        return input to persona.systemPrompt
    }

    /**
     * Keeps the LAST N chars (most recent screen content). If we truncate
     * mid-line, advance to the next `\n` to avoid a fragmented opening.
     */
    private fun truncateFromStart(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val tailStart = text.length - maxChars
        val newlineAfter = text.indexOf('\n', tailStart)
        return if (newlineAfter == -1 || newlineAfter == text.length - 1) {
            text.substring(tailStart)
        } else {
            text.substring(newlineAfter + 1)
        }
    }
}
```

## §3 — `SecureData` consent flag

### Edit `app/app/src/main/java/com/aikeyboard/app/ai/storage/SecureData.kt`

Add ONE field at the end of the data class:

```kotlin
    // Phase 7b: Read & Respond sends screen content to the active backend
    // (cloud or LAN/Termux). Default false — first Read & Respond tap shows
    // a consent activity and only flips to true on Accept. Persisting a
    // single boolean (not per-backend) is intentional: the user already
    // chose the backend in Settings → Backends; this flag captures the
    // separate "OK to send screen content to wherever I configured" decision.
    val readRespondConsented: Boolean = false,
```

Default-value handling via `kotlinx.serialization` covers back-compat — no schema bump.

### Edit `app/app/src/main/java/com/aikeyboard/app/ai/storage/SecureStorage.kt`

Add accessors, mirroring the existing `selectedBackendStrategy` accessor pattern:

```kotlin
    fun isReadRespondConsented(): Boolean =
        load().readRespondConsented

    fun setReadRespondConsented(consented: Boolean) {
        save(load().copy(readRespondConsented = consented))
    }
```

`load()` / `save()` are the existing private helpers used by every other accessor in this file (e.g. `setSelectedBackendStrategy` at line 150). Match their call style exactly — no concurrent-access primitives need to be added; the existing helpers handle synchronization.

## §4 — `ConsentActivity` (src/main, Compose)

### Create `app/app/src/main/java/com/aikeyboard/app/ai/a11y/ReadRespondConsentActivity.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.a11y

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aikeyboard.app.latin.R
import com.aikeyboard.app.ai.storage.SecureStorage

/**
 * Phase 7b first-run consent for Read & Respond. Shown the first time the
 * user taps the IME button while the service is enabled. On Accept,
 * persists `readRespondConsented = true`; on Decline, finishes without
 * persisting (so the next tap re-prompts).
 *
 * The user does NOT auto-resume into the Read & Respond flow on Accept —
 * they tap the keyboard button a second time, which now skips this
 * activity. One-time twoTaps is acceptable for a one-time consent.
 */
class ReadRespondConsentActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val storage = SecureStorage.getInstance(applicationContext)
        setContent {
            ConsentScreen(
                onAccept = {
                    storage.setReadRespondConsented(true)
                    finish()
                },
                onDecline = { finish() },
            )
        }
    }
}

@Composable
private fun ConsentScreen(onAccept: () -> Unit, onDecline: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                stringResource(R.string.ai_read_respond_consent_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.ai_read_respond_consent_body),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ai_read_respond_consent_accept))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onDecline, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ai_read_respond_consent_decline))
            }
        }
    }
}
```

### Register in `app/app/src/main/AndroidManifest.xml`

Add inside the existing `<application>` block, alongside the AI Settings activities:

```xml
        <activity
            android:name="com.aikeyboard.app.ai.a11y.ReadRespondConsentActivity"
            android:exported="false"
            android:launchMode="singleTop"
            android:theme="@style/platformActivityTheme" />
```

**Theme:** `@style/platformActivityTheme` is the project's standard activity theme (defined in `res/values/platform-theme.xml` / v31 variant), used by every existing Activity in the app including `AiSettingsActivity`. AppCompat is not a dependency — do not use any `Theme.AppCompat.*` style.

**`launchMode="singleTop"`:** required to prevent a double-tap race. `ime.startActivity(intent)` is asynchronous; without `singleTop`, two rapid taps on Read & Respond before the first consent activity finishes inflating could stack two consent screens.

## §5 — `CommandRowController.onReadRespondTap` wiring

### Edit `app/app/src/main/java/com/aikeyboard/app/ai/commandrow/CommandRowController.kt`

Replace the entire current `onReadRespondTap` body. The shape mirrors `onRewriteTap` but with a different input source. Drop the `BuildConfig.ENABLE_A11Y` guard — the proxy now handles flavor branching.

Required new imports at the top of the file:
```kotlin
import android.content.Intent
import android.provider.Settings
import com.aikeyboard.app.ai.a11y.A11yProxy
import com.aikeyboard.app.ai.a11y.ReadRespondConsentActivity
import com.aikeyboard.app.ai.a11y.ReadRespondPromptBuilder
import com.aikeyboard.app.ai.a11y.ScreenReaderResult
```

(Remove the now-unused `import com.aikeyboard.app.latin.BuildConfig` if Phase 7a's guard was its only consumer in this file.)

Replace the body:

```kotlin
    override fun onReadRespondTap() {
        // Walk first; consent and backend checks happen after we know there
        // IS something to read. Saves the user a consent prompt if they're
        // on a screen with no content above the cursor.
        val result = A11yProxy.requestScreenContext()
        when (result) {
            is ScreenReaderResult.Success -> handleSuccessfulWalk(result.context)
            ScreenReaderResult.Failure.SERVICE_NOT_ENABLED -> {
                toast(R.string.ai_read_respond_service_not_enabled)
                openAccessibilitySettings()
            }
            ScreenReaderResult.Failure.NO_ACTIVE_WINDOW ->
                toast(R.string.ai_read_respond_no_active_window)
            ScreenReaderResult.Failure.BUILD_DOES_NOT_SUPPORT ->
                toast(R.string.ai_read_respond_not_supported_play)
            ScreenReaderResult.Failure.UNKNOWN_FAILURE ->
                toast(R.string.ai_read_respond_walk_failed)
        }
    }

    private fun handleSuccessfulWalk(context: ScreenContext) {
        if (context.aboveInputText.isBlank()) {
            toast(R.string.ai_read_respond_no_context)
            return
        }
        if (!storage.isReadRespondConsented()) {
            launchConsentActivity()
            return
        }
        val backend = backendResolver()
        if (backend == null) {
            toast(R.string.ai_rewrite_no_backend)
            return
        }
        val persona = storage.getPersonas().firstOrNull { it.id == storage.getActivePersonaId() }
            ?: storage.getPersonas().first()
        val (input, systemPrompt) = ReadRespondPromptBuilder.build(
            aboveInputText = context.aboveInputText,
            focusedInputText = context.focusedInputText,
            persona = persona,
        )
        // Read & Respond replaces the entire field on commit (no selection-range
        // anchoring; Rewrite preserves selection, this doesn't).
        usedSelectionRange = null
        previewStrip.startStream()
        previewStrip.requestLayout()
        streamJob = scope.launch(Dispatchers.Main) {
            try {
                backend.rewrite(input, systemPrompt, fewShots = emptyList())
                    .onCompletion { /* preview already cleaned by cancel() */ }
                    .collect { event ->
                        when (event) {
                            is AiStreamEvent.Delta -> previewStrip.appendDelta(event.text)
                            AiStreamEvent.Done -> previewStrip.markDone()
                            is AiStreamEvent.Error -> previewStrip.showError(event.message)
                        }
                    }
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) {
                    // Log type only — never log `input` or `context.*` content.
                    Log.w(TAG, "Read & Respond stream failed: ${t.javaClass.simpleName}")
                    previewStrip.showError(
                        ime.getString(R.string.ai_read_respond_stream_failed)
                    )
                }
            }
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ime.startActivity(intent) }
            .onFailure {
                Log.w(TAG, "Could not open ACCESSIBILITY_SETTINGS: ${it.javaClass.simpleName}")
            }
    }

    private fun launchConsentActivity() {
        val intent = Intent(ime, ReadRespondConsentActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ime.startActivity(intent) }
            .onFailure {
                Log.w(TAG, "Could not launch consent activity: ${it.javaClass.simpleName}")
            }
    }
```

**Required additions:**
- Import `import com.aikeyboard.app.ai.a11y.ScreenContext`
- Use the existing `private const val TAG = "AiCommandRow"` constant (already declared in this file). Do NOT introduce a second `TAG` constant — it will not compile.
- The error-toast strings need to use `ime.getString(R.string.…)` because `previewStrip.showError(message: String)` takes a String not a resource ID — match the existing `onRewriteTap` calling convention exactly.

**Privacy note — DO NOT mirror `onRewriteTap`'s catch block verbatim:**

The new `onReadRespondTap` catch block above uses `Log.w(TAG, "Read & Respond stream failed: ${t.javaClass.simpleName}")` and `previewStrip.showError(ime.getString(R.string.ai_read_respond_stream_failed))` — the message string is a static localized resource, never `t.message`. This is INTENTIONALLY stricter than the existing `onRewriteTap` pattern, which currently does:

```kotlin
previewStrip.showError("Unexpected error: ${t.message ?: t.javaClass.simpleName}")
```

`t.message` for a network exception during a streaming response can include URL fragments, response-body content, or fragments of the prompt itself echoed back by the server. For Rewrite this is a known low-severity leak (the input was the user's own typed text). For Read & Respond it is higher-severity because the input is screen content the user did NOT type and may not realize is being processed. Do not copy-paste from `onRewriteTap` here — the new wording matters.

**Privacy assertion (verify by reading your own diff before commit):**
- No `Log.*` call in this file passes `aboveInputText`, `focusedInputText`, `context.nodes`, `input`, or any string derived from them. Only `t.javaClass.simpleName` and structural counts are loggable.

## §6 — Strings

### Add to `app/app/src/main/res/values/ai_strings.xml`

```xml
    <!-- Phase 7b: Read & Respond -->
    <string name="ai_read_respond_service_not_enabled">Enable AI Keyboard in Accessibility settings</string>
    <string name="ai_read_respond_settings_hint">If toggle is greyed out, tap ⋮ → Allow restricted settings</string>
    <string name="ai_read_respond_no_active_window">Open the app you want to respond to first</string>
    <string name="ai_read_respond_walk_failed">Couldn\'t read screen, try again</string>
    <string name="ai_read_respond_no_context">Nothing to respond to above your cursor</string>
    <string name="ai_read_respond_stream_failed">Read &amp; Respond failed</string>
    <string name="ai_read_respond_consent_title">Allow Read &amp; Respond?</string>
    <string name="ai_read_respond_consent_body">Read &amp; Respond reads what\'s currently visible on your screen and sends it to the AI backend you selected in Settings → Backends. Only happens when you tap the Read &amp; Respond button — never automatically.</string>
    <string name="ai_read_respond_consent_accept">Allow</string>
    <string name="ai_read_respond_consent_decline">Not now</string>
```

(`ai_read_respond_not_supported_play` already exists from Phase 7a — keep that one.)

**`ai_read_respond_settings_hint` wiring:** in the `SERVICE_NOT_ENABLED` branch of `onReadRespondTap`, fire two sequential toasts — first `R.string.ai_read_respond_service_not_enabled`, then `R.string.ai_read_respond_settings_hint`. Android queues toasts so the second appears after the first finishes (~2s), giving the user time to read the primary message before the restricted-settings hint surfaces. Concrete code:

```kotlin
ScreenReaderResult.Failure.SERVICE_NOT_ENABLED -> {
    toast(R.string.ai_read_respond_service_not_enabled)
    toast(R.string.ai_read_respond_settings_hint)
    openAccessibilitySettings()
}
```

## §7 — Tests (JVM)

### Create `app/app/src/test/java/com/aikeyboard/app/ai/a11y/ReadRespondPromptBuilderTest.kt`

Cover at minimum:
1. `aboveInputText` short, `focusedInputText` empty → "Compose a response" branch, no truncation.
2. `aboveInputText` short, `focusedInputText` non-empty → "Continue or rewrite" branch, focusedInputText quoted into prompt.
3. `aboveInputText` length > MAX_CONTEXT_CHARS → result starts at the next newline after the truncation point (verify by constructing input with a known newline at position `len - MAX_CONTEXT_CHARS + 50` and asserting the output begins after that position).
4. `aboveInputText` length > MAX_CONTEXT_CHARS with NO newline in the truncation window → result is the raw last MAX_CONTEXT_CHARS chars (no special truncation logic kicks in).
5. systemPrompt is passed through verbatim from the persona.

Use the existing test idiom (no MockK, no `kotlinx-coroutines-test`). Pure JVM unit tests — `assertEquals`, `assertTrue`.

### Optional: `CommandRowControllerReadRespondTest`

If the existing test plumbing makes mocking `A11yProxy` + `BackendResolver` + `IME` viable, add branch coverage for the five `when` cases in `onReadRespondTap`. If not (and Phase 7a didn't add a CommandRowController test), skip and rely on smoke. Document the choice in the summary.

## §8 — Smoke test (deferred to human reviewer)

Per the precedent of Phases 5b, 6, and 7a, on-device smoke is deferred. The summary should list these scenarios for the human reviewer to run before merge:

1. **Cross-app Read & Respond happy path:**
   - Open Messages (or any chat app), receive or open a thread with visible message text above the input.
   - Tap into the input field; switch to AI Keyboard fdroid IME.
   - Tap Read & Respond on the command row.
   - **Expected:** preview strip renders streaming text from the active backend; tapping commit replaces the input field's contents with the generated response.

2. **Service not enabled:**
   - Disable AI Keyboard Screen Reader in Accessibility settings.
   - Open any app, tap Read & Respond.
   - **Expected:** toast "Enable AI Keyboard in Accessibility settings", System Settings → Accessibility opens.

3. **First-run consent:**
   - Fresh install (or `adb shell pm clear com.aikeyboard.app.debug`), enable a11y service.
   - Tap Read & Respond.
   - **Expected:** ConsentActivity opens; tapping Allow returns to keyboard with no further action; tapping Read & Respond a second time fires the actual stream.

4. **Consent declined:**
   - Same setup, but tap Decline.
   - **Expected:** Activity finishes; tapping Read & Respond again re-shows the consent activity.

5. **No context above cursor:**
   - Open a fresh app screen with the input at the very top (e.g. a Compose new-message screen with no quoted reply).
   - Tap Read & Respond.
   - **Expected:** toast "Nothing to respond to above your cursor"; no consent prompt shown; no backend call.

6. **Play flavor unchanged:**
   - On the play-debug build, tap Read & Respond.
   - **Expected:** toast "not supported in Play build"; no a11y class referenced (verify via `apkanalyzer dex packages`).

7. **Privacy verification:**
   - With a11y service enabled, open `adb logcat -s ScreenReaderService:V CommandRowController:V` and run scenario 1.
   - **Expected:** no log line contains the message text from the chat thread, the user's typed hint, or any `aboveInputText`/`focusedInputText` content. Only structural metadata (node count, walk duration, error class names).

## §8 — Incidental fix: `onRewriteTap` privacy leak

While writing `onReadRespondTap`, you'll have read `onRewriteTap` (which is its architectural twin). The existing `onRewriteTap` catch block at [CommandRowController.kt:129](app/app/src/main/java/com/aikeyboard/app/ai/commandrow/CommandRowController.kt#L129) reads:

```kotlin
previewStrip.showError("Unexpected error: ${t.message ?: t.javaClass.simpleName}")
```

`t.message` for streaming network errors frequently echoes back URL fragments, response-body bytes, or content from the prompt itself. This is a privacy regression in Rewrite that's been present since Phase 2; Phase 7b's audit surfaces it. **Same root cause as the Phase 6 incidental fix to `RemoteApiBackend.kt:95` (catch-order bug); same pattern of "we noticed it during this phase, fix it now rather than carrying it forward."**

Replace with:

```kotlin
previewStrip.showError(ime.getString(R.string.ai_rewrite_stream_failed))
Log.w(TAG, "Rewrite stream failed: ${t.javaClass.simpleName}")
```

Add the new string to `ai_strings.xml`:

```xml
<string name="ai_rewrite_stream_failed">Rewrite failed</string>
```

Two-line code change + one new string. Mandatory part of Phase 7b — call it out in the summary's "Incidental fixes" section.

## §8.5 — Proguard keep rule for `A11yProxy`

R8 will inline `A11yProxy.requestScreenContext()` at the `CommandRowController` call site (it's a one-line `object` method) and eliminate the class entirely from the dex output. This breaks two invariants:

1. The **play-flavor** dex invariant — the play `A11yProxy` is supposed to be observable as a no-op, not inlined into `CommandRowController` (which is in `src/main/`). If R8 inlines, the `BUILD_DOES_NOT_SUPPORT` enum constant gets folded into `CommandRowController`'s bytecode and the proxy class disappears. That breaks the dex-invariant assertion in §9.
2. The **fdroid-flavor** invariant — the fdroid `A11yProxy` references `ScreenReaderService.instance`. If R8 inlines, that reference moves from `A11yProxy.requestScreenContext()` into `CommandRowController.onReadRespondTap()`. The Phase 7a invariant (`ScreenReaderService` only reached via the proxy boundary) gets blurred.

Phase 6 hit this exact pattern with `BackendResolver` and added a keep rule. Phase 7b needs the same. Add to `app/app/proguard-rules.pro`, alongside the existing `BackendResolver` rule:

```proguard
# Phase 7b: keep A11yProxy in both flavors so the play dex invariant
# (no ScreenReaderService reference) and the fdroid dex invariant
# (proxy is the boundary, not inlined into CommandRowController) both hold.
-keep class com.aikeyboard.app.ai.a11y.A11yProxy { *; }
```

Verify after build: `apkanalyzer dex packages app-play-release-unsigned.apk | grep A11yProxy` should print one line for the play impl. Same on fdroid.

## §9 — Build / lint / dex invariants

Run and confirm clean before declaring DoD:

```bash
cd /Users/kacper/SDK/apk-dev/ai-keyboard/app

./gradlew assembleFdroidDebug assemblePlayDebug \
          assembleFdroidRelease assemblePlayRelease

./gradlew lintFdroidDebug lintPlayDebug
git diff app/app/lint-baseline.xml  # should be empty

./gradlew :app:testFdroidDebugUnitTest :app:testPlayDebugUnitTest
```

**Dex invariants** (use `apkanalyzer` from `$ANDROID_HOME/cmdline-tools/latest/bin/`):

| APK | Must contain | Must NOT contain |
|---|---|---|
| `app-fdroid-release-unsigned.apk` | `A11yProxy` (fdroid impl), `ScreenReaderService`, `ReadRespondPromptBuilder`, `ReadRespondConsentActivity` | — |
| `app-play-release-unsigned.apk` | `A11yProxy` (play impl, no-op), `ReadRespondPromptBuilder`, `ReadRespondConsentActivity` | `ScreenReaderService`, `com.aikeyboard.app.a11y.*` |

Phase 7a's invariant ("zero `com.aikeyboard.app.a11y.*` in play APK") still holds: the play `A11yProxy` lives in `com.aikeyboard.app.ai.a11y` (note the `.ai.` segment), not the fdroid-only `com.aikeyboard.app.a11y` package.

**Manifest invariants** (use `apkanalyzer manifest print`):
- play APK: zero `<service>` entries with `android.accessibilityservice.AccessibilityService` action (unchanged from 7a).
- both APKs: `<activity name=".ai.a11y.ReadRespondConsentActivity" android:exported="false" />` present.

## Definition of Done

- [ ] `A11yProxy.kt` exists in BOTH `src/fdroid/` and `src/play/`, neither in `src/main/`.
- [ ] `ReadRespondPromptBuilder.kt` exists in `src/main/`, fully tested.
- [ ] `ReadRespondConsentActivity.kt` exists in `src/main/` with a manifest entry (`exported="false"`, `launchMode="singleTop"`, `theme="@style/platformActivityTheme"`).
- [ ] `SecureData` has `readRespondConsented: Boolean = false`; `SecureStorage` exposes get/set.
- [ ] `CommandRowController.onReadRespondTap` no longer references `BuildConfig.ENABLE_A11Y` — branching is via `A11yProxy` + `when`.
- [ ] All five `ScreenReaderResult` branches handled with localized toasts.
- [ ] Successful walk + consent → backend stream rendered into preview strip via existing flow.
- [ ] Proguard keep rule for `A11yProxy` is in `proguard-rules.pro` alongside the existing `BackendResolver` rule.
- [ ] **Incidental fix:** `onRewriteTap` catch block uses `ime.getString(R.string.ai_rewrite_stream_failed)` + `Log.w(TAG, "Rewrite stream failed: ${t.javaClass.simpleName}")`; the new string is in `ai_strings.xml`. The pre-existing `t.message` leak is gone.
- [ ] All four flavor/buildtype APKs assemble clean.
- [ ] `lintFdroidDebug lintPlayDebug` clean; `git diff app/app/lint-baseline.xml` empty.
- [ ] Dex invariants in §9 hold (including `apkanalyzer dex packages` showing `A11yProxy` in BOTH play and fdroid release APKs).
- [ ] Manifest invariants in §9 hold.
- [ ] All prior 52+ AI-module unit tests still pass; new `ReadRespondPromptBuilderTest` adds at least 5.
- [ ] No `Log.*` call in `CommandRowController` or `ReadRespondPromptBuilder` accepts `aboveInputText`, `focusedInputText`, `nodes`, `input`, or `t.message` as an argument. Verify via `git diff` before commit.
- [ ] On-device smoke test: deferred to human reviewer per precedent. Summary lists the seven scenarios above.

## Open questions to resolve at end of phase

In your `PHASE_7b_SUMMARY.md` (≤50 lines, mirroring 7a), surface:

1. **Two-tap UX on first use** — user taps Read & Respond → consent → tap again to actually fire. Acceptable, or worth deep-linking back into the IME via a callback? My read: acceptable. Cross-process callback from an Activity → IME is non-trivial; one-time friction is fine for a one-time consent.

2. **No deep-link to specific service in `Settings.ACTION_ACCESSIBILITY_SETTINGS`** — user lands on the main Accessibility list and scrolls. OEM-specific deep-link extras exist on some Pixels (`Settings.EXTRA_ACCESSIBILITY_SERVICE_COMPONENT_NAME`) but are not stable. Pursue in Phase 12 polish, or accept here?

3. **`focusedInputText` redundant with IME's input-connection compose buffer** — Phase 7b uses the value from the screen walk, not from `ime.currentInputConnection`. Both should match, but is there a corner case (composing text mid-prediction) where they diverge? If it matters, switch to `ic.getTextBeforeCursor` + `getTextAfterCursor` like `onRewriteTap` does. My read: ship the screen-walk value and revisit only if smoke testing shows divergence.

4. **`readRespondConsented` is a single boolean, not per-backend** — switching from a cloud backend to a Termux backend doesn't re-prompt. Acceptable, or should consent reset when `selectedBackendStrategy` changes? My read: a single flag is fine because the consent body explicitly mentions "the AI backend you selected in Settings → Backends" — the user has full visibility into where data is going. Per-backend consent is over-engineering.

## Hand-off

After Phase 7b's DoD holds, commit on `phase/07b-read-respond-wiring` with a single commit message summarizing the change. Do NOT push, do NOT merge. Write `PHASE_7b_SUMMARY.md` in repo root, ≤50 lines, structured like `PHASE_7a_SUMMARY.md`. Stop. Phase 8 (always-on a11y + foreground service) is the next planner-prompt and will be drafted separately.
