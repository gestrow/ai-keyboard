# Phase 9a — Sticker engine: import, normalize, on-keyboard picker, COMMIT_CONTENT

This prompt is the first half of `ARCHITECTURE.md`'s Phase 9. The sticker engine has two distinct insertion paths (Gboard-style `COMMIT_CONTENT` for Telegram/Signal/Discord, and WhatsApp's separate sticker-pack `Intent` + `ContentProvider` contract). Implementing both in one phase would push the prompt over 1500 lines and the WhatsApp contract pulls in its own `ContentProvider` subclass plus a tray-icon picker. Same precedent as the Phase 5 (5a/5b) and Phase 7 (7a/7b) splits.

**Phase 9a delivers:** import → 512×512 WebP normalization → app-private storage with `FileProvider` → on-keyboard picker → `COMMIT_CONTENT` insertion → settings-side pack management.

**Phase 9b delivers (next prompt):** WhatsApp `StickerContentProvider`, `contents.json` manifest, tray-icon picker (96×96 PNG), "Add to WhatsApp" intent, multi-pack tabs on the keyboard picker.

Stop when Phase 9a's Definition of Done holds. Do not start 9b.

---

## Read these first (do not skip)

1. `/Users/kacper/SDK/apk-dev/ai-keyboard/ARCHITECTURE.md` — esp. row 9 in the build/phase plan and the StickerEngine bullet under "Module: app/".
2. `/Users/kacper/SDK/apk-dev/ai-keyboard/PHASE_REVIEW.md` — universal checklist + Phase 9's per-phase acceptance criteria + "Keyboard-surface UI invariants" (Phase 9 is on that list).
3. `/Users/kacper/SDK/apk-dev/ai-keyboard/PHASE_8_SUMMARY.md` — the immediately preceding phase. Carries the cross-flavor build invariants (string-FQN intents, no `::class.java` from `src/main/` to a flavor-only class), the `notifySafely` precedent for lint suppression, and the `AlwaysOnProxy` / `A11yProxy` flavor-split pattern.
4. The existing `FileProvider` precedent: `app/app/src/main/java/com/aikeyboard/app/settings/screens/gesturedata/Share.kt` (the `GestureFileProvider` class at line 226, plus `getZipFileUri` at line 162). Authority is `${applicationId}.provider` — Phase 9a's authority must NOT collide.
5. `app/app/src/main/res/layout/main_keyboard_frame.xml` — the keyboard surface root layout. The picker view must slot into this hierarchy.
6. `app/app/src/main/java/com/aikeyboard/app/ai/commandrow/CommandRowController.kt` — `onStickerTap` is currently a `Log.d` stub at line 271–273. Wire it.
7. `app/app/src/main/java/com/aikeyboard/app/latin/LatinIME.java:857` (`onStartInputViewInternal`) — `editorInfo.contentMimeTypes` lives here. Phase 9 reads it from `CommandRowController` via `ime.currentInputEditorInfo`. The `CommandRowController` instantiation site is `bindCommandRow(View)` at line 762, with the actual `new CommandRowController(...)` at line 770. The sticker picker wiring goes there.
8. `app/app/src/main/java/com/aikeyboard/app/ai/ui/PersonaEditScreen.kt` — the precedent for `StickerPackEditScreen` (§11). Read it before writing the edit screen so the state-management idioms (TextField + save dialog + delete confirmation + scope.launch + IO/Main split) match the rest of the codebase.

---

## Architectural decisions (locked before code)

### 1. NO new `<uses-permission>` entries

**This is a deviation from `ARCHITECTURE.md`'s Phase 9 row** (which calls for `READ_MEDIA_IMAGES`). Justification: AndroidX's photo picker (`ActivityResultContracts.PickMultipleVisualMedia`) does not require `READ_MEDIA_IMAGES` on any supported API level — it returns content URIs through the system photo picker UI on API 33+ and falls back to the Storage Access Framework on API 29–32, both of which grant per-URI read access without a manifest permission. Adding `READ_MEDIA_IMAGES` would broaden permission surface for zero functional gain. Document the deviation in `PHASE_9a_SUMMARY.md` "Deviations" section (it's the same kind of justified deviation as Phase 8's `startActivityAndCollapse` correction).

### 2. FileProvider authority is `${applicationId}.stickers`

Must not collide with the existing gesture-data `${applicationId}.provider` authority. Each `<provider>` element needs a unique authority string per Android docs.

### 3. Storage layout is filesystem-based, not a DB

```
${context.filesDir}/stickers/
  manifest.json                     # JSON serialization of StickerManifest (all packs)
  packs/
    <pack-id>/
      sticker_<sticker-id>.webp     # 512×512, ≤100KB
```

`pack-id` is a randomly generated `UUID.randomUUID().toString()`. `sticker-id` likewise. These are filesystem keys; never user-visible. Pack `name` is user-editable.

### 4. Picker view lives in `main_keyboard_frame.xml`, swaps in/out via visibility

A new `StickerPickerView` is added as a sibling of `KeyboardWrapperView`. When shown:
- `keyboard_view_wrapper.visibility = GONE`
- `ai_sticker_picker.visibility = VISIBLE`

When dismissed (back/close/`onStartInputView` of a new field):
- `ai_sticker_picker.visibility = GONE`
- `keyboard_view_wrapper.visibility = VISIBLE`

The command row + suggestion strip + preview strip (above the keys) stay where they are. The inset-region calculation in `LatinIME.onComputeInsets` does **not** change for Phase 9a (the picker occupies the same touchable area as the keyboard view, which is already inset-correct).

### 5. Phase 9a is single-pack-friendly on the keyboard, multi-pack-aware in settings

The keyboard picker shows a flat grid of all stickers across all packs (no tabs). Pack tabs on the keyboard surface are deferred to Phase 9b (which has more reason for them since WhatsApp packs need explicit pack identity). Pack management UI in settings is full-featured: list, rename, delete, per-pack sticker grid, per-pack import.

### 6. First import auto-creates a pack

If the user taps Import on the keyboard picker (or settings) and no pack exists yet, a "My Stickers" pack is created automatically and the imports go there. No empty-state friction.

### 7. Insertion is COMMIT_CONTENT only in 9a; non-supporting apps get a toast

If the target field's `EditorInfo.contentMimeTypes` doesn't include `image/webp` or `image/*`, show a toast: "This app doesn't accept stickers. Add this pack to WhatsApp to send them there." (Pre-staging the 9b workflow in copy.) Do not silently no-op.

---

## §1 — Files to create

```
app/app/src/main/java/com/aikeyboard/app/ai/sticker/
  StickerModels.kt                        # data classes + serialization
  StickerStorage.kt                       # singleton, filesystem ops
  StickerNormalizer.kt                    # Bitmap → 512×512 WebP ≤100KB
  StickerFileProvider.kt                  # 1-line subclass of androidx FileProvider
  StickerCommitter.kt                     # InputContentInfoCompat plumbing
  picker/
    StickerPickerView.kt                  # FrameLayout, on-keyboard grid
    StickerGridAdapter.kt                 # RecyclerView.Adapter

app/app/src/main/java/com/aikeyboard/app/ai/ui/stickers/
  StickerPacksRoute.kt                    # state hoist + Storage observer
  StickerPacksScreen.kt                   # Compose: list of packs + add-pack
  StickerPackEditRoute.kt                 # state hoist for edit screen
  StickerPackEditScreen.kt                # Compose: pack details + sticker grid

app/app/src/main/res/xml/
  sticker_paths.xml                       # FileProvider <paths>

app/app/src/main/res/layout/
  sticker_picker.xml                      # picker root + RecyclerView + buttons
  sticker_picker_item.xml                 # single sticker grid cell

app/app/src/test/java/com/aikeyboard/app/ai/sticker/
  StickerSerdeTest.kt                     # manifest JSON roundtrip
  StickerStorageTest.kt                   # filesystem ops via tmp dir
  StickerNormalizerTest.kt                # quality-search pure helper
  StickerCommitterMimeTest.kt             # mime-type matching helper
```

