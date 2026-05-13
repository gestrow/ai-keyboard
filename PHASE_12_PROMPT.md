# Phase 12 — Final polish + signed F-Droid release prep (v0.1.0)

> **READ BEFORE STARTING.** This is the final phase per `ARCHITECTURE.md`. Phase 12 bundles three concerns: (a) the documented carry-over bug fixes from Phases 8 / 9a / 9b / 10 / 11, (b) the release-prep deliverables called out in `PHASE_REVIEW.md` Phase 12 row ("Done means" list), and (c) a final consolidated smoke pass + privacy audit across all phases. The DoD at §17 is the gate; do not call this phase done until every checkbox holds.
>
> **What this phase is NOT.** It is not the place to refactor `LocalLanBackend` into a generic `OpenAiCompatRemoteBackend` (deferred), not the place to add a model-override UI per provider (deferred — `grok-2-latest`, `claude-3-5-sonnet-latest`, `gemini-2.0-flash-latest` defaults are fine for v0.1.0), and not the place to bump the Codex version pin past 0.42.0 unless §16 smoke proves a later version is Termux-compatible. Carry-overs that stay deferred are listed at §18.

---

## §0. Read order

Open these files first, in this order, so the rest of the prompt makes sense:

1. `ARCHITECTURE.md` — locked decisions + module layout. The "Done means" list for Phase 12 lives at `PHASE_REVIEW.md` line 397+ but the high-level goals (reproducible build, F-Droid metadata, health diagnostics) trace back to ARCHITECTURE's distribution decision (#5).
2. `PHASE_REVIEW.md` — full row inventory. Phase 12's row is at line 397; read it AND the "Known accepted corner cases" section above it (line 60+) — that section flags items that may need resolution in Phase 12.
3. `PHASE_11_SUMMARY.md` — Phase 11 closed out two days before Phase 12 starts; the "Touchpoints for Phase 12" and "Open questions for human reviewer" sections at lines 68+ and 77+ are direct inputs to this phase's scope.
4. Phase summaries 1, 2, 2.5, 3a, 3b, 4, 5a, 5b, 6, 7a, 7b, 8, 9a, 9b, 10. You don't have to re-read all of them line by line — skim the "Carried issues" and "Deviations" sections of each, because Phase 12 is the resolution point for those.
5. `README.md` — currently 57 lines, labeled "Phase 1 scaffold". §13 of this prompt rewrites it.

---

## §1. Architectural decisions made for Phase 12

### 1.1 v0.1.0, not v1.0.0

`PHASE_REVIEW.md` Phase 12 row says "Tagged release `v0.1.0`". The fork inherited HeliBoard's `versionCode = 3901 / versionName = "3.9"` from `app/app/build.gradle.kts:17-18`. **Decision: bump `versionName` to `"0.1.0"` and `versionCode` to `1` for the F-Droid release.** This is a deliberate semver reset for the AI-keyboard fork — the HeliBoard upstream uses its own numbering and our F-Droid metadata file (in F-Droid's `fdroiddata` repo, NOT in this repo) will reference `com.aikeyboard.app` which has never appeared in F-Droid before. The play flavor's `versionNameSuffix = "-play"` (build.gradle.kts:127) gives `"0.1.0-play"` for that flavor's APK, distinguishable from fdroid output.

Rationale for `versionCode = 1` rather than continuing 3901+: F-Droid auto-publish bumps `versionCode` monotonically. Starting at 1 lets the F-Droid build server take over numbering from v0.2.0 onwards without colliding with HeliBoard's range. If a phase-12 reviewer prefers continuity (e.g., bump to 4000 / "4.0"), the cost is a re-coordination with F-Droid's `Builds:` block in the fdroiddata metadata; for v0.1.0 we control the metadata, so 1 is the cleaner start.

### 1.2 Signed release config

Phase 12 introduces a real `signingConfigs.release` block in `app/app/build.gradle.kts`. Currently `buildTypes.release` defaults to debug-signing because `signingConfig` is unset for the release type (build.gradle.kts:54 sets `debugNoMinify` to debug-sign; `release` has no explicit `signingConfig` line so it inherits `signingConfigs.debug` implicitly). The new pattern follows the standard F-Droid contributor guidelines:

- Read `keystore.path` / `keystore.password` / `key.alias` / `key.password` from `keystore.properties` at repo root (a file we add to `.gitignore` — never commit).
- If `keystore.properties` does not exist, fall back to debug signing **but log a Gradle warning** so a CI/release build can't accidentally ship debug-signed.
- Document the keystore generation command + properties file shape in `BUILD.md` (new) — Phase 12 deliverable §15.

### 1.3 Reproducible build flags

`PHASE_REVIEW.md` already promised in Phase 1 that `gradle.properties` carries `android.enableR8.fullMode=false` plus the reproducibility set. Phase 12 verifies the actual `gradle.properties` against that promise (it may have drifted) and adds anything missing. The reproducible-build assertion ("byte-identical APK on two machines") is a §16.8 smoke scenario.

### 1.4 Health diagnostics screen

Per `PHASE_REVIEW.md` Phase 12 "Done means": "Health diagnostics screen: shows IME enabled state, a11y state, backend reachability, bridge version". This is a new Compose screen at `AiSettings → Health` reachable from `BackendsScreen` via a `TextButton("Run diagnostics")`. It checks five things and shows a green-check / red-cross next to each, with copy-to-clipboard for the whole report. Details in §10.

### 1.5 Toast → in-keyboard banner

Phase 9b fixed the toast-hidden-behind-picker bug for the **picker** surface via `StickerPickerView.showError(stringRes)` (an in-picker error chip). The **CommandRowController** still calls `Toast.makeText(ime, …)` at `CommandRowController.kt:395-396` for AI rewrite / Read-Respond errors. When the keyboard is open without the picker showing, these toasts render at `Gravity.BOTTOM` with the keyboard's default offset — **still behind the keyboard surface, still invisible**. Phase 12 adds a similar in-IME error banner that mirrors the picker's chip pattern. Details in §5.

### 1.6 BootReceiver investigation

The Phase 8 smoke showed `BootReceiver` does NOT result in a re-posted FGS chip after reboot. `BootReceiver.kt:24-29` looks correct on paper, but the `runCatching { … }` block silently swallows the exception. Phase 12 (a) replaces `runCatching` with structured exception handling that logs `t.javaClass.simpleName` only, (b) adds entry/exit logs to the receiver, and (c) verifies via §16 smoke whether the fix surfaces the root cause. The most likely culprits are Android 14+ background-FGS-start restrictions and the directBootAware=false race window — but we won't know until logs surface.

### 1.7 WhatsApp validator instrumentation

Phase 9b smoke showed WhatsApp rejected our sticker pack despite a spec-compliant manifest. The suspected cause is `assertAllowedCaller()` at `WhatsAppStickerContentProvider.kt:112-117` rejecting a WhatsApp **worker process** whose `callingPackage` differs from the registered ALLOWED_CALLERS set. Phase 12 adds `BuildConfig.DEBUG`-gated logging of `callingPackage` + `Binder.getCallingUid()` on every rejection (debug builds only — release stays silent), then re-runs the §16.5 smoke to see what package name WhatsApp's worker reports. The fix is then a one-line ALLOWED_CALLERS extension (or, if it turns out to be a different bug, root-cause analysis carries to v0.2.0).

### 1.8 No new dependencies, no new permissions

Phase 12 is polish + release-prep. The deltas in §3.2 invariants must hold: zero new `<uses-permission>` entries, zero new `implementation(...)` lines in `build.gradle.kts`. If a reviewer feels they need to add either, stop and re-scope.

---

## §2. Scope (in / out)

**In scope:**
- Bug fixes for the carry-over list: BootReceiver, in-keyboard banner (Toast positioning), WhatsApp caller logging, `ObsoleteSdkInt` at `TermuxValidationActivity.kt:199`.
- Release signing config + `keystore.properties` template + `.gitignore` entry + `BUILD.md`.
- Reproducible build verification (commands in §16.8).
- `fastlane/metadata/android/en-US/` directory with `title.txt`, `short_description.txt`, `full_description.txt`, `changelogs/1.txt`, and `images/` placeholder structure (icon/featureGraphic/phoneScreenshots are user-supplied — Phase 12 leaves `README` instructions for the developer to drop in real images before tagging).
- `LICENSE` (GPL-3.0-only — the HeliBoard fork is GPL-3 and our additive code is GPL-3 as well, with most files already carrying `SPDX-License-Identifier: GPL-3.0-only`).
- `NOTICE` (third-party attributions: HeliBoard, AOSP LatinIME, Compose, Ktor, kotlinx, colorpicker-compose, Tink, Skydoves colorpicker-compose). Do not relicense anything.
- Health diagnostics screen (§10).
- `README.md` rewrite for v0.1.0 release.
- `bridge/README.md` update for Codex.
- `CHANGELOG.md` (new) covering Phase 1 through Phase 12.
- Final lint baseline review (curate, not chase to zero).
- Final privacy logcat audit + redaction sweep.
- Claude Code Termux compatibility recheck (per `PHASE_REVIEW.md` mandatory).
- Codex version recheck (`CODEX_VERSION="0.42.0"` in `setup/setup.sh`) — verify still the latest viable, document a bump or hold decision.
- Final on-device smoke pass across all phases (§16).
- ARCHITECTURE.md / PHASE_REVIEW.md final updates (mark Phase 12 done, fold in any new accepted corner cases discovered).

