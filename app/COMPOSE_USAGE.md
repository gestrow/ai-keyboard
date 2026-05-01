# HeliBoard's Compose usage

**HeliBoard already uses Jetpack Compose extensively.** Phase 2 should align to HeliBoard's existing dependency versions rather than adding fresh ones.

## What's already wired up (post-rename, in `app/app/build.gradle.kts`)

- Plugin: `kotlin("plugin.compose") version "2.2.21"`
- BuildFeatures: `compose = true`
- Compose BOM: `androidx.compose:compose-bom:2025.11.01` *(intentionally pinned — newer BOMs pull in `material-android:1.10.0` which requires minSdk 23; we now require 29 so this constraint is moot, but the comment in the build file flags it for future bumps)*
- Direct deps already declared:
  - `androidx.compose.material3:material3` (versionless via BOM)
  - `androidx.compose.ui:ui-tooling-preview`
  - `androidx.compose.ui:ui-tooling` (debug)
  - `androidx.navigation:navigation-compose:2.9.6`
  - `sh.calvin.reorderable:reorderable:2.4.3` (drag-to-reorder lists)
  - `com.github.skydoves:colorpicker-compose:1.1.3` (user-defined colors)

## Where Compose is used today

`app/app/src/main/java/com/aikeyboard/app/settings/` (formerly under the upstream package's `settings/` subpackage) — this is HeliBoard's *new* (post-3.0) settings hierarchy, alongside the older `Preference`-based framework that lives in `latin/settings/`. The Compose-based settings include `screens/`, `dialogs/`, and `preferences/` subpackages.

Do not confuse the two — per ARCHITECTURE.md, **HeliBoard's Compose-based `SettingsActivity` is preserved untouched in Phase 1**. Phase 2 will add a *separate* `AiSettingsActivity` for AI-feature surface area, reachable from a dedicated gear icon in the command row. That avoids invasive surgery on HeliBoard's preference graph and keeps the upstream merge surface small.

## Phase 2 implication

Phase 2 only needs to add new Compose source files; **no new Compose dependencies are required**. Match HeliBoard's existing Compose BOM and Material3 imports. A separate Compose Activity entry point is allowed (just register it in the manifest); HeliBoard's `SettingsActivity` remains the activity bound to `android:settingsActivity` in `res/xml/method.xml` for keyboard preferences.
