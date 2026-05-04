// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.a11y

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.aikeyboard.app.ai.a11y.ScreenContext
import com.aikeyboard.app.ai.a11y.ScreenReaderResult
import com.aikeyboard.app.ai.a11y.TextNode

/**
 * Phase 7a's screen reader. Lives in the fdroid source set; the play flavor
 * never compiles or ships this class. The manifest entry that registers it
 * with the system is in `app/src/fdroid/AndroidManifest.xml`.
 *
 * Press-to-read-once: the service config (`accessibility_service_config.xml`)
 * subscribes to zero accessibility events, so the platform never wakes us up
 * for keystrokes or window changes. Real work happens only when a caller
 * (Phase 7b's wiring) invokes `requestScreenContext()` directly via the
 * `instance` singleton handle.
 *
 * Why a singleton, not a bound service: `AccessibilityService.onBind()` is
 * `final` in the platform — overriding it does not compile, so the standard
 * `bindService()` + `onServiceConnected()` IBinder dance isn't available.
 * `PHASE_REVIEW.md:302` explicitly permits this alternative ("via bound
 * service IBinder OR singleton LiveData for in-process consumption"). The
 * IME and the AccessibilityService run in the same app process by default,
 * so a `@Volatile` static reference is safe; no AIDL or `Parcelable`
 * marshalling is needed.
 *
 * Privacy: this class never logs the `text` / `contentDescription` /
 * `hintText` of any node at any log level. Diagnostic logs are limited to
 * structural metadata (node count, walk duration, focused-input index).
 */
class ScreenReaderService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty. The service config subscribes to zero event
        // types (accessibilityEventTypes=0), so this is never called in
        // practice — the empty override is belt-and-suspenders against config
        // drift introducing a non-empty mask.
    }

    override fun onInterrupt() { /* no-op */ }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "ScreenReaderService connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        // Belt-and-suspenders: if the framework destroys us without onUnbind
        // firing first, make sure we don't leak a stale reference for Phase
        // 7b's wiring to discover.
        instance = null
        super.onDestroy()
    }

    /**
     * Walks the active window's accessibility tree once and returns a
     * snapshot. Synchronous; intended to be called from the main thread.
     * Phase 12 polish may move this to a background thread once we have
     * real-world data on dense-UI walk durations.
     */
    fun requestScreenContext(): ScreenReaderResult {
        val start = SystemClock.elapsedRealtime()
        val root = try {
            rootInActiveWindow
        } catch (t: Throwable) {
            Log.w(TAG, "rootInActiveWindow threw ${t.javaClass.simpleName}")
            return ScreenReaderResult.Failure.UNKNOWN_FAILURE
        } ?: return ScreenReaderResult.Failure.NO_ACTIVE_WINDOW

        val nodes = mutableListOf<TextNode>()
        var focusedIdx = -1
        try {
            walk(root, nodes) { idx -> if (focusedIdx == -1) focusedIdx = idx }
        } catch (t: Throwable) {
            // Catches StackOverflowError too (it extends Throwable). Deeply
            // nested layouts that exhaust the JVM stack therefore degrade
            // gracefully instead of taking down the AccessibilityService.
            Log.w(TAG, "walk threw ${t.javaClass.simpleName} (count=${nodes.size})")
            return ScreenReaderResult.Failure.UNKNOWN_FAILURE
        } finally {
            // recycle() is deprecated on API 33+ (no-op there) but mandatory
            // on API 29-32 to return AccessibilityNodeInfo objects to the
            // native pool. minSdk=29 makes this load-bearing for older
            // devices; @Suppress is required because compileSdk=36.
            @Suppress("DEPRECATION")
            root.recycle()
        }

        val above = if (focusedIdx > 0)
            nodes.subList(0, focusedIdx).joinToString(separator = "\n") { it.text }
        else
            ""
        val focusedText = if (focusedIdx >= 0) nodes[focusedIdx].text else ""

        val ctx = ScreenContext(
            nodes = nodes.toList(),
            focusedInputIndex = focusedIdx,
            aboveInputText = above,
            focusedInputText = focusedText,
            nodeCount = nodes.size,
            walkDurationMs = SystemClock.elapsedRealtime() - start,
        )
        Log.d(
            TAG,
            "Walk complete: ${ctx.nodeCount} nodes in ${ctx.walkDurationMs}ms " +
                "(focusedIdx=${ctx.focusedInputIndex})"
        )
        return ScreenReaderResult.Success(ctx)
    }

    /**
     * Depth-first traversal with a strict node count cap to avoid runaway
     * walks on pathological pages (large RecyclerViews, long content lists).
     * The `MAX_NODES = 2000` cap is a starting point; Phase 12 may revisit.
     */
    private fun walk(node: AccessibilityNodeInfo, out: MutableList<TextNode>, onFocused: (Int) -> Unit) {
        if (out.size >= MAX_NODES) return
        // Prefer node.text; fall back to contentDescription (covers
        // icon-only buttons + ImageView labels). DO NOT fall back to
        // hintText — that reveals form-field structure ("Enter password",
        // "Credit card number") even when the field is empty, which is a
        // privacy regression beyond what users expect from "read what's on
        // screen."
        val text = node.text?.toString().orEmpty()
            .ifEmpty { node.contentDescription?.toString().orEmpty() }
        val isInput = node.isEditable
        val isFocused = node.isAccessibilityFocused || node.isFocused
        if (text.isNotEmpty() || isInput) {
            val idx = out.size
            out.add(TextNode(text, isInput, isFocused, node.className?.toString().orEmpty()))
            if (isFocused && isInput) onFocused(idx)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, out, onFocused)
            // Required on API 29-32 to return the child reference to the
            // pool. We don't recycle the parent here — `node` is owned by
            // the caller (or by the framework, for the root).
            @Suppress("DEPRECATION")
            child.recycle()
        }
    }

    companion object {
        private const val TAG = "ScreenReaderService"
        private const val MAX_NODES = 2_000

        /**
         * Process-singleton handle. Set on `onServiceConnected`, cleared on
         * `onUnbind` AND `onDestroy`. Phase 7b's wiring uses this directly
         * (no `bindService()` dance — `onBind` is final on
         * `AccessibilityService`). Volatile so a write on the main thread is
         * visible to a read on any other thread without explicit
         * synchronization.
         */
        @Volatile
        var instance: ScreenReaderService? = null
            private set
    }
}