**Out of scope (defer to v0.2.0+):**
- Model-override UI per provider (`grok-2-latest`, `claude-3-5-sonnet-latest`, `gemini-2.0-flash-latest` defaults stay).
- `OpenAiCompatRemoteBackend` refactor (no third OpenAI-compat provider yet).
- Multi-pack creation flow polish (Phase 9b carry-over).
- Pack tab background contrast (Phase 9b carry-over — acceptable v0.1.0).
- HeliBoard emoji-key gap (Phase 9a carry-over — upstream issue).
- Robolectric SDK-36 failures (6 pre-existing — document as accepted; investigating is multi-day work).
- Codex CLI version bump past 0.42.0 unless §16.10 smoke validates a later version on Termux.
- AGP / Compose BOM / colorpicker-compose dep bumps (lint warns; document as accepted v0.1.0).
- Direct-boot SecureStorage migration retry-on-unlock (Phase 3a carry-over, no real-world reports).

---

## §3. Pre-flight

Before writing any code, run these checks. If any fails, fix it before proceeding (or note as a blocker and stop):

```bash
cd /Users/kacper/SDK/apk-dev/ai-keyboard
git status                                  # working tree should be clean
git log --oneline -1                        # latest commit should be Phase 11 (commit message includes "Phase 11")
cd app && ./gradlew tasks --quiet > /dev/null  # Gradle health check
cd ../bridge && node --test "test/*.test.js" # 45 bridge tests should pass (25 + 20 from Phase 11)
```

### 3.1 Invariants we are NOT touching

The following are stable from prior phases and Phase 12 must not regress them. After every commit in Phase 12, re-verify these:

- **Permissions set** (from `PHASE_11_SUMMARY.md` line 33):
  - fdroid: `{INTERNET, RECEIVE_BOOT_COMPLETED, VIBRATE, READ_USER_DICTIONARY, WRITE_USER_DICTIONARY, READ_CONTACTS, RUN_COMMAND, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS}` + DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
  - play: `{INTERNET, RECEIVE_BOOT_COMPLETED, VIBRATE, READ_USER_DICTIONARY, WRITE_USER_DICTIONARY, READ_CONTACTS, RUN_COMMAND}` + DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
- **No new dependencies** in `app/app/build.gradle.kts`. (License audit may surface attribution requirements but those go into NOTICE, not into build.gradle.)
- **No new MCP servers, no new HTTP endpoints.**
- **Test counts:** 157 AI-module JVM tests + 45 bridge tests. Phase 12 may add tests but must not delete any.
- **NSC contents:** Phase 10's `base-config cleartextTrafficPermitted="true"` (LocalLan) + Phase 10's `tools:ignore="InsecureBaseConfiguration"` stay. The HTTPS-only `domain-config` block lists `api.anthropic.com`, `generativelanguage.googleapis.com`, `api.x.ai` (Phase 11). Phase 12 does not add or remove domains.
- **Privacy invariant:** every `Log.*` call audited — no `t.message`, URLs, prompt bodies, API keys, user input in logs. Phase 12 §14 is the explicit audit pass.

### 3.2 Branch + commit hygiene

- Branch: `phase/12-polish-release` cut from `phase/11-codex-grok` (or whatever the Phase 11 final branch is — `git log --oneline -5` to confirm).
- Commits should be one-concern each. Recommended sequence (do not concatenate — each gets its own commit so a reviewer can bisect):
  1. `Phase 12: BootReceiver diagnostic logging + structured exception handling`
  2. `Phase 12: in-keyboard error banner replaces Toast in CommandRowController`
  3. `Phase 12: WhatsApp provider caller-package debug logging`
  4. `Phase 12: fix TermuxValidationActivity.kt:199 ObsoleteSdkInt`
  5. `Phase 12: health diagnostics screen`
  6. `Phase 12: release signing config + keystore.properties template + .gitignore`
  7. `Phase 12: version bump to 0.1.0 / versionCode 1`
  8. `Phase 12: fastlane/metadata/android/ + LICENSE + NOTICE`
  9. `Phase 12: README.md rewrite, bridge/README.md Codex addition, CHANGELOG.md`
  10. `Phase 12: lint baseline curation + ARCHITECTURE/PHASE_REVIEW final updates`
- Each commit's body should reference the relevant §N of this prompt for traceability.

---

## §4. BootReceiver diagnostic + structured exception handling

**File:** `app/app/src/fdroid/java/com/aikeyboard/app/a11y/BootReceiver.kt`

**Current (Phase 8) state — read it first:**

```kotlin
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        runCatching {
            val storage = SecureStorage.getInstance(context.applicationContext)
            if (storage.isAlwaysOnEnabled()) {
                AlwaysOnProxy.start(context.applicationContext)
            }
        }
    }
}
```

The bug surfaced by Phase 8 smoke: after enabling Always-On → rebooting → unlocking, the FGS chip does NOT appear. The current `runCatching` block swallows any exception silently, so logcat shows nothing diagnostic.

**Phase 12 rewrite (target shape):**

```kotlin
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "onReceive action=${intent.action}")
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        val enabled = try {
            SecureStorage.getInstance(appContext).isAlwaysOnEnabled()
        } catch (t: Throwable) {
            Log.w(TAG, "storage read failed: ${t.javaClass.simpleName}")
            return
        }
        Log.i(TAG, "alwaysOnEnabled=$enabled")
        if (!enabled) return
        try {
            AlwaysOnProxy.start(appContext)
            Log.i(TAG, "AlwaysOnProxy.start invoked")
        } catch (t: Throwable) {
            Log.w(TAG, "AlwaysOnProxy.start failed: ${t.javaClass.simpleName}")
        }
    }
    companion object { private const val TAG = "BootReceiver" }
}
```

**Privacy invariants on the new logs (verify before commit):**
- `intent.action` is a public Android constant string — safe to log.
- `enabled` is a Boolean — safe to log.
- Exception logs use `t.javaClass.simpleName` only, never `t.message` (which on Tink failures can include a partial keystore path; on network exceptions can include URLs).
- No user data of any kind ever enters this receiver.

**After the rewrite, smoke at §16.3** — the goal is to observe in logcat WHICH path is failing. Three likely outcomes:
1. `onReceive` never logged after reboot → directBootAware deferral isn't firing because the user never unlocks before checking, OR the manifest receiver registration silently broke (unlikely, but possible if `AndroidManifest.xml` lint warnings have accumulated).
2. `onReceive` logs but `alwaysOnEnabled=false` → SecureStorage isn't persisting the flag, OR isn't readable post-boot.
3. `onReceive` logs, `alwaysOnEnabled=true`, `AlwaysOnProxy.start invoked` logs, but the FGS chip doesn't appear → `AlwaysOnService.onStartCommand` is failing silently or Android 14+ is rejecting the background FGS start.

**If outcome 3 happens, the next step (within Phase 12) is** to inspect `AlwaysOnService.kt` (`app/app/src/fdroid/java/com/aikeyboard/app/a11y/AlwaysOnService.kt`) for a missing log line at `startForeground(...)` and add one — same privacy rules. Android 14+'s `BackgroundActivityStartException` / `ForegroundServiceStartNotAllowedException` is the most likely culprit; if it surfaces, document the constraint and either (a) gate the boot-start behind a foreground-trigger (e.g., user opens keyboard once after boot, which then re-arms), or (b) request `MANAGE_OWN_CALLS` / device-owner exemptions (rejected, too invasive).

For v0.1.0 it's acceptable to leave the behavior documented-but-not-fully-fixed if the root cause is OS-level restriction — in that case, the FGS chip simply reappears the first time the user opens the keyboard after boot. The README must explain this clearly.

**Tests:** No JVM-level test for BootReceiver (it needs `BroadcastReceiver` runtime + Tink + Always-On wiring — Robolectric SDK-36 is already known broken). The Phase 12 smoke at §16.3 is the regression gate.

---

## §5. Toast → in-keyboard error banner (CommandRowController)

**Files:**
- `app/app/src/main/java/com/aikeyboard/app/ai/commandrow/CommandRowController.kt` (call sites)
- `app/app/src/main/res/layout/ai_command_row.xml` (add banner view)
- `app/app/src/main/res/values/ai_strings.xml` (no new strings — reuse existing)

**Pattern to mirror (Phase 9b):** `app/app/src/main/java/com/aikeyboard/app/ai/sticker/picker/StickerPickerView.kt:90-100` has a `showError(@StringRes stringRes: Int)` method backed by an `errorChip` View. The chip auto-dismisses after `ERROR_DURATION_MS` ms (find the actual constant in that file — should be ~2500ms). Mirror this on the command row.

**Implementation outline:**

1. Add an `<include layout="@layout/ai_error_chip"/>` (extracted shared layout) or an inline `<TextView android:id="@+id/ai_error_chip" ...>` to `ai_command_row.xml`. Style to match the existing picker chip (white text on a translucent dark surface, ~14sp, rounded corners, centered horizontally, ~8dp above the command row).
2. Hold the chip view in `CommandRowController` like `private val errorChip: TextView = view.findViewById(R.id.ai_error_chip)`.
3. Replace `private fun toast(resId: Int) { Toast.makeText(ime, resId, Toast.LENGTH_SHORT).show() }` (line 395-397) with:
   ```kotlin
   private val errorDismiss = Runnable { errorChip.visibility = View.GONE }
   private fun toast(resId: Int) {
       errorChip.removeCallbacks(errorDismiss)
       errorChip.setText(resId)
       errorChip.visibility = View.VISIBLE
       errorChip.postDelayed(errorDismiss, ERROR_DURATION_MS)
   }
   companion object {
       private const val TAG = "AiCommandRow"  // keep existing
       private const val MAX_INPUT_CHARS = 8_000  // keep existing
       private const val ERROR_DURATION_MS = 2500L  // match StickerPickerView
   }
   ```
   The method name stays `toast(resId)` so all 10+ existing call sites (lines 97, 114, 120, 202, 203, 207, 209, 211, 217, 226 — verify via `grep -n "toast(" CommandRowController.kt`) work unchanged.
