# Phase 9b — WhatsApp sticker pack contract + tray icon + multi-pack tabs + 9a UX bug fixes

This prompt is the second half of `ARCHITECTURE.md`'s Phase 9. Phase 9a delivered the import → normalize → app-private storage → on-keyboard picker → `COMMIT_CONTENT` insertion path. Phase 9b adds WhatsApp's separate sticker-pack `Intent` + `ContentProvider` contract (which `COMMIT_CONTENT` does NOT satisfy — WhatsApp uses its own protocol), the tray-icon picker that path requires, and on-keyboard pack tabs (since each WhatsApp pack is a deliberate organizational unit).

It also fixes two UX bugs surfaced by Phase 9a's on-device smoke test:

- **9a carry-over #1: Sticker-context toast positioning** — `Toast.makeText(ime, ...).show()` from `CommandRowController.toast()` defaults to `Gravity.BOTTOM` with a ~64dp y-offset. When the picker is up it occupies the entire keyboard area (~280dp), so toasts render *behind* the picker and are invisible. All four `StickerCommitter.Result.{NO_CONNECTION, UNSUPPORTED_FIELD, FAILED, missing_file}` paths are affected. Fix: inline error chip inside the picker view (avoids `setGravity` deprecation on API 30+ and survives any future toast-positioning policy changes).
- **9a carry-over #2: Post-pack-delete blank screen** — `StickerPackEditRoute` re-derives its pack from `storage.changes` *after* `deletePack` runs. The recomposition observes a now-null pack and renders nothing while `onBack()` races behind it; the user lands on a blank surface and has to manually navigate back into Settings → Stickers to see the empty state. Fix: pop navigation **before** mutating storage, OR observe pack-disappearance in a `LaunchedEffect` and trigger `onBack` from there. Phase 9b uses the LaunchedEffect path (cleaner; matches existing AlwaysOn route pattern).

Stop when Phase 9b's Definition of Done holds. This concludes ARCHITECTURE.md's Phase 9 row.

---

## Read these first (do not skip)