## §2 — Files to modify

```
app/app/src/main/AndroidManifest.xml
  + <provider> for StickerFileProvider with @xml/sticker_paths

app/app/src/main/res/layout/main_keyboard_frame.xml
  + <com.aikeyboard.app.ai.sticker.picker.StickerPickerView ... visibility="gone" />
    inserted as a sibling of KeyboardWrapperView, BELOW it in the LinearLayout
    so it overlays the same vertical region. (See §8 for the exact layout.)

app/app/src/main/java/com/aikeyboard/app/latin/LatinIME.java
  + onStartInputViewInternal: dismiss any active picker (1 line)
  + onCreateInputView wiring: hand the inflated StickerPickerView to
    CommandRowController via setStickerPicker() (3 lines)

app/app/src/main/java/com/aikeyboard/app/ai/commandrow/CommandRowController.kt
  + setStickerPicker(view) wires picker → controller
  + onStickerTap(): show picker; picker callbacks: onImport, onPick, onDismiss
  + onInputStarted(editorInfo): cache + dismiss picker on field change

app/app/src/main/java/com/aikeyboard/app/ai/commandrow/CommandRowView.kt
  (no change — sticker icon was wired in Phase 2)

app/app/src/main/java/com/aikeyboard/app/ai/ui/AiSettingsNavHost.kt
  + AiSettingsRoutes.STICKERS_LIST = "stickers/list"
  + AiSettingsRoutes.STICKERS_EDIT = "stickers/edit/{packId}"
  + ARG_STICKER_PACK_ID = "packId"
  + composable(STICKERS_LIST) { StickerPacksRoute(...) }
  + composable(STICKERS_EDIT/{packId}) { StickerPackEditRoute(...) }

app/app/src/main/java/com/aikeyboard/app/ai/ui/SettingsHubScreen.kt
  + onOpenStickers parameter, fifth HubRow

app/app/src/main/res/values/ai_strings.xml
  + ai_settings_hub_stickers_title / _desc
  + ai_stickers_* strings (see §14)

app/app/build.gradle.kts
  + implementation("androidx.activity:activity-compose")
    The BOM-managed version (currently transitive via navigation-compose).
    PickMultipleVisualMedia + rememberLauncherForActivityResult come from
    activity-compose; making it an explicit direct dependency avoids
    silent breakage if Google ever bumps navigation-compose to a version
    that drops the activity-compose transitive.

(no changes to proguard-rules.pro — kotlinx.serialization global rule
 already covers the new $$serializer classes; no new R8-inlining hazard
 surfaces in 9a since all sticker classes have ≥2 call sites.)
```

---

## §3 — `StickerModels.kt`

Pure data classes with `@Serializable`. The on-disk schema is `StickerManifest`; everything below it is reachable from there.

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker

import kotlinx.serialization.Serializable

@Serializable
data class StickerManifest(
    val schemaVersion: Int = 1,
    val packs: List<StickerPack> = emptyList(),
)

@Serializable
data class StickerPack(
    val id: String,
    val name: String,
    val createdAt: Long,
    val stickers: List<Sticker> = emptyList(),
)

@Serializable
data class Sticker(
    val id: String,
    /**
     * Filename relative to the pack directory. Always *.webp; the file
     * itself is the canonicalized 512×512 normalized output.
     */
    val fileName: String,
    /**
     * Optional emoji tags. Phase 9a does not surface a UI to edit these
     * (it's a Phase 9b deliverable for WhatsApp's contents.json), so
     * imports default to empty. Don't rely on this list being non-empty.
     */
    val emojis: List<String> = emptyList(),
)
```

Schema version starts at 1. If 9b adds a `trayIconFile` field to `StickerPack`, that goes in as a default-null/empty field for back-compat (same precedent as Phase 6's `selectedBackendStrategy` field).

---

## §4 — `StickerStorage.kt`

Singleton, mirrors `SecureStorage`'s factory pattern. Holds an in-memory `StickerManifest` cached after first load; writes are atomic (write to temp + rename).

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker

import android.content.Context
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class StickerStorage @androidx.annotation.VisibleForTesting internal constructor(
    private val rootDir: File,
) {

    @Volatile private var cached: StickerManifest? = null

    fun getManifest(): StickerManifest = cached ?: synchronized(this) {
        cached ?: load().also { cached = it }
    }

    /**
     * Observable change counter. Compose screens collect this as state so any
     * mutation (import, rename, delete) from any route reaches every screen
     * without the routes hand-rolling refresh ticks. StateFlow is used over
     * a plain Long so produceState/collectAsState observation is idiomatic
     * and the cross-screen staleness gap is closed.
     */
    val changes: kotlinx.coroutines.flow.StateFlow<Long> get() = _changes
    private val _changes = kotlinx.coroutines.flow.MutableStateFlow(0L)

    fun renamePack(packId: String, newName: String) {
        update { manifest ->
            manifest.copy(packs = manifest.packs.map {
                if (it.id == packId) it.copy(name = newName) else it
            })
        }
    }

    fun createPack(name: String): StickerPack {
        val pack = StickerPack(
            id = UUID.randomUUID().toString(),
            name = name,
            createdAt = System.currentTimeMillis(),
        )
        File(rootDir, "packs/${pack.id}").mkdirs()
        update { it.copy(packs = it.packs + pack) }
        return pack
    }

    fun deletePack(packId: String) {
        File(rootDir, "packs/$packId").deleteRecursively()
        update { it.copy(packs = it.packs.filterNot { p -> p.id == packId }) }
    }

    fun deleteSticker(packId: String, stickerId: String) {
        val manifest = getManifest()
        val pack = manifest.packs.firstOrNull { it.id == packId } ?: return
        val sticker = pack.stickers.firstOrNull { it.id == stickerId } ?: return
        File(rootDir, "packs/$packId/${sticker.fileName}").delete()
        update {
            it.copy(packs = it.packs.map { p ->
                if (p.id != packId) p
                else p.copy(stickers = p.stickers.filterNot { s -> s.id == stickerId })
            })
        }
    }

    fun addSticker(packId: String, stickerId: String, fileName: String) {
        update {
            it.copy(packs = it.packs.map { p ->
                if (p.id != packId) p
                else p.copy(stickers = p.stickers + Sticker(stickerId, fileName))
            })
        }
    }

    /** Where StickerNormalizer should write a new sticker file. Caller is responsible
     *  for creating the parent directory (createPack does this; for orphan-recovery,
     *  re-call mkdirs()). */
    fun stickerFile(packId: String, fileName: String): File =
        File(rootDir, "packs/$packId/$fileName")

    /** First-import helper. Returns the default pack, creating it if missing. */
    fun ensureDefaultPack(defaultName: String): StickerPack {
        val existing = getManifest().packs.firstOrNull()
        if (existing != null) return existing
        return createPack(defaultName)
    }

    private fun update(transform: (StickerManifest) -> StickerManifest) {
        synchronized(this) {
            val current = getManifest()
            val next = transform(current)
            persist(next)
            cached = next
            _changes.value += 1
        }
    }

    private fun load(): StickerManifest {
        val file = File(rootDir, MANIFEST_FILE)
        if (!file.exists()) return StickerManifest()
        return runCatching { JSON.decodeFromString<StickerManifest>(file.readText()) }
            .getOrElse {
                // Privacy: log type only, never the file's contents.
                Log.w(TAG, "Manifest decode failed: ${it.javaClass.simpleName}; resetting")
                StickerManifest()
            }
    }

    private fun persist(manifest: StickerManifest) {
        rootDir.mkdirs()
        val tmp = File(rootDir, "$MANIFEST_FILE.tmp")
        tmp.writeText(JSON.encodeToString(manifest))
        val target = File(rootDir, MANIFEST_FILE)
        if (!tmp.renameTo(target)) {
            // renameTo can fail across filesystem boundaries; copy fallback.
            target.writeText(tmp.readText())
            tmp.delete()
        }
    }

    companion object {
        private const val TAG = "StickerStorage"
        private const val MANIFEST_FILE = "manifest.json"
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        @Volatile private var instance: StickerStorage? = null

        fun getInstance(context: Context): StickerStorage =
            instance ?: synchronized(this) {
                instance ?: StickerStorage(File(context.filesDir, "stickers")).also {
                    instance = it
                }
            }
    }
}
```

