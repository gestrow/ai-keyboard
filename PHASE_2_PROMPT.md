# Phase 2 Prompt — Command Row + Persona Model + AiSettingsActivity Skeleton

> **How to use this prompt:** open a fresh Claude Code session in `/Users/kacper/SDK/apk-dev/ai-keyboard/`. Tell it: *"Read `ARCHITECTURE.md`, `PHASE_REVIEW.md`, and `PHASE_1_SUMMARY.md`, then execute the prompt in `PHASE_2_PROMPT.md` exactly. Stop when Phase 2's Definition of Done holds. Do not begin Phase 3."* Do not paste this prompt into a long-history conversation; start fresh.

---

You are working in `/Users/kacper/SDK/apk-dev/ai-keyboard/`. Phase 1 is complete and committed on branch `phase/01-scaffold`. Read all three planning docs before doing anything else; they are authoritative for every decision.

Your job in this phase is **only** to add: a command row above the keyboard, a persona data model, an encrypted-prefs storage layer scoped to personas, and a Compose `AiSettingsActivity` reachable from the command row's gear icon. **No AI calls, no networking, no AccessibilityService work, no Termux integration, no real LLM.** All UI elements that will eventually trigger AI flows are **no-op stubs** in this phase.

## Critical context from Phase 1 (do not relearn)

- **Namespace:** HeliBoard's code lives under `com.aikeyboard.app.latin.*`. Our new code lives in **sibling packages** under `com.aikeyboard.app.ai.*`. Do not put new code under `.latin.*`.
- **`BuildConfig` and `R`** must be imported from `com.aikeyboard.app.latin` (not the bare `com.aikeyboard.app`) because `namespace = "com.aikeyboard.app.latin"` in `app/app/build.gradle.kts`. Example pattern (see `Share.kt` line 29 for an existing import that works).
- **Compose, Material3, navigation-compose, lifecycle-viewmodel-compose** are already declared in `app/app/build.gradle.kts` (HeliBoard already uses Compose for its existing settings UI). **Do not add new Compose dependencies.** Document anything you nevertheless add and why.
- **HeliBoard's existing `SettingsActivity`** lives at `app/app/src/main/java/com/aikeyboard/app/settings/`. **Do not modify any file under that directory.** Phase 2's `AiSettingsActivity` is a parallel Activity, not an extension.
- **HeliBoard's IME view hierarchy** (already located for you):
  - Entry: `app/app/src/main/res/layout/input_view.xml` → wraps a `com.aikeyboard.app.latin.InputView` containing `main_keyboard_frame`
  - `app/app/src/main/res/layout/main_keyboard_frame.xml` → vertical `LinearLayout` with `<include id="strip_container">` (suggestions) above `KeyboardWrapperView` (keys)
  - **Insertion point for the command row:** as a new `<include>` element **above** `@id/strip_container` inside `main_keyboard_frame.xml`. This is purely additive; do not edit existing children.

## Tasks

### 1. Branch from Phase 1

```
git checkout phase/01-scaffold
git pull --ff-only          # in case Phase 1 was amended
git checkout -b phase/02-command-row-personas
```

All Phase 2 work goes on this branch. Single commit at the end.

### 2. Persona model and storage

Create directory `app/app/src/main/java/com/aikeyboard/app/ai/persona/`.

**`Persona.kt`** — pure data class, serializable:
```kotlin
data class Persona(
    val id: String,           // UUID string
    val name: String,         // user-visible label, e.g. "Concise Editor"
    val systemPrompt: String, // the prompt fed to the LLM
    val fewShots: List<FewShot> = emptyList(),
    val isBuiltIn: Boolean = false  // built-ins cannot be deleted, only edited or duplicated
)

data class FewShot(val userInput: String, val assistantResponse: String)
```

Use `kotlinx.serialization` for JSON serialization (HeliBoard already declares it; verify in `app/app/build.gradle.kts` and add `@Serializable` annotations on both data classes). If `kotlinx.serialization` is **not** already present, use `org.json.JSONObject` from the platform — do not add a new dependency.

**`DefaultPersonas.kt`** — the four built-ins seeded on first run:

