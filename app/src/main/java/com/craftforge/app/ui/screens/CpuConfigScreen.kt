// CpuConfigScreen.kt
package com.craftforge.app.ui.screens

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.craftforge.app.ui.theme.*
import com.craftforge.app.util.KernelControlEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CpuConfigScreen(isRooted: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("TweaksPrefs", Context.MODE_PRIVATE)
    val styles = infoCardStyles()
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // ----------------------------
    // System state
    // ----------------------------
    var clusters by remember { mutableStateOf<List<KernelControlEngine.CpuCluster>>(emptyList()) }
    var thermal by remember { mutableStateOf<KernelControlEngine.ThermalInfo?>(null) }

    // ----------------------------
    // UI lists
    // ----------------------------
    val schedBoostLevels = listOf("0 (Disabled)", "1 (Performance)", "2 (Aggressive)", "3 (Max Load)")
    val migrateLevels = listOf("95 85 (Battery)", "85 75 (Default)", "75 65 (Performance)", "60 50 (Gaming)")
    val initUtilLevels = listOf("0 (Max Battery)", "15 (Balanced)", "30 (Performance)", "50 (Gaming)")
    val capacityMarginLevels = listOf("1024 (Balanced)", "1150 (Smooth)", "1280 (Performance)", "1536 (Gaming)")
    val timingRates = listOf("500", "1000", "2000", "4000", "8000", "10000", "20000")

    // ----------------------------
    // Global states (system defaults first)
    // ----------------------------
    var isEasEnabled by remember { mutableStateOf(false) }
    var currentSchedBoost by remember { mutableStateOf("0") }
    var currentMigrateDisplay by remember { mutableStateOf("—") }
    var currentUpMigrate by remember { mutableStateOf("") }
    var currentDownMigrate by remember { mutableStateOf("") }
    var currentInitTaskUtilDisplay by remember { mutableStateOf("—") }
    var currentInitTaskUtilRaw by remember { mutableStateOf("") }
    var isAutoGroupEnabled by remember { mutableStateOf(false) }
    var currentCapacityMarginRaw by remember { mutableStateOf("") }

    var schedutilUpRate by remember { mutableStateOf("") }
    var interactiveHispeedFreqKHz by remember { mutableStateOf("") }
    var isTouchBoostEnabled by remember { mutableStateOf(false) }

    var isMulticoreSavingEnabled by remember { mutableStateOf(false) }
    var isPowerCollapseEnabled by remember { mutableStateOf(false) }
    var isThermalEnabled by remember { mutableStateOf(true) }

    // ----------------------------
    // Support snapshot (capability gating)
    // ----------------------------
    var support by remember { mutableStateOf(KernelControlEngine.SupportSnapshot()) }

    // ----------------------------
    // Per-cluster state maps
    // ----------------------------
    val clusterGov = remember { mutableStateMapOf<Int, String>() }
    val clusterMin = remember { mutableStateMapOf<Int, String>() }
    val clusterMax = remember { mutableStateMapOf<Int, String>() }
    val clusterGovs = remember { mutableStateMapOf<Int, List<String>>() }
    val clusterFreqs = remember { mutableStateMapOf<Int, List<String>>() }

    val coreOnline = remember { mutableStateMapOf<Int, Boolean>() }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun displayFromRawBoost(raw: String): String =
        schedBoostLevels.find { it.startsWith(raw) } ?: "$raw (Custom)"

    fun displayFromRawCapacity(raw: String): String =
        capacityMarginLevels.find { it.startsWith(raw) } ?: "$raw (Custom)"

    fun displayFromRawInit(raw: String): String =
        initUtilLevels.find { it.startsWith(raw) } ?: "$raw (Custom)"

    fun displayFromRawMigrate(up: String, down: String): String {
        val raw = "$up $down"
        return migrateLevels.find { it.startsWith(raw) } ?: "$raw (Custom)"
    }

    fun mhzToKhz(selected: String): Long? =
        selected.replace(" MHz", "").trim().toLongOrNull()?.let { it * 1000L }

    @Composable
    fun NotSupportedRow(title: String, subtitle: String) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(text = title, color = styles.titleTextColor.copy(alpha = 0.6f))
            Spacer(Modifier.height(4.dp))
            Text(
                text = "$subtitle • Не підтримується ядром/прошивкою",
                color = styles.titleTextColor.copy(alpha = 0.55f)
            )
        }
    }

    // ----------------------------
    // Initial probe
    // ----------------------------
    LaunchedEffect(isRooted) {
        if (!isRooted) {
            toast("Root недоступний — режим LOCKED")
            return@LaunchedEffect
        }

        withContext(Dispatchers.IO) {
            // 1) Detect clusters + thermal
            val detectedClusters = KernelControlEngine.detectCpuClusters()
            clusters = detectedClusters
            thermal = KernelControlEngine.detectThermal()

            // 2) Fast support snapshot (single cached call)
            support = KernelControlEngine.getSupportSnapshot(forceRefresh = false)

            // 3) Per-cluster: read defaults + apply prefs overlay
            //    Паралелимо читання, щоб UI швидше наповнився
            coroutineScope {
                detectedClusters.map { c ->
                    async {
                        val govNow = KernelControlEngine.readClusterGovernor(c)
                        val minNow = KernelControlEngine.readClusterMinFreqKHz(c)
                        val maxNow = KernelControlEngine.readClusterMaxFreqKHz(c)

                        val govList = KernelControlEngine.readClusterAvailableGovernors(c)
                        val freqsKHz = KernelControlEngine.readClusterAvailableFreqsKHz(c)
                        val freqsDisplay = freqsKHz.map { "${it / 1000} MHz" }

                        clusterGovs[c.id] = govList
                        clusterFreqs[c.id] = freqsDisplay

                        clusterGov[c.id] = govNow
                        clusterMin[c.id] = "${minNow / 1000} MHz"
                        clusterMax[c.id] = "${maxNow / 1000} MHz"

                        // prefs overlay
                        val prefGov = prefs.getString("cluster_${c.id}_gov", null)
                        val prefMin = prefs.getString("cluster_${c.id}_min_khz", null)?.toLongOrNull()
                        val prefMax = prefs.getString("cluster_${c.id}_max_khz", null)?.toLongOrNull()

                        if (!prefGov.isNullOrBlank()) {
                            val r = KernelControlEngine.setClusterGovernor(c, prefGov)
                            if (r.ok) clusterGov[c.id] = prefGov
                        }

                        if (prefMin != null || prefMax != null) {
                            KernelControlEngine.setClusterFreqSafe(c, prefMin, prefMax)
                            val newMin = KernelControlEngine.readClusterMinFreqKHz(c)
                            val newMax = KernelControlEngine.readClusterMaxFreqKHz(c)
                            clusterMin[c.id] = "${newMin / 1000} MHz"
                            clusterMax[c.id] = "${newMax / 1000} MHz"
                        }

                        // per-core overlay
                        c.cores.forEach { core ->
                            val sys = KernelControlEngine.readCoreOnline(core)
                            coreOnline[core.id] = sys

                            val pref = prefs.getString("core_${core.id}_online", null)
                            if (!pref.isNullOrBlank()) {
                                val enabled = pref.trim() == "1"
                                val r = KernelControlEngine.setCoreOnline(core, enabled)
                                if (r.ok) coreOnline[core.id] = enabled
                            }
                        }
                    }
                }.forEach { it.await() }
            }

            // 4) Global defaults (тільки якщо supported)
            if (support.eas) {
                isEasEnabled = KernelControlEngine.readEasEnabled()
            }
            if (support.schedBoost) {
                currentSchedBoost = KernelControlEngine.readSchedBoost().ifBlank { "0" }
            }

            if (support.migrate) {
                currentUpMigrate = KernelControlEngine.readUpMigrate().ifBlank { "" }
                currentDownMigrate = KernelControlEngine.readDownMigrate().ifBlank { "" }
                currentMigrateDisplay =
                    if (currentUpMigrate.isNotBlank() && currentDownMigrate.isNotBlank())
                        displayFromRawMigrate(currentUpMigrate, currentDownMigrate)
                    else "—"
            }

            if (support.initTaskUtil) {
                currentInitTaskUtilRaw = KernelControlEngine.readInitTaskUtil().ifBlank { "" }
                currentInitTaskUtilDisplay =
                    if (currentInitTaskUtilRaw.isNotBlank()) displayFromRawInit(currentInitTaskUtilRaw) else "—"
            }

            if (support.autoGroup) {
                isAutoGroupEnabled = KernelControlEngine.readAutoGroup()
            }

            if (support.capMarginUp) {
                currentCapacityMarginRaw = KernelControlEngine.readCapacityMarginUp().ifBlank { "" }
            }

            if (support.schedutilUpRate) {
                schedutilUpRate = KernelControlEngine.readSchedutilUpRateUs().ifBlank { "" }
            }

            if (support.interactiveHispeed) {
                interactiveHispeedFreqKHz = KernelControlEngine.readInteractiveHispeedFreqKHz().ifBlank { "" }
            }

            if (support.touchBoost) {
                isTouchBoostEnabled = KernelControlEngine.readTouchBoost()
            }

            if (support.mcSaving) {
                isMulticoreSavingEnabled = KernelControlEngine.readMulticorePowerSaving()
            }

            if (support.powerCollapse) {
                isPowerCollapseEnabled = KernelControlEngine.readPowerCollapse()
            }

            if (support.thermal) {
                isThermalEnabled = KernelControlEngine.readThermalEnabled()
            }

            Log.i("CpuConfigScreen", "Support snapshot: $support")
        }
    }

    // ----------------------------
    // UI
    // ----------------------------
    ConfigScreenLayout(title = "CPU Engineering", styles = styles, onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(bottom = 32.dp)
        ) {
            if (!isRooted) {
                StyledBlockCard(styles = styles, title = "LOCKED") {}
                return@Column
            }

            // =================================================
            // SECTION 1: Per-cluster + per-core
            // =================================================
            clusters.forEach { cluster ->
                val cid = cluster.id
                val govValue = clusterGov[cid] ?: "Loading..."
                val govList = clusterGovs[cid] ?: emptyList()
                val minValue = clusterMin[cid] ?: "Loading..."
                val maxValue = clusterMax[cid] ?: "Loading..."
                val freqList = clusterFreqs[cid] ?: emptyList()

                StyledBlockCard(styles = styles, title = "Cluster $cid (${cluster.cores.size} cores)") {

                    SettingsDropdownRow(
                        title = "Governor",
                        subtitle = "Режим керування частотою. Performance = швидше, Powersave = економніше.",
                        currentValue = govValue,
                        availableValues = govList,
                        isRooted = true,
                        styles = styles,
                        onValueSelected = { selected ->
                            val old = clusterGov[cid]
                            clusterGov[cid] = selected

                            scope.launch(Dispatchers.IO) {
                                val r = KernelControlEngine.setClusterGovernor(cluster, selected)
                                if (r.ok) {
                                    prefs.edit().putString("cluster_${cid}_gov", selected).apply()
                                } else {
                                    clusterGov[cid] = old ?: govValue
                                    withContext(Dispatchers.Main) { toast("Cluster $cid governor: ${r.short()}") }
                                }
                            }
                        }
                    )

                    if (freqList.isNotEmpty()) {
                        HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))

                        SettingsDropdownRow(
                            title = "Max Frequency",
                            subtitle = "Максимальна межа частоти для цього кластера. Вище = більше FPS/швидкодії, але більше нагрів.",
                            currentValue = maxValue,
                            availableValues = freqList,
                            isRooted = true,
                            styles = styles,
                            onValueSelected = { selected ->
                                val newMax = mhzToKhz(selected) ?: return@SettingsDropdownRow
                                val oldMax = clusterMax[cid]
                                clusterMax[cid] = selected

                                scope.launch(Dispatchers.IO) {
                                    val rs = KernelControlEngine.setClusterFreqSafe(cluster, newMaxKHz = newMax)
                                    val anyOk = rs.any { it.ok }
                                    val newMinReal = KernelControlEngine.readClusterMinFreqKHz(cluster)
                                    val newMaxReal = KernelControlEngine.readClusterMaxFreqKHz(cluster)

                                    if (anyOk) {
                                        clusterMin[cid] = "${newMinReal / 1000} MHz"
                                        clusterMax[cid] = "${newMaxReal / 1000} MHz"
                                        prefs.edit().putString("cluster_${cid}_max_khz", newMax.toString()).apply()
                                    } else {
                                        clusterMax[cid] = oldMax ?: maxValue
                                        withContext(Dispatchers.Main) { toast("Cluster $cid max: ${rs.firstOrNull()?.short() ?: "FAIL"}") }
                                    }
                                }
                            }
                        )

                        HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))

                        SettingsDropdownRow(
                            title = "Min Frequency",
                            subtitle = "Мінімальна межа частоти. Вище = плавніше (менше просідань), але гірше для батареї.",
                            currentValue = minValue,
                            availableValues = freqList,
                            isRooted = true,
                            styles = styles,
                            onValueSelected = { selected ->
                                val newMin = mhzToKhz(selected) ?: return@SettingsDropdownRow
                                val oldMin = clusterMin[cid]
                                clusterMin[cid] = selected

                                scope.launch(Dispatchers.IO) {
                                    val rs = KernelControlEngine.setClusterFreqSafe(cluster, newMinKHz = newMin)
                                    val anyOk = rs.any { it.ok }
                                    val newMinReal = KernelControlEngine.readClusterMinFreqKHz(cluster)
                                    val newMaxReal = KernelControlEngine.readClusterMaxFreqKHz(cluster)

                                    if (anyOk) {
                                        clusterMin[cid] = "${newMinReal / 1000} MHz"
                                        clusterMax[cid] = "${newMaxReal / 1000} MHz"
                                        prefs.edit().putString("cluster_${cid}_min_khz", newMin.toString()).apply()
                                    } else {
                                        clusterMin[cid] = oldMin ?: minValue
                                        withContext(Dispatchers.Main) { toast("Cluster $cid min: ${rs.firstOrNull()?.short() ?: "FAIL"}") }
                                    }
                                }
                            }
                        )
                    }

                    HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))

                    cluster.cores.forEach { core ->
                        val online = coreOnline[core.id] ?: true
                        SettingsSwitchRow(
                            title = "Core ${core.id}",
                            subtitle = "Вмик/вимик ядра CPU. Деякі ядра (особливо cpu0) не можна вимкнути.",
                            checked = online,
                            styles = styles,
                            onCheckedChange = { enabled ->
                                val old = coreOnline[core.id] ?: online
                                coreOnline[core.id] = enabled

                                scope.launch(Dispatchers.IO) {
                                    val r = KernelControlEngine.setCoreOnline(core, enabled)
                                    if (r.ok) {
                                        prefs.edit().putString("core_${core.id}_online", if (enabled) "1" else "0").apply()
                                    } else {
                                        coreOnline[core.id] = old
                                        withContext(Dispatchers.Main) { toast("Core ${core.id}: ${r.short()}") }
                                    }
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }

            // =================================================
            // SECTION 2: EAS / Scheduler
            // =================================================
            StyledBlockCard(styles = styles, title = "Scheduler (планувальник задач)") {

                if (!support.eas) {
                    NotSupportedRow("Enable EAS", "Енерго-орієнтований планувальник (EAS)")
                } else {
                    SettingsSwitchRow(
                        title = "Enable EAS",
                        subtitle = "Розподіляє навантаження по ядрах з акцентом на економію. Часто добре для батареї.",
                        checked = isEasEnabled,
                        styles = styles,
                        onCheckedChange = { checked ->
                            val old = isEasEnabled
                            isEasEnabled = checked
                            scope.launch(Dispatchers.IO) {
                                val r = KernelControlEngine.setEasEnabled(checked)
                                if (r.ok) {
                                    prefs.edit().putString("saved_eas_enable", if (checked) "1" else "0").apply()
                                } else {
                                    isEasEnabled = old
                                    withContext(Dispatchers.Main) { toast("EAS: ${r.short()}") }
                                }
                            }
                        }
                    )
                }

                HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))

                if (!support.schedBoost) {
                    NotSupportedRow("Scheduler Boost", "Підсилення реактивності планувальника")
                } else {
                    SettingsDropdownRow(
                        title = "Scheduler Boost",
                        subtitle = "Підвищує пріоритет/швидкість реакції системи. Вище = швидше, але більше витрат.",
                        currentValue = displayFromRawBoost(currentSchedBoost),
                        availableValues = schedBoostLevels,
                        isRooted = true,
                        styles = styles,
                        onValueSelected = { selected ->
                            val raw = selected.substringBefore(" ").trim()
                            val old = currentSchedBoost
                            currentSchedBoost = raw

                            scope.launch(Dispatchers.IO) {
                                val r = KernelControlEngine.setSchedBoost(raw)
                                if (r.ok) {
                                    prefs.edit().putString("saved_sched_boost", raw).apply()
                                } else {
                                    currentSchedBoost = old
                                    withContext(Dispatchers.Main) { toast("sched_boost: ${r.short()}") }
                                }
                            }
                        }
                    )
                }

                HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))

                if (!support.migrate) {
                    NotSupportedRow("Up/Down Migrate", "Пороги міграції між кластерами")
                } else {
                    SettingsDropdownRow(
                        title = "Up/Down Migrate",
                        subtitle = "Коли таски перекидаються на “сильні” ядра і назад. Нижче пороги = агресивніша продуктивність.",
                        currentValue = currentMigrateDisplay,
                        availableValues = migrateLevels,
                        isRooted = true,
                        styles = styles,
                        onValueSelected = { selected ->
                            val raw = selected.substringBefore(" (").trim()
                            val parts = raw.split(" ").filter { it.isNotBlank() }
                            if (parts.size < 2) return@SettingsDropdownRow
                            val up = parts[0]
                            val down = parts[1]

                            val oldUp = currentUpMigrate
                            val oldDown = currentDownMigrate
                            val oldDisplay = currentMigrateDisplay

                            currentUpMigrate = up
                            currentDownMigrate = down
                            currentMigrateDisplay = displayFromRawMigrate(up, down)

                            scope.launch(Dispatchers.IO) {
                                val r1 = KernelControlEngine.setUpMigrate(up)
                                val r2 = KernelControlEngine.setDownMigrate(down)

                                if (r1.ok && r2.ok) {
                                    prefs.edit().putString("saved_up_migrate", up).putString("saved_down_migrate", down).apply()
                                } else {
                                    currentUpMigrate = oldUp
                                    currentDownMigrate = oldDown
                                    currentMigrateDisplay = oldDisplay
                                    withContext(Dispatchers.Main) { toast("migrate: ${if (!r1.ok) r1.short() else r2.short()}") }
                                }
                            }
                        }
                    )
                }

                HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))

                if (!support.initTaskUtil) {
                    NotSupportedRow("Initial Task Util", "Стартова “вага” нових задач")
                } else {
                    SettingsDropdownRow(
                        title = "Initial Task Util",
                        subtitle = "Наскільки “важкою” планувальник вважає нову задачу. Вище = швидше розганяє CPU.",
                        currentValue = currentInitTaskUtilDisplay,
                        availableValues = initUtilLevels,
                        isRooted = true,
                        styles = styles,
                        onValueSelected = { selected ->
                            val raw = selected.substringBefore(" ").trim()
                            val oldRaw = currentInitTaskUtilRaw
                            val oldDisplay = currentInitTaskUtilDisplay

                            currentInitTaskUtilRaw = raw
                            currentInitTaskUtilDisplay = displayFromRawInit(raw)

                            scope.launch(Dispatchers.IO) {
                                val r = KernelControlEngine.setInitTaskUtil(raw)
                                if (r.ok) {
                                    prefs.edit().putString("saved_init_task_util_raw", raw).apply()
                                } else {
                                    currentInitTaskUtilRaw = oldRaw
                                    currentInitTaskUtilDisplay = oldDisplay
                                    withContext(Dispatchers.Main) { toast("init_task_util: ${r.short()}") }
                                }
                            }
                        }
                    )
                }

                HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))

                if (!support.autoGroup) {
                    NotSupportedRow("Autogroup", "Групування задач для інтерфейсу")
                } else {
                    SettingsSwitchRow(
                        title = "Autogroup",
                        subtitle = "Покращує чуйність UI, групуючи процеси. Іноді може впливати на “ровність” навантаження.",
                        checked = isAutoGroupEnabled,
                        styles = styles,
                        onCheckedChange = { checked ->
                            val old = isAutoGroupEnabled
                            isAutoGroupEnabled = checked

                            scope.launch(Dispatchers.IO) {
                                val r = KernelControlEngine.setAutoGroup(checked)
                                if (r.ok) {
                                    prefs.edit().putString("saved_autogroup", if (checked) "1" else "0").apply()
                                } else {
                                    isAutoGroupEnabled = old
                                    withContext(Dispatchers.Main) { toast("autogroup: ${r.short()}") }
                                }
                            }
                        }
                    )
                }

                HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))

                if (!support.capMarginUp) {
                    NotSupportedRow("Capacity Margin (Up)", "Запас потужності для бусту")
                } else {
                    SettingsDropdownRow(
                        title = "Capacity Margin (Up)",
                        subtitle = "Додає “запас” при вирішенні, коли буститись. Вище = агресивніша продуктивність.",
                        currentValue = displayFromRawCapacity(currentCapacityMarginRaw.ifBlank { "—" }),
                        availableValues = capacityMarginLevels,
                        isRooted = true,
                        styles = styles,
                        onValueSelected = { selected ->
                            val raw = selected.substringBefore(" ").trim()
                            val old = currentCapacityMarginRaw
                            currentCapacityMarginRaw = raw

                            scope.launch(Dispatchers.IO) {
                                val r = KernelControlEngine.setCapacityMargin(raw)
                                if (r.ok) {
                                    prefs.edit().putString("saved_capacity_margin", raw).apply()
                                } else {
                                    currentCapacityMarginRaw = old
                                    withContext(Dispatchers.Main) { toast("capacity_margin: ${r.short()}") }
                                }
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // =================================================
            // SECTION 3: Tunables
            // =================================================
            StyledBlockCard(styles = styles, title = "Governor tunables") {

                if (!support.schedutilUpRate) {
                    NotSupportedRow("Schedutil Up Rate", "Ліміт частоти підйому (schedutil)")
                } else {
                    SettingsDropdownRow(
                        title = "Schedutil Up Rate",
                        subtitle = "Як швидко schedutil може піднімати частоту. Нижче = швидша реакція, але більше споживання.",
                        currentValue = if (schedutilUpRate.isBlank()) "—" else schedutilUpRate,
                        availableValues = timingRates,
                        isRooted = true,
                        styles = styles,
                        onValueSelected = { selected ->
                            val old = schedutilUpRate
                            schedutilUpRate = selected

                            scope.launch(Dispatchers.IO) {
                                val r = KernelControlEngine.setSchedutilUpRateUs(selected)
                                if (r.ok) {
                                    prefs.edit().putString("saved_schedutil_up_rate", selected).apply()
                                } else {
                                    schedutilUpRate = old
                                    withContext(Dispatchers.Main) { toast("schedutil: ${r.short()}") }
                                }
                            }
                        }
                    )
                }

                val anyFreqList = clusters.firstOrNull()?.let { c -> clusterFreqs[c.id] } ?: emptyList()
                HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))

                if (!support.interactiveHispeed) {
                    NotSupportedRow("Interactive Hispeed Freq", "Поріг “hispeed” (interactive)")
                } else {
                    SettingsDropdownRow(
                        title = "Interactive Hispeed Freq",
                        subtitle = "Частота, на яку governor стрибає при навантаженні. Вище = швидше, але гарячіше.",
                        currentValue = interactiveHispeedFreqKHz.toLongOrNull()?.let { "${it / 1000} MHz" } ?: "—",
                        availableValues = anyFreqList,
                        isRooted = true,
                        styles = styles,
                        onValueSelected = { selected ->
                            val mhz = selected.replace(" MHz", "").trim().toLongOrNull() ?: return@SettingsDropdownRow
                            val khz = (mhz * 1000).toString()
                            val old = interactiveHispeedFreqKHz
                            interactiveHispeedFreqKHz = khz

                            scope.launch(Dispatchers.IO) {
                                val r = KernelControlEngine.setInteractiveHispeedFreqKHz(khz)
                                if (r.ok) {
                                    prefs.edit().putString("saved_interactive_hispeed_khz", khz).apply()
                                } else {
                                    interactiveHispeedFreqKHz = old
                                    withContext(Dispatchers.Main) { toast("hispeed: ${r.short()}") }
                                }
                            }
                        }
                    )
                }

                HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))

                if (!support.touchBoost) {
                    NotSupportedRow("TouchBoost", "Буст при торканні (Qualcomm/подібне)")
                } else {
                    SettingsSwitchRow(
                        title = "TouchBoost",
                        subtitle = "Короткий буст частоти при торканні екрану. Візуально дає чуйніший UI, але трошки їсть батарею.",
                        checked = isTouchBoostEnabled,
                        styles = styles,
                        onCheckedChange = { checked ->
                            val old = isTouchBoostEnabled
                            isTouchBoostEnabled = checked

                            scope.launch(Dispatchers.IO) {
                                val r = KernelControlEngine.setTouchBoost(checked)
                                if (r.ok) {
                                    prefs.edit().putString("saved_touchboost", if (checked) "1" else "0").apply()
                                } else {
                                    isTouchBoostEnabled = old
                                    withContext(Dispatchers.Main) { toast("touchboost: ${r.short()}") }
                                }
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // =================================================
            // SECTION 4: Power
            // =================================================
            StyledBlockCard(styles = styles, title = "Power") {

                if (!support.mcSaving) {
                    NotSupportedRow("Multicore Power Saving", "Економія на багатоядерності")
                } else {
                    SettingsSwitchRow(
                        title = "Multicore Power Saving",
                        subtitle = "Обмежує/оптимізує використання багатьох ядер. Може продовжити батарею, але знизити пікову продуктивність.",
                        checked = isMulticoreSavingEnabled,
                        styles = styles,
                        onCheckedChange = { checked ->
                            val old = isMulticoreSavingEnabled
                            isMulticoreSavingEnabled = checked

                            scope.launch(Dispatchers.IO) {
                                val r = KernelControlEngine.setMulticorePowerSaving(checked)
                                if (r.ok) {
                                    prefs.edit().putString("saved_mc", if (checked) "1" else "0").apply()
                                } else {
                                    isMulticoreSavingEnabled = old
                                    withContext(Dispatchers.Main) { toast("mc_power: ${r.short()}") }
                                }
                            }
                        }
                    )
                }

                HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))

                if (!support.powerCollapse) {
                    NotSupportedRow("Power Collapse", "Глибокий сон CPU")
                } else {
                    SettingsSwitchRow(
                        title = "Power Collapse",
                        subtitle = "Дозволяє глибші стани сну CPU. Краще для standby, інколи може впливати на миттєву чуйність.",
                        checked = isPowerCollapseEnabled,
                        styles = styles,
                        onCheckedChange = { checked ->
                            val old = isPowerCollapseEnabled
                            isPowerCollapseEnabled = checked

                            scope.launch(Dispatchers.IO) {
                                val r = KernelControlEngine.setPowerCollapse(checked)
                                if (r.ok) {
                                    prefs.edit().putString("saved_pc", if (checked) "1" else "0").apply()
                                } else {
                                    isPowerCollapseEnabled = old
                                    withContext(Dispatchers.Main) { toast("power_collapse: ${r.short()}") }
                                }
                            }
                        }
                    )
                }
            }

            // =================================================
            // SECTION 5: Thermal
            // =================================================
            thermal?.let { _ ->
                Spacer(modifier = Modifier.height(12.dp))
                StyledBlockCard(styles = styles, title = "Thermal") {
                    if (!support.thermal) {
                        NotSupportedRow("Thermal throttling", "Керування термо-захистом")
                    } else {
                        SettingsSwitchRow(
                            title = "Thermal throttling",
                            subtitle = "Захист від перегріву (тротлінг). Вимикати небезпечно: можливі перегрів/вильоти/тротлінг по залізу.",
                            checked = isThermalEnabled,
                            styles = styles,
                            onCheckedChange = { enabled ->
                                val old = isThermalEnabled
                                isThermalEnabled = enabled

                                scope.launch(Dispatchers.IO) {
                                    val r = KernelControlEngine.setThermalEnabled(enabled)
                                    if (r.ok) {
                                        prefs.edit().putString("saved_thermal", if (enabled) "1" else "0").apply()
                                    } else {
                                        isThermalEnabled = old
                                        withContext(Dispatchers.Main) { toast("thermal: ${r.short()}") }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}