# Phase 8 — Always-On Read & Respond + Foreground Service

You are working on `phase/08-always-on-read-respond`, branched from `phase/07b-read-respond-wiring` (commit `f0cd6a4`). This phase adds an "Always-On" toggle in AI Settings that lifts Read & Respond out of the IME and exposes it via a Quick Settings tile + a persistent foreground-service notification with a Read & Respond action. fdroid-only — the play APK never ships any of this.

Stop when this phase's Definition of Done holds. **Do not begin Phase 9.**

## Required reading

Read in order:
1. `ARCHITECTURE.md` — Phase 8 row says "Always-on toggle + foreground service (fdroid only). Surfaces Read & Respond via Quick Settings tile + persistent FGS chip; never in Play APK."
2. `PHASE_REVIEW.md` — Phase 8 acceptance criteria.
3. `PHASE_7b_SUMMARY.md` — what 7b shipped: the consent flow, `A11yProxy`, `ReadRespondPromptBuilder`, the catch-block privacy hardening.
4. `PHASE_7a_SUMMARY.md` — `ScreenReaderService` lifecycle and the singleton-handle pattern. Phase 8's foreground service does NOT replace it — Phase 8 is a separate FGS that orchestrates triggers + notifications, while the AccessibilityService remains the screen-walking actor.
5. `PHASE_6_SUMMARY.md` — `BackendResolver.resolve(storage)` is still the dispatch entry point.
6. Existing `app/app/src/main/java/com/aikeyboard/app/ai/ui/SettingsHubScreen.kt` — `HubRow` pattern for adding new top-level entries.
7. Existing `app/app/src/main/java/com/aikeyboard/app/ai/ui/AiSettingsNavHost.kt` — `AiSettingsRoutes` const declaration for nav graph integration.
8. Existing `app/app/proguard-rules.pro` — keep-rule conventions for new R8-inlined object boundaries.

## Goals

1. Users can trigger Read & Respond from a Quick Settings tile WITHOUT first summoning the IME.
2. The streaming response renders into a notification (high-importance, transient, with Copy + Dismiss actions), since the IME's preview strip may not be visible at trigger time.
3. An "Always-On Read & Respond" toggle in AI Settings → Read & Respond starts/stops a foreground service that owns the persistent FGS-chip notification, the tile lifecycle, and the streaming-response notification posts.
4. The toggle's flip-on flow handles all three permission gates: a11y service enabled, POST_NOTIFICATIONS granted (Android 13+), and Phase 7b's `readRespondConsented` flag. Each gate that's not satisfied gets a clear actionable next step.
5. Boot persistence: if always-on was on when the device shut down, the service auto-starts after BOOT_COMPLETED.
6. fdroid-only invariant: zero foreground service, zero tile service, zero `POST_NOTIFICATIONS`, zero `RECEIVE_BOOT_COMPLETED` in the play APK. Verified via `apkanalyzer`.
7. Privacy invariants from 7a/7b extended: notification body content (the streamed response) never logged; `aboveInputText`/`focusedInputText` never logged; the FGS-chip notification never contains screen content.

## §1 — `AlwaysOnProxy` split (fdroid + play)

Same proxy pattern as Phase 7b's `A11yProxy`: same FQN in both source sets, neither in `src/main/`. The proxy is the boundary between `src/main/` callers (the Settings screen) and the fdroid-only service.

### Create `app/app/src/fdroid/java/com/aikeyboard/app/ai/a11y/AlwaysOnProxy.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.a11y

import android.content.Context
import android.content.Intent
import com.aikeyboard.app.a11y.AlwaysOnService

/**
 * Phase 8 indirection: lets `src/main/` settings code start/stop the
 * fdroid-only AlwaysOnService without naming it. The play impl no-ops.
 *
 * Service start uses startForegroundService(...) so the system gives us the
 * 5-second window to call startForeground() before crashing the process.
 * AlwaysOnService.onStartCommand handles both the initial start and any
 * subsequent intents (boot, tile click, settings toggle).
 */
object AlwaysOnProxy {
    fun start(context: Context) {
        val intent = Intent(context, AlwaysOnService::class.java)
        context.startForegroundService(intent)
    }

    fun stop(context: Context) {
        val intent = Intent(context, AlwaysOnService::class.java)
        context.stopService(intent)
    }

    fun isSupported(): Boolean = true
}
```

### Create `app/app/src/play/java/com/aikeyboard/app/ai/a11y/AlwaysOnProxy.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.a11y

import android.content.Context

/**
 * Phase 8 play-flavor stub. The play APK ships no foreground service, no
 * tile service, no boot receiver. Settings UI uses isSupported() to gray out
 * the toggle and surface "not supported in Play build" copy.
 */
object AlwaysOnProxy {
    @Suppress("UNUSED_PARAMETER")
    fun start(context: Context) { /* no-op */ }

    @Suppress("UNUSED_PARAMETER")
    fun stop(context: Context) { /* no-op */ }

    fun isSupported(): Boolean = false
}
```

## §2 — `AlwaysOnService` (fdroid-only foreground service)

### Create `app/app/src/fdroid/java/com/aikeyboard/app/a11y/AlwaysOnService.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.a11y

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.aikeyboard.app.ai.a11y.A11yProxy
import com.aikeyboard.app.ai.a11y.ReadRespondNotificationBuilder
import com.aikeyboard.app.ai.a11y.ReadRespondPromptBuilder
import com.aikeyboard.app.ai.a11y.ScreenReaderResult
import com.aikeyboard.app.ai.client.AiStreamEvent
import com.aikeyboard.app.ai.client.BackendResolver
import com.aikeyboard.app.ai.storage.SecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

/**
 * Phase 8 foreground service. Runs whenever Always-On Read & Respond is
 * toggled on in AI Settings. Owns the persistent FGS-chip notification (so
 * users can see what's running) and posts streaming-response notifications
 * when triggered by tile or notification action.
 *
 * Does NOT extend AccessibilityService — that remains a separate concern in
 * ScreenReaderService. AlwaysOnService is started/stopped by user toggle and
 * orchestrates tile/notification interactions; it calls A11yProxy whenever
 * it needs to walk the screen.
 */