4. Remove the `import android.widget.Toast` line at the top of `CommandRowController.kt:13`.
5. **Side effect** — `StickerPickerView.kt:89` references the old toast path in a comment. Update the comment to point at the new in-keyboard chip (or just remove the comment since the chip pattern is now uniform).

**Why not a Snackbar?** Snackbar requires a `CoordinatorLayout` ancestor and the IME's input view doesn't have one — wiring it requires invasive surgery on HeliBoard's view inflation. The TextView-chip pattern is what Phase 9b chose and is what we extend here.

**Visual placement note.** The chip should appear ABOVE the command row, not below — below would land on top of the keyboard keys which is worse UX than the buried Toast. Confirm placement during §16.4 smoke.

**Lifecycle hygiene.** `CommandRowController` is constructed by `LatinIME` on the main thread; the `postDelayed(errorDismiss, ...)` callback fires after the keyboard view may have detached if the user closed the IME mid-window. To avoid the harmless-but-noisy `View is detached` debug warning, also wire `errorChip.removeCallbacks(errorDismiss)` into whatever teardown hook `CommandRowController` exposes (e.g., `onUnregister()` / `onViewDetached()` / `cleanup()` — find the existing hook by inspecting how the streaming-job cancellation `scope.cancel()` is wired in this file). One line addition; same idempotency guarantee as the picker's `errorChip.removeCallbacks(errorDismiss)` in `showError`.

**Tests:** No JVM test (Android Views require Robolectric SDK-36 which is known broken). §16.4 smoke is the regression gate.

---

## §6. WhatsApp validator caller-package debug logging

**File:** `app/app/src/main/java/com/aikeyboard/app/ai/sticker/WhatsAppStickerContentProvider.kt`

**Current state — read it first:** `assertAllowedCaller()` at line 112-117:

```kotlin
private fun assertAllowedCaller() {
    val caller = callingPackage
    if (caller == null || caller !in ALLOWED_CALLERS) {
        throw SecurityException("Caller $caller not allowed")
    }
}
```

`ALLOWED_CALLERS` (find via grep in the same file — should be a `setOf("com.whatsapp", "com.whatsapp.w4b")`). Phase 9b smoke reported WhatsApp shows a generic "couldn't add pack" toast and never renders pack confirmation. The suspected root cause is WhatsApp's worker process (which fetches sticker bytes) reports a `callingPackage` like `com.whatsapp` itself but the **uid** belongs to a different process group, OR the worker uses a process name we haven't seen.

**Phase 12 instrumentation — debug-only:**

```kotlin
import android.os.Binder
import android.util.Log
import com.aikeyboard.app.latin.BuildConfig

private fun assertAllowedCaller() {
    val caller = callingPackage
    if (BuildConfig.DEBUG) {
        Log.d(TAG, "assertAllowedCaller caller=$caller uid=${Binder.getCallingUid()} pid=${Binder.getCallingPid()}")
    }
    if (caller == null || caller !in ALLOWED_CALLERS) {
        if (BuildConfig.DEBUG) {
            Log.w(TAG, "rejecting caller=$caller (not in ALLOWED_CALLERS)")
        }
        throw SecurityException("Caller $caller not allowed")
    }
}

companion object {
    // ... existing constants
    private const val TAG = "WhatsAppStickers"
}
```

**Privacy invariant:** `BuildConfig.DEBUG` gates ALL log calls. Release builds emit zero logs from this provider. The release-only path is unchanged from Phase 9b. The TAG is added new (the class previously had no logger).

**Smoke procedure** (carry into §16.5):
1. Build debug APK: `./gradlew :app:assembleFdroidDebug`
2. Install with a wildcard so the filename change from §8.2 doesn't matter: `adb install -r app/app/build/outputs/apk/fdroid/debug/*.apk`. (If your debug build was made before the §8.2 version bump, the filename is `ai-keyboard-3.9-fdroid-debug.apk`; after the bump, `ai-keyboard-0.1.0-fdroid-debug.apk`. The wildcard removes the ordering dependency.)
3. Open the app's Sticker Packs settings, create or import a pack, tap "Add to WhatsApp".
4. Watch logcat: `adb logcat -v color WhatsAppStickers:V *:S`
5. Trigger the WhatsApp pack-add intent. Note the `callingPackage` / `uid` values that come through, and which (if any) are being rejected.
6. **If WhatsApp does NOT make any provider calls** (zero logs even after the add intent fires), the bug is upstream of the provider — most likely `AddToWhatsAppHelper.kt` is sending the wrong intent shape or WhatsApp can't find our `<provider>` in the manifest. Re-check `AndroidManifest.xml` provider declaration vs. WhatsApp's `StickerContentProvider` spec.
7. **If WhatsApp DOES make calls but a non-`com.whatsapp` package is rejected**, extend `ALLOWED_CALLERS` to include the surfaced package (likely something like `com.whatsapp.contentproviderworker` if WhatsApp recently introduced a worker process). One-line fix.
8. **If the calls go through but WhatsApp still rejects the pack visually**, the bug is in our metadata/sticker bytes — re-run the WhatsApp `StickerPackValidator` (live in the file by that name in our codebase + WhatsApp's published validator if accessible) to see what they're rejecting.

**Outcome of §16.5 determines whether ALLOWED_CALLERS gets a real fix in this commit, or whether the investigation result + a documentation note goes into CHANGELOG.md + carries to v0.2.0.** Either way, the debug-logging commit ships.

**Tests:** Add a JVM unit test in `app/app/src/test/java/com/aikeyboard/app/ai/sticker/WhatsAppCallerPolicyTest.kt`.

**Required source change first.** `ALLOWED_CALLERS` in `WhatsAppStickerContentProvider.kt` is currently `private val ALLOWED_CALLERS = setOf("com.whatsapp", "com.whatsapp.w4b")` inside the companion object (find via grep; the const lives near line 141). To make the test compile, change the visibility from `private` to `internal`:

```kotlin
companion object {
    // ...other constants...
    internal val ALLOWED_CALLERS = setOf("com.whatsapp", "com.whatsapp.w4b")
}
```

`internal` is the right visibility because (a) the test source set lives in the same Gradle module as the provider, so `internal` is reachable from tests, and (b) `internal` keeps the const non-public-API to the rest of the world (R8 will not synthesize a getter visible outside the module). No `@VisibleForTesting` annotation needed since `internal` already restricts visibility appropriately.

Then the test:

```kotlin
package com.aikeyboard.app.ai.sticker

import kotlin.test.Test
import kotlin.test.assertTrue

class WhatsAppCallerPolicyTest {
    @Test fun `ALLOWED_CALLERS contains both whatsapp consumer and business`() {
        assertTrue("com.whatsapp" in WhatsAppStickerContentProvider.ALLOWED_CALLERS)
        assertTrue("com.whatsapp.w4b" in WhatsAppStickerContentProvider.ALLOWED_CALLERS)
    }
}
```

This is a contract pin: a future rename of `ALLOWED_CALLERS` shouldn't silently drop WhatsApp Business.

---

## §7. `ObsoleteSdkInt` fix at TermuxValidationActivity.kt:199

**File:** `app/app/src/main/java/com/aikeyboard/app/ai/termux/TermuxValidationActivity.kt:199`

The lint warning has been on the carry-over list since Phase 4. Read the file's lines 195-205 to see the actual SDK_INT check. Given `minSdk = 29` (build.gradle.kts:15) and `startForegroundService` requires API 26, the check at line 199 is either `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)` (always true since minSdk=29 > 26) or similar.

**Fix:** delete the `if (...)` guard and unconditionally call `startForegroundService(intent)`. The `else` branch's `startService(intent)` (line 202) becomes dead code and is also removed.

After the fix, run `./gradlew :app:lintFdroidDebug` and confirm zero new lint errors. **Note:** `lint-baseline.xml` does NOT currently contain a TermuxValidationActivity `ObsoleteSdkInt` entry — the warning was passing lint as a valid SDK_INT guard until now, so the baseline never grew an entry for it. Removing the guard makes the warning go away entirely without changing baseline. Do NOT expect a `git diff lint-baseline.xml` from this fix; that's the wrong verification step. The correct verification is: run lint, confirm exit code 0 and no new entries in the lint report. The 44 pre-existing `ObsoleteSdkInt` baselines (in HeliBoard's `compat/`, `accessibility/`, `settings/`, `latin/`, etc. — all upstream code) stay in baseline unchanged.

**Tests:** Lint pass with no new errors is the regression gate.

---

## §8. Release signing config + keystore.properties template

### 8.1 `app/app/build.gradle.kts` changes

