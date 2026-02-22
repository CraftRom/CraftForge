package com.craftforge.app.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.media.MediaDrm
import android.media.UnsupportedSchemeException
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.Display
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

// ================= DATA CLASSES =================
data class StaticDeviceInfo(
    val deviceName: String, val model: String, val manufacturer: String, val brand: String,
    val deviceCodename: String, val androidVersion: String, val apiLevel: Int, val securityPatch: String,
    val buildFingerprint: String, val buildId: String, val buildType: String,
    val socManufacturer: String, val socModel: String,
    val hardwareSku: String, val odmSku: String,
    val board: String?, val hardware: String?, val cpuArchitecture: String, val cpuCoreCount: Int,
    val cpuMaxFrequencies: List<Int>, val supportedAbis: List<String>, val ramTotalMb: Long, val isLowRamDevice: Boolean, val internalTotalGb: Long,
    val simSupported: Boolean, val activeSimCount: Int, val isEsimSupported: Boolean,
    val displayResolution: String, val displayDensityDpi: Int,
    val isHdrSupported: Boolean, val isWideColorGamutSupported: Boolean, val displayMultitouch: String,
    val gpuRenderer: String, val gpuVendor: String, val gpuOpenGlVersion: String, val gpuVulkanVersion: String?,
    val cameraHardwareLevel: String, val isConcurrentCameraSupported: Boolean,
    val rearCameraMegapixels: String, val frontCameraMegapixels: String,
    val widevineVendor: String,
    val widevineVersion: String,
    val widevineDescription: String,
    val widevineAlgorithms: String,
    val widevineSecurityLevel: String,
    val widevineMaxHdcp: String, val dalvikHeapSizeMb: Int,
    val bluetoothVersion: String?, val timezone: String, val language: String,
    val isNfcSupported: Boolean, val isWifiSupported: Boolean, val isBluetoothSupported: Boolean,
    val isUwbSupported: Boolean, val hasFingerprintSensor: Boolean, val sensorCount: Int, val kernelVersion: String?,
    val miuiVersion: String, val isRooted: Boolean, val rootStatus: String, val isTrebleSupported: Boolean,
    val isSeamlessUpdateSupported: Boolean, val isDynamicPartitions: Boolean, val isRetrofitDynamicPartitions: Boolean,
    val virtualAbStatus: String, val isSystemAsRoot: Boolean, val isGsiDevice: Boolean, val isGkiDevice: Boolean
)

data class DynamicDeviceInfo(
    val ramUsedMb: Long, val ramFreeMb: Long, val internalFreeGb: Long,
    val batteryPercent: Int, val batteryStatus: String, val batteryHealth: String, val batteryTechnology: String,
    val batteryTemperatureC: Float, val batteryVoltageMv: Int, val batteryCurrentMa: Int,
    val batteryPowerWatts: Double, val chargingSource: String, val isCharging: Boolean,
    val isFastCharging: Boolean, val batteryCycleCount: Int, val chargeTimeRemainingMs: Long,
    val networkOperator: String?, val networkType: String?,
    val ipv4Address: String, val ipv6Address: String,
    val displayRefreshRate: Int,
    val wifiLinkSpeedMbps: Int?, val wifiStandard: String?,
    val cpuFrequencies: List<Int>, val cpuGovernor: String?, val cpuTemperatureC: Float?,
    val thermalThrottlingStatus: String, val systemUptimeMs: Long
)

class DeviceInfoProvider(context: Context) {
    private val appContext = context.applicationContext

    private var sysfsCpuAccessDenied = false
    private var sysfsBatteryCyclesDenied = false
    private var sysfsBatteryCurrentDenied = false
    private var sysfsCpuTempDenied = false
    private var cachedCameraLevel: String? = null

    // Виконується лише один раз
    private val isDeviceRooted: Boolean by lazy { checkRoot() }