`changes` is a `StateFlow<Long>` that monotonically increments on every mutation (`createPack` / `renamePack` / `deletePack` / `addSticker` / `deleteSticker`). Compose screens collect it via `collectAsState()` and re-derive their data on every emission, so the list screen and the edit screen stay consistent regardless of which one mutated.

`@VisibleForTesting internal constructor(rootDir: File)` lets `StickerStorageTest` instantiate against a `Files.createTempDirectory(...)` root, bypassing the singleton — no shared state between test methods. `androidx.annotation.VisibleForTesting` is already on the runtime classpath via `androidx.core`.

---

## §5 — `StickerNormalizer.kt`

Two responsibilities:

1. **Decode + scale + crop**: load the source URI, downscale via `BitmapFactory.Options.inSampleSize`, then center-crop to 512×512. Must use `ImageDecoder` on API 28+ (HEIC support, EXIF rotation handled automatically) — minSdk 29 makes this unconditional.
2. **Compress to ≤100KB WebP**: try quality 90 first; if larger than 100KB, binary-search down. The quality-search loop is extracted as a pure helper for unit testing.

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File

object StickerNormalizer {

    private const val TAG = "StickerNormalizer"
    private const val TARGET_PX = 512
    private const val TARGET_BYTES = 100 * 1024
    private const val MIN_QUALITY = 30
    private const val MAX_QUALITY = 95

    /**
     * Decode [src], normalize to 512×512 center-cropped WebP under 100KB,
     * write to [dst]. Returns true on success. Does NOT throw — failures
     * are logged structurally (no URI strings, no byte counts) and return false.
     */
    fun normalize(resolver: ContentResolver, src: Uri, dst: File): Boolean {
        return try {
            val source = ImageDecoder.createSource(resolver, src)
            val raw = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
            val normalized = squareCropAndScale(raw, TARGET_PX)
            raw.recycle()
            val bytes = compressToTargetBytes(normalized, TARGET_BYTES, MIN_QUALITY, MAX_QUALITY)
            normalized.recycle()
            dst.parentFile?.mkdirs()
            dst.writeBytes(bytes)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Normalize failed: ${t.javaClass.simpleName}")
            false
        }
    }

    private fun squareCropAndScale(src: Bitmap, target: Int): Bitmap {
        val side = minOf(src.width, src.height)
        val xOff = (src.width - side) / 2
        val yOff = (src.height - side) / 2
        val cropped = Bitmap.createBitmap(src, xOff, yOff, side, side)
        if (side == target) return cropped
        val scaled = cropped.scale(target, target)
        if (scaled !== cropped) cropped.recycle()
        return scaled
    }

    /**
     * Pure helper: extracted for unit testing. Walks compress quality with a
     * binary search to find the largest quality whose output fits the byte
     * budget. Returns the bytes; caller writes them.
     */
    internal fun compressToTargetBytes(
        bitmap: Bitmap,
        budget: Int,
        minQuality: Int,
        maxQuality: Int,
    ): ByteArray {
        val measure = { quality: Int ->
            val baos = ByteArrayOutputStream()
            @Suppress("DEPRECATION")
            // CompressFormat.WEBP_LOSSY is API 30+; we still target 29 for the lower bound.
            // The deprecated WEBP enum is the backwards-compatible spelling that resolves
            // to lossy on every supported API level.
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            bitmap.compress(format, quality, baos)
            baos.toByteArray()
        }
        // Fast path: max-quality output already fits.
        val maxBytes = measure(maxQuality)
        if (maxBytes.size <= budget) return maxBytes
        // Binary search for the largest q whose output ≤ budget.
        var lo = minQuality
        var hi = maxQuality
        var best: ByteArray = measure(minQuality)
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val candidate = measure(mid)
            if (candidate.size <= budget) {
                best = candidate
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return best
    }
}

private fun Bitmap.scale(w: Int, h: Int): Bitmap = Bitmap.createScaledBitmap(this, w, h, true)
```

The `compressToTargetBytes` helper takes a `(quality) -> ByteArray` measure as a closure on a real `Bitmap`. The unit test (§16) substitutes a fake bitmap that returns deterministic synthetic byte arrays per quality — the helper is testable without Robolectric.

Actually re-read: the helper above closes over `bitmap` directly. For the unit test path, refactor the helper to take the measure function as a parameter:

```kotlin
internal fun compressToTargetBytes(
    measure: (Int) -> ByteArray,
    budget: Int,
    minQuality: Int,
    maxQuality: Int,
): ByteArray { /* same body, calls measure(q) instead of compressing */ }

// Real call site wraps it:
private fun compressToTargetBytes(b: Bitmap, budget: Int, min: Int, max: Int): ByteArray =
    compressToTargetBytes(measure = { q -> /* compress */ }, budget, min, max)
```

This is cleaner and the test can drive the search with a synthetic byte-size curve.

---

## §6 — `StickerFileProvider.kt` + `sticker_paths.xml`

Empty subclass purely so the manifest declares a class in **our** package (and so future logging/observability hooks have a home, like `GestureFileProvider` already does).

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker

import androidx.core.content.FileProvider

class StickerFileProvider : FileProvider()
```

```xml
<!-- app/app/src/main/res/xml/sticker_paths.xml -->
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <files-path
        name="stickers"
        path="stickers/" />
</paths>
```

`<files-path>` resolves to `Context.filesDir`, so the path matches `StickerStorage`'s root directory (`filesDir/stickers/...`).

---

## §7 — `StickerCommitter.kt`

Wraps `InputConnectionCompat.commitContent` plumbing. Single method:

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker

import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import android.os.Build
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import java.io.File

object StickerCommitter {

    private const val MIME_WEBP = "image/webp"
    private val ACCEPTED_MIME_PREFIXES = listOf(MIME_WEBP, "image/*")

    enum class Result { OK, NO_CONNECTION, UNSUPPORTED_FIELD, FAILED }

