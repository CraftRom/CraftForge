package com.craftforge.app.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.media.MediaDrm
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.view.Display
import android.view.WindowManager
import com.craftforge.app.data.models.BatteryData
import androidx.annotation.RequiresPermission
import java.io.File
import java.lang.reflect.Method
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

// ================= DATA CLASSES =================
data class StaticDeviceInfo(
    val deviceName: String,
    val model: String,
    val manufacturer: String,
    val brand: String,
    val deviceCodename: String,
    val androidVersion: String,
    val apiLevel: Int,
    val securityPatch: String,
    val buildFingerprint: String,
    val buildId: String,
    val buildType: String,
    val socManufacturer: String,
    val socModel: String,
    val hardwareSku: String,
    val odmSku: String,
    val board: String?,
    val hardware: String?,
    val cpuArchitecture: String,
    val cpuCoreCount: Int,
    val cpuMaxFrequencies: List<Int>,
    val supportedAbis: List<String>,
    val ramTotalMb: Long,
    val isLowRamDevice: Boolean,
    val internalTotalGb: Long,
    val simSupported: Boolean,
    val activeSimCount: Int,
    val isEsimSupported: Boolean,
    val displayResolution: String,
    val displayDensityDpi: Int,
    val isHdrSupported: Boolean,
    val isWideColorGamutSupported: Boolean,
    val displayMultitouch: String,
    val gpuRenderer: String,
    val gpuVendor: String,
    val gpuOpenGlVersion: String,
    val gpuVulkanVersion: String?,
    val cameraHardwareLevel: String,
    val isConcurrentCameraSupported: Boolean,
    val rearCameraMegapixels: String,
    val frontCameraMegapixels: String,
    val widevineVendor: String,
    val widevineVersion: String,
    val widevineDescription: String,
    val widevineAlgorithms: String,
    val widevineSecurityLevel: String,
    val widevineMaxHdcp: String,
    val dalvikHeapSizeMb: Int,
    val bluetoothVersion: String?,
    val timezone: String,
    val language: String,
    val isNfcSupported: Boolean,
    val isWifiSupported: Boolean,
    val isBluetoothSupported: Boolean,
    val isUwbSupported: Boolean,
    val hasFingerprintSensor: Boolean,
    val sensorCount: Int,
    val kernelVersion: String?,
    val miuiVersion: String,
    val romFamily: String,
    val romVersion: String,
    val romType: String,
    val isRooted: Boolean,
    val rootStatus: String,
    val isTrebleSupported: Boolean,
    val isSeamlessUpdateSupported: Boolean,
    val isDynamicPartitions: Boolean,
    val isRetrofitDynamicPartitions: Boolean,
    val virtualAbStatus: String,
    val isSystemAsRoot: Boolean,
    val gsiStatus: String,
    val gsiEvidence: String,
    val gkiStatus: String,
    val gkiEvidence: String,
    val isGsiDevice: Boolean,
    val isGkiDevice: Boolean
)

data class DynamicDeviceInfo(
    val ramUsedMb: Long,
    val ramFreeMb: Long,
    val internalFreeGb: Long,
    val batteryPercent: Int,
    val batteryStatus: String,
    val batteryHealth: String,
    val batteryTechnology: String,
    val batteryTemperatureC: Float,
    val batteryVoltageMv: Int,
    val batteryCurrentMa: Int,
    val batteryPowerWatts: Double,
    val chargingSource: String,
    val isCharging: Boolean,
    val isFastCharging: Boolean,
    val batteryCycleCount: Int,
    val chargeTimeRemainingMs: Long,
    val networkOperator: String?,
    val networkType: String?,
    val ipv4Address: String,
    val ipv6Address: String,
    val displayRefreshRate: Int,
    val wifiLinkSpeedMbps: Int?,
    val wifiStandard: String?,
    val cpuFrequencies: List<Int>,
    val cpuGovernor: String?,
    val cpuTemperatureC: Float?,
    val thermalThrottlingStatus: String,
    val systemUptimeMs: Long
)

private data class RomInfo(
    val displayName: String,
    val family: String,
    val version: String,
    val type: String
)

private data class WidevineInfo(
    val vendor: String,
    val version: String,
    val description: String,
    val algorithms: String,
    val securityLevel: String,
    val maxHdcp: String
)

private data class CameraSummary(
    val hardwareLevel: String,
    val rearMegapixels: String,
    val frontMegapixels: String
)

private data class CompatibilityResult(
    val detected: Boolean,
    val status: String,
    val evidence: String
)

private data class BatteryRealtimeSnapshot(
    val percent: Int,
    val status: String,
    val health: String,
    val technology: String,
    val temperatureC: Float,
    val voltageMv: Int,
    val currentMa: Int,
    val powerWatts: Double,
    val chargingSource: String,
    val isCharging: Boolean,
    val isFastCharging: Boolean,
    val cycleCount: Int,
    val chargeTimeRemainingMs: Long
)

private data class ConnectivitySnapshot(
    val networkOperator: String?,
    val networkType: String?,
    val ipv4Address: String,
    val ipv6Address: String,
    val wifiLinkSpeedMbps: Int?,
    val wifiStandard: String?
)

private data class CpuRuntimeSnapshot(
    val frequencies: List<Int>,
    val governor: String?,
    val temperatureC: Float?,
    val thermalStatus: String
)

class DeviceInfoProvider(context: Context) {
    private val appContext = context.applicationContext
    private val activityManager by lazy(LazyThreadSafetyMode.NONE) {
        appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    }
    private val packageManager by lazy(LazyThreadSafetyMode.NONE) { appContext.packageManager }
    private val telephonyManager by lazy(LazyThreadSafetyMode.NONE) {
        appContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    }

    private var sysfsCpuAccessDenied = false
    private var sysfsBatteryCyclesDenied = false
    private var sysfsBatteryCurrentDenied = false
    private var sysfsCpuTempDenied = false

    private val systemPropertyCache = mutableMapOf<String, String>()
    private val readableNodeCache = mutableMapOf<String, String?>()
    private val systemPropertiesGetMethod: Method? by lazy(LazyThreadSafetyMode.NONE) {
        runCatching {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
        }.getOrNull()
    }

    private val isDeviceRooted: Boolean by lazy(LazyThreadSafetyMode.NONE) { detectRoot() }
    private val rootManagerNameCache: String by lazy(LazyThreadSafetyMode.NONE) { detectRootManagerName() }
    private val widevineInfoCache: WidevineInfo by lazy(LazyThreadSafetyMode.NONE) { readWidevineInfo() }
    private val romInfoCache: RomInfo by lazy(LazyThreadSafetyMode.NONE) { detectRomInfo() }
    private val kernelVersionCache: String by lazy(LazyThreadSafetyMode.NONE) { readKernelVersion() }
    private val cameraSummaryCache: CameraSummary by lazy(LazyThreadSafetyMode.NONE) { readCameraSummary() }
    private val cpuCurrentFreqPaths: List<String> by lazy(LazyThreadSafetyMode.NONE) {
        discoverCpuFreqPaths("scaling_cur_freq")
    }
    private val cpuMaxFreqPaths: List<String> by lazy(LazyThreadSafetyMode.NONE) {
        discoverCpuFreqPaths("cpuinfo_max_freq")
    }

