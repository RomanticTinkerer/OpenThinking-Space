package com.openthinking.blankassistant.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.openthinking.blankassistant.R
import timber.log.Timber

/**
 * Foreground service (type `microphone`) that exists only to keep the process allowed to use the
 * microphone while a voice note is being recorded.
 *
 * Why: both FOSS recorders this app borrows from (Fossify Voice Recorder's `RecorderService`,
 * Dimowner's `AudioRecordingService`) record inside a microphone foreground service. The assistant
 * session window alone does not reliably count as "in use" for mic access on recent Android, which
 * shows up as a recording that is silent or never starts. The recording itself still happens in
 * [VoiceNoteRecorder]; this service is just the keep-alive.
 *
 * The microphone foreground state is only in effect after [onStartCommand] has called
 * `startForeground`, so [start] takes a callback that fires at that moment; begin recording there.
 */
class RecordingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_recording), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.notif_recording))
            .setOngoing(true)
            .build()
        try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // Still let recording try: it works whenever the session window happens to count as visible.
            Timber.w(e, "startForeground failed; recording without foreground state")
        }
        ready?.let {
            ready = null
            it()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1

        @Volatile
        private var ready: (() -> Unit)? = null

        /** Starts the service; [onReady] runs on the main thread once it is in the foreground. */
        fun start(context: Context, onReady: () -> Unit) {
            ready = onReady
            try {
                context.startForegroundService(Intent(context, RecordingService::class.java))
            } catch (e: Exception) {
                Timber.w(e, "Could not start RecordingService; recording without it")
                ready = null
                onReady()
            }
        }

        fun stop(context: Context) {
            ready = null
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }
}