Add a `signingConfigs.release` block. Read the current file structure first; the existing `signingConfigs { }` block in build.gradle.kts may not exist yet (Phase 1's signing was implicit debug). Target shape:

```kotlin
import java.util.Properties

android {
    // ...
    signingConfigs {
        create("release") {
            val keystorePropsFile = rootProject.file("keystore.properties")
            val forceReleaseSigning = project.hasProperty("forceReleaseSigning")
            if (keystorePropsFile.exists()) {
                val props = Properties().apply { keystorePropsFile.inputStream().use { load(it) } }
                storeFile = rootProject.file(props.getProperty("keystore.path"))
                storePassword = props.getProperty("keystore.password")
                keyAlias = props.getProperty("key.alias")
                keyPassword = props.getProperty("key.password")
            } else if (forceReleaseSigning) {
                throw GradleException(
                    "keystore.properties not found and -PforceReleaseSigning was passed. " +
                    "Release signing is required in this context. See BUILD.md."
                )
            } else {
                logger.warn(
                    "keystore.properties not found; release builds will be debug-signed. " +
                    "Pass -PforceReleaseSigning in CI/release contexts to hard-fail instead. " +
                    "See BUILD.md for setup instructions."
                )
            }
        }
    }
    buildTypes {
        release {
            // existing flags
            signingConfig = if (rootProject.file("keystore.properties").exists())
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
            // existing minify / proguard config stays
        }
    }
}
```

The `-PforceReleaseSigning` Gradle property is the CI safety net: if a release pipeline runs without keystore.properties (secret injection silently failed, vault timeout, wrong env-var name), the build hard-fails instead of silently shipping a debug-signed APK. Local contributors building debug-signed for development don't pass the flag and get the warn-and-fall-back path. Document the flag in `BUILD.md` (§8.4) under "CI / release builds".

### 8.2 Version bump

In `app/app/build.gradle.kts` defaultConfig block (lines 17-18):
```kotlin
versionCode = 1
versionName = "0.1.0"
```

`base.archivesBaseName` (line 57, `"ai-keyboard-" + defaultConfig.versionName`) is read at configuration time, so the APK filenames automatically become `ai-keyboard-0.1.0-fdroid-release.apk`, `ai-keyboard-0.1.0-play-release.apk`, etc. — no further wiring needed.

**Upgrade-path caveat to document.** Bumping `versionCode` from `3901` to `1` is a downgrade from Android's perspective. Android will refuse `adb install -r` on top of an existing install with versionCode 3901 — the user must `adb uninstall com.aikeyboard.app[.debug]` first. Both `BUILD.md` (§8.4) and `README.md` (§13) must note this in their install / upgrade sections: **"If you have a previous dev-build (versionCode 3901) installed, uninstall it before installing v0.1.0 (versionCode 1). Otherwise Android reports 'App not installed'."** This caveat only applies once — from v0.1.0 forward, versionCode increments monotonically per release.

### 8.3 `keystore.properties` template

Create a NEW file at repo root: `keystore.properties.example`:

```properties
# Copy this file to keystore.properties (gitignored) and fill in real values.
# See BUILD.md for keytool command to generate the keystore.
keystore.path=release-keys/aikeyboard-release.jks
keystore.password=CHANGE_ME
key.alias=aikeyboard-release
key.password=CHANGE_ME
```

Add to `.gitignore` immediately after the existing entries:

```
# Phase 12: release signing — never commit
keystore.properties
release-keys/
*.jks
*.keystore
```

### 8.4 `BUILD.md` (new)

Create `BUILD.md` at repo root with:

- JDK + SDK + NDK version requirements (copy from README's current build prerequisites section).
- Keystore generation command:
  ```bash
  mkdir -p release-keys
  keytool -genkey -v -keystore release-keys/aikeyboard-release.jks \
    -keyalg RSA -keysize 4096 -validity 36500 \
    -alias aikeyboard-release
  ```
  (36500 days = 100 years, F-Droid recommends long validity to avoid mid-app-life rekeys.)
- `keystore.properties` setup: copy from `keystore.properties.example`, replace passwords.
- Build commands for each variant:
  ```bash
  ./gradlew :app:assembleFdroidRelease  # signed if keystore.properties present, debug-signed otherwise
  ./gradlew :app:assemblePlayRelease
  ```
- **CI / release builds:** pass `-PforceReleaseSigning` to convert the missing-keystore-properties fallback from a Gradle warning into a hard `GradleException`. Use this flag in any pipeline that publishes APKs externally so a misconfigured secret injection step can't ship debug-signed.
  ```bash
  ./gradlew :app:assembleFdroidRelease -PforceReleaseSigning
  ```
- Reproducible build verification command (§16.8).
- **Signing verification — use `apksigner`, NOT `jarsigner`.** Android APKs use the APK Signature Scheme v2/v3 (enabled by AGP whenever `minSdk >= 24`; ours is 29). `jarsigner -verify` only validates the legacy v1 JAR-style signature and reports "jar verified" on a v2-only APK as a false positive. Use:
  ```bash
  $ANDROID_HOME/build-tools/<version>/apksigner verify --verbose --print-certs \
      app/app/build/outputs/apk/fdroid/release/*.apk
  ```
  Replace `<version>` with the installed build-tools version (e.g., `36.1.0`; `ls $ANDROID_HOME/build-tools/` to confirm). Expected output: `Verified using v1 scheme: …`, `Verified using v2 scheme: …`, `Verified using v3 scheme: …`, plus the cert SHA-256.
- **Upgrading from a pre-v0.1.0 dev build:** if you previously installed an `ai-keyboard-3.9-*-debug.apk` (versionCode 3901), Android will refuse the v0.1.0 install (versionCode 1) as a downgrade. Run `adb uninstall com.aikeyboard.app.debug` (or the non-debug package id) first. From v0.1.0 onwards, versionCode increments monotonically and `adb install -r` will work.
- Note: F-Droid auto-publish typically signs the APK with F-Droid's own key, not the developer's. The keystore here is for sideloaded distribution + GitHub Releases. The F-Droid build server will use its own keystore.

---

## §9. fastlane/metadata/android/ + LICENSE + NOTICE

### 9.1 Directory structure

Create at repo root:

```
fastlane/
  metadata/
    android/
      en-US/
        title.txt
        short_description.txt
        full_description.txt
        changelogs/
          1.txt
        images/
          icon.png                       # placeholder; see README §README-Distribution
          featureGraphic.png             # placeholder
          phoneScreenshots/
            .gitkeep                     # placeholder so the directory commits
```

### 9.2 Content for each file

**`title.txt`** (max 50 chars per F-Droid spec):
```
AI Keyboard
```

**`short_description.txt`** (max 80 chars):
```
Privacy-first Android keyboard with on-device AI assistance and stickers.
```

**`full_description.txt`** (max 4000 chars, plain text — no markdown supported by F-Droid):
```
AI Keyboard is a privacy-respecting Android keyboard with personality-driven AI assistance, optional screen-context awareness, and sticker creation.

Features
- Forked from HeliBoard: full autocorrect, gesture typing, multi-language, suggestion strip
- AI rewrite: highlight or capture text, ask AI to refine, replace inline
- Read & Respond: optional screen-context summarization for thoughtful replies (requires AccessibilityService permission, off by default)
- Sticker engine: import any image, normalize to WhatsApp-compatible format, send via COMMIT_CONTENT or Add-to-WhatsApp
- Three backends, your choice:
  - Remote API keys: Anthropic Claude, Google Gemini, xAI Grok (BYOK, direct HTTPS, no proxy)
  - Local LAN: point at your own Ollama / vLLM / LM Studio server
  - Termux bridge: run Claude Code, Codex, or Gemini CLI locally on the same device

Privacy
- No telemetry, no analytics, no ads
- API keys stored in Android Keystore (Tink AEAD)
- All AI requests go directly to your selected backend; no intermediate server
- Always-On Read & Respond defaults OFF on every restart; you must re-enable explicitly
- Network security config restricts cleartext HTTP to LAN backends only
- Source code: GPL-3.0, see GitHub link below

Setup
1. Enable AI Keyboard under Settings → System → Languages & input → On-screen keyboard
2. Open AI Keyboard's settings → Backends; pick a backend and configure
3. (Optional) Pair with personas for tone-tuned rewrites

Forked from HeliBoard <https://github.com/Helium314/HeliBoard>.
Bug reports, feature requests, code: https://github.com/<your-org>/ai-keyboard
```

(Replace `<your-org>` with the actual GitHub org or username once known; if unknown, leave as `<your-org>` and document the placeholder in CHANGELOG so it gets filled in before tagging.)

**`changelogs/1.txt`** (max 500 chars):
```
First release.

Forked from HeliBoard 3.9. Adds:
- AI rewrite via Anthropic, Google, xAI, local LAN, or Termux-hosted CLIs
- Read & Respond screen context (opt-in)
- Sticker engine with WhatsApp pack support
- Three configurable backends; API keys stay on device
```

**`images/icon.png` and `images/featureGraphic.png`:** create placeholder PNGs (any 512×512 image for icon, 1024×500 for featureGraphic). README §13 instructs the developer to replace these before tagging — they need real artwork. The placeholders are intentionally bad-looking (e.g., a 512×512 PNG with the text "REPLACE WITH ICON" rendered) so a release reviewer can't miss the placeholder. Generate via ImageMagick or a one-off Python+Pillow script — do not commit binary garbage, commit something obviously a placeholder.

Acceptable Phase 12 form for `.gitkeep` in `phoneScreenshots/`: empty file, just commits the directory.

### 9.3 LICENSE

Create `LICENSE` at repo root with the full GPL-3.0-only text. Source: `https://www.gnu.org/licenses/gpl-3.0.txt`. Do NOT use `gpl-3.0-or-later` — HeliBoard's source headers are `SPDX-License-Identifier: GPL-3.0-only` so we match.

The text file is ~35KB. Commit it verbatim from the FSF canonical text.

### 9.4 NOTICE

Create `NOTICE` at repo root listing third-party code:

```
AI Keyboard
Copyright (C) 2026 <your-org>

This product includes software from the following projects:

HeliBoard
  https://github.com/Helium314/HeliBoard
  GPL-3.0-only
  Forked from the keyboard surface, autocorrect, gesture typing, layouts.

AOSP LatinIME
  https://android.googlesource.com/platform/packages/inputmethods/LatinIME/
  Apache-2.0
  Original Android keyboard implementation that HeliBoard forked from.

Jetpack Compose
  https://developer.android.com/jetpack/compose
  Apache-2.0

Ktor
  https://ktor.io
  Apache-2.0

kotlinx.serialization, kotlinx.coroutines
  https://github.com/Kotlin/kotlinx.serialization, https://github.com/Kotlin/kotlinx.coroutines
  Apache-2.0

Google Tink
  https://github.com/google/tink
  Apache-2.0

skydoves/colorpicker-compose
  https://github.com/skydoves/colorpicker-compose
  Apache-2.0

sh.calvin.reorderable
  https://github.com/Calvin-LL/Reorderable
  Apache-2.0

@anthropic-ai/claude-code (Termux bridge integration only)
  https://github.com/anthropics/claude-code
  Proprietary (Anthropic Commercial Terms)
  Invoked as an external CLI; not bundled.

@google/gemini-cli (Termux bridge integration only)
  https://github.com/google-gemini/gemini-cli
  Apache-2.0
  Invoked as an external CLI; not bundled.

@openai/codex (Termux bridge integration only)
  https://github.com/openai/codex
  Apache-2.0
  Invoked as an external CLI; not bundled.
```

Audit `build.gradle.kts` `dependencies` block once before commit — if any dep is missing from this NOTICE list, add it. Run `./gradlew :app:dependencies --configuration releaseRuntimeClasspath > /tmp/deps.txt` and cross-reference.

The NOTICE file is referenced from `README.md` and from `LICENSE` ("see NOTICE for third-party attributions"). Do not fold NOTICE into LICENSE; F-Droid's reviewer expects them separate.

---

## §10. Health diagnostics screen

**Files:**
- New: `app/app/src/main/java/com/aikeyboard/app/ai/ui/health/HealthDiagnosticsScreen.kt`
- New: `app/app/src/main/java/com/aikeyboard/app/ai/ui/health/HealthDiagnosticsRoute.kt`
- New: `app/app/src/main/java/com/aikeyboard/app/ai/ui/health/HealthDiagnostic.kt` (data class + checker)
- Modified: `app/app/src/main/java/com/aikeyboard/app/ai/ui/AiNavHost.kt` (or wherever the AiSettings NavHost is — find via grep)
- Modified: `app/app/src/main/java/com/aikeyboard/app/ai/ui/backends/BackendsScreen.kt` (add a "Run diagnostics" TextButton at the bottom of the screen)

### 10.1 Checks to run

The screen shows five rows, each with a status icon + label + optional one-line detail:

1. **IME enabled** — `InputMethodManager.getEnabledInputMethodList()` contains our `LatinIME` component. Pass = green check; fail = red cross + intent button "Open keyboard settings".
2. **IME selected as current** — `Settings.Secure.getString(contentResolver, DEFAULT_INPUT_METHOD)` matches our component. Pass / partial-pass / fail.
3. **AccessibilityService bound** (fdroid flavor only) — `AccessibilityManager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)` contains `ScreenReaderService`. On play flavor, this row is hidden entirely. Pass = green; not-bound = neutral grey "Optional, not enabled".
4. **Backend reachable** — Run `BackendResolver.resolve().isAvailable()` (synchronous) for whichever backend is currently selected. RemoteApi: API key exists → reports configured; LocalLan: configured URL non-empty → reports configured (does NOT do an actual HTTP probe — that's a separate "Test connection" button in BackendsScreen); TermuxBridge: send a `GET /health` over HTTP with 1s timeout. Pass / fail per backend semantics.
5. **Bridge version + provider list** (TermuxBridge backend only) — Show `GET /providers` output as `id: available (reason)` lines. If TermuxBridge is not the selected backend, this row is hidden.

### 10.2 Wire shape

```kotlin
data class HealthDiagnostic(
    val label: String,
    val status: HealthStatus,
    val detail: String? = null,
    val recoveryIntent: Intent? = null
)
enum class HealthStatus { OK, WARN, FAIL, NEUTRAL, HIDDEN }
```

`HealthDiagnosticChecker` (a class with a `suspend fun runAll(): List<HealthDiagnostic>`) does the IO. Mark it `suspend` so the TermuxBridge `/health` probe doesn't block the main thread; use `withContext(Dispatchers.IO)` inside.

### 10.3 Copy to clipboard

The screen has a TopAppBar action: "Copy" — copies the full diagnostic report as plain text, format:

```
AI Keyboard Health Diagnostic — 2026-05-13 14:23:01
Version: 0.1.0 (fdroid release)

[OK] IME enabled
[OK] IME selected as current
[OK] AccessibilityService bound
[OK] Backend reachable: REMOTE_API (Anthropic)
[HIDDEN] Bridge providers (not using TermuxBridge)
```

Use `ClipboardManager.setPrimaryClip(ClipData.newPlainText("Health Diagnostic", report))`.

**Privacy invariant:** the report contains NO API keys, NO URLs (except `127.0.0.1:8787` which is loopback so safe), NO user input. Only enums + structural status. Backend identity (`REMOTE_API (Anthropic)`) is the most-private detail surfaced.

### 10.4 Tests

JVM unit tests for `HealthDiagnosticChecker` are valuable but require mocking `InputMethodManager` + `AccessibilityManager`. Phase 12 ships **one** test that's pure:

`HealthReportFormatTest.kt`:
- Test that an empty diagnostic list formats to `"AI Keyboard Health Diagnostic — <timestamp>\nVersion: ..."` (header only).
- Test that a `HIDDEN` status row is omitted from the report.
- Test that the report contains zero `@` characters (cheap heuristic for "no email / no API key leak").

The bigger checker-level tests can wait for Phase 12.5 or v0.2.0 — Robolectric SDK-36 is the blocker.

### 10.5 NavHost wiring

The NavHost is at `app/app/src/main/java/com/aikeyboard/app/ai/ui/AiSettingsNavHost.kt` (confirm via grep before editing — Phase 11 changed structure may have moved it). It uses an `AiSettingsRoutes` object that holds route-name constants (the established pattern from Phase 2 onwards — do NOT introduce raw `"health"` strings).

**Step 1.** Add the route constant to `AiSettingsRoutes`:

```kotlin
object AiSettingsRoutes {
    // ...existing constants
    const val HEALTH = "health"
}
```

**Step 2.** Add the route to the NavHost's `NavHost(...) { ... }` block:

```kotlin
composable(AiSettingsRoutes.HEALTH) {
    HealthDiagnosticsRoute(onBack = { nav.popBackStack() })
}
```

**Step 3.** Add an `onOpenHealth: () -> Unit` parameter to `BackendsScreen`'s signature (the existing pattern uses callback parameters, NOT raw `navController` — Phase 10 added `onOpenLocalLanEdit: () -> Unit` for the same reason). Read the current `BackendsScreen` parameter list before editing; the call lives in `AiSettingsNavHost.kt` somewhere around the `BackendsScreen(...)` invocation (Phase 10 wired four named params). Update the call site:

```kotlin
composable(AiSettingsRoutes.BACKENDS) {  // or whatever the constant is
    BackendsScreen(
        onBack = { nav.popBackStack() },
        onEditProvider = { /* existing */ },
        onOpenTermuxBridge = { /* existing */ },
        onOpenLocalLanEdit = { /* existing, Phase 10 */ },
        onOpenHealth = { nav.navigate(AiSettingsRoutes.HEALTH) },  // NEW
    )
}
```

**Step 4.** Inside `BackendsScreen`, add the TextButton at the bottom (under the existing backend list):

```kotlin
TextButton(onClick = onOpenHealth) {
    Text(stringResource(R.string.ai_health_diagnostics))
}
```

**Step 5.** Add the strings to `app/app/src/main/res/values/ai_strings.xml`:

```xml
<string name="ai_health_diagnostics">Run diagnostics</string>
<string name="ai_health_diagnostics_title">Health diagnostics</string>
<string name="ai_health_diagnostics_copy">Copy report</string>
```

**Compile-check before commit.** All four files (`AiSettingsRoutes.kt` or whatever holds it, `AiSettingsNavHost.kt`, `BackendsScreen.kt`, `ai_strings.xml`) plus the three new `health/` package files must compile together. If only `BackendsScreen.kt` is updated but the NavHost call site isn't, the build fails on the missing-parameter-default error. This is the exact pitfall Phase 10's `onOpenLocalLanEdit` plan also faced.

### 10.6 R8 keep rule

Phase 12 introduces `HealthDiagnosticChecker` (a class with a single `suspend fun runAll()` method invoked from one composable lambda in `HealthDiagnosticsRoute`). The "4-for-4 expect-and-verify" precedent from prior phases (BackendResolver, ReadRespondPromptBuilder, StickerCommitter, AlwaysOnProxy, LocalLanBackend) showed that R8 in release mode can inline a single-call-site `suspend fun` into the composable lambda, producing a release build where the health screen silently reports all rows as FAIL because the checker was inlined away from the dex.

Add to `app/app/proguard-rules.pro` (or the file the prior phases used — confirm via grep for `"-keep class com.aikeyboard.app.ai"`):

```
# Phase 12: keep HealthDiagnosticChecker observable in dex. Single call site in
# HealthDiagnosticsRoute, prior precedent showed R8 will inline single-call-site
# suspend fun bodies into composable lambdas otherwise.
-keep class com.aikeyboard.app.ai.ui.health.HealthDiagnosticChecker {
    public *** runAll(...);
}
```

After release build (§16.1), verify with `apkanalyzer dex packages app/app/build/outputs/apk/fdroid/release/*.apk | grep HealthDiagnosticChecker` — the class should be observable in the dex.

---

## §11. bridge/README.md update for Codex

**File:** `bridge/README.md` (find it; if it doesn't exist, the spec is in `bridge/package.json` description — create it).

Phase 11 noted as a Phase 12 polish: "`bridge/README.md` still mentions only Claude + Gemini in the Prerequisites section." Add a Codex prereq subsection mirroring the Claude/Gemini ones:

- Install: `npm i -g @openai/codex@0.42.0` (note the version pin; reference `setup.sh`'s `CODEX_VERSION` constant).
- Auth: `codex login` device-code flow. Stores credentials at `~/.codex/auth.json`. Alternative: `OPENAI_API_KEY` env var.
- Verify: `which codex` + `codex --version` should show 0.42.0.
- Termux note: if v0.42+ ever stops working post-Phase 12 (Termux update, Bionic ABI change, etc.), reference the prctl regression openai/codex#6757.

While editing, also add a one-line note that the bridge supports xAI Grok via direct API only (Phase 11), NOT via the bridge — so Grok doesn't appear in `/providers` output.

---

## §12. CHANGELOG.md

**File:** new at repo root, `CHANGELOG.md`.

Format: keep-a-changelog convention. Section per phase, chronological:

```markdown
# Changelog

All notable changes to AI Keyboard, the privacy-respecting Android IME forked from HeliBoard.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] — 2026-05-13

First release. Forked from HeliBoard v3.9 with the following additive features:

### Added
- AI command row with rewrite + Read & Respond + sticker picker (Phases 2, 2.5, 9a).
- AI Settings activity (Compose) for personas, backends, stickers (Phases 2, 9a).
- Persona engine with system-prompt + few-shot templates (Phase 3a).
- SecureStorage backed by Tink AEAD over Android Keystore (Phase 3a).
- Three networking backends behind one AiClient interface (Phases 3a, 5b, 6, 10, 11):
  - Remote API: Anthropic Claude, Google Gemini, xAI Grok (BYOK, direct HTTPS)
  - Local LAN: user-configurable Ollama / vLLM / LM Studio
  - Termux bridge: Claude Code, Gemini CLI, Codex
- Always-On Read & Respond foreground service (fdroid flavor) (Phase 8).
- Quick Settings tile for Always-On toggle (Phase 8).
- Sticker engine: import → normalize → COMMIT_CONTENT or WhatsApp pack export (Phases 9a, 9b).
- Termux setup wizard (Phase 5b) + setup.sh bootstrap (Phases 5a, 11).

### Carried from HeliBoard 3.9 unchanged
- Autocorrect, gesture typing, multi-language, suggestion strip.
- Keyboard themes, layouts.
- Spell checker.

### Known issues
- Always-On FGS may not re-post after boot on Android 14+ due to background-start restrictions. Workaround: open the keyboard once after boot. (Phase 8 / 12 investigation.)
- WhatsApp may reject pack on first add; the in-keyboard error and the WhatsApp toast are both surfaced. Diagnostics added in Phase 12. (Phase 9b carry-over.)
- 6 Robolectric SDK-36 tests fail at compile time; this is upstream and tracked in lint baseline.

### Privacy
- No telemetry. No analytics. No ads.
- API keys stored in Android Keystore.
- All AI requests go directly to the user-selected backend.
- Source code: GPL-3.0-only.

## [Unreleased]
- Model-override UI per provider (current defaults: claude-3-5-sonnet-latest, gemini-2.0-flash-latest, grok-2-latest).
- OpenAI-compatible remote backend (consolidates Grok / future DeepSeek / Mistral La Plateforme).
- Multi-pack creation flow polish.
- Robolectric SDK-36 investigation.
```

Adjust default model names to match the actual `Provider.kt` `defaultModel` values — read the file before committing.

---

## §13. README.md rewrite

**File:** `README.md`, currently 57 lines labeled "Phase 1 scaffold". Rewrite for v0.1.0 release.

Target structure (markdown):

```
# AI Keyboard

(one-paragraph elevator pitch — copy from full_description.txt's opening, but markdown-formatted)

[![release](https://img.shields.io/github/v/release/<your-org>/ai-keyboard)](releases)
[![license](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

(featureGraphic.png here — same image as fastlane/metadata/.../featureGraphic.png)

## Features
- ... (mirror fastlane full_description.txt feature list)

## Screenshots
(placeholder grid — see fastlane/metadata/android/en-US/images/phoneScreenshots/)

## Install
- F-Droid: <link to F-Droid page once published>
- GitHub Releases: <link to signed APKs once tagged>
- Build from source: see BUILD.md

## Privacy
- No telemetry, no analytics, no ads.
- API keys stored in Android Keystore (Tink AEAD).
- All AI requests go directly to your selected backend.
- Source: GPL-3.0-only.
- See `docs/PRIVACY.md` for the full disclosure.

## Backends
(one paragraph each for the three backends, with screenshots if available)

## Setup
1. Install from F-Droid / GitHub.
2. Enable under Settings → System → Languages & input.
3. Open AI Keyboard's AI Settings (gear icon in command row).
4. Pick a backend; provide API key OR run setup.sh in Termux.

## Build from source
See [BUILD.md](BUILD.md) for full instructions.

Quick start:
```
./gradlew :app:assembleFdroidDebug
```

## Architecture
See [ARCHITECTURE.md](ARCHITECTURE.md). Key decisions: HeliBoard fork, Views+Compose split, three backends, AccessibilityService opt-in.

## License
GPL-3.0-only. See [LICENSE](LICENSE) for full text and [NOTICE](NOTICE) for third-party attributions.

## Acknowledgements
Forked from [HeliBoard](https://github.com/Helium314/HeliBoard), which forked from AOSP LatinIME.
```

Replace `<your-org>` with the actual GitHub org / username, or document the placeholder in CHANGELOG so it gets filled in before tagging.

The "Phase 1 scaffold" language must be removed entirely — v0.1.0 is the first real release.

`docs/PRIVACY.md` — create this NEW file at `docs/PRIVACY.md` with one page of structured privacy disclosure: what data is stored, where (on-device only), what's sent over the network (per-backend), what's NOT sent (telemetry / analytics / IME usage data). Reference Android `targetSdk=36`'s data-safety taxonomy. Keep it short — under 1 page.

---

## §14. Privacy logcat re-audit

**Goal:** zero logs across all phases leak `t.message`, URLs, request bodies, response bodies, API keys, user prompts, or user input.

**Procedure:**

```bash
cd /Users/kacper/SDK/apk-dev/ai-keyboard/app/app/src
# All Log.* calls in production code:
grep -rn "Log\\.\\(d\\|i\\|w\\|e\\)" main/java fdroid/java play/java | wc -l
# Inspect every Log.* call:
grep -rn "Log\\.\\(d\\|i\\|w\\|e\\)" main/java fdroid/java play/java > /tmp/all-log-calls.txt
# Also scan for UI-bound sinks that could forward to logcat or persistent storage and
# leak the same data classes the Log.* grep catches. TermuxValidationActivity.kt:207
# is a known instance of `appendDetail("error: ${e.message}\n")` — UI-bound but could
# easily be repurposed as a log sink if a future debug-build branch wires it through.
grep -rn "appendDetail\\|println\\|System\\.\\(err\\|out\\)" main/java fdroid/java play/java > /tmp/non-log-sinks.txt
```

For each entry in `/tmp/all-log-calls.txt`:
- Verify the second argument to `Log.<level>(TAG, msg)` contains no `${t.message}`, no `${request.url}`, no `${response.body}`, no `${input}`, no `${prompt}`, no `${apiKey}`.
- Acceptable: `${t.javaClass.simpleName}`, structural enum names, Boolean flags, action strings (Android constant strings), HTTP status codes (without URL), count/size integers, debug-build-only logs gated by `BuildConfig.DEBUG`.
- Edge case: `Log.d(..., "...$state...")` where `state` is a class with a `toString()` that may include data — verify the `toString()` overrides explicitly redact sensitive fields.

Track findings in `/tmp/privacy-audit-findings.md` and fix each before the §17 DoD signoff. The privacy invariant has been established since Phase 3a; any leak found here is a v0.1.0 release-blocker.

**Specific high-risk files to inspect carefully:**
- All `*Backend.kt` files (`RemoteApiBackend`, `LocalLanBackend`, `TermuxBridgeBackend`).
- `TermuxOrchestrator.kt`.
- `OllamaStreamParser.kt`, `OpenAiCompatStreamParser.kt`.
- `BootReceiver.kt` (new in §4).
- `WhatsAppStickerContentProvider.kt` (new debug-gated logs in §6).

**Bridge audit:**

```bash
cd /Users/kacper/SDK/apk-dev/ai-keyboard/bridge
grep -rn "console\\." . --exclude-dir=node_modules
```

For each: verify the args don't echo `messages` body, `system` prompt, `evt.message` from CLI events, request URLs, or stderr lines. Phase 11 verified the codex.js privacy posture; re-verify in case any phase-12 commit touched it.

---

## §15. Claude Code Termux compatibility recheck + Codex pin recheck

Per `PHASE_REVIEW.md` Phase 12 mandatory:

### 15.1 Claude Code

Check the latest stable release of `@anthropic-ai/claude-code`:

```bash
npm view @anthropic-ai/claude-code dist-tags
npm view @anthropic-ai/claude-code@latest engines
npm view @anthropic-ai/claude-code@latest dependencies
```

Look for:
1. Has the JS entry point `cli.js` returned? (`npm view @anthropic-ai/claude-code@<latest> main`)
2. Are aarch64-Bionic binaries shipped? (Inspect the package contents or check the changelog for "Termux", "Bionic", "aarch64-linux-musl".)
3. Has the autoupdater been made opt-in instead of opt-out? (Read the latest changelog — if `DISABLE_AUTOUPDATER=1` is no longer needed, simplify `setup.sh`.)

If (1) or (2) is yes, bump the pin in `setup/setup.sh` from v2.1.112 to the latest stable; verify with the §16.10 smoke. If both are no, **document the recheck result in `PHASE_REVIEW.md`** by appending a dated line under the existing Phase 12 row:

```
- 2026-05-13: Claude Code recheck — v<latest> still requires glibc; pin remains 2.1.112.
```

If the recheck reveals a regression in v2.1.112 itself (e.g., autoupdater behavior change that breaks our wrapper), document and ship the workaround in `setup.sh`.

### 15.2 Codex

Same recheck for `@openai/codex`:

```bash
npm view @openai/codex dist-tags
npm view @openai/codex@latest engines
# Check the changelog or GitHub releases:
# https://github.com/openai/codex/releases
```

Specifically check:
1. Has the prctl regression openai/codex#6757 been fixed in any v0.43+ release?
2. Are the JSON event names (`agent_message`, `agent_message_delta`, `task_complete`) still emitted by `codex exec --json` in the latest stable?
3. Has device-code OAuth been promoted to the recommended setup flow?

If (1) is yes AND (2) holds, bump the pin from `CODEX_VERSION="0.42.0"` to the latest stable; re-run the §16.10 smoke. If (1) is no, document the recheck result similarly to §15.1 and hold at 0.42.0.

**Test gate before bumping any pin:** `bridge/test/codex.test.js`'s 20 tests must still pass against the new version's event-name schema. If they fail, the schema drifted and the adapter needs updating — DEFER the version bump and document in CHANGELOG.

---

## §16. Smoke tests (final consolidated pass)

This is the v0.1.0 release smoke. All scenarios must pass before §17 DoD. The runner is the human reviewer on a Pixel 6 Pro (serial `1B101FDEE0006U`); each scenario expects an `adb`-flavor verification.

### 16.1 Build matrix passes

```bash
cd app
./gradlew clean
./gradlew :app:assembleFdroidDebug :app:assembleFdroidRelease :app:assemblePlayDebug :app:assemblePlayRelease
```

Expected:
- All four APKs produce.
- fdroid release is signed by `aikeyboard-release` if `keystore.properties` was provided, else debug-signed with a Gradle warning logged.
- play release similarly.
- APK filenames: `ai-keyboard-0.1.0-fdroid-release.apk` etc.

### 16.2 Lint clean (with documented warnings)

```bash
./gradlew :app:lintFdroidDebug :app:lintPlayDebug
```

Expected:
- 0 new errors.
- The 4 known pre-existing warnings remain: `AndroidGradlePluginVersion` (8.14 → 8.14.5), `GradleDependency` (Compose BOM 2025.11.01 → 2026.05.00), `NewerVersionAvailable` (colorpicker-compose 1.1.3 → 1.1.4), and the **TermuxValidationActivity.kt:199 ObsoleteSdkInt warning that the prior phases mentioned as a carry-over is gone** because §7 fixed it. The 44 pre-existing `ObsoleteSdkInt` entries in lint-baseline.xml (all in HeliBoard upstream code) remain.
- `git diff app/app/lint-baseline.xml` is **expected to be empty** — the §7 fix touches a non-baselined warning, so no baseline entry is added or removed. If the baseline DID change, audit what was added.

### 16.3 BootReceiver fix verification

1. Install fdroid debug build.
2. Open AI Settings → Always-On Read & Respond → toggle ON. Verify FGS chip in notification shade.
3. Force-stop the app: `adb shell am force-stop com.aikeyboard.app.debug`.
4. Reboot: `adb reboot`. Wait for boot + unlock (PIN/fingerprint as the user).
5. After unlock, check logcat: `adb logcat -v color BootReceiver:V AlwaysOnService:V *:S`.
6. Expected logs (in order):
   ```
   BootReceiver: onReceive action=android.intent.action.BOOT_COMPLETED
   BootReceiver: alwaysOnEnabled=true
   BootReceiver: AlwaysOnProxy.start invoked
   AlwaysOnService: onCreate / startForeground (if AlwaysOnService gains its own log line in §4 follow-up)
   ```
7. Verify the FGS chip is visible in the notification shade.

**Outcome decision tree:**
- All four logs present + chip visible → BUG FIXED. Mark §16.3 pass.
- `onReceive` log present but `alwaysOnEnabled=false` → Tink can't read post-boot. Document; the partial-pass with documentation is acceptable for v0.1.0.
- `onReceive` log present but `AlwaysOnProxy.start failed: <ExceptionClass>` → root cause identified. If the exception class is `BackgroundServiceStartNotAllowedException` (Android 14+ restriction), document in README and CHANGELOG that the FGS re-arms on first keyboard open after boot. Acceptable v0.1.0.
- `onReceive` never logs → receiver isn't being invoked at all. Investigate manifest + AndroidManifest merger output (`./gradlew :app:processFdroidDebugMainManifest` then inspect the merged manifest under `app/app/build/intermediates/merged_manifests/`). This is a release-blocker.

### 16.4 In-keyboard error banner

1. Open any text field where AI Keyboard is selected.
2. Enable AirPlane mode (kills network).
3. Long-press AI rewrite. Expected: in-keyboard banner appears ABOVE the command row reading "No backend available" or similar (depending on configured backend). Banner dismisses after ~2.5s.
4. Verify the banner does NOT render behind any keyboard surface.
5. Verify Toast is no longer used: `adb logcat` should not show any Toast lines from `Toast.java` originating from our package.

Pass criterion: banner visible, auto-dismisses, no Toast logs.

### 16.5 WhatsApp caller-package investigation

(Per §6 procedure.)

1. Build debug APK with the new logging.
2. Install. Create a sticker pack with 3 valid stickers.
3. Tap "Add to WhatsApp" from the pack-edit screen.
4. Watch logcat: `adb logcat -v color WhatsAppStickers:V *:S`.
5. Document the observed `callingPackage` + `uid` values.
6. Outcome: either a fix lands (extend ALLOWED_CALLERS) or a documented investigation result lands in CHANGELOG.md. Either is acceptable for §17 DoD.

### 16.6 Health diagnostics screen

1. Open AI Settings → Backends → tap "Run diagnostics".
2. Verify 5 rows appear (or 4 if play flavor — a11y row hidden).
3. With Always-On disabled and a configured backend, verify rows 1-2 are green, row 3 is neutral grey, row 4 is green, row 5 is hidden.
4. Tap "Copy report" → paste into a chat / note → verify the format.
5. Verify the report contains no `@` characters (heuristic for no email leak).

Pass criterion: 5 rows render correctly, copy works, no privacy violation.

### 16.7 Privacy logcat clean (per-backend)

For each backend (RemoteApi-Anthropic / RemoteApi-Gemini / RemoteApi-Grok / LocalLan / TermuxBridge), trigger one rewrite request that succeeds AND one that fails (toggle airplane mode mid-stream). For each:

```bash
adb logcat -v color > /tmp/logcat-<backend>-<scenario>.log
```

Inspect the log for any of: API key fragments, prompt text, response body, URL with query params, user input. Should be zero hits.

Pass criterion: 10 logs (5 backends × 2 scenarios) all clean.

### 16.8 Reproducible build verification

The §1.3 promise. Run on the build machine:

```bash
cd /Users/kacper/SDK/apk-dev/ai-keyboard
./gradlew clean
./gradlew :app:assembleFdroidRelease
sha256sum app/app/build/outputs/apk/fdroid/release/*.apk > /tmp/sha-machine1.txt
```

Run on a second machine (a fresh checkout of the same commit, same JDK 21, same SDK 36 + NDK 28):

```bash
git clone . /tmp/aikeyboard-fresh && cd /tmp/aikeyboard-fresh && git checkout phase/12-polish-release
./gradlew clean
./gradlew :app:assembleFdroidRelease
sha256sum app/app/build/outputs/apk/fdroid/release/*.apk
```

Compare hashes. Pass criterion: identical hash on both machines.

If they differ, run `diffoscope <apk1> <apk2>` to see what bytes differ. Common culprits: timestamp embedding in DEX, MANIFEST.MF resource ordering, signing block (use `apksigner` with `--in-source` to strip the signing block before comparison). Fix or document any irreducible diff.

**If reproducibility cannot be achieved by Phase 12 deadline, document in `BUILD.md` + `PHASE_REVIEW.md` as "deferred to v0.2.0" and move on — F-Droid will still build from source either way.**

### 16.9 Dex / manifest invariants

```bash
apkanalyzer manifest permissions app/app/build/outputs/apk/fdroid/release/ai-keyboard-0.1.0-fdroid-release.apk
apkanalyzer manifest permissions app/app/build/outputs/apk/play/release/ai-keyboard-0.1.0-play-release.apk
```

Expected: identical to §3.1 invariants. **Zero permission additions over Phase 11.**

```bash
apkanalyzer dex packages app/app/build/outputs/apk/fdroid/release/*.apk | grep -E "(BootReceiver|HealthDiagnostic|AlwaysOnService)"
```

Expected: all three classes present.

### 16.10 Termux bridge end-to-end (final)

(Requires Termux + Codex 0.42.0 device-code OAuth + xAI API key, deferred from Phase 11.)

1. Pixel 6 has Termux installed; if not, install F-Droid Termux 0.118+.
2. Run `setup/setup.sh` end-to-end choosing "all three" providers.
3. Verify `~/bin/claude --version` works, `~/.codex/auth.json` exists, gemini CLI is on PATH.
4. Start bridge: `cd ~/.config/aikeyboard-bridge && node server.js &`. Probe `curl http://127.0.0.1:8787/providers` — expect 3 providers, all available.
5. In AI Keyboard, switch backend to TermuxBridge → pick Codex. Run an AI rewrite. Expect a streaming response.
6. Switch to Claude. Repeat. Expect streaming.
7. Switch to Gemini. Repeat.
8. Add xAI API key in BackendsScreen. Switch to RemoteApi/Grok. Run a rewrite. Expect streaming from `api.x.ai`.
9. Toggle airplane mode mid-stream for Grok. Expect graceful error banner ("network error" or similar). Logcat clean (no URL/body).
10. Logcat audit (§14 invariants) — zero leaks.

Pass criterion: all 4 providers stream successfully; all error paths produce structural messages; zero privacy leaks.

### 16.11 Fresh-device install (the ARCHITECTURE.md "brand-new device" smoke)

This is the Phase 12 success criterion per `PHASE_REVIEW.md`: "Brand-new device, follow README from scratch — keyboard works without manual debugging."

1. Find or simulate a fresh device (factory reset Pixel 6, or use Android Studio AVD running Android 14 minimum).
2. Open the README. Follow it step by step exactly as written.
3. After each step, verify the expected outcome happens.
4. Note any step that requires the user to debug, search, or guess. Each note is a Phase 12.1 follow-up.
5. End state: keyboard works, one AI provider configured, one rewrite roundtrip succeeds.

Pass criterion: README is self-sufficient. No undocumented debugging.

---

## §17. Definition of Done

Every item below holds:

- [ ] All five §3.1 invariants verified (permissions, deps, MCP, tests, NSC).
- [ ] §4 BootReceiver diagnostic logging in place; §16.3 smoke run + outcome documented in CHANGELOG.
- [ ] §5 in-keyboard banner replaces Toast in CommandRowController; §16.4 smoke pass.
- [ ] §6 WhatsApp caller logging in place + at least one debug-build smoke run completed; outcome (fix landed OR investigation result documented) in CHANGELOG.
- [ ] §7 ObsoleteSdkInt fix landed at TermuxValidationActivity.kt:199; `./gradlew lintFdroidDebug` produces zero new errors; lint-baseline.xml unchanged (the warning was never baselined).
- [ ] §8 release signing config (with `-PforceReleaseSigning` Gradle property guard) + `keystore.properties.example` + `.gitignore` entry + `BUILD.md` shipped.
- [ ] §8.2 version bumped to 0.1.0 / versionCode 1.
- [ ] §9 fastlane/metadata/android/en-US/ structure populated with title, short_description, full_description, changelogs/1.txt, and image placeholders.
- [ ] §9.3 LICENSE (GPL-3.0-only) and §9.4 NOTICE files at repo root.
- [ ] §10 health diagnostics screen reachable via `AiSettingsRoutes.HEALTH`, `onOpenHealth` threaded through `BackendsScreen` signature and call site, R8 keep rule for `HealthDiagnosticChecker` landed in `proguard-rules.pro`, 5 rows render in fdroid release build (verified via `apkanalyzer dex packages` showing `HealthDiagnosticChecker` observable), copy-to-clipboard works.
- [ ] §11 bridge/README.md updated to include Codex.
- [ ] §12 CHANGELOG.md at repo root covering Phase 1 through Phase 12.
- [ ] §13 README.md rewritten for v0.1.0 + docs/PRIVACY.md created.
- [ ] §14 privacy audit complete; zero leaks across Kotlin + bridge.
- [ ] §15 Claude Code + Codex compatibility recheck completed; result documented in PHASE_REVIEW.md.
- [ ] §16.1 build matrix passes (all 4 APKs).
- [ ] §16.2 lint clean with the 4 documented warnings only.
- [ ] §16.3 / §16.4 / §16.5 / §16.6 / §16.7 / §16.9 / §16.10 / §16.11 smoke pass (or partial-pass-with-documentation as per the per-scenario outcome trees).
- [ ] §16.8 reproducible build verified OR deferred-and-documented to v0.2.0.
- [ ] ARCHITECTURE.md updated with Phase 12 deliverables. PHASE_REVIEW.md's Phase 12 row marked done. PHASE_12_SUMMARY.md written.
- [ ] Git: branch `phase/12-polish-release` with the 10-commit sequence from §3.2; final tag `v0.1.0` annotated with the CHANGELOG entry; no force-pushes; commit messages reference §N of this prompt.

When DoD holds, **stop here. Do not begin Phase 12.5 or v0.2.0.** Write `PHASE_12_SUMMARY.md` with the same shape as prior summaries: deliverables, deviations, build / lint / dex / manifest verifications, privacy invariants, smoke result inventory, carried issues for v0.2.0, open questions for human reviewer.

---

## §18. Out of scope (carry to v0.2.0+)

Document these in CHANGELOG.md's `## [Unreleased]` section. Do NOT implement in Phase 12.

- **Model-override UI per provider.** `Provider.defaultModel` ships as the only available value for each provider in v0.1.0. Phase 12.5 or v0.2.0 will add a per-provider `modelOverride: String?` field in `SecureStorage` + a TextField below the API key field in `BackendsScreen` that defaults to `Provider.defaultModel`. `GrokRequestTest.modelDefaultsToGrok2LatestViaProviderEnum` (Phase 11) pins the current behavior; bumping that test is the one-line v0.2.0 change.
- **OpenAiCompatRemoteBackend refactor.** Consolidate the Grok branch (Phase 11) + a future DeepSeek/Mistral La Plateforme provider into a single `OpenAiCompatRemoteBackend(url, model, key)` class. Defer until a real third OpenAI-compat provider lands.
- **Multi-pack creation flow polish.** Phase 9b carry-over. Acceptable v0.1.0 UX as-is.
- **Pack tab background contrast.** Phase 9b carry-over. Cosmetic.
- **HeliBoard emoji-key gap.** Phase 9a carry-over. Upstream issue.
- **Robolectric SDK-36 failures.** 6 tests fail at compile time. Document; investigating is multi-day work.
- **Direct-boot SecureStorage retry-on-unlock.** Phase 3a carry-over. No real-world reports of the migration race.
- **`LocalLifecycleOwner` deprecation.** Compose API churn; defer to next Compose BOM bump.
- **`EncryptedSharedPreferences` deprecation.** Tink AEAD migration shim still exists; defer to v0.2.0 cleanup.
- **AGP 8.14 → 8.14.5, Compose BOM 2025.11.01 → 2026.05.00, colorpicker-compose 1.1.3 → 1.1.4 bumps.** Conservative; defer pending Phase 12 reproducibility verification (a dep bump could break byte-identity).
- **Codex CLI version bump past 0.42.0** unless §16.10 smoke proves a later version is Termux-compatible.
- **Multi-provider parallel install in setup.sh.** Sequential install is fine for v0.1.0.
- **Fastlane / GitHub Actions release pipeline.** v0.1.0 ships manually-tagged + manually-uploaded; CI automation is a v0.2.0 concern.

---

## §19. Failure-mode playbook

If §16 smoke fails for any scenario, the response shape is in the per-scenario outcome trees (§16.3 especially). Generally:

- **Build failure** in §16.1: usually a missing import in newly-added Kotlin (CommandRowController's banner code, HealthDiagnosticsScreen). Fix the import, re-run.
- **Lint regression** in §16.2 (more than the 4 documented warnings): inspect lint output, decide whether it's a Phase 12 deliverable bug (fix it) or pre-existing accepted (add to baseline with a `git diff` note).
- **Privacy leak** in §14 or §16.7: this is a release-blocker. Fix and re-verify.
- **Reproducibility diff** in §16.8: try `diffoscope`. If the diff is the signing block, it's expected (signing involves a timestamp); use `--in-source` mode to strip. If the diff is in DEX, look for `BuildConfig.BUILD_TIMESTAMP` references or similar timestamp embedding. If the diff cannot be eliminated by Phase 12 deadline, document and defer per §16.8.
- **BootReceiver still broken after §4** in §16.3: document the OS-level constraint in README + CHANGELOG. The "first-keyboard-open re-arm" pattern is acceptable for v0.1.0.
- **WhatsApp pack still rejected after §6** in §16.5: document the investigation outcome. v0.1.0 ships with the COMMIT_CONTENT sticker path working (Phase 9a); the WhatsApp pack-add path can be carry-over.
- **`keystore.properties` provided but signed APK fails `apksigner verify`** in §8: re-check the keystore properties file values; verify with `keytool -list -keystore <path>`.

When in doubt, document and defer rather than block the v0.1.0 release. The README's "Known issues" section is the appropriate place for documented-but-not-fixed items.

---

## §20. Handoff

When DoD holds:

1. Commit and push the `phase/12-polish-release` branch.
2. Tag locally: `git tag -a v0.1.0 -m "AI Keyboard v0.1.0 — first release"`. Do NOT push the tag yet — wait for the human reviewer to do the manual final sanity check and the GitHub Release upload.
3. Write `PHASE_12_SUMMARY.md` mirroring the prior phase summaries' shape.
4. Stop. Do not begin v0.2.0.

The human reviewer will:
- Read `PHASE_12_SUMMARY.md`.
- Validate any partial-pass outcomes in §16.
- Push the v0.1.0 tag.
- Create a GitHub Release with the signed APKs attached.
- Open a PR against F-Droid's `fdroiddata` repo with the metadata block for `com.aikeyboard.app`.
- Move the "Known issues" inventory into a GitHub Issues tracker for v0.2.0.

End of Phase 12 prompt.
