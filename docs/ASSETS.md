# Pre-release image-swap checklist

Every PNG/XML listed below ships in v0.1.0 as either a placeholder (the F-Droid metadata directory) or an inherited HeliBoard asset (the in-app launcher icon). Replace before `git tag v0.1.0`.

The columns are exact: filename, path, format, **pixel** dimensions, and what consumes the asset.

---

## 1. F-Droid metadata (placeholders — empty in repo)

These are required by F-Droid's index renderer. Without them the app page on `f-droid.org` falls back to a generic icon and an empty screenshot carousel. F-Droid's `fdroidserver` does not fail the build if any are missing; the index page just renders empty slots.

| Path | Pixel dims | Format | Purpose |
|---|---|---|---|
| `fastlane/metadata/android/en-US/images/icon.png` | **512×512** | PNG, RGBA or RGB | App icon shown next to the app name in F-Droid's index. Square. Transparent background is fine; F-Droid renders against a neutral gray. |
| `fastlane/metadata/android/en-US/images/featureGraphic.png` | **1024×500** | PNG, RGB | Banner shown at the top of the app's F-Droid page. Don't put critical content in the outer 10% — F-Droid crops slightly on some viewports. |
| `fastlane/metadata/android/en-US/images/phoneScreenshots/1.png` | **≥ 1080×1920**, portrait | PNG | First screenshot in the carousel. Filename literally `1.png`, not `screenshot1.png` or `01.png`. |
| `fastlane/metadata/android/en-US/images/phoneScreenshots/2.png` | same | PNG | Second screenshot. |
| `fastlane/metadata/android/en-US/images/phoneScreenshots/3.png` | same | PNG | Third (minimum). Up to 8 total. |
| `fastlane/metadata/android/en-US/images/phoneScreenshots/4.png` … `8.png` | same | PNG | Optional, max 8. |

**Screenshot suggestions** (what to capture, in order):

1. Command row with persona selector visible — shows the AI/persona controls.
2. AI rewrite mid-stream — preview strip above the keyboard with tokens streaming in.
3. Sticker picker open over Telegram/Signal — shows the sticker engine.
4. Backends screen with one provider configured — shows BYOK setup.
5. Always-On Read & Respond Quick Settings tile + persistent chip in the shade.
6. Health diagnostics screen with the 5-row check report.

Capture with `adb exec-out screencap -p > N.png` (clean PNG, no compression). Resize/crop to ≥ 1080×1920 portrait. Strip status-bar clutter via `adb shell settings put global window_animation_scale 0` first if needed.

The `phoneScreenshots/` directory currently holds only a `.gitkeep` placeholder.

---

## 2. App launcher icon (inherited from HeliBoard — replace if rebranding to SignalWraith)

Two parallel asset systems ship for backward compatibility: **adaptive icons** (Android 8+, API 26+) and **classic PNG icons** (legacy fallback). Replace both for full coverage.

### 2a. Adaptive icon (API 26+)

