// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.commandrow

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.view.isVisible
import com.aikeyboard.app.ai.a11y.A11yProxy
import com.aikeyboard.app.ai.a11y.ReadRespondConsentActivity
import com.aikeyboard.app.ai.a11y.ReadRespondPromptBuilder
import com.aikeyboard.app.ai.a11y.ScreenContext
import com.aikeyboard.app.ai.a11y.ScreenReaderResult
import com.aikeyboard.app.ai.client.AiClient
import com.aikeyboard.app.ai.client.AiStreamEvent
import com.aikeyboard.app.ai.client.BackendResolver
import com.aikeyboard.app.ai.persona.Persona
import com.aikeyboard.app.ai.preview.PreviewStripView
import com.aikeyboard.app.ai.sticker.StickerCommitter
import com.aikeyboard.app.ai.sticker.StickerStorage
import com.aikeyboard.app.ai.sticker.picker.StickerPickerView
import com.aikeyboard.app.ai.storage.SecureStorage
import com.aikeyboard.app.ai.ui.AiSettingsActivity
import com.aikeyboard.app.ai.ui.AiSettingsRoutes
import com.aikeyboard.app.latin.R
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

class CommandRowController @JvmOverloads constructor(
    private val ime: InputMethodService,
    private val view: CommandRowView,
    private val previewStrip: PreviewStripView,
    private val storage: SecureStorage,
    private val backendResolver: () -> AiClient? = { BackendResolver.resolve(storage) },
) : CommandRowView.Listener, PreviewStripView.Listener {

    private val scope: CoroutineScope = MainScope()
    private var streamJob: Job? = null

    // Captured at the moment the user tapped Rewrite — used at commit-time to put the
    // rewritten text back where it came from. usedSelectionRange is non-null when there
    // was a non-empty selection; the field/range case stores (-1, -1) and is treated as
    // "replace the entire field around the cursor".
    private var usedSelectionRange: IntRange? = null

    private var stickerPicker: StickerPickerView? = null
    // Cached sibling reference. Set in setStickerPicker so picker visibility flips
    // don't have to walk the view tree each time. Both views live in the same
    // LinearLayout in main_keyboard_frame.xml.
    private var keyboardWrapper: View? = null
    private val stickerStorage: StickerStorage = StickerStorage.getInstance(ime)
    private var cachedEditorInfo: EditorInfo? = null

    init {
        view.listener = this
        previewStrip.listener = this
        refreshActivePersona()
    }

    fun refreshActivePersona() {
        val activeId = storage.getActivePersonaId()
        val personas = storage.getPersonas()
        val active = personas.firstOrNull { it.id == activeId } ?: personas.first()
        view.bindActivePersona(active)
    }

    override fun onPersonaSelectorTap(anchor: View) {
        val personas = storage.getPersonas()
        val popup = PopupMenu(ime, anchor)
        personas.forEachIndexed { index, persona ->
            popup.menu.add(0, index, index, persona.name)
        }
        popup.setOnMenuItemClickListener { item ->
            val selected = personas.getOrNull(item.itemId) ?: return@setOnMenuItemClickListener false
            storage.setActivePersonaId(selected.id)
            view.bindActivePersona(selected)
            true
        }
        popup.show()
    }

    override fun onRewriteTap() {
        val ic = ime.currentInputConnection ?: run {
            toast(R.string.ai_rewrite_no_input)
            return
        }

        // Prefer selection if non-empty; fall back to the whole field.
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        val (input, range) = if (selected.isNotEmpty()) {
            val ext = ic.getExtractedText(ExtractedTextRequest(), 0)
            val start = ext?.selectionStart ?: -1
            val end = ext?.selectionEnd ?: -1
            selected to (if (start in 0..end) IntRange(start, end) else null)
        } else {
            val before = ic.getTextBeforeCursor(MAX_INPUT_CHARS, 0)?.toString().orEmpty()
            val after = ic.getTextAfterCursor(MAX_INPUT_CHARS, 0)?.toString().orEmpty()
            (before + after) to null
        }
        if (input.isBlank()) {
            toast(R.string.ai_rewrite_no_input)
            return
        }

        val backend = backendResolver()
        if (backend == null) {
            toast(R.string.ai_rewrite_no_backend)
            return
        }

        val persona = storage.getPersonas().firstOrNull { it.id == storage.getActivePersonaId() }
            ?: storage.getPersonas().first()

        usedSelectionRange = range
        previewStrip.startStream()
        // The visibility flip widens the touchable region; LatinIME.onComputeInsets re-runs
        // when the system next polls insets. View.requestLayout pokes a layout pass that
        // ultimately triggers WindowManager to query insets again.
        previewStrip.requestLayout()
        streamJob = scope.launch(Dispatchers.Main) {
            try {
                backend.rewrite(input, persona.systemPrompt, persona.fewShots)
                    .onCompletion { /* nothing on cancel — preview already cleaned by cancel() */ }
                    .collect { event ->
                        when (event) {
                            is AiStreamEvent.Delta -> previewStrip.appendDelta(event.text)
                            AiStreamEvent.Done -> previewStrip.markDone()
                            is AiStreamEvent.Error -> previewStrip.showError(event.message)
                        }
                    }
            } catch (t: Throwable) {
                // Coroutine cancellation re-throws CancellationException, which is also a Throwable.
                // The flow's emit-on-error wraps network failures, but if anything else slips
                // through (e.g. construction error) surface it rather than failing silently.
                if (t !is kotlinx.coroutines.CancellationException) {
                    // Phase 7b incidental fix: t.message can echo URL fragments, response-body
                    // bytes, or prompt content. Use a static localized string instead.
                    previewStrip.showError(ime.getString(R.string.ai_rewrite_stream_failed))
                    Log.w(TAG, "Rewrite stream failed: ${t.javaClass.simpleName}")
                }
            }
        }
    }

    /** Cancels any in-flight rewrite and hides the preview strip. */
    fun cancelStreamIfActive() {
        val job = streamJob
        if (job != null && job.isActive) {
            job.cancel()
        }
        streamJob = null
        usedSelectionRange = null
        if (previewStrip.isVisible) {
            previewStrip.hide()
        }
    }

    override fun onCommitTap(text: String) {
        val ic = ime.currentInputConnection
        if (ic != null && text.isNotEmpty()) {
            val range = usedSelectionRange
            if (range != null && range.first >= 0 && range.last >= range.first) {
                ic.setSelection(range.first, range.last)
                ic.commitText(text, 1)
            } else {
                // Whole-field replacement: nuke the surrounding text and commit the rewrite at
                // the cursor. Some fields cap MAX so we use a generous but bounded window.
                ic.deleteSurroundingText(MAX_INPUT_CHARS, MAX_INPUT_CHARS)
                ic.commitText(text, 1)
            }
        }
        streamJob = null
        usedSelectionRange = null
        previewStrip.hide()
    }

    override fun onCancelTap() {
        cancelStreamIfActive()
    }

    override fun onReadRespondTap() {
        // Walk first; consent and backend checks happen after we know there
        // IS something to read. Saves the user a consent prompt if they're
        // on a screen with no content above the cursor.
        val result = A11yProxy.requestScreenContext()
        when (result) {
            is ScreenReaderResult.Success -> handleSuccessfulWalk(result.context)
            ScreenReaderResult.Failure.SERVICE_NOT_ENABLED -> {
                toast(R.string.ai_read_respond_service_not_enabled)
                toast(R.string.ai_read_respond_settings_hint)
                openAccessibilitySettings()
            }
            ScreenReaderResult.Failure.NO_ACTIVE_WINDOW ->
                toast(R.string.ai_read_respond_no_active_window)
            ScreenReaderResult.Failure.BUILD_DOES_NOT_SUPPORT ->
                toast(R.string.ai_read_respond_not_supported_play)
            ScreenReaderResult.Failure.UNKNOWN_FAILURE ->
                toast(R.string.ai_read_respond_walk_failed)
        }
    }

    private fun handleSuccessfulWalk(context: ScreenContext) {
        if (context.aboveInputText.isBlank()) {
            toast(R.string.ai_read_respond_no_context)
            return
        }
        if (!storage.isReadRespondConsented()) {
            launchConsentActivity()
            return
        }
        val backend = backendResolver()
        if (backend == null) {
            toast(R.string.ai_rewrite_no_backend)
            return
        }
        val persona = storage.getPersonas().firstOrNull { it.id == storage.getActivePersonaId() }
            ?: storage.getPersonas().first()
        val (input, systemPrompt) = ReadRespondPromptBuilder.build(
            aboveInputText = context.aboveInputText,
            focusedInputText = context.focusedInputText,
            persona = persona,
        )
        // Read & Respond replaces the entire field on commit (no selection-range
        // anchoring; Rewrite preserves selection, this doesn't).
        usedSelectionRange = null
        previewStrip.startStream()
        previewStrip.requestLayout()
        streamJob = scope.launch(Dispatchers.Main) {
            try {
                backend.rewrite(input, systemPrompt, fewShots = emptyList())
                    .onCompletion { /* preview already cleaned by cancel() */ }
                    .collect { event ->
                        when (event) {
                            is AiStreamEvent.Delta -> previewStrip.appendDelta(event.text)
                            AiStreamEvent.Done -> previewStrip.markDone()
                            is AiStreamEvent.Error -> previewStrip.showError(event.message)
                        }
                    }
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) {
                    // Privacy: log type only — never `input`, `context.*`, or `t.message`.
                    // Read & Respond input is screen content the user didn't type, so we
                    // are stricter than `onRewriteTap`'s mirror-image catch block.
                    Log.w(TAG, "Read & Respond stream failed: ${t.javaClass.simpleName}")
                    previewStrip.showError(
                        ime.getString(R.string.ai_read_respond_stream_failed)
                    )
                }
            }
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ime.startActivity(intent) }
            .onFailure {
                Log.w(TAG, "Could not open ACCESSIBILITY_SETTINGS: ${it.javaClass.simpleName}")
            }
    }

    private fun launchConsentActivity() {
        val intent = Intent(ime, ReadRespondConsentActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ime.startActivity(intent) }
            .onFailure {
                Log.w(TAG, "Could not launch consent activity: ${it.javaClass.simpleName}")
            }
    }

    override fun onStickerTap() {
        if (stickerPicker == null) return
        refreshPickerData()
        showPicker()
    }

    fun setStickerPicker(view: StickerPickerView) {
        stickerPicker = view
        keyboardWrapper = (view.parent as? ViewGroup)?.findViewById(R.id.keyboard_view_wrapper)
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
    }

    /** Invoked from LatinIME.onStartInputViewInternal so the picker is dismissed
     *  on every input transition (including transitions where editorInfo is null).
     *  cachedEditorInfo gives commitSticker a stable handle even if
     *  getCurrentInputEditorInfo() momentarily returns null mid-transition. */
    fun onInputStarted(editorInfo: EditorInfo?) {
        cachedEditorInfo = editorInfo
        if (stickerPicker?.isVisible == true) hidePicker()
    }

    private fun refreshPickerData() {
        val picker = stickerPicker ?: return
        val packs = stickerStorage.getManifest().packs
        picker.bind(packs) { packId ->
            File(ime.filesDir, "stickers/packs/$packId")
        }
    }

    private fun showPicker() {
        stickerPicker?.visibility = View.VISIBLE
        keyboardWrapper?.visibility = View.GONE
    }

    private fun hidePicker() {
        stickerPicker?.visibility = View.GONE
        keyboardWrapper?.visibility = View.VISIBLE
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
        // Hide BEFORE startActivity so the user doesn't see a flash of the picker
        // while the IME deactivates.
        hidePicker()
        val intent = Intent(ime, AiSettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(AiSettingsActivity.EXTRA_DEEP_LINK_ROUTE, AiSettingsRoutes.STICKERS_LIST)
        ime.startActivity(intent)
    }

    override fun onSettingsTap() {
        launchSettings()
    }

    override fun onSettingsLongPress() {
        launchSettings()
    }

    fun dispose() {
        scope.cancel()
        streamJob = null
    }

    private fun launchSettings() {
        val intent = Intent(ime, AiSettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ime.startActivity(intent)
    }

    private fun toast(resId: Int) {
        Toast.makeText(ime, resId, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "AiCommandRow"
        // Cap text we ship to the LLM. Most apps' fields are far smaller; this keeps a runaway
        // text field from blowing the request budget.
        private const val MAX_INPUT_CHARS = 8_000
    }
}
