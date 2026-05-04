# Phase 7a Prompt — `ScreenReaderService` (fdroid only, bound-service interface, no UI)

> **How to use this prompt:** open a fresh Claude Code session in `/Users/kacper/SDK/apk-dev/ai-keyboard/`. Tell it: *"Read `ARCHITECTURE.md`, `PHASE_REVIEW.md`, and the summaries for Phases 1, 2, 2.5, 3a, 3b, 4, 5a, 5b, 6. Then execute the prompt in `PHASE_7a_PROMPT.md` exactly. Stop when Phase 7a's Definition of Done holds. Do not begin Phase 7b."*

---

You are working in `/Users/kacper/SDK/apk-dev/ai-keyboard/`. Phases 1–6 are complete. Read all planning + summary docs first.

This phase **introduces the AccessibilityService** — the foundation of "Read & Respond." It is intentionally narrow: ship the service so it's enable-able from System Settings, expose a bindable in-process API for Phase 7b to consume, and **add zero UI integration**. The keyboard surface stays untouched in Phase 7a.

Three Phase 7a-specific principles, drawn from the architecture's privacy posture:

1. **Press-to-read-once is enforced by the service config, not just by callers.** The accessibility event types we subscribe to are **minimal** — we don't want a continuous event stream for every keystroke in every app, even if we'd discard it. Subscribe to the smallest legal set; do real work only when `requestScreenContext()` is called.
2. **No text content reaches a log line at any level.** The service may log structural metadata (node count, tree depth, time-to-walk) for diagnostics, but never the `text`/`contentDescription`/`hintText` of any node. This is part of why the architecture chose a11y-as-foundation over MediaProjection.
3. **Flavor split is load-bearing.** The service class lives in `app/src/fdroid/`; the manifest entry lives in `app/src/fdroid/AndroidManifest.xml`; the play APK never references the service class. `BuildConfig.ENABLE_A11Y` guards every shared-code reference.

## Critical context from prior phases

