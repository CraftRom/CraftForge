package com.craftforge.app.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

object PermissionManager {

    /**
     * Повний список дозволів для DeviceInfoProvider
     */
    fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf<String>()

        // 1. Сповіщення (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // 2. Bluetooth (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        // 3. Стан мережі та оператора (getReadableNetworkType)
        permissions.add(Manifest.permission.READ_PHONE_STATE)

        // 4. Wi-Fi та мережевий стан (getWifiSpeed, getWifiStandard)
        permissions.add(Manifest.permission.ACCESS_WIFI_STATE)
        permissions.add(Manifest.permission.ACCESS_NETWORK_STATE)

        // 5. Камери (getCameraMegapixels)
        permissions.add(Manifest.permission.CAMERA)

        return permissions.toTypedArray()
    }

    /**
     * Перевірка, чи всі базові дозволи надані
     */
    fun hasAllPermissions(context: Context): Boolean {
        return getRequiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Спеціальний дозвіл для запису системних налаштувань (WRITE_SETTINGS)
     */
    fun canWriteSettings(context: Context): Boolean {
        return Settings.System.canWrite(context)
    }

    fun openWriteSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}