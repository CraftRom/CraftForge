package com.craftforge.app.util

import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

object KernelControlEngine {

    private const val TAG = "KernelControlEngine"

    data class CpuCore(val id: Int, val onlinePath: String?)

    data class CpuCluster(
        val id: Int,
        val policyPath: String,
        val cores: List<CpuCore>,
        val minFreqPath: String?,
        val maxFreqPath: String?,
        val govPath: String?,
        val availableGovsPath: String?,
        val availableFreqsPath: String?
    )

    data class ThermalInfo(val throttlingPath: String?, val tempPath: String?)

    data class RuntimeProfile(
        val romFamily: String = "Android",
        val vendorFamily: String = "Generic",
        val socFamily: String = "Generic",
        val kernelFlavor: String = "Unknown",
        val hints: List<String> = emptyList()
    )

    data class SupportSnapshot(
        val eas: Boolean = false,
        val schedBoost: Boolean = false,
        val migrate: Boolean = false,
        val initTaskUtil: Boolean = false,
        val autoGroup: Boolean = false,
        val capMarginUp: Boolean = false,
        val schedutilUpRate: Boolean = false,
        val interactiveHispeed: Boolean = false,
        val touchBoost: Boolean = false,
        val mcSaving: Boolean = false,
        val powerCollapse: Boolean = false,
        val thermal: Boolean = false,
    )

    data class WriteResult(
        val ok: Boolean,
        val key: String,
        val path: String?,
        val value: String,
        val before: String?,
        val after: String?,
        val method: String?,
        val reason: String?
    ) {
        fun short(): String {
            if (ok) return "OK key=$key path=$path method=$method"
            return "FAIL key=$key path=$path reason=$reason before=$before after=$after method=$method"
        }
    }

    private val resolved = ConcurrentHashMap<String, String>()
    private val unsupported = ConcurrentHashMap.newKeySet<String>()
    private val writeLocks = ConcurrentHashMap<String, Mutex>()

    @Volatile private var supportSnapshotCache: SupportSnapshot? = null
    @Volatile private var cpuClusterCache: List<CpuCluster>? = null
    @Volatile private var runtimeProfileCache: RuntimeProfile? = null

    fun isSupported(key: String): Boolean = !unsupported.contains(key)

    private fun norm(v: String): String = v.trim().split(Regex("s+")).firstOrNull().orEmpty()

