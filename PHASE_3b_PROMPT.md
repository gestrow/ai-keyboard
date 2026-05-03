# Phase 3b Prompt — End-to-End Rewrite (Anthropic + Gemini Streaming + Preview Strip)

> **How to use this prompt:** open a fresh Claude Code session in `/Users/kacper/SDK/apk-dev/ai-keyboard/`. Tell it: *"Read `ARCHITECTURE.md`, `PHASE_REVIEW.md`, `PHASE_1_SUMMARY.md`, `PHASE_2_SUMMARY.md`, `PHASE_2.5_SUMMARY.md`, and `PHASE_3a_SUMMARY.md`, then execute the prompt in `PHASE_3b_PROMPT.md` exactly. Stop when Phase 3b's Definition of Done holds. Do not begin Phase 4."* Start fresh.

---

You are working in `/Users/kacper/SDK/apk-dev/ai-keyboard/`. Phases 1, 2, 2.5, and 3a are complete. Read all planning + summary docs before any code work.

This phase is the user-visible payoff: typing becomes "type a draft, tap Rewrite, watch tokens stream into a preview strip, tap to commit." Two providers (Anthropic + Gemini), one streaming preview surface, one command-row button wired to the active persona + selected backend.

## Critical context from prior phases

- **Namespace:** new code under `com.aikeyboard.app.ai.*` (sibling to HeliBoard's `.latin.*`); `BuildConfig` and `R` import from `com.aikeyboard.app.latin`.
- **Phase 3a built the foundation:** `AiClient` interface, `BackendStrategy` enum, `AiStreamEvent` sealed events (`Delta` / `Done` / `Error`), `ErrorType` enum, `RemoteApiBackend` skeleton with **real request builders** for both providers and a singleton `HttpClient` (Ktor + OkHttp). `RemoteApiBackend.rewrite()` currently emits `AiStreamEvent.Error(NOT_IMPLEMENTED)`. Your job is to fill in the actual streaming response handling.
- **Phase 2.5 codified two keyboard-surface UI invariants** in `PHASE_REVIEW.md`. They apply to the new preview strip: (1) source colors from runtime `Settings.getValues().mColors`, (2) extend `LatinIME.onComputeInsets` for any view added above HeliBoard's `strip_container`. Phase 2 already extended `onComputeInsets` for the command row; you extend the same calculation again for the preview strip.
- **Active persona** is read from `SecureStorage.getActivePersonaId()` then looked up in `getPersonas()`. Pass its `systemPrompt` and `fewShots` to `AiClient.rewrite(...)`.
- **Active backend strategy** is currently implicit (only REMOTE_API exists). For Phase 3b, hardcode `REMOTE_API` as the only option; the strategy switcher arrives in Phase 6.
- **Provider selection within REMOTE_API** — there's no "active provider" pref yet. Add one: simple int/string in `SecureStorage` or a new dedicated pref, default to `Provider.ANTHROPIC` if both are configured, fall back to whichever has an API key. UI to switch providers manually arrives in Phase 6 (the Backends settings can show a "default" badge for now if you want, optional).
- **Phase 3a's command row** has 5 buttons: persona selector, AI mode toggle, Read & Respond, Sticker, Settings gear. The "AI mode toggle" was always semantically vague (Phase 2 said "in-memory boolean, no persistence yet"). **Phase 3b repurposes it as the Rewrite button** — same slot in the command row, new icon (sparkle/wand or similar), new behavior: tap = rewrite. This avoids debating layout and lets us drop the never-defined "AI mode" concept. If you have a strong reason to add a 6th button instead, do so and document in the summary.

## Tasks

### 1. Branch from Phase 3a

```
git checkout phase/03a-storage-and-backends-infra
git pull --ff-only
git checkout -b phase/03b-streaming-rewrite
```

### 2. Anthropic Messages SSE streaming

Implement in `RemoteApiBackend.rewrite()` for `Provider.ANTHROPIC`. Use Ktor's response body channel reading; you can use Ktor's SSE plugin if Ktor 3.x has it stable, or read the body line-by-line manually (simpler, fewer deps).

**Endpoint:** `POST https://api.anthropic.com/v1/messages` (request builder already implemented in 3a)

**Response format:** `text/event-stream` with named events. Each event is two lines:
```
event: <name>
data: {<json>}
```
followed by a blank line. The events you care about:

- `event: message_start` — initial event, contains `message` object with metadata; emit nothing
- `event: content_block_start` — start of a content block (usually one text block); emit nothing
- `event: content_block_delta` — the data payload is `{"type":"text_delta", "delta": {"type":"text_delta","text":"the text chunk"}}`. **Emit `AiStreamEvent.Delta(text)`** for each.
- `event: content_block_stop` — end of a content block; emit nothing
- `event: message_delta` — usage stats / stop reason; emit nothing
- `event: message_stop` — terminal event. **Emit `AiStreamEvent.Done` and complete the flow.**
- `event: error` — error from the API mid-stream. Map to `AiStreamEvent.Error(...)` with appropriate `ErrorType` (typically `RATE_LIMITED` if the inner type is `overloaded_error` or `rate_limit_error`, else `UNKNOWN`).
- `event: ping` — keepalive; ignore

**Parsing:** simple state machine reading lines. Each event terminates on a blank line. Be defensive about malformed JSON in `data:` (don't crash; emit `AiStreamEvent.Error(UNKNOWN, ...)` and complete).

**Pre-stream HTTP error mapping:**
- 401 / 403 → `ErrorType.AUTH_FAILURE` ("Anthropic API key invalid or expired")
- 429 → `ErrorType.RATE_LIMITED` ("Anthropic rate limit hit; try again in a moment")
- 5xx → `ErrorType.NETWORK_FAILURE` ("Anthropic service unavailable; try again")
- Network/IO exception → `ErrorType.NETWORK_FAILURE` ("Network error: $message")
- `kotlinx.coroutines.TimeoutCancellationException` (from Ktor's HttpTimeout) → `ErrorType.TIMEOUT` ("Request timed out")
- Anything else → `ErrorType.UNKNOWN`

Use `claude-sonnet-4-6` as the default model. (If Anthropic deprecates or renames, verify via context7.)

### 3. Gemini streamGenerateContent SSE streaming

**Endpoint:** `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent?alt=sse&key={apiKey}` (request builder already implemented in 3a). Use `gemini-2.5-flash` as the default model unless context7 lookup shows a current-stable replacement.

**Response format:** `text/event-stream`, but Google does NOT use `event:` lines — only `data:` lines, one JSON object per event:

```
data: {"candidates":[{"content":{"parts":[{"text":"first chunk"}],"role":"model"},"finishReason":null}]}

data: {"candidates":[{"content":{"parts":[{"text":" second chunk"}],"role":"model"},"finishReason":null}]}

data: {"candidates":[{"content":{"parts":[{"text":""}],"role":"model"},"finishReason":"STOP"}]}
```

For each event: parse JSON, walk `candidates[0].content.parts[0].text`, **emit `AiStreamEvent.Delta(text)`** if non-empty. When `candidates[0].finishReason != null`, **emit `AiStreamEvent.Done` and complete**.

`finishReason` values to handle:
- `"STOP"` — normal completion → `Done`
- `"MAX_TOKENS"` — truncated → `Done` (emit a brief notice as a final delta? optional; not required)
- `"SAFETY"` / `"RECITATION"` / `"BLOCKED"` → `AiStreamEvent.Error(UNKNOWN, "Response blocked by Gemini safety filter")`
- `"OTHER"` / unrecognized → `Error(UNKNOWN, ...)`

**HTTP error mapping** — same shape as Anthropic, but Gemini's 401 returns a JSON body with `error.message` you can surface in the user-facing string for clarity.

Be defensive: Gemini sometimes sends prompt-feedback events without candidates (when input was blocked at the prompt level). If `candidates` is missing or empty AND `promptFeedback.blockReason` is set, emit `AiStreamEvent.Error(UNKNOWN, "Input blocked by Gemini safety filter")`.

### 4. PreviewStripView (Kotlin View)

Create `app/app/src/main/java/com/aikeyboard/app/ai/preview/PreviewStripView.kt`.

A horizontal `LinearLayout` with:
- A `TextView` (`weight=1`, `ellipsize=end`, `singleLine=false` but `maxLines=2`) — the streaming content. Style: 14sp, padding 8dp.
- A small `TextView` to the right showing "tap to commit" hint when complete, in a subtle color (lower-alpha text). Hidden during streaming.
- An `ImageButton` (X icon, 24dp) to cancel the stream. Hidden when stream is `Done`.

Background: `Settings.getValues().mColors.get(ColorType.STRIP_BACKGROUND)`.
Text color: `KEY_TEXT`. Secondary text (hint): `KEY_HINT_LETTER` if available, else 50% alpha of `KEY_TEXT`.
Error text color: `Color.RED` or whatever's most legible against `STRIP_BACKGROUND` — pick one and document.

State machine — expose these methods:
```kotlin
class PreviewStripView : LinearLayout {
    interface Listener {
        fun onCommitTap(text: String)
        fun onCancelTap()
    }
    var listener: Listener? = null

    fun startStream()                        // visibility = VISIBLE, clears text, shows cancel button, hides hint
    fun appendDelta(text: String)            // appends to TextView
    fun markDone()                           // hides cancel, shows "tap to commit" hint
    fun showError(message: String)           // visibility = VISIBLE, shows red text, no commit-on-tap
    fun hide()                               // visibility = GONE; clears state
}
```

Tap on the TextView area while in `Done` state → calls `listener.onCommitTap(currentText)`.
Tap on the X button at any time → calls `listener.onCancelTap()` and hides.

Apply HeliBoard's runtime colors in an `init {}` block / `applyKeyboardTheme()` method, same pattern Phase 2's `CommandRowView` used.

### 5. Wire PreviewStripView into the keyboard layout

Edit `app/app/src/main/res/layout/main_keyboard_frame.xml` — add **between** the existing command row (`@id/ai_command_row`) and `@id/strip_container`:

```xml
<com.aikeyboard.app.ai.preview.PreviewStripView
    android:id="@+id/ai_preview_strip"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:visibility="gone" />
```

This is the **only** edit to this file.

### 6. Extend `LatinIME.onComputeInsets`

Phase 2 added a `commandRowHeight` term to the `visibleTopY` calculation. Phase 3b adds a `previewStripHeight` term using the same pattern. Read the Phase 2 summary's onComputeInsets diff first to mirror its style (same helper method, same null-guarded layout lookup). Increment the touchable region by the preview strip's height when its visibility is VISIBLE.

Pseudo-pattern:
```java
View previewStrip = mInputView.findViewById(R.id.ai_preview_strip);
int previewStripHeight = (previewStrip != null && previewStrip.getVisibility() == View.VISIBLE)
        ? previewStrip.getHeight()
        : 0;
visibleTopY -= previewStripHeight;
```

Crucially: `onComputeInsets` is called frequently. The lookup-by-id should be cached or guarded against null (Phase 2's helper likely already caches the command row). Match the existing pattern.

Verify the touchable region update fires when the preview strip's visibility changes. If `LatinIME` doesn't auto-call `updateInsets()` on visibility change, you may need to call it explicitly when toggling preview strip visibility. (Test: tap the preview strip's top edge while streaming — keyboard should NOT dismiss.)

### 7. Repurpose the AI toggle as the Rewrite button

In `app/app/src/main/res/layout/command_row.xml`:
- Rename `@+id/cmd_ai_toggle` to `@+id/cmd_rewrite` (search-replace across the codebase for the old id)
- Replace the toggle's icon (`ic_ai_toggle` / `ic_ai_toggle_on`) with a single `ic_rewrite` vector drawable — a sparkle, wand, or magic-wand-with-text-line icon. 24dp, suitable for tinting via `Colors.get(ColorType.TOOL_BAR_KEY)`.
- Update its `contentDescription` string in `ai_strings.xml` from "Toggle AI mode" to "Rewrite with AI".

Delete `ic_ai_toggle.xml` and `ic_ai_toggle_on.xml` (no longer used).

In `CommandRowView.kt` and `CommandRowController.kt`:
- Rename `onAiToggleTap()` to `onRewriteTap()` everywhere (Listener interface, controller impl, click bindings)
- Remove the old "in-memory AI mode boolean" state — there's no toggle behavior anymore; tap = action

Document the rename and the dropped "AI mode" concept in the summary.

### 8. Wire Rewrite end-to-end in CommandRowController

`onRewriteTap()` flow:

1. Get `InputConnection` from `LatinIME` (the controller already has access via the IME service reference Phase 2 wired).
2. Read current input text:
   - If a selection is active (`getSelectedText(...)` returns non-empty), use the selected text and remember the selection range
   - Otherwise, concatenate `getTextBeforeCursor(MAX, 0)` + `getTextAfterCursor(MAX, 0)` and remember the entire field range. `MAX` = something reasonable like 8000 chars (capped because some apps have huge text fields and we don't want to ship a novel to the LLM).
   - If the resulting text is empty, show a brief toast ("Nothing to rewrite") and return.
3. Look up active persona via `SecureStorage.getActivePersonaId()` → `getPersonas().find {...}`. If somehow null, fall back to the `Default` persona.
4. Determine selected provider: read from `SecureStorage` (you'll add a `getSelectedProvider()` / `setSelectedProvider()` for this — initial default = `ANTHROPIC` if it has a key, else `GOOGLE_GEMINI`, else null).
5. If no provider has a key configured, show a toast ("No AI backend configured — open Settings → Backends") and return; do not start a stream.
6. Construct `RemoteApiBackend(provider, secureStorage)` (or get from a singleton if you've added one).
7. Call `previewStripView.startStream()`, then `LatinIME.updateInsets()` (or whatever invalidates the touchable region) so the strip's area becomes touchable immediately.
8. Launch a coroutine in a controller-owned `CoroutineScope` (use `MainScope()` or a `SupervisorJob`-rooted scope; cancel on controller dispose):
   ```kotlin
   currentStreamJob = scope.launch {
       backend.rewrite(input, persona.systemPrompt, persona.fewShots)
           .onCompletion { if (it is CancellationException) previewStripView.hide() }
           .collect { event ->
               when (event) {
                   is Delta -> previewStripView.appendDelta(event.text)
                   Done -> previewStripView.markDone()
                   is Error -> previewStripView.showError(event.message)
               }
           }
   }
   ```
9. `previewStripView.listener = ...`:
   - `onCommitTap(text)`: if a selection was active originally, replace it via `setComposingRegion` + `commitText`; otherwise replace the entire field (`deleteSurroundingText(MAX, MAX)` + `commitText(text, 1)`). Hide preview strip; null out `currentStreamJob`.
   - `onCancelTap()`: `currentStreamJob?.cancel()`; hide preview strip.

### 9. Cancel-on-typing

Hook into `LatinIME`'s key event flow OR use the controller's existing wiring:
- When any character key is pressed while `currentStreamJob != null && currentStreamJob.isActive`, cancel the job and hide the preview strip.
- Implementation: easiest is a hook in `LatinIME.onUpdateSelection` or wherever HeliBoard processes key events; broadcast/notify `CommandRowController` to cancel.
- Alternative: subscribe to `InputConnection`'s `setComposingText` calls from the controller (less clean; HeliBoard's autocorrect uses composing regions extensively).

Pick the implementation that touches the least HeliBoard code. Document choice in the summary.

### 10. Smoke test (real device, both flavors)

**Setup** — needs a real Anthropic API key OR a real Gemini API key. If you don't have one, ask the human reviewer to provide one for the test session; do not commit it. Use a throwaway/test key if possible.

```
cd app
./gradlew assembleFdroidDebug assemblePlayDebug
adb -s 1B101FDEE0006U install -r app/build/outputs/apk/fdroid/debug/ai-keyboard-3.9-fdroid-debug.apk
```

**On device** — clean tests:

1. Open settings → Backends → enter the API key for the provider you have, Save
2. Open any text field (Notes, Messages), type "draft email about being late to the meeting"
3. Tap the Rewrite button (was the AI toggle slot) → tokens stream into the preview strip in real-time
4. **Tap the preview strip text** when "tap to commit" hint shows → input field text is replaced with the rewrite
5. Try with a different persona (Esquire, Flirty) → output style differs
6. Test the OTHER provider if you have both keys (or skip, document)
7. **Cancel-on-typing test:** start a Rewrite, then immediately type any character → stream cancels, preview hides, the character you typed appears as expected
8. **Cancel-button test:** start a Rewrite, tap the X on the preview strip → stream cancels
9. **Disable network mid-stream:** start a Rewrite, then `adb shell svc wifi disable && adb shell svc data disable` after a few chunks → preview strip shows network error message in red, keyboard remains usable
10. **No API key test:** delete the configured API key in BackendsScreen, attempt Rewrite → toast says "No AI backend configured" and a stream is NOT started (verify with `dumpsys netstats`)
11. **Selection test:** select a portion of text in a notes app, then Rewrite → only the selection is rewritten and replaced
12. **Touchable region test:** while streaming, tap at the very top edge of the preview strip → the X (cancel) registers; the keyboard does NOT dismiss
13. **Empty text test:** tap Rewrite with an empty text field → toast "Nothing to rewrite", no API call

**Logcat clean checks:**

14. `adb logcat -d | grep -E "FATAL|AndroidRuntime|com.aikeyboard.app"` → no FATAL exceptions
15. `adb logcat -d | grep -i "draft email\|rewrite output\|api key"` → **empty** (privacy invariant: no user input/output/keys logged)

**Both flavors:**

16. Repeat steps 2, 3, 4 on the play flavor (`com.aikeyboard.app.play.debug`)

**Architecture invariants:**

17. `apkanalyzer manifest print app/build/outputs/apk/play/debug/ai-keyboard-3.9-play-debug.apk | grep -i accessibility` → empty (Phase 8 territory)
18. Visual check: preview strip's background blends with the suggestion strip across at least two distinct HeliBoard themes (try Light + Dark Material You) — confirms runtime `Colors` invariant is honored

### 11. Commit

Single commit on `phase/03b-streaming-rewrite`:

```
Phase 3b: end-to-end Rewrite — Anthropic + Gemini streaming + preview strip

- Anthropic Messages SSE streaming: parses content_block_delta events,
  surfaces auth/rate-limit/network errors with distinct user messages
- Gemini streamGenerateContent SSE streaming: parses chunked candidates,
  handles safety/recitation block reasons, surfaces prompt-feedback errors
- New PreviewStripView between command row and HeliBoard's strip_container;
  hidden by default, shows during streams, tap-to-commit, X to cancel
- LatinIME.onComputeInsets extended (same pattern as Phase 2's command-row
  patch) to include preview strip height in the touchable region when visible
- Repurposed the AI toggle slot as the Rewrite button (sparkle icon);
  the never-defined "AI mode" concept is dropped
- Cancel-on-typing: any key press while a stream is active cancels it
- All errors surface to the user via the preview strip; no silent failures
- Privacy invariant maintained: zero text content logged
- Both flavors build clean; smoke-tested on Pixel 6 Pro
```

Do not push, do not merge.

## Constraints

- **Do not** make any changes to `SecureStorage` beyond adding the `getSelectedProvider()` / `setSelectedProvider()` methods (the storage layer was finalized in 3a).
- **Do not** add the BackendsScreen "Test connection" button — that's Phase 12 polish (or whenever we surface a need).
- **Do not** add UI surfaces for switching active provider — Phase 6 owns that. Use the simple "first configured wins, default Anthropic" rule.
- **Do not** modify HeliBoard's settings package, keyboard rendering, key layouts, or input logic beyond `LatinIME.onComputeInsets` and any necessary key-event hook for cancel-on-typing.
- **Do not** add AccessibilityService / Termux / Sticker code.
- **Do not** add new dependencies — Ktor + Tink are already in place from 3a.
- **Do not** edit `ARCHITECTURE.md` or `PHASE_REVIEW.md` — surface findings in the summary.
- **Do not** log API keys, prompt text, model output, persona content, or any user data at any log level. Verify with the smoke-test logcat grep.
- **Do** mirror Phase 2's `onComputeInsets` extension pattern (don't introduce a parallel calculation).
- **Do** apply runtime `Colors` to PreviewStripView in an `applyKeyboardTheme()` method matching `CommandRowView`'s pattern.

## Phase 3b summary template

When finished, write `PHASE_3b_SUMMARY.md` at repo root, under 70 lines:

```markdown
## Phase 3b Review (carried into Phase 4 prompt)

- **Streaming impls:** <Anthropic SSE parsing approach, Gemini SSE parsing approach, any libraries vs hand-rolled>
- **Cancel-on-typing implementation:** <how the key-press hook works, where it lives>
- **Provider selection logic:** <how the active provider is chosen when both have keys>
- **Built:** <terse outcome>
- **Smoke test:** <results across all 18 steps; note which provider keys you used>
- **Deviations from Phase 3b prompt:** <list, or "none">
- **Carried issues:** <list, or "none">
- **Touchpoints for Phase 4:**
  - `bridge/` directory — Phase 4 builds the Node service here; currently a placeholder README from Phase 1
  - `app/app/src/main/res/xml/network_security_config.xml` — Phase 4 adds `127.0.0.1` cleartext entry
  - `app/app/src/main/AndroidManifest.xml` — Phase 4 may add `com.termux.permission.RUN_COMMAND`
  - `app/app/src/main/java/com/aikeyboard/app/ai/client/` — Phase 4 adds a `TermuxBridgeBackend` sibling to `RemoteApiBackend`
- **Open questions for human reviewer:** <items where you guessed and they should sanity-check>
```

## Definition of done

- `./gradlew assembleFdroidDebug assemblePlayDebug` both succeed
- `./gradlew lint` passes; `git diff lint-baseline.xml` empty
- All 18 smoke-test steps pass on Pixel 6 Pro `1B101FDEE0006U` (steps 5/6 may be skipped with documented reason if you only have one provider's key)
- A real LLM rewrite completes end-to-end with at least one provider, tokens visible streaming into the preview strip, tap-to-commit replaces input field text
- Cancel-on-typing test (#7) and cancel-button test (#8) both work
- Privacy logcat grep (#15) is empty
- Touchable-region test (#12) confirms the inset extension works
- `PHASE_3b_SUMMARY.md` exists at repo root, under 70 lines
- Single commit on `phase/03b-streaming-rewrite`, not pushed, not merged

Then stop. Do not begin Phase 4.
