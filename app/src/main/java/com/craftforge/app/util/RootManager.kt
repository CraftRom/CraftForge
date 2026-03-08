package com.craftforge.app.util

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
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

    // Кандидати: PATH + найпопулярніші місця.
    // (KernelSU/APatch можуть не мати /system/bin/su — тому тут і PATH, і абсолютні шляхи)
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

    /** Нормальне quoting для sh -c */
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

    /** Перевіряє, чи даний su реально працює для root-команд */
    private fun testSu(suPath: String): Boolean {
        // "id -u" має повернути 0
        val r = runProcess(listOf(suPath, "-c", "id -u"))
        val ok = r.ok && r.out.trim() == "0"
        Log.d(TAG, "testSu: $suPath -> ok=$ok code=${r.code} out='${r.out}' err='${r.err}'")
        return ok
    }

    /** Підбирає робочий su (PATH або абсолютний), кешує */
    fun resolveSu(): String? {
        cachedSu?.let { return it }

        for (c in suCandidates) {
            if (testSu(c)) {
                cachedSu = c
                Log.i(TAG, "resolveSu: using $c")
                return c
            }
        }

        Log.w(TAG, "resolveSu: no working su found")
        return null
    }

    /** Виконує root команду, повертає повний результат */
    fun execRoot(cmd: String, timeoutSec: Long = 12): CmdResult {
        val su = resolveSu()
        if (su == null) {
            return CmdResult(-1, "", "no_su", used = null)
        }

        // Через sh -c, глушимо stderr в самому cmd де треба — але тут НЕ глушимо, бо нам треба діагностика
        val full = listOf(su, "-c", "sh -c ${shQuote(cmd)}")
        val r = runProcess(full, timeoutSec)

        if (!r.ok) {
            Log.w(TAG, "execRoot FAIL code=${r.code} used=${r.used} cmd=$cmd err=${r.err}")
        } else {
            Log.d(TAG, "execRoot OK used=${r.used} cmd=$cmd out=${r.out}")
        }
        return r
    }

    /** Старі сумісні методи (щоб твій код не розвалився) */
    fun executeRootCommand(cmd: String) {
        execRoot(cmd)
    }

    fun readNodeViaRoot(cmdOrPath: String): String? {
        // Дозволимо передавати або "cat /path", або просто "/path"
        val cmd = if (cmdOrPath.trim().startsWith("cat ") || cmdOrPath.contains(" ")) {
            cmdOrPath
        } else {
            "cat ${shQuote(cmdOrPath)}"
        }
        val r = execRoot(cmd)
        return if (r.ok) r.out else null
    }
}