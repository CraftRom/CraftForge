package com.craftforge.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.craftforge.app.service.TweaksService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {

            val prefs = context.getSharedPreferences("TweaksPrefs", Context.MODE_PRIVATE)
            val runOnBoot = prefs.getBoolean("run_on_boot", false)

            if (runOnBoot) {
                val serviceIntent = Intent(context, TweaksService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}