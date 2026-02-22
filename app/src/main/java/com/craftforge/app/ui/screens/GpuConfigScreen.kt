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
import androidx.compose.ui.unit.sp
import com.craftforge.app.ui.screens.RootManager.readNodeViaRoot
import com.craftforge.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun GpuConfigScreen(isRooted: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("TweaksPrefs", Context.MODE_PRIVATE)
    val styles = infoCardStyles()
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // БАЗОВІ ШЛЯХИ
    val adrenoPath = "/sys/class/kgsl/kgsl-3d0"
    val devfreqPath = "$adrenoPath/devfreq"

    // СТАН ПІДТРИМКИ (Чи це взагалі Adreno GPU)
    var isAdrenoGpu by remember { mutableStateOf(false) }

    // СТАН СИСТЕМИ (Говернор та Частоти)
    var currentGov by remember { mutableStateOf("Loading...") }
    var availableGovs by remember { mutableStateOf(listOf<String>()) }
    var currentMinFreq by remember { mutableStateOf("Loading...") }
    var currentMaxFreq by remember { mutableStateOf("Loading...") }
    var availableFreqsDisplay by remember { mutableStateOf(listOf<String>()) }

    // СТАНИ ПІДТРИМКИ (Feature Flags)
    var isAdrenoBoostSupported by remember { mutableStateOf(false) }
    var currentAdrenoBoost by remember { mutableStateOf("") }
    val adrenoBoostLevels = listOf("0 (Off)", "1 (Low)", "2 (Medium)", "3 (High)")

    var isAdrenoIdlerSupported by remember { mutableStateOf(false) }
    var isAdrenoIdlerEnabled by remember { mutableStateOf(false) }
    val idlerPath = "/sys/module/adreno_idler/parameters/adreno_idler_active"

    var isIdleTimerSupported by remember { mutableStateOf(false) }
    var currentIdleTimer by remember { mutableStateOf("") }
    val idleTimerLevels = listOf("10 ms", "50 ms", "80 ms", "120 ms", "250 ms")

    LaunchedEffect(isRooted) {
        if (isRooted) {
            withContext(Dispatchers.IO) {
                // Безпечна функція для читання вузлів
                suspend fun safeRead(path: String): String? {
                    val res = readNodeViaRoot("cat $path")
                    if (res == null || res.isBlank() || res.contains("No such file") || res.contains("Not a directory")) {
                        return null
                    }
                    return res.trim()
                }

                // 1. ПЕРЕВІРКА GPU (Чи є папка kgsl-3d0)
                isAdrenoGpu = safeRead("$devfreqPath/governor") != null

                if (isAdrenoGpu) {
                    val gov = safeRead("$devfreqPath/governor") ?: "Unknown"
                    val govListRaw = safeRead("$devfreqPath/available_governors") ?: ""
                    val govList = govListRaw.split(" ").filter { it.isNotBlank() }

                    val freqListRaw = safeRead("$devfreqPath/available_frequencies") ?: ""
                    val rawFreqs = freqListRaw.split(" ").filter { it.isNotBlank() }.mapNotNull { it.toLongOrNull() }.sorted()

                    val minF = safeRead("$devfreqPath/min_freq")?.let { "${it.toLong() / 1000000} MHz" } ?: "Unknown"
                    val maxF = safeRead("$devfreqPath/max_freq")?.let { "${it.toLong() / 1000000} MHz" } ?: "Unknown"

                    // 2. ADRENO BOOST (Кастомний патч)
                    val boostRaw = safeRead("$devfreqPath/adrenoboost")
                    isAdrenoBoostSupported = boostRaw != null
                    currentAdrenoBoost = when (boostRaw) {
                        "0" -> "0 (Off)"
                        "1" -> "1 (Low)"
                        "2" -> "2 (Medium)"
                        "3" -> "3 (High)"
                        else -> boostRaw ?: ""
                    }

                    // 3. ADRENO IDLER (Кастомний патч від arter97)
                    val idlerRaw = safeRead(idlerPath)
                    isAdrenoIdlerSupported = idlerRaw != null
                    isAdrenoIdlerEnabled = idlerRaw == "Y" || idlerRaw == "1"

                    // 4. IDLE TIMER (Стандартна фіча Qualcomm, але іноді заблокована)
                    val timerRaw = safeRead("$adrenoPath/idle_timer")
                    isIdleTimerSupported = timerRaw != null
                    currentIdleTimer = timerRaw?.let { "$it ms" } ?: ""

                    // Оновлюємо UI стани
                    currentGov = gov
                    availableGovs = govList
                    availableFreqsDisplay = rawFreqs.map { "${it / 1000000} MHz" }
                    currentMinFreq = minF
                    currentMaxFreq = maxF
                } else {
                    currentGov = "NOT SUPPORTED (Mali/Unknown)"
                }
            }
        } else {
            currentGov = "LOCKED"
            currentMinFreq = "LOCKED"
            currentMaxFreq = "LOCKED"
        }
    }

    ConfigScreenLayout(title = "GPU Tuning", styles = styles, onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(bottom = 32.dp)
        ) {

            // Якщо це не Adreno, показуємо заглушку
            if (isRooted && !isAdrenoGpu && currentGov.contains("NOT SUPPORTED")) {
                StyledBlockCard(styles = styles, title = "Unsupported GPU") {
                    InfoRowStyled(
                        label = "Warning",
                        value = "Your device does not use a standard Qualcomm Adreno GPU. GPU tuning is currently limited.",
                        labelColor = styles.accentColor,
                        valueColor = styles.valueTextColor,
                        fontSize = 14.sp
                    )
                }
                return@ConfigScreenLayout
            }

            // --- СЕКЦІЯ 1: ПРОФІЛЬ ТА ЧАСТОТИ ---
            StyledBlockCard(styles = styles, title = "Graphics Engine") {
                SettingsDropdownRow(
                    title = "GPU Governor",
                    subtitle = "Optimizes Adreno rendering performance.",
                    currentValue = currentGov,
                    availableValues = availableGovs,
                    isRooted = isRooted,
                    styles = styles,
                    onValueSelected = { selected ->
                        currentGov = selected
                        scope.launch(Dispatchers.IO) {
                            RootManager.executeRootCommand("echo $selected > $devfreqPath/governor")
                            prefs.edit().putString("saved_gpu_governor", selected).apply()
                            withContext(Dispatchers.Main) { Toast.makeText(context, "GPU Governor: $selected", Toast.LENGTH_SHORT).show() }
                        }
                    }
                )

                if (availableFreqsDisplay.isNotEmpty()) {
                    HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))

                    SettingsDropdownRow(
                        title = "Max Frequency",
                        subtitle = "Cap maximum 3D performance to save battery.",
                        currentValue = currentMaxFreq,
                        availableValues = availableFreqsDisplay,
                        isRooted = isRooted,
                        styles = styles,
                        onValueSelected = { selectedDisplay ->
                            currentMaxFreq = selectedDisplay
                            val rawHz = (selectedDisplay.replace(" MHz", "").toLongOrNull() ?: 0L) * 1000000
                            if (rawHz > 0) {
                                scope.launch(Dispatchers.IO) {
                                    RootManager.executeRootCommand("echo $rawHz > $devfreqPath/max_freq")
                                    prefs.edit().putString("saved_gpu_max_freq", rawHz.toString()).apply()
                                }
                            }
                        }
                    )

                    HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))

                    SettingsDropdownRow(
                        title = "Min Frequency",
                        subtitle = "Raise base frequency to eliminate UI stuttering.",
                        currentValue = currentMinFreq,
                        availableValues = availableFreqsDisplay,
                        isRooted = isRooted,
                        styles = styles,
                        onValueSelected = { selectedDisplay ->
                            currentMinFreq = selectedDisplay
                            val rawHz = (selectedDisplay.replace(" MHz", "").toLongOrNull() ?: 0L) * 1000000
                            if (rawHz > 0) {
                                scope.launch(Dispatchers.IO) {
                                    RootManager.executeRootCommand("echo $rawHz > $devfreqPath/min_freq")
                                    prefs.edit().putString("saved_gpu_min_freq", rawHz.toString()).apply()
                                }
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // --- СЕКЦІЯ 2: POWER & IDLE (Динамічна) ---
            val showIdleSection = isAdrenoIdlerSupported || isIdleTimerSupported
            if (showIdleSection || !isRooted) {
                StyledBlockCard(styles = styles, title = "Power & Idle Management") {

                    if (isAdrenoIdlerSupported || !isRooted) {
                        SettingsSwitchRow(
                            title = "Adreno Idler",
                            subtitle = "Aggressively drops GPU freq when idle to save battery.",
                            checked = isAdrenoIdlerEnabled,
                            styles = styles,
                            onCheckedChange = { isChecked ->
                                isAdrenoIdlerEnabled = isChecked
                                val value = if (isChecked) "Y" else "N"
                                scope.launch(Dispatchers.IO) {
                                    RootManager.executeRootCommand("echo $value > $idlerPath")
                                    prefs.edit().putString("saved_adreno_idler", value).apply()
                                }
                            }
                        )
                    }

                    if (isIdleTimerSupported || !isRooted) {
                        if (isAdrenoIdlerSupported) HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsDropdownRow(
                            title = "GPU Idle Timer",
                            subtitle = "Time before GPU sleeps. Higher = Smoother, Lower = Battery.",
                            currentValue = if (isRooted) currentIdleTimer else "LOCKED",
                            availableValues = idleTimerLevels,
                            isRooted = isRooted,
                            styles = styles,
                            onValueSelected = { selected ->
                                currentIdleTimer = selected
                                val timeMs = selected.replace(" ms", "")
                                scope.launch(Dispatchers.IO) {
                                    RootManager.executeRootCommand("echo $timeMs > $adrenoPath/idle_timer")
                                    prefs.edit().putString("saved_gpu_idle_timer", timeMs).apply()
                                }
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // --- СЕКЦІЯ 3: ADVANCED RENDERING (Динамічна) ---
            if (isAdrenoBoostSupported || !isRooted) {
                StyledBlockCard(styles = styles, title = "Advanced Rendering") {
                    SettingsDropdownRow(
                        title = "Adreno Boost",
                        subtitle = "Dynamic frequency spike on heavy 3D load.",
                        currentValue = if (isRooted) currentAdrenoBoost else "LOCKED",
                        availableValues = adrenoBoostLevels,
                        isRooted = isRooted,
                        styles = styles,
                        onValueSelected = { selected ->
                            currentAdrenoBoost = selected
                            val boostLevel = selected.substring(0, 1)
                            scope.launch(Dispatchers.IO) {
                                RootManager.executeRootCommand("echo $boostLevel > $devfreqPath/adrenoboost")
                                prefs.edit().putString("saved_adrenoboost", boostLevel).apply()
                            }
                        }
                    )
                }
            }
        }
    }
}