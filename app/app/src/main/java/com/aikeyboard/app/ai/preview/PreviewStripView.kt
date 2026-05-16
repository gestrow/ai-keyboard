// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.preview

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.aikeyboard.app.latin.R
import com.aikeyboard.app.latin.common.ColorType
import com.aikeyboard.app.latin.settings.Settings

/**
 * Streaming preview surface, rendered between our command row and HeliBoard's strip_container.
 * Hidden by default; the controller drives lifecycle via {@link #startStream}, {@link #appendDelta},
 * {@link #markDone}, {@link #showError}, and {@link #hide}.
 *
 * Like {@code CommandRowView}, sources colors from the active keyboard theme through
 * {@code Settings.getValues().mColors} so it merges with whichever theme the user picked.
 *
 * Insets contract: {@code LatinIME.onComputeInsets} adds this view's height to the touchable
 * region whenever its visibility is {@code VISIBLE}; the controller calls
 * {@link Listener#onVisibilityWillChange} before flipping visibility so the IME refreshes its
 * touchable region in time.
 */
class PreviewStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    interface Listener {
        fun onCommitTap(text: String)
        fun onCancelTap()
    }

    var listener: Listener? = null

    private val streamText: TextView
    private val hintText: TextView
    private val cancelBtn: ImageButton

    private enum class State { HIDDEN, STREAMING, DONE, ERROR }
    private var state: State = State.HIDDEN

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // Generous padding for legibility; matches the strip's vertical density.
        setPadding(dp(8), dp(4), dp(4), dp(4))

        streamText = TextView(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(4)
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(8), 0, dp(8), 0)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            isSingleLine = false
        }
        addView(streamText)

        hintText = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(4), 0, dp(8), 0)
            text = context.getString(R.string.ai_preview_strip_tap_to_commit)
            visibility = GONE
        }
        addView(hintText)

        cancelBtn = ImageButton(context).apply {
            layoutParams = LayoutParams(dp(40), LayoutParams.MATCH_PARENT)
            setPadding(dp(8), 0, dp(8), 0)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.ic_preview_close)
            contentDescription = context.getString(R.string.ai_preview_strip_cancel_desc)
            background = null
            setOnClickListener {
                listener?.onCancelTap()
                hide()
            }
        }
        addView(cancelBtn)

        // The TextView consumes the commit-on-tap gesture only when the stream is finished.
        streamText.setOnClickListener {
            if (state == State.DONE) listener?.onCommitTap(streamText.text.toString())
        }

        applyKeyboardTheme()
        // Default hidden so we don't take any space until startStream() shows us.
        visibility = GONE
    }

    /**
     * Re-applies HeliBoard's runtime theme colors. Mirrors CommandRowView's pattern. Called
     * from {@code init} and again whenever the keyboard theme changes (controller re-binds).
     */
    fun applyKeyboardTheme() {
        val colors = Settings.getValues().mColors
        colors.setBackground(this, ColorType.STRIP_BACKGROUND)

        val keyTextColor = colors.get(ColorType.KEY_TEXT)
        streamText.setTextColor(keyTextColor)
        // 50%-alpha KEY_TEXT for the secondary "tap to commit" hint, since HeliBoard
        // doesn't expose a dedicated hint color enum (KEY_HINT_LETTER, mentioned in the
        // prompt, doesn't exist on this fork). MORE_SUGGESTIONS_HINT is dimmed-key-text in
        // most themes — close, but visually noisier; the alpha-blended KEY_TEXT is cleanest.
        hintText.setTextColor(ColorUtils.setAlphaComponent(keyTextColor, 128))

        colors.setColor(cancelBtn, ColorType.TOOL_BAR_KEY)
        colors.setBackground(cancelBtn, ColorType.STRIP_BACKGROUND)
    }

    fun startStream() {
        state = State.STREAMING
        streamText.text = ""
        streamText.setTextColor(Settings.getValues().mColors.get(ColorType.KEY_TEXT))
        cancelBtn.visibility = VISIBLE
        hintText.visibility = GONE
        visibility = VISIBLE
    }

    fun appendDelta(text: String) {
        if (state != State.STREAMING) return
        streamText.append(text)
    }

    fun markDone() {
        if (state == State.HIDDEN) return
        state = State.DONE
        // Keep cancelBtn visible alongside the "tap to commit" hint so the user
        // can dismiss a finished suggestion without committing it. Click handler
        // routes through onCancelTap() → cancelStreamIfActive(), which no-ops
        // on an already-finished streamJob.
        cancelBtn.visibility = VISIBLE
        hintText.visibility = VISIBLE
    }

    fun showError(message: String) {
        state = State.ERROR
        streamText.text = message
        // Pure red is hard to read on dark themes; soften with alpha so it stays legible
        // against STRIP_BACKGROUND in both light and dark themes.
        streamText.setTextColor(Color.rgb(0xE5, 0x39, 0x35))
        hintText.visibility = GONE
        cancelBtn.visibility = VISIBLE
        visibility = VISIBLE
    }

    fun hide() {
        state = State.HIDDEN
        streamText.text = ""
        hintText.visibility = GONE
        cancelBtn.visibility = VISIBLE
        visibility = GONE
    }

    fun currentText(): String = streamText.text.toString()

    fun isStreaming(): Boolean = state == State.STREAMING

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
