## Phase 2.5 Review (carried into Phase 3 prompt)

- **HeliBoard pref discovery:**
  - Pref key: `Settings.PREF_TOOLBAR_MODE = "toolbar_mode"` (in `com.aikeyboard.app.latin.settings.Settings`)
  - Enum: `com.aikeyboard.app.latin.utils.ToolbarMode { EXPANDABLE, TOOLBAR_KEYS, SUGGESTION_STRIP, HIDDEN }`
  - HeliBoard upstream default: `Defaults.PREF_TOOLBAR_MODE = "EXPANDABLE"` (consumed at read-time via `prefs.getString(KEY, default)` — there is no `setDefaultValues` call seeding the file, so absence of the key means `EXPANDABLE` implicitly)
  - Backing file: device-protected `<packageName>_preferences.xml`, accessed via `Context.prefs()` extension which delegates to `DeviceProtectedUtils.getSharedPreferences(this)`

- **Implementation path chosen:** Option A (app-side first-run hook). New `com.aikeyboard.app.ai.setup.FirstRunDefaults.apply(context)` writes `SUGGESTION_STRIP` and an `ai_keyboard_toolbar_default_applied` flag once into the same prefs file HeliBoard already uses; subsequent launches short-circuit on the flag. Called from `App.onCreate` immediately after `Defaults.initDynamicDefaults(this)`. No diff in HeliBoard's `Defaults.kt` / `Settings.java`.

- **Built:**
  - `FirstRunDefaults.kt` (new) — one-shot flip of `toolbar_mode` to `SUGGESTION_STRIP` on first install
  - `KeyboardChromeScreen.kt` (new) — Compose screen with a single `Switch` row "Show HeliBoard toolbar"; reads/writes `Settings.PREF_TOOLBAR_MODE` directly via `Context.prefs()`. EXPANDABLE = ON, SUGGESTION_STRIP = OFF.
  - `AiSettingsNavHost.kt` (modified) — new `keyboard/chrome` route + nav callback wired into `PersonaListScreen`
  - `PersonaListScreen.kt` (modified) — `TopAppBar` `actions` slot adds a settings-advanced icon button that navigates to the new chrome screen
  - `ai_strings.xml` — four new English strings (back, screen title, switch label, switch description)
  - `App.kt` — one import + one call to `FirstRunDefaults.apply(this)`. No other touch.

- **Smoke test (Pixel 6 Pro `1B101FDEE0006U`, both flavors, fresh uninstall+install):**
  - Pref file inspection right after `App.onCreate`: both flavors have `toolbar_mode=SUGGESTION_STRIP` and `ai_keyboard_toolbar_default_applied=true` written into device-protected `<packageName>_preferences.xml`
  - View-tree dump on the keyboard surface: command row (`ai_command_row` at y=[1769,1923]) + suggestion strip (`strip_container` at y=[1923,2063]) — no `suggestions_strip_toolbar_key`, no `toolbar_container` (i.e., toolbar hidden by default ✓)
  - Toggle ON via the gear → "Keyboard layout" top-app-bar action → `Switch`: pref flips to `EXPANDABLE`, view-tree gains `suggestions_strip_toolbar_key`; tapping that chevron reveals `toolbar_container` populated with HeliBoard's clipboard/voice/etc. keys
  - Toggle OFF: pref returns to `SUGGESTION_STRIP`, chevron disappears
  - `am force-stop` + relaunch: pref state preserved (HeliBoard already persists this, our toggle just writes via `apply()`)
  - Tap at the very top edge of the command row (y=1770, where Phase 2 fixed the inset bug) on the AI toggle button: keyboard NOT dismissed, toggle handler fires; `dumpsys input_method` confirms `touchableRegion=SkRegion((0,1624,1440,3075))` — inset region invariant still holds end-to-end
  - HeliBoard's launcher icon still opens its full `SettingsActivity` (escape hatch intact)
  - `adb logcat -d -t 5000 | grep -E "FATAL|AndroidRuntime|com.aikeyboard"`: zero FATAL / AndroidRuntime entries referencing our app
  - Both flavors: `assembleFdroidDebug assemblePlayDebug` clean; `lint{Fdroid,Play}Debug` "Lint found no new issues"; `git diff app/app/lint-baseline.xml` empty