- **First fdroid-only source set.** Looking at the repo today (`find app/app/src -maxdepth 1 -type d`), the existing source sets are `main`, `test`, `debug`, `playDebug`, `debugNoMinify`. **No `app/src/fdroid/` or `app/src/play/` directory exists yet.** Phase 7a creates `app/src/fdroid/` for the first time. AGP picks it up automatically via the `flavorDimensions` declaration in [app/app/build.gradle.kts:117-129](app/app/build.gradle.kts#L117) — no Gradle changes are needed for the source set itself.
- **`BuildConfig.ENABLE_A11Y`** ([app/app/build.gradle.kts:121,125](app/app/build.gradle.kts#L121)) is `true` for fdroid, `false` for play. Phase 1 declared it; Phase 7a is the first real consumer.
- **Phase 4's `TermuxValidationActivity`** is the precedent for a debug-only smoke-test Activity. Note: that activity's *class* lives in `src/main/` (compiled into all APKs) with only its *manifest entry* gated by `src/debug/AndroidManifest.xml`. Phase 7a's `ScreenReaderTestActivity` does **better**: both class AND manifest entry live in `src/fdroidDebug/`, so the class is physically absent from play and release dex. Follow Phase 7a's instructions, NOT Phase 4's actual file layout.
- **Existing manifest declarations** in [app/app/src/main/AndroidManifest.xml](app/app/src/main/AndroidManifest.xml): `LatinIME` service, `SpellCheckerService`, FileProvider, Termux RUN_COMMAND permission + queries. Phase 7a adds **none** to main; everything new is in the fdroid overlay.
- **AccessibilityService config XML** is documented at developer.android.com/guide/topics/ui/accessibility/service. The `accessibilityEventTypes` flag values and `canRetrieveWindowContent` semantics have evolved across API levels. **Use context7 to fetch current Android docs for `AccessibilityServiceInfo` / `AccessibilityServiceConfig` before writing the XML** — getting the minimum-noise event-type subscription right is the single most-important privacy decision in this phase.
- **Threading.** `AccessibilityService.getRootInActiveWindow()` is main-thread-restricted (or at least main-thread-preferred). Walking the tree can be slow on dense UIs (>1000 nodes is plausible). Phase 7a does the walk synchronously; if it turns out to need backgrounding, that's a Phase 12 polish, not 7a's concern.
- **No keyboard-surface UI invariants apply** — Phase 7a touches no view in the IME surface.

## Tasks

### 1. Branch from Phase 6

```
git checkout phase/06-termux-backend
git pull --ff-only
git checkout -b phase/07a-screen-reader-service
```

### 2. `ScreenContext` data class — lives in **main**

Create `app/app/src/main/java/com/aikeyboard/app/ai/a11y/ScreenContext.kt`. It must live in `main` (not `fdroid`) so Phase 7b's wiring code (which will live in main, guarded by `BuildConfig.ENABLE_A11Y`) can reference the type without a flavor-conditional import. play flavor compiles too, just never produces a `ScreenContext` because there's no service to invoke.

```kotlin
package com.aikeyboard.app.ai.a11y

/**
 * Snapshot of a single window-tree walk, captured on demand by the
 * AccessibilityService. Phase 7b's "Read & Respond" button will package this
 * (plus the user's input text) into the prompt sent to the active backend.
 *
 * Privacy: instances of this type carry user-visible screen text; never log
 * `nodes`, `focusedInputText`, or `aboveInputText` content. Diagnostic logs
 * may use only `nodeCount` and `walkDurationMs`.
 */
data class ScreenContext(
    /** All text-bearing nodes in document order, top-to-bottom. */
    val nodes: List<TextNode>,
    /** Index into `nodes` of the currently-focused input field, or -1 if none. */
    val focusedInputIndex: Int,
    /** Convenience: text from `nodes` BEFORE the focused input (the "above" hint). */
    val aboveInputText: String,
    /** Convenience: text inside the focused input field (may be empty). */
    val focusedInputText: String,
    val nodeCount: Int,
    val walkDurationMs: Long,
)

data class TextNode(
    val text: String,            // node.text ?: node.contentDescription ?: ""
    val isInputField: Boolean,   // node.isEditable
    val isFocused: Boolean,      // node.isAccessibilityFocused || node.isFocused
    val className: String,       // for debugging only — never logged
)

sealed interface ScreenReaderResult {
    data class Success(val context: ScreenContext) : ScreenReaderResult
    enum class Failure : ScreenReaderResult {
        SERVICE_NOT_ENABLED,    // user hasn't granted a11y access
        NO_ACTIVE_WINDOW,       // getRootInActiveWindow returned null
        BUILD_DOES_NOT_SUPPORT, // play flavor — caller should never invoke, but defensive
        UNKNOWN_FAILURE,        // unexpected exception during walk
    }
}
```

The `Failure` enum is what Phase 7b's wiring will surface as toast messages; structural enough that the wiring can map each value to a localized string.

### 3. `ScreenReaderService` — lives in **fdroid**

Create `app/app/src/fdroid/java/com/aikeyboard/app/a11y/ScreenReaderService.kt`. This is an `AccessibilityService` subclass.

**Critical: `AccessibilityService.onBind()` is `final` in the platform** (verify with `javap android.accessibilityservice.AccessibilityService` against your Android SDK if curious). Overriding it does not compile. The architecture's Phase 7a checklist in `PHASE_REVIEW.md:302` already permits the alternative: *"via bound service IBinder **or** singleton LiveData for in-process consumption."* We use the **process-singleton handle** approach: a `@Volatile static instance` set in `onServiceConnected` and cleared in `onUnbind`/`onDestroy`. Phase 7b's wiring calls `ScreenReaderService.instance?.requestScreenContext()` from a fdroid-only helper. Same-process access (IME and a11y service share the app process by default), no AIDL, no `Parcelable`.

```kotlin
package com.aikeyboard.app.a11y

class ScreenReaderService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty. The service config subscribes to zero event types
        // (accessibilityEventTypes=""), so this is never called in practice — the
        // empty override is belt-and-suspenders against config drift.
    }

    override fun onInterrupt() { /* no-op */ }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "ScreenReaderService connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        // Belt-and-suspenders: if the framework destroys us without onUnbind firing,
        // make sure we don't leak a stale reference.
        instance = null
        super.onDestroy()
    }

    /**
     * Walks the active window's accessibility tree once and returns a snapshot.
     * Synchronous; intended to be called from the main thread.
     */
    fun requestScreenContext(): ScreenReaderResult {
        val start = SystemClock.elapsedRealtime()
        val root = try {
            rootInActiveWindow
        } catch (t: Throwable) {
            Log.w(TAG, "rootInActiveWindow threw ${t.javaClass.simpleName}")
            return ScreenReaderResult.Failure.UNKNOWN_FAILURE
        } ?: return ScreenReaderResult.Failure.NO_ACTIVE_WINDOW

        val nodes = mutableListOf<TextNode>()
        var focusedIdx = -1
        try {
            walk(root, nodes) { idx -> if (focusedIdx == -1) focusedIdx = idx }
        } catch (t: Throwable) {
            Log.w(TAG, "walk threw ${t.javaClass.simpleName} (count=${nodes.size})")
            return ScreenReaderResult.Failure.UNKNOWN_FAILURE
        } finally {
            // recycle() is deprecated on API 33+ (no-op there) but mandatory on
            // API 29-32 to return AccessibilityNodeInfo objects to the native pool.
            // minSdk=29 makes this load-bearing for older devices.
            @Suppress("DEPRECATION")
            root.recycle()
        }

        val above = if (focusedIdx > 0) nodes.subList(0, focusedIdx).joinToString(separator = "\n") { it.text }
                    else ""
        val focusedText = if (focusedIdx >= 0) nodes[focusedIdx].text else ""

        val ctx = ScreenContext(
            nodes = nodes.toList(),
            focusedInputIndex = focusedIdx,
            aboveInputText = above,
            focusedInputText = focusedText,
            nodeCount = nodes.size,
            walkDurationMs = SystemClock.elapsedRealtime() - start,
        )
        Log.d(TAG, "Walk complete: ${ctx.nodeCount} nodes in ${ctx.walkDurationMs}ms (focusedIdx=${ctx.focusedInputIndex})")
        return ScreenReaderResult.Success(ctx)
    }

    // Depth-first traversal with a strict node count cap to avoid runaway walks.
    private fun walk(node: AccessibilityNodeInfo, out: MutableList<TextNode>, onFocused: (Int) -> Unit) {
        if (out.size >= MAX_NODES) return
        val text = node.text?.toString().orEmpty()
            .ifEmpty { node.contentDescription?.toString().orEmpty() }
        val isInput = node.isEditable
        val isFocused = node.isAccessibilityFocused || node.isFocused
        if (text.isNotEmpty() || isInput) {
            val idx = out.size
            out.add(TextNode(text, isInput, isFocused, node.className?.toString().orEmpty()))
            if (isFocused && isInput) onFocused(idx)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, out, onFocused)
            // Required on API 29-32 to return the child reference to the pool.
            // Caller (or framework) holds a separate reference to `node` itself,
            // so we don't recycle the parent — only the child we just retrieved.
            @Suppress("DEPRECATION")
            child.recycle()
        }
    }

    companion object {
        private const val TAG = "ScreenReaderService"
        private const val MAX_NODES = 2_000

        // Process-singleton handle. Set on onServiceConnected, cleared on
        // onUnbind/onDestroy. Phase 7b's wiring uses this directly (no bindService
        // dance — onBind is final on AccessibilityService).
        @Volatile var instance: ScreenReaderService? = null
            private set
    }
}
```

Key design points:
- **`MAX_NODES = 2000`** prevents pathological pages (large RecyclerViews, content lists) from walking forever. Adjustable in Phase 12 once we have data. Note: deeply nested layouts (200+ nesting levels) could plausibly exhaust the JVM stack before hitting this cap, but `requestScreenContext`'s `catch (t: Throwable)` catches `StackOverflowError` (which extends `Throwable`) and returns `Failure.UNKNOWN_FAILURE` gracefully. Phase 12 polish may switch to iterative traversal once any real-world OOM/stack incidents surface.
- **`text` fallback to `contentDescription`** captures button labels and icon-only UI elements. **Do NOT add `hintText` as a third fallback** — hint text reveals form-field structure ("Enter your password", "Credit card number") even when the field is empty, which is a privacy regression beyond what users expect from "read what's on screen."
- **`recycle()` calls are required on API 29-32** (minSdk=29). Deprecated and no-op on API 33+. The `@Suppress("DEPRECATION")` annotations are necessary because compileSdk is 36.
- **`instance` singleton handle** is the only path Phase 7b can use. Set on `onServiceConnected`, cleared on `onUnbind` AND `onDestroy` (defense against lifecycle paths that skip `onUnbind`).
- **No event subscription work in `onAccessibilityEvent`** — the empty override is belt-and-suspenders. The actual press-to-read-once enforcement is the empty `accessibilityEventTypes` in the config XML.

### 4. AccessibilityService config XML

Create `app/app/src/fdroid/res/xml/accessibility_service_config.xml`. **Use context7 to fetch current Android `AccessibilityServiceInfo` docs** before writing this — the right set of `accessibilityEventTypes` and feedback flags for "I want to be bind-able but receive minimal events" is non-obvious and has shifted across API levels.

Required attributes:
- `canRetrieveWindowContent="true"` — required for `rootInActiveWindow`
- `accessibilityEventTypes=""` (empty mask, equivalent to `0`) — **subscribe to NO events**. `getRootInActiveWindow()` works regardless of event subscription as long as `canRetrieveWindowContent="true"`; subscribing to anything (even `typeViewClicked`) means the framework wakes our service for every tap in every app. Empty mask is legal and is the truly press-to-read-once posture.
- `accessibilityFeedbackType="feedbackGeneric"`
- `notificationTimeout="100"` (ms) — required attribute even with empty event types
- `accessibilityFlags` — at least `flagDefault`; consider `flagRetrieveInteractiveWindows` if the docs indicate it's needed for `rootInActiveWindow` to return floating windows
- `description="@string/screen_reader_service_description"`
- `summary="@string/screen_reader_service_summary"`

The description string explains to the user, in System Settings → Accessibility, what data we read and how — be explicit about "press-to-read-once": "AI Keyboard reads on-screen text only when you tap Read & Respond. It does not run continuously. No data is sent off-device."

### 5. fdroid manifest overlay

Create `app/app/src/fdroid/AndroidManifest.xml`. **Only contains** the new `<service>` declaration — Android merges it with the main manifest at build time. Do not duplicate the `<application>` open tag's attributes (use the empty form):

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <service
            android:name="com.aikeyboard.app.a11y.ScreenReaderService"
            android:exported="true"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>
    </application>
</manifest>
```

Notes:
- `android:exported="true"` is **required** for AccessibilityServices. The Android `AccessibilityManagerService` runs in `system_server` (a different UID), so `exported="false"` would silently prevent the system from binding — the service wouldn't appear in Settings → Accessibility at all. The `android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"` declaration restricts who can perform the framework binding (only the system holds this permission), which is the actual security gate. This is standard a11y boilerplate.
- **Do not** add a `<uses-permission>` for `BIND_ACCESSIBILITY_SERVICE` — that's the service-side permission, not a caller-side one.
- **Do not** declare `FOREGROUND_SERVICE` or `FOREGROUND_SERVICE_SPECIAL_USE` here — those are Phase 8's concerns (always-on toggle).

### 5b. play manifest overlay (empty — for checklist conformance)

Create `app/app/src/play/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application />
</manifest>
```

Functionally equivalent to omitting the file (same merged manifest), but `ARCHITECTURE.md:157` and `PHASE_REVIEW.md:30` both reference `src/play/AndroidManifest.xml` as a thing that exists. Creating the empty overlay makes the architecture description literal-true and gives reviewers a file path to grep against.

### 6. fdroid string resources

Create `app/app/src/fdroid/res/values/a11y_strings.xml`:

```xml
<resources>
    <string name="screen_reader_service_description">AI Keyboard reads on-screen text only when you tap Read &amp; Respond on the keyboard. It does not run continuously, does not record anywhere, and reads no data when the keyboard is hidden. No data leaves your device beyond what you would normally send to your selected AI backend.</string>
    <string name="screen_reader_service_summary">Read on-screen text on demand for AI replies</string>
</resources>
```

Keep the description honest and specific. System Settings displays this verbatim; vague copy ("collects accessibility data") is what makes users distrust a11y-using apps.

### 7. Smoke-test Activity (debug-only)

Create `app/app/src/fdroidDebug/java/com/aikeyboard/app/a11y/ScreenReaderTestActivity.kt` (the `fdroidDebug` source-set merges with `fdroid` + `debug`). Pattern from Phase 4's `TermuxValidationActivity`:

- Single-screen Activity (Compose or simple Views — match the Phase 4 pattern).
- Button "Read screen now" → calls `ScreenReaderService.instance?.requestScreenContext()` (no bind dance for the smoke test; if service is null, show "service not enabled — open System Settings → Accessibility").
- Result rendering: show **only** structural metadata: `nodeCount`, `walkDurationMs`, `focusedInputIndex`, `nodes.size`, **first 80 chars of focusedInputText** (one debug line for verification, NOT to be added to logs). Do not render `aboveInputText` content.
- Failure cases each render their own message ("service not enabled", "no active window", "unknown failure").
- Add to `app/app/src/fdroidDebug/AndroidManifest.xml` as `android:exported="true"` (Android 12+ requires LAUNCHER activities to be exported to appear in the app drawer; debug-only build so the exposure is acceptable — same as `TermuxValidationActivity`'s pattern), with a `LAUNCHER` intent-filter so it shows up in the app drawer of the debug build only.

### 8. Shared-code guards

The only `BuildConfig.ENABLE_A11Y` reference Phase 7a needs to add lives in `CommandRowController.onReadRespondTap()` (currently a `Log.d(TAG, "Read & Respond tapped (Phase 7)")` no-op at [app/app/src/main/java/com/aikeyboard/app/ai/commandrow/CommandRowController.kt:174](app/app/src/main/java/com/aikeyboard/app/ai/commandrow/CommandRowController.kt#L174)). Update it to:

```kotlin
override fun onReadRespondTap() {
    if (!BuildConfig.ENABLE_A11Y) {
        toast(R.string.ai_read_respond_not_supported_play)
        return
    }
    // Phase 7b wires the actual service-bind + Rewrite-with-context call here.
    Log.d(TAG, "Read & Respond tapped — Phase 7b will wire the bind+rewrite path")
}
```

Add `import com.aikeyboard.app.latin.BuildConfig` to the file's imports (the `namespace` in `build.gradle.kts:111` is `com.aikeyboard.app.latin`, so `BuildConfig` lives in that package — not `com.aikeyboard.app`).

Add the string `ai_read_respond_not_supported_play` to `app/app/src/main/res/values/ai_strings.xml` (per Phase 6's deviation note — strings live there): `"AI screen reading isn't available in the Play build."`

This keeps play-flavor builds compiling cleanly: `BuildConfig.ENABLE_A11Y` short-circuits before any code path that would reference the fdroid-only `ScreenReaderService` class. **Do not import `ScreenReaderService` in main** — once Phase 7b wires the bind, the import goes inside a fdroid-only helper that main calls via a typed interface (Phase 7b's concern, not 7a's).

### 9. Tests

Add `app/app/src/test/java/com/aikeyboard/app/ai/a11y/ScreenContextTest.kt`:
- Construct a `ScreenContext` with focused index = 2 and 5 text nodes; assert `aboveInputText` joins nodes 0-1 with newlines and `focusedInputText` matches node 2.
- Construct a `ScreenContext` with focusedInputIndex = -1; assert `aboveInputText` is empty and `focusedInputText` is empty.
- Construct a `ScreenContext` with focusedInputIndex = 0; assert `aboveInputText` is empty (no nodes above).

The walk logic itself is hard to unit-test without an Android `AccessibilityNodeInfo` (Robolectric can do it but our existing pattern is JVM-pure tests). Skip walk tests for Phase 7a; the on-device smoke test is the verification path.

### 10. Smoke test (Pixel 6 Pro)

#### Build invariants

- `./gradlew assembleFdroidDebug assemblePlayDebug assembleFdroidRelease assemblePlayRelease` clean.
- `./gradlew lintFdroidDebug lintPlayDebug` clean; `git diff app/app/lint-baseline.xml` empty.
- `./gradlew test` passes.
- **`apkanalyzer dex packages` on the play-release APK** confirms NO `com.aikeyboard.app.a11y` package, NO `ScreenReaderService` class, NO `ScreenReaderTestActivity`. This is the load-bearing flavor-discipline check.
- `apkanalyzer dex packages` on the fdroid-release APK confirms `ScreenReaderService` IS present.
- `apkanalyzer manifest print` on play-release: NO `<service>` for `ScreenReaderService`, NO `BIND_ACCESSIBILITY_SERVICE` permission referenced.
- `apkanalyzer manifest print` on fdroid-release: service IS declared with the correct config XML reference.
- `apkanalyzer manifest print` on fdroidDebug: `ScreenReaderTestActivity` is present with `LAUNCHER` intent-filter.
- `apkanalyzer manifest print` on fdroid-release (NOT fdroid-debug): `ScreenReaderTestActivity` is NOT present (debug-only).

#### On-device fdroid scenario

1. `adb install -r app/app/build/outputs/apk/fdroid/debug/...`
2. App drawer shows two icons: "AI Keyboard debug" (the IME) and "Screen Reader Test" (the debug Activity).
3. Open "Screen Reader Test", tap "Read screen now" — shows "Service not enabled" (correct, we haven't enabled it yet).
4. Open Android Settings → Accessibility → Installed apps → "AI Keyboard debug" → toggle on. **Android 14+ note:** if the toggle silently reverts or shows "Restricted setting" greyed out, run `adb shell appops set com.aikeyboard.app ACCESS_RESTRICTED_SETTINGS allow` (or, manually: app info → ⋮ → Allow restricted settings). The wizard for this UX is Phase 7b's responsibility.
5. Return to "Screen Reader Test", tap "Read screen now" — shows `nodeCount`, `walkDurationMs`, `focusedInputIndex`, and the first 80 chars of any focused input text.
6. Open a notes app, type a few words into the field. Switch to "Screen Reader Test" via app drawer, tap "Read screen now" again — node count should reflect the notes app's tree from when it was last in the foreground (or the test activity's own tree if it's now focused — either way, no crash).
7. Disable a11y service in System Settings, return to test activity, tap again — "Service not enabled" message (graceful failure).

#### On-device play scenario

1. `adb install -r app/app/build/outputs/apk/play/debug/...` (alongside the fdroid debug, different applicationId).
2. App drawer: only "AI Keyboard Play debug" — no test activity (debug-only on fdroid only).
3. Open AI settings, tap "Read & Respond" on the keyboard's command row — shows the "AI screen reading isn't available in the Play build" toast. No crash, no `ClassNotFoundException`.

#### Privacy invariants

- `adb logcat -d -s ScreenReaderService` after a full walk: shows only the `Walk complete: N nodes in Xms (focusedIdx=Y)` structural line. No node text content.
- `adb logcat -d | grep -iE "text|content|node\."` — no text content from the screen.
- `adb logcat -d | grep -iE "FATAL|AndroidRuntime"` — empty.

#### Manifest correctness

After install, on a fdroid debug build (note: Phase 1's debug suffix means `applicationId == com.aikeyboard.app.debug`, NOT `com.aikeyboard.app`):
- `adb shell dumpsys accessibility | grep com.aikeyboard.app.debug` — should show our service registered post-enable.
- `adb shell settings get secure enabled_accessibility_services` — contains `com.aikeyboard.app.debug/com.aikeyboard.app.a11y.ScreenReaderService` after enable. (For a release-flavor install the same path with `applicationId == com.aikeyboard.app` would apply, but Phase 7a's smoke test runs on the debug build.)

### 11. Commit

Single commit on `phase/07a-screen-reader-service`:

```
Phase 7a: ScreenReaderService (fdroid only) + in-process singleton handle

- ScreenContext + ScreenReaderResult data classes in main (Phase 7b
  consumes both)
- ScreenReaderService in app/src/fdroid/java/com/aikeyboard/app/a11y/.
  Exposes @Volatile instance set on onServiceConnected, cleared on
  onUnbind/onDestroy (AccessibilityService.onBind is final — bindService
  approach won't compile)
- accessibility_service_config.xml with empty accessibilityEventTypes
  mask — truly press-to-read-once, zero events subscribed
- AccessibilityNodeInfo.recycle() on root + every child for API 29-32
  native pool hygiene (deprecated/no-op on 33+)
- fdroid AndroidManifest.xml overlay declares the <service> as
  exported=true (required for system_server to bind), gated by
  BIND_ACCESSIBILITY_SERVICE permission; play overlay is empty
- ScreenReaderTestActivity in fdroidDebug for smoke testing the walk
  path before Phase 7b wires it to the keyboard surface
- CommandRowController.onReadRespondTap guarded by BuildConfig.ENABLE_A11Y
  with a clear toast on play
- Tests: ScreenContext convenience-field derivation
- play-release dex confirmed to contain NO a11y/ package or ScreenReaderService
- Both flavors build clean; smoke-tested on Pixel 6 Pro
```

Do not push, do not merge.

## Constraints

- **Do not** modify HeliBoard's `LatinIME` or any code in the keyboard surface beyond the one `CommandRowController.onReadRespondTap` guard.
- **Do not** add UI to the keyboard surface, the AI settings hub, or the BackendsScreen. Phase 7b owns the wizard and the wiring.
- **Do not** add any new top-level Activity to the main manifest. The smoke-test Activity is debug-only.
- **Do not** declare `FOREGROUND_SERVICE` or `FOREGROUND_SERVICE_SPECIAL_USE` — Phase 8 owns the always-on toggle and its foreground-service plumbing.
- **Do not** import `ScreenReaderService` from main code. The class lives in fdroid; main code references only `ScreenContext` and (eventually, in Phase 7b) a typed interface.
- **Do not** put `ScreenContext` in the fdroid source set — Phase 7b's wiring lives in main and needs the type.
- **Do not** subscribe to ANY events in the service config. Use empty `accessibilityEventTypes=""` (or `"0"`); the service still receives `getRootInActiveWindow` access via `canRetrieveWindowContent="true"`. Even `typeViewClicked` fires on every tap in every app — that's not press-to-read-once.
- **Do not** override `AccessibilityService.onBind()` — it's `final` in the platform and the build will fail with "cannot override". Use the `@Volatile static instance` singleton approach.
- **Do not** skip `recycle()` calls on `AccessibilityNodeInfo` — required on API 29-32 (minSdk=29) to avoid pool exhaustion. `@Suppress("DEPRECATION")` is fine on compileSdk 36.
- **Do not** log node `text`, `contentDescription`, `hintText`, or any other content-bearing field at any log level.
- **Do not** add new dependencies — Compose is already present from Phase 2.5+; the AccessibilityService API is platform-provided.
- **Do not** edit `ARCHITECTURE.md` or `PHASE_REVIEW.md`. Surface findings in the summary.
- **Do** use context7 for current `AccessibilityServiceInfo` / config XML docs before writing the config.
- **Do** verify the play APK does not contain `ScreenReaderService` — this is the single most-important Phase 7a invariant. `apkanalyzer dex packages` is the verification tool.

## Phase 7a summary template

When finished, write `PHASE_7a_SUMMARY.md` at repo root, under 70 lines:

```markdown
## Phase 7a Review (carried into Phase 7b prompt)

- **Service surface:** <ScreenReaderService class location, LocalBinder shape, requestScreenContext() return shape, MAX_NODES choice, threading>
- **Service config:** <accessibilityEventTypes / accessibilityFlags / notificationTimeout / canRetrieveWindowContent values picked + brief rationale>
- **ScreenContext API:** <data class location, what's in it, derivation rules for above/focused convenience fields>
- **Flavor split verification:** <apkanalyzer outputs proving the play APK is free of a11y code>
- **Manifest changes:** <fdroid overlay only — service declaration, smoke-test activity in fdroidDebug>
- **CommandRowController guard:** <one-line summary of the BuildConfig.ENABLE_A11Y addition>
- **Built:** <terse outcome>
- **Smoke test:** <results: fdroid enable + walk / play not-supported toast / service-disabled graceful failure / privacy-log audit>
- **Deviations from Phase 7a prompt:** <list, or "none">
- **Carried issues:** <list, or "none">
- **Touchpoints for Phase 7b:**
  - `app/app/src/main/java/com/aikeyboard/app/ai/commandrow/CommandRowController.kt` — Phase 7b replaces the `Log.d` placeholder with the real walk+rewrite path
  - `ScreenReaderService.instance` — Phase 7b accesses the connected service via this `@Volatile` static (no `bindService` dance — `AccessibilityService.onBind` is `final`); the access lives in a fdroid-only helper class so main code never imports `ScreenReaderService`
  - `ScreenContext` + `ScreenReaderResult` in main — Phase 7b consumes both directly
  - Onboarding wizard for Android 14+ restricted-settings flow — Phase 7b
- **Open questions for human reviewer:** <items>
```

## Definition of done

- `ScreenContext` + `TextNode` + `ScreenReaderResult` data types exist in `app/app/src/main/java/com/aikeyboard/app/ai/a11y/`
- `ScreenReaderService` exists in `app/app/src/fdroid/java/com/aikeyboard/app/a11y/`; exposes a `@Volatile static instance` set on `onServiceConnected` and cleared on `onUnbind` AND `onDestroy`; `requestScreenContext()` walks the active window's tree once and returns a populated `ScreenContext` or a typed `Failure`; `recycle()` is called on the root and on every child node to release the API 29-32 native pool
- `accessibility_service_config.xml` exists in `app/app/src/fdroid/res/xml/`; `accessibilityEventTypes=""` (empty mask — zero events subscribed); `canRetrieveWindowContent=true`; description and summary strings are written and accurate
- fdroid manifest overlay declares the service with `android:exported="true"` (required for system_server to bind), `android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"`, the correct intent-filter, and the config meta-data
- `app/app/src/play/AndroidManifest.xml` exists as an empty overlay (architecture-description conformance)
- fdroid string resources include the description + summary
- `ScreenReaderTestActivity` exists in `app/app/src/fdroidDebug/`; manifest entry has LAUNCHER filter; renders only structural metadata + a single 80-char focused-input snippet
- `CommandRowController.onReadRespondTap` guards on `BuildConfig.ENABLE_A11Y` with a clear toast on play
- Both flavor APKs build clean (debug + release); lint clean; lint-baseline diff empty
- New unit tests pass; existing 52 AI-module tests still green
- `apkanalyzer dex packages` on play-release: NO `com.aikeyboard.app.a11y` package, NO `ScreenReaderService`, NO `ScreenReaderTestActivity`
- `apkanalyzer dex packages` on fdroid-release: `ScreenReaderService` present; `ScreenReaderTestActivity` NOT present (debug-only)
- `apkanalyzer manifest print` confirms the service is declared in fdroid manifests, absent from play manifests
- Smoke test on Pixel 6 Pro: fdroid enable + walk works; play "not supported" toast works; service-disabled graceful failure works; logcat carries no node text content
- `PHASE_7a_SUMMARY.md` exists at repo root, under 70 lines
- Single commit on `phase/07a-screen-reader-service`, not pushed, not merged

Then stop. Do not begin Phase 7b.