    @Volatile
    private var cachedBatteryIntent: Intent? = null
    @Volatile
    private var cachedBatteryIntentAtMs: Long = 0L
    @Volatile
    private var cachedBatterySnapshot: BatteryRealtimeSnapshot? = null
    @Volatile
    private var cachedBatterySnapshotAtMs: Long = 0L
    @Volatile
    private var cachedConnectivitySnapshot: ConnectivitySnapshot? = null
    @Volatile
    private var cachedConnectivitySnapshotAtMs: Long = 0L
    @Volatile
    private var cachedCpuRuntimeSnapshot: CpuRuntimeSnapshot? = null
    @Volatile
    private var cachedCpuRuntimeSnapshotAtMs: Long = 0L
    @Volatile
    private var cachedDisplayRefreshRate: Int? = null
    @Volatile
    private var cachedDisplayRefreshRateAtMs: Long = 0L

    fun isRootedFast(): Boolean = isDeviceRooted

    fun isLowRamDeviceFast(): Boolean = activityManager.isLowRamDevice

    fun getBatteryDashboardData(): BatteryData {
        val snapshot = getBatteryRealtimeSnapshot()
        return BatteryData(
            levelPercent = snapshot.percent,
            voltageMv = snapshot.voltageMv,
            batteryChargePower = snapshot.powerWatts,
            temperature = snapshot.temperatureC.toInt(),
            isCharging = snapshot.isCharging
        )
    }