| Path | Format | Notes |
|---|---|---|
| `app/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | XML (adaptive-icon) | References `@drawable/ic_launcher_background` + `@drawable/ic_launcher_foreground`. Also references foreground as the `monochrome` layer (Android 13+ themed-icon support). Don't rename the drawables; this XML hard-codes their names. |
| `app/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml` | XML | Identical contents; Android picks one based on launcher's circular-mask preference. |
| `app/app/src/main/res/drawable/ic_launcher_foreground.xml` | Vector drawable | 108dp × 108dp viewport. Center 72dp × 72dp is the **safe zone** — anything outside it gets cropped on circular masks. Anything in the outer 18dp ring renders only on rectangular masks. |
| `app/app/src/main/res/drawable-v24/ic_launcher_background.xml` | Vector drawable | Same 108dp viewport. Solid color or simple gradient recommended; avoid detail (gets cropped on most launchers). |

**Easiest production path:** Android Studio → right-click `app/app/src/main/res/` → New → Image Asset → "Launcher Icons (Adaptive and Legacy)". Drop a 1024×1024 source PNG/SVG; Android Studio generates all four XML/drawable files plus the five PNG densities below in one step.

### 2b. Classic PNG launcher icons (legacy fallback)

Ten PNGs at five density buckets (square + round at each). API 25 and below use these directly; API 26+ falls back to these if the launcher doesn't support adaptive icons.

| Density bucket | Path | Pixel dims |
|---|---|---|
| `mdpi` | `app/app/src/main/res/mipmap-mdpi/ic_launcher.png` | **48×48** |
| `mdpi` round | `app/app/src/main/res/mipmap-mdpi/ic_launcher_round.png` | **48×48** |
| `hdpi` | `app/app/src/main/res/mipmap-hdpi/ic_launcher.png` | **72×72** |
| `hdpi` round | `app/app/src/main/res/mipmap-hdpi/ic_launcher_round.png` | **72×72** |
| `xhdpi` | `app/app/src/main/res/mipmap-xhdpi/ic_launcher.png` | **96×96** |
| `xhdpi` round | `app/app/src/main/res/mipmap-xhdpi/ic_launcher_round.png` | **96×96** |
| `xxhdpi` | `app/app/src/main/res/mipmap-xxhdpi/ic_launcher.png` | **144×144** |
| `xxhdpi` round | `app/app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png` | **144×144** |
| `xxxhdpi` | `app/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png` | **192×192** |
| `xxxhdpi` round | `app/app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png` | **192×192** |

Filename suffixes (`_round`) are required — don't rename. The manifest references `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round`; Android resolves to the matching density bucket at runtime.

**Source recommendation:** start from a single 1024×1024 PNG (or SVG with a 1024×1024 export). Scale down to each density. Maintain transparency / RGBA — Android masks the icon shape at runtime on adaptive-icon-aware launchers.

---

## 3. Optional / not required for v0.1.0

These exist in the source tree but don't need swapping for first release:

- **In-keyboard themes (HeliBoard inherited):** `app/app/src/main/res/drawable*/key_*.xml`, theme-specific drawables. Replace only if rebranding the keyboard surface itself. The default theme renders the existing HeliBoard look; the AI command row is the only AI-Keyboard-specific surface and uses Compose, not drawables.
- **Sticker engine sample assets:** none ship in the APK. Users add their own.
- **Notification icons:** `app/app/src/main/res/drawable/ic_read_respond.xml` is referenced by the Quick Settings tile and FGS chip. It's a small 24dp vector. Replace if rebranding the Always-On Read & Respond surface.

---

## 4. Verification after swap

```bash
# Visual smoke
adb install -r app/app/build/outputs/apk/fdroid/debug/*.apk
adb shell am start -n com.aikeyboard.app.debug/com.aikeyboard.app.settings.SettingsActivity

# Confirm the launcher icon picked up the new asset
adb shell pm dump com.aikeyboard.app.debug | grep -E "icon|banner" | head -5

# Confirm fastlane structure parses (run from repo root)
test -f fastlane/metadata/android/en-US/images/icon.png             && echo "icon ✓"             || echo "icon MISSING"
test -f fastlane/metadata/android/en-US/images/featureGraphic.png   && echo "feature ✓"          || echo "feature MISSING"
test -f fastlane/metadata/android/en-US/images/phoneScreenshots/1.png && echo "screenshot 1 ✓"  || echo "screenshot 1 MISSING"
```

For F-Droid metadata validation, install `fdroidserver` (Python) and run `fdroid lint --format` against the metadata directory once the F-Droid `fdroiddata` PR is open. F-Droid's reviewer will flag any missing field.

---

## Summary table

| Asset | Required for v0.1.0? | Easiest tooling |
|---|---|---|
| F-Droid icon + featureGraphic | Yes (else empty index page) | Figma / GIMP / `magick` resize |
| F-Droid phoneScreenshots (≥ 3) | Yes (else empty carousel) | `adb exec-out screencap -p` |
| Launcher PNGs (10 files) | Yes if rebranding | Android Studio Image Asset wizard |
| Adaptive icon XMLs (4 files) | Yes if rebranding | Android Studio Image Asset wizard |
| Notification / tile drawable | No (cosmetic) | hand-edit vector XML |
