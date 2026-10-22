package com.openthinking.blankassistant.audio

import android.content.Context
import android.media.MediaRecorder
import com.dimowner.audiorecorder.v2.audio.AudioInput
import com.dimowner.audiorecorder.v2.audio.AudioRecorderV2
import com.dimowner.audiorecorder.v2.audio.RecorderEvent
import com.dimowner.audiorecorder.v2.audio.RecorderV2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * One-shot voice-note recorder on top of the vendored Dimowner/AudioRecorder `MediaRecorder`
 * engine (AAC in an MPEG-4/.m4a container).
 *
 * The engine reports everything through a [RecorderV2.subscribeRecorderEvents] flow with no
 * replay, so this class subscribes *before* starting; it then turns the event stream into three
 * simple callbacks. All callbacks run on the main thread.
 *
 * Lifecycle: [start] -> any number of [Callbacks.onProgress] -> exactly one of
 * [Callbacks.onFinished] / [Callbacks.onFailed] (unless [discard] was called, which is silent).
 */
class VoiceNoteRecorder(
    context: Context,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onProgress(durationMs: Long, amplitude: Int)

        /** The file is closed and playable. The callee now owns it (and must delete it). */
        fun onFinished(file: File, durationMs: Long)

        fun onFailed()
    }

    private val appContext = context.applicationContext
    private val engine: RecorderV2 = AudioRecorderV2(appContext, engineScope)

    private var eventsJob: Job? = null
    private var file: File? = null
    private var durationMs = 0L
    private var delivered = false
    private var discarded = false

    /** True from [start] until the engine has finished writing the file (or failed). */
    val isActive: Boolean get() = file != null && !delivered

    fun start(): Boolean {
        if (isActive) return false
        val out = File(appContext.cacheDir, "voice-note-${System.currentTimeMillis()}.m4a")
        try {
            // The engine insists on an existing, empty file.
            if (!out.createNewFile()) throw IOException("File already exists: $out")
        } catch (e: IOException) {
            Timber.e(e, "Could not create the recording file")
            callbacks.onFailed()
            return false
        }
        file = out
        durationMs = 0L
        delivered = false
        discarded = false

        // UNDISPATCHED: collection must be registered before start(), or early events are lost.
        eventsJob = uiScope.launch(start = CoroutineStart.UNDISPATCHED) {
            engine.subscribeRecorderEvents().collect(::onEvent)
        }
        return engine.startRecording(
            outputFile = out,
            channelCount = 1,
            sampleRate = SAMPLE_RATE_HZ,
            bitrate = BITRATE_BPS,
            maxRecordingDurationMills = MAX_DURATION_MS,
            audioInput = AudioInput.Mic(MediaRecorder.AudioSource.MIC),
        )
    }

    /** Ends the recording. The result arrives via [Callbacks.onFinished]. */
    fun stop() {
        if (!isActive) return
        if (!engine.stopRecording()) finish(success = false)
    }

    /** Ends the recording and throws the audio away without any callback. */
    fun discard() {
        if (!isActive) return
        discarded = true
        // The file is deleted once the engine reports that it has closed it.
        if (!engine.stopRecording()) finish(success = false)
    }

    private fun onEvent(event: RecorderEvent) {
        when (event) {
            is RecorderEvent.OnRecordingProgress -> {
                durationMs = event.durationMills
                if (!discarded) callbacks.onProgress(event.durationMills, event.amplitude)
            }
            // The engine emits the stop event only after the container is closed. A failure that
            // captured audio emits OnStopRecording first, so the audio is kept.
            RecorderEvent.OnStopRecording,
            RecorderEvent.OnMaxDurationReached -> finish(success = true)
            is RecorderEvent.OnError -> finish(success = false)
            RecorderEvent.OnStartRecording,
            RecorderEvent.OnPauseRecording,
            RecorderEvent.OnResumeRecording -> Unit
        }
    }

    private fun finish(success: Boolean) {
        if (delivered) return
        delivered = true
        eventsJob?.cancel()
        eventsJob = null
        val recorded = file
        file = null
        if (success && !discarded && recorded != null && recorded.length() > 0L) {
            callbacks.onFinished(recorded, durationMs)
        } else {
            recorded?.delete()
            if (!discarded) callbacks.onFailed()
        }
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 44_100
        const val BITRATE_BPS = 96_000
        const val MAX_DURATION_MS = 5 * 60 * 1000

        /**
         * Process-wide on purpose: the engine finalises the file (and releases the microphone) on
         * this scope after stop(), which must still happen if the assistant session is already gone.
         */
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
