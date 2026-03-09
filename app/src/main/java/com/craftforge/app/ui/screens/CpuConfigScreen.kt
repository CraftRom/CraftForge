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
    var runtimeProfile by remember { mutableStateOf(KernelControlEngine.RuntimeProfile()) }
    var isLoading by remember { mutableStateOf(true) }

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

    var globalGovernorSelection by remember { mutableStateOf("—") }
    var commonGovernorOptions by remember { mutableStateOf<List<String>>(emptyList()) }
    var totalCoreCount by remember { mutableStateOf(0) }

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

    fun clusterTierLabel(clusterId: Int): String {
        val maxByCluster = clusters.associate { cluster ->
            cluster.id to (clusterMax[cluster.id]?.substringBefore(" ")?.toLongOrNull() ?: 0L)
        }
        val distinctMax = maxByCluster.values.filter { it > 0L }.distinct().sorted()
        if (distinctMax.isEmpty()) return "Cluster"
        val current = maxByCluster[clusterId] ?: 0L
        val highest = distinctMax.last()
        return when {
            distinctMax.size >= 3 && current == highest && (clusters.firstOrNull { it.id == clusterId }?.cores?.size == 1) -> "Prime"
            current == highest -> "Big"
            distinctMax.size > 1 && current == distinctMax.first() -> "Little"
            else -> "Mid"
        }
    }

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
            isLoading = false
            toast("Root недоступний — режим LOCKED")
            return@LaunchedEffect
        }

        isLoading = true
        val snapshot = withContext(Dispatchers.IO) {
            data class ClusterSnapshot(
                val id: Int,
                val governor: String,
                val minDisplay: String,
                val maxDisplay: String,
                val governorList: List<String>,
                val freqList: List<String>,
                val coreStates: List<Pair<Int, Boolean>>
            )

            data class ScreenSnapshot(
                val runtimeProfile: KernelControlEngine.RuntimeProfile,
                val clusters: List<KernelControlEngine.CpuCluster>,
                val thermal: KernelControlEngine.ThermalInfo?,
                val support: KernelControlEngine.SupportSnapshot,
                val clusterSnapshots: List<ClusterSnapshot>,
                val eas: Boolean,
                val schedBoost: String,
                val upMigrate: String,
                val downMigrate: String,
                val initTaskUtil: String,
                val autoGroup: Boolean,
                val capacityMargin: String,
                val schedutilUpRate: String,
                val interactiveHispeed: String,
                val touchBoost: Boolean,
                val multicoreSaving: Boolean,
                val powerCollapse: Boolean,
                val thermalEnabled: Boolean
            )

            val detectedClusters = KernelControlEngine.detectCpuClusters()
            val detectedThermal = KernelControlEngine.detectThermal()
            val detectedSupport = KernelControlEngine.getSupportSnapshot(forceRefresh = false)
            val profile = KernelControlEngine.getRuntimeProfile(forceRefresh = false)

            val clusterSnapshots = coroutineScope {
                detectedClusters.map { c ->
                    async {
                        val runtime = KernelControlEngine.readClusterRuntimeSnapshot(c)
                        val freqsDisplay = runtime.availableFreqsKHz.map { "${it / 1000} MHz" }
                        ClusterSnapshot(
                            id = c.id,
                            governor = runtime.governor,
                            minDisplay = "${runtime.minKHz / 1000} MHz",
                            maxDisplay = "${runtime.maxKHz / 1000} MHz",
                            governorList = runtime.availableGovernors,
                            freqList = freqsDisplay,
                            coreStates = runtime.coreStates
                        )
                    }
                }.map { it.await() }
            }

            ScreenSnapshot(
                runtimeProfile = profile,
                clusters = detectedClusters,
                thermal = detectedThermal,
                support = detectedSupport,
                clusterSnapshots = clusterSnapshots,
                eas = if (detectedSupport.eas) KernelControlEngine.readEasEnabled() else false,
                schedBoost = if (detectedSupport.schedBoost) KernelControlEngine.readSchedBoost().ifBlank { "0" } else "0",
                upMigrate = if (detectedSupport.migrate) KernelControlEngine.readUpMigrate().ifBlank { "" } else "",
                downMigrate = if (detectedSupport.migrate) KernelControlEngine.readDownMigrate().ifBlank { "" } else "",
                initTaskUtil = if (detectedSupport.initTaskUtil) KernelControlEngine.readInitTaskUtil().ifBlank { "" } else "",
                autoGroup = if (detectedSupport.autoGroup) KernelControlEngine.readAutoGroup() else false,
                capacityMargin = if (detectedSupport.capMarginUp) KernelControlEngine.readCapacityMarginUp().ifBlank { "" } else "",
                schedutilUpRate = if (detectedSupport.schedutilUpRate) KernelControlEngine.readSchedutilUpRateUs().ifBlank { "" } else "",
                interactiveHispeed = if (detectedSupport.interactiveHispeed) KernelControlEngine.readInteractiveHispeedFreqKHz().ifBlank { "" } else "",
                touchBoost = if (detectedSupport.touchBoost) KernelControlEngine.readTouchBoost() else false,
                multicoreSaving = if (detectedSupport.mcSaving) KernelControlEngine.readMulticorePowerSaving() else false,
                powerCollapse = if (detectedSupport.powerCollapse) KernelControlEngine.readPowerCollapse() else false,
                thermalEnabled = if (detectedSupport.thermal) KernelControlEngine.readThermalEnabled() else true
            )
        }

        runtimeProfile = snapshot.runtimeProfile
        clusters = snapshot.clusters
        thermal = snapshot.thermal
        support = snapshot.support

        clusterGov.clear(); clusterMin.clear(); clusterMax.clear(); clusterGovs.clear(); clusterFreqs.clear(); coreOnline.clear()
        snapshot.clusterSnapshots.forEach { clusterState ->
            clusterGov[clusterState.id] = clusterState.governor
            clusterMin[clusterState.id] = clusterState.minDisplay
            clusterMax[clusterState.id] = clusterState.maxDisplay
            clusterGovs[clusterState.id] = clusterState.governorList
            clusterFreqs[clusterState.id] = clusterState.freqList
            clusterState.coreStates.forEach { (coreId, online) -> coreOnline[coreId] = online }
        }

        totalCoreCount = snapshot.clusters.sumOf { it.cores.size }
        commonGovernorOptions = snapshot.clusterSnapshots
            .map { it.governorList.toSet() }
            .reduceOrNull { acc, set -> acc.intersect(set) }
            ?.toList()
            ?.sorted()
            .orEmpty()
        globalGovernorSelection = snapshot.clusterSnapshots
            .map { it.governor }
            .distinct()
            .singleOrNull()
            ?: "Mixed"

        isEasEnabled = snapshot.eas
        currentSchedBoost = snapshot.schedBoost
        currentUpMigrate = snapshot.upMigrate
        currentDownMigrate = snapshot.downMigrate
        currentMigrateDisplay = if (snapshot.upMigrate.isNotBlank() && snapshot.downMigrate.isNotBlank()) displayFromRawMigrate(snapshot.upMigrate, snapshot.downMigrate) else "—"
        currentInitTaskUtilRaw = snapshot.initTaskUtil
        currentInitTaskUtilDisplay = if (snapshot.initTaskUtil.isNotBlank()) displayFromRawInit(snapshot.initTaskUtil) else "—"
        isAutoGroupEnabled = snapshot.autoGroup
        currentCapacityMarginRaw = snapshot.capacityMargin
        schedutilUpRate = snapshot.schedutilUpRate
        interactiveHispeedFreqKHz = snapshot.interactiveHispeed
        isTouchBoostEnabled = snapshot.touchBoost
        isMulticoreSavingEnabled = snapshot.multicoreSaving
        isPowerCollapseEnabled = snapshot.powerCollapse
        isThermalEnabled = snapshot.thermalEnabled
        isLoading = false

        Log.i("CpuConfigScreen", "Support snapshot: $support | runtime=$runtimeProfile")
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

            if (isLoading) {
                StyledBlockCard(styles = styles, title = "Scanning kernel") {
                    SettingsBadgeRow(
                        title = "Підготовка профілю",
                        subtitle = "Сканую доступні ноди, кластери та сумісні тюнінги для поточної прошивки.",
                        value = "SCANNING",
                        isRooted = true,
                        styles = styles
                    )
                }
            }

            InfoCardUniversal(
                title = "Kernel runtime profile",
                info = listOf(
                    "ROM" to runtimeProfile.romFamily,
                    "Vendor" to runtimeProfile.vendorFamily,
                    "SoC" to runtimeProfile.socFamily,
                    "Kernel" to runtimeProfile.kernelFlavor,
                    "Features" to "${listOf(support.eas, support.schedBoost, support.migrate, support.initTaskUtil, support.autoGroup, support.capMarginUp, support.schedutilUpRate, support.interactiveHispeed, support.touchBoost, support.mcSaving, support.powerCollapse, support.thermal).count { it }} / 12",
                    "Clusters" to clusters.size.toString(),
                    "Hints" to runtimeProfile.hints.joinToString().ifBlank { "—" }
                ),
                styles = styles
            )

            StyledBlockCard(styles = styles, title = "Cluster control center") {
                SettingsBadgeRow(
                    title = "Topology",
                    subtitle = "Виявлено $totalCoreCount ядер у ${clusters.size} кластерах. Кожен cluster має власні межі частот і governor, тому масові дії йдуть лише по сумісних параметрах.",
                    value = if (clusters.isEmpty()) "EMPTY" else "READY",
                    isRooted = true,
                    styles = styles
                )

                HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))

                SettingsDropdownRow(
                    title = "Governor для всіх кластерів",
                    subtitle = "Застосовує спільний governor до всіх кластерів, якщо він підтримується на кожному policy. Корисно для швидкого переходу між balanced/performance.",
                    currentValue = globalGovernorSelection,
                    availableValues = commonGovernorOptions,
                    isRooted = true,
                    styles = styles,
                    onValueSelected = { selected ->
                        val oldGovByCluster = clusters.associate { it.id to (clusterGov[it.id] ?: "") }
                        globalGovernorSelection = selected
                        clusters.forEach { clusterGov[it.id] = selected }

                        scope.launch(Dispatchers.IO) {
                            val results = KernelControlEngine.setAllClustersGovernor(clusters, selected)
                            if (results.all { it.ok }) {
                                val editor = prefs.edit()
                                clusters.forEach { editor.putString("cluster_${it.id}_gov", selected) }
                                editor.apply()
                            } else {
                                oldGovByCluster.forEach { (id, value) -> clusterGov[id] = value }
                                globalGovernorSelection = oldGovByCluster.values.distinct().singleOrNull() ?: "Mixed"
                                withContext(Dispatchers.Main) {
                                    toast("All clusters governor: ${results.firstOrNull { !it.ok }?.short() ?: "FAIL"}")
                                }
                            }
                        }
                    }
                )
            }

            // =================================================
            // SECTION 1: Per-cluster + per-core
            // =================================================
            // =================================================
            clusters.forEach { cluster ->
                val cid = cluster.id
                val govValue = clusterGov[cid] ?: "Loading..."
                val govList = clusterGovs[cid] ?: emptyList()
                val minValue = clusterMin[cid] ?: "Loading..."
                val maxValue = clusterMax[cid] ?: "Loading..."
                val freqList = clusterFreqs[cid] ?: emptyList()

                StyledBlockCard(styles = styles, title = "${clusterTierLabel(cid)} cluster $cid • cores ${cluster.cores.joinToString { it.id.toString() }}") {

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
                                    globalGovernorSelection = clusters.map { clusterGov[it.id] ?: selected }.distinct().singleOrNull() ?: "Mixed"
                                } else {
                                    clusterGov[cid] = old ?: govValue
                                    globalGovernorSelection = clusters.map { clusterGov[it.id] ?: "" }.distinct().singleOrNull() ?: "Mixed"
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