    private fun getProp(name: String): String = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val m = cls.getMethod("get", String::class.java, String::class.java)
        (m.invoke(null, name, "") as? String).orEmpty().trim()
    }.getOrDefault("")

    private fun read(path: String): String? = RootManager.readNode(path)

    private suspend fun writeRaw(path: String, value: String, key: String): WriteResult = withContext(Dispatchers.IO) {
        if (!RootManager.nodeExists(path)) {
            return@withContext WriteResult(false, key, path, value, null, null, null, "node_missing")
        }

        val before = read(path)
        val target = norm(value)
        if (before != null && norm(before) == target) {
            return@withContext WriteResult(true, key, path, target, before, before, "noop_same_value", null)
        }

        val lock = writeLocks.getOrPut(path) { Mutex() }
        lock.withLock {
            val latestBefore = read(path)
            if (latestBefore != null && norm(latestBefore) == target) {
                return@withLock WriteResult(true, key, path, target, latestBefore, latestBefore, "noop_same_value", null)
            }

            val result = RootManager.writeNode(path, target, verify = true)
            val after = read(path)
            val ok = result.ok && after != null && norm(after) == target
            RootManager.invalidateNodeCache(path)
            return@withLock if (ok) {
                WriteResult(true, key, path, target, latestBefore, after, "root_write", null)
            } else {
                WriteResult(false, key, path, target, latestBefore, after, "root_write", result.err.ifBlank { "readback_mismatch_or_rejected" })
            }
        }
    }

    private suspend fun resolveNode(
        key: String,
        candidates: List<String>,
        verifyWriteNoop: Boolean = true
    ): String? = withContext(Dispatchers.IO) {
        resolved[key]?.let { return@withContext it }
        if (unsupported.contains(key)) return@withContext null

        val existing = candidates.distinct().filter { RootManager.nodeExists(it) }
        if (existing.isEmpty()) {
            unsupported.add(key)
            Log.w(TAG, "resolve: no existing node for key=$key candidates=${candidates.size}")
            return@withContext null
        }

        val pick = if (!verifyWriteNoop) {
            existing.firstOrNull()
        } else {
            existing.firstOrNull { RootManager.nodeWritable(it) }
        }

        if (pick != null) {
            resolved[key] = pick
            Log.i(TAG, "resolve: key=$key -> $pick verifyWrite=$verifyWriteNoop")
            return@withContext pick
        }

        unsupported.add(key)
        Log.w(TAG, "resolve: no working node for key=$key candidates=${existing.size}")
        null
    }

    fun getRuntimeProfile(forceRefresh: Boolean = false): RuntimeProfile {
        if (forceRefresh) runtimeProfileCache = null
        runtimeProfileCache?.let { return it }

        val vendor = Build.MANUFACTURER.orEmpty().ifBlank { Build.BRAND.orEmpty() }.ifBlank { "Generic" }
        val hardware = Build.HARDWARE.orEmpty().lowercase()
        val board = Build.BOARD.orEmpty().lowercase()
        val fingerprint = Build.FINGERPRINT.orEmpty().lowercase()
        val kernelVersion = RootManager.readNodeViaRoot("cat /proc/version")?.lowercase().orEmpty()

        val romFamily = when {
            getProp("ro.mi.os.version.name").isNotBlank() || getProp("ro.mi.os.version.code").isNotBlank() -> "HyperOS"
            getProp("ro.miui.ui.version.name").isNotBlank() -> "MIUI"
            getProp("ro.build.version.oneui").isNotBlank() -> "One UI"
            getProp("ro.build.version.oplusrom").isNotBlank() || getProp("ro.build.version.opporom").isNotBlank() -> "ColorOS"
            getProp("ro.build.version.realmeui").isNotBlank() -> "realme UI"
            getProp("ro.oxygen.version").isNotBlank() || getProp("ro.build.version.oxygen").isNotBlank() -> "OxygenOS"
            getProp("ro.vivo.os.version").isNotBlank() -> "Funtouch / OriginOS"
            getProp("ro.build.version.emui").isNotBlank() -> "EMUI"
            getProp("ro.lineage.version").isNotBlank() -> "LineageOS"
            getProp("ro.crdroid.version").isNotBlank() -> "crDroid"
            vendor.equals("Google", ignoreCase = true) || fingerprint.contains("pixel") -> "Pixel UI"
            fingerprint.contains("aosp") || getProp("ro.build.flavor").contains("aosp", ignoreCase = true) -> "AOSP-like"
            else -> vendor.ifBlank { "Android" }
        }

        val socFamily = when {
            hardware.contains("qcom") || hardware.contains("kona") || hardware.contains("kalama") || board.contains("sm") -> "Qualcomm"
            hardware.contains("mt") || hardware.contains("mediatek") || board.contains("mt") -> "MediaTek"
            hardware.contains("exynos") || board.contains("exynos") -> "Samsung Exynos"
            hardware.contains("gs") || fingerprint.contains("tensor") -> "Google Tensor"
            hardware.contains("kirin") || board.contains("kirin") -> "HiSilicon Kirin"
            hardware.contains("ums") || hardware.contains("sc") || board.contains("sp") -> "UNISOC"
            else -> "Generic"
        }

        val kernelFlavor = when {
            kernelVersion.contains("-gki") || kernelVersion.contains("android") -> "GKI-like"
            kernelVersion.isNotBlank() -> kernelVersion.substringBefore("#").take(48)
            else -> "Unknown"
        }

        val hints = buildList {
            add(romFamily)
            add(socFamily)
            if (kernelFlavor == "GKI-like") add("GKI")
            if (romFamily == "HyperOS" || romFamily == "MIUI") add("Xiaomi")
            if (romFamily == "One UI") add("Samsung")
        }.distinct()

        return RuntimeProfile(
            romFamily = romFamily,
            vendorFamily = vendor,
            socFamily = socFamily,
            kernelFlavor = kernelFlavor,
            hints = hints
        ).also { runtimeProfileCache = it }
    }

    suspend fun detectCpuClusters(forceRefresh: Boolean = false): List<CpuCluster> = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            cpuClusterCache?.let { return@withContext it }
        }

        val clusters = mutableListOf<CpuCluster>()
        var policyId = 0

        fun existingFile(path: String): String? = if (RootManager.nodeExists(path)) path else null

        while (true) {
            val policyPath = "/sys/devices/system/cpu/cpufreq/policy$policyId"
            if (!RootManager.nodeExists(policyPath)) break

            val related = RootManager.readNode(policyPath + "/related_cpus")?.trim().orEmpty()
            val coreIds = related.split(" ").mapNotNull { it.toIntOrNull() }.distinct().sorted()
            val cores = coreIds.map { id -> CpuCore(id, "/sys/devices/system/cpu/cpu$id/online") }

            clusters += CpuCluster(
                id = policyId,
                policyPath = policyPath,
                cores = cores,
                minFreqPath = existingFile("$policyPath/scaling_min_freq") ?: existingFile("$policyPath/cpuinfo_min_freq"),
                maxFreqPath = existingFile("$policyPath/scaling_max_freq") ?: existingFile("$policyPath/cpuinfo_max_freq"),
                govPath = existingFile("$policyPath/scaling_governor"),
                availableGovsPath = existingFile("$policyPath/scaling_available_governors"),
                availableFreqsPath = existingFile("$policyPath/scaling_available_frequencies")
            )
            policyId++
        }

        if (clusters.isEmpty()) {
            val discovered = mutableListOf<String>()
            var cpuId = 0
            while (cpuId < 12) {
                val cpuBase = "/sys/devices/system/cpu/cpu$cpuId/cpufreq"
                if (RootManager.nodeExists(cpuBase)) discovered += cpuBase
                cpuId++
            }
            discovered.distinct().forEachIndexed { idx, base ->
                clusters += CpuCluster(
                    id = idx,
                    policyPath = base,
                    cores = listOf(CpuCore(idx, "/sys/devices/system/cpu/cpu$idx/online")),
                    minFreqPath = existingFile("$base/scaling_min_freq") ?: existingFile("$base/cpuinfo_min_freq"),
                    maxFreqPath = existingFile("$base/scaling_max_freq") ?: existingFile("$base/cpuinfo_max_freq"),
                    govPath = existingFile("$base/scaling_governor"),
                    availableGovsPath = existingFile("$base/scaling_available_governors"),
                    availableFreqsPath = existingFile("$base/scaling_available_frequencies")
                )
            }
        }

        cpuClusterCache = clusters
        clusters
    }

    suspend fun readClusterGovernor(cluster: CpuCluster): String = withContext(Dispatchers.IO) {
        cluster.govPath?.let { read(it) } ?: "Unknown"
    }

    suspend fun readClusterMinFreqKHz(cluster: CpuCluster): Long = withContext(Dispatchers.IO) {
        cluster.minFreqPath?.let { read(it)?.toLongOrNull() } ?: 0L
    }

    suspend fun readClusterMaxFreqKHz(cluster: CpuCluster): Long = withContext(Dispatchers.IO) {
        cluster.maxFreqPath?.let { read(it)?.toLongOrNull() } ?: 0L
    }

    suspend fun readClusterAvailableGovernors(cluster: CpuCluster): List<String> = withContext(Dispatchers.IO) {
        val path = cluster.availableGovsPath
            ?: "/sys/devices/system/cpu/cpu${cluster.cores.firstOrNull()?.id ?: 0}/cpufreq/scaling_available_governors"
        read(path)?.split(" ")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
    }

    suspend fun readClusterAvailableFreqsKHz(cluster: CpuCluster): List<Long> = withContext(Dispatchers.IO) {
        val direct = cluster.availableFreqsPath
            ?: "/sys/devices/system/cpu/cpu${cluster.cores.firstOrNull()?.id ?: 0}/cpufreq/scaling_available_frequencies"
        val parsedDirect = read(direct)?.split(" ")?.mapNotNull { it.trim().toLongOrNull() }?.sorted()
        if (!parsedDirect.isNullOrEmpty()) return@withContext parsedDirect

        val timeInState = listOf(
            "${cluster.policyPath}/stats/time_in_state",
            "/sys/devices/system/cpu/cpu${cluster.cores.firstOrNull()?.id ?: 0}/cpufreq/stats/time_in_state"
        ).firstOrNull { RootManager.nodeExists(it) }

        val fromTimeInState = timeInState?.let { path ->
            RootManager.readNode(path)
                ?.lineSequence()
                ?.mapNotNull { it.substringBefore(' ').trim().toLongOrNull() }
                ?.toList()
                ?.sorted()
        }.orEmpty()

        if (fromTimeInState.isNotEmpty()) return@withContext fromTimeInState

        val min = readClusterMinFreqKHz(cluster)
        val max = readClusterMaxFreqKHz(cluster)
        if (min > 0 && max >= min) listOf(min, max).distinct().sorted() else emptyList()
    }

    suspend fun readCoreOnline(core: CpuCore): Boolean = withContext(Dispatchers.IO) {
        val p = core.onlinePath ?: return@withContext true
        val v = read(p) ?: "1"
        norm(v) == "1"
    }

    suspend fun setCoreOnline(core: CpuCore, enabled: Boolean): WriteResult = withContext(Dispatchers.IO) {
        val p = core.onlinePath
        if (p.isNullOrBlank()) return@withContext WriteResult(false, "core.online", null, if (enabled) "1" else "0", null, null, null, "node_missing")
        writeRaw(p, if (enabled) "1" else "0", "core.online")
    }

    suspend fun setClusterGovernor(cluster: CpuCluster, gov: String): WriteResult = withContext(Dispatchers.IO) {
        val p = cluster.govPath
        if (p.isNullOrBlank()) return@withContext WriteResult(false, "cluster.gov", null, gov, null, null, null, "node_missing")
        writeRaw(p, gov, "cluster.gov")
    }

    suspend fun setClusterFreqSafe(cluster: CpuCluster, newMinKHz: Long? = null, newMaxKHz: Long? = null): List<WriteResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<WriteResult>()
        val curMin = readClusterMinFreqKHz(cluster)
        val curMax = readClusterMaxFreqKHz(cluster)
        val targetMin = newMinKHz ?: curMin
        val targetMax = newMaxKHz ?: curMax

        if (targetMin > targetMax) {
            results += setClusterMaxFreqKHz(cluster, targetMin.toString())
            results += setClusterMinFreqKHz(cluster, targetMin.toString())
            return@withContext results
        }
        if (newMaxKHz != null && newMaxKHz < curMin) {
            results += setClusterMinFreqKHz(cluster, newMaxKHz.toString())
            results += setClusterMaxFreqKHz(cluster, newMaxKHz.toString())
            return@withContext results
        }
        if (newMinKHz != null && newMinKHz > curMax) {
            results += setClusterMaxFreqKHz(cluster, newMinKHz.toString())
            results += setClusterMinFreqKHz(cluster, newMinKHz.toString())
            return@withContext results
        }
        if (newMinKHz != null) results += setClusterMinFreqKHz(cluster, newMinKHz.toString())
        if (newMaxKHz != null) results += setClusterMaxFreqKHz(cluster, newMaxKHz.toString())
        results
    }

    private suspend fun setClusterMinFreqKHz(cluster: CpuCluster, v: String): WriteResult = withContext(Dispatchers.IO) {
        val p = cluster.minFreqPath
        if (p.isNullOrBlank()) return@withContext WriteResult(false, "cluster.minfreq", null, v, null, null, null, "node_missing")
        writeRaw(p, v, "cluster.minfreq")
    }

    private suspend fun setClusterMaxFreqKHz(cluster: CpuCluster, v: String): WriteResult = withContext(Dispatchers.IO) {
        val p = cluster.maxFreqPath
        if (p.isNullOrBlank()) return@withContext WriteResult(false, "cluster.maxfreq", null, v, null, null, null, "node_missing")
        writeRaw(p, v, "cluster.maxfreq")
    }

    private suspend fun hasWorkingNode(
        key: String,
        candidates: List<String>,
        verifyWriteNoop: Boolean = false,
        forceRefresh: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        if (forceRefresh) {
            resolved.remove(key)
            unsupported.remove(key)
        }
        resolveNode(key, candidates, verifyWriteNoop = verifyWriteNoop) != null
    }

    suspend fun getSupportSnapshot(forceRefresh: Boolean = false): SupportSnapshot = withContext(Dispatchers.IO) {
        if (forceRefresh) supportSnapshotCache = null
        supportSnapshotCache?.let { return@withContext it }

        val snapshot = SupportSnapshot(
            eas = hasWorkingNode("eas.enabled", CAND_EAS, verifyWriteNoop = false, forceRefresh = forceRefresh),
            schedBoost = hasWorkingNode("sched.boost", CAND_SCHED_BOOST, verifyWriteNoop = false, forceRefresh = forceRefresh),
            migrate = hasWorkingNode("sched.upmigrate", CAND_UPMIGRATE, verifyWriteNoop = false, forceRefresh = forceRefresh) &&
                hasWorkingNode("sched.downmigrate", CAND_DOWNMIGRATE, verifyWriteNoop = false, forceRefresh = forceRefresh),
            initTaskUtil = hasWorkingNode("sched.init_task_util", CAND_INIT_TASK_UTIL, verifyWriteNoop = false, forceRefresh = forceRefresh),
            autoGroup = hasWorkingNode("sched.autogroup", CAND_AUTOGROUP, verifyWriteNoop = false, forceRefresh = forceRefresh),
            capMarginUp = hasWorkingNode("sched.capacity_margin_up", CAND_CAP_MARGIN_UP, verifyWriteNoop = false, forceRefresh = forceRefresh),
            schedutilUpRate = hasWorkingNode("schedutil.up_rate_us", CAND_SCHEDUTIL_UPRATE, verifyWriteNoop = false, forceRefresh = forceRefresh),
            interactiveHispeed = hasWorkingNode("interactive.hispeed", CAND_INTERACTIVE_HISPEED, verifyWriteNoop = false, forceRefresh = forceRefresh),
            touchBoost = hasWorkingNode("touchboost", CAND_TOUCHBOOST, verifyWriteNoop = false, forceRefresh = forceRefresh),
            mcSaving = hasWorkingNode("power.mc", CAND_MC_SAVE, verifyWriteNoop = false, forceRefresh = forceRefresh),
            powerCollapse = hasWorkingNode("power.collapse", CAND_POWER_COLLAPSE, verifyWriteNoop = false, forceRefresh = forceRefresh),
            thermal = hasWorkingNode("thermal.enabled", CAND_THERMAL, verifyWriteNoop = false, forceRefresh = forceRefresh),
        )

        supportSnapshotCache = snapshot
        snapshot
    }

    private val CAND_EAS = listOf(
        "/proc/sys/kernel/sched_energy_aware",
        "/proc/sys/kernel/sched_energy_aware_enabled"
    )

    private val CAND_SCHED_BOOST = listOf(
        "/proc/sys/kernel/sched_boost",
        "/proc/sys/kernel/sched_boost_enabled"
    )

    private val CAND_UPMIGRATE = listOf(
        "/proc/sys/kernel/sched_upmigrate",
        "/proc/sys/kernel/sched_upmigrate_pct"
    )

    private val CAND_DOWNMIGRATE = listOf(
        "/proc/sys/kernel/sched_downmigrate",
        "/proc/sys/kernel/sched_downmigrate_pct"
    )

    private val CAND_INIT_TASK_UTIL = listOf(
        "/proc/sys/kernel/sched_initial_task_util"
    )

    private val CAND_AUTOGROUP = listOf(
        "/proc/sys/kernel/sched_autogroup_enabled"
    )

    private val CAND_CAP_MARGIN_UP = listOf(
        "/proc/sys/kernel/sched_capacity_margin_up"
    )

    private val CAND_SCHEDUTIL_UPRATE = listOf(
        "/sys/devices/system/cpu/cpufreq/schedutil/up_rate_limit_us",
        "/sys/devices/system/cpu/cpufreq/schedutil/rate_limit_us",
        "/sys/devices/system/cpu/cpufreq/policy0/schedutil/up_rate_limit_us",
        "/sys/devices/system/cpu/cpufreq/policy4/schedutil/up_rate_limit_us",
        "/sys/devices/system/cpu/cpufreq/policy6/schedutil/up_rate_limit_us"
    )

    private val CAND_INTERACTIVE_HISPEED = listOf(
        "/sys/devices/system/cpu/cpufreq/interactive/hispeed_freq",
        "/sys/devices/system/cpu/cpufreq/policy0/interactive/hispeed_freq",
        "/sys/devices/system/cpu/cpufreq/policy4/interactive/hispeed_freq",
        "/sys/devices/system/cpu/cpufreq/policy6/interactive/hispeed_freq",
        "/sys/devices/system/cpu/cpu0/cpufreq/interactive/hispeed_freq"
    )

    private val CAND_TOUCHBOOST = listOf(
        "/sys/module/msm_performance/parameters/touchboost",
        "/sys/module/performance/parameters/touchboost",
        "/sys/module/cpu_boost/parameters/input_boost_enabled",
        "/sys/module/cpu_input_boost/parameters/enabled"
    )

    private val CAND_MC_SAVE = listOf(
        "/sys/devices/system/cpu/sched_mc_power_savings",
        "/proc/sys/kernel/sched_mc_power_savings"
    )

    private val CAND_POWER_COLLAPSE = listOf(
        "/sys/module/pm_8x60/parameters/sleep_mode"
    )

    private val CAND_THERMAL = listOf(
        "/sys/module/msm_thermal/core_control/enabled",
        "/sys/module/msm_thermal/parameters/enabled",
        "/sys/module/thermal_core/parameters/enabled",
        "/sys/module/msm_thermal/parameters/core_control"
    )

    suspend fun readEasEnabled(): Boolean = withContext(Dispatchers.IO) {
        val p = resolveNode("eas.enabled", CAND_EAS, verifyWriteNoop = false) ?: return@withContext false
        read(p) == "1"
    }

    suspend fun setEasEnabled(enabled: Boolean): WriteResult = withContext(Dispatchers.IO) {
        val p = resolveNode("eas.enabled", CAND_EAS, verifyWriteNoop = true)
            ?: return@withContext WriteResult(false, "eas.enabled", null, if (enabled) "1" else "0", null, null, null, "no_working_node")
        writeRaw(p, if (enabled) "1" else "0", "eas.enabled").also { Log.w(TAG, it.short()) }
    }

    suspend fun readSchedBoost(): String = withContext(Dispatchers.IO) {
        val p = resolveNode("sched.boost", CAND_SCHED_BOOST, verifyWriteNoop = false) ?: return@withContext "0"
        read(p) ?: "0"
    }

    suspend fun setSchedBoost(level: String): WriteResult = withContext(Dispatchers.IO) {
        val p = resolveNode("sched.boost", CAND_SCHED_BOOST, verifyWriteNoop = true)
            ?: return@withContext WriteResult(false, "sched.boost", null, level, null, null, null, "no_working_node")
        writeRaw(p, level, "sched.boost").also { Log.w(TAG, it.short()) }
    }

    suspend fun readUpMigrate(): String = withContext(Dispatchers.IO) {
        val p = resolveNode("sched.upmigrate", CAND_UPMIGRATE, verifyWriteNoop = false) ?: return@withContext ""
        read(p) ?: ""
    }

    suspend fun setUpMigrate(v: String): WriteResult = withContext(Dispatchers.IO) {
        val p = resolveNode("sched.upmigrate", CAND_UPMIGRATE, verifyWriteNoop = true)
            ?: return@withContext WriteResult(false, "sched.upmigrate", null, v, null, null, null, "no_working_node")
        writeRaw(p, v, "sched.upmigrate").also { Log.w(TAG, it.short()) }
    }

    suspend fun readDownMigrate(): String = withContext(Dispatchers.IO) {
        val p = resolveNode("sched.downmigrate", CAND_DOWNMIGRATE, verifyWriteNoop = false) ?: return@withContext ""
        read(p) ?: ""
    }

    suspend fun setDownMigrate(v: String): WriteResult = withContext(Dispatchers.IO) {
        val p = resolveNode("sched.downmigrate", CAND_DOWNMIGRATE, verifyWriteNoop = true)
            ?: return@withContext WriteResult(false, "sched.downmigrate", null, v, null, null, null, "no_working_node")
        writeRaw(p, v, "sched.downmigrate").also { Log.w(TAG, it.short()) }
    }

    suspend fun readInitTaskUtil(): String = withContext(Dispatchers.IO) {
        val p = resolveNode("sched.init_task_util", CAND_INIT_TASK_UTIL, verifyWriteNoop = false) ?: return@withContext ""
        read(p) ?: ""
    }

    suspend fun setInitTaskUtil(v: String): WriteResult = withContext(Dispatchers.IO) {
        val p = resolveNode("sched.init_task_util", CAND_INIT_TASK_UTIL, verifyWriteNoop = true)
            ?: return@withContext WriteResult(false, "sched.init_task_util", null, v, null, null, null, "no_working_node")
        writeRaw(p, v, "sched.init_task_util").also { Log.w(TAG, it.short()) }
    }

    suspend fun readAutoGroup(): Boolean = withContext(Dispatchers.IO) {
        val p = resolveNode("sched.autogroup", CAND_AUTOGROUP, verifyWriteNoop = false) ?: return@withContext false
        read(p) == "1"
    }

    suspend fun setAutoGroup(enabled: Boolean): WriteResult = withContext(Dispatchers.IO) {
        val p = resolveNode("sched.autogroup", CAND_AUTOGROUP, verifyWriteNoop = true)
            ?: return@withContext WriteResult(false, "sched.autogroup", null, if (enabled) "1" else "0", null, null, null, "no_working_node")
        writeRaw(p, if (enabled) "1" else "0", "sched.autogroup").also { Log.w(TAG, it.short()) }
    }

    suspend fun readCapacityMarginUp(): String = withContext(Dispatchers.IO) {
        val p = resolveNode("sched.capacity_margin_up", CAND_CAP_MARGIN_UP, verifyWriteNoop = false) ?: return@withContext ""
        read(p) ?: ""
    }

    suspend fun setCapacityMargin(v: String): WriteResult = withContext(Dispatchers.IO) {
        val p = resolveNode("sched.capacity_margin_up", CAND_CAP_MARGIN_UP, verifyWriteNoop = true)
            ?: return@withContext WriteResult(false, "sched.capacity_margin_up", null, v, null, null, null, "no_working_node")
        writeRaw(p, v, "sched.capacity_margin_up").also { Log.w(TAG, it.short()) }
    }

    suspend fun readSchedutilUpRateUs(): String = withContext(Dispatchers.IO) {
        val p = resolveNode("schedutil.up_rate_us", CAND_SCHEDUTIL_UPRATE, verifyWriteNoop = false) ?: return@withContext ""
        read(p) ?: ""
    }

    suspend fun setSchedutilUpRateUs(v: String): WriteResult = withContext(Dispatchers.IO) {
        val p = resolveNode("schedutil.up_rate_us", CAND_SCHEDUTIL_UPRATE, verifyWriteNoop = true)
            ?: return@withContext WriteResult(false, "schedutil.up_rate_us", null, v, null, null, null, "no_working_node")
        writeRaw(p, v, "schedutil.up_rate_us").also { Log.w(TAG, it.short()) }
    }

    suspend fun readInteractiveHispeedFreqKHz(): String = withContext(Dispatchers.IO) {
        val p = resolveNode("interactive.hispeed", CAND_INTERACTIVE_HISPEED, verifyWriteNoop = false) ?: return@withContext ""
        read(p) ?: ""
    }

    suspend fun setInteractiveHispeedFreqKHz(v: String): WriteResult = withContext(Dispatchers.IO) {
        val p = resolveNode("interactive.hispeed", CAND_INTERACTIVE_HISPEED, verifyWriteNoop = true)
            ?: return@withContext WriteResult(false, "interactive.hispeed", null, v, null, null, null, "no_working_node")
        writeRaw(p, v, "interactive.hispeed").also { Log.w(TAG, it.short()) }
    }

    suspend fun readTouchBoost(): Boolean = withContext(Dispatchers.IO) {
        val p = resolveNode("touchboost", CAND_TOUCHBOOST, verifyWriteNoop = false) ?: return@withContext false
        read(p) == "1"
    }

    suspend fun setTouchBoost(enabled: Boolean): WriteResult = withContext(Dispatchers.IO) {
        val p = resolveNode("touchboost", CAND_TOUCHBOOST, verifyWriteNoop = true)
            ?: return@withContext WriteResult(false, "touchboost", null, if (enabled) "1" else "0", null, null, null, "no_working_node")
        writeRaw(p, if (enabled) "1" else "0", "touchboost").also { Log.w(TAG, it.short()) }
    }

    suspend fun readMulticorePowerSaving(): Boolean = withContext(Dispatchers.IO) {
        val p = resolveNode("power.mc", CAND_MC_SAVE, verifyWriteNoop = false) ?: return@withContext false
        read(p) == "1"
    }

    suspend fun setMulticorePowerSaving(enabled: Boolean): WriteResult = withContext(Dispatchers.IO) {
        val p = resolveNode("power.mc", CAND_MC_SAVE, verifyWriteNoop = true)
            ?: return@withContext WriteResult(false, "power.mc", null, if (enabled) "1" else "0", null, null, null, "no_working_node")
        writeRaw(p, if (enabled) "1" else "0", "power.mc").also { Log.w(TAG, it.short()) }
    }

    suspend fun readPowerCollapse(): Boolean = withContext(Dispatchers.IO) {
        val p = resolveNode("power.collapse", CAND_POWER_COLLAPSE, verifyWriteNoop = false) ?: return@withContext false
        read(p) == "1"
    }

    suspend fun setPowerCollapse(enabled: Boolean): WriteResult = withContext(Dispatchers.IO) {
        val p = resolveNode("power.collapse", CAND_POWER_COLLAPSE, verifyWriteNoop = true)
            ?: return@withContext WriteResult(false, "power.collapse", null, if (enabled) "1" else "0", null, null, null, "no_working_node")
        writeRaw(p, if (enabled) "1" else "0", "power.collapse").also { Log.w(TAG, it.short()) }
    }

    suspend fun detectThermal(): ThermalInfo = withContext(Dispatchers.IO) {
        val tempCandidates = listOf(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/devices/virtual/thermal/thermal_zone0/temp",
            "/sys/class/thermal/thermal_zone1/temp"
        )
        ThermalInfo(throttlingPath = null, tempPath = tempCandidates.firstOrNull { RootManager.nodeExists(it) })
    }

    suspend fun readThermalEnabled(): Boolean = withContext(Dispatchers.IO) {
        val p = resolveNode("thermal.enabled", CAND_THERMAL, verifyWriteNoop = false) ?: return@withContext false
        read(p) == "1"
    }

    suspend fun setThermalEnabled(enabled: Boolean): WriteResult = withContext(Dispatchers.IO) {
        val p = resolveNode("thermal.enabled", CAND_THERMAL, verifyWriteNoop = true)
            ?: return@withContext WriteResult(false, "thermal.enabled", null, if (enabled) "1" else "0", null, null, null, "no_working_node")
        writeRaw(p, if (enabled) "1" else "0", "thermal.enabled").also { Log.w(TAG, it.short()) }
    }
}