class AlwaysOnService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var inFlight: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ReadRespondNotificationBuilder.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = ReadRespondNotificationBuilder.buildPersistentChip(this)
        // Android 14+ requires specifying the FGS type at startForeground time;
        // the type must match what's declared in the manifest. The 3-arg overload
        // exists since API 29; older API levels (29-33) ignore the unknown
        // SPECIAL_USE bitmask gracefully — verified safe across API range.
        startForeground(
            ReadRespondNotificationBuilder.NOTIF_ID_PERSISTENT,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        when (intent?.action) {
            ACTION_READ_RESPOND -> triggerReadRespond()
            // null action = service start from toggle/boot; chip is already
            // posted, nothing else to do.
            else -> {}
        }
        // START_STICKY: if the system kills us under memory pressure, restart
        // with a null intent. The chip will re-post; no in-flight requests
        // resume (acceptable — user re-triggers).
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        inFlight = null
        super.onDestroy()
    }

    /**
     * Tile click or notification-action invocation. Walks the screen, builds
     * the prompt, streams into a transient notification. Cancels any
     * in-flight request before starting a new one.
     */
    private fun triggerReadRespond() {
        inFlight?.cancel()
        val storage = SecureStorage.getInstance(applicationContext)
        if (!storage.isReadRespondConsented()) {
            ReadRespondNotificationBuilder.postConsentRequired(this)
            return
        }
        val walk = A11yProxy.requestScreenContext()
        when (walk) {
            ScreenReaderResult.Failure.SERVICE_NOT_ENABLED -> {
                ReadRespondNotificationBuilder.postServiceNotEnabled(this)
                return
            }
            ScreenReaderResult.Failure.NO_ACTIVE_WINDOW -> {
                ReadRespondNotificationBuilder.postFailureChip(this, ReadRespondNotificationBuilder.R_FAILURE_NO_WINDOW)
                return
            }
            ScreenReaderResult.Failure.UNKNOWN_FAILURE -> {
                ReadRespondNotificationBuilder.postFailureChip(this, ReadRespondNotificationBuilder.R_FAILURE_GENERIC)
                return
            }
            ScreenReaderResult.Failure.BUILD_DOES_NOT_SUPPORT -> {
                // Defensive: unreachable, since AlwaysOnService is fdroid-only
                // and the proxy in fdroid never returns this enum value.
                Log.e(TAG, "AlwaysOnService got BUILD_DOES_NOT_SUPPORT from fdroid A11yProxy — bug")
                return
            }
            is ScreenReaderResult.Success -> { /* fall through */ }
        }
        val ctx = (walk as ScreenReaderResult.Success).context
        if (ctx.aboveInputText.isBlank()) {
            ReadRespondNotificationBuilder.postFailureChip(this, ReadRespondNotificationBuilder.R_FAILURE_NO_CONTEXT)
            return
        }
        val backend = BackendResolver.resolve(storage)
        if (backend == null) {
            ReadRespondNotificationBuilder.postFailureChip(this, ReadRespondNotificationBuilder.R_FAILURE_NO_BACKEND)
            return
        }
        val persona = storage.getPersonas()
            .firstOrNull { it.id == storage.getActivePersonaId() }
            ?: storage.getPersonas().first()
        val (input, systemPrompt) = ReadRespondPromptBuilder.build(
            aboveInputText = ctx.aboveInputText,
            focusedInputText = ctx.focusedInputText,
            persona = persona,
        )
        inFlight = scope.launch {
            val accumulated = StringBuilder()
            try {
                backend.rewrite(input, systemPrompt, fewShots = emptyList())
                    .onCompletion { /* notif state already finalized inside collect */ }
                    .collect { event ->
                        when (event) {
                            is AiStreamEvent.Delta -> {
                                accumulated.append(event.text)
                                ReadRespondNotificationBuilder.postStreaming(
                                    this@AlwaysOnService,
                                    accumulated.toString(),
                                )
                            }
                            AiStreamEvent.Done -> {
                                ReadRespondNotificationBuilder.postCompleted(
                                    this@AlwaysOnService,
                                    accumulated.toString(),
                                )
                            }
                            is AiStreamEvent.Error -> {
                                ReadRespondNotificationBuilder.postFailureChip(
                                    this@AlwaysOnService,
                                    ReadRespondNotificationBuilder.R_FAILURE_STREAM,
                                )
                            }
                        }
                    }
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) {
                    Log.w(TAG, "AlwaysOn stream failed: ${t.javaClass.simpleName}")
                    ReadRespondNotificationBuilder.postFailureChip(
                        this@AlwaysOnService, ReadRespondNotificationBuilder.R_FAILURE_STREAM,
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "AlwaysOnService"
        const val ACTION_READ_RESPOND = "com.aikeyboard.app.ACTION_READ_RESPOND"
        // NOTE: notification ID constants and R_FAILURE_* sentinels live on
        // ReadRespondNotificationBuilder (in src/main/), NOT here. AlwaysOnService
        // is fdroid-only; the builder is in main. Putting the constants in main
        // is the only way for a main-residing class (the builder) to reference
        // them without a forbidden main → fdroid compile dependency.
    }
}
```

## §3 — `ReadRespondTileService` (fdroid-only)

### Create `app/app/src/fdroid/java/com/aikeyboard/app/a11y/ReadRespondTileService.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.a11y

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.aikeyboard.app.ai.storage.SecureStorage

/**
 * Phase 8 Quick Settings tile. Visible only on fdroid builds (the manifest
 * entry is in src/fdroid/AndroidManifest.xml).
 *
 * Tile state mirrors `alwaysOnEnabled`: ACTIVE if always-on is on,
 * INACTIVE otherwise. Tap dispatches:
 *   - if always-on is on: send ACTION_READ_RESPOND to AlwaysOnService
 *   - if always-on is off: open AI Settings → Always-On screen so the user
 *     can flip the toggle (we don't auto-flip from a tile tap because the
 *     toggle requires permission flows that are awkward without an Activity)
 */
class ReadRespondTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val enabled = SecureStorage.getInstance(applicationContext).isAlwaysOnEnabled()
        qsTile?.apply {
            state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    @Suppress("DEPRECATION")  // startActivityAndCollapse(Intent) deprecated on
    // API 34+ in favor of the (PendingIntent) overload. Phase 12 polish migrates
    // to the new API once minSdk hits 34. The Intent overload still works on all
    // API levels we support (29-36).
    override fun onClick() {
        super.onClick()
        val storage = SecureStorage.getInstance(applicationContext)
        if (storage.isAlwaysOnEnabled()) {
            val intent = Intent(applicationContext, AlwaysOnService::class.java).apply {
                action = AlwaysOnService.ACTION_READ_RESPOND
            }
            applicationContext.startForegroundService(intent)
        } else {
            // Open the always-on settings screen via the existing AiSettingsActivity.
            // Intent.FLAG_ACTIVITY_NEW_TASK because TileService isn't an Activity context.
            val intent = Intent().apply {
                setClassName(
                    applicationContext.packageName,
                    "com.aikeyboard.app.ai.ui.AiSettingsActivity",
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_DEEP_LINK_ROUTE, "always-on")
            }
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        const val EXTRA_DEEP_LINK_ROUTE = "deep_link_route"
    }
}
```

**On `AiSettingsActivity` integration:** check the existing class — if it accepts a deep-link route extra in `onCreate` (start destination override), wire `EXTRA_DEEP_LINK_ROUTE = "always-on"` to navigate directly to the new screen on launch. If not, add minimal extra-handling: `intent.getStringExtra(ReadRespondTileService.EXTRA_DEEP_LINK_ROUTE)?.let { startDestination = it }`. Be conservative — match existing patterns; don't refactor the activity's launch logic.

## §4 — `ReadRespondNotificationBuilder` (src/main)

The builder lives in `src/main/` because notifications themselves are platform-agnostic; only the SERVICE that posts them is fdroid-only. This means the play APK ships the builder class but never instantiates it (R8 will keep it alive via the Phase 8 keep rule below).

### Create `app/app/src/main/java/com/aikeyboard/app/ai/a11y/ReadRespondNotificationBuilder.kt`

Don't paste the full implementation here; spec the surface and the requirements:

```kotlin
package com.aikeyboard.app.ai.a11y

object ReadRespondNotificationBuilder {

    // Two channels.
    const val CHANNEL_PERSISTENT = "read_respond_persistent"   // IMPORTANCE_LOW
    const val CHANNEL_RESULTS = "read_respond_results"         // IMPORTANCE_HIGH

    // Notification IDs. Constants live HERE (in src/main/) rather than on
    // AlwaysOnService (in src/fdroid/) so that ClipboardCopyReceiver in fdroid
    // can reference them via a main-classpath name without forcing main code
    // to import a fdroid-only class.
    const val NOTIF_ID_PERSISTENT = 1001
    const val NOTIF_ID_RESULT = 1002

    // Failure-kind sentinels. AlwaysOnService passes these to postFailureChip;
    // postFailureChip's internal `when` maps them to localized R.string IDs.
    const val R_FAILURE_NO_WINDOW = 0
    const val R_FAILURE_GENERIC = 1
    const val R_FAILURE_NO_CONTEXT = 2
    const val R_FAILURE_NO_BACKEND = 3
    const val R_FAILURE_STREAM = 4

    /** Idempotent. Call from AlwaysOnService.onCreate(). */
    fun createChannels(context: Context)

    /**
     * The FGS chip. ongoing=true, autoCancel=false. NotificationCompat.Builder.
     * Title: stringResource(ai_always_on_chip_title)
     * Text: stringResource(ai_always_on_chip_body)
     * Tap action: PendingIntent that launches AiSettingsActivity → always-on
     * Action: "Read & Respond" PendingIntent.getService(...) targeting
     *   AlwaysOnService with ACTION_READ_RESPOND. The mutability flag must be
     *   PendingIntent.FLAG_IMMUTABLE.
     *
     * CROSS-FLAVOR INTENT CONSTRUCTION: AlwaysOnService is fdroid-only. This
     * builder is in src/main/. Use Intent().setClassName(...) with the FQN as
     * a String literal — NEVER `Intent(context, AlwaysOnService::class.java)`,
     * which would fail to compile in the play flavor. Same applies to the
     * AiSettingsActivity intent (use setClassName with the FQN string).
     *
     * Example for the "Read & Respond" service action:
     *   val serviceIntent = Intent().apply {
     *       setClassName(context.packageName,
     *           "com.aikeyboard.app.a11y.AlwaysOnService")
     *       action = "com.aikeyboard.app.ACTION_READ_RESPOND"  // hardcoded
     *           // string literal — same reason; ACTION_READ_RESPOND lives on
     *           // the fdroid-only AlwaysOnService companion.
     *   }
     *
     * Returns a Notification, suitable for startForeground().
     */
    fun buildPersistentChip(context: Context): Notification

    /**
     * Streaming response — posts/updates NOTIF_ID_RESULT on CHANNEL_RESULTS.
     * Title: stringResource(ai_read_respond_result_title)
     * Body: text (the accumulated response). USES setBigTextStyle for
     *   multi-line. setOnlyAlertOnce(true) so updates don't re-vibrate.
     * No actions while streaming. setOngoing(true) and setAutoCancel(false).
     * Visibility VISIBILITY_PRIVATE to keep response off the lock screen.
     */
    fun postStreaming(context: Context, text: String)

    /**
     * Final state. Same notification ID as postStreaming. Becomes:
     * setOngoing(false), setAutoCancel(true), adds Copy + Dismiss actions.
     * Copy intent: PendingIntent.getBroadcast targeting ClipboardCopyReceiver
     *   via setClassName (string FQN), with EXTRA_TEXT carrying the response.
     *   Action string is "com.aikeyboard.app.ACTION_CLIPBOARD_COPY" — declared
     *   as a literal, NOT via ClipboardCopyReceiver.ACTION (cross-flavor rule).
     */
    fun postCompleted(context: Context, text: String)

    /**
     * Reuses NOTIF_ID_RESULT slot. Renders a localized error using the
     * sentinel passed in. Internal `when` maps the sentinel to an R.string.
     * setAutoCancel(true).
     */
    fun postFailureChip(context: Context, failureKind: Int)

    fun postConsentRequired(context: Context)
    fun postServiceNotEnabled(context: Context)
}
```

The `failureKind → resId` mapping inside `postFailureChip` is JVM-pure switch logic — extract it as a private `failureKindToResId(kind: Int): Int` helper so the unit test in §11 can call it directly without a Context:

| `failureKind` | maps to |
|---|---|
| `R_FAILURE_NO_WINDOW` | `R.string.ai_read_respond_failure_no_window` |
| `R_FAILURE_GENERIC` | `R.string.ai_read_respond_failure_generic` |
| `R_FAILURE_NO_CONTEXT` | `R.string.ai_read_respond_failure_no_context` |
| `R_FAILURE_NO_BACKEND` | `R.string.ai_read_respond_failure_no_backend` |
| `R_FAILURE_STREAM` | `R.string.ai_read_respond_failure_stream` |

**Throttling:** do NOT add custom delta-buffering. Android already coalesces frequent updates per channel; setOnlyAlertOnce ensures no haptic re-trigger. If smoke testing shows visible jank, Phase 12 can revisit.

**Lockscreen:** every notification this builder posts uses `Notification.VISIBILITY_PRIVATE` and (if you want a redacted public version) `setPublicVersion(...)` with a generic "Read & Respond" body that doesn't include the actual response text.

### §4.5 — `ClipboardCopyReceiver` (fdroid)

Create `app/app/src/fdroid/java/com/aikeyboard/app/a11y/ClipboardCopyReceiver.kt`:

```kotlin
class ClipboardCopyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("Read & Respond", text))
        // Dismiss the result notification. Constant lives on the builder
        // (in src/main/) — see §4. Don't import the fdroid-only AlwaysOnService.
        NotificationManagerCompat.from(context).cancel(ReadRespondNotificationBuilder.NOTIF_ID_RESULT)
        // Android 13+ shows its own clipboard toast; do not double-toast.
    }
    companion object {
        const val ACTION = "com.aikeyboard.app.ACTION_CLIPBOARD_COPY"
        const val EXTRA_TEXT = "text"
    }
}
```

Register in `src/fdroid/AndroidManifest.xml`:

```xml
<receiver
    android:name="com.aikeyboard.app.a11y.ClipboardCopyReceiver"
    android:exported="false">
    <intent-filter>
        <action android:name="com.aikeyboard.app.ACTION_CLIPBOARD_COPY" />
    </intent-filter>