| id (UUID, hardcoded) | name | systemPrompt |
|---|---|---|
| `00000000-0000-0000-0000-000000000001` | Default | empty string |
| `00000000-0000-0000-0000-000000000002` | Concise Editor | "Rewrite the user's text to be more concise while preserving meaning. Output only the rewritten text. No preamble, no explanation." |
| `00000000-0000-0000-0000-000000000003` | Esquire | "Rewrite the user's text in formal legal/professional tone, suitable for a written communication. Output only the rewritten text. No preamble." |
| `00000000-0000-0000-0000-000000000004` | Flirty | "Rewrite the user's text with a playful, lightly flirtatious tone. Stay tasteful. Output only the rewritten text. No preamble." |

All four have `isBuiltIn = true`.

### 3. SecureStorage

Create `app/app/src/main/java/com/aikeyboard/app/ai/storage/SecureStorage.kt`.

- Wraps `androidx.security.crypto.EncryptedSharedPreferences` with `MasterKey` from Android Keystore (AES-256-GCM scheme)
- Add the dependency `androidx.security:security-crypto:1.1.0-alpha06` to `app/app/build.gradle.kts` (the latest stable; verify via context7 if a newer one exists). This **is** a new dep — declare it.
- Prefs file name: `ai_keyboard_secure.prefs`
- API for Phase 2 (persona-only; API keys arrive in Phase 3, scaffold the class to accept them later):

```kotlin
class SecureStorage(context: Context) {
    fun getPersonas(): List<Persona>
    fun savePersona(persona: Persona)          // upsert by id
    fun deletePersona(id: String)              // throws if persona.isBuiltIn
    fun getActivePersonaId(): String           // defaults to "Default" persona on first run
    fun setActivePersonaId(id: String)
}
```

- On first read of `getPersonas()`, if the file is empty, seed with the four `DefaultPersonas` entries and persist.
- Store the persona list as a single JSON string under key `personas_json`. Active persona id under key `active_persona_id`.
- Do not log persona content or IDs at any level.
- Singleton accessor: `SecureStorage.getInstance(context)` using `applicationContext` to avoid leaks.

### 4. Command row View (Views, not Compose)

Per locked decision #1, the keyboard surface uses Views. Compose-in-IME is avoided.