    // ================= PUBLIC METHODS =================
    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    fun getStaticDeviceInfo(): StaticDeviceInfo {
        val act = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val gpu = getGpuInfo()
        val displayInfo = getDisplayDetails()
        val cameraInfo = getCameraMegapixels()
        val tm = appContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        return StaticDeviceInfo(
            deviceName = getDeviceName(), model = Build.MODEL, manufacturer = Build.MANUFACTURER, brand = Build.BRAND,
            deviceCodename = Build.DEVICE, androidVersion = Build.VERSION.RELEASE, apiLevel = Build.VERSION.SDK_INT,
            securityPatch = Build.VERSION.SECURITY_PATCH,
            buildFingerprint = Build.FINGERPRINT, buildId = Build.ID, buildType = Build.TYPE,
            socManufacturer = Build.SOC_MANUFACTURER, socModel = Build.SOC_MODEL, hardwareSku = Build.SKU, odmSku = Build.ODM_SKU,
            board = Build.BOARD, hardware = Build.HARDWARE,
            cpuArchitecture = Build.SUPPORTED_ABIS.firstOrNull() ?: System.getProperty("os.arch") ?: "Unknown",
            cpuCoreCount = Runtime.getRuntime().availableProcessors(), cpuMaxFrequencies = getCpuMaxFrequencies(),
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            ramTotalMb = getTotalRamMb(), isLowRamDevice = act.isLowRamDevice, internalTotalGb = getInternalTotalGb(),
            simSupported = isSimSupported(), activeSimCount = tm.activeModemCount,
            isEsimSupported = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_EUICC),
            displayResolution = getDisplayResolution(), displayDensityDpi = getDisplayDensityDpi(),
            isHdrSupported = displayInfo.first, isWideColorGamutSupported = displayInfo.second, displayMultitouch = getMultitouchDetails(),
            gpuRenderer = gpu.first, gpuVendor = gpu.second, gpuOpenGlVersion = gpu.third, gpuVulkanVersion = getVulkanVersion(),
            cameraHardwareLevel = getCameraHardwareLevel(), isConcurrentCameraSupported = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_CONCURRENT),
            rearCameraMegapixels = cameraInfo.first, frontCameraMegapixels = cameraInfo.second,
            dalvikHeapSizeMb = act.memoryClass,
            bluetoothVersion = getBluetoothVersion(), timezone = TimeZone.getDefault().id, language = Locale.getDefault().toLanguageTag(),
            isNfcSupported = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC),
            isWifiSupported = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI),
            isBluetoothSupported = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH),
            isUwbSupported = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_UWB),
            hasFingerprintSensor = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT),
            sensorCount = getSensorCount(), kernelVersion = System.getProperty("os.version"), miuiVersion = getUiRomName(),
            isRooted = isDeviceRooted, rootStatus = getRootManagerName(), isTrebleSupported = getSystemProperty("ro.treble.enabled", "false") == "true",
            isSeamlessUpdateSupported = checkIsSeamlessUpdateSupported(),
            isGsiDevice = checkIsGsiAdvanced(), isGkiDevice = checkIsGkiAdvanced(),
            isDynamicPartitions = checkIsDynamicPartitions(),
            isRetrofitDynamicPartitions = getSystemProperty("ro.boot.dynamic_partitions_retrofit") == "true",
            virtualAbStatus = getVirtualAbStatus(), isSystemAsRoot = checkIsSystemAsRoot(),
            widevineVendor = getWidevineInfo()["vendor"]!!,
            widevineVersion = getWidevineInfo()["version"]!!,
            widevineDescription = getWidevineInfo()["description"]!!,
            widevineAlgorithms = getWidevineInfo()["algorithms"]!!,
            widevineSecurityLevel = getWidevineInfo()["securityLevel"]!!,
            widevineMaxHdcp = getWidevineInfo()["maxHdcp"]!!
        )
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    fun getDynamicDeviceInfo(): DynamicDeviceInfo {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val temp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1).let { if (it != -1) it / 10f else -1f }
        val technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "Unknown"
        val currentMa = getBatteryCurrentMa(bm)

        val statusRaw = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = statusRaw == BatteryManager.BATTERY_STATUS_CHARGING || statusRaw == BatteryManager.BATTERY_STATUS_FULL

        val status = when {
            isCharging && statusRaw == BatteryManager.BATTERY_STATUS_FULL -> "full"
            isCharging -> "charging"
            currentMa > 0 && !isCharging -> "discharging"
            else -> "not charging"
        }

        val health = when (intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"; BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"; BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            else -> "unknown"
        }

        val chargingSource = when (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"; BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"; else -> "Battery"
        }

        val powerWatts = if (isCharging && currentMa > 0 && voltage > 0) {
            ((currentMa.toDouble() / 1000.0) * (voltage.toDouble() / 1000.0) * 100).roundToInt() / 100.0
        } else 0.0

        val ipAddresses = getNetworkIpAddresses()

        return DynamicDeviceInfo(
            ramUsedMb = getUsedRamMb(), ramFreeMb = getFreeRamMb(), internalFreeGb = getInternalFreeGb(),
            batteryPercent = level, batteryStatus = status, batteryHealth = health, batteryTechnology = technology,
            batteryTemperatureC = temp, batteryVoltageMv = voltage, batteryCurrentMa = currentMa, batteryPowerWatts = powerWatts,
            chargingSource = chargingSource, isCharging = isCharging, isFastCharging = powerWatts >= 10.0,
            batteryCycleCount = getBatteryCycleCount(bm, intent),
            chargeTimeRemainingMs = bm.computeChargeTimeRemaining(),
            networkOperator = getNetworkOperatorName(), networkType = getReadableNetworkType(),
            ipv4Address = ipAddresses.first, ipv6Address = ipAddresses.second,
            displayRefreshRate = getDisplayRefreshRate(),
            wifiLinkSpeedMbps = getWifiSpeed(), wifiStandard = getWifiStandard(),
            cpuFrequencies = getCpuFrequencies(), cpuGovernor = getCpuGovernor(), cpuTemperatureC = getCpuTemperature(),
            thermalThrottlingStatus = getThermalStatus(),
            systemUptimeMs = SystemClock.elapsedRealtime()
        )
    }

    // ================= ADVANCED SYSTEM CHECKS =================

    private fun checkIsGsiAdvanced(): Boolean {
        val props = listOf(
            getSystemProperty("ro.product.system.name").lowercase(),
            getSystemProperty("ro.product.system.device").lowercase(),
            getSystemProperty("ro.build.flavor").lowercase(),
            getSystemProperty("ro.product.name").lowercase()
        )
        if (props.any { it.contains("gsi") || it.contains("treble_") || it.contains("aosp") }) return true

        val hasPhhProps = getSystemProperty("persist.sys.phh.mainkeys").isNotEmpty() ||
                getSystemProperty("ro.treble.phh.rom_hal_version").isNotEmpty()
        if (hasPhhProps || File("/system/phh").exists() || File("/system/bin/phh-su").exists()) return true

        return false
    }

    private fun checkIsGkiAdvanced(): Boolean {
        val kernelVersion = System.getProperty("os.version")?.lowercase() ?: ""
        if (kernelVersion.contains("gki")) return true

        val gkiRegex = Regex("""^(\d+)\.(\d+)\.\d+-android\d+-""")
        val matchResult = gkiRegex.find(kernelVersion)
        if (matchResult != null) {
            val major = matchResult.groupValues[1].toIntOrNull() ?: 0
            val minor = matchResult.groupValues[2].toIntOrNull() ?: 0
            if (major > 5 || (major == 5 && minor >= 4)) return true
        }
        return false
    }

    private fun checkIsDynamicPartitions(): Boolean {
        if (getSystemProperty("ro.boot.dynamic_partitions") == "true") return true
        val mapperPaths = listOf(
            "/dev/block/mapper/system", "/dev/block/mapper/system_a",
            "/dev/block/mapper/vendor", "/dev/block/mapper/vendor_a"
        )
        if (mapperPaths.any { File(it).exists() }) return true
        if (File("/dev/block/by-name/super").exists()) return true
        return false
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
        val abPaths = listOf("/dev/block/by-name/boot_a", "/dev/block/by-name/system_a", "/dev/block/mapper/system_a")
        return abPaths.any { File(it).exists() }
    }

    private fun checkIsSystemAsRoot(): Boolean {
        val propSysRoot = getSystemProperty("ro.build.system_root_image") == "true"
        val propBootSysRoot = getSystemProperty("ro.boot.system_root_image") == "true"
        if (propSysRoot || propBootSysRoot) return true
        if (Build.VERSION.SDK_INT >= 29) return true

        try {
            val mountsFile = File("/proc/mounts")
            if (mountsFile.exists()) {
                val mounts = mountsFile.readLines()
                for (line in mounts) {
                    val parts = line.split(" ")
                    if (parts.size >= 3 && parts[1] == "/") {
                        val fsType = parts[2]
                        if (fsType != "rootfs" && fsType != "tmpfs") return true
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return false
    }

    // ================= MEDIA & DISPLAY CHECKS =================

    private fun getWidevineInfo(): Map<String, String> {
        val widevineUuid = UUID(-0x121074561ec14800L, -0x5c6b47d8d415a111L) // UUID для Widevine
        val info = mutableMapOf<String, String>()

        var mediaDrm: MediaDrm? = null
        try {
            mediaDrm = MediaDrm(widevineUuid)
            info["vendor"] = mediaDrm.getPropertyString(MediaDrm.PROPERTY_VENDOR) ?: "Unknown"
            info["version"] = mediaDrm.getPropertyString(MediaDrm.PROPERTY_VERSION) ?: "Unknown"
            info["description"] = mediaDrm.getPropertyString(MediaDrm.PROPERTY_DESCRIPTION) ?: "Unknown"
            info["algorithms"] = mediaDrm.getPropertyString(MediaDrm.PROPERTY_ALGORITHMS) ?: "Unknown"
            info["securityLevel"] = mediaDrm.getPropertyString("securityLevel") ?: "Unknown"
            info["maxHdcp"] = mediaDrm.getPropertyString("maxHdcpLevel") ?: "Unknown"
        } catch (e: Exception) {
            val none = "Not Supported"
            info["vendor"] = none; info["version"] = none; info["description"] = none
            info["algorithms"] = none; info["securityLevel"] = none; info["maxHdcp"] = none
        } finally {
            mediaDrm?.release()
        }
        return info
    }

    private fun getDisplayDetails(): Pair<Boolean, Boolean> {
        val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return Pair(false, false)
        val isHdr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            display.mode.supportedHdrTypes.isNotEmpty()
        } else {
            @Suppress("DEPRECATION")
            display.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true
        }
        val isWcg = display.isWideColorGamut
        return Pair(isHdr, isWcg)
    }

    private fun getDisplayRefreshRate(): Int {
        val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return 0
        val rate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) display.mode.refreshRate
        else { @Suppress("DEPRECATION") display.refreshRate }
        return rate.roundToInt()
    }

    private fun getMultitouchDetails(): String {
        val pm = appContext.packageManager
        return when {
            pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH_JAZZHAND) -> "Supported (5+ points)"
            pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH_DISTINCT) -> "Supported (2+ points)"
            pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH) -> "Supported (Basic)"
            pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) -> "Single Touch Only"
            else -> "Not Supported"
        }
    }

    private fun getCameraMegapixels(): Pair<String, String> {
        return try {
            val cm = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            var rearMp = "Unknown"
            var frontMp = "Unknown"
            for (cameraId in cm.cameraIdList) {
                val chars = cm.getCameraCharacteristics(cameraId)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val size = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                if (size != null) {
                    val mp = (size.width() * size.height() / 1000000.0).roundToInt().toString() + " MP"
                    if (facing == CameraCharacteristics.LENS_FACING_BACK && rearMp == "Unknown") rearMp = mp
                    if (facing == CameraCharacteristics.LENS_FACING_FRONT && frontMp == "Unknown") frontMp = mp
                }
            }
            Pair(rearMp, frontMp)
        } catch (e: Exception) { Pair("Unknown", "Unknown") }
    }

    private fun getThermalStatus(): String {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        return when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "Cool / Normal"
            PowerManager.THERMAL_STATUS_LIGHT -> "Light Throttling"
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderate Throttling"
            PowerManager.THERMAL_STATUS_SEVERE -> "Severe Throttling"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critical Throttling"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency!"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutting Down"
            else -> "Unknown"
        }
    }

    private fun getNetworkIpAddresses(): Pair<String, String> {
        var ipv4 = "Not Connected"
        var ipv6 = "Not Connected"
        try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val linkProperties: LinkProperties? = cm.getLinkProperties(cm.activeNetwork)
            linkProperties?.linkAddresses?.forEach { linkAddress ->
                val address = linkAddress.address
                if (!address.isLoopbackAddress) {
                    if (address is Inet4Address) ipv4 = address.hostAddress ?: "Unknown"
                    else if (address is Inet6Address) {
                        val fullAddress = address.hostAddress ?: ""
                        ipv6 = fullAddress.substringBefore('%').ifEmpty { "Unknown" }
                    }
                }
            }
        } catch (e: Exception) { /* Ігноруємо */ }
        return Pair(ipv4, ipv6)
    }

    // ================= ROOT & SYSFS LOGIC =================

    // ОПТИМІЗАЦІЯ ПРОДУКТИВНОСТІ: Видалено виклик su для динамічних даних,
    // щоб уникнути перегріву процесора під час щосекундного оновлення.
    private fun readSysfsFallback(paths: List<String>, isDeniedCache: Boolean): String? {
        if (!isDeniedCache) {
            for (path in paths) {
                try {
                    val file = File(path)
                    if (file.exists() && file.canRead()) {
                        val content = file.readText().trim()
                        if (content.isNotEmpty()) return content
                    }
                } catch (e: Exception) { /* Ігноруємо */ }
            }
        }
        return null
    }

    private fun readNodeViaRoot(command: String): String? {
        if (!isDeviceRooted) return null
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val result = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor()
            result.ifEmpty { null }
        } catch (e: Exception) { null }
    }

    // ================= DYNAMIC SYSFS READERS =================

    private fun getBatteryCurrentMa(bm: BatteryManager): Int {
        val microAmps = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (microAmps != Long.MIN_VALUE && microAmps != 0L) return (abs(microAmps) / 1000L).toInt()

        val paths = listOf(
            "/sys/class/power_supply/battery/current_now", "/sys/class/power_supply/bms/current_now",
            "/sys/class/power_supply/main/current_now", "/sys/class/power_supply/battery/batt_current",
            "/sys/class/power_supply/battery/batt_current_now", "/sys/devices/platform/battery/power_supply/battery/current_now"
        )
        val content = readSysfsFallback(paths, sysfsBatteryCurrentDenied)
        if (content != null) {
            val rawValue = content.toLongOrNull() ?: return 0
            val absValue = abs(rawValue)
            return if (absValue > 10000) (absValue / 1000).toInt() else absValue.toInt()
        } else sysfsBatteryCurrentDenied = true
        return 0
    }

    private fun getBatteryCycleCount(bm: BatteryManager, intent: Intent?): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val cycles = intent?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1) ?: -1
            if (cycles > 0) return cycles
        }
        val paths = listOf("/sys/class/power_supply/battery/cycle_count", "/sys/class/power_supply/bms/charge_full")
        val content = readSysfsFallback(paths, sysfsBatteryCyclesDenied)
        if (content != null) {
            val cycles = content.toIntOrNull()
            if (cycles != null && cycles in 1..10000) return cycles
        } else sysfsBatteryCyclesDenied = true
        return -1
    }

    private fun getCpuTemperature(): Float? {
        val paths = listOf(
            "/sys/class/thermal/thermal_zone0/temp", "/sys/class/thermal/thermal_zone1/temp",
            "/sys/devices/system/cpu/cpu0/cpufreq/cpu_temp", "/sys/class/hwmon/hwmon0/temp1_input"
        )
        val content = readSysfsFallback(paths, sysfsCpuTempDenied)
        if (content != null) {
            val temp = content.toFloatOrNull() ?: return null
            return if (temp > 1000) temp / 1000f else temp
        } else sysfsCpuTempDenied = true
        return null
    }

    private fun getCpuGovernor(): String? {
        val paths = listOf("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")
        val content = readSysfsFallback(paths, sysfsCpuAccessDenied)
        if (content == null) sysfsCpuAccessDenied = true
        return content
    }

    private fun getCpuMaxFrequencies(): List<Int> {
        val list = mutableListOf<Int>()
        val cores = Runtime.getRuntime().availableProcessors()
        var hasAccessError = false

        for (i in 0 until cores) {
            try {
                val file = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                if (file.exists() && file.canRead()) {
                    val freq = file.readText().trim().toIntOrNull()?.div(1000)
                    if (freq != null) list.add(freq) else hasAccessError = true
                } else hasAccessError = true
            } catch (e: Exception) { hasAccessError = true }
        }

        if (!hasAccessError && list.isNotEmpty()) return list
        list.clear()

        // Використовуємо ROOT лише для статичних перевірок (викликається 1 раз)
        if (isDeviceRooted) {
            val output = readNodeViaRoot("cat /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq")
            if (output != null) {
                for (line in output.split("\n")) {
                    val freq = line.trim().toIntOrNull()?.div(1000)
                    if (freq != null) list.add(freq)
                }
            }
        }
        return list
    }

    // ОПТИМІЗОВАНО: Прибрано виконання `su` команди в циклі
    private fun getCpuFrequencies(): List<Int> {
        val list = mutableListOf<Int>()
        val cores = Runtime.getRuntime().availableProcessors()

        if (!sysfsCpuAccessDenied) {
            var hasAccessError = false
            for (i in 0 until cores) {
                try {
                    val file = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
                    if (file.exists() && file.canRead()) {
                        val freq = file.readText().trim().toIntOrNull()?.div(1000)
                        if (freq != null) list.add(freq) else hasAccessError = true
                    } else hasAccessError = true
                } catch (e: Exception) { hasAccessError = true }
            }
            if (!hasAccessError && list.isNotEmpty()) return list
            sysfsCpuAccessDenied = true
        }

        return list
    }

    // ================= UTILS & WIFI =================

    private fun getUsedRamMb(): Long {
        val mem = ActivityManager.MemoryInfo()
        (appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mem)
        return (mem.totalMem - mem.availMem) / (1024 * 1024)
    }

    private fun getFreeRamMb(): Long {
        val mem = ActivityManager.MemoryInfo()
        (appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mem)
        return mem.availMem / (1024 * 1024)
    }

    private fun getInternalFreeGb(): Long {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            (stat.blockSizeLong * stat.availableBlocksLong) / (1024 * 1024 * 1024)
        } catch (e: Exception) { File(appContext.filesDir.absolutePath).freeSpace / (1024 * 1024 * 1024) }
    }

    private fun getNetworkOperatorName(): String? =
        (appContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).networkOperatorName

    @SuppressLint("MissingPermission")
    private fun getReadableNetworkType(): String {
        if (appContext.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return "Permission Denied"
        return try {
            when ((appContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).dataNetworkType) {
                TelephonyManager.NETWORK_TYPE_NR -> "5G"; TelephonyManager.NETWORK_TYPE_LTE -> "4G (LTE)"
                TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_UMTS -> "3G (HSPA/UMTS)"
                TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G (EDGE/GPRS)"
                else -> "Unknown"
            }
        } catch (e: Exception) { "Unknown" }
    }

    private fun getActiveWifiInfo(): WifiInfo? {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return capabilities.transportInfo as? WifiInfo
        }
        return null
    }

    private fun getWifiSpeed(): Int? {
        if (appContext.checkSelfPermission(Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val info = getActiveWifiInfo()
            if (info != null && info.linkSpeed > 0) info.linkSpeed else null
        } catch (e: Exception) { null }
    }

    @SuppressLint("SwitchIntDef")
    private fun getWifiStandard(): String? {
        if (appContext.checkSelfPermission(Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val info = getActiveWifiInfo() ?: return null
            when (info.wifiStandard) {
                1 -> "Legacy (802.11a/b/g)"
                4 -> "Wi-Fi 4 (802.11n)"
                5 -> "Wi-Fi 5 (802.11ac)"
                6 -> "Wi-Fi 6 (802.11ax)"
                7 -> "Wi-Fi (802.11ad)"
                8 -> "Wi-Fi 7 (802.11be)"
                else -> "Unknown"
            }
        } catch (e: Exception) { null }
    }

    // ================= STATIC HELPERS =================

    private fun getDeviceName(): String {
        val settingsName = Settings.Global.getString(appContext.contentResolver, Settings.Global.DEVICE_NAME)
        if (!settingsName.isNullOrBlank()) return settingsName
        val marketName = getSystemProperty("ro.product.marketname", "")
        if (marketName.isNotBlank()) return marketName
        return Build.MODEL
    }

    private fun getUiRomName(): String {
        val hyper = getSystemProperty("ro.miui.version.name_raw", ""); val miui = getSystemProperty("ro.miui.ui.version.name", "")
        val oneUi = getSystemProperty("ro.build.version.oneui", ""); val colorOs = getSystemProperty("ro.build.version.oplusrom", "")
        val oxygenOs = getSystemProperty("ro.oxygen.version", ""); val funtouch = getSystemProperty("ro.vivo.os.version", "")
        return when {
            hyper.isNotBlank() -> "HyperOS $hyper"
            miui.isNotBlank() -> "MIUI $miui"
            oneUi.isNotBlank() -> "OneUI (Samsung)"
            colorOs.isNotBlank() -> "ColorOS $colorOs"
            oxygenOs.isNotBlank() -> "OxygenOS $oxygenOs"
            funtouch.isNotBlank() -> "Funtouch OS $funtouch"
            else -> "AOSP / Stock"
        }
    }

    private fun getGpuInfo(): Triple<String, String, String> {
        return try {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            Triple("Available via GLSurface", "Available via GLSurface", am.deviceConfigurationInfo.glEsVersion ?: "Unknown")
        } catch (e: Exception) { Triple("Unknown", "Unknown", "Unknown") }
    }

    private fun getVulkanVersion(): String? {
        val feature = appContext.packageManager.systemAvailableFeatures.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
        val version = feature?.version ?: return null
        return "${version shr 22}.${(version shr 12) and 0x3FF}"
    }

    private fun getDisplayResolution(): String {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = wm.currentWindowMetrics.bounds
        return "${bounds.width()}x${bounds.height()}"
    }

    private fun getDisplayDensityDpi(): Int = appContext.resources.displayMetrics.densityDpi

    private fun getInternalTotalGb(): Long {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            (stat.blockSizeLong * stat.blockCountLong) / (1024 * 1024 * 1024)
        } catch (e: Exception) { File(appContext.filesDir.absolutePath).totalSpace / (1024 * 1024 * 1024) }
    }

    private fun isSimSupported(): Boolean =
        (appContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).phoneType != TelephonyManager.PHONE_TYPE_NONE

    @SuppressLint("MissingPermission")
    private fun getBluetoothVersion(): String? {
        return try {
            val bm = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            if (bm.adapter == null) "Not Supported"
            else if (appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) "BLE / 4.0+" else "Classic"
        } catch (e: Exception) { "Unknown" }
    }

    private fun getTotalRamMb(): Long {
        val mem = ActivityManager.MemoryInfo()
        (appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mem)
        return mem.totalMem / (1024 * 1024)
    }

    private fun getSensorCount(): Int =
        (appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getSensorList(android.hardware.Sensor.TYPE_ALL).size

    private fun getCameraHardwareLevel(): String {
        if (cachedCameraLevel != null) return cachedCameraLevel!!
        return try {
            val cm = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ids = cm.cameraIdList
            if (ids.isEmpty()) { cachedCameraLevel = "Unknown"; return "Unknown" }
            val level = when (cm.getCameraCharacteristics(ids[0]).get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "Legacy"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "Limited"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "Full"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "Level 3"
                else -> "Unknown"
            }
            cachedCameraLevel = level; level
        } catch (e: Exception) { cachedCameraLevel = "Unknown"; "Unknown" }
    }

    private fun checkRoot(): Boolean {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            if (process.waitFor() == 0) return true
        } catch (e: Exception) { /* Ігноруємо */ }

        val paths = arrayOf(
            "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su", "/system/xbin/su",
            "/data/local/xbin/su", "/data/local/bin/su", "/system/sd/xbin/su",
            "/system/bin/failsafe/su", "/data/local/su", "/su/bin/su",
            "/sbin/magisk", "/data/adb/magisk", "/data/adb/ksu", "/data/adb/apatch"
        )
        return paths.any { File(it).exists() }
    }

    fun getRootManagerName(): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-v"))
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim().uppercase()
            process.waitFor()

            when {
                output.contains("MAGISK") -> "Magisk"
                output.contains("KERNELSU") || output.contains("KSU") -> "KernelSU"
                output.contains("APATCH") -> "APatch"
                output.isNotEmpty() -> "Rooted (Unknown: $output)"
                else -> if (checkRoot()) "Rooted (Traditional)" else "None"
            }
        } catch (e: Exception) {
            if (checkRoot()) "Rooted (Traditional/Hidden)" else "None"
        }
    }

    @SuppressLint("PrivateApi")
    private fun getSystemProperty(key: String, def: String = ""): String {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val get = cls.getMethod("get", String::class.java, String::class.java)
            get.invoke(cls, key, def) as String
        } catch (e: Exception) { def }
    }
}