</receiver>
```

## §5 — `BootReceiver` (fdroid-only)

Create `app/app/src/fdroid/java/com/aikeyboard/app/a11y/BootReceiver.kt`:

```kotlin
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Direct-boot mode is not supported (directBootAware="false"), so the
        // user is unlocked by the time this fires — Tink-backed SecureStorage
        // is readable. We still wrap in runCatching as a defensive measure;
        // failing-silent on first-boot edge cases is preferable to crashing
        // the boot receiver.
        runCatching {
            val storage = SecureStorage.getInstance(context.applicationContext)
            if (storage.isAlwaysOnEnabled()) {
                AlwaysOnProxy.start(context.applicationContext)
            }
        }
    }
}
```

**Direct-boot caveat:** Tink keystore reads need CE storage (post-unlock). With `directBootAware="false"` the receiver is only invoked after user unlock, so `SecureStorage` is reachable. The `runCatching` wrapper above is belt-and-suspenders. Do NOT register for `LOCKED_BOOT_COMPLETED` — that requires `directBootAware="true"`, which would force CE-backed storage code into a partially-initialized state.

Register in `src/fdroid/AndroidManifest.xml`:

```xml
<receiver
    android:name="com.aikeyboard.app.a11y.BootReceiver"
    android:exported="true"
    android:directBootAware="false">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

