package com.craftforge.app.util

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object RootManager {

    private const val TAG = "RootManager"

    data class CmdResult(
        val code: Int,
        val out: String,
        val err: String,
        val used: String? = null
    ) {
        val ok: Boolean get() = code == 0
    }

    @Volatile
    private var cachedSu: String? = null

    private val existsCache = ConcurrentHashMap<String, Boolean>()
    private val writableCache = ConcurrentHashMap<String, Boolean>()

    private val suCandidates = listOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/data/adb/magisk/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
        "/data/adb/apatch/bin/su"
    )

    private fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun runProcess(cmd: List<String>, timeoutSec: Long = 12): CmdResult {
        return try {
            val pb = ProcessBuilder(cmd)
            val p = pb.start()

            val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                p.destroy()
                return CmdResult(-1, "", "timeout", used = cmd.firstOrNull())
            }

            val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
            val err = BufferedReader(InputStreamReader(p.errorStream)).readText()
            CmdResult(p.exitValue(), out.trim(), err.trim(), used = cmd.firstOrNull())
        } catch (t: Throwable) {
            CmdResult(-1, "", t.message ?: "exception", used = cmd.firstOrNull())
        }
    }

    private fun testSu(suPath: String): Boolean {
        val r = runProcess(listOf(suPath, "-c", "id -u"))
        val ok = r.ok && r.out.trim() == "0"
        Log.d(TAG, "testSu: $suPath -> ok=$ok code=${r.code} out='${r.out}' err='${r.err}'")
        return ok
    }

    @Synchronized
    fun resolveSu(): String? {
        cachedSu?.let { return it }

        for (candidate in suCandidates) {
            if (testSu(candidate)) {
                cachedSu = candidate
                Log.i(TAG, "resolveSu: using $candidate")
                return candidate
            }
        }

        Log.w(TAG, "resolveSu: no working su found")
        return null
    }

    fun execRoot(cmd: String, timeoutSec: Long = 12): CmdResult {
        val su = resolveSu()
            ?: return CmdResult(-1, "", "no_su", used = null)

        val r = runProcess(listOf(su, "-c", cmd), timeoutSec)
        if (!r.ok) {
            Log.w(TAG, "execRoot FAIL code=${r.code} used=${r.used} cmd=$cmd err=${r.err}")
        } else {
            Log.d(TAG, "execRoot OK used=${r.used} cmd=$cmd out=${r.out}")
        }
        return r
    }

    fun clearNodeCaches() {
        existsCache.clear()
        writableCache.clear()
    }

    fun invalidateNodeCache(path: String?) {
        if (path.isNullOrBlank()) return
        existsCache.remove(path)
        writableCache.remove(path)
    }

    fun nodeExists(path: String, forceRefresh: Boolean = false): Boolean {
        if (!forceRefresh) {
            existsCache[path]?.let { return it }
        }
        val ok = execRoot("[ -e ${shQuote(path)} ] && echo 1 || echo 0").out == "1"
        existsCache[path] = ok
        return ok
    }

    fun nodeWritable(path: String, forceRefresh: Boolean = false): Boolean {
        if (!forceRefresh) {
            writableCache[path]?.let { return it }
        }
        val ok = execRoot("[ -w ${shQuote(path)} ] && echo 1 || echo 0").out == "1"
        existsCache[path] = existsCache[path] ?: ok
        writableCache[path] = ok
        return ok
    }

    fun readNode(path: String, forceRefresh: Boolean = false): String? {
        if (!nodeExists(path, forceRefresh)) return null
        val r = execRoot("cat ${shQuote(path)}")
        return if (r.ok) r.out.trim().ifEmpty { null } else null
    }

    fun writeNode(path: String, value: String, verify: Boolean = true, timeoutSec: Long = 12): CmdResult {
        if (!nodeExists(path)) {
            return CmdResult(-1, "", "node_missing", used = path)
        }
        val trimmed = value.trim()
        val before = readNode(path)
        if (before?.trim() == trimmed) {
            return CmdResult(0, trimmed, "noop_same_value", used = path)
        }

        val primary = execRoot("printf %s ${shQuote(trimmed)} > ${shQuote(path)}", timeoutSec)
        if (!verify) {
            invalidateNodeCache(path)
            return primary
        }

        val afterPrimary = readNode(path, forceRefresh = true)?.trim()
        if (primary.ok && afterPrimary == trimmed) {
            return CmdResult(0, afterPrimary ?: trimmed, primary.err, used = path)
        }

        val fallback = execRoot("printf %s ${shQuote(trimmed)} | tee ${shQuote(path)} >/dev/null", timeoutSec)
        val afterFallback = readNode(path, forceRefresh = true)?.trim()
        return when {
            fallback.ok && afterFallback == trimmed -> CmdResult(0, afterFallback ?: trimmed, fallback.err, used = path)
            else -> CmdResult(
                code = if (fallback.code != 0) fallback.code else primary.code,
                out = afterFallback ?: afterPrimary ?: "",
                err = listOf(primary.err, fallback.err).filter { it.isNotBlank() }.joinToString(" | ").ifBlank { "readback_mismatch" },
                used = path
            )
        }
    }

    fun executeRootCommand(cmd: String): CmdResult = execRoot(cmd)

    fun readNodeViaRoot(cmdOrPath: String): String? {
        val trimmed = cmdOrPath.trim()
        val cmd = if (trimmed.startsWith("cat ") || trimmed.contains(" ")) {
            trimmed
        } else {
            "cat ${shQuote(trimmed)}"
        }
        val r = execRoot(cmd)
        return if (r.ok) r.out else null
    }
}
