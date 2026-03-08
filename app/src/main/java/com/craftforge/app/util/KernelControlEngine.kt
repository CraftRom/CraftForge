package com.craftforge.app.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object KernelControlEngine {

    private const val TAG = "KernelControlEngine"

    // ----------------------------
    // Models
    // ----------------------------
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

    // ----------------------------
    // Resolver cache (запам’ятовуємо коректні ноди)
    // ----------------------------
    private val resolved = mutableMapOf<String, String>()         // key -> path
    private val unsupported = mutableSetOf<String>()              // key -> unsupported
    @Volatile
    private var supportSnapshotCache: SupportSnapshot? = null

    fun isSupported(key: String): Boolean = !unsupported.contains(key)

    private fun norm(v: String): String = v.trim().split(Regex("\\s+")).firstOrNull().orEmpty()

    private fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private suspend fun existsFile(path: String): Boolean = withContext(Dispatchers.IO) {
        val r = RootManager.execRoot("[ -f ${shQuote(path)} ] && echo 1 || echo 0")
        r.ok && r.out.trim() == "1"
    }

    private suspend fun read(path: String): String? = withContext(Dispatchers.IO) {
        if (!existsFile(path)) return@withContext null
        val r = RootManager.execRoot("cat ${shQuote(path)}")
        if (!r.ok) null else r.out.trim()
    }

    /**
     * Пише кількома способами і робить readback.
     * Повертає діагностику (before/after/method/reason).
     */
    private suspend fun writeRaw(path: String, value: String, key: String): WriteResult = withContext(Dispatchers.IO) {
        if (!existsFile(path)) {
            return@withContext WriteResult(false, key, path, value, null, null, null, "node_missing")
        }

        val before = read(path)
        val v = norm(value)

        suspend fun verify(method: String): WriteResult {
            val after = read(path)
            val ok = after != null && norm(after) == v
            return if (ok) {
                WriteResult(true, key, path, v, before, after, method, null)
            } else {
                WriteResult(false, key, path, v, before, after, method, "readback_mismatch_or_rejected")
            }
        }

        // Методи запису (актуальні для su в Magisk/KernelSU/APatch: все одно йде через su -c sh -c)
        // 1) echo >
        run {
            RootManager.execRoot("sh -c ${shQuote("echo $v > ${shQuote(path)}")}")
            val r = verify("echo_redirect")
            if (r.ok) return@withContext r
        }
        // 2) printf >
        run {
            RootManager.execRoot("sh -c ${shQuote("printf %s $v > ${shQuote(path)}")}")
            val r = verify("printf_redirect")
            if (r.ok) return@withContext r
        }
        // 3) echo | tee
        run {
            RootManager.execRoot("sh -c ${shQuote("echo $v | tee ${shQuote(path)} >/dev/null")}")
            val r = verify("echo_tee")
            if (r.ok) return@withContext r
        }
        // 4) printf | tee
        run {
            RootManager.execRoot("sh -c ${shQuote("printf %s $v | tee ${shQuote(path)} >/dev/null")}")
            val r = verify("printf_tee")
            if (r.ok) return@withContext r
        }

        return@withContext WriteResult(
            ok = false,
            key = key,
            path = path,
            value = v,
            before = before,
            after = read(path),
            method = "all_failed",
            reason = "rejected_by_kernel_or_requires_constraints"
        )
    }

    /**
     * Resolver:
     * - бере candidates
     * - шукає існуючі
     * - якщо треба write-перевірка — робить safe-test: пише ТО САМЕ значення (no-op), перевіряє readback
     * - запам’ятовує робочий path
     */
    private suspend fun resolveNode(
        key: String,
        candidates: List<String>,
        verifyWriteNoop: Boolean = true
    ): String? = withContext(Dispatchers.IO) {
        resolved[key]?.let { return@withContext it }
        if (unsupported.contains(key)) return@withContext null

        val existing = candidates.filter { existsFile(it) }
        if (existing.isEmpty()) {
            unsupported.add(key)
            Log.w(TAG, "resolve: no existing node for key=$key candidates=${candidates.size}")
            return@withContext null
        }

        if (!verifyWriteNoop) {
            val pick = existing.first()
            resolved[key] = pick
            Log.i(TAG, "resolve: key=$key -> $pick (no write verify)")
            return@withContext pick
        }

        // write-noop verify: читаємо поточне, пробуємо записати це ж саме (має спрацювати якщо node реально доступний)
        for (p in existing) {
            val cur = read(p)
            if (cur.isNullOrBlank()) continue

            val test = writeRaw(p, norm(cur), key)
            if (test.ok) {
                resolved[key] = p
                Log.i(TAG, "resolve: key=$key -> $p (verified)")
                return@withContext p
            } else {
                Log.w(TAG, "resolve: key=$key candidate=$p -> ${test.short()}")
            }
        }

        unsupported.add(key)
        Log.w(TAG, "resolve: no working node for key=$key candidates=${existing.size}")
        null
    }

    // ----------------------------
    // CPU: detect clusters by policy*
    // ----------------------------
    suspend fun detectCpuClusters(): List<CpuCluster> = withContext(Dispatchers.IO) {
        val clusters = mutableListOf<CpuCluster>()
        var policyId = 0

        while (true) {
            val policyPath = "/sys/devices/system/cpu/cpufreq/policy$policyId"
            val dirOk = RootManager.readNodeViaRoot("[ -d ${shQuote(policyPath)} ] && echo 1 || echo 0") == "1"
            if (!dirOk) break

            val related = RootManager.readNodeViaRoot("cat ${shQuote("$policyPath/related_cpus")}")?.trim().orEmpty()
            val coreIds = related.split(" ").mapNotNull { it.toIntOrNull() }.distinct().sorted()

            val cores = coreIds.map { id -> CpuCore(id, "/sys/devices/system/cpu/cpu$id/online") }

            fun f(p: String) = if (RootManager.readNodeViaRoot("[ -f ${shQuote(p)} ] && echo 1 || echo 0") == "1") p else null

            val minFreqPath = f("$policyPath/scaling_min_freq")
            val maxFreqPath = f("$policyPath/scaling_max_freq")
            val govPath = f("$policyPath/scaling_governor")
            val availableGovsPath = f("$policyPath/scaling_available_governors")
            val availableFreqsPath = f("$policyPath/scaling_available_frequencies")

            clusters += CpuCluster(
                id = policyId,
                policyPath = policyPath,
                cores = cores,
                minFreqPath = minFreqPath,
                maxFreqPath = maxFreqPath,
                govPath = govPath,
                availableGovsPath = availableGovsPath,
                availableFreqsPath = availableFreqsPath
            )

            policyId++
        }
        clusters
    }

    suspend fun readClusterGovernor(cluster: CpuCluster): String =
        withContext(Dispatchers.IO) { cluster.govPath?.let { read(it) } ?: "Unknown" }

    suspend fun readClusterMinFreqKHz(cluster: CpuCluster): Long =
        withContext(Dispatchers.IO) { cluster.minFreqPath?.let { read(it)?.toLongOrNull() } ?: 0L }

    suspend fun readClusterMaxFreqKHz(cluster: CpuCluster): Long =
        withContext(Dispatchers.IO) { cluster.maxFreqPath?.let { read(it)?.toLongOrNull() } ?: 0L }

    suspend fun readClusterAvailableGovernors(cluster: CpuCluster): List<String> =
        withContext(Dispatchers.IO) {
            val path = cluster.availableGovsPath
                ?: "/sys/devices/system/cpu/cpu${cluster.cores.firstOrNull()?.id ?: 0}/cpufreq/scaling_available_governors"
            read(path)?.split(" ")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        }

    suspend fun readClusterAvailableFreqsKHz(cluster: CpuCluster): List<Long> =
        withContext(Dispatchers.IO) {
            val path = cluster.availableFreqsPath
                ?: "/sys/devices/system/cpu/cpu${cluster.cores.firstOrNull()?.id ?: 0}/cpufreq/scaling_available_frequencies"
            read(path)?.split(" ")?.mapNotNull { it.trim().toLongOrNull() }?.sorted() ?: emptyList()
        }

    // ----------------------------
    // Per-core online
    // ----------------------------
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

    // ----------------------------
    // Per-cluster setters (freq safe)
    // ----------------------------
    suspend fun setClusterGovernor(cluster: CpuCluster, gov: String): WriteResult =
        withContext(Dispatchers.IO) {
            val p = cluster.govPath
            if (p.isNullOrBlank()) return@withContext WriteResult(false, "cluster.gov", null, gov, null, null, null, "node_missing")
            writeRaw(p, gov, "cluster.gov")
        }

    suspend fun setClusterFreqSafe(cluster: CpuCluster, newMinKHz: Long? = null, newMaxKHz: Long? = null): List<WriteResult> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<WriteResult>()
            val curMin = readClusterMinFreqKHz(cluster)
            val curMax = readClusterMaxFreqKHz(cluster)

            val targetMin = newMinKHz ?: curMin
            val targetMax = newMaxKHz ?: curMax

            // Порядок запису щоб kernel не відхилив
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
        if (forceRefresh) {
            supportSnapshotCache = null
        }

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

    // ----------------------------
    // Scheduler/EAS nodes with resolver
    // ----------------------------

    // У різних ядер можуть бути різні назви/місця.
    // Ти можеш додавати сюди свої candidates без зміни UI.
    private val CAND_EAS = listOf(
        "/proc/sys/kernel/sched_energy_aware",
        "/proc/sys/kernel/sched_energy_aware_enabled"
    )

    private val CAND_SCHED_BOOST = listOf(
        "/proc/sys/kernel/sched_boost",
        "/proc/sys/kernel/sched_boost_enabled"
    )

    private val CAND_UPMIGRATE = listOf(
        "/proc/sys/kernel/sched_upmigrate"
    )

    private val CAND_DOWNMIGRATE = listOf(
        "/proc/sys/kernel/sched_downmigrate"
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
        "/sys/devices/system/cpu/cpufreq/schedutil/rate_limit_us"
    )

    private val CAND_INTERACTIVE_HISPEED = listOf(
        "/sys/devices/system/cpu/cpufreq/interactive/hispeed_freq"
    )

    private val CAND_TOUCHBOOST = listOf(
        "/sys/module/msm_performance/parameters/touchboost",
        "/sys/module/performance/parameters/touchboost"
    )

    private val CAND_MC_SAVE = listOf(
        "/sys/devices/system/cpu/sched_mc_power_savings"
    )

    private val CAND_POWER_COLLAPSE = listOf(
        "/sys/module/pm_8x60/parameters/sleep_mode"
    )

    // Thermal candidates вже були — зробимо теж resolver-логіку
    private val CAND_THERMAL = listOf(
        "/sys/module/msm_thermal/core_control/enabled",
        "/sys/module/msm_thermal/parameters/enabled",
        "/sys/module/thermal_core/parameters/enabled",
        "/sys/module/cpu_boost/parameters/input_boost_enabled",
        "/sys/module/msm_thermal/parameters/core_control"
    )

    suspend fun readEasEnabled(): Boolean = withContext(Dispatchers.IO) {
        val p = resolveNode("eas.enabled", CAND_EAS, verifyWriteNoop = false) ?: return@withContext false
        (read(p) == "1")
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

    // Thermal: детект + resolver
    suspend fun detectThermal(): ThermalInfo = withContext(Dispatchers.IO) {
        val temp = if (existsFile("/sys/class/thermal/thermal_zone0/temp")) "/sys/class/thermal/thermal_zone0/temp" else null
        // Ми не фіксуємо throttlingPath тут жорстко — нехай resolver підбирає.
        ThermalInfo(throttlingPath = null, tempPath = temp)
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