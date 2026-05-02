# Phase 2.5 Prompt — Chrome Polish (Toolbar Default + Show Toggle)

> **How to use this prompt:** open a fresh Claude Code session in `/Users/kacper/SDK/apk-dev/ai-keyboard/`. Tell it: *"Read `ARCHITECTURE.md`, `PHASE_REVIEW.md`, `PHASE_1_SUMMARY.md`, and `PHASE_2_SUMMARY.md`, then execute the prompt in `PHASE_2.5_PROMPT.md` exactly. Stop when Phase 2.5's Definition of Done holds. Do not begin Phase 3."* Do not paste this prompt into a long-history conversation; start fresh.

---

You are working in `/Users/kacper/SDK/apk-dev/ai-keyboard/`. Phases 1 and 2 are complete. Read all four planning docs before doing anything else.

This is a deliberately narrow phase. Its only job is making the keyboard feel less visually crowded by **defaulting HeliBoard's existing toolbar to hidden** and **adding a show-toggle in `AiSettingsActivity`** so users who want it can re-enable it. It exists because device testing in Phase 2 showed the chrome stack (our command row + HeliBoard's suggestion strip + HeliBoard's toolbar) is too tall by default.

Do **not** modify our command row, persona model, or `SecureStorage` in this phase. Do **not** start any AI / networking / a11y / Termux work. Do **not** alter HeliBoard's source-code behavior beyond setting a default and exposing one new toggle.

## Critical context from Phase 2