    /**
     * @return Result.OK if the target field accepted the sticker; UNSUPPORTED_FIELD
     *         if the field's contentMimeTypes declared no compatible type
     *         (commitContent never called); NO_CONNECTION if the IME has no
     *         current input connection; FAILED if commitContent returned false.
     */
    fun insert(
        context: Context,
        ic: InputConnection?,
        editorInfo: EditorInfo?,
        stickerFile: File,
        authority: String,
    ): Result {
        if (ic == null || editorInfo == null) return Result.NO_CONNECTION
        if (!fieldAcceptsWebp(editorInfo)) return Result.UNSUPPORTED_FIELD
        val uri: Uri = FileProvider.getUriForFile(context, authority, stickerFile)
        val info = InputContentInfoCompat(
            uri,
            ClipDescription(stickerFile.name, arrayOf(MIME_WEBP)),
            null, // linkUri — not applicable for a local sticker
        )
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1)
            InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION else 0
        val ok = InputConnectionCompat.commitContent(ic, editorInfo, info, flags, null)
        return if (ok) Result.OK else Result.FAILED
    }

    /** Pure helper: extracted for unit testing. */
    internal fun fieldAcceptsWebp(editorInfo: EditorInfo): Boolean {
        val mimes = editorInfo.contentMimeTypes ?: return false
        return mimes.any { declared ->
            ACCEPTED_MIME_PREFIXES.any { accept ->
                ClipDescription.compareMimeTypes(declared, accept)
            }
        }
    }
}
```

`ClipDescription.compareMimeTypes` is the official wildcard-aware matcher (handles `image/*` vs `image/webp` correctly). Don't substring-match.

---

## §8 — `StickerPickerView.kt` + layouts

The picker is a `FrameLayout` with three states: empty (no stickers anywhere), grid (≥1 sticker), and a top toolbar (back arrow + import button) that's always visible.

### `sticker_picker.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<merge xmlns:android="http://schemas.android.com/apk/res/android">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="48dp"
            android:orientation="horizontal"
            android:gravity="center_vertical"
            android:paddingHorizontal="8dp">

            <ImageButton
                android:id="@+id/sticker_picker_back"
                android:layout_width="40dp"
                android:layout_height="40dp"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:src="@drawable/ic_arrow_back"
                android:contentDescription="@string/ai_stickers_picker_back_desc" />

            <View
                android:layout_width="0dp"
                android:layout_height="0dp"
                android:layout_weight="1" />

            <ImageButton
                android:id="@+id/sticker_picker_import"
                android:layout_width="40dp"
                android:layout_height="40dp"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:src="@drawable/ic_ai_settings_gear"
                android:contentDescription="@string/ai_stickers_picker_import_desc" />
        </LinearLayout>

        <FrameLayout
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1">

            <androidx.recyclerview.widget.RecyclerView
                android:id="@+id/sticker_picker_grid"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:padding="8dp"
                android:clipToPadding="false" />

            <TextView
                android:id="@+id/sticker_picker_empty"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:gravity="center"
                android:padding="16dp"
                android:textSize="14sp"
                android:text="@string/ai_stickers_picker_empty"
                android:visibility="gone" />
        </FrameLayout>

    </LinearLayout>
</merge>
```

The toolbar uses `ic_arrow_back` (already in `res/drawable/`) and reuses `ic_ai_settings_gear` as a placeholder for the import action — ideally an "add" plus icon, but Phase 9a explicitly avoids drawable churn. The `ai_stickers_picker_import_desc` content-description carries the actual semantic ("Import stickers"). Phase 12 polish can swap in a dedicated `ic_ai_add` if the gear UX-tests poorly.

### `sticker_picker_item.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<ImageView
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/sticker_picker_item_image"
    android:layout_width="80dp"
    android:layout_height="80dp"
    android:layout_margin="4dp"
    android:scaleType="fitCenter"
    android:adjustViewBounds="false"
    android:contentDescription="@string/ai_stickers_picker_item_desc" />
```

### `StickerPickerView.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker.picker

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aikeyboard.app.ai.sticker.Sticker
import com.aikeyboard.app.ai.sticker.StickerPack
import com.aikeyboard.app.latin.R
import com.aikeyboard.app.latin.common.ColorType
import com.aikeyboard.app.latin.settings.Settings

class StickerPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    interface Listener {
        /** User tapped a sticker; controller commits via StickerCommitter. */
        fun onStickerSelected(packId: String, stickerId: String)
        /** User tapped the import action. */
        fun onImportRequested()
        /** User tapped the back arrow. */
        fun onDismissRequested()
    }

    var listener: Listener? = null

    private val grid: RecyclerView
    private val emptyView: TextView
    private val backBtn: ImageButton
    private val importBtn: ImageButton
    private val adapter: StickerGridAdapter

    init {
        LayoutInflater.from(context).inflate(R.layout.sticker_picker, this, true)
        grid = findViewById(R.id.sticker_picker_grid)
        emptyView = findViewById(R.id.sticker_picker_empty)
        backBtn = findViewById(R.id.sticker_picker_back)
        importBtn = findViewById(R.id.sticker_picker_import)

        adapter = StickerGridAdapter(
            // No-op resolver until bind() is called. Adapter starts with an empty
            // data list, so RecyclerView never invokes the binder against this
            // resolver. The File("") would route through onBindViewHolder's
            // file.exists() guard if it ever WAS hit (e.g. cached views from a
            // theme-change recreation), failing safely with a blank thumbnail
            // rather than crashing.
            packDirResolver = { _ -> java.io.File("") },
            onTap = { packId, stickerId -> listener?.onStickerSelected(packId, stickerId) },
        )
        grid.layoutManager = GridLayoutManager(context, COLUMN_COUNT)
        grid.adapter = adapter

        backBtn.setOnClickListener { listener?.onDismissRequested() }
        importBtn.setOnClickListener { listener?.onImportRequested() }

        applyKeyboardTheme()
    }

    fun bind(packs: List<StickerPack>, packDirResolver: (String) -> java.io.File) {
        val flat = packs.flatMap { p -> p.stickers.map { s -> p.id to s } }
        adapter.update(flat, packDirResolver)
        emptyView.visibility = if (flat.isEmpty()) View.VISIBLE else View.GONE
        grid.visibility = if (flat.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Phase 2.5 keyboard-surface invariant: pull all colors from HeliBoard's
     *  Colors so the picker matches the user's selected keyboard theme. */
    private fun applyKeyboardTheme() {
        val colors = Settings.getValues().mColors
        colors.setBackground(this, ColorType.STRIP_BACKGROUND)
        colors.setColor(backBtn, ColorType.TOOL_BAR_KEY)
        colors.setColor(importBtn, ColorType.TOOL_BAR_KEY)
        emptyView.setTextColor(colors.get(ColorType.KEY_TEXT))
    }

    companion object {
        private const val COLUMN_COUNT = 4
    }
}
```

### `StickerGridAdapter.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.sticker.picker

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.aikeyboard.app.ai.sticker.Sticker
import com.aikeyboard.app.latin.R
import java.io.File

class StickerGridAdapter(
    private var packDirResolver: (String) -> File,
    private val onTap: (packId: String, stickerId: String) -> Unit,
) : RecyclerView.Adapter<StickerGridAdapter.VH>() {

    private var data: List<Pair<String, Sticker>> = emptyList()

    fun update(items: List<Pair<String, Sticker>>, resolver: (String) -> File) {
        packDirResolver = resolver
        data = items
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.sticker_picker_item, parent, false) as ImageView
        return VH(view)
    }

    override fun getItemCount(): Int = data.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (packId, sticker) = data[position]
        val file = File(packDirResolver(packId), sticker.fileName)
        // Decoding on the main thread (RecyclerView binds on whichever thread
        // notifyDataSetChanged was called from — for our use that's main).
        // ≤100KB WebPs decode fast on Pixel 6 Pro (sub-5ms each); a 30-sticker
        // grid is fine. The picker open gesture is a single user-initiated
        // event, not a typing-loop bottleneck. If a future user accumulates
        // 100+ stickers and reports hitches, Phase 12 swaps in a small LRU
        // bitmap cache (or Coil/Glide); see §19 open question 2.
        val bitmap = if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
        holder.image.setImageBitmap(bitmap)
        holder.image.setOnClickListener { onTap(packId, sticker.id) }
    }

    class VH(val image: ImageView) : RecyclerView.ViewHolder(image)
}
```

`notifyDataSetChanged` is OK for v1 — the grid is small. If Phase 12 wants smoother updates, swap in DiffUtil.

---

## §9 — `CommandRowController.kt` modifications

Three changes. Full diff:

```kotlin
// Add imports
import android.view.inputmethod.EditorInfo
import com.aikeyboard.app.ai.sticker.StickerCommitter
import com.aikeyboard.app.ai.sticker.StickerPack
import com.aikeyboard.app.ai.sticker.StickerStorage
import com.aikeyboard.app.ai.sticker.picker.StickerPickerView

