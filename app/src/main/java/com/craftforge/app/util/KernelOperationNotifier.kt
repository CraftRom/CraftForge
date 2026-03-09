package com.craftforge.app.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.craftforge.app.R

object KernelOperationNotifier {
    private const val CHANNEL_ID = "craftforge_kernel_ops"
    private const val NOTIFICATION_ID = 2002

    private fun manager(context: Context): NotificationManager {
        return context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private fun canNotify(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.zram_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.zram_notification_channel_desc)
                setShowBadge(false)
            }
            manager(context).createNotificationChannel(channel)
        }
    }

    fun showProgress(context: Context, text: String, progress: Int) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(context.getString(R.string.zram_notification_title))
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(progress in 0 until 100)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .build()
        manager(context).notify(NOTIFICATION_ID, notification)
    }

    fun showFinished(context: Context, text: String, success: Boolean) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(if (success) android.R.drawable.stat_sys_upload_done else android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.zram_notification_title))
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(false)
            .setProgress(0, 0, false)
            .build()
        manager(context).notify(NOTIFICATION_ID, notification)
    }
}