    fun warmUpBackgroundCaches() {
        runCatching { romInfoCache }
        runCatching { kernelVersionCache }
        runCatching { cameraSummaryCache }
        runCatching { widevineInfoCache }
        runCatching { getBatteryRealtimeSnapshot(maxAgeMs = 0L) }
        runCatching { getConnectivitySnapshot(maxAgeMs = 0L) }
        runCatching { getCpuRuntimeSnapshot(maxAgeMs = 0L) }
        runCatching { getDisplayRefreshRateCached(maxAgeMs = 0L) }
    }

    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    fun getStaticDeviceInfo(): StaticDeviceInfo {

        val gpu = getGpuInfo()
        val displayInfo = getDisplayDetails()
        val romInfo = romInfoCache
        val widevineInfo = widevineInfoCache
        val gsiInfo = analyzeGsiInfo()
        val gkiInfo = analyzeGkiInfo()

        return StaticDeviceInfo(
            deviceName = getDeviceName(),
            model = Build.MODEL.orUnknown(),
            manufacturer = Build.MANUFACTURER.orUnknown(),
            brand = Build.BRAND.orUnknown(),
            deviceCodename = Build.DEVICE.orUnknown(),
            androidVersion = Build.VERSION.RELEASE.orUnknown(),
            apiLevel = Build.VERSION.SDK_INT,
            securityPatch = Build.VERSION.SECURITY_PATCH.orUnknown(),
            buildFingerprint = Build.FINGERPRINT.orUnknown(),
            buildId = Build.ID.orUnknown(),
            buildType = Build.TYPE.orUnknown(),
            socManufacturer = getSocManufacturerCompat(),
            socModel = getSocModelCompat(),
            hardwareSku = getHardwareSkuCompat(),
            odmSku = getOdmSkuCompat(),
            board = Build.BOARD,
            hardware = Build.HARDWARE,
            cpuArchitecture = Build.SUPPORTED_ABIS.firstOrNull().orUnknown(System.getProperty("os.arch")),
            cpuCoreCount = Runtime.getRuntime().availableProcessors(),
            cpuMaxFrequencies = getCpuMaxFrequencies(),
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            ramTotalMb = getTotalRamMb(),
            isLowRamDevice = activityManager.isLowRamDevice,
            internalTotalGb = getInternalTotalGb(),
            simSupported = isSimSupported(),
            activeSimCount = getActiveSimCountCompat(telephonyManager),
            isEsimSupported = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_EUICC),
            displayResolution = getDisplayResolution(),
            displayDensityDpi = getDisplayDensityDpi(),
            isHdrSupported = displayInfo.first,
            isWideColorGamutSupported = displayInfo.second,
            displayMultitouch = getMultitouchDetails(),
            gpuRenderer = gpu.first,
            gpuVendor = gpu.second,
            gpuOpenGlVersion = gpu.third,
            gpuVulkanVersion = getVulkanVersion(),
            cameraHardwareLevel = cameraSummaryCache.hardwareLevel,
            isConcurrentCameraSupported = hasFeatureCompat(PackageManager.FEATURE_CAMERA_CONCURRENT, Build.VERSION_CODES.R),
            rearCameraMegapixels = cameraSummaryCache.rearMegapixels,
            frontCameraMegapixels = cameraSummaryCache.frontMegapixels,
            widevineVendor = widevineInfo.vendor,
            widevineVersion = widevineInfo.version,
            widevineDescription = widevineInfo.description,
            widevineAlgorithms = widevineInfo.algorithms,
            widevineSecurityLevel = widevineInfo.securityLevel,
            widevineMaxHdcp = widevineInfo.maxHdcp,
            dalvikHeapSizeMb = activityManager.memoryClass,
            bluetoothVersion = getBluetoothVersion(),
            timezone = TimeZone.getDefault().id,
            language = Locale.getDefault().toLanguageTag(),
            isNfcSupported = packageManager.hasSystemFeature(PackageManager.FEATURE_NFC),
            isWifiSupported = packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI),
            isBluetoothSupported = packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH),
            isUwbSupported = hasFeatureCompat(PackageManager.FEATURE_UWB, Build.VERSION_CODES.S),
            hasFingerprintSensor = packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT),
            sensorCount = getSensorCount(),
            kernelVersion = kernelVersionCache,
            miuiVersion = romInfo.displayName,
            romFamily = romInfo.family,
            romVersion = romInfo.version,
            romType = romInfo.type,
            isRooted = isDeviceRooted,
            rootStatus = rootManagerNameCache,
            isTrebleSupported = getSystemProperty("ro.treble.enabled", "false") == "true",
            isSeamlessUpdateSupported = checkIsSeamlessUpdateSupported(),
            isDynamicPartitions = checkIsDynamicPartitions(),
            isRetrofitDynamicPartitions = getSystemProperty("ro.boot.dynamic_partitions_retrofit") == "true",
            virtualAbStatus = getVirtualAbStatus(),
            isSystemAsRoot = checkIsSystemAsRoot(),
            gsiStatus = gsiInfo.status,
            gsiEvidence = gsiInfo.evidence,
            gkiStatus = gkiInfo.status,
            gkiEvidence = gkiInfo.evidence,
            isGsiDevice = gsiInfo.detected,
            isGkiDevice = gkiInfo.detected
        )
    }

    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    fun getDynamicDeviceInfo(): DynamicDeviceInfo {
        val battery = getBatteryRealtimeSnapshot()
        val connectivity = getConnectivitySnapshot()
        val cpuRuntime = getCpuRuntimeSnapshot()

        return DynamicDeviceInfo(
            ramUsedMb = getUsedRamMb(),
            ramFreeMb = getFreeRamMb(),
            internalFreeGb = getInternalFreeGb(),
            batteryPercent = battery.percent,
            batteryStatus = battery.status,
            batteryHealth = battery.health,
            batteryTechnology = battery.technology,
            batteryTemperatureC = battery.temperatureC,
            batteryVoltageMv = battery.voltageMv,
            batteryCurrentMa = battery.currentMa,
            batteryPowerWatts = battery.powerWatts,
            chargingSource = battery.chargingSource,
            isCharging = battery.isCharging,
            isFastCharging = battery.isFastCharging,
            batteryCycleCount = battery.cycleCount,
            chargeTimeRemainingMs = battery.chargeTimeRemainingMs,
            networkOperator = connectivity.networkOperator,
            networkType = connectivity.networkType,
            ipv4Address = connectivity.ipv4Address,
            ipv6Address = connectivity.ipv6Address,
            displayRefreshRate = getDisplayRefreshRateCached(),
            wifiLinkSpeedMbps = connectivity.wifiLinkSpeedMbps,
            wifiStandard = connectivity.wifiStandard,
            cpuFrequencies = cpuRuntime.frequencies,
            cpuGovernor = cpuRuntime.governor,
            cpuTemperatureC = cpuRuntime.temperatureC,
            thermalThrottlingStatus = cpuRuntime.thermalStatus,
            systemUptimeMs = SystemClock.elapsedRealtime()
        )
    }

    private fun getBatteryIntentCached(maxAgeMs: Long = 1_500L): Intent? {
        val now = SystemClock.elapsedRealtime()
        val cached = cachedBatteryIntent
        if (cached != null && now - cachedBatteryIntentAtMs <= maxAgeMs) return cached

        val fresh = runCatching {
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        cachedBatteryIntent = fresh
        cachedBatteryIntentAtMs = now
        return fresh
    }

    private fun getBatteryRealtimeSnapshot(maxAgeMs: Long = 1_500L): BatteryRealtimeSnapshot {
        val now = SystemClock.elapsedRealtime()
        cachedBatterySnapshot?.let { if (now - cachedBatterySnapshotAtMs <= maxAgeMs) return it }

        val batteryManager = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryIntent = getBatteryIntentCached(maxAgeMs = maxAgeMs)
        val batteryPercent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .takeIf { it in 0..100 } ?: -1
        val batteryVoltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val batteryTemperatureC = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1)
            .let { if (it >= 0) it / 10f else -1f }
        val batteryTechnology = batteryIntent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY).orUnknown()
        val batteryCurrentMa = getBatteryCurrentMa(batteryManager)

        val batteryStatusRaw = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = batteryStatusRaw == BatteryManager.BATTERY_STATUS_CHARGING ||
            batteryStatusRaw == BatteryManager.BATTERY_STATUS_FULL

        val batteryStatus = when (batteryStatusRaw) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not Charging"
            else -> "Unknown"
        }

        val batteryHealth = when (batteryIntent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "Unknown"
        }

        val chargingSource = when (batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "Battery"
        }

        val batteryPowerWatts = if (isCharging && batteryCurrentMa > 0 && batteryVoltageMv > 0) {
            ((batteryCurrentMa.toDouble() / 1000.0) * (batteryVoltageMv.toDouble() / 1000.0) * 100.0).roundToInt() / 100.0
        } else {
            0.0
        }

        return BatteryRealtimeSnapshot(
            percent = batteryPercent,
            status = batteryStatus,
            health = batteryHealth,
            technology = batteryTechnology,
            temperatureC = batteryTemperatureC,
            voltageMv = batteryVoltageMv,
            currentMa = batteryCurrentMa,
            powerWatts = batteryPowerWatts,
            chargingSource = chargingSource,
            isCharging = isCharging,
            isFastCharging = batteryPowerWatts >= 10.0,
            cycleCount = getBatteryCycleCount(batteryManager, batteryIntent) ?: -1,
            chargeTimeRemainingMs = getChargeTimeRemainingCompat(batteryManager)
        ).also {
            cachedBatterySnapshot = it
            cachedBatterySnapshotAtMs = now
        }
    }

    private fun getConnectivitySnapshot(maxAgeMs: Long = 4_500L): ConnectivitySnapshot {
        val now = SystemClock.elapsedRealtime()
        cachedConnectivitySnapshot?.let { if (now - cachedConnectivitySnapshotAtMs <= maxAgeMs) return it }

        val (ipv4Address, ipv6Address) = getNetworkIpAddresses()
        return ConnectivitySnapshot(
            networkOperator = getNetworkOperatorName(),
            networkType = getReadableNetworkType(),
            ipv4Address = ipv4Address,
            ipv6Address = ipv6Address,
            wifiLinkSpeedMbps = getWifiSpeed(),
            wifiStandard = getWifiStandard()
        ).also {
            cachedConnectivitySnapshot = it
            cachedConnectivitySnapshotAtMs = now
        }
    }

    private fun getCpuRuntimeSnapshot(maxAgeMs: Long = 1_500L): CpuRuntimeSnapshot {
        val now = SystemClock.elapsedRealtime()
        cachedCpuRuntimeSnapshot?.let { if (now - cachedCpuRuntimeSnapshotAtMs <= maxAgeMs) return it }

        return CpuRuntimeSnapshot(
            frequencies = getCpuFrequencies(),
            governor = getCpuGovernor(),
            temperatureC = getCpuTemperature(),
            thermalStatus = getThermalStatus()
        ).also {
            cachedCpuRuntimeSnapshot = it
            cachedCpuRuntimeSnapshotAtMs = now
        }
    }

    private fun getDisplayRefreshRateCached(maxAgeMs: Long = 3_000L): Int {
        val now = SystemClock.elapsedRealtime()
        cachedDisplayRefreshRate?.let { if (now - cachedDisplayRefreshRateAtMs <= maxAgeMs) return it }
        return getDisplayRefreshRate().also {
            cachedDisplayRefreshRate = it
            cachedDisplayRefreshRateAtMs = now
        }
    }

    private fun getSocManufacturerCompat(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER.orUnknown() else "Unknown"

    private fun getSocModelCompat(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.orUnknown() else "Unknown"

    private fun getHardwareSkuCompat(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SKU.orUnknown() else "Unknown"

    private fun getOdmSkuCompat(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.ODM_SKU.orUnknown() else "Unknown"

    private fun getActiveSimCountCompat(tm: TelephonyManager): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tm.activeModemCount else if (isSimSupported()) 1 else 0

    private fun hasFeatureCompat(feature: String, minApi: Int): Boolean =
        Build.VERSION.SDK_INT >= minApi && packageManager.hasSystemFeature(feature)

    private fun getChargeTimeRemainingCompat(batteryManager: BatteryManager): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) batteryManager.computeChargeTimeRemaining() else -1L

    // ================= ADVANCED SYSTEM CHECKS =================

    private fun analyzeGsiInfo(): CompatibilityResult {
        val evidence = linkedSetOf<String>()
        val props = mapOf(
            "ro.product.system.name" to getSystemProperty("ro.product.system.name"),
            "ro.product.system.device" to getSystemProperty("ro.product.system.device"),
            "ro.build.flavor" to getSystemProperty("ro.build.flavor"),
            "ro.product.name" to getSystemProperty("ro.product.name"),
            "ro.system.build.fingerprint" to getSystemProperty("ro.system.build.fingerprint"),
            "ro.system_ext.build.fingerprint" to getSystemProperty("ro.system_ext.build.fingerprint"),
            "ro.build.description" to getSystemProperty("ro.build.description")
        )

        props.forEach { (key, value) ->
            val normalized = value.lowercase(Locale.ROOT)
            when {
                normalized.contains("treble_") -> evidence += "$key contains treble_"
                normalized.contains("gsi") -> evidence += "$key contains gsi"
                normalized.contains("phh") -> evidence += "$key contains phh"
                normalized.contains("aosp") -> evidence += "$key contains aosp"
                normalized.contains("generic") -> evidence += "$key contains generic"
            }
        }

        val phhProps = listOf(
            "persist.sys.phh.mainkeys",
            "persist.sys.phh.disable_audio_effects",
            "ro.treble.phh.rom_hal_version"
        ).filter { getSystemProperty(it).isNotBlank() }
        if (phhProps.isNotEmpty()) evidence += "PHH props: ${phhProps.joinToString()}"

        val gsiMarkers = listOf(
            "/system/phh" to "PHH system marker",
            "/system/bin/phh-su" to "phh-su present",
            "/system/etc/treble_app_compat.xml" to "Treble app compat file"
        ).filter { File(it.first).exists() }.map { it.second }
        evidence += gsiMarkers

        val systemFp = getSystemProperty("ro.system.build.fingerprint")
        val vendorFp = getSystemProperty("ro.vendor.build.fingerprint")
        if (systemFp.isNotBlank() && vendorFp.isNotBlank()) {
            val systemBrand = systemFp.substringBefore('/').substringBefore(':').lowercase(Locale.ROOT)
            val vendorBrand = vendorFp.substringBefore('/').substringBefore(':').lowercase(Locale.ROOT)
            if (systemBrand.isNotBlank() && vendorBrand.isNotBlank() && systemBrand != vendorBrand) {
                evidence += "system/vendor fingerprint mismatch"
            }
        }

        val strong = evidence.any {
            it.contains("phh", ignoreCase = true) ||
                it.contains("contains gsi", ignoreCase = true) ||
                it.contains("contains treble_", ignoreCase = true)
        }
        val medium = evidence.any {
            it.contains("contains aosp", ignoreCase = true) ||
                it.contains("contains generic", ignoreCase = true) ||
                it.contains("fingerprint mismatch", ignoreCase = true)
        }

        return when {
            strong -> CompatibilityResult(
                detected = true,
                status = "Detected",
                evidence = evidence.take(3).joinToString(" • ").ifBlank { "PHH / GSI markers found" }
            )
            medium -> CompatibilityResult(
                detected = true,
                status = "Likely",
                evidence = evidence.take(3).joinToString(" • ").ifBlank { "AOSP / generic system image markers found" }
            )
            else -> CompatibilityResult(
                detected = false,
                status = "Not detected",
                evidence = if (checkIsDynamicPartitions() && getSystemProperty("ro.treble.enabled") == "true") {
                    "Treble-capable device, but no clear GSI markers"
                } else {
                    "OEM / stock-like system image markers"
                }
            )
        }
    }

    private fun analyzeGkiInfo(): CompatibilityResult {
        val evidence = linkedSetOf<String>()
        val kernelVersion = kernelVersionCache.lowercase(Locale.ROOT)
        val cmdline = readFirstLine("/proc/cmdline").orEmpty().lowercase(Locale.ROOT)
        val bootConfig = readFirstLine("/proc/bootconfig").orEmpty().lowercase(Locale.ROOT)
        val firstApiLevel = getSystemProperty("ro.product.first_api_level").toIntOrNull()
            ?: getSystemProperty("ro.board.first_api_level").toIntOrNull()

        if (kernelVersion.contains("gki")) evidence += "/proc/version contains gki"
        if (Regex("android(12|13|14|15|16)-").containsMatchIn(kernelVersion)) {
            evidence += "/proc/version matches android common kernel pattern"
        }
        if (cmdline.contains("androidboot.hardware") && cmdline.contains("bootconfig")) {
            evidence += "/proc/cmdline exposes modern bootconfig markers"
        }
        if (bootConfig.contains("androidboot.")) {
            evidence += "/proc/bootconfig available"
        }
        if (firstApiLevel != null && firstApiLevel >= 31) {
            evidence += "first API level $firstApiLevel suggests GKI era launch"
        }

        val match = Regex("""(\d+)\.(\d+)\.(\d+)-android(\d+)-""").find(kernelVersion)
        val kernelMajor = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val kernelMinor = match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
        if ((kernelMajor > 5 || (kernelMajor == 5 && kernelMinor >= 4)) && firstApiLevel != null && firstApiLevel >= 31) {
            evidence += "kernel >= 5.4 on Android 12+ launch device"
        }

        val strong = evidence.any { it.contains("contains gki") || it.contains("android common kernel pattern") }
        val medium = evidence.size >= 2

        return when {
            strong -> CompatibilityResult(
                detected = true,
                status = "Detected",
                evidence = evidence.take(3).joinToString(" • ").ifBlank { "Android common kernel markers found" }
            )
            medium -> CompatibilityResult(
                detected = true,
                status = "Likely",
                evidence = evidence.take(3).joinToString(" • ").ifBlank { "Modern GKI-era kernel markers found" }
            )
            else -> CompatibilityResult(
                detected = false,
                status = "Not detected",
                evidence = if (kernelVersion.contains("perf+") || kernelVersion.contains("silvercore") || kernelVersion.contains("eas")) {
                    "Custom kernel style markers found"
                } else {
                    "Legacy or OEM-specific kernel string"
                }
            )
        }
    }

    private fun checkIsDynamicPartitions(): Boolean {
        if (getSystemProperty("ro.boot.dynamic_partitions") == "true") return true
        val mapperPaths = listOf(
            "/dev/block/mapper/system",
            "/dev/block/mapper/system_a",
            "/dev/block/mapper/vendor",
            "/dev/block/mapper/vendor_a"
        )
        if (mapperPaths.any { File(it).exists() }) return true
        return File("/dev/block/by-name/super").exists()
    }

    private fun getVirtualAbStatus(): String {
        var isVab = getSystemProperty("ro.virtual_ab.enabled") == "true"
        var isVabc = getSystemProperty("ro.virtual_ab.compression.enabled") == "true"
        val isRetrofit = getSystemProperty("ro.virtual_ab.retrofit") == "true"

        if (!isVab && File("/system/bin/snapshotctl").exists()) isVab = true
        if (isVab && !isVabc && File("/system/bin/snapuserd").exists()) isVabc = true
        if (!isVab) return "Not Supported"

        return when {
            isVabc && isRetrofit -> "VABC (Compressed Retrofit)"
            isVabc -> "VABC (Compressed)"
            isRetrofit -> "VAB (Retrofit)"
            else -> "VAB (Standard)"
        }
    }

    private fun checkIsSeamlessUpdateSupported(): Boolean {
        if (getSystemProperty("ro.build.ab_update", "false") == "true") return true
        val abPaths = listOf(
            "/dev/block/by-name/boot_a",
            "/dev/block/by-name/system_a",
            "/dev/block/mapper/system_a"
        )
        return abPaths.any { File(it).exists() }
    }

    private fun checkIsSystemAsRoot(): Boolean {
        if (getSystemProperty("ro.build.system_root_image") == "true") return true
        if (getSystemProperty("ro.boot.system_root_image") == "true") return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true

        return runCatching {
            File("/proc/mounts")
                .takeIf(File::exists)
                ?.readLines()
                ?.any { line ->
                    val parts = line.split(" ")
                    parts.size >= 3 && parts[1] == "/" && parts[2] !in setOf("rootfs", "tmpfs")
                } == true
        }.getOrDefault(false)
    }

    // ================= MEDIA & DISPLAY CHECKS =================

    private fun readWidevineInfo(): WidevineInfo {
        val widevineUuid = UUID(-0x121074561ec14800L, -0x5c6b47d8d415a111L)
        var mediaDrm: MediaDrm? = null

        return try {
            mediaDrm = MediaDrm(widevineUuid)
            WidevineInfo(
                vendor = safeDrmString(mediaDrm, MediaDrm.PROPERTY_VENDOR),
                version = safeDrmString(mediaDrm, MediaDrm.PROPERTY_VERSION),
                description = safeDrmString(mediaDrm, MediaDrm.PROPERTY_DESCRIPTION),
                algorithms = safeDrmString(mediaDrm, MediaDrm.PROPERTY_ALGORITHMS),
                securityLevel = safeDrmString(mediaDrm, "securityLevel"),
                maxHdcp = safeDrmString(mediaDrm, "maxHdcpLevel")
            )
        } catch (_: Exception) {
            WidevineInfo(
                vendor = "Not Supported",
                version = "Not Supported",
                description = "Not Supported",
                algorithms = "Not Supported",
                securityLevel = "Not Supported",
                maxHdcp = "Not Supported"
            )
        } finally {
            runCatching { mediaDrm?.release() }
        }
    }

    private fun safeDrmString(mediaDrm: MediaDrm, key: String): String =
        runCatching { mediaDrm.getPropertyString(key) }
            .getOrNull()
            .orUnknown("Unknown")

    private fun getDisplayDetails(): Pair<Boolean, Boolean> {
        val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return false to false
        val isHdr = runCatching {
            @Suppress("DEPRECATION")
            display.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true
        }.getOrDefault(false)
        val isWcg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { display.isWideColorGamut }.getOrDefault(false)
        } else {
            false
        }
        return isHdr to isWcg
    }

    private fun getDisplayRefreshRate(): Int {
        val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return 0
        val refreshRate = runCatching { display.mode.refreshRate }
            .getOrNull()
            ?.takeIf { it > 0f }
            ?: runCatching {
                @Suppress("DEPRECATION")
                display.refreshRate
            }.getOrDefault(0f)
        return refreshRate.roundToInt()
    }

    private fun getMultitouchDetails(): String = when {
        packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH_JAZZHAND) -> "Supported (5+ points)"
        packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH_DISTINCT) -> "Supported (2+ points)"
        packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH) -> "Supported (Basic)"
        packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) -> "Single Touch Only"
        else -> "Not Supported"
    }

    private fun readCameraSummary(): CameraSummary {
        return try {
            val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            var bestHardwareLevel = CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
            var rearMp = 0.0
            var frontMp = 0.0

            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                val hardwareLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                    ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY

                if (hardwareLevelScore(hardwareLevel) > hardwareLevelScore(bestHardwareLevel)) {
                    bestHardwareLevel = hardwareLevel
                }

                val megapixels = activeArray?.let { (it.width().toDouble() * it.height().toDouble()) / 1_000_000.0 } ?: 0.0
                when (facing) {
                    CameraCharacteristics.LENS_FACING_BACK -> if (megapixels > rearMp) rearMp = megapixels
                    CameraCharacteristics.LENS_FACING_FRONT -> if (megapixels > frontMp) frontMp = megapixels
                }
            }

            CameraSummary(
                hardwareLevel = hardwareLevelLabel(bestHardwareLevel),
                rearMegapixels = rearMp.takeIf { it > 0.0 }?.let { "${it.roundToInt()} MP" } ?: "Unknown",
                frontMegapixels = frontMp.takeIf { it > 0.0 }?.let { "${it.roundToInt()} MP" } ?: "Unknown"
            )
        } catch (_: Exception) {
            CameraSummary(
                hardwareLevel = "Unknown",
                rearMegapixels = "Unknown",
                frontMegapixels = "Unknown"
            )
        }
    }

    private fun hardwareLevelScore(level: Int): Int = when (level) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> 5
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> 4
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> 3
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> 2
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> 1
        else -> 0
    }

    private fun hardwareLevelLabel(level: Int): String = when (level) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "Level 3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "Full"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "External"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "Limited"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "Legacy"
        else -> "Unknown"
    }

    private fun getThermalStatus(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "Unknown / Not Supported"
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        return when (powerManager.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "Cool / Normal"
            PowerManager.THERMAL_STATUS_LIGHT -> "Light Throttling"
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderate Throttling"
            PowerManager.THERMAL_STATUS_SEVERE -> "Severe Throttling"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critical Throttling"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutting Down"
            else -> "Unknown"
        }
    }

    private fun getNetworkIpAddresses(): Pair<String, String> {
        val fromLinkProperties = runCatching {
            val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            extractIpAddresses(connectivityManager.getLinkProperties(connectivityManager.activeNetwork))
        }.getOrNull()

        if (fromLinkProperties != null && fromLinkProperties != ("Not Connected" to "Not Connected")) {
            return fromLinkProperties
        }

        return runCatching {
            var ipv4 = "Not Connected"
            var ipv6 = "Not Connected"
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            interfaces.forEach { networkInterface ->
                if (!networkInterface.isUp || networkInterface.isLoopback) return@forEach
                Collections.list(networkInterface.inetAddresses).forEach { address ->
                    if (address.isLoopbackAddress) return@forEach
                    when (address) {
                        is Inet4Address -> ipv4 = address.hostAddress.orUnknown("Unknown")
                        is Inet6Address -> ipv6 = address.hostAddress?.substringBefore('%').orUnknown("Unknown")
                    }
                }
            }
            ipv4 to ipv6
        }.getOrDefault("Not Connected" to "Not Connected")
    }

    private fun extractIpAddresses(linkProperties: LinkProperties?): Pair<String, String> {
        var ipv4 = "Not Connected"
        var ipv6 = "Not Connected"
        linkProperties?.linkAddresses?.forEach { linkAddress ->
            val address = linkAddress.address ?: return@forEach
            if (address.isLoopbackAddress) return@forEach
            when (address) {
                is Inet4Address -> ipv4 = address.hostAddress.orUnknown("Unknown")
                is Inet6Address -> ipv6 = address.hostAddress?.substringBefore('%').orUnknown("Unknown")
            }
        }
        return ipv4 to ipv6
    }

    // ================= ROOT & SYSFS LOGIC =================

    private fun readFirstLine(path: String): String? = runCatching {
        File(path).takeIf { it.exists() && it.canRead() }?.bufferedReader()?.use { reader ->
            reader.readLine()?.trim()?.takeIf(String::isNotEmpty)
        }
    }.getOrNull()

    private fun readCachedNode(cacheKey: String, candidates: List<String>, isDenied: Boolean): String? {
        val cachedPath = readableNodeCache[cacheKey]
        if (!cachedPath.isNullOrBlank()) {
            val value = readFirstLine(cachedPath)
            if (!value.isNullOrBlank()) return value
            readableNodeCache.remove(cacheKey)
        }

        if (isDenied) return null

        for (path in candidates) {
            val value = readFirstLine(path)
            if (!value.isNullOrBlank()) {
                readableNodeCache[cacheKey] = path
                return value
            }
        }
        readableNodeCache[cacheKey] = null
        return null
    }

    private fun readNodeViaRoot(command: String): String? {
        if (!isDeviceRooted) return null
        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val result = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor()
            result.takeIf(String::isNotEmpty)
        }.getOrNull()
    }

    // ================= DYNAMIC SYSFS READERS =================

    private fun getBatteryCurrentMa(batteryManager: BatteryManager): Int {
        val microAmps = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (microAmps != Long.MIN_VALUE && microAmps != 0L) {
            return (abs(microAmps) / 1000L).toInt()
        }

        val paths = listOf(
            "/sys/class/power_supply/battery/current_now",
            "/sys/class/power_supply/bms/current_now",
            "/sys/class/power_supply/main/current_now",
            "/sys/class/power_supply/battery/batt_current",
            "/sys/class/power_supply/battery/batt_current_now",
            "/sys/devices/platform/battery/power_supply/battery/current_now"
        )
        val content = readCachedNode("battery_current", paths, sysfsBatteryCurrentDenied)
        if (content != null) {
            val rawValue = content.toLongOrNull() ?: return 0
            val absValue = abs(rawValue)
            return if (absValue > 10_000L) (absValue / 1000L).toInt() else absValue.toInt()
        }
        sysfsBatteryCurrentDenied = true
        return 0
    }

    private fun getBatteryCycleCount(bm: BatteryManager, batteryIntent: Intent? = null): Int? =
        getBatteryCycleCountCompat(bm, batteryIntent)

    private fun getBatteryCycleCountCompat(bm: BatteryManager, batteryIntent: Intent? = null): Int? {
        val fromApi = runCatching {
            val field = BatteryManager::class.java.getField("BATTERY_PROPERTY_CYCLE_COUNT")
            val propId = field.getInt(null)
            bm.getIntProperty(propId)
        }.getOrNull()?.takeIf { it > 0 && it != Int.MIN_VALUE }

        if (fromApi != null) return fromApi

        val fromIntent = runCatching {
            val extraField = BatteryManager::class.java.getField("EXTRA_CYCLE_COUNT")
            val extraKey = extraField.get(null) as? String
            extraKey?.let { key -> batteryIntent?.getIntExtra(key, -1) }
        }.getOrNull()?.takeIf { it != null && it > 0 }

        if (fromIntent != null) return fromIntent

        val sysfsCandidates = listOf(
            "/sys/class/power_supply/battery/cycle_count",
            "/sys/class/power_supply/bms/cycle_count",
            "/sys/class/power_supply/maxfg/cycle_count",
            "/sys/class/power_supply/battery/charge_cycles"
        )

        val fromSysfs = readCachedNode("battery_cycle_count", sysfsCandidates, sysfsBatteryCyclesDenied)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
        if (fromSysfs != null) return fromSysfs

        sysfsBatteryCyclesDenied = true
        return null
    }

    private fun mapNetworkTypeCompat(networkType: Int): String {
        return when (networkType) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN,
            TelephonyManager.NETWORK_TYPE_GSM -> "2G"
            TelephonyManager.NETWORK_TYPE_UNKNOWN -> "Unknown"
            else -> "Unknown"
        }
    }

    private fun getNetworkTypeNameCompat(
        networkType: Int,
        overrideNetworkType: Int? = null
    ): String {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "LTE CA"
            else -> mapNetworkTypeCompat(networkType)
        }
    }

    private fun getCpuTemperature(): Float? {
        val directPaths = listOf(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/class/thermal/thermal_zone1/temp",
            "/sys/devices/system/cpu/cpu0/cpufreq/cpu_temp",
            "/sys/class/hwmon/hwmon0/temp1_input"
        )
        val directValue = readCachedNode("cpu_temp", directPaths, sysfsCpuTempDenied)?.toFloatOrNull()
        if (directValue != null) return normalizeTemp(directValue)

        val thermalZones = File("/sys/class/thermal")
        if (thermalZones.exists() && thermalZones.isDirectory) {
            thermalZones.listFiles()
                ?.filter { it.name.startsWith("thermal_zone") }
                ?.forEach { zone ->
                    val type = readFirstLine("${zone.absolutePath}/type")?.lowercase(Locale.ROOT).orEmpty()
                    if (type.contains("cpu") || type.contains("soc") || type.contains("ap") || type.contains("big") || type.contains("little")) {
                        val temp = readFirstLine("${zone.absolutePath}/temp")?.toFloatOrNull()
                        if (temp != null) {
                            readableNodeCache["cpu_temp"] = "${zone.absolutePath}/temp"
                            return normalizeTemp(temp)
                        }
                    }
                }
        }

        sysfsCpuTempDenied = true
        return null
    }

    private fun normalizeTemp(value: Float): Float = if (value > 1000f) value / 1000f else value

    private fun getCpuGovernor(): String? {
        val paths = listOf("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")
        val content = readCachedNode("cpu_governor", paths, sysfsCpuAccessDenied)
        if (content == null) sysfsCpuAccessDenied = true
        return content
    }

    private fun discoverCpuFreqPaths(nodeName: String): List<String> {
        val paths = mutableListOf<String>()
        val cores = Runtime.getRuntime().availableProcessors()
        for (index in 0 until cores) {
            val path = "/sys/devices/system/cpu/cpu$index/cpufreq/$nodeName"
            if (File(path).canRead()) {
                paths += path
            }
        }
        return paths
    }

    private fun getCpuMaxFrequencies(): List<Int> {
        val values = cpuMaxFreqPaths.mapNotNull { readFirstLine(it)?.toIntOrNull()?.div(1000) }
        if (values.isNotEmpty()) return values

        if (isDeviceRooted) {
            val rootOutput = readNodeViaRoot("cat /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq")
            val rootValues = rootOutput
                ?.lineSequence()
                ?.mapNotNull { it.trim().toIntOrNull()?.div(1000) }
                ?.toList()
                .orEmpty()
            if (rootValues.isNotEmpty()) return rootValues
        }
        return emptyList()
    }

    private fun getCpuFrequencies(): List<Int> {
        if (sysfsCpuAccessDenied) return emptyList()
        val values = cpuCurrentFreqPaths.mapNotNull { readFirstLine(it)?.toIntOrNull()?.div(1000) }
        if (values.isNotEmpty()) return values
        sysfsCpuAccessDenied = true
        return emptyList()
    }

    // ================= UTILS & WIFI =================

    private fun getUsedRamMb(): Long {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return (memoryInfo.totalMem - memoryInfo.availMem) / (1024 * 1024)
    }

    private fun getFreeRamMb(): Long {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.availMem / (1024 * 1024)
    }

    private fun getInternalFreeGb(): Long = runCatching {
        val statFs = StatFs(Environment.getDataDirectory().path)
        statFs.availableBytes / (1024 * 1024 * 1024)
    }.getOrElse {
        File(appContext.filesDir.absolutePath).freeSpace / (1024 * 1024 * 1024)
    }

    private fun getNetworkOperatorName(): String? = runCatching {
        telephonyManager.networkOperatorName?.takeIf(String::isNotBlank)
    }.getOrNull()

    @SuppressLint("MissingPermission")
    private fun getReadableNetworkType(): String {
        if (appContext.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return "Permission Denied"
        }

        return runCatching {
            val networkType = telephonyManager.dataNetworkType

            when (getNetworkTypeNameCompat(networkType, null)) {
                "LTE CA" -> "4G (LTE CA)"
                "LTE" -> "4G (LTE)"
                "5G NR" -> "5G"
                "3G" -> "3G"
                "2G" -> "2G"
                "IWLAN" -> "Wi‑Fi Calling / IWLAN"
                else -> "Unknown"
            }
        }.getOrDefault("Unknown")
    }

    private fun getActiveWifiInfo(): WifiInfo? {
        val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        val transportInfo = capabilities?.transportInfo as? WifiInfo
        if (transportInfo != null) return transportInfo

        return runCatching {
            @Suppress("DEPRECATION")
            val wifiManager = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiManager.connectionInfo
        }.getOrNull()
    }

    private fun getWifiSpeed(): Int? {
        if (appContext.checkSelfPermission(Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) return null
        return runCatching {
            getActiveWifiInfo()?.linkSpeed?.takeIf { it > 0 }
        }.getOrNull()
    }

    @SuppressLint("SwitchIntDef")
    private fun getWifiStandard(): String? {
        if (appContext.checkSelfPermission(Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) return null
        val info = getActiveWifiInfo() ?: return null

        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            when {
                info.linkSpeed >= 2400 -> "Wi‑Fi 6 / 6E (Estimated)"
                info.linkSpeed >= 866 -> "Wi‑Fi 5 (Estimated)"
                info.linkSpeed >= 150 -> "Wi‑Fi 4 (Estimated)"
                info.linkSpeed > 0 -> "Legacy / Unknown"
                else -> null
            }
        } else {
            runCatching {
                when (info.wifiStandard) {
                    1 -> "Legacy (802.11a/b/g)"
                    4 -> "Wi‑Fi 4 (802.11n)"
                    5 -> "Wi‑Fi 5 (802.11ac)"
                    6 -> "Wi‑Fi 6 (802.11ax)"
                    7 -> "WiGig (802.11ad)"
                    8 -> "Wi‑Fi 7 (802.11be)"
                    else -> "Unknown"
                }
            }.getOrNull()
        }
    }

    // ================= STATIC HELPERS =================

    private fun getDeviceName(): String {
        val settingsName = runCatching {
            Settings.Global.getString(appContext.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull().orEmpty().trim()
        if (settingsName.isNotEmpty()) return settingsName

        val marketName = listOf(
            getSystemProperty("ro.product.marketname"),
            getSystemProperty("ro.product.odm.marketname"),
            getSystemProperty("ro.config.marketing_name"),
            getSystemProperty("ro.vendor.oplus.market.name")
        ).firstOrNull { it.isNotBlank() }
        if (!marketName.isNullOrBlank()) return marketName

        val secureBluetoothName = runCatching {
            Settings.Secure.getString(appContext.contentResolver, "bluetooth_name")
        }.getOrNull().orEmpty().trim()
        if (secureBluetoothName.isNotEmpty()) return secureBluetoothName

        return buildList {
            val manufacturer = Build.MANUFACTURER.orUnknown("Unknown")
            val model = Build.MODEL.orUnknown("Unknown")
            if (!model.startsWith(manufacturer, ignoreCase = true)) add(manufacturer)
            add(model)
        }.joinToString(" ").trim().ifEmpty { "Unknown" }
    }

    private fun detectRomInfo(): RomInfo {
        fun versioned(display: String, family: String, version: String, type: String) = RomInfo(
            displayName = if (version.isBlank()) display else "$display $version".trim(),
            family = family,
            version = version.ifBlank { "Unknown" },
            type = type
        )

        val hyperVersion = firstNonBlankProp(
            "ro.mi.os.version.name",
            "ro.mi.os.version.code",
            "ro.miui.version.name_raw"
        )
        if (hyperVersion.isNotBlank()) return versioned("HyperOS", "Xiaomi HyperOS", hyperVersion, "OEM")

        val miuiVersion = firstNonBlankProp("ro.miui.ui.version.name", "ro.miui.version.name")
        if (miuiVersion.isNotBlank()) return versioned("MIUI", "Xiaomi MIUI", miuiVersion, "OEM")

        val oneUiVersion = parseOneUiVersion()
        if (oneUiVersion.isNotBlank()) return versioned("One UI", "Samsung One UI", oneUiVersion, "OEM")

        val emuiVersion = firstNonBlankProp("ro.build.version.emui", "ro.build.magic_api_level")
        if (emuiVersion.isNotBlank()) return versioned("EMUI", "Huawei EMUI", emuiVersion.removePrefix("EmotionUI_"), "OEM")

        val magicOsVersion = firstNonBlankProp("ro.build.version.magic", "ro.magic.version")
        if (magicOsVersion.isNotBlank()) return versioned("MagicOS", "Honor MagicOS", magicOsVersion, "OEM")

        val colorOsVersion = firstNonBlankProp("ro.build.version.oplusrom", "ro.build.version.opporom")
        if (colorOsVersion.isNotBlank()) return versioned("ColorOS", "OPPO ColorOS", colorOsVersion, "OEM")

        val realmeUiVersion = firstNonBlankProp("ro.build.version.realmeui", "ro.realme.ui.version")
        if (realmeUiVersion.isNotBlank()) return versioned("realme UI", "realme UI", realmeUiVersion, "OEM")

        val oxygenOsVersion = firstNonBlankProp("ro.oxygen.version", "ro.build.version.oxygen")
        if (oxygenOsVersion.isNotBlank()) return versioned("OxygenOS", "OnePlus OxygenOS", oxygenOsVersion, "OEM")

        val funtouchVersion = firstNonBlankProp("ro.vivo.os.version", "ro.vivo.os.build.display.id")
        if (funtouchVersion.isNotBlank()) return versioned("Funtouch OS", "vivo Funtouch OS", funtouchVersion, "OEM")

        val originOsVersion = firstNonBlankProp("ro.vivo.os.name", "ro.origin.os.version")
        if (originOsVersion.lowercase(Locale.ROOT).contains("origin")) {
            return versioned("OriginOS", "vivo OriginOS", firstNonBlankProp("ro.origin.os.version", "ro.vivo.os.version"), "OEM")
        }

        val customRomDetectors = listOf(
            Triple("ro.lineage.version", "LineageOS", "Custom ROM"),
            Triple("org.pixelexperience.version.display", "PixelExperience", "Custom ROM"),
            Triple("ro.evolution.version", "Evolution X", "Custom ROM"),
            Triple("ro.crdroid.version", "crDroid", "Custom ROM"),
            Triple("ro.pixelos.version", "PixelOS", "Custom ROM"),
            Triple("ro.project.elixir.version", "Project Elixir", "Custom ROM"),
            Triple("ro.derp.version", "DerpFest", "Custom ROM"),
            Triple("ro.aospa.version", "Paranoid Android", "Custom ROM"),
            Triple("ro.arrow.version", "ArrowOS", "Custom ROM"),
            Triple("ro.havoc.version", "Havoc-OS", "Custom ROM"),
            Triple("ro.superior.version", "SuperiorOS", "Custom ROM"),
            Triple("ro.xtended.version", "Xtended", "Custom ROM"),
            Triple("ro.spark.version", "SparkOS", "Custom ROM"),
            Triple("ro.rising.version", "RisingOS", "Custom ROM"),
            Triple("ro.modversion", "Custom ROM", "Custom ROM")
        )
        customRomDetectors.firstOrNull { getSystemProperty(it.first).isNotBlank() }?.let { detector ->
            return versioned(detector.second, detector.second, getSystemProperty(detector.first), detector.third)
        }

        val buildDisplay = Build.DISPLAY.orUnknown("")
        val buildFlavor = getSystemProperty("ro.build.flavor")
        val fingerprint = Build.FINGERPRINT.orUnknown("")
        return when {
            buildDisplay.contains("userdebug", ignoreCase = true) ||
                buildFlavor.contains("aosp", ignoreCase = true) ||
                fingerprint.contains("aosp", ignoreCase = true) -> {
                versioned("AOSP / Custom", "AOSP-like", Build.DISPLAY.orUnknown(Build.VERSION.RELEASE), "AOSP / Custom")
            }
            brandLooksPixel() -> versioned("Pixel UI", "Google Pixel", Build.VERSION.RELEASE.orUnknown(), "OEM")
            else -> versioned("Android", detectBrandFamily(), Build.VERSION.RELEASE.orUnknown(), "Stock / OEM")
        }
    }

    private fun parseOneUiVersion(): String {
        val raw = getSystemProperty("ro.build.version.oneui")
        if (raw.isBlank()) return ""
        val digits = raw.filter(Char::isDigit)
        return when (digits.length) {
            3 -> "${digits[0]}.${digits[1]}${digits[2]}"
            2 -> "${digits[0]}.${digits[1]}"
            1 -> digits
            else -> raw
        }
    }

    private fun brandLooksPixel(): Boolean {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase(Locale.ROOT)
        val brand = Build.BRAND.orEmpty().lowercase(Locale.ROOT)
        return manufacturer == "google" || brand == "google"
    }

    private fun detectBrandFamily(): String {
        val manufacturer = Build.MANUFACTURER.orUnknown("Android").trim()
        val brand = Build.BRAND.orUnknown("").trim()
        return if (brand.isNotEmpty() && !brand.equals(manufacturer, ignoreCase = true)) {
            "$manufacturer / $brand"
        } else {
            manufacturer
        }
    }

    private fun firstNonBlankProp(vararg keys: String): String =
        keys.asSequence().map { getSystemProperty(it) }.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun getGpuInfo(): Triple<String, String, String> = runCatching {
        val glEsVersion = activityManager.deviceConfigurationInfo?.glEsVersion.orUnknown()
        val renderer = firstNonBlankProp(
            "ro.hardware.egl",
            "ro.hardware.vulkan",
            "ro.board.platform"
        ).ifBlank { "Unknown" }
        val vendor = when {
            renderer.contains("adreno", ignoreCase = true) -> "Qualcomm"
            renderer.contains("mali", ignoreCase = true) -> "ARM"
            renderer.contains("powervr", ignoreCase = true) -> "Imagination"
            renderer.contains("xclipse", ignoreCase = true) -> "Samsung / AMD"
            renderer.contains("immortalis", ignoreCase = true) -> "ARM"
            renderer.contains("tegra", ignoreCase = true) -> "NVIDIA"
            else -> detectGpuVendorFromProperties()
        }
        Triple(renderer, vendor, glEsVersion)
    }.getOrElse {
        Triple("Unknown", "Unknown", "Unknown")
    }

    private fun detectGpuVendorFromProperties(): String {
        val props = listOf(
            getSystemProperty("ro.hardware.egl"),
            getSystemProperty("ro.hardware.vulkan"),
            getSystemProperty("ro.board.platform")
        ).joinToString(" ").lowercase(Locale.ROOT)
        return when {
            "adreno" in props || "qcom" in props || "sm8" in props || "sdm" in props -> "Qualcomm"
            "mali" in props || "kirin" in props || "exynos" in props || "mt" in props -> "ARM"
            "powervr" in props -> "Imagination"
            "xclipse" in props -> "Samsung / AMD"
            else -> "Unknown"
        }
    }

    private fun getVulkanVersion(): String? {
        val vulkanFeature = packageManager.systemAvailableFeatures
            .firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
        val version = vulkanFeature?.version ?: return null
        return "${version shr 22}.${(version shr 12) and 0x3FF}"
    }

    private fun getDisplayResolution(): String {
        val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val physical = runCatching {
            display?.mode?.let { mode ->
                val width = mode.physicalWidth
                val height = mode.physicalHeight
                if (width > 0 && height > 0) "${width}x${height}" else null
            }
        }.getOrNull()
        if (!physical.isNullOrBlank()) return physical

        return runCatching {
            val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                "${bounds.width()}x${bounds.height()}"
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay?.let { "${it.width}x${it.height}" } ?: "Unknown"
            }
        }.getOrDefault("Unknown")
    }

    private fun getDisplayDensityDpi(): Int = appContext.resources.displayMetrics.densityDpi

    private fun getInternalTotalGb(): Long = runCatching {
        val statFs = StatFs(Environment.getDataDirectory().path)
        statFs.totalBytes / (1024 * 1024 * 1024)
    }.getOrElse {
        File(appContext.filesDir.absolutePath).totalSpace / (1024 * 1024 * 1024)
    }

    private fun isSimSupported(): Boolean = runCatching {
        telephonyManager.phoneType != TelephonyManager.PHONE_TYPE_NONE ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    }.getOrDefault(false)

    private fun getBluetoothVersion(): String? = runCatching {
        val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        when {
            bluetoothManager.adapter == null -> "Not Supported"
            packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) -> "BLE / 4.0+"
            packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) -> "Classic"
            else -> "Unknown"
        }
    }.getOrNull() ?: "Unknown"

    private fun getTotalRamMb(): Long {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.totalMem / (1024 * 1024)
    }

    private fun getSensorCount(): Int =
        (appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getSensorList(Sensor.TYPE_ALL).size

    private fun detectRoot(): Boolean {
        runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            if (process.waitFor() == 0) return true
        }

        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su",
            "/sbin/magisk",
            "/data/adb/magisk",
            "/data/adb/ksu",
            "/data/adb/apatch"
        )
        return paths.any { File(it).exists() }
    }

    fun getRootManagerName(): String = rootManagerNameCache

    private fun detectRootManagerName(): String {
        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-v"))
            val output = process.inputStream.bufferedReader().use { it.readText() }
                .trim()
                .uppercase(Locale.ROOT)
            process.waitFor()

            when {
                output.contains("MAGISK") -> "Magisk"
                output.contains("KERNELSU") || output.contains("KSU") -> "KernelSU"
                output.contains("APATCH") -> "APatch"
                output.isNotEmpty() -> "Rooted ($output)"
                isDeviceRooted -> "Rooted (Traditional)"
                else -> "None"
            }
        }.getOrElse {
            if (isDeviceRooted) "Rooted (Traditional/Hidden)" else "None"
        }
    }

    private fun readKernelVersion(): String {
        val procVersion = readFirstLine("/proc/version")
        if (!procVersion.isNullOrBlank()) return procVersion
        return System.getProperty("os.version").orUnknown("Unknown")
    }

    @SuppressLint("PrivateApi")
    private fun getSystemProperty(key: String, def: String = ""): String {
        systemPropertyCache[key]?.let { return if (it.isEmpty()) def else it }
        val value = runCatching {
            systemPropertiesGetMethod?.invoke(null, key, "") as? String
        }.getOrNull().orEmpty().trim()
        systemPropertyCache[key] = value
        return if (value.isEmpty()) def else value
    }

    private fun String?.orUnknown(fallback: String = "Unknown"): String =
        this?.trim()?.takeIf(String::isNotEmpty) ?: fallback
}
