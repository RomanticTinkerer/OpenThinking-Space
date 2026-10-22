package com.openthinking.blankassistant.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.openthinking.blankassistant.R
import com.openthinking.blankassistant.ViewerActivity
import com.openthinking.blankassistant.storage.MemoryMeta

/**
 * "Remind me about this screenshot": one inexact alarm per screenshot that posts a notification
 * opening it. ponytail: alarms don't survive a reboot; re-arm from [MemoryMeta] on BOOT_COMPLETED if that matters.
 */
object Reminders {
    private const val CHANNEL_ID = "reminders"
    private const val EXTRA_BASE = "base"

    fun schedule(context: Context, image: Uri, base: String, at: Long) {
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(context, image, base))
        MemoryMeta(context).setReminderAt(base, at)
    }

    fun cancel(context: Context, image: Uri, base: String) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context, image, base))
        MemoryMeta(context).setReminderAt(base, 0L)
    }

    // Same data Uri + class => same PendingIntent, so cancel() finds what schedule() set.
    private fun alarmIntent(context: Context, image: Uri, base: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        base.hashCode(),
        Intent(context, ReminderReceiver::class.java).setData(image).putExtra(EXTRA_BASE, base),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    internal fun notify(context: Context, image: Uri, base: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel_reminders), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context,
            base.hashCode(),
            Intent(context, ViewerActivity::class.java).setData(image),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val caption = MemoryMeta(context).caption(base)
        nm.notify(
            base.hashCode(),
            android.app.Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(context.getString(R.string.notif_reminder))
                .setContentText(caption.ifBlank { context.getString(R.string.notif_reminder_body) })
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
        MemoryMeta(context).setReminderAt(base, 0L)
    }

    class ReminderReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val image = intent.data ?: return
            val base = intent.getStringExtra(EXTRA_BASE) ?: return
            notify(context, image, base)
        }
    }
}
