// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.a11y

// Phase 7a smoke-test harness. fdroidDebug source set: physically absent
// from play APKs (no fdroid pieces) AND from fdroid release APKs (no debug
// pieces). The manifest entry that turns it into a launchable activity
// lives in `app/src/fdroidDebug/AndroidManifest.xml`.
//
// Pattern follows Phase 4's TermuxValidationActivity, but with one
// improvement: that activity's *class* lived in `src/main/`, gated only by
// its `src/debug/` manifest entry. This one's class AND manifest entry are
// both in fdroidDebug so the class is physically absent from play and from
// fdroid release dex.
//
// Result rendering deliberately shows ONLY structural metadata and a single
// 80-char snippet of focusedInputText for verification. The full nodes /
// aboveInputText content is never displayed, never logged, and never
// written to a file — the on-device privacy invariant must be observable
// from logcat AND from anything visible on screen.

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.aikeyboard.app.ai.a11y.ScreenReaderResult

@SuppressLint("SetTextI18n")
class ScreenReaderTestActivity : ComponentActivity() {

    private lateinit var statusView: TextView
    private lateinit var detailsView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        renderIdle()
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 96)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        statusView = TextView(this).apply {
            textSize = 18f
            setPadding(0, 0, 0, 32)
        }
        detailsView = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            movementMethod = ScrollingMovementMethod()
        }
        val readButton = Button(this).apply {
            text = "Read screen in 3s"
            setOnClickListener { v ->
                statusView.text = "Switch to another app now…"
                detailsView.text = ""
                v.postDelayed({ runWalk() }, 3_000L)
            }
        }
        root.addView(statusView)
        root.addView(readButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 32 })
        val scroll = ScrollView(this).apply {
            addView(detailsView)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(scroll)
        val footer = TextView(this).apply {
            text = "Enable: Settings → Accessibility → AI Keyboard debug"
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        }
        root.addView(footer)
        return root
    }

    private fun renderIdle() {
        statusView.text = "Idle. Tap to walk the active window's a11y tree."
        detailsView.text = ""
    }

    private fun runWalk() {
        val service = ScreenReaderService.instance
        if (service == null) {
            statusView.text = "❌ Service not enabled — open System Settings → Accessibility → AI Keyboard debug"
            detailsView.text = ""
            return
        }
        when (val result = service.requestScreenContext()) {
            is ScreenReaderResult.Success -> {
                val ctx = result.context
                statusView.text = "✅ Walk complete: ${ctx.nodeCount} nodes / ${ctx.walkDurationMs}ms"
                // Single 80-char snippet of focused input — debug-only
                // verification. NOT logged. The full text and aboveInputText
                // content are intentionally NOT rendered.
                val focusedSnippet = ctx.focusedInputText.take(80)
                detailsView.text = buildString {
                    append("nodeCount=").append(ctx.nodeCount).append('\n')
                    append("walkDurationMs=").append(ctx.walkDurationMs).append('\n')
                    append("focusedInputIndex=").append(ctx.focusedInputIndex).append('\n')
                    append("nodes.size=").append(ctx.nodes.size).append('\n')
                    append("focusedInputText (≤80c)=").append(focusedSnippet).append('\n')
                    if (ctx.focusedInputText.length > 80) append("…\n")
                }
            }
            ScreenReaderResult.Failure.SERVICE_NOT_ENABLED -> {
                statusView.text = "❌ Service not enabled"
                detailsView.text = "Open System Settings → Accessibility → Installed apps → AI Keyboard debug"
            }
            ScreenReaderResult.Failure.NO_ACTIVE_WINDOW -> {
                statusView.text = "❌ No active window"
                detailsView.text = "rootInActiveWindow returned null. Foreground a different app and try again."
            }
            ScreenReaderResult.Failure.BUILD_DOES_NOT_SUPPORT -> {
                // Defensive: should be unreachable in this debug build
                statusView.text = "❌ Build does not support a11y"
                detailsView.text = "BuildConfig.ENABLE_A11Y=false"
            }
            ScreenReaderResult.Failure.UNKNOWN_FAILURE -> {
                statusView.text = "❌ Unknown failure during walk"
                detailsView.text = "Check `adb logcat -s ScreenReaderService` for details."
            }
        }
    }
}