// Add fields
private var stickerPicker: StickerPickerView? = null
// Cached sibling reference. Set in setStickerPicker so hidePicker / onStickerTap
// don't have to walk decorView every flip. Both views live in the same
// LinearLayout in main_keyboard_frame.xml, so picker.parent IS that LinearLayout.
private var keyboardWrapper: View? = null
private val stickerStorage: StickerStorage = StickerStorage.getInstance(ime)

// Add wiring method (called from LatinIME.bindCommandRow at line 770)
fun setStickerPicker(view: StickerPickerView) {
    stickerPicker = view
    keyboardWrapper = (view.parent as? android.view.ViewGroup)
        ?.findViewById<View>(R.id.keyboard_view_wrapper)
    view.listener = object : StickerPickerView.Listener {
        override fun onStickerSelected(packId: String, stickerId: String) {
            commitSticker(packId, stickerId)
        }
        override fun onImportRequested() {
            launchSettingsAtStickers()
        }
        override fun onDismissRequested() {
            hidePicker()
        }
    }
    refreshPickerData()
}

// Replace the stub onStickerTap (line 271)
override fun onStickerTap() {
    if (stickerPicker == null) return
    refreshPickerData()
    showPicker()
}

// New: dismiss-on-input-change hook called from LatinIME.onStartInputViewInternal
fun onInputStarted(editorInfo: EditorInfo?) {
    cachedEditorInfo = editorInfo
    if (stickerPicker?.visibility == View.VISIBLE) hidePicker()
}

private var cachedEditorInfo: EditorInfo? = null

private fun refreshPickerData() {
    val picker = stickerPicker ?: return
    val packs = stickerStorage.getManifest().packs
    picker.bind(packs) { packId ->
        java.io.File(ime.filesDir, "stickers/packs/$packId")
    }
}

private fun hidePicker() {
    stickerPicker?.visibility = View.GONE
    keyboardWrapper?.visibility = View.VISIBLE
}

private fun showPicker() {
    stickerPicker?.visibility = View.VISIBLE
    keyboardWrapper?.visibility = View.GONE
}

private fun commitSticker(packId: String, stickerId: String) {
    val manifest = stickerStorage.getManifest()
    val pack = manifest.packs.firstOrNull { it.id == packId } ?: return
    val sticker = pack.stickers.firstOrNull { it.id == stickerId } ?: return
    val file = stickerStorage.stickerFile(packId, sticker.fileName)
    if (!file.exists()) {
        toast(R.string.ai_stickers_commit_missing_file)
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
        StickerCommitter.Result.NO_CONNECTION -> toast(R.string.ai_stickers_commit_no_connection)
        StickerCommitter.Result.UNSUPPORTED_FIELD -> toast(R.string.ai_stickers_commit_unsupported)
        StickerCommitter.Result.FAILED -> toast(R.string.ai_stickers_commit_failed)
    }
}

