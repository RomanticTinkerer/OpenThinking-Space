package com.openthinking.blankassistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.openthinking.blankassistant.audio.RecordingService
import com.openthinking.blankassistant.audio.VoiceNoteRecorder
import com.openthinking.blankassistant.storage.CaptureStore
import com.openthinking.blankassistant.ui.CaptureOverlayView
import com.openthinking.blankassistant.ui.CaptureOverlayView.NoteState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * One assistant invocation (hold power button, etc.).
 *
 * The system hands us a screenshot of whatever was on screen via [onHandleScreenshot]; we show it in
 * [CaptureOverlayView] and let the user Edit it, attach a voice note, or just Save it.
 *
 * Nothing is written to shared storage until the user chooses Edit or Save; dismissing discards
 * everything, including any in-progress recording.
 */
class AssistSession(sessionContext: Context) :
    VoiceInteractionSession(sessionContext),
    CaptureOverlayView.Listener,
    VoiceNoteRecorder.Callbacks {

    private enum class Pending { SAVE, EDIT }

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val overlay by lazy { CaptureOverlayView(context, this) }

    private var screenshot: Bitmap? = null
    // Set once the screenshot has been written; the voice note is named after it.
    private var savedImageUri: Uri? = null
    private var savedBaseName: String? = null

    private var recorder: VoiceNoteRecorder? = null
    private var noteFile: File? = null
    private var noteDurationMs = 0L

    /** What to do as soon as an in-flight recording has been finalised (the user tapped Save/Edit mid-recording). */
    private var pending: Pending? = null
    private var busy = false
    private var screenshotTimeout: Job? = null

    override fun onCreateContentView(): View = overlay

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        // The default session window is a wrap_content bottom panel; the zoom-out animation needs the
        // whole screen, drawn edge to edge over a transparent window.
        window.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDecorFitsSystemWindows(false)
        }

        resetState()
        overlay.prepare()
        if (showFlags and SHOW_WITH_SCREENSHOT == 0) {
            // The system will not send a screenshot; don't wait for one.
            overlay.showUnavailable()
        }

        // With "Use screenshot" switched off the system may never call onHandleScreenshot at all,
        // so don't leave the user looking at a dimmed screen: explain after a short wait. A late
        // screenshot still replaces the message.
        screenshotTimeout?.cancel()
        screenshotTimeout = uiScope.launch {
            delay(SCREENSHOT_TIMEOUT_MS)
            if (screenshot == null) overlay.showUnavailable()
        }
    }

    /** Null when "Use screenshot" is off, or the foreground window is FLAG_SECURE. */
    override fun onHandleScreenshot(screenshot: Bitmap?) {
        if (screenshot == null) {
            overlay.showUnavailable()
            return
        }
        this.screenshot = screenshot
        overlay.showScreenshot(screenshot)
    }

    override fun onHide() {
        super.onHide()
        releaseCapture()
    }

    override fun onDestroy() {
        releaseCapture()
        uiScope.cancel()
        super.onDestroy()
    }

    override fun onBackPressed() {
        finish()
    }

    // --- CaptureOverlayView.Listener ---------------------------------------------------------

    override fun onDismiss() = finish()

    override fun onRecordToggle() {
        val active = recorder
        if (active != null) {
            // Tap while recording = stop. Taps during the brief startup (service coming up) are ignored.
            if (active.isActive) active.stop()
            return
        }
        startRecording()
    }

    /** Reset: throw this take away and start a fresh one. */
    override fun onRecordReset() {
        recorder?.discard()
        recorder = null
        RecordingService.stop(context)
        startRecording()
    }

    override fun onSave() = completeWith(Pending.SAVE)

    override fun onEdit() = completeWith(Pending.EDIT)

    // --- VoiceNoteRecorder.Callbacks ---------------------------------------------------------

    override fun onProgress(durationMs: Long, amplitude: Int) {
        overlay.setRecordingProgress(durationMs, amplitude)
    }

    override fun onFinished(file: File, durationMs: Long) {
        recorder = null
        RecordingService.stop(context)
        noteFile = file
        noteDurationMs = durationMs
        overlay.setNoteState(NoteState.RECORDED, durationMs)
        runPending()
    }

    override fun onFailed() {
        recorder = null
        RecordingService.stop(context)
        overlay.setNoteState(NoteState.NONE)
        toast(R.string.msg_record_failed)
        // If the user had already asked to save, still save the screenshot without a note.
        runPending()
    }

    // --- Recording ---------------------------------------------------------------------------

    private fun startRecording() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // An assistant overlay can't show a permission dialog; send the user to the setup screen.
            toast(R.string.msg_need_mic)
            startActivityFromSession(
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_REQUEST_MIC, true),
            )
            finish()
            return
        }
        // Re-recording replaces the previous note.
        noteFile?.delete()
        noteFile = null

        val newRecorder = VoiceNoteRecorder(context, this)
        recorder = newRecorder
        overlay.setNoteState(NoteState.RECORDING, 0L)
        // Mic access needs the microphone foreground service up first; it calls back when ready.
        // Success or failure of the recording itself is reported through the callbacks.
        RecordingService.start(context) {
            if (recorder === newRecorder) newRecorder.start() else RecordingService.stop(context)
        }
    }

    // --- Save / Edit -------------------------------------------------------------------------

    private fun completeWith(action: Pending) {
        if (busy || screenshot == null) return
        busy = true
        overlay.setBusy(true)
        pending = action

        val active = recorder
        if (active != null && active.isActive) {
            // Finish the note first; onFinished/onFailed resumes via runPending().
            active.stop()
        } else {
            runPending()
        }
    }

    private fun runPending() {
        val action = pending ?: return
        pending = null
        val bitmap = screenshot ?: return
        val note = noteFile

        uiScope.launch {
            val (imageUri, noteSaved) = withContext(Dispatchers.IO) {
                if (savedImageUri == null) {
                    CaptureStore.saveScreenshot(context, bitmap)?.let { (uri, base) ->
                        savedImageUri = uri
                        savedBaseName = base
                    }
                }
                val base = savedBaseName
                val noteUri = if (savedImageUri != null && base != null && note != null) {
                    CaptureStore.saveVoiceNote(context, note, base)
                } else {
                    null
                }
                savedImageUri to (noteUri != null)
            }

            if (imageUri == null) {
                toast(R.string.msg_save_failed)
                busy = false
                overlay.setBusy(false)
                return@launch
            }
            when (action) {
                Pending.SAVE -> toast(if (noteSaved) R.string.msg_saved_with_note else R.string.msg_saved)
                Pending.EDIT -> openInEditor(imageUri)
            }
            finish()
        }
    }

    /**
     * Opens the built-in editor (vendored ReFra markup + crop, module `:editor`) on the saved
     * screenshot. It overwrites the same MediaStore entry when the user saves.
     */
    private fun openInEditor(uri: Uri) {
        val intent = Intent()
            .setClassName(context.packageName, EDITOR_ACTIVITY)
            .setData(uri)
        try {
            startActivityFromSession(intent)
        } catch (e: Exception) {
            Timber.e(e, "Could not open the editor")
            toast(R.string.msg_no_editor)
        }
    }

    // --- Helpers -----------------------------------------------------------------------------

    /**
     * Sessions must start activities through [startAssistantActivity]: it launches into the
     * assistant task on the system's behalf. A plain startActivity() from a session context is
     * treated as a background launch and can be silently dropped.
     */
    private fun startActivityFromSession(intent: Intent) {
        try {
            startAssistantActivity(intent)
        } catch (e: Exception) {
            Timber.w(e, "startAssistantActivity failed; falling back to startActivity")
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun resetState() {
        releaseCapture()
        screenshot = null
        savedImageUri = null
        savedBaseName = null
        pending = null
        busy = false
    }

    /** Stops any recording and deletes the temporary note; safe to call repeatedly. */
    private fun releaseCapture() {
        recorder?.discard()
        recorder = null
        RecordingService.stop(context)
        noteFile?.delete()
        noteFile = null
        noteDurationMs = 0L
    }

    private fun toast(resId: Int) {
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val SCREENSHOT_TIMEOUT_MS = 1_500L
        const val EDITOR_ACTIVITY = "com.openthinking.blankassistant.editor.EditorActivity"
    }
}