`exported="true"` is required: the system's ActivityManager (different UID) sends BOOT_COMPLETED. `directBootAware="false"` defers receiver invocation until after the user unlock — sidesteps the Tink direct-boot issue cleanly without code-side workarounds.

## §6 — `SecureData` extension

Add to `app/app/src/main/java/com/aikeyboard/app/ai/storage/SecureData.kt`:

```kotlin
    // Phase 8: Always-On Read & Respond toggle. Default false. When true,
    // AlwaysOnService runs as FGS, the QS tile is ACTIVE, and BootReceiver
    // restarts the service after reboot. Flipping to true requires a11y
    // permission, POST_NOTIFICATIONS (Android 13+), and readRespondConsented.
    val alwaysOnEnabled: Boolean = false,
```

Add to `SecureStorage.kt`:

```kotlin
    fun isAlwaysOnEnabled(): Boolean = load().alwaysOnEnabled
    fun setAlwaysOnEnabled(enabled: Boolean) {
        save(load().copy(alwaysOnEnabled = enabled))
    }
```

## §7 — `AlwaysOnRoute` + `AlwaysOnScreen` (src/main, Compose)

### Create `app/app/src/main/java/com/aikeyboard/app/ai/ui/alwayson/AlwaysOnRoute.kt`

```kotlin
@Composable
fun AlwaysOnRoute(
    onBack: () -> Unit,
    storage: SecureStorage,
) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(storage.isAlwaysOnEnabled()) }
    var a11yEnabled by remember { mutableStateOf(false) }
    var notifGranted by remember { mutableStateOf(false) }
    var consented by remember { mutableStateOf(storage.isReadRespondConsented()) }
    var resumeCount by remember { mutableIntStateOf(0) }

    // ON_RESUME refresh — re-check permission state every time the user comes
    // back from System Settings, the consent activity, or the notification
    // permission prompt. EXACT precedent: BackendsScreen.kt:60-75 (Phase 6).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeCount++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notifGranted = granted
        if (granted && a11yEnabled && consented) {
            // All gates satisfied; flip on.
            storage.setAlwaysOnEnabled(true)
            AlwaysOnProxy.start(context)
            enabled = true
        }
    }

    LaunchedEffect(resumeCount) {
        a11yEnabled = isAccessibilityServiceEnabled(context)
        notifGranted = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true
        consented = storage.isReadRespondConsented()
        enabled = storage.isAlwaysOnEnabled()
    }

    AlwaysOnScreen(
        enabled = enabled,
        a11yEnabled = a11yEnabled,
        notifGranted = notifGranted,
        consented = consented,
        supported = AlwaysOnProxy.isSupported(),
        onBack = onBack,
        onToggle = { newValue ->
            // FIRST gate: play flavor cannot run the service. Toast and return
            // before any storage write — otherwise the switch flips on with no
            // running service, persisting `alwaysOnEnabled = true` falsely.
            if (newValue && !AlwaysOnProxy.isSupported()) {
                Toast.makeText(
                    context, R.string.ai_always_on_not_supported_play, Toast.LENGTH_SHORT
                ).show()
                return@AlwaysOnScreen
            }
            if (!newValue) {
                storage.setAlwaysOnEnabled(false)
                AlwaysOnProxy.stop(context)
                enabled = false
                return@AlwaysOnScreen
            }
            // Trying to flip ON. Walk the gates in order.
            if (!a11yEnabled) {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return@AlwaysOnScreen
            }
            if (!consented) {
                // Reuse the Phase 7b consent activity.
                context.startActivity(
                    Intent(context, ReadRespondConsentActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return@AlwaysOnScreen
            }
            if (Build.VERSION.SDK_INT >= 33 && !notifGranted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return@AlwaysOnScreen
            }
            storage.setAlwaysOnEnabled(true)
            AlwaysOnProxy.start(context)
            enabled = true
        },
    )
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    // Best-effort: query Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES.
    // String-contains check on our service component name is the standard
    // pattern (no public API for "is my service enabled").
    val target = ComponentName(context, "com.aikeyboard.app.a11y.ScreenReaderService").flattenToString()
    val setting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return setting.contains(target)
}
```

