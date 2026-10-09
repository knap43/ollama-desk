package io.github.ollamadesk.remote

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Notifications for when the app isn't on screen: approval requests and finished replies. */
class Notifier(private val context: Context) {
    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(APPROVALS, "Approval requests", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When the model wants to do something that needs your OK"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(REPLIES, "Finished replies", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a reply you were waiting for is ready"
            }
        )
    }

    private fun allowed(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    private fun openApp(chat: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (chat != null) putExtra(MainActivity.EXTRA_CHAT, chat)
        }
        return PendingIntent.getActivity(
            context, chat?.hashCode() ?: 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun approval(id: String, chat: String, title: String, detail: String) {
        if (!allowed()) return
        val notification = NotificationCompat.Builder(context, APPROVALS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openApp(chat))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
        } catch (_: SecurityException) {
        }
    }

    fun cancel(id: String) = NotificationManagerCompat.from(context).cancel(id.hashCode())

    fun reply(chat: String, title: String, text: String) {
        if (!allowed()) return
        val notification = NotificationCompat.Builder(context, REPLIES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(chat))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(chat.hashCode(), notification)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        const val APPROVALS = "approvals"
        const val REPLIES = "replies"
    }
}
