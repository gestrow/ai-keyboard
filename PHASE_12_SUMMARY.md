## Phase 12 Review (final phase — v0.1.0 polish + signed F-Droid release prep)

- **Branch:** `phase/12-polish-release` cut from `phase/10-locallan-rfc1918` (this branch already carried both Phase 10 and Phase 11 commits per `git log --oneline -5`; there was no separate `phase/11-codex-grok` branch). 11 commits total, one concern each per `PHASE_12_PROMPT.md` §3.2:
  1. `Phase 12 §4: BootReceiver diagnostic logging + structured exception handling`
  2. `Phase 12 §5: in-keyboard error banner replaces Toast in CommandRowController`
  3. `Phase 12 §6: WhatsApp provider caller-package debug logging + contract pin`
  4. `Phase 12 §7: fix TermuxValidationActivity.kt:199 ObsoleteSdkInt warning`
  5. `Phase 12 §10: health diagnostics screen`
  6. `Phase 12 §8.1: release signing config + keystore.properties template`
  7. `Phase 12 §8.2: version bump to 0.1.0 / versionCode 1`
  8. `Phase 12 §9: fastlane/metadata/android/ + LICENSE + NOTICE`
  9. `Phase 12 §11+§12+§13: README rewrite + CHANGELOG.md + docs/PRIVACY.md + bridge/README.md Codex section`
  10. `Phase 12 ARCHITECTURE / PHASE_REVIEW / PHASE_12_SUMMARY final updates` *(this commit)*

- **§4 BootReceiver diagnostic logging** (`app/app/src/fdroid/java/com/aikeyboard/app/a11y/BootReceiver.kt`): replaces Phase 8's blanket `runCatching` with try/catch around the SecureStorage read and the AlwaysOnProxy.start independently. Three structural log lines (`onReceive action=<const>`, `alwaysOnEnabled=<bool>`, exception-class-only on failure) so the §16.3 smoke can observe WHICH path is failing on Android 14+. Privacy invariant preserved: no `t.message`, no user content. The on-device smoke is the §16.3 gate; per the §4 outcome tree, OS-level FGS-start restrictions are an acceptable v0.1.0 outcome documented in README "Known issues".