private fun launchSettingsAtStickers() {
    // Hide BEFORE startActivity. The IME is about to deactivate as the
    // settings task comes to foreground; if the picker is still VISIBLE
    // for those few frames the user sees a flash. Tear down first.
    hidePicker()
    val intent = Intent(ime, AiSettingsActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(
            AiSettingsActivity.EXTRA_DEEP_LINK_ROUTE,
            AiSettingsRoutes.STICKERS_LIST,
        )
    ime.startActivity(intent)
}
```

**Why launch Settings to import** instead of opening the system photo picker directly from the IME: `ActivityResultContracts.PickMultipleVisualMedia` requires an `ActivityResultRegistry`, which `InputMethodService` doesn't have. The cleanest path is to deep-link into the Settings UI's Stickers screen, where Compose's `rememberLauncherForActivityResult` works idiomatically. The Phase 8 `EXTRA_DEEP_LINK_ROUTE` plumbing already exists — reuse it.

---

## §10 — `LatinIME.java` hooks

Three small additions, in Java because LatinIME is Java.

### a) Hand the picker view to the controller in `bindCommandRow`

`bindCommandRow(View view)` lives at `LatinIME.java:762`. Line 770 currently reads:

```java
mCommandRowController = new CommandRowController(this, row, preview, SecureStorage.Companion.getInstance(this));
```

Immediately after that line, add:

```java
final StickerPickerView stickerPicker = view.findViewById(R.id.ai_sticker_picker);
if (stickerPicker != null) {
    mCommandRowController.setStickerPicker(stickerPicker);
}
```

Use the existing `view` parameter of `bindCommandRow`, not `mInputView`. The controller is non-null on this branch (just constructed). The picker view is non-null in normal builds (it's declared in `main_keyboard_frame.xml`); the null-guard exists for landscape / lazy-inflation edge cases that already gate `row` and `preview`.

### b) Dismiss the picker when input starts

In `onStartInputViewInternal` (line 857), add the call **immediately after `super.onStartInputView(...)` at line 858, BEFORE the null-`editorInfo` early return at line 881**. This ensures the picker is dismissed on every input transition, including transitions to a null `editorInfo` (no field active):

```java
super.onStartInputView(editorInfo, restarting);

if (mCommandRowController != null) {
    mCommandRowController.onInputStarted(editorInfo);
}

// existing gesture-data + facilitator setup follows ...
```

Caches the new `EditorInfo` in the controller so `commitSticker` has a reliable handle even if `getCurrentInputEditorInfo()` momentarily returns null mid-transition.

### c) (No layout-inflation change in Java)

The picker view is added declaratively in `main_keyboard_frame.xml` (§2), so `LayoutInflater` will already attach it during `setInputView`. No Java change needed beyond (a) and (b).

### Also in `main_keyboard_frame.xml`

Add the picker as a sibling of `KeyboardWrapperView` (existing line 33–78). The picker should occupy the same vertical region as the keys, so make it match `KeyboardWrapperView`'s sizing:

```xml
<com.aikeyboard.app.ai.sticker.picker.StickerPickerView
    android:id="@+id/ai_sticker_picker"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:visibility="gone" />
```

Place it *after* `KeyboardWrapperView` in the LinearLayout so it stacks below in source order. When visibility flips in (§9), `KeyboardWrapperView` goes GONE and the picker becomes visible in the same vertical slot. (`LinearLayout` collapses `GONE` children, so the picker rises into the keyboard area.)

---

## §11 — Settings UI: `StickerPacksScreen` + `StickerPackEditScreen`

Follow the exact pattern of `BackendsScreen.kt` (precedent at `app/app/src/main/java/com/aikeyboard/app/ai/ui/BackendsScreen.kt:43`, route at `AiSettingsNavHost.kt:75-83`).

### `StickerPacksRoute.kt`

State hoist + rememberLauncherForActivityResult for the photo picker. The route observes `StickerStorage.changeCounter` to refresh after import/delete.

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.ui.stickers

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.aikeyboard.app.ai.sticker.StickerNormalizer
import com.aikeyboard.app.ai.sticker.StickerPack
import com.aikeyboard.app.ai.sticker.StickerStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import java.util.UUID

@Composable
fun StickerPacksRoute(
    onBack: () -> Unit,
    onOpenPack: (packId: String) -> Unit,
) {
    val context = LocalContext.current
    val storage = remember { StickerStorage.getInstance(context) }
    // Cross-screen consistency: every storage mutation increments
    // StickerStorage.changes; both the list and the edit screen subscribe.
    // Re-derive the pack list whenever the counter advances.
    val tick by storage.changes.collectAsState()
    val packs by produceState(initialValue = emptyList<StickerPack>(), tick) {
        value = storage.getManifest().packs
    }
    val scope = rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_IMPORTS_PER_BATCH)
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val pack = storage.ensureDefaultPack(
                context.getString(com.aikeyboard.app.latin.R.string.ai_stickers_default_pack_name)
            )
            uris.forEach { uri ->
                val stickerId = UUID.randomUUID().toString()
                val fileName = "sticker_$stickerId.webp"
                val file = storage.stickerFile(pack.id, fileName)
                val ok = StickerNormalizer.normalize(context.contentResolver, uri, file)
                if (ok) storage.addSticker(pack.id, stickerId, fileName)
                // No explicit recompose trigger needed — addSticker bumps
                // StickerStorage.changes, which the StateFlow-backed tick
                // collector picks up on the main thread automatically.
            }
        }
    }

    StickerPacksScreen(
        packs = packs,
        onBack = onBack,
        onOpenPack = onOpenPack,
        onImport = {
            importLauncher.launch(
                PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly
                )
            )
        },
    )
}

private const val MAX_IMPORTS_PER_BATCH = 30
```

`MAX_IMPORTS_PER_BATCH` caps the photo picker; the system enforces an upper bound (~100 on most OEMs) but 30 is a sensible UX default and avoids long blocking import loops.

### `StickerPacksScreen.kt`

Compose screen mirrors `BackendsScreen` structure: TopAppBar with back arrow, lazy column of pack rows, FAB or button for "Import stickers". Each pack row shows the pack name + sticker count. Tap → onOpenPack.

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerPacksScreen(
    packs: List<StickerPack>,
    onBack: () -> Unit,
    onOpenPack: (String) -> Unit,
    onImport: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_settings_hub_stickers_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), null)
                    }
                },
                actions = {
                    IconButton(onClick = onImport) {
                        Icon(painterResource(R.drawable.ic_ai_settings_gear), null)
                    }
                }
            )
        }
    ) { padding ->
        if (packs.isEmpty()) {
            // Empty state
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.ai_stickers_packs_empty_title))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.ai_stickers_packs_empty_body),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onImport) {
                        Text(stringResource(R.string.ai_stickers_packs_import_action))
                    }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(packs, key = { it.id }) { pack ->
                    ListItem(
                        modifier = Modifier.clickable { onOpenPack(pack.id) },
                        headlineContent = { Text(pack.name) },
                        supportingContent = {
                            Text(pluralStickerCount(pack.stickers.size))
                        },
                        trailingContent = {
                            Icon(painterResource(R.drawable.ic_chevron_right), null)
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
```

`pluralStickerCount` is a small helper that picks "1 sticker" / "%d stickers" — `tools:ignore="PluralsCandidate"` is already set in `ai_strings.xml`, so a simple `if (count == 1) ... else ...` is fine for v1.

### `StickerPackEditRoute.kt` + `StickerPackEditScreen.kt`

Same shape as `PersonaEditScreen`. Inputs: pack name (TextField), sticker grid (LazyVerticalGrid of thumbnails), per-sticker delete button (long-press or trailing icon), pack-level delete button, "Import more" action.

The screen reads sticker bitmaps from disk on the IO dispatcher and shows them in a LazyVerticalGrid (4 columns). Tapping a sticker does nothing (insertion is keyboard-only); long-pressing surfaces a delete confirmation dialog.

Same `storage.changes.collectAsState()` + `produceState(...)` observability pattern as the list screen — every mutation from this screen (rename, delete sticker, delete pack) goes through `StickerStorage`, bumps the StateFlow, and propagates back to the list screen automatically.

(Full code omitted from this prompt for size; the executor should mirror `PersonaEditScreen` patterns precisely. **Do not invent new state-management idioms**.)

---

## §12 — `AiSettingsNavHost.kt` + `SettingsHubScreen.kt`

### NavHost edits

```kotlin
// In AiSettingsRoutes
const val STICKERS_LIST = "stickers/list"
const val STICKERS_EDIT = "stickers/edit"
const val ARG_STICKER_PACK_ID = "packId"

fun editStickerPackRoute(packId: String): String =
    "$STICKERS_EDIT/$packId"

// In AiSettingsNavHost composable list, add:
composable(AiSettingsRoutes.STICKERS_LIST) {
    StickerPacksRoute(
        onBack = { nav.popBackStack() },
        onOpenPack = { id -> nav.navigate(AiSettingsRoutes.editStickerPackRoute(id)) },
    )
}
composable(
    route = "${AiSettingsRoutes.STICKERS_EDIT}/{${AiSettingsRoutes.ARG_STICKER_PACK_ID}}",
    arguments = listOf(
        navArgument(AiSettingsRoutes.ARG_STICKER_PACK_ID) { type = NavType.StringType }
    ),
) { backStackEntry ->
    val id = backStackEntry.arguments?.getString(AiSettingsRoutes.ARG_STICKER_PACK_ID) ?: return@composable
    StickerPackEditRoute(
        packId = id,
        onBack = { nav.popBackStack() },
    )
}
```

Update the `SettingsHubScreen` invocation in `AiSettingsNavHost.kt` to pass `onOpenStickers`:

```kotlin
composable(AiSettingsRoutes.HUB) {
    SettingsHubScreen(
        onOpenPersonas = { nav.navigate(AiSettingsRoutes.PERSONAS_LIST) },
        onOpenKeyboardChrome = { nav.navigate(AiSettingsRoutes.KEYBOARD_CHROME) },
        onOpenBackends = { nav.navigate(AiSettingsRoutes.BACKENDS_LIST) },
        onOpenAlwaysOn = { nav.navigate(AiSettingsRoutes.ALWAYS_ON) },
        onOpenStickers = { nav.navigate(AiSettingsRoutes.STICKERS_LIST) },
    )
}
```

### `SettingsHubScreen.kt` edits

Add `onOpenStickers: () -> Unit` parameter and a fifth HubRow:

```kotlin
HubRow(
    titleRes = R.string.ai_settings_hub_stickers_title,
    descriptionRes = R.string.ai_settings_hub_stickers_desc,
    iconRes = R.drawable.ic_sticker, // already exists
    onClick = onOpenStickers,
)
HorizontalDivider()
```

---

## §13 — `AndroidManifest.xml` (src/main/) — add `<provider>`

Inside `<application>`, alongside the existing `GestureFileProvider`:

```xml
<provider
    android:name="com.aikeyboard.app.ai.sticker.StickerFileProvider"
    android:authorities="${applicationId}.stickers"
    android:enabled="true"
    android:grantUriPermissions="true"
    android:exported="false">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/sticker_paths"/>
</provider>
```

`exported="false"` + `grantUriPermissions="true"` is the standard COMMIT_CONTENT pattern (the IME explicitly grants per-URI read access to the receiving app via the `INPUT_CONTENT_GRANT_READ_URI_PERMISSION` flag in §7). Receiving apps don't need export.

**No new `<uses-permission>` entries.** (Photo picker is a privileged system UI on API 33+ and falls back to SAF on 29–32 — both grant per-URI read access without a manifest permission.)

---

## §14 — Strings (`ai_strings.xml`)

```xml
<!-- Phase 9a: sticker engine + COMMIT_CONTENT path. -->
<string name="ai_settings_hub_stickers_title">Stickers</string>
<string name="ai_settings_hub_stickers_desc">Import and manage sticker packs</string>

<string name="ai_stickers_default_pack_name">My Stickers</string>

<string name="ai_stickers_picker_back_desc">Close stickers</string>
<!-- The import button on the keyboard picker opens AI Settings →
     Stickers (because ActivityResultRegistry isn't available on
     InputMethodService). The content-description reflects the actual
     navigation target so screen readers don't mislead. -->
<string name="ai_stickers_picker_import_desc">Open Settings to import stickers</string>
<string name="ai_stickers_picker_item_desc">Sticker</string>
<string name="ai_stickers_picker_empty">No stickers yet — open AI Settings to import.</string>

<string name="ai_stickers_packs_empty_title">No sticker packs</string>
<string name="ai_stickers_packs_empty_body">Import images and they\'ll be normalized to 512×512 stickers, ready to send.</string>
<string name="ai_stickers_packs_import_action">Import stickers</string>

<string name="ai_stickers_pack_count_one">1 sticker</string>
<string name="ai_stickers_pack_count_many">%1$d stickers</string>

<string name="ai_stickers_pack_edit_title">Edit pack</string>
<string name="ai_stickers_pack_name_label">Pack name</string>
<string name="ai_stickers_pack_save">Save</string>
<string name="ai_stickers_pack_delete">Delete pack</string>
<string name="ai_stickers_pack_import_more">Import more</string>
<string name="ai_stickers_sticker_delete_title">Delete sticker?</string>
<string name="ai_stickers_sticker_delete_confirm">Delete</string>
<string name="ai_stickers_sticker_delete_cancel">Cancel</string>

<string name="ai_stickers_commit_no_connection">No active text field</string>
<string name="ai_stickers_commit_unsupported">This app doesn\'t accept stickers. Add this pack to WhatsApp to send them there.</string>
<string name="ai_stickers_commit_failed">Couldn\'t insert sticker, try again</string>
<string name="ai_stickers_commit_missing_file">Sticker file missing</string>
```

The `ai_stickers_commit_unsupported` copy intentionally pre-stages the Phase 9b WhatsApp-pack workflow so users have an action to take when COMMIT_CONTENT fails. Phase 9b's "Add to WhatsApp" button completes the loop.

---

## §15 — R8 / Proguard

**No new keep rules expected.** The kotlinx.serialization global rule (`proguard-rules.pro:36-39`) covers the new `$$serializer` classes. `StickerFileProvider` is a manifest entry — R8 keeps it via the implicit "manifest-referenced classes" set. `StickerCommitter`, `StickerNormalizer`, `StickerStorage` all have ≥2 distinct call sites in the same flavor, so R8 won't inline them out of existence (no precedent like Phase 6's `BackendResolver` or Phase 7b's `ReadRespondPromptBuilder` applies here).

If `apkanalyzer dex packages` on the fdroid release APK shows any of `StickerStorage`, `StickerNormalizer`, `StickerCommitter`, or `StickerFileProvider` missing, **that's a regression** — add the relevant keep rule and document it. Don't pre-emptively add them.

---

## §16 — Tests

Four JVM-only unit tests. None require Robolectric.

### `StickerSerdeTest.kt`

```kotlin
class StickerSerdeTest {
    @Test fun manifest_roundTrips() { /* encode + decode equals */ }
    @Test fun manifest_unknownFields_areIgnored() { /* extra JSON keys don't break */ }
    @Test fun pack_emptyEmojis_defaultRoundTrips() { /* sticker missing emojis → empty list */ }
}
```

### `StickerStorageTest.kt`

The project uses **JUnit 4** (verified at `app/app/build.gradle.kts:175` — `junit:junit:4.13.2`). JUnit 5's `@TempDir` is NOT on the classpath; do not use it. Use `Files.createTempDirectory(...)` in `@Before` and `deleteRecursively()` in `@After`. Instantiate via the `@VisibleForTesting internal constructor(rootDir: File)` declared on `StickerStorage` (§4) so each test has an isolated root and the singleton is bypassed.

```kotlin
class StickerStorageTest {
    private lateinit var rootDir: File
    private lateinit var storage: StickerStorage

    @Before fun setUp() {
        rootDir = Files.createTempDirectory("sticker-test").toFile()
        storage = StickerStorage(rootDir)
    }

    @After fun tearDown() {
        rootDir.deleteRecursively()
    }

    @Test fun createPack_writesManifestAndCreatesPackDir() { ... }
    @Test fun addSticker_isReflectedInManifest() { ... }
    @Test fun deleteSticker_removesFileAndManifestEntry() { ... }
    @Test fun deletePack_recursivelyRemovesPackDir() { ... }
    @Test fun getManifest_handlesCorruptManifestGracefully() { ... }
    @Test fun ensureDefaultPack_createsOnEmpty_returnsExistingOtherwise() { ... }
}
```

### `StickerNormalizerTest.kt`

Test only the pure helper `compressToTargetBytes(measure, budget, min, max)`. Substitute a synthetic `measure` curve that returns deterministic byte arrays per quality (e.g., `q -> ByteArray(1000 / q * 100)`).

```kotlin
class StickerNormalizerTest {
    @Test fun returnsHighestQualityWithinBudget() { ... }
    @Test fun returnsMinQualityWhenNothingFits() { ... }
    @Test fun fastPathSkipsSearchWhenMaxQualityFits() { ... }
}
```

### `StickerCommitterMimeTest.kt`

Test only `fieldAcceptsWebp(EditorInfo)`. EditorInfo is a plain data holder; instantiate directly.

```kotlin
class StickerCommitterMimeTest {
    @Test fun nullContentMimeTypes_rejected() { ... }
    @Test fun emptyContentMimeTypes_rejected() { ... }
    @Test fun explicitWebp_accepted() { ... }
    @Test fun imageStar_accepted() { ... }
    @Test fun imagePngOnly_rejected() { ... }
}
```

**Tally target: 12 new tests + 68 prior = 80 passing AI-module unit tests.**

---

## §17 — Definition of Done

All four flavor/buildtype builds clean (`assembleFdroidDebug assemblePlayDebug assembleFdroidRelease assemblePlayRelease`). `lintFdroidDebug lintPlayDebug` clean — only the pre-existing Phase 4 `ObsoleteSdkInt` warning. `git diff app/app/lint-baseline.xml` empty.

Functional invariants (all verified via the smoke tests deferred to human reviewer):

1. `StickerStorage.getInstance(context)` returns a stable singleton; manifest at `filesDir/stickers/manifest.json` round-trips.
2. Photo-picker import succeeds without `READ_MEDIA_IMAGES` declared. Imports auto-create "My Stickers" pack on first run.
3. On-keyboard picker shows after sticker tab tap; back button dismisses; opening a new field dismisses; tapping a sticker COMMITs it (Telegram/Signal target) or toasts (apps without `image/webp` in `contentMimeTypes`).
4. Settings → Stickers route lists packs, lets the user rename/delete packs, delete individual stickers, and import more.
5. Privacy: `grep "Log\\." app/app/src/main/java/com/aikeyboard/app/ai/sticker/` returns only structural-string log calls. No URIs, file paths with user-chosen names, or byte-count specifics.

Dex invariants (verify via `apkanalyzer dex packages`):

| Class | fdroid release | play release |
|---|---|---|
| `com.aikeyboard.app.ai.sticker.StickerStorage` | present | present |
| `com.aikeyboard.app.ai.sticker.StickerNormalizer` | present | present |
| `com.aikeyboard.app.ai.sticker.StickerCommitter` | present | present |
| `com.aikeyboard.app.ai.sticker.StickerFileProvider` | present | present |
| `com.aikeyboard.app.ai.sticker.picker.StickerPickerView` | present | present |
| `com.aikeyboard.app.ai.ui.stickers.StickerPacksScreen*` | present | present |

Manifest invariants:

| Element | fdroid release | play release |
|---|---|---|
| `<provider>` for `StickerFileProvider` (authority `*.stickers`) | yes | yes |
| New `<uses-permission>` entries | none | none |

Tests:

- 12 new JVM unit tests pass.
- 6 pre-existing Robolectric SDK-36 failures unchanged (don't touch).

Carry-overs unchanged (gesture lib redistributability, `LocalLifecycleOwner` deprecation, `EncryptedSharedPreferences` deprecation in Phase 3a migration shim, lint baseline 64 inherited entries, `TermuxValidationActivity.kt:199` `ObsoleteSdkInt`).

---

## §18 — Smoke test scenarios (deferred to human reviewer)

Same precedent as Phase 5b/6/7a/7b/8. Phase 9a is on-device behavior layered on photo picker + keyboard surface + cross-app COMMIT_CONTENT — all require a Pixel 6 Pro with the new fdroidDebug APK.

1. **Empty-state import:** fresh install, open keyboard, tap sticker tab → empty state shown. Tap import → opens Settings → tap Import → photo picker → select 3 images → return to keyboard, tap sticker tab → 3 stickers in grid.
2. **Telegram COMMIT_CONTENT:** open Telegram chat, focus input field, open keyboard, tap sticker → tap a sticker → sticker appears in chat.
3. **Signal COMMIT_CONTENT:** same as Telegram on a Signal thread.
4. **Discord COMMIT_CONTENT:** same. (Discord's mobile app accepts `image/webp` per their dev docs.)
5. **Unsupported field toast:** open the system Settings app, tap into a Wi-Fi password field, open keyboard → tap sticker → tap a sticker → toast shows the "doesn't accept stickers" copy. No crash.
6. **Auto-dismiss on field change:** open picker, swipe to a different app's text field → picker is gone when keyboard reappears.
7. **Settings rename pack:** open Settings → Stickers → tap pack → rename → save → return → list shows new name.
8. **Settings delete sticker:** open pack edit → long-press a sticker → confirm delete → grid updates → file is gone (verify via `adb shell run-as ... ls files/stickers/packs/...`).
9. **Settings delete pack:** delete pack from edit screen → returns to list → pack gone, pack directory gone.
10. **Privacy logcat:** `adb logcat -d -s StickerStorage StickerNormalizer AiCommandRow` after the above session — zero entries containing user file names, URIs, or sticker byte counts. Only structural strings (`Manifest decode failed: <ExceptionClass>`, etc.).

Document outcomes in `PHASE_9a_SUMMARY.md`. If any smoke test fails, fix and re-verify before claiming DoD.

---

## §19 — Open questions for human reviewer (carry into 9b prompt)

1. **Multi-pack tabs on the keyboard picker.** Phase 9a ships with a flat grid. Once Phase 9b lands the WhatsApp pack-identity contract, packs become first-class on the keyboard surface — should the picker grow horizontal pack tabs at the top of the grid? My read: yes, but defer to 9b which has the strongest reason for them (each WhatsApp pack is a deliberate organizational unit).

2. **Sticker thumbnail caching.** `StickerGridAdapter.onBindViewHolder` decodes from disk on the binder thread. For a 30-sticker pack this is fine (≤3MB total decoded), but a 200-sticker library could hitch. My read: defer until Phase 12 if profiling surfaces a problem.

3. **Animated WebP support.** ARCHITECTURE.md explicitly says "animated WebP support out of scope for v1." `ImageDecoder` does decode animated WebP, but the WebP_LOSSY encoder produces a static frame. Confirm with reviewer that v1 stays static-only.

4. **Long-press vs. trailing-icon delete UX.** Phase 9a uses long-press inside the pack-edit screen (precedent: most photo apps). Trailing-icon delete is more discoverable. My read: long-press is fine for v1; A/B in Phase 12 polish if the user reports friction.

5. **Picker dismiss behavior on hardware back press.** Android dispatches back-press to the IME via `onKeyDown(KEYCODE_BACK)` only when the IME has explicitly opted in. Phase 9a doesn't wire this; users dismiss via the picker's own back arrow. My read: acceptable for v1; Phase 12 can wire it if the no-back-button UX feels off.

6. **Unbounded sticker count → OOM risk.** `MAX_IMPORTS_PER_BATCH = 30` caps a single import batch but NOT the total stickers in a pack. After multiple batches a pack could hold 100+ stickers, all of which the picker decodes synchronously into ~1MB ARGB_8888 bitmaps each (~100MB just for thumbnails on a 100-sticker pack). The IME process shares heap with key rendering. My read: log a Phase 12 task to add a pack-size soft cap (e.g., 50 stickers) plus a one-line LRU `BitmapPool` if smoke testing surfaces hitches or OOM. Don't over-engineer pre-emptively.

---

## §20 — Coding-style invariants (carry-overs from prior phases)

- **Comments explain *why*, not *what*.** Delete narration that restates the code.
- **No new `Log.d` / `Log.w` calls that include file URIs, file names with user-chosen text, sticker byte counts, or any `t.message`.** Use `t.javaClass.simpleName` and static structural strings only. (Same precedent as Phase 7b's `t.message` privacy callout and Phase 8's `Log.*` audit.)
- **Same FQN rules across `src/main/` ↔ flavor sources.** Phase 9a's sticker code is entirely in `src/main/` (no a11y dependency, no flavor split needed). This means no string-FQN intent gymnastics this phase. If you find yourself reaching for `Intent.setClassName`, you've drifted off-plan — stop and re-read.
- **No new permissions.** Photo picker is privileged system UI; SAF is permissionless. If lint flags a `MissingPermission` error, the call site is wrong (probably routing through the wrong `MediaStore` API). Don't suppress it; fix the call site.
- **No backwards-compat shims** for `StickerManifest.schemaVersion = 1` — there is no v0. Phase 9b will add fields with default values, NOT a schema bump.
- **Data-preserving** even on storage corruption: `StickerStorage.load()` catches decode failures and returns an empty manifest, but **does not** delete the corrupt file (per the Phase 3a migration precedent — preserve user data on failure paths).

---

## Handoff

When DoD holds, write `PHASE_9a_SUMMARY.md` mirroring the Phase 8 summary's structure (sections: what was built, deviations, dex invariants, manifest invariants, builds/lint, tests, privacy invariants, smoke deferred, carry-overs, open questions, touchpoints for next phase). Commit on a new branch `phase/09a-sticker-engine-commit-content`. Stop. Do not begin Phase 9b.
