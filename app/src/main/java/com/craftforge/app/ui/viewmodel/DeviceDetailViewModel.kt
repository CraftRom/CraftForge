package com.craftforge.app.ui.viewmodel

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.craftforge.app.data.DynamicDeviceInfo
import com.craftforge.app.data.StaticDeviceInfo
import com.craftforge.app.data.DeviceInfoItem
import com.craftforge.app.data.DeviceInfoProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class DeviceDetailViewModel(context: Context) : ViewModel() {

    private val provider = DeviceInfoProvider(context)
    private var isUpdating = false

    // ================= UI STATES =================
    val headData = mutableStateOf<List<DeviceInfoItem>>(emptyList())
    val systemData = mutableStateOf<List<DeviceInfoItem>>(emptyList())
    val cpuGpuData = mutableStateOf<List<DeviceInfoItem>>(emptyList())
    val memoryData = mutableStateOf<List<DeviceInfoItem>>(emptyList())
    val batteryDetailsData = mutableStateOf<List<DeviceInfoItem>>(emptyList())
    val displayCameraData = mutableStateOf<List<DeviceInfoItem>>(emptyList())
    val connectivityData = mutableStateOf<List<DeviceInfoItem>>(emptyList())

    // Стан для малювання графіка ЦП
    val cpuHistoryData = mutableStateOf<List<List<Float>>>(emptyList())
    val cpuMaxFreq = mutableStateOf(1f)

    // ================= CACHE (Тільки незмінні дані) =================
    private var staticInfoCache: StaticDeviceInfo? = null

    // Змінні для графіків
    private val maxHistoryPoints = 60
    private val coreHistoryMap = mutableMapOf<Int, MutableList<Float>>()
    private var maxObservedFreq = 1f

    @SuppressLint("DefaultLocale")
    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    fun loadData() {
        if (isUpdating) return
        isUpdating = true

        viewModelScope.launch(Dispatchers.IO) {
            // 1. Отримуємо СТАТИЧНІ дані ОДИН РАЗ
            try {
                val staticInfo = provider.getStaticDeviceInfo()
                staticInfoCache = staticInfo
                initializeStaticData(staticInfo)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 2. Запускаємо цикл для ДИНАМІЧНИХ даних
            while (isActive) {
                try {
                    val staticCache = staticInfoCache
                    if (staticCache != null) {
                        val dynamicInfo = provider.getDynamicDeviceInfo()
                        updateDynamicData(staticCache, dynamicInfo)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(1000L) // Оновлення кожну секунду
            }
        }
    }

    private fun initializeStaticData(info: StaticDeviceInfo) {
        // --- 1. OVERVIEW ---
        headData.value = listOf(
            DeviceInfoItem("Device", info.deviceName),
            DeviceInfoItem("Model", info.model),
            DeviceInfoItem("Manufacturer", info.manufacturer),
            DeviceInfoItem("Brand", info.brand),
            DeviceInfoItem("Codename", info.deviceCodename)
        )

        // --- 2. SYSTEM (Ці дані статичні, їх можна формувати раз) ---
        systemData.value = listOf(
            DeviceInfoItem("Android Version", info.androidVersion),
            DeviceInfoItem("API Level", info.apiLevel.toString()),
            DeviceInfoItem("UI Version", info.miuiVersion),
            DeviceInfoItem("Security Patch", info.securityPatch),

            DeviceInfoItem("Build ID", info.buildId),
            DeviceInfoItem("Build Type", info.buildType),
            DeviceInfoItem("Build Fingerprint", info.buildFingerprint),
            DeviceInfoItem("Supported ABIs", info.supportedAbis.joinToString(", ")),

            DeviceInfoItem("Kernel Version", info.kernelVersion ?: "Unknown"),
            DeviceInfoItem("Generic Kernel (GKI)", if (info.isGkiDevice) "Yes (Official)" else "No (Custom/Legacy)"),

            DeviceInfoItem("Generic System (GSI)", if (info.isGsiDevice) "Yes (GSI/AOSP)" else "No (Stock/OEM)"),
            DeviceInfoItem("Project Treble", if (info.isTrebleSupported) "Supported" else "Not Supported"),
            DeviceInfoItem("System-As-Root (SAR)", if (info.isSystemAsRoot) "Yes" else "No"),
            DeviceInfoItem(
                label = "Dynamic Partitions",
                value = when {
                    info.isRetrofitDynamicPartitions -> "Yes (Retrofit)"
                    info.isDynamicPartitions -> "Yes (Native)"
                    else -> "No (Legacy Only)"
                }
            ),
            DeviceInfoItem("Seamless Updates (A/B)", if (info.isSeamlessUpdateSupported) "Supported" else "Not Supported"),
            DeviceInfoItem("Virtual A/B Status", info.virtualAbStatus),

            DeviceInfoItem("Root Access", if (info.isRooted) "Granted" else "None"),
            DeviceInfoItem("Root Manager", info.rootStatus),
            // Widevine DRM Data
            DeviceInfoItem("Vendor", info.widevineVendor),
            DeviceInfoItem("Version", info.widevineVersion),
            DeviceInfoItem("Description", info.widevineDescription),
            DeviceInfoItem("Algorithms", info.widevineAlgorithms),
            DeviceInfoItem("Widevine DRM Level", info.widevineSecurityLevel),
            DeviceInfoItem("Max HDCP Level", info.widevineMaxHdcp),

            DeviceInfoItem("Language", info.language),
            DeviceInfoItem("Timezone", info.timezone)
        )

        // Оновлюємо початкову макс. частоту ЦП
        val trueMax = info.cpuMaxFrequencies.maxOrNull()?.toFloat()
        if (trueMax != null && trueMax > 0f) {
            maxObservedFreq = trueMax
            cpuMaxFreq.value = trueMax
        }
    }

    // ОПТИМІЗОВАНО: Тепер ми повністю збираємо списки наново, комбінуючи статику та динаміку.
    // Ніяких `.add(index, ...)` !
    @SuppressLint("DefaultLocale")
    private fun updateDynamicData(static: StaticDeviceInfo, dynamic: DynamicDeviceInfo) {

        // ================= CPU & GPU =================
        val freqs = dynamic.cpuFrequencies
        val currentMax = freqs.maxOrNull()?.toFloat() ?: 1f
        if (currentMax > maxObservedFreq) {
            maxObservedFreq = currentMax
            cpuMaxFreq.value = maxObservedFreq
        }

        freqs.forEachIndexed { index, freq ->
            val list = coreHistoryMap.getOrPut(index) { mutableListOf() }
            val percentage = (freq.toFloat() / maxObservedFreq) * 100f
            list.add(percentage)
            if (list.size > maxHistoryPoints) list.removeAt(0)
        }
        cpuHistoryData.value = coreHistoryMap.values.map { it.toList() }

        // Збираємо список CPU/GPU чисто, без індексів
        cpuGpuData.value = listOf(
            DeviceInfoItem("SoC Manufacturer", static.socManufacturer.ifEmpty { "Unknown" }),
            DeviceInfoItem("SoC Model", static.socModel.ifEmpty { "Unknown" }),
            DeviceInfoItem("Hardware SKU", static.hardwareSku.ifEmpty { "Unknown" }),
            DeviceInfoItem("ODM SKU", static.odmSku.ifEmpty { "Unknown" }),
            DeviceInfoItem("Board", static.board ?: "Unknown"),
            DeviceInfoItem("Hardware", static.hardware ?: "Unknown"),
            DeviceInfoItem("CPU Architecture", static.cpuArchitecture),
            DeviceInfoItem("CPU Cores", static.cpuCoreCount.toString()),
            DeviceInfoItem("Max Frequencies", static.cpuMaxFrequencies.joinToString(", ") { "$it MHz" }.ifEmpty { "Unknown" }),

            // Динамічні дані CPU
            DeviceInfoItem("CPU Governor", dynamic.cpuGovernor ?: "Unknown"),
            DeviceInfoItem("CPU Temperature", dynamic.cpuTemperatureC?.let { "$it°C" } ?: "Unknown"),
            DeviceInfoItem("Thermal Status", dynamic.thermalThrottlingStatus),

            // GPU
            DeviceInfoItem("GPU Renderer", static.gpuRenderer),
            DeviceInfoItem("GPU Vendor", static.gpuVendor),
            DeviceInfoItem("OpenGL Version", static.gpuOpenGlVersion),
            DeviceInfoItem("Vulkan Version", static.gpuVulkanVersion ?: "Not Supported")
        )

        // ================= MEMORY =================
        memoryData.value = listOf(
            DeviceInfoItem("RAM Total", "${static.ramTotalMb} MB"),
            DeviceInfoItem("RAM Used", "${dynamic.ramUsedMb} MB"),
            DeviceInfoItem("RAM Free", "${dynamic.ramFreeMb} MB"),
            DeviceInfoItem("Java Heap Size (Dalvik)", "${static.dalvikHeapSizeMb} MB"),
            DeviceInfoItem("Low RAM Device", if (static.isLowRamDevice) "Yes" else "No"),
            DeviceInfoItem("Internal Storage Total", "${static.internalTotalGb} GB"),
            DeviceInfoItem("Internal Storage Free", "${dynamic.internalFreeGb} GB")
        )

        // ================= DISPLAY & CAMERA =================
        displayCameraData.value = listOf(
            DeviceInfoItem("Resolution", static.displayResolution),
            DeviceInfoItem("Refresh Rate", "${dynamic.displayRefreshRate} Hz"),
            DeviceInfoItem("Pixel Density", "${static.displayDensityDpi} DPI"),
            DeviceInfoItem("HDR Support", if (static.isHdrSupported) "Yes" else "No"),
            DeviceInfoItem("Wide Color Gamut", if (static.isWideColorGamutSupported) "Supported" else "Standard"),
            DeviceInfoItem("Multitouch", static.displayMultitouch),
            DeviceInfoItem("Rear Camera", static.rearCameraMegapixels),
            DeviceInfoItem("Front Camera", static.frontCameraMegapixels),
            DeviceInfoItem("Camera Hardware Level", static.cameraHardwareLevel),
            DeviceInfoItem("Concurrent Cameras", if (static.isConcurrentCameraSupported) "Supported" else "Not Supported")
        )

        // ================= CONNECTIVITY =================
        connectivityData.value = listOf(
            DeviceInfoItem("SIM Supported", if (static.simSupported) "Yes" else "No"),
            DeviceInfoItem("Active SIM Slots", static.activeSimCount.toString()),
            DeviceInfoItem("eSIM Support", if (static.isEsimSupported) "Yes" else "No"),

            DeviceInfoItem("Network Operator", dynamic.networkOperator ?: "None"),
            DeviceInfoItem("Data Network Type", dynamic.networkType ?: "Unknown"),
            DeviceInfoItem("IPv4 Address", dynamic.ipv4Address),
            DeviceInfoItem("IPv6 Address", dynamic.ipv6Address),

            DeviceInfoItem("Wi-Fi Supported", if (static.isWifiSupported) "Yes" else "No"),
            DeviceInfoItem("Wi-Fi Standard", dynamic.wifiStandard ?: "Unknown"),
            DeviceInfoItem("Wi-Fi Link Speed", dynamic.wifiLinkSpeedMbps?.let { "$it Mbps" } ?: "Unknown"),

            DeviceInfoItem("Bluetooth Supported", if (static.isBluetoothSupported) "Yes" else "No"),
            DeviceInfoItem("Bluetooth Version", static.bluetoothVersion ?: "Unknown"),
            DeviceInfoItem("NFC Supported", if (static.isNfcSupported) "Yes" else "No"),
            DeviceInfoItem("Ultra-Wideband (UWB)", if (static.isUwbSupported) "Yes" else "No"),
            DeviceInfoItem("Total Sensors", static.sensorCount.toString()),
            DeviceInfoItem("Fingerprint Sensor", if (static.hasFingerprintSensor) "Present" else "Absent")
        )

        // ================= BATTERY =================
        val powerText = if (dynamic.isCharging && dynamic.batteryPowerWatts > 0.0) "${dynamic.batteryPowerWatts} W" else "Not Charging"
        val cycleText = if (dynamic.batteryCycleCount != -1) "${dynamic.batteryCycleCount}" else "Unknown / Not Supported"

        val timeRemainingText = if (dynamic.chargeTimeRemainingMs > 0) {
            val mins = TimeUnit.MILLISECONDS.toMinutes(dynamic.chargeTimeRemainingMs)
            if (mins > 60) "${mins / 60}h ${mins % 60}m" else "$mins mins"
        } else "Unknown"

        val uptimeFormat = String.format(
            "%02d hrs %02d mins %02d secs",
            TimeUnit.MILLISECONDS.toHours(dynamic.systemUptimeMs),
            TimeUnit.MILLISECONDS.toMinutes(dynamic.systemUptimeMs) % 60,
            TimeUnit.MILLISECONDS.toSeconds(dynamic.systemUptimeMs) % 60
        )

        batteryDetailsData.value = listOf(
            DeviceInfoItem("Battery Health", dynamic.batteryHealth),
            DeviceInfoItem("Battery Status", dynamic.batteryStatus),
            DeviceInfoItem("Technology", dynamic.batteryTechnology),
            DeviceInfoItem("Power Source", dynamic.chargingSource),
            DeviceInfoItem("Fast Charging", if (dynamic.isFastCharging) "Yes" else "No"),
            DeviceInfoItem("Time to Full", timeRemainingText),
            DeviceInfoItem("Battery Voltage", "${dynamic.batteryVoltageMv} mV"),
            DeviceInfoItem("Battery Current", "${dynamic.batteryCurrentMa} mA"),
            DeviceInfoItem("Charging Power", powerText),
            DeviceInfoItem("Battery Temperature", "${dynamic.batteryTemperatureC}°C"),
            DeviceInfoItem("Battery Cycles", cycleText),
            DeviceInfoItem("System Uptime", uptimeFormat)
        )
    }

    companion object {
        fun provideFactory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return DeviceDetailViewModel(context.applicationContext) as T
                }
            }
    }
}