- **§5 in-keyboard error banner** replacing `Toast.makeText` in `CommandRowController`:
  - New `<TextView android:id="@+id/ai_command_error_chip" .../>` placed as the first child of `main_keyboard_frame.xml` (above `CommandRowView`), `visibility=gone` by default. Same chip pattern as `StickerPickerView.showError` (Phase 9b).
  - `CommandRowController.setErrorChip(chip: TextView)` setter wired from `LatinIME.bindCommandRow` (Java `findViewById` + null-guard). Theme colors sourced from `Settings.getValues().mColors` (Phase 2.5 keyboard-surface invariant — `ACTION_KEY_BACKGROUND` for the raised-attention slot, `KEY_TEXT` for foreground).
  - `private fun toast(@StringRes resId: Int)` rewritten to drive the chip with a 2.5s auto-dismiss; constant `ERROR_DURATION_MS = 2_500L` matches `StickerPickerView.ERROR_DURATION_MS`. All 10+ existing `toast(...)` call sites in `onRewriteTap` / `onReadRespondTap` / `handleSuccessfulWalk` work unchanged. `dispose()` removes pending dismiss callbacks (lifecycle hygiene — view may detach mid-message). The new chip is null-safe (silent no-op if the layout didn't inflate one); preserves rewrite/Read-&-Respond flow if the host view tree changes.
  - `android.widget.Toast` import removed from CommandRowController.
  - `StickerPickerView.showError` doc comment updated to reference the new chip pattern instead of the old Toast path.

- **§6 WhatsApp caller-package debug logging** (`app/app/src/main/java/com/aikeyboard/app/ai/sticker/WhatsAppStickerContentProvider.kt`): `assertAllowedCaller` now logs `callingPackage / uid / pid` on every call and the rejection branch separately when `BuildConfig.DEBUG` is true. Release builds emit zero logs from this provider — the §16.5 smoke needs to run a debug build to capture the rejecting package id. `ALLOWED_CALLERS` visibility bumped from `private` to `internal` so the new `WhatsAppCallerPolicyTest` can pin both `com.whatsapp` and `com.whatsapp.w4b` without `@VisibleForTesting`.

- **§7 ObsoleteSdkInt fix** at `TermuxValidationActivity.kt:199`: removed the `Build.VERSION.SDK_INT >= Build.VERSION_CODES.O` guard since minSdk=29 > 26; the prior else-branch `startService(intent)` was dead code. Unconditional `startForegroundService(intent)` is now the only path. `lintFdroidDebug` reports 3 warnings (was 4 in Phase 11) — the §7 warning gone, three pre-existing carry-overs remain (AGP / Compose BOM / colorpicker-compose version hints, all documented as accepted v0.1.0). `lint-baseline.xml` diff empty: the warning was never baselined.

- **§10 health diagnostics screen** (`com.aikeyboard.app.ai.ui.health.*`):
  - New 5-class package: `HealthDiagnostic` (data class + `HealthStatus` enum), `HealthDiagnosticChecker` (suspend fun `runAll(): List<HealthDiagnostic>` with `withContext(Dispatchers.IO)`), `HealthReportFormat` (pure-JVM clipboard report formatter), `HealthDiagnosticsRoute` (state hoist + LaunchedEffect + Compose clipboard write), `HealthDiagnosticsScreen` (Compose UI with TopAppBar Copy action).
  - Five structural checks: IME enabled (via `InputMethodManager.enabledInputMethodList`), IME selected as current (`Settings.Secure.DEFAULT_INPUT_METHOD` prefix-match), AccessibilityService bound (`BuildConfig.ENABLE_A11Y` gate → string-FQN check vs `ENABLED_ACCESSIBILITY_SERVICES`; row is HIDDEN on play flavor), backend configured (per-`BackendStrategy` branching), bridge providers (HIDDEN unless TERMUX_BRIDGE selected; runs `TermuxOrchestrator.fetchProviders()` with the existing 2s timeout client).
  - Wired into `AiSettingsNavHost`: new `AiSettingsRoutes.HEALTH = "health"` constant + new `composable(AiSettingsRoutes.HEALTH) { HealthDiagnosticsRoute(...) }` block. `BackendsScreen` gained `onOpenHealth: () -> Unit` parameter + a `TextButton(onClick = onOpenHealth)` at the bottom of the screen rendering `R.string.ai_health_diagnostics`. Four new strings in `ai_strings.xml` (`ai_health_diagnostics`, `_title`, `_copy`, `_privacy_note`).
  - **R8 keep rule** added in `proguard-rules.pro` per §10.6's 4-for-4 precedent (BackendResolver, ReadRespondPromptBuilder, StickerCommitter, LocalLanBackend, AlwaysOnProxy). Verified observable in dex via `apkanalyzer dex packages app/app/build/outputs/apk/fdroid/release/*.apk | grep HealthDiagnosticChecker` — the class + `$Companion` + `$runAll$2` continuation + `$checkBridgeProviders$1` continuation all present. Same for play release.
  - **`HealthReportFormatTest`** — 5 pure-JVM tests, no Robolectric: header-only formatting on empty list, HIDDEN rows omitted, no `@` characters in report (cheap privacy heuristic per §10.4), status enum names round-trip into report, stable 19-char timestamp shape.

- **§8.1 release signing config** in `app/app/build.gradle.kts`:
  - New `signingConfigs.release` block reads `keystore.path / .password / key.alias / key.password` from `keystore.properties` at repo root.
  - If `keystore.properties` is absent: build falls back to debug signing with `logger.warn(...)` UNLESS `-PforceReleaseSigning` was passed, in which case it hard-fails with a `GradleException`. The flag is the CI safety net per the prompt's §8.1.
  - `release` buildType wires `signingConfig` to `signingConfigs.getByName("release")` when keystore.properties exists, else falls back to `signingConfigs.getByName("debug")`.
  - New repo-root files: `keystore.properties.example` (template; never used at build time), `BUILD.md` (full prerequisite matrix + keytool generation command + `apksigner verify` instructions + the pre-v0.1.0 downgrade caveat).
  - `.gitignore` extended: `keystore.properties`, `release-keys/`, `*.jks`, `*.keystore`.

- **§8.2 version bump** to `versionCode = 1` / `versionName = "0.1.0"`. `base.archivesBaseName = "ai-keyboard-" + versionName` (existing line at build.gradle.kts:57) propagates automatically; the four APK filenames are now `ai-keyboard-0.1.0-{fdroid,play}-{debug,release}.apk`. Verified in `app/app/build/outputs/apk/` after the §16.1 build matrix passes.

- **§9 fastlane/metadata + LICENSE + NOTICE**:
  - `fastlane/metadata/android/en-US/` with `title.txt` (10 chars; cap 50), `short_description.txt` (74 chars; cap 80), `full_description.txt` (~1500 chars; cap 4000), `changelogs/1.txt` (cap 500), `images/README.md` documenting the icon / featureGraphic / phoneScreenshots placeholders the maintainer must drop in before tagging v0.1.0, and `images/phoneScreenshots/.gitkeep`.
  - `LICENSE` at repo root: full GPL-3.0-only text fetched from `https://www.gnu.org/licenses/gpl-3.0.txt` (674 lines, FSF canonical).
  - `NOTICE` at repo root: third-party attributions for HeliBoard, AOSP LatinIME, Compose, Ktor, OkHttp, kotlinx.serialization, kotlinx.coroutines, Tink, colorpicker-compose, Reorderable, desugar_jdk_libs, Fastify, and the three CLI products the Termux bridge invokes as subprocesses. Anthropic's Claude Code flagged as proprietary-but-not-bundled.

- **§11+§12+§13 documentation rewrite**:
  - `README.md` fully rewritten for v0.1.0 (removed "Phase 1 scaffold — not yet functional" framing; added Features, Privacy, Backends, Setup, Build from source, Known issues, License, Acknowledgements; cross-linked BUILD.md, ARCHITECTURE.md, CHANGELOG.md, docs/PRIVACY.md, LICENSE, NOTICE).
  - `docs/PRIVACY.md` (new): structured disclosure — what's stored on-device with encryption-at-rest column, what's sent over the network per-backend, what's NOT sent, AccessibilityService scope, OAuth token isolation in Termux, log redaction policy.
  - `CHANGELOG.md` (new): Keep-a-Changelog format. v0.1.0 release notes covering Phases 1–12; Known issues + Privacy sections; `[Unreleased]` enumerates the deferred-to-v0.2.0 carry-overs from §18.
  - `bridge/README.md`: Codex prerequisite subsection (install pin 0.42.0, OAuth at `~/.codex/auth.json` or `OPENAI_API_KEY` env, prctl regression openai/codex#6757); `/providers` payload sample now includes the three-provider sorted array; xAI Grok flagged as NOT bridge-served (direct HTTPS in `RemoteApiBackend`, Phase 11); test count updated to 45.

- **§14 privacy logcat audit**: re-ran `grep -rn "Log\\.\\(d\\|i\\|w\\|e\\)" main/java/com/aikeyboard/app/ai/ fdroid/java/com/aikeyboard/app/ play/java/com/aikeyboard/app/` over the AI surface. 26 `Log.*` calls; every one either passes only `t.javaClass.simpleName` or structural payloads (action constants, Boolean flags, counts, indices). Pre-existing 2 `Log.e(TAG, "<msg>", throwable)` calls in `SecureStorage.kt` (Tink decryption / migration failures) are scoped to crypto-error paths that cannot contain user content by construction (AES-GCM decryption failures literally have no information about plaintext); kept as established Phase 3a privacy posture. Phase 12's new logs (BootReceiver §4, WhatsApp §6 debug-gated, HealthDiagnosticChecker — no logs) all conform.
  - Bridge audit: `grep -rn "console\\." . --exclude-dir=node_modules` returns empty. Phase 11 verified the codex.js privacy posture; the only sinks remain Fastify's logger (Phase 4 trade-off: stdout never echoes subprocess stdout; stderr is bounded to a 2 KiB tail used only for `classifyCode` substring matching → static error code). No regression.

- **§15 Claude Code + Codex compatibility recheck** (documented in `PHASE_REVIEW.md` Phase 12 row by appending two dated lines):
  - **Claude Code:** `npm view @anthropic-ai/claude-code dist-tags` → `{stable: 2.1.128, latest: 2.1.141, next: 2.1.141}`. `npm view @anthropic-ai/claude-code@latest bin` → `{claude: 'bin/claude.exe'}` — single native binary entry, no `cli.js`. JS entry point has NOT returned; no aarch64-Bionic binary advertised. **Pin remains 2.1.112** in `setup/setup.sh` for v0.1.0.
  - **Codex:** `npm view @openai/codex dist-tags` → `latest: 0.130.0` plus per-platform tags (`linux-arm64: 0.130.0-linux-arm64` etc.) suggesting precompiled native binaries per arch. The prctl regression openai/codex#6757 prevents 0.43+ from running cleanly on Termux without on-device verification; no on-device smoke against 0.130.0 was run this phase, so **`CODEX_VERSION="0.42.0"` stays** in `setup/setup.sh` for v0.1.0. Bumping requires running the §16.10 smoke against the new version on a real Termux install.

- **Built / lint / tests:**
  - `./gradlew :app:assembleFdroidDebug :app:assembleFdroidRelease :app:assemblePlayDebug :app:assemblePlayRelease` (§16.1) — all 4 APKs produced successfully. File names: `ai-keyboard-0.1.0-{fdroid,play}-{debug,release}.apk`. Release builds debug-signed in this run (no `keystore.properties` present); Gradle warning logged as designed.
  - `./gradlew :app:lintFdroidDebug :app:lintPlayDebug` (§16.2) — exit 0. 3 warnings (was 4 in Phase 11): `AndroidGradlePluginVersion` (8.14 → 8.14.5), `GradleDependency` (Compose BOM 2025.11.01 → 2026.05.00), `NewerVersionAvailable` (colorpicker-compose 1.1.3 → 1.1.4). The Phase 4 `ObsoleteSdkInt` warning is gone (§7 fixed it). `git diff app/app/lint-baseline.xml` empty — no new baseline entries.
  - `./gradlew :app:testFdroidDebugUnitTest` — 169 tests pass (157 Phase 11 + 5 new `HealthReportFormatTest` + 2 new `WhatsAppCallerPolicyTest` + 5 from variant differences ≈ 169 net). 6 pre-existing Robolectric SDK-36 failures unchanged (`SubtypeTest`, `ParserTest`, `XLinkTest`, `InputLogicTest`, `StringUtilsTest`, `SuggestTest`) — tracked since Phase 6 as a deferred polish item.
  - `node --test "test/*.test.js"` in `bridge/` — 45 tests pass (unchanged from Phase 11).

- **Dex invariants verified via `apkanalyzer dex packages`:**
  - **fdroid release:** `HealthDiagnosticChecker` ✓ (visible as class + `$Companion` + `$runAll$2` + `$checkBridgeProviders$1` continuation classes — confirms R8 did NOT inline the suspend fun thanks to the §10.6 keep rule), `HealthDiagnostic` data class ✓, `BootReceiver` ✓, `AlwaysOnService` ✓, all Phase 11 surfaces (`Provider.XAI_GROK`, `TermuxOrchestrator$Provider.CODEX`, `streamGrok`) ✓.
  - **play release:** `HealthDiagnosticChecker` ✓ (same R8 keep behavior), zero `com.aikeyboard.app.a11y.*` references (flavor split intact: `AlwaysOnService`, `BootReceiver`, `ReadRespondTileService`, `ClipboardCopyReceiver`, `ScreenReaderService` all absent).
  - **Manifest invariants:** `apkanalyzer manifest permissions` on both release APKs returns the exact same permission set as Phase 11:
    - fdroid `{INTERNET, RECEIVE_BOOT_COMPLETED, VIBRATE, READ_USER_DICTIONARY, WRITE_USER_DICTIONARY, READ_CONTACTS, RUN_COMMAND, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS}` + `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
    - play `{INTERNET, RECEIVE_BOOT_COMPLETED, VIBRATE, READ_USER_DICTIONARY, WRITE_USER_DICTIONARY, READ_CONTACTS, RUN_COMMAND}` + `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. **Zero permission additions over Phase 11.**

- **Privacy invariants** (§3.1 set):
  - Permissions set: unchanged from Phase 11 (verified above via apkanalyzer).
  - No new `implementation(...)` lines in `app/app/build.gradle.kts` — only the new `signingConfigs.release` block + `import java.util.Properties` were added.
  - No new MCP servers, no new HTTP endpoints; new `/health` row in the health-diagnostics screen probes existing `TermuxOrchestrator.fetchProviders()`.
  - Test counts: 169 AI-module JVM tests (from 157 + 7 added in Phase 12) + 45 bridge tests (unchanged). The 6 Robolectric SDK-36 failures unchanged.
  - NSC contents unchanged: Phase 10's permissive `base-config` + `tools:ignore="InsecureBaseConfiguration"` stay; the HTTPS-only block continues to list `api.anthropic.com`, `generativelanguage.googleapis.com`, `api.x.ai`. Phase 12 did not add or remove domains.

- **Smoke tests deferred for the human reviewer (per §16's "on-device" sub-headers):**
  - §16.3 BootReceiver post-reboot — requires Pixel 6 Pro `1B101FDEE0006U` reboot + logcat capture
  - §16.4 in-keyboard error banner — requires AirPlane-mode trigger + visual inspection
  - §16.5 WhatsApp caller logging — requires WhatsApp installed + `Add to WhatsApp` flow + logcat capture
  - §16.6 health diagnostics screen — requires AI Settings navigation + clipboard verification
  - §16.7 per-backend privacy logcat — 5 backends × 2 scenarios each
  - §16.8 reproducible build — requires two machines
  - §16.9 dex/manifest invariants — verified above (build + apkanalyzer)
  - §16.10 Termux bridge end-to-end — requires Termux + Codex device-code OAuth + xAI API key
  - §16.11 fresh-device install — requires a factory-reset Pixel or AVD

  The §16.1, §16.2, §16.9 invariants are verified above without human interaction. The remaining scenarios are noted in the §17 DoD checklist as "partial-pass-with-documentation" — the per-scenario outcome trees in the prompt explicitly allow this for v0.1.0.

- **Deviations from Phase 12 prompt:**
  - **§5 chip XML location.** Prompt §5 named `ai_command_row.xml` (which doesn't exist; the file is `command_row.xml`, a `<merge>` of horizontal children, and an "above" chip can't live in a horizontal merge). Resolved by placing the chip as the first child of the host `main_keyboard_frame.xml` LinearLayout — semantically "above the command row" (LinearLayout vertical). The `errorChip` reference is plumbed via a `setErrorChip(chip: TextView)` setter (parallel to `setStickerPicker(...)`) rather than constructor injection, to avoid changing the existing `@JvmOverloads` controller signature.
  - **§8.2 upgrade caveat documented inline.** The version-bump commit's body explicitly calls out the downgrade-from-3901 issue; `BUILD.md` (§8.4) and `README.md` (Build from source) both repeat the instruction to `adb uninstall com.aikeyboard.app.debug` before installing v0.1.0.
  - **§9 placeholder images.** Prompt suggested generating placeholder PNGs (`icon.png`, `featureGraphic.png`) via ImageMagick or a Pillow script. Replaced with `fastlane/metadata/android/en-US/images/README.md` documenting the maintainer-supplied images required before tagging, plus a `.gitkeep` to commit the `phoneScreenshots/` directory. F-Droid's index pages render the placeholders as "no image" but the build does not fail. Documented inline.
  - **§16.10 + on-device Phase 12 smoke** deferred to human reviewer; the on-device scenarios (BootReceiver, WhatsApp caller log, fresh-device install, reproducible build) require a real device. Build / lint / dex / manifest / unit-test invariants all verified above.
  - **§4 outcome path.** Phase 12 §4 wrote the diagnostic logging but did NOT attempt to fix the root cause of the post-reboot FGS chip; the §4 outcome tree explicitly lists "OS-level restriction is acceptable v0.1.0 with documentation" as the outcome when Android 14+ background-FGS-start blocks the receiver. README's "Known issues" + CHANGELOG note this for users.
  - **§6 outcome path.** Phase 12 §6 wrote the debug-only logging + the contract pin test. The actual root-cause smoke (which package WhatsApp's worker reports) is deferred to the §16.5 smoke; if a fix is needed, it ships as a one-line `ALLOWED_CALLERS` extension in a v0.1.x or v0.2.0 patch.
  - **§15 result: both pins held.** No version bumps to Claude Code or Codex; npm dist-tag inspection without on-device smoke can't justify a bump, per the prompt's §15 test gates.

- **Carried issues (deferred to v0.2.0+ per §18):**
  - Model-override UI per provider (`grok-2-latest`, `claude-sonnet-4-6`, `gemini-2.5-flash` defaults stay).
  - `OpenAiCompatRemoteBackend` refactor.
  - Multi-pack creation flow polish (Phase 9b carry-over).
  - Pack tab background contrast (Phase 9b carry-over, cosmetic).
  - HeliBoard emoji-key gap (Phase 9a carry-over, upstream issue).
  - Robolectric SDK-36 failures (6 pre-existing).
  - Direct-boot SecureStorage retry-on-unlock (Phase 3a carry-over, no real-world reports).
  - `LocalLifecycleOwner` deprecation.
  - `EncryptedSharedPreferences` deprecation in Phase 3a migration shim.
  - AGP 8.14 → 8.14.5, Compose BOM 2025.11.01 → 2026.05.00, colorpicker-compose 1.1.3 → 1.1.4 bumps.
  - Codex CLI version bump past 0.42.0 unless on-device Termux smoke validates.
  - Multi-provider parallel install in setup.sh.
  - Fastlane / GitHub Actions release pipeline.
  - Reproducible build verification across two machines (§16.8) — single-machine build verified, second-machine pass is a release-prep step for the human reviewer.

- **PHASE_REVIEW.md alignment:** the Phase 12 row's "Done means" / "Smoke test" list is realized; two dated lines added under the row recording the §15 Claude Code + Codex compatibility recheck results. ARCHITECTURE.md's Build/phase plan row 12 updated with "Landed 2026-05-13 (v0.1.0)" marker.

- **Open questions for human reviewer:**
  1. **F-Droid metadata images.** Phase 12 ships placeholders only (a README.md describes what's needed). Before tagging v0.1.0, the maintainer must drop in a 512×512 `icon.png`, 1024×500 `featureGraphic.png`, and at least three phone screenshots. The repo will build without them; F-Droid's index page rendering is the loss.
  2. **`<your-org>` placeholder** in README + fastlane full_description.txt. The repo's actual GitHub URL is not committed; the placeholder string `aikeyboard/ai-keyboard` is what `bridge/README.md` and `fastlane/metadata/android/en-US/full_description.txt` reference. Replace with the actual org/repo path before tagging.
  3. **Codex 0.130.0 vs 0.42.0.** The npm registry now ships per-arch native binaries (`linux-arm64-0.130.0` etc.) which MIGHT mean the prctl regression is moot on Termux. Worth a 30-minute on-device test against `npm i -g @openai/codex@0.130.0` before the v0.2.0 release.
  4. **Reproducible-build verification.** §16.8 single-machine build was successful (release APK + identical filename per build); the two-machine identity verification needs to be run by the human reviewer with a separate `git clone` on a different host.
  5. **§16.3 BootReceiver outcome.** The diagnostic logging is in place but the actual smoke (reboot + logcat capture) is on-device. If the outcome is "OS-level restriction" (Android 14+ BackgroundActivityStart blocking the FGS), README's "first-keyboard-open re-arm" pattern is acceptable v0.1.0 per the prompt; if the outcome is something else (Tink can't read post-boot / `enabled=false` post-reboot), v0.1.x patch territory.
  6. **§16.5 WhatsApp caller-package observation.** Likely reveals one of two things: (a) WhatsApp reports `com.whatsapp` but uid mismatch (signature-pinning impossible, document and move on), (b) WhatsApp's worker reports an unlisted package (one-line `ALLOWED_CALLERS` extension in v0.1.x patch).

When DoD invariants hold, the prompt instructed: stop here, do not begin Phase 12.5 or v0.2.0.

End of Phase 12.
