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
fun CpuConfigScreen(isRooted: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("TweaksPrefs", Context.MODE_PRIVATE)
    val styles = infoCardStyles()
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // СТАН СИСТЕМИ
    var govPath by remember { mutableStateOf<String?>(null) }
    var currentGov by remember { mutableStateOf("Loading...") }
    var availableGovs by remember { mutableStateOf(listOf<String>()) }

    var minFreqPath by remember { mutableStateOf<String?>(null) }
    var currentMinFreq by remember { mutableStateOf("Loading...") }

    var maxFreqPath by remember { mutableStateOf<String?>(null) }
    var currentMaxFreq by remember { mutableStateOf("Loading...") }
    var availableFreqsDisplay by remember { mutableStateOf(listOf<String>()) }

    // ЕНЕРГОЗБЕРЕЖЕННЯ
    var touchBoostPath by remember { mutableStateOf<String?>(null) }
    var isTouchBoostEnabled by remember { mutableStateOf(false) }

    var powerCollapsePath by remember { mutableStateOf<String?>(null) }
    var isPowerCollapseEnabled by remember { mutableStateOf(false) }

    var mcSavingPath by remember { mutableStateOf<String?>(null) }
    var isMulticoreSavingEnabled by remember { mutableStateOf(false) }

    // EAS (Energy Aware Scheduling) - РОЗШИРЕНІ ШЛЯХИ ТА ФІЧІ
    var easEnablePath by remember { mutableStateOf<String?>(null) }
    var isEasEnabled by remember { mutableStateOf(false) }

    var schedBoostPath by remember { mutableStateOf<String?>(null) }
    var currentSchedBoost by remember { mutableStateOf("") }
    val schedBoostLevels = listOf("0 (Disabled)", "1 (Performance)", "2 (Aggressive)", "3 (Max Load)")

    var upMigratePath by remember { mutableStateOf<String?>(null) }
    var downMigratePath by remember { mutableStateOf<String?>(null) }
    var currentMigrate by remember { mutableStateOf("") }
    val migrateLevels = listOf("95 85 (Battery)", "85 75 (Default)", "75 65 (Performance)", "60 50 (Gaming)")

    var initTaskUtilPath by remember { mutableStateOf<String?>(null) }
    var currentInitTaskUtil by remember { mutableStateOf("") }
    val initUtilLevels = listOf("0 (Max Battery)", "15 (Balanced)", "30 (Performance)", "50 (Gaming)")

    var autoGroupPath by remember { mutableStateOf<String?>(null) }
    var isAutoGroupEnabled by remember { mutableStateOf(false) }

    // НОВІ ПАРАМЕТРИ EAS (Capacity Margins)
    var capacityMarginPath by remember { mutableStateOf<String?>(null) }
    var currentCapacityMargin by remember { mutableStateOf("") }
    // Чим більший відсоток маржі, тим швидше ядро підвищує частоту, не чекаючи 100% навантаження
    val capacityMarginLevels = listOf("1024 (Balanced)", "1150 (Smooth)", "1280 (Performance)", "1536 (Gaming)")

    // TUNABLES
    var schedutilUpPath by remember { mutableStateOf<String?>(null) }
    var schedutilUpRate by remember { mutableStateOf("") }

    var interactiveHiSpeedPath by remember { mutableStateOf<String?>(null) }
    var interactiveHispeedFreq by remember { mutableStateOf("") }

    val timingRates = listOf("500", "1000", "2000", "4000", "8000", "10000", "20000")

    LaunchedEffect(isRooted) {
        if (isRooted) {
            withContext(Dispatchers.IO) {
                // УНІВЕРСАЛЬНА ФУНКЦІЯ PROBING'У
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

                // 1. БАЗОВІ ПАРАМЕТРИ
                val govProbe = probeNode("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")
                govPath = govProbe?.first
                currentGov = govProbe?.second ?: "Unknown"

                val govListRaw = probeNode("/sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors")?.second ?: ""
                availableGovs = govListRaw.split(" ").filter { it.isNotBlank() }

                val freqListRaw = probeNode("/sys/devices/system/cpu/cpu0/cpufreq/scaling_available_frequencies")?.second ?: ""
                val rawFreqs = freqListRaw.split(" ").filter { it.isNotBlank() }.mapNotNull { it.toLongOrNull() }.sorted()
                availableFreqsDisplay = rawFreqs.map { "${it / 1000} MHz" }

                val minProbe = probeNode("/sys/devices/system/cpu/cpu0/cpufreq/scaling_min_freq")
                minFreqPath = minProbe?.first
                currentMinFreq = minProbe?.second?.let { "${it.toLong() / 1000} MHz" } ?: "Unknown"

                val maxProbe = probeNode("/sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq")
                maxFreqPath = maxProbe?.first
                currentMaxFreq = maxProbe?.second?.let { "${it.toLong() / 1000} MHz" } ?: "Unknown"

                // 2. ЕНЕРГОЗБЕРЕЖЕННЯ
                val touchProbe = probeNode("/sys/module/msm_performance/parameters/touchboost")
                touchBoostPath = touchProbe?.first
                isTouchBoostEnabled = touchProbe?.second == "1"

                val pcProbe = probeNode("/sys/module/pm_8x60/parameters/sleep_mode", "/sys/module/lpm_levels/parameters/sleep_disabled")
                powerCollapsePath = pcProbe?.first
                isPowerCollapseEnabled = pcProbe?.second == "1" || pcProbe?.second == "0"

                val mcProbe = probeNode("/sys/devices/system/cpu/sched_mc_power_savings")
                mcSavingPath = mcProbe?.first
                isMulticoreSavingEnabled = mcProbe?.second == "1"

                // 3. EAS (Energy Aware Scheduling)
                val easProbe = probeNode("/proc/sys/kernel/sched_energy_aware", "/sys/devices/system/cpu/eas/enable")
                easEnablePath = easProbe?.first
                isEasEnabled = easProbe?.second == "1"

                val boostProbe = probeNode("/proc/sys/kernel/sched_boost", "/sys/devices/system/cpu/eas/sched_boost")
                schedBoostPath = boostProbe?.first
                currentSchedBoost = when (boostProbe?.second) {
                    "0" -> "0 (Disabled)"
                    "1" -> "1 (Performance)"
                    "2" -> "2 (Aggressive)"
                    "3" -> "3 (Max Load)"
                    else -> boostProbe?.second ?: ""
                }

                val upMigProbe = probeNode("/proc/sys/kernel/sched_upmigrate", "/sys/devices/system/cpu/eas/up_migrate")
                val downMigProbe = probeNode("/proc/sys/kernel/sched_downmigrate", "/sys/devices/system/cpu/eas/down_migrate")
                upMigratePath = upMigProbe?.first
                downMigratePath = downMigProbe?.first
                currentMigrate = if (upMigProbe != null && downMigProbe != null) "${upMigProbe.second} ${downMigProbe.second} (Custom)" else ""

                val initUtilProbe = probeNode("/proc/sys/kernel/sched_initial_task_util")
                initTaskUtilPath = initUtilProbe?.first
                currentInitTaskUtil = initUtilProbe?.second ?: ""

                val autoGroupProbe = probeNode("/proc/sys/kernel/sched_autogroup_enabled")
                autoGroupPath = autoGroupProbe?.first
                isAutoGroupEnabled = autoGroupProbe?.second == "1"

                // НОВИЙ ПАРАМЕТР: Capacity Margin
                val marginProbe = probeNode("/proc/sys/kernel/sched_capacity_margin_up", "/sys/devices/system/cpu/eas/capacity_margin")
                capacityMarginPath = marginProbe?.first
                currentCapacityMargin = marginProbe?.second ?: ""

                // 4. TUNABLES
                val schedUpProbe = probeNode("/sys/devices/system/cpu/cpufreq/schedutil/up_rate_limit_us")
                schedutilUpPath = schedUpProbe?.first
                schedutilUpRate = schedUpProbe?.second ?: ""

                val intHiSpeedProbe = probeNode("/sys/devices/system/cpu/cpufreq/interactive/hispeed_freq")
                interactiveHiSpeedPath = intHiSpeedProbe?.first
                interactiveHispeedFreq = intHiSpeedProbe?.second ?: ""
            }
        } else {
            currentGov = "LOCKED"
            currentMinFreq = "LOCKED"
            currentMaxFreq = "LOCKED"
        }
    }

    ConfigScreenLayout(title = "CPU Engineering", styles = styles, onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(bottom = 32.dp)
        ) {
            // --- СЕКЦІЯ 1: ПРОФІЛЬ ТА ЧАСТОТИ ---
            if (govPath != null || !isRooted) {
                StyledBlockCard(styles = styles, title = "Governor & Clock Control") {
                    SettingsDropdownRow(
                        title = "Main Governor",
                        subtitle = "Switch between performance and battery modes.",
                        currentValue = currentGov,
                        availableValues = availableGovs,
                        isRooted = isRooted,
                        styles = styles,
                        onValueSelected = { selected ->
                            currentGov = selected
                            scope.launch(Dispatchers.IO) {
                                RootManager.executeRootCommand("echo $selected > /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor")
                                prefs.edit().putString("saved_governor", selected).apply()
                                withContext(Dispatchers.Main) { Toast.makeText(context, "Governor: $selected", Toast.LENGTH_SHORT).show() }
                            }
                        }
                    )

                    if (maxFreqPath != null && availableFreqsDisplay.isNotEmpty()) {
                        HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsDropdownRow(
                            title = "Max Frequency",
                            subtitle = "Set the hard limit for all CPU cores.",
                            currentValue = currentMaxFreq,
                            availableValues = availableFreqsDisplay,
                            isRooted = isRooted,
                            styles = styles,
                            onValueSelected = { selected ->
                                currentMaxFreq = selected
                                val mhz = selected.replace(" MHz", "").toLong() * 1000
                                scope.launch(Dispatchers.IO) {
                                    RootManager.executeRootCommand("echo $mhz > /sys/devices/system/cpu/cpu*/cpufreq/scaling_max_freq")
                                    prefs.edit().putString("saved_max_freq", mhz.toString()).apply()
                                }
                            }
                        )
                    }

                    if (minFreqPath != null && availableFreqsDisplay.isNotEmpty()) {
                        HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsDropdownRow(
                            title = "Min Frequency",
                            subtitle = "Set lower limit (higher fixes lag, lowers battery).",
                            currentValue = currentMinFreq,
                            availableValues = availableFreqsDisplay,
                            isRooted = isRooted,
                            styles = styles,
                            onValueSelected = { selected ->
                                currentMinFreq = selected
                                val mhz = selected.replace(" MHz", "").toLong() * 1000
                                scope.launch(Dispatchers.IO) {
                                    // Щоб уникнути помилки ядра, переконуємося, що ми записуємо правильне значення
                                    RootManager.executeRootCommand("echo $mhz > /sys/devices/system/cpu/cpu*/cpufreq/scaling_min_freq")
                                    prefs.edit().putString("saved_min_freq", mhz.toString()).apply()
                                }
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // --- СЕКЦІЯ 2: EAS (big.LITTLE) ---
            val hasEas = easEnablePath != null || schedBoostPath != null || upMigratePath != null || initTaskUtilPath != null || autoGroupPath != null || capacityMarginPath != null
            if (hasEas || !isRooted) {
                StyledBlockCard(styles = styles, title = "Energy Aware Scheduling (EAS)") {
                    var needsDivider = false

                    if (easEnablePath != null || !isRooted) {
                        SettingsSwitchRow(
                            title = "Enable EAS Engine",
                            subtitle = "Core algorithm for big.LITTLE efficiency.",
                            checked = isEasEnabled,
                            styles = styles,
                            onCheckedChange = { isChecked ->
                                isEasEnabled = isChecked
                                val v = if (isChecked) "1" else "0"
                                scope.launch(Dispatchers.IO) {
                                    RootManager.executeRootCommand("echo $v > $easEnablePath")
                                    prefs.edit().putString("saved_eas_enable", v).apply()
                                }
                            }
                        )
                        needsDivider = true
                    }

                    if (schedBoostPath != null || !isRooted) {
                        if (needsDivider) HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsDropdownRow(
                            title = "Scheduler Boost",
                            subtitle = "Forces tasks to run on performance cores.",
                            currentValue = if(isRooted) currentSchedBoost else "LOCKED",
                            availableValues = schedBoostLevels,
                            isRooted = isRooted,
                            styles = styles,
                            onValueSelected = { selected ->
                                currentSchedBoost = selected
                                val level = selected.substring(0, 1)
                                scope.launch(Dispatchers.IO) {
                                    RootManager.executeRootCommand("echo $level > $schedBoostPath")
                                    prefs.edit().putString("saved_sched_boost", level).apply()
                                }
                            }
                        )
                        needsDivider = true
                    }

                    if ((upMigratePath != null && downMigratePath != null) || !isRooted) {
                        if (needsDivider) HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsDropdownRow(
                            title = "Core Migration Threshold",
                            subtitle = "Up/Down % load required to shift tasks.",
                            currentValue = if(isRooted) currentMigrate else "LOCKED",
                            availableValues = migrateLevels,
                            isRooted = isRooted,
                            styles = styles,
                            onValueSelected = { selected ->
                                currentMigrate = selected
                                val parts = selected.split(" ")
                                if (parts.size >= 2) {
                                    scope.launch(Dispatchers.IO) {
                                        RootManager.executeRootCommand("echo ${parts[0]} > $upMigratePath")
                                        RootManager.executeRootCommand("echo ${parts[1]} > $downMigratePath")
                                        prefs.edit().putString("saved_sched_upmigrate", parts[0]).apply()
                                        prefs.edit().putString("saved_sched_downmigrate", parts[1]).apply()
                                        withContext(Dispatchers.Main) { Toast.makeText(context, "Migration updated!", Toast.LENGTH_SHORT).show() }
                                    }
                                }
                            }
                        )
                        needsDivider = true
                    }

                    // НОВИЙ ПАРАМЕТР: CAPACITY MARGIN
                    if (capacityMarginPath != null || !isRooted) {
                        if (needsDivider) HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsDropdownRow(
                            title = "Capacity Margin (Up)",
                            subtitle = "Artificial load injected to boost CPU frequency earlier.",
                            currentValue = if(isRooted) currentCapacityMargin.let { val raw = it.substringBefore(" "); capacityMarginLevels.find { lvl -> lvl.startsWith(raw) } ?: "$it (Custom)" } else "LOCKED",
                            availableValues = capacityMarginLevels,
                            isRooted = isRooted,
                            styles = styles,
                            onValueSelected = { selected ->
                                val value = selected.substringBefore(" ")
                                currentCapacityMargin = selected
                                scope.launch(Dispatchers.IO) {
                                    RootManager.executeRootCommand("echo $value > $capacityMarginPath")
                                    prefs.edit().putString("saved_capacity_margin", value).apply()
                                }
                            }
                        )
                        needsDivider = true
                    }

                    if (initTaskUtilPath != null || !isRooted) {
                        if (needsDivider) HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsDropdownRow(
                            title = "Initial Task Utilization",
                            subtitle = "Higher value launches apps on Big cores.",
                            currentValue = if(isRooted) currentInitTaskUtil.let { val raw = it.substringBefore(" "); initUtilLevels.find { lvl -> lvl.startsWith(raw) } ?: "$it (Custom)" } else "LOCKED",
                            availableValues = initUtilLevels,
                            isRooted = isRooted,
                            styles = styles,
                            onValueSelected = { selected ->
                                val value = selected.substringBefore(" ")
                                currentInitTaskUtil = selected
                                scope.launch(Dispatchers.IO) {
                                    RootManager.executeRootCommand("echo $value > $initTaskUtilPath")
                                    prefs.edit().putString("saved_init_task_util", value).apply()
                                }
                            }
                        )
                        needsDivider = true
                    }

                    if (autoGroupPath != null || !isRooted) {
                        if (needsDivider) HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsSwitchRow(
                            title = "Scheduler Autogroup",
                            subtitle = "Groups tasks to keep UI smooth under load.",
                            checked = isAutoGroupEnabled,
                            styles = styles,
                            onCheckedChange = { isChecked ->
                                isAutoGroupEnabled = isChecked
                                val v = if (isChecked) "1" else "0"
                                scope.launch(Dispatchers.IO) {
                                    RootManager.executeRootCommand("echo $v > $autoGroupPath")
                                    prefs.edit().putString("saved_autogroup", v).apply()
                                }
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // --- СЕКЦІЯ 3: ЕНЕРГОЗБЕРЕЖЕННЯ ---
            val showPowerSection = mcSavingPath != null || powerCollapsePath != null
            if (showPowerSection || !isRooted) {
                StyledBlockCard(styles = styles, title = "Power Management") {
                    var needsDivider = false

                    if (mcSavingPath != null || !isRooted) {
                        SettingsSwitchRow(
                            title = "Multicore Power Saving",
                            subtitle = "Try to group tasks on fewer cores to save energy.",
                            checked = isMulticoreSavingEnabled,
                            styles = styles,
                            onCheckedChange = { isChecked ->
                                isMulticoreSavingEnabled = isChecked
                                val v = if (isChecked) "1" else "0"
                                scope.launch(Dispatchers.IO) {
                                    RootManager.executeRootCommand("echo $v > $mcSavingPath")
                                    prefs.edit().putString("saved_mc_power", v).apply()
                                }
                            }
                        )
                        needsDivider = true
                    }

                    if (powerCollapsePath != null || !isRooted) {
                        if (needsDivider) HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsSwitchRow(
                            title = "Power Collapse",
                            subtitle = "Allows CPU to enter deepest sleep states.",
                            checked = isPowerCollapseEnabled,
                            styles = styles,
                            onCheckedChange = { isChecked ->
                                isPowerCollapseEnabled = isChecked
                                val v = if (isChecked) "1" else "0"
                                scope.launch(Dispatchers.IO) {
                                    RootManager.executeRootCommand("echo $v > $powerCollapsePath")
                                    prefs.edit().putString("saved_power_collapse", v).apply()
                                }
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // --- СЕКЦІЯ 4: ТОНКЕ НАЛАШТУВАННЯ (TUNABLES) ---
            val showTunablesSection = (currentGov == "schedutil" && schedutilUpPath != null) ||
                    (currentGov == "interactive" && interactiveHiSpeedPath != null) ||
                    touchBoostPath != null

            if (showTunablesSection || !isRooted) {
                StyledBlockCard(styles = styles, title = "Governor Tunables") {
                    var needsDivider = false

                    if ((currentGov == "schedutil" && schedutilUpPath != null) || !isRooted) {
                        SettingsDropdownRow(
                            title = "Up Rate Limit",
                            subtitle = "Minimum time between frequency increases.",
                            currentValue = if (isRooted) "$schedutilUpRate µs" else "LOCKED",
                            availableValues = timingRates,
                            isRooted = isRooted,
                            styles = styles,
                            onValueSelected = { v ->
                                schedutilUpRate = v
                                scope.launch(Dispatchers.IO) {
                                    RootManager.executeRootCommand("echo $v > $schedutilUpPath")
                                    prefs.edit().putString("saved_sched_uprate", v).apply()
                                }
                            }
                        )
                        needsDivider = true
                    }

                    if ((currentGov == "interactive" && interactiveHiSpeedPath != null) || (!isRooted && !needsDivider)) {
                        if (needsDivider) HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsDropdownRow(
                            title = "Hi-Speed Frequency",
                            subtitle = "Frequency to jump to when task load increases.",
                            currentValue = if (isRooted) "$interactiveHispeedFreq MHz" else "LOCKED",
                            availableValues = availableFreqsDisplay,
                            isRooted = isRooted,
                            styles = styles,
                            onValueSelected = { v ->
                                interactiveHispeedFreq = v.replace(" MHz", "")
                                val khz = interactiveHispeedFreq.toLong() * 1000
                                scope.launch(Dispatchers.IO) {
                                    RootManager.executeRootCommand("echo $khz > $interactiveHiSpeedPath")
                                    prefs.edit().putString("saved_interactive_hispeed", khz.toString()).apply()
                                }
                            }
                        )
                        needsDivider = true
                    }

                    if (touchBoostPath != null || !isRooted) {
                        if (needsDivider) HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsSwitchRow(
                            title = "Touch Boost",
                            subtitle = "Temporarily boost CPU on screen interaction.",
                            checked = isTouchBoostEnabled,
                            styles = styles,
                            onCheckedChange = { isChecked ->
                                isTouchBoostEnabled = isChecked
                                val v = if (isChecked) "1" else "0"
                                scope.launch(Dispatchers.IO) {
                                    RootManager.executeRootCommand("echo $v > $touchBoostPath")
                                    prefs.edit().putString("saved_touchboost", v).apply()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}