### Create `AlwaysOnScreen.kt` — the Composable

Sections, in order:

1. **TopAppBar** with back arrow, title "Always-On Read & Respond"
2. **Switch row** at the top: "Always-On Read & Respond" + Switch. If `!supported`, the row is greyed out and tapping shows a toast "Not supported in Play build".
3. **Status section** — three rows showing the three gates:
   - "Accessibility service: Enabled / Disabled" (with action chip "Open settings" if disabled)
   - "Notification permission: Granted / Required (Android 13+)" (with action chip "Grant" if Android 13+ and not granted)
   - "Screen-content consent: Allowed / Required" (with action chip "Review")
4. **Body text** explaining what enabling does:
   - "A persistent notification will appear while always-on is active."
   - "Add the AI Keyboard tile to Quick Settings to trigger Read & Respond from anywhere."
   - "Read & Respond will only fire when you tap the tile or the notification action — never automatically."
5. **"How to add the tile"** instructions: a 3-step list, e.g. "Open Quick Settings → Edit / pencil icon → drag 'Read & Respond' to active tiles."

### Wire into `AiSettingsRoutes` and `AiSettingsNavHost.kt`

Add the route constant to the `AiSettingsRoutes` object:

```kotlin
const val ALWAYS_ON = "always-on"
```

Add the `composable(...)` block in `AiSettingsNavHost.kt`, mirroring the existing `BACKENDS_TERMUX` / `KEYBOARD_CHROME` patterns. The `storage` parameter comes from wherever the existing screens get it (likely a `LocalContext.current.let { SecureStorage.getInstance(it) }` or from the activity's nav-graph factory).

### Add a HubRow to `SettingsHubScreen.kt`

```kotlin
HubRow(
    titleRes = R.string.ai_settings_hub_always_on_title,
    descriptionRes = R.string.ai_settings_hub_always_on_desc,
    iconRes = R.drawable.ic_settings_default, // or a new vector — your call
    onClick = onOpenAlwaysOn,
)
HorizontalDivider()
```

And thread the `onOpenAlwaysOn: () -> Unit` parameter through `SettingsHubScreen`'s caller in `AiSettingsNavHost`. If the play flavor has `AlwaysOnProxy.isSupported() == false`, the row should still render but tap should toast — this prevents Settings UI from differing between flavors (debugger / reviewer ergonomics).

## §8 — `AiSettingsActivity` deep-link extra + `AiSettingsNavHost` signature change

`NavHost(startDestination = ...)` is set declaratively inside `AiSettingsNavHost`'s body and cannot be changed by recomposition. The `startDestination` must be a constructor argument to the composable. Three concrete edits required:

### Edit 1: `AiSettingsNavHost` signature change

Change the function signature from `fun AiSettingsNavHost()` (no parameters) to:

```kotlin
@Composable
fun AiSettingsNavHost(
    startDestination: String = AiSettingsRoutes.HUB,
) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = startDestination) {
        // ... existing composable() blocks unchanged ...
    }
}
```

### Edit 2: `AiSettingsActivity.onCreate` reads the extra and passes it through

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val startRoute = intent.getStringExtra(ReadRespondTileService.EXTRA_DEEP_LINK_ROUTE)
        ?: AiSettingsRoutes.HUB
    setContent {
        AiSettingsNavHost(startDestination = startRoute)
    }
}
```

### Edit 3: New route value in `AiSettingsRoutes`

Add `const val ALWAYS_ON = "always-on"` (matches the literal in `ReadRespondTileService.onClick`).

### Edit 4: `composable(HUB)` block needs `onOpenAlwaysOn`

In `AiSettingsNavHost`'s existing `composable(AiSettingsRoutes.HUB) { SettingsHubScreen(...) }` block, add the new lambda parameter:

```kotlin
composable(AiSettingsRoutes.HUB) {
    SettingsHubScreen(
        onOpenPersonas = { nav.navigate(AiSettingsRoutes.PERSONAS_LIST) },
        onOpenKeyboardChrome = { nav.navigate(AiSettingsRoutes.KEYBOARD_CHROME) },
        onOpenBackends = { nav.navigate(AiSettingsRoutes.BACKENDS_LIST) },
        onOpenAlwaysOn = { nav.navigate(AiSettingsRoutes.ALWAYS_ON) },
    )
}
```

### Edit 5: New `composable(ALWAYS_ON)` block

```kotlin
composable(AiSettingsRoutes.ALWAYS_ON) {
    val context = LocalContext.current
    AlwaysOnRoute(
        onBack = { nav.popBackStack() },
        storage = SecureStorage.getInstance(context),
    )
}
```

`SettingsHubScreen` must take a new `onOpenAlwaysOn: () -> Unit` parameter (see §7).

## §9 — Manifest changes

### Add to `app/app/src/fdroid/AndroidManifest.xml`

Inside `<manifest>` at the top, alongside any existing fdroid-only `<uses-permission>`s:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

**Do NOT add `RECEIVE_BOOT_COMPLETED` to the fdroid overlay** — it's already declared in `src/main/AndroidManifest.xml:12` (the legacy HeliBoard `SystemBroadcastReceiver` uses it). Both APKs already ship it. The §13 invariant table reflects this.

Inside `<application>` (alongside the existing `ScreenReaderService` declaration):

```xml
<!-- AlwaysOnService — runs whenever Always-On Read & Respond is toggled on. -->
<service
    android:name="com.aikeyboard.app.a11y.AlwaysOnService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="screen_reader_companion" />
