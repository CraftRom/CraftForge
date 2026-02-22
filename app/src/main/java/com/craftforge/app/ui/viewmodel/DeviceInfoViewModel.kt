package com.craftforge.app.ui.viewmodel

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.craftforge.app.data.DeviceInfoProvider
import com.craftforge.app.data.models.BatteryData
import com.craftforge.app.data.models.RamData
import com.craftforge.app.data.models.StorageData
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DeviceInfoViewModel(application: Application) : AndroidViewModel(application) {

    @SuppressLint("StaticFieldLeak")
    private val context = application.applicationContext
    private val provider = DeviceInfoProvider(context)
    private val maxHistory = 50
    private val updateIntervalMs = 1500L
    private val _ramHistory = MutableStateFlow<List<Float>>(emptyList())

    val ramData = MutableStateFlow(
        RamData(totalMb = 0, usedMb = 0, freeMb = 0, usedPercent = 0, history = emptyList())
    )

    val storageData = MutableStateFlow(
        StorageData(usedPercent = 0, freeGb = 0.0, totalGb = 0.0)
    )

    val batteryData = MutableStateFlow(
        BatteryData(levelPercent = 0, voltageMv = 0, batteryChargePower = 0.0, temperature = 0, isCharging = true)
    )

    init {
        startUpdates()
    }

    private fun startUpdates() {
        viewModelScope.launch {
            while (isActive) {
                updateRam()
                updateStorage()
                updateBattery()
                delay(updateIntervalMs)
            }
        }
    }

    private fun updateRam() {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val totalMb = (memInfo.totalMem / (1024 * 1024)).toInt()
        val freeMb = (memInfo.availMem / (1024 * 1024)).toInt()
        val usedMb = totalMb - freeMb

        val usedPercent = if (totalMb > 0) ((usedMb.toFloat() / totalMb.toFloat()) * 100).toInt() else 0
        val percentValue = if (totalMb > 0) (usedMb.toFloat() / totalMb.toFloat()) * 100f else 0f

        val currentHistory = _ramHistory.value.toMutableList()
        currentHistory.add(percentValue)

        if (currentHistory.size > maxHistory) currentHistory.removeAt(0)
        _ramHistory.value = currentHistory

        ramData.value = RamData(
            totalMb = totalMb,
            usedMb = usedMb,
            freeMb = freeMb,
            usedPercent = usedPercent,
            history = currentHistory
        )
    }

    private fun updateStorage() {
        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.totalBytes.toDouble()
        val free = stat.availableBytes.toDouble()
        val used = total - free
        val usedPercent = if (total > 0) ((used / total) * 100).toInt() else 0

        storageData.value = StorageData(
            usedPercent = usedPercent,
            freeGb = free / 1_073_741_824.0,
            totalGb = total / 1_073_741_824.0
        )
    }

    @SuppressLint("MissingPermission")
    private fun updateBattery() {
        val info = provider.getDynamicDeviceInfo()

        batteryData.value = BatteryData(
            levelPercent = info.batteryPercent,
            voltageMv = info.batteryVoltageMv,
            batteryChargePower = info.batteryPowerWatts,
            temperature = info.batteryTemperatureC.toInt(),
            isCharging = info.isCharging
        )
    }
}