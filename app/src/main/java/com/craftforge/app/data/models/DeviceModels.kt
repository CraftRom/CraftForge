package com.craftforge.app.data.models

import androidx.compose.ui.graphics.Color

data class StorageData(
    val usedPercent: Int,
    val freeGb: Double,
    val totalGb: Double
)

data class BatteryData(
    val levelPercent: Int,
    val voltageMv: Int,
    val batteryChargePower: Double,
    val temperature: Int,
    val isCharging: Boolean
)

data class RamData(
    val totalMb: Int,
    val usedMb: Int,
    val freeMb: Int,
    val usedPercent: Int,
    val history: List<Float>
)

data class DisplayData(
    val resolution: String,
    val refreshRate: Float,
    val densityDpi: Int
)

data class SystemFeatureData(
    val isNfcSupported: Boolean,
    val hasFingerprintSensor: Boolean,
    val sensorCount: Int,
    val systemUptimeMs: Long
)

data class InfoCardTheme(
    val iconColor: Color,
    val containerColor: Color
)