</service>

<!-- Quick Settings tile. The system binds via BIND_QUICK_SETTINGS_TILE. -->
<service
    android:name="com.aikeyboard.app.a11y.ReadRespondTileService"
    android:exported="true"
    android:icon="@drawable/ic_read_respond"
    android:label="@string/ai_always_on_tile_label"
    android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
    <intent-filter>
        <action android:name="android.service.quicksettings.action.QS_TILE" />
    </intent-filter>
</service>

<receiver
    android:name="com.aikeyboard.app.a11y.ClipboardCopyReceiver"
    android:exported="false">
    <intent-filter>
        <action android:name="com.aikeyboard.app.ACTION_CLIPBOARD_COPY" />
    </intent-filter>
</receiver>

<receiver
    android:name="com.aikeyboard.app.a11y.BootReceiver"
    android:exported="true"
    android:directBootAware="false">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

The `<property>` element with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` is required by Google Play policy AND by Android 14+ runtime — but per ARCHITECTURE.md, this APK never ships to Play, so the property's main consumer is the runtime check. The string `"screen_reader_companion"` is for ourselves; it appears in `dumpsys activity services` output for human auditing.

### `src/play/AndroidManifest.xml`

Stays empty (or whatever Phase 7a left there). No changes.

### `src/main/AndroidManifest.xml`

No changes. Permissions are fdroid-only.

## §10 — Strings

Add to `app/app/src/main/res/values/ai_strings.xml`:

```xml
<!-- Phase 8: Always-On Read & Respond -->
<string name="ai_always_on_tile_label">Read &amp; Respond</string>
<string name="ai_always_on_chip_title">AI Keyboard — Read &amp; Respond</string>
<string name="ai_always_on_chip_body">Tap a chat\'s input field, then tap the tile to compose a response.</string>
<string name="ai_always_on_chip_action">Read &amp; Respond now</string>
<string name="ai_settings_hub_always_on_title">Always-on Read &amp; Respond</string>
<string name="ai_settings_hub_always_on_desc">Trigger Read &amp; Respond from Quick Settings, even when the keyboard isn\'t open</string>
<string name="ai_always_on_screen_title">Always-on Read &amp; Respond</string>
<string name="ai_always_on_toggle_label">Always-on Read &amp; Respond</string>
<string name="ai_always_on_status_a11y_enabled">Accessibility service: Enabled</string>
<string name="ai_always_on_status_a11y_disabled">Accessibility service: Disabled</string>
<string name="ai_always_on_status_notif_granted">Notification permission: Granted</string>
<string name="ai_always_on_status_notif_required">Notification permission: Required</string>
<string name="ai_always_on_status_consent_yes">Screen-content consent: Allowed</string>
<string name="ai_always_on_status_consent_no">Screen-content consent: Required</string>
<string name="ai_always_on_action_open_a11y">Open accessibility settings</string>
<string name="ai_always_on_action_grant_notif">Grant</string>
<string name="ai_always_on_action_review_consent">Review</string>
<string name="ai_always_on_body_persistent_notice">A persistent notification will appear while always-on is active.</string>
<string name="ai_always_on_body_tile_hint">Add the AI Keyboard tile to Quick Settings to trigger Read &amp; Respond from anywhere.</string>
<string name="ai_always_on_body_consent_reminder">Read &amp; Respond only fires when you tap the tile or the notification action — never automatically.</string>
<string name="ai_always_on_body_how_to_add_tile">How to add the tile: open Quick Settings, tap the pencil/edit icon, then drag \"Read &amp; Respond\" into your active tiles.</string>
<string name="ai_always_on_not_supported_play">Always-on Read &amp; Respond requires the F-Droid build of AI Keyboard.</string>
<string name="ai_read_respond_result_title">Response ready</string>
<string name="ai_read_respond_action_copy">Copy</string>
<string name="ai_read_respond_action_dismiss">Dismiss</string>
<string name="ai_read_respond_failure_no_window">No active window — open the app you want to respond to first.</string>
<string name="ai_read_respond_failure_generic">Couldn\'t read screen, try again.</string>
<string name="ai_read_respond_failure_no_context">Nothing to respond to above your cursor.</string>
<string name="ai_read_respond_failure_no_backend">Configure a backend in AI Settings to use Read &amp; Respond.</string>
<string name="ai_read_respond_failure_stream">Read &amp; Respond failed.</string>
<string name="ai_read_respond_failure_consent_required">Tap the AI Keyboard\'s Read &amp; Respond button once to grant consent.</string>
<string name="ai_read_respond_failure_a11y_required">Enable AI Keyboard\'s accessibility service in System Settings.</string>
```

The five `R_FAILURE_*` sentinels in §2 map to:

| Sentinel constant | Resource string |
|---|---|
| `R_FAILURE_NO_WINDOW = 0` | `R.string.ai_read_respond_failure_no_window` |
| `R_FAILURE_GENERIC = 1` | `R.string.ai_read_respond_failure_generic` |
| `R_FAILURE_NO_CONTEXT = 2` | `R.string.ai_read_respond_failure_no_context` |
| `R_FAILURE_NO_BACKEND = 3` | `R.string.ai_read_respond_failure_no_backend` |
| `R_FAILURE_STREAM = 4` | `R.string.ai_read_respond_failure_stream` |

Wire the mapping inside `ReadRespondNotificationBuilder.postFailureChip` via a `when` on the `failureKind: Int` parameter. Don't expose the resource IDs across the fdroid/main boundary directly — keep the integer sentinel as the API.

## §11 — Tests (JVM)

`AlwaysOnService`, `ReadRespondTileService`, `BootReceiver`, `ClipboardCopyReceiver` are all Android-framework-bound — JVM unit tests can't exercise them without Robolectric, which is currently broken on this project (Robolectric 4.14.1 / SDK 36 mismatch, see Phase 6 carry-over).

What CAN be tested in JVM:
- `ReadRespondNotificationBuilder.postFailureChip` failureKind→stringResource mapping. Pure switch logic. Test that all five `R_FAILURE_*` sentinels resolve to non-zero string resource IDs (using a fake `Context` via Mockito-inline if available, OR by extracting the `failureKind → resId` mapping into a private helper that takes no `Context` and is JVM-pure).
- `SecureStorage.{is,set}AlwaysOnEnabled` round-trip. Trivial; mirror existing `selectedBackendStrategy` test.

Add at minimum:
- 1 test for the `failureKind → resId` mapping (5 cases parameterized)
- 1 test for `SecureStorage` round-trip

Total ≥6 tests added. Existing 60 still pass.

## §12 — Smoke test scenarios (deferred to human reviewer)

1. **Toggle on, all gates satisfied:** a11y enabled, consent given, notif granted (or pre-Android 13). Flip switch → service starts → persistent FGS chip appears in notification shade → "Read & Respond" tile appears in QS edit list → drag to active tiles.
2. **Tile triggers stream:** active in chat thread, swipe down QS, tap Read & Respond tile → notification posts and streams response → Copy action puts text in clipboard, Dismiss removes notification.
3. **Toggle on with gates missing:** various permutations. Verify the toggle doesn't flip on if any gate is unmet, and the status row's action chip routes correctly.
4. **Toggle off:** flip switch → service stops → FGS chip disappears → tile becomes INACTIVE.
5. **Boot persistence:** toggle on, reboot device, observe FGS chip re-appears within ~30s of unlock. Tile still ACTIVE.
6. **A11y revocation mid-session:** disable AI Keyboard a11y service in System Settings while always-on is on. Tap tile. Expect: failure notification "Enable AI Keyboard's accessibility service" + intent. Persistent FGS chip remains.
7. **Backend not configured:** clear all API keys / unmark Termux active. Tap tile. Expect: failure notification "Configure a backend".
8. **Play flavor:** AI Settings hub still shows the Always-On entry; tapping the toggle shows "Not supported in Play build" toast; switch doesn't flip.
9. **Privacy logcat scan:** run scenarios 2 + 6 with logcat at `*:V`; grep output for screen content. Expect zero matches.
10. **Clipboard receiver:** trigger response, tap Copy, verify clipboard has the response text exactly (Android 13+ shows its own toast — don't double-toast).

## §13 — Build / lint / dex invariants

Run before declaring DoD:

```bash
cd /Users/kacper/SDK/apk-dev/ai-keyboard/app