Create:
- `app/app/src/main/res/layout/command_row.xml` — a horizontal `LinearLayout`, ~44dp tall, background `?attr/colorBackground` (matches HeliBoard's theme attrs). Children left-to-right:
  1. **Persona label** — `TextView` showing the active persona name with a small chevron icon (`▾`); tap-target wraps the whole element. ID: `@+id/cmd_persona_selector`.
  2. **AI mode toggle** — `ImageView` of an icon (use `R.drawable.ic_ai_toggle` — create a simple 24dp vector drawable in `res/drawable/`, suggestion: a chat-bubble outline). ID: `@+id/cmd_ai_toggle`.
  3. **"Read & Respond"** — `ImageButton` with vector drawable `ic_read_respond` (create as a simple 24dp vector). ID: `@+id/cmd_read_respond`.
  4. **Sticker tab** — `ImageButton` with `ic_sticker` (24dp vector). ID: `@+id/cmd_sticker`.
  5. **Settings gear** — `ImageButton` with `ic_settings` (use HeliBoard's existing settings icon if one exists; otherwise add a 24dp vector). ID: `@+id/cmd_settings`.

All buttons should have `android:contentDescription` strings (declare in `donottranslate.xml` or a new `ai_strings.xml` to keep additions clearly separable from upstream).

- `app/app/src/main/java/com/aikeyboard/app/ai/commandrow/CommandRowView.kt` — extends `LinearLayout`, inflates `command_row.xml`, exposes:

```kotlin
class CommandRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    interface Listener {
        fun onPersonaSelectorTap()       // shows persona dropdown
        fun onAiToggleTap()              // toggles AI mode (in-memory flag, no persistence yet)
        fun onReadRespondTap()           // no-op: log "Read & Respond tapped (Phase 7)"
        fun onStickerTap()               // no-op: log "Sticker tab tapped (Phase 9)"
        fun onSettingsLongPress()        // launches AiSettingsActivity
        fun onSettingsTap()              // short-tap also launches AiSettingsActivity for now (long-press requirement is still met; we just allow both)
    }

    var listener: Listener? = null
    fun bindActivePersona(persona: Persona)   // updates the label text
}
```

Notes:
- Persona dropdown UI: when `onPersonaSelectorTap()` fires, show an `androidx.appcompat.widget.PopupMenu` (or `ListPopupWindow`) anchored to the persona label, populated from `SecureStorage.getPersonas()`. Selecting an entry calls `setActivePersonaId(...)` and rebinds.
- AI mode toggle: in-memory `Boolean` on the IME service or a singleton. Visually flip the icon (use a `StateListDrawable` or call `setImageResource`). No persistence yet.

### 5. Wire the command row into the keyboard surface

Edit `app/app/src/main/res/layout/main_keyboard_frame.xml` — add **above** the existing `<include android:id="@+id/strip_container">`:

```xml
<com.aikeyboard.app.ai.commandrow.CommandRowView
    android:id="@+id/ai_command_row"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_gravity="top" />
```

This is the **only** edit to this file. Do not modify any existing element.

In `app/app/src/main/java/com/aikeyboard/app/latin/LatinIME.java` (HeliBoard territory — minimal touch):
- Find the existing override of `setInputView(View view)` (it already calls `super.setInputView(view)` and `updateSuggestionStripView(view)`)
- Add **one** line: locate `R.id.ai_command_row`, instantiate a `CommandRowController` (Kotlin class you'll create — see next bullet), and pass it the view + a `SecureStorage` reference

Keep the diff in `LatinIME.java` minimal — just enough to wire the new view to a controller. No business logic in `LatinIME.java`.

Create `app/app/src/main/java/com/aikeyboard/app/ai/commandrow/CommandRowController.kt` — owns the wiring:
- Holds the `CommandRowView` reference
- Implements `CommandRowView.Listener`
- Persona dropdown logic (`PopupMenu` populated from `SecureStorage`)
- AI mode toggle in-memory state
- Launches `AiSettingsActivity` via `Intent` with `FLAG_ACTIVITY_NEW_TASK` (required from a service context)
- Logs "tapped (Phase N)" stubs for `Read & Respond` and `Sticker tab`
- Uses `Logger.d(TAG, "...")` style — never log persona content

### 6. AiSettingsActivity (Compose)

Create `app/app/src/main/java/com/aikeyboard/app/ai/ui/AiSettingsActivity.kt`:

- Extends `androidx.activity.ComponentActivity` (not `AppCompatActivity`)
- `setContent { AiSettingsTheme { AiSettingsNavHost() } }`
- Theme: simple Material3 with `dynamicLightColorScheme` / `dynamicDarkColorScheme` on Android 12+, fallback to default Material3 colors. Do not try to inherit HeliBoard's theme system in this phase.
- Navigation graph (use `androidx-navigation-compose` already in deps):
  - `personas/list` (start destination): scrollable list of personas with edit/delete swipe actions; FAB to create new
  - `personas/edit/{id?}`: form for name, systemPrompt (multiline), and a simple list-of-pairs UI for fewShots (add/remove rows)
- Built-in personas: cannot be deleted (delete swipe disabled or shows a tooltip). They CAN be edited and duplicated.
- "Save" persists via `SecureStorage`. "Cancel" pops without saving.
- No network calls, no test buttons, no LLM previews.

Activity declaration in `app/app/src/main/AndroidManifest.xml` — add inside `<application>`:

```xml
<activity
    android:name="com.aikeyboard.app.ai.ui.AiSettingsActivity"
    android:label="@string/ai_settings_title"
    android:exported="false"
    android:theme="@style/Theme.AppCompat.DayNight.NoActionBar" />
```

Add the title string `ai_settings_title` ("AI Keyboard Settings") to the new `app/app/src/main/res/values/ai_strings.xml` — keeping all our additions in one new resource file makes them easy to spot vs upstream.

### 7. Smoke test (manual on device)

Build and install both flavors:
```
cd app
./gradlew assembleFdroidDebug assemblePlayDebug
adb -s 1B101FDEE0006U install -r app/build/outputs/apk/fdroid/debug/ai-keyboard-3.9-fdroid-debug.apk
adb -s 1B101FDEE0006U install -r app/build/outputs/apk/play/debug/ai-keyboard-3.9-play-debug.apk
```

On the device:

1. Switch to AI Keyboard (fdroid flavor first), open any text field
2. **Verify command row visible** above the suggestions strip; all five buttons render
3. Tap persona selector → dropdown shows Default, Concise Editor, Esquire, Flirty. Tap one — label updates.
4. Tap AI toggle — icon flips visibly (in-memory state)
5. Tap Read & Respond — `adb logcat -d | grep "Phase 7"` shows the stub log
6. Tap Sticker — `adb logcat -d | grep "Phase 9"` shows the stub log
7. Tap or long-press Settings gear → AiSettingsActivity opens
8. In the activity: list shows four personas. Try editing Concise Editor's prompt, save, return, swipe to delete a built-in (should be blocked). Create a new persona named "Test", save. Kill the app, reopen the keyboard, verify "Test" persists in the dropdown.
9. Inspect `/data/data/com.aikeyboard.app.debug/shared_prefs/ai_keyboard_secure.prefs.xml` via `adb shell run-as com.aikeyboard.app.debug cat ...` — file content should be **encrypted binary**, not readable plaintext.
10. Repeat steps 1–8 with the play flavor (uninstall and reinstall isn't required because the applicationIds differ).
11. Logcat during the entire session: no `FATAL EXCEPTION`, no `AndroidRuntime` crashes referencing `com.aikeyboard.app`.

### 8. Update the universal review checklist if anything new emerged

If you discover something during Phase 2 that the universal checklist in `PHASE_REVIEW.md` should catch in future phases, surface it in the phase summary — **do not** edit the planning docs yourself.

### 9. Commit

Single commit on `phase/02-command-row-personas`:

```
Phase 2: command row + persona model + AiSettingsActivity skeleton

- New code lives under com.aikeyboard.app.ai.* (sibling to .latin)
- Persona data model + four built-in defaults
- SecureStorage wraps EncryptedSharedPreferences for persona persistence
- CommandRowView added to main_keyboard_frame.xml above strip_container
- AiSettingsActivity (Compose) reachable from command row gear
- HeliBoard's existing settings/Preference framework untouched
- Both flavors build clean; smoke-tested on Pixel 6 Pro
```

Do not push, do not merge to main.

## Constraints

- **Do not** make any network calls, even hypothetical / commented-out ones.
- **Do not** modify any file under `app/app/src/main/java/com/aikeyboard/app/settings/` (HeliBoard's settings).
- **Do not** modify HeliBoard's keyboard rendering, key layouts, or input logic.
- **Do not** add Compose dependencies (HeliBoard already has them) or any other dependencies beyond `androidx.security:security-crypto`.
- **Do not** start an `AccessibilityService`, declare one, or stub one.
- **Do not** wire up any real AI call paths even as TODOs that look like they might leak content.
- **Do not** persist the AI mode toggle — it's in-memory only this phase.
- **Do not** add ProGuard / R8 rules unless something fails to build without them, and document if so.
- **Do** keep all log messages anonymous — never log persona names, prompt content, or any user-typed text.
- **Do** put every new resource string in `app/app/src/main/res/values/ai_strings.xml` so our additions are easy to scan vs upstream.

## Phase 2 summary template

When finished, write `PHASE_2_SUMMARY.md` at repo root, under 50 lines:

```markdown
## Phase 2 Review (carried into Phase 3 prompt)

- **Built:** <terse outcome>
- **Smoke test:** <on-device results, including any HeliBoard features tested for regression>
- **Deviations from architecture / Phase 2 prompt:** <list, or "none">
- **Carried issues:** <list, or "none">
- **Touchpoints for Phase 3:**
  - `app/app/src/main/java/com/aikeyboard/app/ai/storage/SecureStorage.kt` — extend with API key CRUD methods
  - `app/app/src/main/java/com/aikeyboard/app/ai/commandrow/CommandRowController.kt` — Read/Respond and AI toggle hook into AiClient here
  - `app/app/src/main/java/com/aikeyboard/app/ai/persona/Persona.kt` — already has fewShots; Phase 3 builds prompt-construction logic that uses them
- **Open questions for human reviewer:** <items where you guessed and they should sanity-check>
```

## Definition of done

All of the following must hold:

- `./gradlew assembleFdroidDebug assemblePlayDebug` both succeed
- `./gradlew lint` passes; `git diff lint-baseline.xml` is empty (no new entries absorbed)
- New code lives under `com.aikeyboard.app.ai.*`; nothing under `com.aikeyboard.app.settings.*` was modified
- Both flavors install on Pixel 6 Pro (`1B101FDEE0006U`); command row renders; gear opens settings; persona dropdown populates from storage; created personas persist across app restart
- Logcat during a 2-minute session in each flavor is clean (no `FATAL` / `AndroidRuntime` referencing `com.aikeyboard.app`)
- Encrypted prefs file is binary on disk (not plaintext)
- `PHASE_2_SUMMARY.md` exists at repo root, under 50 lines, follows template
- `phase/02-command-row-personas` branch contains a single clean commit, not pushed, not merged

Then stop. Do not begin Phase 3.
