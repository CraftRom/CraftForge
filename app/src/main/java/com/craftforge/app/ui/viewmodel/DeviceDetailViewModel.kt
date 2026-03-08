package com.craftforge.app.ui.viewmodel

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.craftforge.app.data.DeviceInfoItem
import com.craftforge.app.data.DeviceInfoProvider
import com.craftforge.app.data.DeviceInfoSection
import com.craftforge.app.data.DynamicDeviceInfo
import com.craftforge.app.data.StaticDeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class DeviceDetailViewModel(context: Context) : ViewModel() {

    private val provider = DeviceInfoProvider(context)
    private var isUpdating = false

    val headData = mutableStateOf<List<DeviceInfoItem>>(emptyList())
    val systemData = mutableStateOf<List<DeviceInfoSection>>(emptyList())
    val cpuGpuData = mutableStateOf<List<DeviceInfoSection>>(emptyList())
    val memoryData = mutableStateOf<List<DeviceInfoSection>>(emptyList())
    val batteryDetailsData = mutableStateOf<List<DeviceInfoSection>>(emptyList())
    val displayCameraData = mutableStateOf<List<DeviceInfoSection>>(emptyList())
    val connectivityData = mutableStateOf<List<DeviceInfoSection>>(emptyList())

    val cpuHistoryData = mutableStateOf<List<List<Float>>>(emptyList())
    val cpuMaxFreq = mutableStateOf(1f)

    private var staticInfoCache: StaticDeviceInfo? = null

    private val maxHistoryPoints = 60
    private val coreHistoryMap = mutableMapOf<Int, MutableList<Float>>()
    private var maxObservedFreq = 1f

    fun loadData() {
        if (isUpdating) return
        isUpdating = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val staticInfo = provider.getStaticDeviceInfo()
                staticInfoCache = staticInfo
                initializeStaticData(staticInfo)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            while (isActive) {
                try {
                    staticInfoCache?.let { staticInfo ->
                        updateDynamicData(staticInfo, provider.getDynamicDeviceInfo())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(1000L)
            }
        }
    }

    private fun initializeStaticData(info: StaticDeviceInfo) {
        headData.value = listOf(
            item("Device", info.deviceName),
            item("Model", info.model),
            item("Manufacturer", info.manufacturer),
            item("Brand", info.brand),
            item("Codename", info.deviceCodename)
        )

        systemData.value = listOf(
            section(
                "Android & ROM",
                item("Android Version", info.androidVersion),
                item("API Level", info.apiLevel.toString()),
                item("ROM / UI", info.miuiVersion),
                item("ROM Family", info.romFamily),
                item("ROM Version", info.romVersion),
                item("ROM Type", info.romType),
                item("Security Patch", info.securityPatch),
                item("Language", info.language),
                item("Timezone", info.timezone)
            ),
            section(
                "Build & ABI",
                item("Build ID", info.buildId),
                item("Build Type", info.buildType),
                item("Build Fingerprint", info.buildFingerprint),
                item("Supported ABIs", info.supportedAbis.joinToString(", ").ifEmpty { "Unknown" })
            ),
            section(
                "Kernel / Treble / Images",
                item("Kernel Version", info.kernelVersion ?: "Unknown"),
                item("GKI", info.gkiStatus),
                item("GKI Evidence", info.gkiEvidence),
                item("GSI", info.gsiStatus),
                item("GSI Evidence", info.gsiEvidence),
                item("Project Treble", yesNo(info.isTrebleSupported, "Supported", "Not Supported")),
                item("System-As-Root", yesNo(info.isSystemAsRoot)),
                item(
                    "Dynamic Partitions",
                    when {
                        info.isRetrofitDynamicPartitions -> "Retrofit"
                        info.isDynamicPartitions -> "Native"
                        else -> "Legacy"
                    }
                ),
                item("Seamless Updates (A/B)", yesNo(info.isSeamlessUpdateSupported, "Supported", "Not Supported")),
                item("Virtual A/B", info.virtualAbStatus)
            ),
            section(
                "Security & DRM",
                item("Root Access", yesNo(info.isRooted, "Granted", "None")),
                item("Root Manager", info.rootStatus),
                item("Widevine Vendor", info.widevineVendor),
                item("Widevine Version", info.widevineVersion),
                item("Widevine Level", info.widevineSecurityLevel),
                item("Max HDCP", info.widevineMaxHdcp),
                item("DRM Description", info.widevineDescription),
                item("DRM Algorithms", info.widevineAlgorithms)
            )
        )

        info.cpuMaxFrequencies.maxOrNull()?.toFloat()?.takeIf { it > 0f }?.let { maxFreq ->
            maxObservedFreq = maxFreq
            cpuMaxFreq.value = maxFreq
        }
    }

    private fun updateDynamicData(static: StaticDeviceInfo, dynamic: DynamicDeviceInfo) {
        val freqs = dynamic.cpuFrequencies
        val currentMax = freqs.maxOrNull()?.toFloat() ?: 1f
        if (currentMax > maxObservedFreq) {
            maxObservedFreq = currentMax
            cpuMaxFreq.value = maxObservedFreq
        }

        freqs.forEachIndexed { index, freq ->
            val list = coreHistoryMap.getOrPut(index) { mutableListOf() }
            val percentage = if (maxObservedFreq > 0f) (freq.toFloat() / maxObservedFreq) * 100f else 0f
            list.add(percentage)
            if (list.size > maxHistoryPoints) list.removeAt(0)
        }
        cpuHistoryData.value = coreHistoryMap.toSortedMap().values.map { it.toList() }

        val ramUsedPercent = percent(dynamic.ramUsedMb, static.ramTotalMb)
        val storageUsedGb = (static.internalTotalGb - dynamic.internalFreeGb).coerceAtLeast(0)
        val storageUsedPercent = percent(storageUsedGb, static.internalTotalGb)
        val chargePowerText = if (dynamic.isCharging && dynamic.batteryPowerWatts > 0.0) {
            "${"%.1f".format(dynamic.batteryPowerWatts)} W"
        } else {
            "Not Charging"
        }
        val cycleText = dynamic.batteryCycleCount.takeIf { it > 0 }?.toString() ?: "Unknown / Not Supported"
        val timeRemainingText = formatChargeTime(dynamic.chargeTimeRemainingMs)
        val uptimeFormat = formatDuration(dynamic.systemUptimeMs)

        cpuGpuData.value = listOf(
            section(
                "Chipset & CPU",
                item("SoC Manufacturer", static.socManufacturer),
                item("SoC Model", static.socModel),
                item("Hardware SKU", static.hardwareSku),
                item("ODM SKU", static.odmSku),
                item("Board", static.board ?: "Unknown"),
                item("Hardware", static.hardware ?: "Unknown"),
                item("CPU Architecture", static.cpuArchitecture),
                item("CPU Cores", static.cpuCoreCount.toString()),
                item("Max Frequencies", static.cpuMaxFrequencies.joinToString(", ") { "$it MHz" }.ifEmpty { "Unknown" })
            ),
            section(
                "Live CPU",
                item("CPU Governor", dynamic.cpuGovernor ?: "Unknown"),
                item("Current Frequencies", dynamic.cpuFrequencies.joinToString(", ") { "$it MHz" }.ifEmpty { "Unavailable" }),
                item("CPU Temperature", dynamic.cpuTemperatureC?.let { "${"%.1f".format(it)}°C" } ?: "Unknown"),
                item("Thermal Status", dynamic.thermalThrottlingStatus)
            ),
            section(
                "GPU",
                item("GPU Renderer", static.gpuRenderer),
                item("GPU Vendor", static.gpuVendor),
                item("OpenGL Version", static.gpuOpenGlVersion),
                item("Vulkan Version", static.gpuVulkanVersion ?: "Not Supported")
            )
        )

        memoryData.value = listOf(
            section(
                "RAM",
                item("RAM Total", formatMb(static.ramTotalMb)),
                item("RAM Used", formatMb(dynamic.ramUsedMb)),
                item("RAM Free", formatMb(dynamic.ramFreeMb)),
                item("RAM Usage", "$ramUsedPercent%"),
                item("Java Heap (Dalvik)", "${static.dalvikHeapSizeMb} MB"),
                item("Low RAM Device", yesNo(static.isLowRamDevice))
            ),
            section(
                "Storage",
                item("Internal Storage Total", "${static.internalTotalGb} GB"),
                item("Internal Storage Used", "$storageUsedGb GB"),
                item("Internal Storage Free", "${dynamic.internalFreeGb} GB"),
                item("Storage Usage", "$storageUsedPercent%")
            )
        )

        displayCameraData.value = listOf(
            section(
                "Display",
                item("Resolution", static.displayResolution),
                item("Refresh Rate", "${dynamic.displayRefreshRate} Hz"),
                item("Pixel Density", "${static.displayDensityDpi} DPI"),
                item("HDR", yesNo(static.isHdrSupported)),
                item("Wide Color Gamut", yesNo(static.isWideColorGamutSupported, "Supported", "Standard")),
                item("Multitouch", static.displayMultitouch)
            ),
            section(
                "Camera",
                item("Rear Camera", static.rearCameraMegapixels),
                item("Front Camera", static.frontCameraMegapixels),
                item("Camera Hardware Level", static.cameraHardwareLevel),
                item("Concurrent Cameras", yesNo(static.isConcurrentCameraSupported, "Supported", "Not Supported"))
            )
        )

        connectivityData.value = listOf(
            section(
                "Telephony",
                item("SIM Supported", yesNo(static.simSupported)),
                item("Active SIM Slots", static.activeSimCount.toString()),
                item("eSIM Support", yesNo(static.isEsimSupported)),
                item("Network Operator", dynamic.networkOperator ?: "None"),
                item("Data Network Type", dynamic.networkType ?: "Unknown")
            ),
            section(
                "Network",
                item("IPv4 Address", dynamic.ipv4Address),
                item("IPv6 Address", dynamic.ipv6Address),
                item("Wi‑Fi Supported", yesNo(static.isWifiSupported)),
                item("Wi‑Fi Standard", dynamic.wifiStandard ?: "Unknown"),
                item("Wi‑Fi Link Speed", dynamic.wifiLinkSpeedMbps?.let { "$it Mbps" } ?: "Unknown")
            ),
            section(
                "Wireless & Sensors",
                item("Bluetooth Supported", yesNo(static.isBluetoothSupported)),
                item("Bluetooth Version", static.bluetoothVersion ?: "Unknown"),
                item("NFC Supported", yesNo(static.isNfcSupported)),
                item("UWB", yesNo(static.isUwbSupported)),
                item("Fingerprint Sensor", yesNo(static.hasFingerprintSensor, "Present", "Absent")),
                item("Total Sensors", static.sensorCount.toString())
            )
        )

        batteryDetailsData.value = listOf(
            section(
                "Battery State",
                item("Battery Level", "${dynamic.batteryPercent}%"),
                item("Battery Status", dynamic.batteryStatus),
                item("Power Source", dynamic.chargingSource),
                item("Fast Charging", yesNo(dynamic.isFastCharging)),
                item("Time to Full", timeRemainingText)
            ),
            section(
                "Battery Health",
                item("Health", dynamic.batteryHealth),
                item("Technology", dynamic.batteryTechnology),
                item("Temperature", if (dynamic.batteryTemperatureC >= 0f) "${"%.1f".format(dynamic.batteryTemperatureC)}°C" else "Unknown"),
                item("Voltage", if (dynamic.batteryVoltageMv > 0) "${dynamic.batteryVoltageMv} mV" else "Unknown"),
                item("Current", if (dynamic.batteryCurrentMa > 0) "${dynamic.batteryCurrentMa} mA" else "Unknown"),
                item("Charging Power", chargePowerText),
                item("Cycle Count", cycleText)
            ),
            section(
                "Runtime",
                item("System Uptime", uptimeFormat)
            )
        )
    }

    private fun section(title: String, vararg items: DeviceInfoItem) =
        DeviceInfoSection(title = title, items = items.filter { it.value.isNotBlank() })

    private fun item(label: String, value: String) = DeviceInfoItem(label, value.ifBlank { "Unknown" })

    private fun yesNo(value: Boolean, yes: String = "Yes", no: String = "No") = if (value) yes else no

    private fun formatMb(value: Long): String = "${value} MB"

    private fun percent(used: Long, total: Long): Int =
        if (total > 0) ((used.toDouble() / total.toDouble()) * 100.0).roundToInt() else 0

    private fun formatChargeTime(valueMs: Long): String {
        if (valueMs <= 0L) return "Unknown"
        val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(valueMs)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes} min"
    }

    private fun formatDuration(valueMs: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(valueMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(valueMs) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(valueMs) % 60
        return "%02dh %02dm %02ds".format(hours, minutes, seconds)
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
