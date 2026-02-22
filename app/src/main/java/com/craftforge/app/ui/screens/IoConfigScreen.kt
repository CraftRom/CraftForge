package com.craftforge.app.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.craftforge.app.ui.screens.RootManager.readNodeViaRoot
import com.craftforge.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun IoConfigScreen(isRooted: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("TweaksPrefs", Context.MODE_PRIVATE)
    val styles = infoCardStyles()
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // --- БЛОКОВІ ПРИСТРОЇ (STORAGE) ---
    var blockDevice by remember { mutableStateOf("Unknown") }
    var blockPath by remember { mutableStateOf<String?>(null) }

    var currentIo by remember { mutableStateOf("Loading...") }
    var availableIos by remember { mutableStateOf(listOf<String>()) }

    var currentReadAhead by remember { mutableStateOf("Loading...") }
    val readAheadLevels = listOf("128 KB", "256 KB", "512 KB", "1024 KB", "2048 KB")

    var ioStatsPath by remember { mutableStateOf<String?>(null) }
    var isIoStatsEnabled by remember { mutableStateOf(false) }

    var addRandomPath by remember { mutableStateOf<String?>(null) }
    var isAddRandomEnabled by remember { mutableStateOf(false) }

    var nrRequestsPath by remember { mutableStateOf<String?>(null) }
    var currentNrRequests by remember { mutableStateOf("") }
    val nrRequestsLevels = listOf("64", "128 (Default)", "256 (Performance)", "512 (Max I/O)")

    // --- ВІРТУАЛЬНА ПАМ'ЯТЬ (VM / ZRAM) ---
    var zramCompPath by remember { mutableStateOf<String?>(null) }
    var currentZramComp by remember { mutableStateOf("") }
    var availableZramComps by remember { mutableStateOf(listOf<String>()) }

    var currentSwappiness by remember { mutableStateOf("Loading...") }
    val swappinessLevels = listOf("0 (Disabled)", "30 (Light)", "60 (Balanced)", "100 (Aggressive)", "150 (ZRAM Max)")

    var pageClusterPath by remember { mutableStateOf<String?>(null) }
    var currentPageCluster by remember { mutableStateOf("") }
    val pageClusterLevels = listOf("0 (Optimal for ZRAM)", "1", "2", "3 (Default for HDD)")

    var currentVfs by remember { mutableStateOf("Loading...") }
    val vfsLevels = listOf("10 (Keep Cache Long)", "50", "100 (Default)", "150 (Drop Fast)")

    // --- ADVANCED VM (Ядра 4.9 - 6.1+) ---
    var mglruPath by remember { mutableStateOf<String?>(null) }
    var isMglruEnabled by remember { mutableStateOf(false) }

    var watermarkPath by remember { mutableStateOf<String?>(null) }
    var currentWatermark by remember { mutableStateOf("") }
    val watermarkLevels = listOf("10 (Default)", "50 (Aggressive Kswapd)", "100", "200 (Gaming Mode)")

    // --- КЕШУВАННЯ ДАНИХ (DIRTY CACHE) ---
    var dirtyRatioPath by remember { mutableStateOf<String?>(null) }
    var currentDirtyRatio by remember { mutableStateOf("") }
    val dirtyRatioLevels = listOf("5 (Max I/O Smoothness)", "10", "20 (Default)", "30 (Max App Cache)")

    var dirtyBgRatioPath by remember { mutableStateOf<String?>(null) }
    var currentDirtyBgRatio by remember { mutableStateOf("") }
    val dirtyBgRatioLevels = listOf("2", "5", "10 (Default)", "15")

    LaunchedEffect(isRooted) {
        if (isRooted) {
            withContext(Dispatchers.IO) {
                suspend fun probeNode(vararg paths: String): Pair<String, String>? {
                    for (path in paths) {
                        val res = readNodeViaRoot("cat $path")
                        if (res != null && res.isNotBlank() && !res.contains("No such file") && !res.contains("Not a directory")) {
                            val canWrite = readNodeViaRoot("if [ -w \"$path\" ]; then echo '1'; else echo '0'; fi")?.trim() == "1"
                            if (canWrite) {
                                return Pair(path, res.trim())
                            }
                        }
                    }
                    return null
                }

                // 1. ВИЗНАЧЕННЯ НАКОПИЧУВАЧА
                val sdaProbe = probeNode("/sys/block/sda/queue/scheduler")
                val mmcProbe = probeNode("/sys/block/mmcblk0/queue/scheduler")

                when {
                    sdaProbe != null -> {
                        blockDevice = "UFS (sda)"
                        blockPath = "/sys/block/sda/queue"

                        val ioRaw = sdaProbe.second
                        currentIo = ioRaw.substringAfter("[").substringBefore("]")
                        availableIos = ioRaw.replace("[", "").replace("]", "").split(" ").filter { it.isNotBlank() }
                    }
                    mmcProbe != null -> {
                        blockDevice = "eMMC (mmcblk0)"
                        blockPath = "/sys/block/mmcblk0/queue"

                        val ioRaw = mmcProbe.second
                        currentIo = ioRaw.substringAfter("[").substringBefore("]")
                        availableIos = ioRaw.replace("[", "").replace("]", "").split(" ").filter { it.isNotBlank() }
                    }
                    else -> {
                        blockDevice = "Virtual/Unknown"
                        blockPath = null
                    }
                }

                // 2. ДОДАТКОВІ ПАРАМЕТРИ НАКОПИЧУВАЧА
                if (blockPath != null) {
                    currentReadAhead = probeNode("$blockPath/read_ahead_kb")?.second?.let { "$it KB" } ?: "Unknown"

                    val ioStatsProbe = probeNode("$blockPath/iostats")
                    ioStatsPath = ioStatsProbe?.first
                    isIoStatsEnabled = ioStatsProbe?.second == "1"

                    val addRandomProbe = probeNode("$blockPath/add_random")
                    addRandomPath = addRandomProbe?.first
                    isAddRandomEnabled = addRandomProbe?.second == "1"

                    val nrReqProbe = probeNode("$blockPath/nr_requests")
                    nrRequestsPath = nrReqProbe?.first
                    currentNrRequests = nrReqProbe?.second ?: ""
                }

                // 3. ZRAM ТА ВІРТУАЛЬНА ПАМ'ЯТЬ (VM)
                val zramCompProbe = probeNode("/sys/block/zram0/comp_algorithm")
                zramCompPath = zramCompProbe?.first
                if (zramCompProbe != null) {
                    val raw = zramCompProbe.second
                    currentZramComp = raw.substringAfter("[").substringBefore("]")
                    availableZramComps = raw.replace("[", "").replace("]", "").split(" ").filter { it.isNotBlank() }
                }

                val swapRaw = probeNode("/proc/sys/vm/swappiness")?.second
                currentSwappiness = when (swapRaw) {
                    "0" -> "0 (Disabled)"
                    "30" -> "30 (Light)"
                    "60" -> "60 (Balanced)"
                    "100" -> "100 (Aggressive)"
                    "150" -> "150 (ZRAM Max)"
                    null -> "Unknown"
                    else -> "$swapRaw (Custom)"
                }

                val pcProbe = probeNode("/proc/sys/vm/page-cluster")
                pageClusterPath = pcProbe?.first
                currentPageCluster = pcProbe?.second ?: ""

                val vfsRaw = probeNode("/proc/sys/vm/vfs_cache_pressure")?.second
                currentVfs = when (vfsRaw) {
                    "10" -> "10 (Keep Cache Long)"
                    "50" -> "50"
                    "100" -> "100 (Default)"
                    "150" -> "150 (Drop Fast)"
                    null -> "Unknown"
                    else -> "$vfsRaw (Custom)"
                }

                // 4. ADVANCED VM (MGLRU / Watermarks)
                val mglruProbe = probeNode("/sys/kernel/mm/lru_gen/enabled")
                mglruPath = mglruProbe?.first
                // MGLRU може бути 'y', 'n', '0x0000', '0x0003' тощо
                isMglruEnabled = mglruProbe?.second?.let { it.contains("y") || it.contains("0x0001") || it.contains("0x0003") || it.contains("0x0007") } == true

                val wmProbe = probeNode("/proc/sys/vm/watermark_scale_factor")
                watermarkPath = wmProbe?.first
                currentWatermark = wmProbe?.second ?: ""

                // 5. DIRTY CACHE
                val drProbe = probeNode("/proc/sys/vm/dirty_ratio")
                dirtyRatioPath = drProbe?.first
                currentDirtyRatio = drProbe?.second ?: ""

                val dbgProbe = probeNode("/proc/sys/vm/dirty_background_ratio")
                dirtyBgRatioPath = dbgProbe?.first
                currentDirtyBgRatio = dbgProbe?.second ?: ""
            }
        } else {
            currentIo = "LOCKED"
            currentReadAhead = "LOCKED"
            currentSwappiness = "LOCKED"
            currentVfs = "LOCKED"
        }
    }

    ConfigScreenLayout(title = "Storage & Memory", styles = styles, onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(bottom = 32.dp)
        ) {
            // --- БЛОК 1: ФІЗИЧНА ПАМ'ЯТЬ (STORAGE) ---
            if (blockPath != null || !isRooted) {
                StyledBlockCard(styles = styles, title = "Storage Tuning ($blockDevice)") {
                    var needsDivider = false

                    SettingsDropdownRow(
                        title = "I/O Scheduler",
                        subtitle = "Manages storage read/write requests.",
                        currentValue = currentIo,
                        availableValues = availableIos,
                        isRooted = isRooted,
                        styles = styles,
                        onValueSelected = { selected ->
                            currentIo = selected
                            scope.launch(Dispatchers.IO) {
                                RootManager.executeRootCommand("echo $selected > $blockPath/scheduler")
                                prefs.edit().putString("saved_scheduler", selected).apply()
                                withContext(Dispatchers.Main) { Toast.makeText(context, "Scheduler applied!", Toast.LENGTH_SHORT).show() }
                            }
                        }
                    )
                    needsDivider = true

                    if (needsDivider) HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsDropdownRow(
                        title = "Read-Ahead Cache",
                        subtitle = "Higher value speeds up large app launches.",
                        currentValue = currentReadAhead,
                        availableValues = readAheadLevels,
                        isRooted = isRooted,
                        styles = styles,
                        onValueSelected = { selected ->
                            currentReadAhead = selected
                            val kb = selected.replace(" KB", "")
                            scope.launch(Dispatchers.IO) {
                                RootManager.executeRootCommand("echo $kb > $blockPath/read_ahead_kb")
                                prefs.edit().putString("saved_readahead", kb).apply()
                            }
                        }
                    )

                    if (nrRequestsPath != null || !isRooted) {
                        if (needsDivider) HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsDropdownRow(
                            title = "I/O Queue Depth",
                            subtitle = "Number of requests device can handle at once.",
                            currentValue = if (isRooted) currentNrRequests.let { raw -> nrRequestsLevels.find { it.startsWith(raw) } ?: "$raw (Custom)" } else "LOCKED",
                            availableValues = nrRequestsLevels,
                            isRooted = isRooted,
                            styles = styles,
                            onValueSelected = { selected ->
                                val v = selected.substringBefore(" ")
                                currentNrRequests = selected
                                scope.launch(Dispatchers.IO) {
                                    RootManager.executeRootCommand("echo $v > $nrRequestsPath")
                                    prefs.edit().putString("saved_nr_requests", v).apply()
                                }
                            }
                        )
                    }

                    if (addRandomPath != null || !isRooted) {
                        if (needsDivider) HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsSwitchRow(
                            title = "I/O Entropy (Add Random)",
                            subtitle = "Disable for UFS/SSD to save CPU overhead.",
                            checked = isAddRandomEnabled,
                            styles = styles,
                            onCheckedChange = { isChecked ->
                                isAddRandomEnabled = isChecked
                                val value = if (isChecked) "1" else "0"
                                scope.launch(Dispatchers.IO) {
                                    RootManager.executeRootCommand("echo $value > $addRandomPath")
                                    prefs.edit().putString("saved_add_random", value).apply()
                                }
                            }
                        )
                    }

                    if (ioStatsPath != null || !isRooted) {
                        if (needsDivider) HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsSwitchRow(
                            title = "I/O Stats Logging",
                            subtitle = "Disable to reduce CPU overhead and save battery.",
                            checked = isIoStatsEnabled,
                            styles = styles,
                            onCheckedChange = { isChecked ->
                                isIoStatsEnabled = isChecked
                                val value = if (isChecked) "1" else "0"
                                scope.launch(Dispatchers.IO) {
                                    RootManager.executeRootCommand("echo $value > $ioStatsPath")
                                    prefs.edit().putString("saved_iostats", value).apply()
                                }
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // --- БЛОК 2: ОПЕРАТИВНА ПАМ'ЯТЬ (VM / ZRAM) ---
            StyledBlockCard(styles = styles, title = "Virtual Memory & ZRAM") {
                if (zramCompPath != null || !isRooted) {
                    SettingsDropdownRow(
                        title = "ZRAM Algorithm",
                        subtitle = "Select 'zstd' or 'lz4' for best Android 13+ performance.",
                        currentValue = if (isRooted) currentZramComp else "LOCKED",
                        availableValues = availableZramComps,
                        isRooted = isRooted,
                        styles = styles,
                        onValueSelected = { selected ->
                            currentZramComp = selected
                            scope.launch(Dispatchers.IO) {
                                // Алгоритм ZRAM зазвичай можна змінити тільки після скидання (reset) ZRAM,
                                // але деякі ядра дозволяють гарячу зміну. Ми робимо прямий запис.
                                RootManager.executeRootCommand("echo $selected > $zramCompPath")
                                prefs.edit().putString("saved_zram_comp", selected).apply()
                            }
                        }
                    )
                    HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))
                }

                SettingsDropdownRow(
                    title = "Swappiness",
                    subtitle = "How aggressively apps are moved to ZRAM.",
                    currentValue = currentSwappiness,
                    availableValues = swappinessLevels,
                    isRooted = isRooted,
                    styles = styles,
                    onValueSelected = { selected ->
                        currentSwappiness = selected
                        val value = selected.substringBefore(" ")
                        scope.launch(Dispatchers.IO) {
                            RootManager.executeRootCommand("echo $value > /proc/sys/vm/swappiness")
                            prefs.edit().putString("saved_swappiness", value).apply()
                        }
                    }
                )

                if (pageClusterPath != null || !isRooted) {
                    HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsDropdownRow(
                        title = "Page Cluster",
                        subtitle = "Number of pages read per swap. '0' is best for ZRAM.",
                        currentValue = if (isRooted) currentPageCluster.let { raw -> pageClusterLevels.find { it.startsWith(raw) } ?: "$raw (Custom)" } else "LOCKED",
                        availableValues = pageClusterLevels,
                        isRooted = isRooted,
                        styles = styles,
                        onValueSelected = { selected ->
                            val v = selected.substringBefore(" ")
                            currentPageCluster = selected
                            scope.launch(Dispatchers.IO) {
                                RootManager.executeRootCommand("echo $v > $pageClusterPath")
                                prefs.edit().putString("saved_page_cluster", v).apply()
                            }
                        }
                    )
                }

                HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))

                SettingsDropdownRow(
                    title = "VFS Cache Pressure",
                    subtitle = "Lower = Snappier gallery/files, uses more RAM.",
                    currentValue = currentVfs,
                    availableValues = vfsLevels,
                    isRooted = isRooted,
                    styles = styles,
                    onValueSelected = { selected ->
                        currentVfs = selected
                        val value = selected.substringBefore(" ")
                        scope.launch(Dispatchers.IO) {
                            RootManager.executeRootCommand("echo $value > /proc/sys/vm/vfs_cache_pressure")
                            prefs.edit().putString("saved_vfs", value).apply()
                        }
                    }
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            // --- БЛОК 3: ADVANCED VM (Kernel 5.4 - 6.1+) ---
            val showAdvVmSection = mglruPath != null || watermarkPath != null
            if (showAdvVmSection || !isRooted) {
                StyledBlockCard(styles = styles, title = "Advanced Memory Management") {
                    var needsDivider = false

                    if (mglruPath != null || !isRooted) {
                        SettingsSwitchRow(
                            title = "Multi-Gen LRU (MGLRU)",
                            subtitle = "Kernel 6.1+ feature. Massively reduces CPU usage and improves multitasking.",
                            checked = isMglruEnabled,
                            styles = styles,
                            onCheckedChange = { isChecked ->
                                isMglruEnabled = isChecked
                                val value = if (isChecked) "y" else "n" // Використовуємо стандартний синтаксис MGLRU
                                scope.launch(Dispatchers.IO) {
                                    RootManager.executeRootCommand("echo $value > $mglruPath")
                                    prefs.edit().putString("saved_mglru", value).apply()
                                }
                            }
                        )
                        needsDivider = true
                    }

                    if (watermarkPath != null || !isRooted) {
                        if (needsDivider) HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsDropdownRow(
                            title = "Watermark Scale Factor",
                            subtitle = "Higher value wakes kswapd earlier, preventing sudden lag.",
                            currentValue = if (isRooted) currentWatermark.let { raw -> watermarkLevels.find { it.startsWith(raw) } ?: "$raw (Custom)" } else "LOCKED",
                            availableValues = watermarkLevels,
                            isRooted = isRooted,
                            styles = styles,
                            onValueSelected = { selected ->
                                val value = selected.substringBefore(" ")
                                currentWatermark = selected
                                scope.launch(Dispatchers.IO) {
                                    RootManager.executeRootCommand("echo $value > $watermarkPath")
                                    prefs.edit().putString("saved_watermark_scale", value).apply()
                                }
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // --- БЛОК 4: КЕШУВАННЯ ДАНИХ (DIRTY CACHE) ---
            val showDirtySection = dirtyRatioPath != null || dirtyBgRatioPath != null
            if (showDirtySection || !isRooted) {
                StyledBlockCard(styles = styles, title = "Data Caching & Dirty Queue") {
                    var needsDivider = false

                    if (dirtyRatioPath != null || !isRooted) {
                        SettingsDropdownRow(
                            title = "Dirty Ratio (%)",
                            subtitle = "Max RAM % used for caching unsaved data.",
                            currentValue = if (isRooted) currentDirtyRatio.let { raw -> dirtyRatioLevels.find { it.startsWith(raw) } ?: "$raw (Custom)" } else "LOCKED",
                            availableValues = dirtyRatioLevels,
                            isRooted = isRooted,
                            styles = styles,
                            onValueSelected = { selected ->
                                currentDirtyRatio = selected
                                val value = selected.substringBefore(" ")
                                scope.launch(Dispatchers.IO) {
                                    RootManager.executeRootCommand("echo $value > $dirtyRatioPath")
                                    prefs.edit().putString("saved_dirty_ratio", value).apply()
                                }
                            }
                        )
                        needsDivider = true
                    }

                    if (dirtyBgRatioPath != null || !isRooted) {
                        if (needsDivider) HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsDropdownRow(
                            title = "Dirty Background Ratio (%)",
                            subtitle = "When to start writing cache to disk in background.",
                            currentValue = if (isRooted) currentDirtyBgRatio.let { raw -> dirtyBgRatioLevels.find { it.startsWith(raw) } ?: "$raw (Custom)" } else "LOCKED",
                            availableValues = dirtyBgRatioLevels,
                            isRooted = isRooted,
                            styles = styles,
                            onValueSelected = { selected ->
                                currentDirtyBgRatio = selected
                                val value = selected.substringBefore(" ")
                                scope.launch(Dispatchers.IO) {
                                    RootManager.executeRootCommand("echo $value > $dirtyBgRatioPath")
                                    prefs.edit().putString("saved_dirty_bg_ratio", value).apply()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}