./gradlew assembleFdroidDebug assemblePlayDebug \
          assembleFdroidRelease assemblePlayRelease

./gradlew lintFdroidDebug lintPlayDebug
git diff app/app/lint-baseline.xml  # should be empty

./gradlew :app:testFdroidDebugUnitTest :app:testPlayDebugUnitTest
```

**Dex invariants** (use `apkanalyzer dex packages`):

| APK | Must contain | Must NOT contain |
|---|---|---|
| `app-fdroid-release-unsigned.apk` | `AlwaysOnService`, `ReadRespondTileService`, `BootReceiver`, `ClipboardCopyReceiver`, `AlwaysOnProxy` (fdroid impl), `ReadRespondNotificationBuilder`, `AlwaysOnRoute`, `AlwaysOnScreen` | — |
| `app-play-release-unsigned.apk` | `AlwaysOnProxy` (play no-op impl), `ReadRespondNotificationBuilder` (kept by R8 keep rule), `AlwaysOnRoute`, `AlwaysOnScreen` | `AlwaysOnService`, `ReadRespondTileService`, `BootReceiver`, `ClipboardCopyReceiver`, `com.aikeyboard.app.a11y.*` |

**Manifest invariants** (use `apkanalyzer manifest print`):

| APK | Must contain | Must NOT contain |
|---|---|---|
| `app-fdroid-release-unsigned.apk` | service entries for `AlwaysOnService`, `ReadRespondTileService`; receivers for `BootReceiver`, `ClipboardCopyReceiver`; `<uses-permission>` for FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS | — |
| `app-play-release-unsigned.apk` | — | any of the four new services/receivers (`AlwaysOnService`, `ReadRespondTileService`, `BootReceiver`, `ClipboardCopyReceiver`); the three new fdroid-only permissions (FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS) |

**Note on `RECEIVE_BOOT_COMPLETED`:** Already declared in `src/main/AndroidManifest.xml` since Phase 1 (HeliBoard's legacy `SystemBroadcastReceiver`). Both APKs ship it. NOT a Phase 8 addition; not in the "must / must not" assertions above.

## §14 — Proguard / R8 keep rules

Add to `app/app/proguard-rules.pro` alongside existing Phase 6/7b keeps:

```proguard
# Phase 8: keep AlwaysOnProxy in both flavors. Same precedent as A11yProxy:
# the play impl is a 1-line no-op that R8 will inline; the keep rule preserves
# the dex-invariant assertion that the play APK contains a (no-op) proxy
# class rather than inlining its constants into Settings UI bytecode.
-keep class com.aikeyboard.app.ai.a11y.AlwaysOnProxy { *; }

# Phase 8: keep ReadRespondNotificationBuilder so the play APK ships the
# class even though no fdroid-only code references it there. Required by
# the §13 dex invariant table.
-keep class com.aikeyboard.app.ai.a11y.ReadRespondNotificationBuilder { *; }

# Phase 8: keep BootReceiver, AlwaysOnService, ReadRespondTileService,
# ClipboardCopyReceiver — Android instantiates these reflectively from
# the manifest. R8 doesn't auto-detect that without the manifest-aware
# default rules, but the AGP default proguard files DO cover Service +
# BroadcastReceiver subclasses. No new keep rule needed unless the dex
# invariant check reveals an issue. Do not over-keep.
```

Verify both proxy and builder classes are observable in the play APK after build:

```bash
$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer dex packages \
  /Users/kacper/SDK/apk-dev/ai-keyboard/app/app/build/outputs/apk/play/release/app-play-release-unsigned.apk \
  | grep -E "AlwaysOnProxy|ReadRespondNotificationBuilder"
