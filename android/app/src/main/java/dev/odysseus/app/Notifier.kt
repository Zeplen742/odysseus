package dev.odysseus.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifier {
    const val EXTRA_SESSION = "session_id"
    const val ONGOING_ID = 1
    private const val CH_RUN = "run"
    private const val CH_DONE = "done"

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_RUN, "Active chats", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Finished responses", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openIntent(ctx: Context, sessionId: String?): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (sessionId != null) intent.putExtra(EXTRA_SESSION, sessionId)
        return PendingIntent.getActivity(
            ctx, sessionId?.hashCode() ?: 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Shown by the foreground service for as long as a response is being received. */
    fun ongoing(ctx: Context): Notification =
        NotificationCompat.Builder(ctx, CH_RUN)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Odysseus")
            .setContentText("Receiving a response…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent(ctx, null))
            .build()

    fun finished(ctx: Context, sessionId: String, title: String, text: String) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val n = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openIntent(ctx, sessionId))
            .build()
        try { nm.notify(sessionId.hashCode(), n) } catch (_: SecurityException) {}
    }

    fun clear(ctx: Context, sessionId: String) =
        NotificationManagerCompat.from(ctx).cancel(sessionId.hashCode())
}