- HeliBoard exposes a `ToolbarMode` enum with four values: `EXPANDABLE` (current default; suggestions + collapsible toolbar), `TOOLBAR_KEYS` (toolbar only), `SUGGESTION_STRIP` (suggestions only — **our target default**), `HIDDEN` (everything off).
- HeliBoard reads this preference from `SharedPreferences` under a key like `pref_toolbar_mode` (verify the exact key by searching HeliBoard's source — likely defined in `Settings.kt` or `Defaults.kt`).
- HeliBoard's full settings are reachable via the launcher icon (which opens its `SettingsActivity`). This is our **escape hatch**: power users can still access every HeliBoard preference there. Our toggle in `AiSettingsActivity` is a convenience surface, not a replacement.
- Our `AiSettingsActivity` lives at `com.aikeyboard.app.ai.ui.AiSettingsActivity` and uses Compose + `platformActivityTheme`. Existing `AiSettingsNavHost` has at least a persona-list and persona-edit route from Phase 2.

## Tasks

### 1. Branch from Phase 2

```
git checkout phase/02-command-row-personas
git pull --ff-only
git checkout -b phase/02.5-chrome-polish
```

### 2. Find the exact pref key and default mechanism HeliBoard uses

Before writing any code, locate:
- The Kotlin enum or constant that names `SUGGESTION_STRIP` (likely `ToolbarMode.SUGGESTION_STRIP`)
- The SharedPreferences key string for toolbar mode (likely `Settings.PREF_TOOLBAR_MODE` or similar)
- The mechanism HeliBoard uses to seed defaults on first launch (likely a `Defaults` object or `PreferenceManager.setDefaultValues`)

Document what you find at the top of `PHASE_2.5_SUMMARY.md` so the next phase doesn't re-investigate.

### 3. Default `ToolbarMode = SUGGESTION_STRIP` on first launch

Two implementation paths — pick the one that minimizes the diff into HeliBoard's territory:

**Option A (preferred): app-side first-run hook.** In our `App` class (or `LatinIME.onCreate` if there's no Application subclass) — actually there *is* a `com.aikeyboard.app.latin.App` class per `AndroidManifest.xml` — add a one-time check on first launch (use a boolean flag in our `SecureStorage` or a regular `SharedPreferences` named `ai_keyboard_first_run.prefs`) that:

```kotlin
val prefs = PreferenceManager.getDefaultSharedPreferences(context)
if (!firstRunFlagPrefs.contains("toolbar_default_applied")) {
    prefs.edit { putString(Settings.PREF_TOOLBAR_MODE, ToolbarMode.SUGGESTION_STRIP.name) }
    firstRunFlagPrefs.edit { putBoolean("toolbar_default_applied", true) }
}
```

This sets HeliBoard's pref once on first launch and respects subsequent user changes. **Do not** modify HeliBoard's `Defaults.kt` directly — that's deeper into upstream territory than necessary.

**Option B (only if A doesn't work cleanly): patch HeliBoard's `Defaults`** with a single-line change to flip the default value. Keep the diff minimal and document in `PHASE_2.5_SUMMARY.md` with a one-line note in `app/UPSTREAM.md` explaining the deviation.

If you discover HeliBoard already has a hook for "app-supplied default overrides" (some keyboards do), use it.

**Migration consideration:** users who already installed Phase 2 will have HeliBoard's default `EXPANDABLE` mode in their prefs. Our first-run flag only fires for fresh installs. This is acceptable — existing testers get to opt in via the toggle. Document this behavior in the summary.

### 4. Add show-toggle to `AiSettingsActivity`

Add a new section / row to `AiSettingsNavHost`'s persona-list screen (or as a sibling route — your judgment based on what feels natural in the existing UI):

- **Label:** "Show HeliBoard toolbar"
- **Description (small text below):** "When on, HeliBoard's clipboard, voice input, and other shortcuts appear below the suggestion strip. When off, only word suggestions are shown."
- **Control:** Compose `Switch` (Material3)
- **State binding:**
  - On: writes `EXPANDABLE` to `Settings.PREF_TOOLBAR_MODE`
  - Off: writes `SUGGESTION_STRIP`
  - Initial state read from the same pref
- **Persistence:** writes via `SharedPreferences.edit().apply()` — HeliBoard already persists this pref across keyboard restarts; no new persistence layer needed
- **Toggle survives app process kill** — the pref is in `SharedPreferences`, not in-memory state

The toggle should immediately affect the keyboard surface. HeliBoard's `Settings.kt` listens for pref changes and re-renders; your toggle write triggers that flow naturally. If for some reason it doesn't (HeliBoard caches the value somewhere), find the cache invalidation point and call it.

Add the strings to `app/app/src/main/res/values/ai_strings.xml` (the file Phase 2 created for our additions).

### 5. Verify the Phase 2.5-relevant keyboard-surface invariants are codified

`PHASE_REVIEW.md` was amended (by the planner) to include a "Keyboard-surface UI invariants" section listing the runtime `Colors` requirement and the inset-region extension requirement. Read that section. **Do not** edit `PHASE_REVIEW.md` yourself.

Before declaring Phase 2.5 done, verify these invariants in our existing code (Phase 2 should have got them right, but cross-check):
- Grep for `?android:attr/textColor` and `?attr/colorPrimary` in any source file under `app/app/src/main/res/layout/command_row.xml` or `app/app/src/main/java/com/aikeyboard/app/ai/commandrow/` — should be empty (Phase 2 already migrated these to runtime `Colors`)
- Confirm the `onComputeInsets` patch from Phase 2 is still in place by tapping at the very top of the command row in the smoke test — keyboard should NOT dismiss

### 6. Smoke test (real device, both flavors)

```
cd app
./gradlew assembleFdroidDebug assemblePlayDebug
```

For a clean test, **uninstall first** (the migration semantics matter):

```
adb -s 1B101FDEE0006U uninstall com.aikeyboard.app.debug
adb -s 1B101FDEE0006U uninstall com.aikeyboard.app.play.debug
adb -s 1B101FDEE0006U install app/build/outputs/apk/fdroid/debug/ai-keyboard-3.9-fdroid-debug.apk
adb -s 1B101FDEE0006U install app/build/outputs/apk/play/debug/ai-keyboard-3.9-play-debug.apk
```

On the device:

1. Switch to AI Keyboard (fdroid flavor first), open any text field
2. **Verify chrome state on first launch:** our command row visible at top, suggestions strip below, **HeliBoard's toolbar (gear/mic/clipboard) NOT visible**
3. Open `AiSettingsActivity` via the gear, find the "Show HeliBoard toolbar" toggle, toggle ON
4. Return to the keyboard — HeliBoard's toolbar is now visible below the suggestion strip
5. Toggle OFF — HeliBoard's toolbar disappears again
6. Force-stop the app, reopen the keyboard — toggle state preserved
7. Confirm via `adb`:
   ```
   adb -s 1B101FDEE0006U shell run-as com.aikeyboard.app.debug cat shared_prefs/com.aikeyboard.app.debug_preferences.xml | grep toolbar_mode
   ```
   Should show the current value matching the toggle state.
8. Tap the very top of our command row (the persona selector area) — keyboard does NOT dismiss (touchable region invariant holds)
9. Repeat 1–8 with the play flavor
10. `adb logcat -d | grep -E "FATAL|AndroidRuntime|com.aikeyboard"` — clean of crashes

### 7. Commit

Single commit on `phase/02.5-chrome-polish`:

```
Phase 2.5: chrome polish — default ToolbarMode=SUGGESTION_STRIP + show toggle

- HeliBoard's clipboard/voice/etc. toolbar now hidden by default on first launch
- "Show HeliBoard toolbar" toggle in AiSettingsActivity restores HeliBoard's
  EXPANDABLE mode; persistent across keyboard restarts
- HeliBoard's full settings remain reachable via launcher icon (escape hatch)
- No changes to command row, personas, or SecureStorage
- Both flavors build clean; smoke-tested on Pixel 6 Pro
```

Do not push, do not merge.

## Constraints

- **Do not** modify our command row code (`com.aikeyboard.app.ai.commandrow.*`).
- **Do not** modify `Persona`, `SecureStorage`, or any storage code.
- **Do not** modify `LatinIME.java`, `onComputeInsets`, or any keyboard-rendering code in HeliBoard's `latin/` or `keyboard/` packages.
- **Do not** add any AI / networking / Termux / a11y code.
- **Do not** add new dependencies.
- **Do not** edit `ARCHITECTURE.md` or `PHASE_REVIEW.md`. If you find an error, surface it in the phase summary.
- **Do not** edit `app/COMPOSE_USAGE.md` or `app/UPSTREAM.md` unless Option B (Defaults patch) was used — in which case add one line documenting the upstream deviation.
- **Do** keep all changes scoped to either our `app/app/src/main/java/com/aikeyboard/app/ai/...` packages OR `app/app/src/main/java/com/aikeyboard/app/latin/App.kt` (or wherever the first-run hook lives — minimal touch).

## Phase 2.5 summary template

When finished, write `PHASE_2.5_SUMMARY.md` at repo root, under 50 lines:

```markdown
## Phase 2.5 Review (carried into Phase 3 prompt)

- **HeliBoard pref discovery:** <pref key, enum class, defaults mechanism>
- **Implementation path chosen:** <Option A first-run hook, OR Option B Defaults patch with rationale>
- **Built:** <terse outcome>
- **Smoke test:** <results>
- **Deviations from Phase 2.5 prompt:** <list, or "none">
- **Carried issues:** <list, or "none">
- **Touchpoints for Phase 3:**
  - same as Phase 2's Touchpoints, plus any new files this phase introduced
- **Open questions for human reviewer:** <items>
```

## Definition of done

- `./gradlew assembleFdroidDebug assemblePlayDebug` both succeed
- `./gradlew lint` passes; `git diff app/app/lint-baseline.xml` is empty
- Fresh install of fdroid debug shows our command row + suggestions, HeliBoard's toolbar hidden by default
- Show-toggle in `AiSettingsActivity` flips HeliBoard's toolbar visibility; state persists across app restarts
- HeliBoard's launcher-icon settings still open and function (escape hatch intact)
- Tapping anywhere on our command row does not dismiss the keyboard (inset region invariant verified)
- Smoke test on Pixel 6 Pro `1B101FDEE0006U` for both flavors logs clean (no `FATAL` / `AndroidRuntime`)
- `PHASE_2.5_SUMMARY.md` exists at repo root, under 50 lines
- Single commit on `phase/02.5-chrome-polish`, not pushed, not merged

Then stop. Do not begin Phase 3.