```
Should print both classes, one per line. If either is missing, add a keep rule.

## Definition of Done

- [ ] `AlwaysOnProxy.kt` exists in BOTH `src/fdroid/` and `src/play/`, neither in `src/main/`.
- [ ] `AlwaysOnService.kt` exists in `src/fdroid/`, with `specialUse` FGS type, and is correctly registered in `src/fdroid/AndroidManifest.xml` with the `<property>` subtype declaration.
- [ ] `ReadRespondTileService.kt` exists in `src/fdroid/` with the QS_TILE intent-filter and `BIND_QUICK_SETTINGS_TILE` permission.
- [ ] `BootReceiver.kt` exists in `src/fdroid/`, registered with `BOOT_COMPLETED`, runCatching-safe against direct-boot storage failures.
- [ ] `ClipboardCopyReceiver.kt` exists in `src/fdroid/`, registered with `exported="false"`.
- [ ] `ReadRespondNotificationBuilder.kt` exists in `src/main/`, with two channels created idempotently, lockscreen visibility PRIVATE, no logging of accumulated text or screen content.
- [ ] `AlwaysOnRoute.kt` and `AlwaysOnScreen.kt` exist in `src/main/`, with three-gate flow (a11y / notif / consent) and a working toggle. Permission-state queries use the `resumeCount` / `ON_RESUME` pattern (precedent: `BackendsScreen.kt:60-75`), NOT `LaunchedEffect(Unit)`.
- [ ] Toggle's `onToggle` lambda gates the storage write behind `AlwaysOnProxy.isSupported()` — play flavor cannot persist `alwaysOnEnabled = true`.
- [ ] `AlwaysOnRoute` route added to `AiSettingsRoutes`, wired in `AiSettingsNavHost`.
- [ ] `AiSettingsNavHost` signature updated to take `startDestination: String = AiSettingsRoutes.HUB`.
- [ ] `SettingsHubScreen.kt` has a HubRow for Always-On with `onOpenAlwaysOn: () -> Unit` parameter; the parameter is wired from `AiSettingsNavHost`'s `composable(HUB)` block.
- [ ] `AiSettingsActivity` reads `EXTRA_DEEP_LINK_ROUTE` and passes it as `startDestination` to `AiSettingsNavHost`.
- [ ] `ReadRespondNotificationBuilder` (in src/main/) hosts the `NOTIF_ID_*` and `R_FAILURE_*` constants — NOT `AlwaysOnService`. Cross-flavor intents in the builder use `Intent.setClassName(packageName, "<FQN>")`, NEVER `::class.java`.
- [ ] `SecureData` has `alwaysOnEnabled: Boolean = false`; `SecureStorage` exposes get/set.
- [ ] All four flavor/buildtype APKs build clean.
- [ ] `lintFdroidDebug lintPlayDebug` clean; `git diff app/app/lint-baseline.xml` empty.
- [ ] Dex invariants in §13 hold: play APK has zero `com.aikeyboard.app.a11y.*` classes (other than via the `.ai.a11y.*` proxy/builder, which is a different package); fdroid APK has all the new classes.
- [ ] Manifest invariants in §13 hold: play APK has zero new permissions / services / receivers; fdroid APK has all four permissions and four services/receivers.
- [ ] R8 keep rules for `AlwaysOnProxy` and `ReadRespondNotificationBuilder` are in `proguard-rules.pro`.
- [ ] All prior 60 AI-module unit tests still pass; ≥6 new tests added (per §11).
- [ ] No `Log.*` call in any new file (`AlwaysOnService`, `ReadRespondTileService`, `BootReceiver`, `ClipboardCopyReceiver`, `ReadRespondNotificationBuilder`, `AlwaysOnRoute`) accepts the streamed response text, `aboveInputText`, `focusedInputText`, or `t.message` as an argument. Only `t.javaClass.simpleName` and structural metadata. Verify via `git diff` before commit.
- [ ] On-device smoke test: deferred to human reviewer per Phase 5b/6/7a/7b precedent. Summary lists the ten scenarios in §12.

## Open questions to resolve at end of phase

In `PHASE_8_SUMMARY.md` (≤50 lines, mirroring 7b), surface:

1. **Notification update throttling** — Phase 8 ships without delta-buffering, relying on Android's per-channel coalescing + `setOnlyAlertOnce`. Acceptable, or did smoke testing show jank that needs Phase 12 polish?

2. **Tile state when always-on is off** — current spec: tile renders STATE_INACTIVE; tap opens Settings. Alternative: tile is hidden entirely until always-on is enabled. The current behavior is more discoverable for new users; the alternative is cleaner. My read: keep STATE_INACTIVE — discoverability wins.

3. **Boot receiver and direct-boot** — current spec uses `directBootAware="false"` to defer until user unlock, sidestepping Tink's CE-only storage. Does that introduce unacceptable user-visible latency on first reboot (e.g., user unlocks, says "where's my notification"), or is the few-second delay invisible in practice?

4. **`POST_NOTIFICATIONS` denial UX** — current spec requires the permission before flipping `alwaysOnEnabled = true`. If the user denies and then tries again, the system suppresses the prompt. We don't currently route them to App Settings → Permissions to grant manually. Acceptable, or worth a "Grant in App Info" link?

5. **Persistent FGS chip body copy** — "Tap a chat's input field, then tap the tile to compose a response." That's the action-oriented framing. Alternative is privacy-oriented framing: "Read & Respond is on standby. It runs only when you trigger it." Pick one in the summary; I lean toward the privacy framing because that's what the chip exists to communicate.

## Hand-off

After Phase 8's DoD holds, commit on `phase/08-always-on-read-respond` with a single commit message summarizing the change. Do NOT push, do NOT merge. Write `PHASE_8_SUMMARY.md` in repo root, ≤50 lines, structured like prior summaries. Stop. Phase 9 (sticker engine) is the next planner-prompt and will be drafted separately.
