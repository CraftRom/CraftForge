package com.craftforge.app.util

import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

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

    data class ClusterRuntimeSnapshot(
        val clusterId: Int,
        val governor: String,
        val minKHz: Long,
        val maxKHz: Long,
        val availableGovernors: List<String>,
        val availableFreqsKHz: List<Long>,
        val coreStates: List<Pair<Int, Boolean>>
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

    private val governorListCache = ConcurrentHashMap<String, List<String>>()
    private val freqListCache = ConcurrentHashMap<String, List<Long>>()
    private val clusterRuntimeCache = ConcurrentHashMap<Int, ClusterRuntimeSnapshot>()

    @Volatile private var supportSnapshotCache: SupportSnapshot? = null
    @Volatile private var cpuClusterCache: List<CpuCluster>? = null
    @Volatile private var runtimeProfileCache: RuntimeProfile? = null
    @Volatile private var lastWarmUpMs: Long = 0L

    fun isSupported(key: String): Boolean = !unsupported.contains(key)

    suspend fun warmUpBackgroundScan() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if ((now - lastWarmUpMs) < 15_000L && runtimeProfileCache != null && cpuClusterCache != null && supportSnapshotCache != null) {
            return@withContext
        }
        lastWarmUpMs = now
        coroutineScope {
            listOf(
                async { runCatching { getRuntimeProfile(forceRefresh = false) } },
                async { runCatching { detectCpuClusters(forceRefresh = false) } },
                async { runCatching { getSupportSnapshot(forceRefresh = false) } }
            ).awaitAll()
        }
    }


    private fun getProp(name: String): String = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val m = cls.getMethod("get", String::class.java, String::class.java)
        (m.invoke(null, name, "") as? String).orEmpty().trim()
    }.getOrDefault("")

    private fun read(path: String): String? = RootManager.readNode(path)
    private fun existingNodePath(vararg candidates: String): String? =
        candidates.firstOrNull { candidate -> RootManager.nodeExists(candidate) }

    private fun readFirstNonBlank(vararg candidates: String): String? =
        candidates.firstNotNullOfOrNull { candidate -> read(candidate)?.trim()?.takeIf { it.isNotBlank() } }

    private fun readLongCandidate(vararg candidates: String): Long? =
        candidates.firstNotNullOfOrNull { candidate -> read(candidate)?.trim()?.toLongOrNull() }

    private fun norm(value: String): String = value.trim().replace(Regex("\\s+"), " ")


    private fun parseCpuList(raw: String): List<Int> {
        return raw
            .trim()
            .split(Regex("[,\\s]+"))
            .flatMap { token ->
                val part = token.trim()
                if (part.isBlank()) return@flatMap emptyList()
                if ('-' in part) {
                    val bounds = part.split('-', limit = 2)
                    val start = bounds.getOrNull(0)?.toIntOrNull()
                    val end = bounds.getOrNull(1)?.toIntOrNull()
                    if (start != null && end != null) {
                        val from = minOf(start, end)
                        val to = maxOf(start, end)
                        (from..to).toList()
                    } else {
                        emptyList()
                    }
                } else {
                    part.toIntOrNull()?.let(::listOf).orEmpty()
                }
            }
            .distinct()
            .sorted()
    }

    private fun listPolicyIds(): List<Int> {
        val command = "for d in /sys/devices/system/cpu/cpufreq/policy*; do [ -d \"${'$'}d\" ] && basename \"${'$'}d\"; done"
        val raw = RootManager.execRoot(command).out
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("policy") }
            .mapNotNull { it.removePrefix("policy").toIntOrNull() }
            .distinct()
            .sorted()
            .toList()
    }

    private fun discoverCpuIds(limit: Int = max(Runtime.getRuntime().availableProcessors() * 2, 12)): List<Int> {
        return (0 until limit).filter { cpuId -> RootManager.nodeExists("/sys/devices/system/cpu/cpu$cpuId") }
    }

    private fun clusterCoreIdsForPolicy(policyPath: String, fallbackPolicyId: Int): List<Int> {
        val raw = listOf(
            "$policyPath/related_cpus",
            "$policyPath/affected_cpus"
        ).firstNotNullOfOrNull { candidate ->
            read(candidate)?.trim()?.takeIf { it.isNotBlank() }
        }

        val parsed = raw?.let(::parseCpuList).orEmpty()
        if (parsed.isNotEmpty()) return parsed

        val cpuFromPolicy = read("$policyPath/cpuinfo_cur_freq")
        if (cpuFromPolicy != null) return listOf(fallbackPolicyId)

        return listOf(fallbackPolicyId)
    }

    private fun invalidateClusterCaches(cluster: CpuCluster) {
        cluster.govPath?.let {
            RootManager.invalidateNodeCache(it)
            governorListCache.remove(it)
        }
        cluster.availableGovsPath?.let { governorListCache.remove(it) }
        cluster.availableFreqsPath?.let { freqListCache.remove(it) }
        cluster.minFreqPath?.let { RootManager.invalidateNodeCache(it) }
        cluster.maxFreqPath?.let { RootManager.invalidateNodeCache(it) }
        cluster.cores.forEach { core ->
            core.onlinePath?.let { RootManager.invalidateNodeCache(it) }
        }
        clusterRuntimeCache.remove(cluster.id)
    }

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

    private fun buildCpuPathClusters(cpuIds: List<Int>): List<CpuCluster> {
        val groupedBases = linkedMapOf<String, MutableList<Int>>()
        cpuIds.forEach { cpuId ->
            val base = "/sys/devices/system/cpu/cpu$cpuId/cpufreq"
            if (!RootManager.nodeExists(base)) return@forEach

            val signature = listOf(
                read("$base/related_cpus")?.trim(),
                read("$base/affected_cpus")?.trim(),
                RootManager.execRoot("readlink -f '$base' || echo '$base'").out.trim().ifBlank { base }
            ).firstOrNull { !it.isNullOrBlank() } ?: base

            groupedBases.getOrPut(signature) { mutableListOf() }.add(cpuId)
        }

        return groupedBases.values.mapIndexed { idx, cpuIdsForGroup ->
            val firstCpu = cpuIdsForGroup.minOrNull() ?: idx
            val base = "/sys/devices/system/cpu/cpu$firstCpu/cpufreq"
            val parsedRelated = listOf(
                read("$base/related_cpus")?.trim(),
                read("$base/affected_cpus")?.trim()
            ).firstNotNullOfOrNull { it?.takeIf { s -> s.isNotBlank() } }
                ?.let(::parseCpuList)
                .orEmpty()
                .ifEmpty { cpuIdsForGroup.distinct().sorted() }

            CpuCluster(
                id = idx,
                policyPath = base,
                cores = parsedRelated.map { cpu -> CpuCore(cpu, "/sys/devices/system/cpu/cpu$cpu/online") },
                minFreqPath = existingNodePath("$base/scaling_min_freq", "$base/cpuinfo_min_freq"),
                maxFreqPath = existingNodePath("$base/scaling_max_freq", "$base/cpuinfo_max_freq"),
                govPath = existingNodePath("$base/scaling_governor"),
                availableGovsPath = existingNodePath("$base/scaling_available_governors"),
                availableFreqsPath = existingNodePath("$base/scaling_available_frequencies")
            )
        }
    }

    private fun buildVendorTopologyClusters(cpuIds: List<Int>, profile: RuntimeProfile): List<CpuCluster> {
        if (cpuIds.isEmpty()) return emptyList()

        val grouped = linkedMapOf<String, MutableList<Int>>()
        cpuIds.forEach { cpuId ->
            val cpuBase = "/sys/devices/system/cpu/cpu$cpuId"
            val cpufreqBase = "$cpuBase/cpufreq"
            val explicitClusterId = readFirstNonBlank(
                "$cpuBase/topology/cluster_id",
                "$cpuBase/topology/physical_package_id"
            )
            val relatedCpuSignature = readFirstNonBlank(
                "$cpufreqBase/related_cpus",
                "$cpufreqBase/affected_cpus"
            )?.let(::parseCpuList)?.takeIf { it.isNotEmpty() }?.joinToString(",")
            val capacity = readLongCandidate(
                "$cpuBase/cpu_capacity",
                "$cpuBase/cpu_capacity_max",
                "$cpuBase/topology/core_capacity"
            )
            val maxFreq = readLongCandidate(
                "$cpufreqBase/cpuinfo_max_freq",
                "$cpufreqBase/scaling_max_freq"
            )
            val freqBucket = maxFreq?.let { (it / 100000L).toString() }

            val signature = when (profile.socFamily) {
                "Qualcomm" -> listOfNotNull(
                    explicitClusterId?.let { "cluster:$it" },
                    relatedCpuSignature?.let { "related:$it" },
                    capacity?.let { "cap:$it" },
                    freqBucket?.let { "freq:$it" }
                )
                "MediaTek" -> listOfNotNull(
                    explicitClusterId?.let { "cluster:$it" },
                    capacity?.let { "cap:$it" },
                    relatedCpuSignature?.let { "related:$it" },
                    freqBucket?.let { "freq:$it" }
                )
                else -> listOfNotNull(
                    explicitClusterId?.let { "cluster:$it" },
                    relatedCpuSignature?.let { "related:$it" },
                    capacity?.let { "cap:$it" },
                    freqBucket?.let { "freq:$it" }
                )
            }.joinToString("|").ifBlank { "cpu:$cpuId" }

            grouped.getOrPut(signature) { mutableListOf() }.add(cpuId)
        }

        return grouped.values.mapIndexed { idx, groupedCpuIds ->
            val orderedCpuIds = groupedCpuIds.distinct().sorted()
            val anchorCpu = orderedCpuIds.first()
            val cpuBase = "/sys/devices/system/cpu/cpu$anchorCpu"
            val cpufreqBase = "$cpuBase/cpufreq"
            CpuCluster(
                id = idx,
                policyPath = cpufreqBase,
                cores = orderedCpuIds.map { cpu -> CpuCore(cpu, "/sys/devices/system/cpu/cpu$cpu/online") },
                minFreqPath = existingNodePath("$cpufreqBase/scaling_min_freq", "$cpufreqBase/cpuinfo_min_freq"),
                maxFreqPath = existingNodePath("$cpufreqBase/scaling_max_freq", "$cpufreqBase/cpuinfo_max_freq"),
                govPath = existingNodePath("$cpufreqBase/scaling_governor"),
                availableGovsPath = existingNodePath("$cpufreqBase/scaling_available_governors"),
                availableFreqsPath = existingNodePath("$cpufreqBase/scaling_available_frequencies")
            )
        }.filter { it.cores.isNotEmpty() }
    }

    suspend fun detectCpuClusters(forceRefresh: Boolean = false): List<CpuCluster> = withContext(Dispatchers.IO) {
        if (forceRefresh) {
            cpuClusterCache = null
            clusterRuntimeCache.clear()
            governorListCache.clear()
            freqListCache.clear()
        }
        if (!forceRefresh) {
            cpuClusterCache?.let { return@withContext it }
        }

        val profile = getRuntimeProfile(forceRefresh = false)
        val clusters = mutableListOf<CpuCluster>()
        val cpuIds = discoverCpuIds(limit = max(Runtime.getRuntime().availableProcessors() * 3, 16))

        val policyIds = listPolicyIds()
        Log.i(TAG, "detectCpuClusters: policy ids=$policyIds cpuIds=$cpuIds profile=${profile.socFamily}/${profile.romFamily}")

        policyIds.forEach { policyId ->
            val policyPath = "/sys/devices/system/cpu/cpufreq/policy$policyId"
            if (!RootManager.nodeExists(policyPath)) return@forEach

            val coreIds = clusterCoreIdsForPolicy(policyPath, fallbackPolicyId = policyId)
            val cores = coreIds.map { id -> CpuCore(id, "/sys/devices/system/cpu/cpu$id/online") }

            clusters += CpuCluster(
                id = policyId,
                policyPath = policyPath,
                cores = cores,
                minFreqPath = existingNodePath("$policyPath/scaling_min_freq", "$policyPath/cpuinfo_min_freq"),
                maxFreqPath = existingNodePath("$policyPath/scaling_max_freq", "$policyPath/cpuinfo_max_freq"),
                govPath = existingNodePath("$policyPath/scaling_governor"),
                availableGovsPath = existingNodePath("$policyPath/scaling_available_governors"),
                availableFreqsPath = existingNodePath("$policyPath/scaling_available_frequencies")
            )
        }

        val cpuPathClusters = buildCpuPathClusters(cpuIds)
        val vendorTopologyClusters = buildVendorTopologyClusters(cpuIds, profile)

        val rawClusters = when {
            clusters.size > 1 -> clusters
            profile.socFamily in setOf("Qualcomm", "MediaTek") && vendorTopologyClusters.size > maxOf(clusters.size, cpuPathClusters.size) -> vendorTopologyClusters
            vendorTopologyClusters.size > clusters.size -> vendorTopologyClusters
            cpuPathClusters.size > clusters.size -> cpuPathClusters
            clusters.isNotEmpty() -> clusters
            vendorTopologyClusters.isNotEmpty() -> vendorTopologyClusters
            else -> cpuPathClusters
        }

        val deduped = rawClusters
            .map { cluster ->
                cluster.copy(cores = cluster.cores.distinctBy { it.id }.sortedBy { it.id })
            }
            .filter { it.cores.isNotEmpty() }
            .distinctBy { cluster ->
                val coreKey = cluster.cores.joinToString(",") { it.id.toString() }
                coreKey
            }
            .sortedWith(compareBy<CpuCluster> { it.cores.firstOrNull()?.id ?: it.id }.thenBy { it.id })

        cpuClusterCache = deduped
        val clusterSummary = deduped.joinToString { cluster ->
            val coreIds = cluster.cores.joinToString(prefix = "[", postfix = "]") { it.id.toString() }
            "id=${cluster.id} cores=$coreIds"
        }
        Log.i(
            TAG,
            "detectCpuClusters: policy=${clusters.size} vendor=${vendorTopologyClusters.size} cpuPath=${cpuPathClusters.size} resolved=${deduped.size} -> $clusterSummary"
        )
        deduped
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
        governorListCache[path]?.let { return@withContext it }
        val values = read(path)
            ?.split(" ")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        governorListCache[path] = values
        values
    }

    suspend fun readClusterAvailableFreqsKHz(cluster: CpuCluster): List<Long> = withContext(Dispatchers.IO) {
        val direct = cluster.availableFreqsPath
            ?: "/sys/devices/system/cpu/cpu${cluster.cores.firstOrNull()?.id ?: 0}/cpufreq/scaling_available_frequencies"
        freqListCache[direct]?.let { return@withContext it }
        val parsedDirect = read(direct)?.split(" ")?.mapNotNull { it.trim().toLongOrNull() }?.sorted()
        if (!parsedDirect.isNullOrEmpty()) {
            freqListCache[direct] = parsedDirect
            return@withContext parsedDirect
        }

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

        if (fromTimeInState.isNotEmpty()) {
            freqListCache[direct] = fromTimeInState
            return@withContext fromTimeInState
        }

        val min = readClusterMinFreqKHz(cluster)
        val max = readClusterMaxFreqKHz(cluster)
        val fallback = if (min > 0 && max >= min) listOf(min, max).distinct().sorted() else emptyList()
        freqListCache[direct] = fallback
        fallback
    }

    suspend fun readCoreOnline(core: CpuCore): Boolean = withContext(Dispatchers.IO) {
        val p = core.onlinePath ?: return@withContext true
        val v = read(p) ?: "1"
        norm(v) == "1"
    }

    suspend fun readClusterRuntimeSnapshot(cluster: CpuCluster, forceRefresh: Boolean = false): ClusterRuntimeSnapshot = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            clusterRuntimeCache[cluster.id]?.let { return@withContext it }
        }

        val snapshot = coroutineScope {
            val governorDeferred = async { readClusterGovernor(cluster) }
            val minDeferred = async { readClusterMinFreqKHz(cluster) }
            val maxDeferred = async { readClusterMaxFreqKHz(cluster) }
            val govListDeferred = async { readClusterAvailableGovernors(cluster) }
            val freqListDeferred = async { readClusterAvailableFreqsKHz(cluster) }
            val coreStatesDeferred = async { cluster.cores.map { it.id to readCoreOnline(it) } }

            ClusterRuntimeSnapshot(
                clusterId = cluster.id,
                governor = governorDeferred.await(),
                minKHz = minDeferred.await(),
                maxKHz = maxDeferred.await(),
                availableGovernors = govListDeferred.await(),
                availableFreqsKHz = freqListDeferred.await(),
                coreStates = coreStatesDeferred.await()
            )
        }

        clusterRuntimeCache[cluster.id] = snapshot
        snapshot
    }

    suspend fun setCoreOnline(core: CpuCore, enabled: Boolean): WriteResult = withContext(Dispatchers.IO) {
        val p = core.onlinePath
        if (p.isNullOrBlank()) return@withContext WriteResult(false, "core.online", null, if (enabled) "1" else "0", null, null, null, "node_missing")
        writeRaw(p, if (enabled) "1" else "0", "core.online")
    }

    suspend fun setClusterGovernor(cluster: CpuCluster, gov: String): WriteResult = withContext(Dispatchers.IO) {
        val p = cluster.govPath
        if (p.isNullOrBlank()) return@withContext WriteResult(false, "cluster.gov", null, gov, null, null, null, "node_missing")
        writeRaw(p, gov, "cluster.gov").also { if (it.ok) invalidateClusterCaches(cluster) }
    }

    suspend fun setAllClustersGovernor(clusters: List<CpuCluster>, gov: String): List<WriteResult> = coroutineScope {
        clusters.map { cluster -> async { setClusterGovernor(cluster, gov) } }.awaitAll()
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
        if (results.any { it.ok }) invalidateClusterCaches(cluster)
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

    suspend fun applyAllClustersFreqSafe(
        clusters: List<CpuCluster>,
        newMinKHz: Long? = null,
        newMaxKHz: Long? = null,
    ): List<WriteResult> = coroutineScope {
        clusters.map { cluster ->
            async {
                setClusterFreqSafe(cluster, newMinKHz = newMinKHz, newMaxKHz = newMaxKHz)
            }
        }.awaitAll().flatten()
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

        val snapshot = coroutineScope {
            val eas = async { hasWorkingNode("eas.enabled", CAND_EAS, verifyWriteNoop = false, forceRefresh = forceRefresh) }
            val schedBoost = async { hasWorkingNode("sched.boost", CAND_SCHED_BOOST, verifyWriteNoop = false, forceRefresh = forceRefresh) }
            val upMigrate = async { hasWorkingNode("sched.upmigrate", CAND_UPMIGRATE, verifyWriteNoop = false, forceRefresh = forceRefresh) }
            val downMigrate = async { hasWorkingNode("sched.downmigrate", CAND_DOWNMIGRATE, verifyWriteNoop = false, forceRefresh = forceRefresh) }
            val initTaskUtil = async { hasWorkingNode("sched.init_task_util", CAND_INIT_TASK_UTIL, verifyWriteNoop = false, forceRefresh = forceRefresh) }
            val autoGroup = async { hasWorkingNode("sched.autogroup", CAND_AUTOGROUP, verifyWriteNoop = false, forceRefresh = forceRefresh) }
            val capMarginUp = async { hasWorkingNode("sched.capacity_margin_up", CAND_CAP_MARGIN_UP, verifyWriteNoop = false, forceRefresh = forceRefresh) }
            val schedutilUpRate = async { hasWorkingNode("schedutil.up_rate_us", CAND_SCHEDUTIL_UPRATE, verifyWriteNoop = false, forceRefresh = forceRefresh) }
            val interactiveHispeed = async { hasWorkingNode("interactive.hispeed", CAND_INTERACTIVE_HISPEED, verifyWriteNoop = false, forceRefresh = forceRefresh) }
            val touchBoost = async { hasWorkingNode("touchboost", CAND_TOUCHBOOST, verifyWriteNoop = false, forceRefresh = forceRefresh) }
            val mcSaving = async { hasWorkingNode("power.mc", CAND_MC_SAVE, verifyWriteNoop = false, forceRefresh = forceRefresh) }
            val powerCollapse = async { hasWorkingNode("power.collapse", CAND_POWER_COLLAPSE, verifyWriteNoop = false, forceRefresh = forceRefresh) }
            val thermal = async { hasWorkingNode("thermal.enabled", CAND_THERMAL, verifyWriteNoop = false, forceRefresh = forceRefresh) }

            SupportSnapshot(
                eas = eas.await(),
                schedBoost = schedBoost.await(),
                migrate = upMigrate.await() && downMigrate.await(),
                initTaskUtil = initTaskUtil.await(),
                autoGroup = autoGroup.await(),
                capMarginUp = capMarginUp.await(),
                schedutilUpRate = schedutilUpRate.await(),
                interactiveHispeed = interactiveHispeed.await(),
                touchBoost = touchBoost.await(),
                mcSaving = mcSaving.await(),
                powerCollapse = powerCollapse.await(),
                thermal = thermal.await(),
            )
        }

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
        "/sys/devices/system/cpu/cpufreq/policy6/schedutil/up_rate_limit_us",
        "/sys/devices/system/cpu/cpu0/cpufreq/schedutil/up_rate_limit_us",
        "/sys/devices/system/cpu/cpu4/cpufreq/schedutil/up_rate_limit_us",
        "/sys/devices/system/cpu/cpu7/cpufreq/schedutil/up_rate_limit_us"
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
        "/sys/module/cpu_input_boost/parameters/enabled",
        "/proc/sys/kernel/pnpmgr/touch_boost_enabled"
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

    private fun SharedPreferences.getAnyString(vararg keys: String): String? {
        return keys.asSequence()
            .mapNotNull { key -> getString(key, null)?.trim()?.takeIf { it.isNotEmpty() } }
            .firstOrNull()
    }

    suspend fun applySavedCpuTweaks(prefs: SharedPreferences, includeCoreOnline: Boolean = false): List<WriteResult> = coroutineScope {
        val results = mutableListOf<WriteResult>()
        val clusters = detectCpuClusters()
        val support = getSupportSnapshot()

        clusters.forEach { cluster ->
            prefs.getAnyString("cluster_${cluster.id}_gov", "saved_governor")?.let { gov ->
                results += setClusterGovernor(cluster, gov)
            }

            val savedMin = prefs.getAnyString("cluster_${cluster.id}_min_khz", "saved_min_freq")?.toLongOrNull()
            val savedMax = prefs.getAnyString("cluster_${cluster.id}_max_khz", "saved_max_freq")?.toLongOrNull()
            if (savedMin != null || savedMax != null) {
                results += setClusterFreqSafe(cluster, newMinKHz = savedMin, newMaxKHz = savedMax)
            }

            if (includeCoreOnline) {
                cluster.cores.forEach { core ->
                    prefs.getAnyString("core_${core.id}_online")?.let { raw ->
                        results += setCoreOnline(core, raw == "1")
                    }
                }
            }
        }

        if (support.eas) {
            prefs.getAnyString("saved_eas_enable")?.let { results += setEasEnabled(it == "1") }
        }
        if (support.schedBoost) {
            prefs.getAnyString("saved_sched_boost")?.let { results += setSchedBoost(it) }
        }
        if (support.migrate) {
            prefs.getAnyString("saved_up_migrate", "saved_sched_upmigrate")?.let { results += setUpMigrate(it) }
            prefs.getAnyString("saved_down_migrate", "saved_sched_downmigrate")?.let { results += setDownMigrate(it) }
        }
        if (support.initTaskUtil) {
            prefs.getAnyString("saved_init_task_util_raw", "saved_init_task_util")?.let { results += setInitTaskUtil(it) }
        }
        if (support.autoGroup) {
            prefs.getAnyString("saved_autogroup")?.let { results += setAutoGroup(it == "1") }
        }
        if (support.capMarginUp) {
            prefs.getAnyString("saved_capacity_margin")?.let { results += setCapacityMargin(it) }
        }
        if (support.schedutilUpRate) {
            prefs.getAnyString("saved_schedutil_up_rate", "saved_sched_uprate")?.let { results += setSchedutilUpRateUs(it) }
        }
        if (support.interactiveHispeed) {
            prefs.getAnyString("saved_interactive_hispeed_khz", "saved_interactive_hispeed")?.let { results += setInteractiveHispeedFreqKHz(it) }
        }
        if (support.touchBoost) {
            prefs.getAnyString("saved_touchboost")?.let { results += setTouchBoost(it == "1") }
        }
        if (support.mcSaving) {
            prefs.getAnyString("saved_mc", "saved_mc_power")?.let { results += setMulticorePowerSaving(it == "1") }
        }
        if (support.powerCollapse) {
            prefs.getAnyString("saved_pc", "saved_power_collapse")?.let { results += setPowerCollapse(it == "1") }
        }
        if (support.thermal) {
            prefs.getAnyString("saved_thermal")?.let { results += setThermalEnabled(it == "1") }
        }

        results
    }
}