1. `/Users/kacper/SDK/apk-dev/ai-keyboard/PHASE_9a_SUMMARY.md` — what shipped in 9a, esp. the seven deviations and the Phase 9b touchpoints (§"Touchpoints for Phase 9b" lists every file 9b modifies). The summary's open questions 1, 3, 4, 5, 6 all carry into 9b's design space.
2. `/Users/kacper/SDK/apk-dev/ai-keyboard/ARCHITECTURE.md` — the StickerEngine bullet under "Module: app/" and the Phase 9 row in the build/phase plan. Note the explicit "WhatsApp does NOT use COMMIT_CONTENT" callout — that's what this phase is for.
3. `/Users/kacper/SDK/apk-dev/ai-keyboard/PHASE_REVIEW.md` — universal checklist + Phase 9 acceptance criteria (item 2 is WhatsApp-specific). Keyboard-surface UI invariants apply: pack-tabs strip is on the picker view, must use HeliBoard runtime colors.
4. **Existing 9a files this phase modifies:**
   - `app/app/src/main/java/com/aikeyboard/app/ai/sticker/StickerModels.kt` — extended with three new fields (all default-valued for back-compat per Phase 6's `selectedBackendStrategy` precedent).
   - `app/app/src/main/java/com/aikeyboard/app/ai/sticker/StickerStorage.kt` — gains `setTrayIcon`, `setPublisher`, `setStickerEmojis`. `update` already bumps `_changes`; Phase 9b extends it to also bump per-pack `imageDataVersion` (so WhatsApp re-fetches modified packs).
   - `app/app/src/main/java/com/aikeyboard/app/ai/commandrow/CommandRowController.kt` — replace the four `toast(R.string.ai_stickers_commit_*)` calls with a new `picker.showError(stringRes)` path (carry-over #1 fix).
   - `app/app/src/main/java/com/aikeyboard/app/ai/sticker/picker/StickerPickerView.kt` — gains `showError(stringRes)` API + horizontal pack-tabs strip above the grid.
   - `app/app/src/main/java/com/aikeyboard/app/ai/sticker/picker/StickerGridAdapter.kt` — gains a per-pack filter so the strip can flip the visible pack.
   - `app/app/src/main/java/com/aikeyboard/app/ai/ui/stickers/StickerPackEditRoute.kt` — gains the LaunchedEffect-on-pack-disappearance fix (carry-over #2).
   - `app/app/src/main/java/com/aikeyboard/app/ai/ui/stickers/StickerPackEditScreen.kt` — gains tray-icon picker, publisher field, per-sticker emoji editor, "Add to WhatsApp" button + preflight error chips.
   - `app/app/src/main/AndroidManifest.xml` — registers `WhatsAppStickerContentProvider`; `<queries>` entry for both WhatsApp packages.
   - `app/app/proguard-rules.pro` — preemptive keep rules for the three new singleton objects (lesson learned from Phase 9a: R8 inlines single-call-site singletons; this is now a 3-for-3 pattern with `BackendResolver` + `ReadRespondPromptBuilder` + `StickerCommitter`).
5. `/Users/kacper/SDK/apk-dev/ai-keyboard/app/app/src/main/java/com/aikeyboard/app/ai/ui/stickers/StickerPacksRoute.kt` — the import flow precedent for the tray-icon picker (single-image variant of `PickMultipleVisualMedia`).
6. **WhatsApp's official sticker reference:** the contract is documented at <https://github.com/WhatsApp/stickers/tree/main/Android>. Cursor column names + intent action + minimum/maximum sticker counts come from there. This phase encodes the contract; the executor does not need to fetch this URL during execution since the constants are listed inline below.

---

## Architectural decisions (locked before code)

### 1. Two WhatsApp packages, one intent

WhatsApp ships two distinct apps: `com.whatsapp` (consumer) and `com.whatsapp.w4b` (Business). Both register an Activity for `com.whatsapp.intent.action.ENABLE_STICKER_PACK`. Strategy:

- Fire the intent **without specifying a target package**. If both apps are installed, Android's chooser appears (this is the standard intent-resolution behavior and the right UX — lets the user pick which app to add the pack to).
- If neither is installed, the intent resolution returns no activities → show "WhatsApp not installed" toast (positioned to NOT collide with the picker; we're moving toasts off the keyboard surface in this phase anyway).
- The "Add to WhatsApp" button in `StickerPackEditScreen` is only enabled when at least one of the two packages is detected via `packageManager.queryIntentActivities`.
- `<queries>` entries for **both packages** in `src/main/AndroidManifest.xml` (Android 11+ package visibility — without these, `queryIntentActivities` returns empty even for installed apps).

### 2. ContentProvider authority is `${applicationId}.whatsapp.stickers`

Distinct from Phase 9a's `${applicationId}.stickers` FileProvider authority and from the legacy `${applicationId}.provider` GestureFileProvider authority. Each `<provider>` element needs a unique authority per Android docs.

The provider is `exported="true"` (WhatsApp must reach it from its own process), `grantUriPermissions="false"` (we explicitly serve files via `openAssetFile`, not via per-URI grants). It carries calling-package gating in code: `query`/`openAssetFile` reject any caller whose `getCallingPackage()` is not in the allowlist `{com.whatsapp, com.whatsapp.w4b}`. This is hardening beyond WhatsApp's reference sample (which allows any caller); the privacy posture mandate justifies it.

### 3. WhatsApp's pack rules are enforced as preflight, not as authoring constraints

The pack-edit screen lets the user create packs in any state (1 sticker, no tray icon, blank publisher). The "Add to WhatsApp" button reads a `StickerPackValidator.validate(pack)` result and renders disabled with a chip listing missing items when validation fails. WhatsApp's official rules are:

- Pack identifier: ASCII `[A-Za-z0-9_.-]`, ≤128 chars. UUIDs (lowercase hex with hyphens) are valid; `id` from Phase 9a's `StickerStorage.createPack` is `UUID.randomUUID().toString()`, which is ASCII-safe and ≤36 chars.
- Pack name: required, ≤128 chars.
- Publisher: required, ≤128 chars.
- Tray icon: required; must be 96×96 PNG, ≤50KB.
- Sticker count: 3 ≤ count ≤ 30.
- Each sticker file: WebP, 512×512, ≤100KB. (Already enforced by 9a's `StickerNormalizer`; we trust this and don't re-validate.)
- Each sticker emoji string: 1–3 emojis recommended (WhatsApp doesn't strictly require, but UX breaks without).

The validator returns a sealed result with one entry per failing rule, so the UI can surface multiple problems simultaneously instead of one-at-a-time error chasing.

### 4. `imageDataVersion` is a Long, monotonically incremented per pack

WhatsApp's column type is `String`, but the underlying contract is "WhatsApp will re-fetch the pack when this value differs from what it cached." Easiest invariant: a per-pack monotonic Long, formatted as a decimal string at provider-cursor time. Bumped by `StickerStorage` on every mutation that touches the pack (rename, sticker add/delete/edit, tray-icon set, publisher change).

This means the existing `update(transform)` envelope in `StickerStorage` needs to be aware of *which packs* a transform changed, so it can bump the right `imageDataVersion`s. Simplest approach: on every `update`, compare `manifest.packs` before and after by `id` × `(name, stickers, trayIconFile, publisher)` deep-equality; bump the version of each pack whose tuple changed.

### 5. Tray-icon format is PNG, not WebP

WhatsApp explicitly requires PNG for the tray icon. Our existing `StickerNormalizer` produces WebP. `TrayIconNormalizer` is a separate object: same decode path (`ImageDecoder` → `Bitmap`), different post-processing (96×96 not 512×512, PNG not WebP, ≤50KB budget not ≤100KB). The quality-search loop differs because PNG quality is binary (lossless or not — in Android the `Bitmap.compress(PNG, ...)` quality argument is ignored). We instead binary-search **bitmap pre-scale dimensions** to fit the byte budget — which is rare in practice for 96×96 (a 96×96 image rarely exceeds 50KB even at maximum quality), so the fast path returns the first attempt.

### 6. On-keyboard pack tabs

The picker grows a horizontal scrollable strip of pack-tray-icon buttons above the grid. UX:

- One button per pack. The button shows the pack's tray icon (or a fallback `ic_sticker` if the pack has no tray yet).
- Default-selected pack: the first pack in `manifest.packs` with at least one sticker.
- Tap a pack button → grid filters to that pack's stickers only. (No more "flat across all packs" view from 9a.)
- The pack name is shown as `accessibilityHint` on the button, not as visible text — saves vertical space, which is precious on the picker surface.
- Strip height: 48dp + 8dp top/bottom padding = 64dp total. The picker's existing `minHeight=240dp` (set by 9a executor to avoid IME shrink) + the new 64dp tab strip = visible IME height is unchanged when picker is up (the toolbar+grid was already ~270dp tall with `minHeight`).

### 7. Per-sticker emoji editing UX is a free-text TextField

Inside `StickerPackEditScreen`, each sticker's grid cell now has a small TextField below the thumbnail accepting up to 3 emojis. Implementation: trim user input on blur, split on whitespace, take the first 3 codepoint clusters (a "codepoint cluster" includes ZWJ-joined emoji sequences). Saved via `StickerStorage.setStickerEmojis(packId, stickerId, emojis: List<String>)`.

No custom emoji picker — we're an IME, the system emoji keyboard works. Hint text: "1–3 emojis".

---

## §1 — Files to create

```
app/app/src/main/java/com/aikeyboard/app/ai/sticker/
  TrayIconNormalizer.kt                   # Bitmap → 96×96 PNG ≤50KB
  StickerPackValidator.kt                 # pure-JVM preflight
  AddToWhatsAppHelper.kt                  # intent assembly + WhatsApp detection
  WhatsAppStickerContentProvider.kt       # the WhatsApp contract

app/app/src/main/java/com/aikeyboard/app/ai/sticker/picker/
  StickerPackTabsView.kt                  # horizontal pack-tab strip

app/app/src/main/res/layout/
  sticker_pack_tab.xml                    # single tab cell
  sticker_picker_error_chip.xml           # in-picker error banner (replaces the broken toast path)

app/app/src/test/java/com/aikeyboard/app/ai/sticker/
  StickerPackValidatorTest.kt
  AddToWhatsAppHelperTest.kt              # intent-extra assembly + package detection (JVM-pure helpers extracted)
  WhatsAppStickerContentProviderTest.kt   # cursor-shape tests via Robolectric or pure-JVM helpers
  TrayIconNormalizerTest.kt               # quality-search + scale logic
```

## §2 — Files to modify

```
app/app/src/main/java/com/aikeyboard/app/ai/sticker/StickerModels.kt
  StickerPack:
    + trayIconFile: String? = null
    + publisher: String = ""
    + imageDataVersion: Long = 1L
    + avoidCache: Boolean = false

app/app/src/main/java/com/aikeyboard/app/ai/sticker/StickerStorage.kt
  + setTrayIcon(packId, fileName)
  + setPublisher(packId, publisher)
  + setStickerEmojis(packId, stickerId, emojis: List<String>)
  + trayIconFile(packId, fileName) → File   (sibling of stickerFile)
  Modified update() to compare pre/post pack tuples and bump imageDataVersion
  for each changed pack.

app/app/src/main/java/com/aikeyboard/app/ai/commandrow/CommandRowController.kt
  Replace the four toast() calls in commitSticker with picker.showError(stringRes).
  toast(R.string.ai_stickers_commit_missing_file) becomes
  picker?.showError(R.string.ai_stickers_commit_missing_file).

app/app/src/main/java/com/aikeyboard/app/ai/sticker/picker/StickerPickerView.kt
  + new method showError(stringRes: Int) — flashes a banner above the grid for ~2.5s
  + integrates StickerPackTabsView at the top of the existing layout (toolbar
    stays; tabs go between toolbar and grid)
  + bind(packs, packDirResolver) extended: now passes pack list to the tabs
    strip and tracks the selected pack id in member state

app/app/src/main/java/com/aikeyboard/app/ai/sticker/picker/StickerGridAdapter.kt
  update(items, resolver) gains a 3rd parameter selectedPackId: String? = null
  When non-null, the adapter filters items to only that pack's stickers.

app/app/src/main/res/layout/sticker_picker.xml
  + StickerPackTabsView between toolbar and grid (above the empty TextView too)
  + sticker_picker_error_chip layout slot (a TextView at the top, visibility=gone)

app/app/src/main/java/com/aikeyboard/app/ai/ui/stickers/StickerPackEditRoute.kt
  + LaunchedEffect(packs) { if (packs.firstOrNull { it.id == packId } == null) onBack() }
    placed AFTER the route reads its packs, so a delete-from-this-screen pops back
    automatically without leaving the user on a null-pack render.
  + plumbing for new screen affordances (publisher, tray icon, emojis, add-to-whatsapp)
    — see §10.

app/app/src/main/java/com/aikeyboard/app/ai/ui/stickers/StickerPackEditScreen.kt
  + tray-icon row: ImageView showing current tray icon (or placeholder), button
    "Choose tray icon" → launches single-image PickVisualMedia → TrayIconNormalizer
  + publisher TextField below pack name
  + per-sticker emoji TextField in each grid cell
  + "Add to WhatsApp" Button at the bottom, with disabled state + preflight
    error chips when validator fails

app/app/src/main/AndroidManifest.xml
  + <provider> for WhatsAppStickerContentProvider with authority
    ${applicationId}.whatsapp.stickers, exported=true, grantUriPermissions=false
  + <queries>
      <package android:name="com.whatsapp" />
      <package android:name="com.whatsapp.w4b" />
    </queries>

app/app/proguard-rules.pro
  + keep rules for TrayIconNormalizer, StickerPackValidator, AddToWhatsAppHelper
    (preemptive — Phase 9a's StickerCommitter regression confirms R8 inlines
    single-call-site singletons; this is now a 3-for-3 pattern. Verify with
    apkanalyzer dex packages on fdroid release; if any are inlined out, the
    keep rule was needed and is doing its job.)

app/app/src/main/res/values/ai_strings.xml
  + ai_stickers_whatsapp_* strings (see §13)
  + ai_stickers_tray_* strings
  + ai_stickers_emoji_* strings
  + ai_stickers_pack_publisher_* strings
  + ai_stickers_validation_* strings (one per failure type)
```

---

## §3 — `StickerModels.kt` (extension)

```kotlin
@Serializable
data class StickerPack(
    val id: String,
    val name: String,
    val createdAt: Long,
    val stickers: List<Sticker> = emptyList(),
    /** Phase 9b: filename relative to the pack directory of the 96×96 PNG tray icon.
     *  Null until the user chooses one in pack-edit. WhatsApp requires a tray icon. */
    val trayIconFile: String? = null,
    /** Phase 9b: pack publisher shown in WhatsApp's pack list. Required by WhatsApp. */
    val publisher: String = "",
    /** Phase 9b: monotonic version per pack. Bumped by StickerStorage on every
     *  mutation that touches the pack (rename / sticker add+delete+edit / tray /
     *  publisher). WhatsApp re-fetches a pack whenever this differs from its cache. */
    val imageDataVersion: Long = 1L,
    /** Phase 9b: WhatsApp's "do not cache" hint. Default false; v1 doesn't surface a UI. */
    val avoidCache: Boolean = false,
)
```

The four new fields are all default-valued for back-compat: existing 9a manifest blobs decode unchanged, default values fill in as the user edits packs in 9b.

---

## §4 — `StickerStorage.kt` extensions

Three new mutations + one helper:

```kotlin
fun setTrayIcon(packId: String, fileName: String) {
    update { manifest ->
        manifest.copy(packs = manifest.packs.map {
            if (it.id == packId) it.copy(trayIconFile = fileName) else it
        })
    }
}

fun setPublisher(packId: String, publisher: String) {
    update { manifest ->
        manifest.copy(packs = manifest.packs.map {
            if (it.id == packId) it.copy(publisher = publisher) else it
        })
    }
}

fun setStickerEmojis(packId: String, stickerId: String, emojis: List<String>) {
    update { manifest ->
        manifest.copy(packs = manifest.packs.map { p ->
            if (p.id != packId) p
            else p.copy(stickers = p.stickers.map { s ->
                if (s.id == stickerId) s.copy(emojis = emojis) else s
            })
        })
    }
}

/** Where TrayIconNormalizer should write a new tray icon. The pack directory
 *  exists by createPack invariant; callers can re-mkdirs the parent for orphan recovery. */
fun trayIconFile(packId: String, fileName: String): File =
    File(rootDir, "packs/$packId/$fileName")
```

The existing `update(transform)` envelope must be modified to bump per-pack `imageDataVersion`:

```kotlin
private fun update(transform: (StickerManifest) -> StickerManifest) {
    synchronized(this) {
        val current = getManifest()
        val transformed = transform(current)
        // Phase 9b: bump imageDataVersion for each pack whose content-bearing tuple
        // changed. WhatsApp re-fetches a pack when this differs from its cache.
        val priorById = current.packs.associateBy { it.id }
        val next = transformed.copy(packs = transformed.packs.map { newPack ->
            val priorPack = priorById[newPack.id]
            val packContentChanged = priorPack != null && !packsContentEqual(priorPack, newPack)
            if (packContentChanged) newPack.copy(imageDataVersion = newPack.imageDataVersion + 1)
            else newPack
        })
        persist(next)
        cached = next
        _changes.value += 1
    }
}

/** Two packs are content-equal when their WhatsApp-visible state matches. The
 *  imageDataVersion field itself and createdAt do NOT contribute to equality
 *  (otherwise the bump would cascade indefinitely / be a no-op). */
private fun packsContentEqual(a: StickerPack, b: StickerPack): Boolean =
    a.name == b.name &&
        a.publisher == b.publisher &&
        a.trayIconFile == b.trayIconFile &&
        a.avoidCache == b.avoidCache &&
        a.stickers == b.stickers
```

`Sticker`'s `data class` `equals` already includes `emojis`, so changing emoji tags via `setStickerEmojis` correctly bumps the version.

---

## §5 — `TrayIconNormalizer.kt`

Mirror `StickerNormalizer`'s structure but produce 96×96 PNG ≤50KB.

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.aikeyboard.app.latin.utils.Log
import java.io.ByteArrayOutputStream
import java.io.File

object TrayIconNormalizer {

    private const val TAG = "TrayIconNormalizer"
    private const val TARGET_PX = 96
    private const val TARGET_BYTES = 50 * 1024

    fun normalize(resolver: ContentResolver, src: Uri, dst: File): Boolean {
        return try {
            val source = ImageDecoder.createSource(resolver, src)
            val raw = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
            val cropped = squareCrop(raw)
            raw.recycle()
            val bytes = encodeFitting(cropped, TARGET_PX, TARGET_BYTES)
            cropped.recycle()
            dst.parentFile?.mkdirs()
            dst.writeBytes(bytes)
            true
        } catch (t: Throwable) {
            runCatching { Log.w(TAG, "Tray normalize failed: ${t.javaClass.simpleName}") }
            false
        }
    }

    private fun squareCrop(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height)
        return Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
    }

    /**
     * PNG quality is lossless — the `quality` argument to Bitmap.compress(PNG, q, …)
     * is ignored. To fit a byte budget we instead binary-search the bitmap dimension
     * (downsampling the 96×96 to a smaller PNG when needed). 96×96 is small enough
     * that this rarely fires; the fast path returns the first attempt.
     */
    @Suppress("SameParameterValue")
    internal fun encodeFitting(bitmap: Bitmap, targetPx: Int, budget: Int): ByteArray =
        encodeFitting({ px: Int -> compressAtPx(bitmap, px) }, targetPx, budget)

    /** Pure helper: extracted for unit testing. */
    internal fun encodeFitting(
        compress: (Int) -> ByteArray,
        targetPx: Int,
        budget: Int,
    ): ByteArray {
        val first = compress(targetPx)
        if (first.size <= budget) return first
        // Rare branch: shrink in 8px steps until it fits, floor at 32px.
        var px = targetPx - 8
        var best = first
        while (px >= 32) {
            val attempt = compress(px)
            if (attempt.size <= budget) return attempt
            best = attempt
            px -= 8
        }
        return best
    }

    private fun compressAtPx(bitmap: Bitmap, px: Int): ByteArray {
        val target = if (bitmap.width == px && bitmap.height == px) bitmap
                     else Bitmap.createScaledBitmap(bitmap, px, px, true)
        val baos = ByteArrayOutputStream()
        @Suppress("DEPRECATION") // PNG format does not have a non-deprecated spelling.
        target.compress(Bitmap.CompressFormat.PNG, 100, baos)
        if (target !== bitmap) target.recycle()
        return baos.toByteArray()
    }
}
```

The `encodeFitting(compress, targetPx, budget)` overload takes the compress measure as a function so the unit test can drive the search with a synthetic byte-size curve. Same precedent as 9a's `compressToTargetBytes` test extraction.

---

## §6 — `WhatsAppStickerContentProvider.kt` (the core deliverable)

This is the largest file in 9b. It exposes WhatsApp's URI hierarchy via a `MatrixCursor` returned from `query` and an `AssetFileDescriptor` returned from `openAssetFile`. All write methods (`insert`/`update`/`delete`) return null/0 — WhatsApp doesn't write through this provider.

### URI hierarchy

```
content://${applicationId}.whatsapp.stickers/metadata               → all packs
content://${applicationId}.whatsapp.stickers/metadata/<pack-id>     → one pack
content://${applicationId}.whatsapp.stickers/stickers/<pack-id>     → stickers in pack
content://${applicationId}.whatsapp.stickers/stickers_asset/<pack-id>/<filename>  → file
```

### Cursor columns (from WhatsApp's spec — DO NOT rename)

For `metadata`:
```
sticker_pack_identifier (String)
sticker_pack_name (String)
sticker_pack_publisher (String)
sticker_pack_icon (String)                      — tray icon filename
android_play_store_link (String)                — empty for v1
ios_app_store_link (String)                     — empty
sticker_pack_publisher_email (String)           — empty
sticker_pack_publisher_website (String)         — empty
sticker_pack_privacy_policy_website (String)    — empty
sticker_pack_license_agreement_website (String) — empty
image_data_version (String)                     — Long.toString
whatsapp_will_not_cache_stickers (int 0/1)
animated_sticker_pack (int 0/1)                 — always 0 in v1
sticker_pack_image_redirect_url (String)        — empty
sticker_pack_publisher_redirect_url (String)    — empty
```

For `stickers/<pack-id>`:
```
sticker_file_name (String)
sticker_emoji (String)                          — comma-joined emojis
sticker_accessibility_text (String)             — derived from emojis (joined " ")
```

### Calling-package gating

The provider declares `ALLOWED_CALLERS = setOf("com.whatsapp", "com.whatsapp.w4b")` and calls a single private helper `assertAllowedCaller()` at the top of `query()` and `openAssetFile()` only — **NOT `getType()`**. `getType()` is invoked by the Android framework itself (intent resolution, content-type detection) from the same process, where `getCallingPackage()` returns null; gating it would crash the framework's internal calls. WhatsApp's reference sample also leaves `getType()` ungated.

Concrete shape: a single private instance method definition in the class body (NOT in the companion). See the full structural sketch below — that is the canonical implementation; do not duplicate it here.

`SecurityException` is the canonical way for a `ContentProvider` to refuse access — Android propagates it across the binder call to WhatsApp's process, which surfaces it as a permission denial in WhatsApp's UI.

### Full structural sketch

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.aikeyboard.app.latin.BuildConfig
import java.io.File

class WhatsAppStickerContentProvider : ContentProvider() {

    private val authority by lazy { "${BuildConfig.APPLICATION_ID}.whatsapp.stickers" }

    private lateinit var matcher: UriMatcher
    private lateinit var storage: StickerStorage

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        storage = StickerStorage.getInstance(ctx)
        matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(authority, "metadata", CODE_METADATA_ALL)
            addURI(authority, "metadata/*", CODE_METADATA_SINGLE)
            addURI(authority, "stickers/*", CODE_STICKERS_FOR_PACK)
            addURI(authority, "stickers_asset/*/*", CODE_STICKER_ASSET)
        }
        return true
    }

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?,
    ): Cursor? {
        assertAllowedCaller()
        return when (matcher.match(uri)) {
            CODE_METADATA_ALL -> metadataCursor(storage.getManifest().packs)
            CODE_METADATA_SINGLE -> {
                val packId = uri.lastPathSegment ?: return null
                val pack = storage.getManifest().packs.firstOrNull { it.id == packId } ?: return null
                metadataCursor(listOf(pack))
            }
            CODE_STICKERS_FOR_PACK -> {
                val packId = uri.lastPathSegment ?: return null
                val pack = storage.getManifest().packs.firstOrNull { it.id == packId } ?: return null
                stickersCursor(pack)
            }
            else -> null
        }
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        assertAllowedCaller()
        if (matcher.match(uri) != CODE_STICKER_ASSET) return null
        // Path: stickers_asset/<pack-id>/<filename>
        val segments = uri.pathSegments
        if (segments.size < 3) return null
        val packId = segments[1]
        val fileName = segments[2]
        // Defense in depth: reject path components that try to escape the pack dir.
        if (packId.contains('/') || fileName.contains('/') || fileName.contains("..")) return null
        val ctx = context ?: return null
        val file = File(File(ctx.filesDir, "stickers/packs/$packId"), fileName)
        if (!file.exists()) return null
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, file.length())
    }

    /**
     * NOT gated by assertAllowedCaller — see §6 prose. The framework calls this
     * from our own process (callingPackage == null) for intent-resolution and
     * content-type detection; gating it would crash internal Android calls.
     */
    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        CODE_METADATA_ALL -> "vnd.android.cursor.dir/vnd.$authority.metadata"
        CODE_METADATA_SINGLE -> "vnd.android.cursor.item/vnd.$authority.metadata"
        CODE_STICKERS_FOR_PACK -> "vnd.android.cursor.dir/vnd.$authority.stickers"
        CODE_STICKER_ASSET -> {
            // The asset URI serves both stickers (.webp) and tray icons (.png);
            // dispatch on the file extension. WhatsApp inspects this MIME and
            // will mis-decode if we hardcode one type.
            if (uri.lastPathSegment?.endsWith(".png", ignoreCase = true) == true) "image/png"
            else "image/webp"
        }
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int = 0

    // --- helpers ---

    private fun assertAllowedCaller() {
        val caller = callingPackage
        if (caller == null || caller !in ALLOWED_CALLERS) {
            throw SecurityException("Caller $caller not allowed")
        }
    }

    private fun metadataCursor(packs: List<StickerPack>): Cursor {
        val cursor = MatrixCursor(METADATA_COLUMNS)
        for (pack in packs) {
            cursor.addRow(arrayOf(
                pack.id,
                pack.name,
                pack.publisher,
                pack.trayIconFile.orEmpty(),
                "", "", "", "", "", "",
                pack.imageDataVersion.toString(),
                if (pack.avoidCache) 1 else 0,
                0, // animated_sticker_pack
                "", "",
            ))
        }
        return cursor
    }

    private fun stickersCursor(pack: StickerPack): Cursor {
        val cursor = MatrixCursor(STICKER_COLUMNS)
        for (sticker in pack.stickers) {
            val emojiCsv = sticker.emojis.joinToString(separator = ",")
            val a11yText = sticker.emojis.joinToString(separator = " ").ifEmpty { pack.name }
            cursor.addRow(arrayOf(sticker.fileName, emojiCsv, a11yText))
        }
        return cursor
    }

    companion object {
        private const val CODE_METADATA_ALL = 1
        private const val CODE_METADATA_SINGLE = 2
        private const val CODE_STICKERS_FOR_PACK = 3
        private const val CODE_STICKER_ASSET = 4

        private val ALLOWED_CALLERS = setOf("com.whatsapp", "com.whatsapp.w4b")

        private val METADATA_COLUMNS = arrayOf(
            "sticker_pack_identifier",
            "sticker_pack_name",
            "sticker_pack_publisher",
            "sticker_pack_icon",
            "android_play_store_link",
            "ios_app_store_link",
            "sticker_pack_publisher_email",
            "sticker_pack_publisher_website",
            "sticker_pack_privacy_policy_website",
            "sticker_pack_license_agreement_website",
            "image_data_version",
            "whatsapp_will_not_cache_stickers",
            "animated_sticker_pack",
            "sticker_pack_image_redirect_url",
            "sticker_pack_publisher_redirect_url",
        )

        private val STICKER_COLUMNS = arrayOf(
            "sticker_file_name",
            "sticker_emoji",
            "sticker_accessibility_text",
        )
    }
}
```

**Privacy/security note:** `assertAllowedCaller` is the gate. Without it, any app on the device could query the sticker manifest and download the user's sticker files via this provider. The allowlist is hard-coded; we don't accept signature-based authorization (WhatsApp's signing cert is not stable across regional builds). The trade-off is that a malicious app *could* spoof its callingPackage by claiming to be `com.whatsapp` — but Android's binder transaction includes a verified UID, and `getCallingPackage()` resolves through that UID to the actual package name. Spoofing requires a specifically crafted manifest with `<sharedUserId>android.uid.system</sharedUserId>` and a system-signed APK, which a non-privileged app cannot achieve.

---

## §7 — `AddToWhatsAppHelper.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

object AddToWhatsAppHelper {

    private const val ACTION = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"
    private const val EXTRA_ID = "sticker_pack_id"
    private const val EXTRA_AUTHORITY = "sticker_pack_authority"
    private const val EXTRA_NAME = "sticker_pack_name"

    private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

    enum class WhatsAppStatus { CONSUMER_ONLY, BUSINESS_ONLY, BOTH, NONE }

    fun status(context: Context): WhatsAppStatus =
        statusFromInstalled(WHATSAPP_PACKAGES.filter { isInstalled(context, it) })

    /** Pure helper: extracted for unit testing. */
    internal fun statusFromInstalled(installed: List<String>): WhatsAppStatus = when {
        "com.whatsapp" in installed && "com.whatsapp.w4b" in installed -> WhatsAppStatus.BOTH
        "com.whatsapp" in installed -> WhatsAppStatus.CONSUMER_ONLY
        "com.whatsapp.w4b" in installed -> WhatsAppStatus.BUSINESS_ONLY
        else -> WhatsAppStatus.NONE
    }

    /** Build the intent. Doesn't call startActivity — caller (a Compose route)
     *  uses rememberLauncherForActivityResult(StartActivityForResult()) to fire
     *  it and observe the result. */
    fun buildIntent(packId: String, authority: String, packName: String): Intent =
        Intent(ACTION).apply {
            putExtra(EXTRA_ID, packId)
            putExtra(EXTRA_AUTHORITY, authority)
            putExtra(EXTRA_NAME, packName)
        }

    private fun isInstalled(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)
}
```

`buildIntent` and `statusFromInstalled` are JVM-pure; the only Android call is `getPackageInfo`, isolated in `isInstalled`. Tests cover the pure helpers.

---

## §8 — `StickerPackValidator.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker

object StickerPackValidator {

    enum class Issue {
        TOO_FEW_STICKERS,         // < 3
        TOO_MANY_STICKERS,        // > 30
        MISSING_TRAY_ICON,
        MISSING_PUBLISHER,
        NAME_TOO_LONG,
        PUBLISHER_TOO_LONG,
        IDENTIFIER_INVALID,
    }

    private const val MAX_NAME_LEN = 128
    private const val MAX_PUBLISHER_LEN = 128
    private const val MIN_STICKERS = 3
    private const val MAX_STICKERS = 30
    private val IDENTIFIER_REGEX = Regex("^[A-Za-z0-9_.\\-]+$")

    /** Returns all issues so the UI can render them simultaneously. Empty list = OK. */
    fun validate(pack: StickerPack): List<Issue> = buildList {
        if (pack.stickers.size < MIN_STICKERS) add(Issue.TOO_FEW_STICKERS)
        if (pack.stickers.size > MAX_STICKERS) add(Issue.TOO_MANY_STICKERS)
        if (pack.trayIconFile.isNullOrEmpty()) add(Issue.MISSING_TRAY_ICON)
        if (pack.publisher.isBlank()) add(Issue.MISSING_PUBLISHER)
        if (pack.name.length > MAX_NAME_LEN) add(Issue.NAME_TOO_LONG)
        if (pack.publisher.length > MAX_PUBLISHER_LEN) add(Issue.PUBLISHER_TOO_LONG)
        if (!IDENTIFIER_REGEX.matches(pack.id)) add(Issue.IDENTIFIER_INVALID)
    }
}
```

`Issue` enum maps 1:1 to localized strings (see §13). UI iterates `validate(pack)` and renders an error chip per `Issue`.

---

## §9 — `StickerPackTabsView.kt`

A horizontal `RecyclerView` of pack tabs sitting above the grid in `sticker_picker.xml`. Each tab is a 48dp ImageButton showing the pack's tray icon (or a fallback `ic_sticker` drawable for tray-less packs).

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker.picker

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aikeyboard.app.ai.sticker.StickerPack
import com.aikeyboard.app.latin.R
import com.aikeyboard.app.latin.common.ColorType
import com.aikeyboard.app.latin.settings.Settings
import java.io.File

class StickerPackTabsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {

    var onPackSelected: ((packId: String) -> Unit)? = null

    private val tabAdapter = TabAdapter()

    init {
        layoutManager = LinearLayoutManager(context, HORIZONTAL, false)
        // Pass the click-callback as a constructor arg, NOT via inner-class
        // implicit `this$0`, so the adapter can outlive the view (e.g. during
        // IME input view recreation on theme change) without leaking the view
        // hierarchy. The `tabAdapter` reference holds a strong ref into us
        // anyway via `adapter = tabAdapter`, but a static-nested adapter is
        // the codified pattern in HeliBoard's RecyclerView usage.
        adapter = tabAdapter
        applyKeyboardTheme()
    }

    private val tabAdapter = TabAdapter(onTap = { packId -> onPackSelected?.invoke(packId) })

    fun bind(packs: List<StickerPack>, packDirResolver: (String) -> File, selectedPackId: String?) {
        tabAdapter.update(packs, packDirResolver, selectedPackId)
    }

    private fun applyKeyboardTheme() {
        val colors = Settings.getValues().mColors
        colors.setBackground(this, ColorType.STRIP_BACKGROUND)
    }

    /**
     * Static-nested (NOT `inner`) so the adapter doesn't capture an implicit
     * reference to the outer StickerPackTabsView. Callback is constructor-injected.
     * Phase 9b-specific lifecycle concern: the IME recreates input view on
     * theme changes, so old picker view hierarchies should be GC-eligible
     * promptly. An `inner` adapter would block GC via `this$0` until the
     * adapter itself is collected.
     */
    private class TabAdapter(
        private val onTap: (packId: String) -> Unit,
    ) : Adapter<TabVH>() {
        private var packs: List<StickerPack> = emptyList()
        private var resolver: (String) -> File = { File("") }
        private var selectedPackId: String? = null

        fun update(packs: List<StickerPack>, resolver: (String) -> File, selectedPackId: String?) {
            this.packs = packs
            this.resolver = resolver
            this.selectedPackId = selectedPackId
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = packs.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabVH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.sticker_pack_tab, parent, false) as ImageButton
            return TabVH(view)
        }

        override fun onBindViewHolder(holder: TabVH, position: Int) {
            val pack = packs[position]
            val tray = pack.trayIconFile?.let { File(resolver(pack.id), it) }
            if (tray != null && tray.exists()) {
                holder.btn.setImageBitmap(android.graphics.BitmapFactory.decodeFile(tray.absolutePath))
            } else {
                holder.btn.setImageResource(R.drawable.ic_sticker)
            }
            // Highlight selected via alpha (cheap; no theme-attr roundtrip)
            holder.btn.alpha = if (pack.id == selectedPackId) 1.0f else 0.5f
            holder.btn.contentDescription = pack.name
            holder.btn.setOnClickListener { onTap(pack.id) }
        }
    }

    private class TabVH(val btn: ImageButton) : ViewHolder(btn)
}
```

Layout `sticker_pack_tab.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<ImageButton
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="48dp"
    android:layout_height="48dp"
    android:layout_marginHorizontal="4dp"
    android:padding="6dp"
    android:scaleType="fitCenter"
    android:background="?android:attr/selectableItemBackgroundBorderless" />
```

**Note on `?android:attr/selectableItemBackgroundBorderless`** — Phase 9a deviation #4 surfaced that HeliBoard does NOT depend on AppCompat, so AppCompat's `?attr/selectableItemBackgroundBorderless` doesn't resolve. The framework form `?android:attr/...` works on minSdk 29.

`StickerPickerView.bind` is extended to thread the selected pack id through to both the tabs view and the grid adapter:

```kotlin
// In StickerPickerView
private var selectedPackId: String? = null

fun bind(packs: List<StickerPack>, packDirResolver: (String) -> File) {
    // First-bind: pick the first pack with stickers as default selection.
    if (selectedPackId == null) {
        selectedPackId = packs.firstOrNull { it.stickers.isNotEmpty() }?.id
            ?: packs.firstOrNull()?.id
    }
    tabsView.bind(packs, packDirResolver, selectedPackId)
    val flat = packs.firstOrNull { it.id == selectedPackId }
        ?.stickers
        ?.map { selectedPackId!! to it }
        ?: emptyList()
    adapter.update(flat, packDirResolver)
    emptyView.visibility = if (flat.isEmpty()) View.VISIBLE else View.GONE
    grid.visibility = if (flat.isEmpty()) View.GONE else View.VISIBLE
}

// Wire onPackSelected in init {}
tabsView.onPackSelected = { packId ->
    selectedPackId = packId
    listener?.let {
        // Re-bind with current packs; we don't hold them, so caller must re-call bind
        // — simplest path is: tabs callback bubbles to controller, which re-calls bind.
        // Alternative: cache packs in the view. We cache to avoid the round-trip.
    }
}
```

Pragmatic choice: cache the last-bound packs in `StickerPickerView` so `onPackSelected` can re-bind locally without the controller round-trip:

```kotlin
private var lastPacks: List<StickerPack> = emptyList()
private var lastResolver: (String) -> File = { File("") }

fun bind(packs: List<StickerPack>, packDirResolver: (String) -> File) {
    lastPacks = packs
    lastResolver = packDirResolver
    rebindFromCache()
}

private fun rebindFromCache() {
    // Stale-selection guard: if the previously-selected pack was deleted from
    // Settings while the picker was open, the cached id no longer matches any
    // pack. Reset so the default-selection fallback below picks a valid one.
    if (selectedPackId != null && lastPacks.none { it.id == selectedPackId }) {
        selectedPackId = null
    }
    if (selectedPackId == null) {
        selectedPackId = lastPacks.firstOrNull { it.stickers.isNotEmpty() }?.id
            ?: lastPacks.firstOrNull()?.id
    }
    tabsView.bind(lastPacks, lastResolver, selectedPackId)
    val flat = lastPacks.firstOrNull { it.id == selectedPackId }
        ?.stickers
        ?.map { selectedPackId!! to it }
        ?: emptyList()
    adapter.update(flat, lastResolver)
    emptyView.visibility = if (flat.isEmpty()) View.VISIBLE else View.GONE
    grid.visibility = if (flat.isEmpty()) View.GONE else View.VISIBLE
}

// Then in init:
tabsView.onPackSelected = { packId ->
    selectedPackId = packId
    rebindFromCache()
}
```

---

## §10 — `StickerPackEditScreen.kt` extensions

Four new affordances. Sketch (mirror existing code style; re-use the existing screen's TopAppBar, Column, dialog patterns):

### a) Tray-icon row

Above the existing pack name OutlinedTextField, add a Row showing the current tray icon (decoded from `File(packDir, pack.trayIconFile)` via `LaunchedEffect` + `withContext(Dispatchers.IO)`, mirror existing `StickerCell` pattern) plus a Button "Choose tray icon".

The button launches a single-image variant of PickVisualMedia:

```kotlin
val trayLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.PickVisualMedia()
) { uri ->
    if (uri != null) {
        scope.launch(Dispatchers.IO) {
            val fileName = "tray.png"
            val file = storage.trayIconFile(pack.id, fileName)
            val ok = TrayIconNormalizer.normalize(context.contentResolver, uri, file)
            if (ok) storage.setTrayIcon(pack.id, fileName)
        }
    }
}

Button(onClick = {
    trayLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
}) {
    Text(stringResource(R.string.ai_stickers_tray_choose))
}
```

### b) Publisher TextField

Below the pack name field, **identical SAVE pattern** to the existing pack-name field: a local `var publisher by remember(pack.id) { mutableStateOf(pack.publisher) }`, an OutlinedTextField bound to that local state, and a Save button (or on-blur via `Modifier.onFocusChanged`) that calls `storage.setPublisher(pack.id, publisher.trim())`. **Do NOT wire `onValueChange` directly to `storage.setPublisher`** — every keystroke would bump `imageDataVersion`, trigger the StateFlow, and recompose every screen observing it. The existing pack-name field uses the deferred-save pattern; the publisher follows it. Hint text "Required for WhatsApp" when empty.

### c) Per-sticker emoji TextField

Inside `StickerCell`, add a small OutlinedTextField below the thumbnail accepting the emoji string. **Use the deferred-save pattern, not per-keystroke storage writes** (same reasoning as the publisher field — each write bumps `imageDataVersion`). Local state via `var emojis by remember(sticker.id) { mutableStateOf(sticker.emojis.joinToString(" ")) }`; on blur (via `Modifier.onFocusChanged { state -> if (!state.isFocused) save() }`), trim → split on whitespace → take first 3 codepoint clusters → call `storage.setStickerEmojis(packId, sticker.id, parsed)` only if the parsed list differs from `sticker.emojis`.

A simple "first 3 codepoint clusters" parse:

```kotlin
internal fun parseEmojis(input: String): List<String> {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return emptyList()
    return trimmed.split(Regex("\\s+")).take(3)
}
```

This is approximate — a true emoji-cluster parser would handle ZWJ sequences. For v1 the user-facing contract is "separate emojis with spaces"; the hint text says so. Phase 12 polish can swap in a proper grapheme-cluster parser if real-world usage shows mismatches.

### d) "Add to WhatsApp" button + preflight chips

At the bottom of the screen, add:

```kotlin
val issues = remember(pack) { StickerPackValidator.validate(pack) }
val whatsAppStatus = remember(context) { AddToWhatsAppHelper.status(context) }

if (issues.isNotEmpty()) {
    Column(modifier = Modifier.fillMaxWidth()) {
        for (issue in issues) {
            AssistChip(
                onClick = { /* no-op; chips are informational */ },
                label = { Text(stringResource(issueResId(issue))) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_ai_lock), null) },
            )
        }
    }
}

val whatsAppLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { result ->
    // result.resultCode == 0 → success; non-zero → user cancelled or WhatsApp error.
    // No on-screen feedback for v1 (WhatsApp itself shows confirmation UI).
}

Button(
    enabled = issues.isEmpty() && whatsAppStatus != AddToWhatsAppHelper.WhatsAppStatus.NONE,
    onClick = {
        val intent = AddToWhatsAppHelper.buildIntent(
            packId = pack.id,
            authority = "${context.packageName}.whatsapp.stickers",
            packName = pack.name,
        )
        whatsAppLauncher.launch(intent)
    }
) {
    Text(stringResource(R.string.ai_stickers_whatsapp_add))
}

if (whatsAppStatus == AddToWhatsAppHelper.WhatsAppStatus.NONE) {
    Text(
        stringResource(R.string.ai_stickers_whatsapp_not_installed),
        style = MaterialTheme.typography.bodySmall,
    )
}
```

`issueResId(Issue)` is a small mapper from enum → string resource:

```kotlin
private fun issueResId(issue: StickerPackValidator.Issue): Int = when (issue) {
    StickerPackValidator.Issue.TOO_FEW_STICKERS -> R.string.ai_stickers_validation_too_few
    StickerPackValidator.Issue.TOO_MANY_STICKERS -> R.string.ai_stickers_validation_too_many
    StickerPackValidator.Issue.MISSING_TRAY_ICON -> R.string.ai_stickers_validation_no_tray
    StickerPackValidator.Issue.MISSING_PUBLISHER -> R.string.ai_stickers_validation_no_publisher
    StickerPackValidator.Issue.NAME_TOO_LONG -> R.string.ai_stickers_validation_name_long
    StickerPackValidator.Issue.PUBLISHER_TOO_LONG -> R.string.ai_stickers_validation_publisher_long
    StickerPackValidator.Issue.IDENTIFIER_INVALID -> R.string.ai_stickers_validation_id_invalid
}
```

### e) `StickerPackEditRoute` LaunchedEffect for the post-delete fix

```kotlin
@Composable
fun StickerPackEditRoute(packId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val storage = remember { StickerStorage.getInstance(context) }
    val tick by storage.changes.collectAsState()
    // CRITICAL: keep the SYNCHRONOUS initial-value lookup. Phase 9a's route used
    // this same pattern; switching it to `null` would let the LaunchedEffect
    // below fire on first composition (because `tick` is non-zero from prior
    // mutations on any non-fresh install) and pop the user out before the
    // screen ever renders for an existing pack.
    val pack by produceState<StickerPack?>(
        initialValue = storage.getManifest().packs.firstOrNull { it.id == packId },
        tick,
    ) {
        value = storage.getManifest().packs.firstOrNull { it.id == packId }
    }
    // Phase 9b carry-over fix: when the pack disappears (delete-from-this-screen
    // path), pop back to the list before the recomposition can render a null pack.
    // With the synchronous initialValue above, `pack` is non-null on first
    // composition for any existing packId, so this fires only after a real delete.
    LaunchedEffect(pack) {
        if (pack == null) onBack()
    }
    val packSnapshot = pack
    if (packSnapshot != null) {
        StickerPackEditScreen(pack = packSnapshot, /* ... */)
    }
    // No `?: return` — Composables don't return values, and an early-return
    // pattern is non-idiomatic in Compose. The `if` block leaves the route
    // as an empty surface for the one-frame window before LaunchedEffect pops.
}
```

---

## §11 — `CommandRowController.kt` toast → in-picker error fix

Replace the four `toast(...)` calls in `commitSticker` (and the existing `missing_file` toast):

```kotlin
private fun commitSticker(packId: String, stickerId: String) {
    val manifest = stickerStorage.getManifest()
    val pack = manifest.packs.firstOrNull { it.id == packId } ?: return
    val sticker = pack.stickers.firstOrNull { it.id == stickerId } ?: return
    val file = stickerStorage.stickerFile(packId, sticker.fileName)
    if (!file.exists()) {
        stickerPicker?.showError(R.string.ai_stickers_commit_missing_file)
        return
    }
    val authority = "${ime.packageName}.stickers"
    val result = StickerCommitter.insert(
        context = ime,
        ic = ime.currentInputConnection,
        editorInfo = cachedEditorInfo ?: ime.currentInputEditorInfo,
        stickerFile = file,
        authority = authority,
    )
    when (result) {
        StickerCommitter.Result.OK -> hidePicker()
        StickerCommitter.Result.NO_CONNECTION ->
            stickerPicker?.showError(R.string.ai_stickers_commit_no_connection)
        StickerCommitter.Result.UNSUPPORTED_FIELD ->
            stickerPicker?.showError(R.string.ai_stickers_commit_unsupported)
        StickerCommitter.Result.FAILED ->
            stickerPicker?.showError(R.string.ai_stickers_commit_failed)
    }
}
```

`StickerPickerView.showError(stringRes)` displays an inline TextView at the top of the picker (above the tab strip) for ~2.5 seconds, then auto-fades. The view is `visibility="gone"` by default; `showError` flips it visible, sets the string, and posts a `Handler` token to flip it back. Cancel any pending hide on a new `showError` call to avoid stale messages.

```kotlin
// In StickerPickerView
private val errorChip: TextView by lazy { findViewById(R.id.sticker_picker_error_chip) }
private val errorDismiss = Runnable { errorChip.visibility = View.GONE }

fun showError(@StringRes stringRes: Int) {
    errorChip.removeCallbacks(errorDismiss)
    errorChip.setText(stringRes)
    errorChip.visibility = View.VISIBLE
    errorChip.postDelayed(errorDismiss, ERROR_DURATION_MS)
}

companion object {
    private const val COLUMN_COUNT = 4
    private const val ERROR_DURATION_MS = 2_500L
}
```

Layout addition to `sticker_picker.xml` (place above the tabs strip):

```xml
<TextView
    android:id="@+id/sticker_picker_error_chip"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:padding="12dp"
    android:gravity="center"
    android:textSize="13sp"
    android:visibility="gone"
    android:background="?android:attr/colorBackground"
    tools:text="Error message" />
```

Apply HeliBoard color to the chip in `applyKeyboardTheme()`:

```kotlin
val accentBg = colors.get(ColorType.ACTION_KEY_BACKGROUND)
errorChip.setBackgroundColor(accentBg)
errorChip.setTextColor(colors.get(ColorType.KEY_TEXT))
```

(If `ACTION_KEY_BACKGROUND` is not the right color slot, use whichever `ColorType` HeliBoard exposes for "raised attention" surfaces — `STRIP_BACKGROUND` with a higher alpha overlay is also acceptable. Read `ColorType.kt` to confirm available constants before committing to one.)

---

## §12 — `AndroidManifest.xml` (src/main/) additions

Inside `<application>`, alongside the existing 9a `StickerFileProvider`:

```xml
<provider
    android:name="com.aikeyboard.app.ai.sticker.WhatsAppStickerContentProvider"
    android:authorities="${applicationId}.whatsapp.stickers"
    android:exported="true"
    android:grantUriPermissions="false" />
```

`exported="true"` because WhatsApp must reach it from its own process. `grantUriPermissions="false"` because we serve files via `openAssetFile` not URI grants. **`readPermission` is intentionally omitted** — Android's manifest parser treats `readPermission=""` inconsistently across OEM-modified parsers, so the safer form is to leave the attribute off entirely (effect on AOSP is identical: no manifest-level gate, in-code `assertAllowedCaller()` from §6 is the actual access control). `android:enabled="true"` is the default and is omitted to avoid lint's `RedundantAttribute` flag, which would dirty the lint baseline. Android does not allow signature-based permission gates against arbitrary packages anyway — only same-cert apps could hold a custom permission, which is irrelevant for cross-vendor IPC like WhatsApp.

Inside the existing `<queries>` block (already present from Phase 5b), add:

```xml
<package android:name="com.whatsapp" />
<package android:name="com.whatsapp.w4b" />
```

These declarations are required on Android 11+ for `packageManager.queryIntentActivities` and `getPackageInfo` to see WhatsApp's packages. Without them, `AddToWhatsAppHelper.status()` always returns `NONE` even when WhatsApp is installed.

**No new `<uses-permission>` entries.** WhatsApp doesn't require a custom permission for its sticker-pack contract.

---

## §13 — Strings (`ai_strings.xml`)

```xml
<!-- Phase 9b: WhatsApp sticker pack contract + tray icon + multi-pack tabs. -->

<!-- Tray icon picker -->
<string name="ai_stickers_tray_section">Tray icon</string>
<string name="ai_stickers_tray_hint">96×96 PNG. Shown as the pack icon in WhatsApp\'s sticker tray.</string>
<string name="ai_stickers_tray_choose">Choose tray icon</string>
<string name="ai_stickers_tray_replace">Replace tray icon</string>

<!-- Publisher field -->
<string name="ai_stickers_pack_publisher_label">Publisher</string>
<string name="ai_stickers_pack_publisher_hint">Required for WhatsApp. Shown as the pack author.</string>

<!-- Per-sticker emoji editor -->
<string name="ai_stickers_emoji_label">Emojis</string>
<string name="ai_stickers_emoji_hint">1–3, separated by spaces</string>

<!-- Add to WhatsApp -->
<string name="ai_stickers_whatsapp_add">Add to WhatsApp</string>
<string name="ai_stickers_whatsapp_not_installed">WhatsApp isn\'t installed. Stickers stay usable for sending in Telegram, Signal, Discord, and other apps.</string>
<string name="ai_stickers_whatsapp_added">Added to WhatsApp</string>
<string name="ai_stickers_whatsapp_failed">WhatsApp couldn\'t add the pack</string>

<!-- Validation chips (one per StickerPackValidator.Issue) -->
<string name="ai_stickers_validation_too_few">Add at least 3 stickers</string>
<string name="ai_stickers_validation_too_many">Pack can have at most 30 stickers</string>
<string name="ai_stickers_validation_no_tray">Add a tray icon (96×96 PNG)</string>
<string name="ai_stickers_validation_no_publisher">Set a publisher name</string>
<string name="ai_stickers_validation_name_long">Pack name too long (max 128)</string>
<string name="ai_stickers_validation_publisher_long">Publisher name too long (max 128)</string>
<string name="ai_stickers_validation_id_invalid">Pack ID has an invalid character — recreate the pack</string>

<!-- Picker pack tab content description -->
<string name="ai_stickers_picker_tab_desc">Pack: %1$s</string>
```

---

## §14 — `proguard-rules.pro` (preemptive keep rules)

Phase 9a's `StickerCommitter` regression confirmed that R8 inlines single-call-site singletons in this codebase. Three new singleton objects in 9b match the same risk profile:

```proguard
# Phase 9b: keep singleton objects observable in dex. Same precedent as Phase 6's
# BackendResolver, Phase 7b's ReadRespondPromptBuilder, Phase 9a's StickerCommitter.
-keep class com.aikeyboard.app.ai.sticker.TrayIconNormalizer {
    public static *** normalize(...);
    static *** encodeFitting(...);
}
-keep class com.aikeyboard.app.ai.sticker.StickerPackValidator {
    public static *** validate(...);
}
-keep class com.aikeyboard.app.ai.sticker.AddToWhatsAppHelper {
    public static *** status(...);
    public static *** buildIntent(...);
    static *** statusFromInstalled(...);
}
```

`WhatsAppStickerContentProvider` does NOT need an explicit keep rule — it's manifest-referenced, R8's default rules cover ContentProvider subclasses.

---

## §15 — Tests

Aim for ~12 new tests; all JVM-pure (no Robolectric — same precedent as 9a's `StickerCommitterMimeTest`).

### `StickerPackValidatorTest.kt` (~7 tests)

```kotlin
class StickerPackValidatorTest {
    @Test fun emptyPack_flagsTooFewStickersAndMissingTrayAndPublisher() { ... }
    @Test fun fullValidPack_passes() { ... }
    @Test fun thirtyOneStickers_flagsTooMany() { ... }
    @Test fun blankPublisher_flagged() { ... }
    @Test fun longName_flagged() { ... }
    @Test fun nonAsciiIdentifier_flagged() { ... }
    @Test fun multipleIssues_allReturned() { ... }
}
```

### `AddToWhatsAppHelperTest.kt` (4 tests)

```kotlin
class AddToWhatsAppHelperTest {
    @Test fun statusFromInstalled_neither_returnsNone() { ... }
    @Test fun statusFromInstalled_consumerOnly_returnsConsumerOnly() { ... }
    @Test fun statusFromInstalled_businessOnly_returnsBusinessOnly() { ... }
    @Test fun statusFromInstalled_both_returnsBoth() { ... }
}
```

**Do NOT include a `buildIntent_carriesAllExtras` test.** `Intent`'s constructor and `putExtra` are Android framework methods that throw `RuntimeException: Stub!` on plain JVM (the project does not enable `unitTests.isReturnDefaultValues`). The intent extra-key constants are simple strings; their correctness is verified by reading the source + the on-device smoke scenario 5 (the WhatsApp launch). Robolectric is not on the classpath and the 9a precedent mandates JVM-pure tests for new files.

### `TrayIconNormalizerTest.kt` (~3 tests)

```kotlin
class TrayIconNormalizerTest {
    @Test fun fastPath_firstAttemptUnderBudget_returnsImmediately() { ... }
    @Test fun shrinks_in8pxSteps_untilFits() { ... }
    @Test fun honorsFloorWhenNothingFits() { ... }
}
```

Drive the `encodeFitting(compress, targetPx, budget)` helper with a synthetic `compress` curve.

### `WhatsAppStickerContentProviderTest.kt` (~6 tests)

This needs Android types (`MatrixCursor`, `Uri`). MatrixCursor IS instantiable on the JVM (it's plain Java with Object[][] backing). `Uri.parse` is also pure-Java in `android.jar` stubs. **However**, those classes return Stub! exceptions in unit tests unless `unitTests.isReturnDefaultValues = true` OR Robolectric is used.

Two acceptable paths:

(a) **Extract pure helpers and test those.** The `metadataCursor(packs: List<StickerPack>)` body builds a row-array per pack — extract it as a JVM-pure `metadataRow(pack: StickerPack): Array<Any?>` and unit-test that. Same for `stickersCursor`. Skip the URI matching / SecurityException paths (they need real Android; defer to integration testing on device).

(b) **Use Robolectric just for this file.** Pulls in the Robolectric dependency for one test class; matches the Phase 9a-deferred path.

Phase 9b mandates **option (a)** — pure helpers — to stay consistent with 9a's "no Robolectric" precedent. Refactor `WhatsAppStickerContentProvider` to expose:

```kotlin
internal fun metadataRow(pack: StickerPack): Array<Any?> = arrayOf(
    pack.id, pack.name, pack.publisher, pack.trayIconFile.orEmpty(),
    "", "", "", "", "", "",
    pack.imageDataVersion.toString(),
    if (pack.avoidCache) 1 else 0,
    0,
    "", "",
)

internal fun stickerRows(pack: StickerPack): List<Array<Any?>> =
    pack.stickers.map { sticker ->
        val emojiCsv = sticker.emojis.joinToString(",")
        val a11yText = sticker.emojis.joinToString(" ").ifEmpty { pack.name }
        arrayOf(sticker.fileName, emojiCsv, a11yText)
    }
```

Then `metadataCursor` calls `metadataRow` per pack and `stickersCursor` calls `stickerRows`. Tests cover the row-shape helpers:

```kotlin
class WhatsAppStickerContentProviderTest {
    @Test fun metadataRow_emptyTrayIcon_serializesAsEmptyString() { ... }
    @Test fun metadataRow_imageDataVersion_serializesAsDecimalString() { ... }
    @Test fun metadataRow_avoidCacheTrue_serializesAsOne() { ... }
    @Test fun stickerRow_emptyEmojis_a11yTextFallsBackToPackName() { ... }
    @Test fun stickerRow_multipleEmojis_csvCommaSeparated() { ... }
    @Test fun stickerRow_a11yText_spaceSeparated() { ... }
}
```

The URI-matching path and the calling-package gate (`assertAllowedCaller`) are not tested at the JVM level. Both are exercised by the on-device smoke test (§18 scenarios 4 & 7).

**Tally target: 7 + 4 + 3 + 6 = 20 new tests + 90 prior = 110 passing AI-module unit tests.**

---

## §16 — Definition of Done

All four flavor/buildtype builds clean (`assembleFdroidDebug assemblePlayDebug assembleFdroidRelease assemblePlayRelease`). `lintFdroidDebug lintPlayDebug` clean — only the pre-existing Phase 4 `ObsoleteSdkInt` warning. `git diff app/app/lint-baseline.xml` empty.

Functional invariants (verified via the smoke tests deferred to human reviewer):

1. The pack-edit screen lets the user set a tray icon; `TrayIconNormalizer` produces a 96×96 PNG ≤50KB.
2. The pack-edit screen lets the user set a publisher; the value persists across app restarts.
3. The pack-edit screen lets the user assign 1–3 emojis per sticker; values persist.
4. The "Add to WhatsApp" button is disabled when validator returns issues; chips list every issue simultaneously.
5. Tapping "Add to WhatsApp" with a valid pack + WhatsApp installed launches WhatsApp; the pack is selectable in WhatsApp's stickers UI.
6. The on-keyboard picker shows a horizontal pack-tab strip; tapping a tab filters the grid.
7. Sticker-context error chips (carry-over #1) appear at the top of the picker on UNSUPPORTED_FIELD tap, not behind a hidden toast.
8. Post-delete navigation (carry-over #2) cleanly pops back to the Stickers list.
9. Privacy: provider rejects callers other than `com.whatsapp` and `com.whatsapp.w4b` with `SecurityException`. `imageDataVersion` increments on every content-bearing pack edit.

Dex invariants (verify via `apkanalyzer dex packages`):

| Class | fdroid release | play release |
|---|---|---|
| `com.aikeyboard.app.ai.sticker.TrayIconNormalizer` | present | present |
| `com.aikeyboard.app.ai.sticker.StickerPackValidator` | present | present |
| `com.aikeyboard.app.ai.sticker.AddToWhatsAppHelper` | present | present |
| `com.aikeyboard.app.ai.sticker.WhatsAppStickerContentProvider` | present | present |
| `com.aikeyboard.app.ai.sticker.picker.StickerPackTabsView` | present | present |

Manifest invariants:

| Element | fdroid release | play release |
|---|---|---|
| `<provider>` for `WhatsAppStickerContentProvider` (authority `*.whatsapp.stickers`, exported=true) | yes | yes |
| `<queries>` entries for `com.whatsapp` and `com.whatsapp.w4b` | yes | yes |
| New `<uses-permission>` entries | none | none |

Tests:

- 20 new JVM unit tests pass.
- 6 pre-existing Robolectric SDK-36 failures unchanged.

Carry-overs unchanged (gesture lib redistributability, `LocalLifecycleOwner` deprecation, `EncryptedSharedPreferences` deprecation in Phase 3a migration shim, lint baseline 64 inherited entries, `TermuxValidationActivity.kt:199` `ObsoleteSdkInt`).

**New carry-overs documented in PHASE_9b_SUMMARY.md:**

- Phase 8 architectural deviation (pre-existing): `alwaysOnEnabled` is persisted across reboots vs PHASE_REVIEW.md's "in-memory only" original spec; the persistent FGS notification is the privacy compensator. Document in the deviations carry-over list.
- Phase 8 boot persistence broken (pre-existing, surfaced in 9a smoke): `BootReceiver` does not result in a re-posted FGS chip after reboot. Phase 12 polish task: investigate why (BootReceiver not firing? `isAlwaysOnEnabled` returning false post-reboot due to Tink keystore unlock timing? `startForegroundService` silently failing in the post-boot window?).

---

## §17 — Smoke test scenarios (deferred to human reviewer)

Same precedent as Phase 5b/6/7a/7b/8/9a. Phase 9b is on-device behavior layered on a public ContentProvider exposed to WhatsApp + a third-party intent + new keyboard surface UI — all require a Pixel 6 Pro with WhatsApp installed.

1. **Tray icon import:** open Settings → Stickers → tap a pack → "Choose tray icon" → photo picker → pick image → returns. Verify: `adb shell run-as com.aikeyboard.app.debug ls files/stickers/packs/<pack-id>/` shows `tray.png`. Manifest's `trayIconFile` entry matches.
2. **Publisher set + persists:** type publisher name, kill app, reopen pack edit screen → publisher field shows the saved value.
3. **Emoji per sticker:** type "🎉 👋" in a sticker's emoji field, blur, kill app, reopen → emojis still set. Manifest entry: `Sticker.emojis = ["🎉", "👋"]`.
4. **Provider gating from arbitrary caller:** from `adb shell` (which has no calling-package — Android represents this as `null`):
   ```bash
   adb shell content query --uri content://com.aikeyboard.app.debug.whatsapp.stickers/metadata
   ```
   Expected: `java.lang.SecurityException: Caller null not allowed`. Verifies the assertAllowedCaller path.
5. **Add to WhatsApp (consumer):** with WhatsApp installed and a valid pack (3+ stickers, tray icon, publisher), tap "Add to WhatsApp" → WhatsApp opens with "Add to favorites?" prompt → confirm → returns to AI Keyboard Settings. Open WhatsApp → Settings → Stickers → pack visible. Send a sticker from the pack into a WhatsApp chat — works.
6. **Add to WhatsApp without WhatsApp installed:** uninstall WhatsApp, attempt → button is disabled, helper text "WhatsApp isn't installed" shows.
7. **Pack edit triggers WhatsApp re-fetch:** with pack already added, edit a sticker's emojis, return to WhatsApp → pack's stickers refreshed (verifying `imageDataVersion` bump).
8. **Pack tabs on keyboard:** in keyboard picker with 2+ packs, verify tab strip appears, tapping a tab filters grid.
9. **Error chip (carry-over #1 fix):** in dialer text field, tap a sticker → in-picker error chip appears at top of picker, NOT a hidden toast.
10. **Post-delete navigation (carry-over #2 fix):** edit a pack, tap Delete pack, confirm → screen pops cleanly to Stickers list, no black flash.
11. **Privacy logcat:** `adb logcat -s WhatsAppStickerContentProvider TrayIconNormalizer StickerPackValidator AddToWhatsAppHelper StickerPackEditScreen` after the above session — zero entries containing pack names, publisher names, sticker file names, or emoji content. Only structural strings allowed.

Document outcomes in `PHASE_9b_SUMMARY.md`. If any scenario fails, fix and re-verify.

---

## §18 — Open questions for human reviewer (carry into Phase 10 prompt)

1. **Calling-package allowlist hardening.** The provider checks `getCallingPackage() in {com.whatsapp, com.whatsapp.w4b}`. If WhatsApp ships a third package (region-specific, business v2, etc.), our provider denies access until we add it. My read: keep the allowlist tight; users on unknown WhatsApp builds report → we add their package in Phase 12. Alternative: signature pinning against WhatsApp's known certs — fragile across regional builds, not recommended.

2. **`imageDataVersion` upper bound.** `Long.MAX_VALUE` is unreachable in practice (~9 quintillion edits per pack), so no overflow concern. But WhatsApp's contract says "String" — would they parse `"9223372036854775807"` correctly? Spec doesn't say. My read: not a real concern at human edit cadences; defer.

3. **Animated stickers.** ARCHITECTURE.md explicitly defers animated WebP to v2. Phase 9b's provider hardcodes `animated_sticker_pack=0` for all packs. When Phase 11+ adds animated support, both the cursor row and `StickerNormalizer`'s decode path need updates. My read: leave as-is for v1.

4. **Per-sticker emoji parser fidelity.** The simple `split("\\s+").take(3)` parser doesn't handle ZWJ sequences correctly — "👨‍👩‍👧" has internal ZWJ joiners and would parse as one emoji-cluster which is correct, but multi-skin-tone variants like "👋🏿" should also stay as one unit and the simple split MIGHT split them depending on whether the user types a space between modifier and base. Phase 12 polish can swap in a proper grapheme-cluster parser using `BreakIterator`. Acceptable for v1.

5. **Tray icon overrides for tabs.** Pack tabs show the tray icon if set, else fall back to `ic_sticker`. Should we render the first sticker's thumbnail as the fallback instead, for visual differentiation? My read: yes, but defer to Phase 12 — `ic_sticker` is fine for v1 and avoids extra disk I/O during keyboard surface display.

6. **Multi-pack emoji-tagging UX.** Each sticker has its own TextField for emojis in the pack-edit screen. With 30 stickers per pack max, the screen has up to 30 TextFields — Compose handles this, but the screen scrolls a lot. Phase 12 could add a bulk-edit dialog (one row per sticker, all in a list). My read: defer — first user feedback should drive the UX.

7. **`StickerPackValidator.MISSING_TRAY_ICON` doesn't verify the file actually exists on disk.** It only checks `pack.trayIconFile.isNullOrEmpty()`. If the manifest references `tray.png` but the file was deleted out from under us (manual `adb` shenanigans, app-data wipe edge case), validation passes but WhatsApp's `openAssetFile` request returns null and WhatsApp shows a broken icon. Stricter validator would accept a `(File) -> Boolean` checker as a parameter (for testability) and verify file existence. My read: defer to Phase 12 polish — the v1 failure mode is recoverable (user re-picks tray icon) and not a privacy or correctness concern.

---

## §19 — Coding-style invariants (carry-overs from prior phases, reaffirmed)

- **Comments explain *why*, not *what*.** Delete narration that restates the code.
- **No `Log.d`/`Log.w` calls that include pack names, publisher names, sticker file names, emoji content, full URIs, or `t.message`.** Use `t.javaClass.simpleName` and structural strings only. (Phase 7b+8+9a precedent.)
- **`Log.w` from `StickerStorage`-like sites must wrap in `runCatching`** (Phase 9a deviation #5 — the JVM Stub! exception in unit tests). Apply to any new `Log.w` call site in 9b too.
- **`?attr/`-prefixed drawables resolve against AppCompat which we don't have**; use `?android:attr/...` (framework) or define our own. Phase 9a deviation #4 surfaced this; new layouts in 9b have already been written using framework attrs — verify before linking.
- **Picker UI uses `minHeight`** to prevent IME shrink during empty states (Phase 9a executor's polish; carry forward).
- **R8 keep rules for single-call-site singleton `object`s** are now mandatory (3-for-3 from Phase 6 / 7b / 9a). Phase 9b's three new singletons (`TrayIconNormalizer`, `StickerPackValidator`, `AddToWhatsAppHelper`) get keep rules preemptively in §14 — verify with `apkanalyzer dex packages` post-build that they survive R8.
- **All new fields on `StickerPack` are default-valued** so 9a manifest blobs decode without migration. No schema bump.
- **No new permissions.** Photo picker is permissionless; WhatsApp pack contract uses calling-package gating, not permissions; ContentProvider is exported but auth-gated.
- **Same-FQN cross-flavor rules don't apply** — Phase 9b is entirely in `src/main/`, no flavor split (no a11y dependency, no FGS dependency).

---

## Handoff

When DoD holds, write `PHASE_9b_SUMMARY.md` mirroring Phase 8's and 9a's summary structure. Sections to include:

- What was built (per-component overview)
- Manifest changes (provider + queries)
- Builds/lint
- Dex invariants verified
- Manifest invariants verified
- Test count tally
- Privacy invariants verified (logcat audit)
- Smoke test deferred (10 scenarios above)
- **Deviations from this prompt**, if any
- **Carry-overs:** include the four pre-existing ones (gesture lib, deprecations, lint baseline, ObsoleteSdkInt) PLUS Phase 8's two architectural items (alwaysOnEnabled persistence; broken boot-persistence)
- **Open questions for next phase reviewer:** any new ones surfaced + any from §18 that didn't resolve cleanly during execution
- **Touchpoints for Phase 10** (LocalLanBackend + RFC1918 networkSecurityConfig — references `app/app/src/main/res/xml/network_security_config.xml`, `app/app/src/main/java/com/aikeyboard/app/ai/client/`)

Commit on a new branch `phase/09b-sticker-engine-whatsapp`. Stop. Do not begin Phase 10.
