package com.craftforge.app.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ZramApplyStage {
    PREPARING,
    SWAPOFF,
    RESETTING,
    SETTING_ALGO,
    RESTORING_SIZE,
    REENABLING_SWAP,
    VERIFYING,
    DONE
}

object ZramManager {
    private const val ZRAM_COMP_PATH = "/sys/block/zram0/comp_algorithm"
    private const val ZRAM_RESET_PATH = "/sys/block/zram0/reset"
    private const val ZRAM_DISKSIZE_PATH = "/sys/block/zram0/disksize"

    private val deviceCandidates = listOf("/dev/block/zram0", "/dev/zram0")

    suspend fun applyCompressionAlgorithm(
        algorithm: String,
        onProgress: suspend (ZramApplyStage, Int) -> Unit = { _, _ -> }
    ): RootManager.CmdResult = withContext(Dispatchers.IO) {
        val cleanAlgo = algorithm.trim()
        if (cleanAlgo.isEmpty()) {
            return@withContext RootManager.CmdResult(-1, "", "empty_algorithm", used = ZRAM_COMP_PATH)
        }
        if (!RootManager.nodeWritable(ZRAM_COMP_PATH)) {
            return@withContext RootManager.CmdResult(-1, "", "comp_algorithm_unavailable", used = ZRAM_COMP_PATH)
        }

        suspend fun report(stage: ZramApplyStage, progress: Int) {
            onProgress(stage, progress.coerceIn(0, 100))
        }

        val device = deviceCandidates.firstOrNull { RootManager.nodeExists(it) } ?: "/dev/zram0"
        val disksizeBefore = RootManager.readNode(ZRAM_DISKSIZE_PATH)?.trim()?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val swaps = RootManager.execRoot("cat /proc/swaps").out
        val wasSwapActive = swaps.lineSequence().drop(1).any { it.contains("zram0") || it.contains(device) }
        val swapPriority = swaps.lineSequence()
            .drop(1)
            .firstOrNull { it.contains("zram0") || it.contains(device) }
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.lastOrNull()
            ?.toIntOrNull()
            ?: 32758

        val currentRaw = RootManager.readNode(ZRAM_COMP_PATH, forceRefresh = true).orEmpty()
        val currentAlgo = parseCurrentAlgorithm(currentRaw)
        if (currentAlgo.equals(cleanAlgo, ignoreCase = true)) {
            report(ZramApplyStage.DONE, 100)
            return@withContext RootManager.CmdResult(0, currentAlgo, "noop_same_value", used = ZRAM_COMP_PATH)
        }

        report(ZramApplyStage.PREPARING, 10)

        if (wasSwapActive) {
            report(ZramApplyStage.SWAPOFF, 25)
            val swapoff = RootManager.execRoot("swapoff ${RootManager.shellQuote(device)}", timeoutSec = 20)
            if (!swapoff.ok) return@withContext swapoff
        }

        report(ZramApplyStage.RESETTING, 45)
        val resetResult = if (RootManager.nodeWritable(ZRAM_RESET_PATH)) {
            RootManager.writeNode(ZRAM_RESET_PATH, "1", verify = false, timeoutSec = 12)
        } else {
            RootManager.writeNode(ZRAM_DISKSIZE_PATH, "0", verify = false, timeoutSec = 12)
        }
        if (!resetResult.ok) return@withContext resetResult

        RootManager.invalidateNodeCache(ZRAM_COMP_PATH)
        RootManager.invalidateNodeCache(ZRAM_DISKSIZE_PATH)

        report(ZramApplyStage.SETTING_ALGO, 60)
        val setAlgo = RootManager.execRoot(
            "printf %s ${RootManager.shellQuote(cleanAlgo)} > ${RootManager.shellQuote(ZRAM_COMP_PATH)}",
            timeoutSec = 12
        )
        val algoAfterSet = parseCurrentAlgorithm(RootManager.readNode(ZRAM_COMP_PATH, forceRefresh = true).orEmpty())
        if (!setAlgo.ok || !algoAfterSet.equals(cleanAlgo, ignoreCase = true)) {
            return@withContext RootManager.CmdResult(
                code = if (setAlgo.ok) -1 else setAlgo.code,
                out = algoAfterSet,
                err = setAlgo.err.ifBlank { "algorithm_readback_mismatch" },
                used = ZRAM_COMP_PATH
            )
        }

        if (disksizeBefore > 0L) {
            report(ZramApplyStage.RESTORING_SIZE, 78)
            val restoreSize = RootManager.writeNode(ZRAM_DISKSIZE_PATH, disksizeBefore.toString(), verify = true, timeoutSec = 15)
            if (!restoreSize.ok) return@withContext restoreSize

            val mkSwap = RootManager.execRoot("mkswap ${RootManager.shellQuote(device)} >/dev/null 2>&1", timeoutSec = 20)
            if (!mkSwap.ok) return@withContext mkSwap
        }

        if (wasSwapActive && disksizeBefore > 0L) {
            report(ZramApplyStage.REENABLING_SWAP, 90)
            val swapon = RootManager.execRoot("swapon -p $swapPriority ${RootManager.shellQuote(device)}", timeoutSec = 20)
            if (!swapon.ok) return@withContext swapon
        }

        report(ZramApplyStage.VERIFYING, 98)
        val finalAlgo = parseCurrentAlgorithm(RootManager.readNode(ZRAM_COMP_PATH, forceRefresh = true).orEmpty())
        RootManager.clearNodeCaches()

        if (!finalAlgo.equals(cleanAlgo, ignoreCase = true)) {
            return@withContext RootManager.CmdResult(-1, finalAlgo, "final_algorithm_mismatch", used = ZRAM_COMP_PATH)
        }

        report(ZramApplyStage.DONE, 100)
        RootManager.CmdResult(0, finalAlgo, "", used = ZRAM_COMP_PATH)
    }

    fun parseCurrentAlgorithm(raw: String): String {
        return raw.substringAfter("[", raw)
            .substringBefore("]")
            .trim()
            .ifBlank { raw.trim().split(Regex("\\s+")).firstOrNull().orEmpty() }
    }
}