- **Deviations from Phase 2.5 prompt:**
  - First-run flag is co-located in HeliBoard's existing prefs file (key `ai_keyboard_toolbar_default_applied`) rather than a separate `ai_keyboard_first_run.prefs`. Avoids creating a second SharedPreferences file for a single boolean; the key is namespaced and HeliBoard has no reason to read it. The prompt allowed either path.
  - Toggle UX: implemented as a sibling route `keyboard/chrome` rather than a section on `PersonaListScreen` — reached via a top-app-bar action button (gear-style icon) on the personas screen. Cleaner separation, extensible when Phase 3+ adds more "general" settings. The prompt invited either layout.
  - Used the existing `R.drawable.ic_settings_advanced` (HeliBoard upstream icon) for the top-app-bar action instead of vendoring a new vector. Same coupling-to-upstream-icons concern flagged in Phase 2 still applies; Phase 12 polish can vendor an own icon if upstream removes it.

- **Carried issues:**
  - **Existing-installer migration (acknowledged in prompt):** anyone who installed Phase 2 already has `EXPANDABLE` written to the prefs file; the flag-gated first-run hook does not retro-apply. Existing testers must flip the toggle once. Not blocking — every user gets the same end-state on next reinstall.
  - **Command row layout XML still has `?android:attr/textColorPrimary` / `textColorSecondary` on the persona TextViews** (`res/layout/command_row.xml:27,38`). The prompt's verification grep for these attrs was not empty — but `CommandRowView.applyKeyboardTheme()` overrides both with runtime `Colors.get(ColorType.KEY_TEXT)` immediately in `init {}`. The runtime invariant holds (verified visually in Phase 2), but the XML carries dead theme-attr fallbacks. Left untouched per the "Do not modify our command row code" constraint; trivial cleanup for any future phase that touches the layout.
  - All Phase 2 carry-overs unchanged: `EncryptedSharedPreferences` deprecation warnings, `LocalLifecycleOwner` deprecation, localized HeliBoard brand strings in `~10` languages, gesture lib not redistributable, Phase 1 lint baseline still 64 inherited entries.
  - HeliBoard's `EXPANDABLE` mode shows a chevron-collapsed toolbar (because `Defaults.PREF_TOOLBAR_HIDING_GLOBAL = true`); the toolbar contents only render after the user taps the chevron. Matches the prompt's contract ("HeliBoard's clipboard, voice input, and other shortcuts appear below the suggestion strip" — they appear once expanded). If Phase 12 wants a "show toolbar fully expanded by default" setting, that's a separate `PREF_TOOLBAR_HIDING_GLOBAL` toggle, out of scope here.

- **Touchpoints for Phase 3:** Phase 2's list applies unchanged plus —
  - `app/app/src/main/java/com/aikeyboard/app/ai/setup/FirstRunDefaults.kt` — central place for any future first-run pref defaults; extend rather than scattering hooks
  - `app/app/src/main/java/com/aikeyboard/app/ai/ui/AiSettingsNavHost.kt` — new `keyboard/chrome` route is the pattern for Phase 3's "Backends" / "API keys" route; add another `composable(...)` and another `actions` button on `PersonaListScreen` (or migrate to a hub-style start destination if more than ~3 sibling routes accumulate)
  - `app/app/src/main/java/com/aikeyboard/app/ai/ui/KeyboardChromeScreen.kt` — minimal Switch-row pattern to copy for any other simple keyboard pref surface (Phase 8's kill-switch indicator config, etc.)

- **Open questions for human reviewer:**
  1. Co-locating the first-run flag in HeliBoard's prefs file (vs a dedicated `ai_keyboard_first_run.prefs`) — prefer either? My read: same file is simpler and doesn't fragment our state across files.
  2. Sibling-route layout for the toggle vs same-screen section on PersonaListScreen — the route adds one nav level but feels cleaner once Phase 3 adds backend settings. Confirm or restructure?
  3. The `?android:attr/textColor*` dead attrs in `command_row.xml` — leave for Phase 3 to clean while it's in the area, or land a tiny follow-up commit on this branch?
