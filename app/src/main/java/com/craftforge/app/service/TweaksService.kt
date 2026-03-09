package com.craftforge.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.craftforge.app.MainActivity
import com.craftforge.app.R
import com.craftforge.app.util.KernelControlEngine
import com.craftforge.app.util.RootManager
import com.craftforge.app.util.ZramApplyStage
import com.craftforge.app.util.ZramManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class TweaksService : Service() {

    private fun shellWrite(path: String, value: String): String =
        "[ -w ${RootManager.shellQuote(path)} ] && printf %s ${RootManager.shellQuote(value)} > ${RootManager.shellQuote(path)}"

    private fun addWrites(commands: MutableSet<String>, value: String?, vararg paths: String) {
        val clean = value?.trim()?.takeIf { it.isNotEmpty() } ?: return
        paths.forEach { path -> commands += shellWrite(path, clean) }
    }

    private fun addLoopWrites(commands: MutableSet<String>, value: String?, glob: String, leaf: String) {
        val clean = value?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val quoted = RootManager.shellQuote(clean)
        commands += buildString {
            append("for base in ")
            append(glob)
            append("; do [ -d \"\$base\" ] || continue; ")
            append("p=\"\$base/")
            append(leaf)
            append("\"; [ -w \"\$p\" ] && printf %s ")
            append(quoted)
            append(" > \"\$p\"; done")
        }
    }


    companion object {
        private const val CHANNEL_ID = "craftforge_core_service_v3"
        private const val NOTIFICATION_ID = 1001
        private const val EXECUTION_TIMEOUT = 30_000L
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationBuilder: NotificationCompat.Builder

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_init))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        try {
            startForeground(NOTIFICATION_ID, notificationBuilder.build())
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) e.printStackTrace()
        }

        serviceScope.launch {
            applySystemTweaksWithProgress()
        }

        return START_STICKY
    }

    private suspend fun applySystemTweaksWithProgress() {
        val prefs = getSharedPreferences("TweaksPrefs", MODE_PRIVATE)
        updateNotificationProgress(getString(R.string.service_notification_cpu_scan), 1, 4)

        val cpuResults = withTimeoutOrNull(EXECUTION_TIMEOUT) {
            KernelControlEngine.applySavedCpuTweaks(prefs)
        }.orEmpty()

        val savedZramComp = prefs.getString("saved_zram_comp", null)?.trim().orEmpty()
        if (savedZramComp.isNotEmpty()) {
            updateNotificationProgress(getString(R.string.service_notification_zram), 2, 4)
            withTimeoutOrNull(EXECUTION_TIMEOUT) {
                ZramManager.applyCompressionAlgorithm(savedZramComp) { stage, progress ->
                    val text = when (stage) {
                        ZramApplyStage.PREPARING -> getString(R.string.zram_progress_preparing)
                        ZramApplyStage.SWAPOFF -> getString(R.string.zram_progress_swapoff)
                        ZramApplyStage.RESETTING -> getString(R.string.zram_progress_reset)
                        ZramApplyStage.SETTING_ALGO -> getString(R.string.zram_progress_set_algo)
                        ZramApplyStage.RESTORING_SIZE -> getString(R.string.zram_progress_restore_size)
                        ZramApplyStage.REENABLING_SWAP -> getString(R.string.zram_progress_reenable_swap)
                        ZramApplyStage.VERIFYING -> getString(R.string.zram_progress_verify)
                        ZramApplyStage.DONE -> getString(R.string.zram_progress_done)
                    }
                    notificationBuilder.setContentText(text).setProgress(100, progress, false)
                    notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
                }
            }
        }

        updateNotificationProgress(getString(R.string.service_notification_other), 3, 4)
        val commands = buildNonCpuCommands(prefs)

        if (commands.isNotEmpty()) {
            withTimeoutOrNull(EXECUTION_TIMEOUT) {
                RootManager.execRootScript(commands, timeoutSec = 20)
            }
        }

        val failedCpuWrites = cpuResults.count { !it.ok }
        val appliedCpuWrites = cpuResults.count { it.ok }

        updateNotificationProgress(getString(R.string.service_notification_finalize), 4, 4)
        updateNotificationFinal(
            if (failedCpuWrites == 0) {
                getString(R.string.service_notification_active_ok, appliedCpuWrites)
            } else {
                getString(R.string.service_notification_active_mixed, appliedCpuWrites, failedCpuWrites)
            }
        )
    }

    private fun buildNonCpuCommands(prefs: android.content.SharedPreferences): List<String> {
        val commands = linkedSetOf<String>()

        // GPU - Qualcomm KGSL + generic devfreq GPU fallbacks
        addWrites(commands, prefs.getString("saved_gpu_governor", null),
            "/sys/class/kgsl/kgsl-3d0/devfreq/governor"
        )
        addLoopWrites(commands, prefs.getString("saved_gpu_governor", null), "/sys/class/devfreq/*gpu*", "governor")
        addLoopWrites(commands, prefs.getString("saved_gpu_governor", null), "/sys/class/devfreq/*mali*", "governor")

        addWrites(commands, prefs.getString("saved_gpu_max_freq", null),
            "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq"
        )
        addLoopWrites(commands, prefs.getString("saved_gpu_max_freq", null), "/sys/class/devfreq/*gpu*", "max_freq")
        addLoopWrites(commands, prefs.getString("saved_gpu_min_freq", null), "/sys/class/devfreq/*gpu*", "min_freq")
        addWrites(commands, prefs.getString("saved_gpu_min_freq", null),
            "/sys/class/kgsl/kgsl-3d0/devfreq/min_freq"
        )
        addWrites(commands, prefs.getString("saved_adreno_idler", null),
            "/sys/module/adreno_idler/parameters/adreno_idler_active"
        )
        addWrites(commands, prefs.getString("saved_gpu_idle_timer", null),
            "/sys/class/kgsl/kgsl-3d0/idle_timer"
        )
        addWrites(commands, prefs.getString("saved_adrenoboost", null),
            "/sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost"
        )

        // I/O - generic loop across block devices
        addLoopWrites(commands, prefs.getString("saved_scheduler", null), "/sys/block/*/queue", "scheduler")
        addLoopWrites(commands, prefs.getString("saved_readahead", null), "/sys/block/*/queue", "read_ahead_kb")
        addLoopWrites(commands, prefs.getString("saved_nr_requests", null), "/sys/block/*/queue", "nr_requests")
        addLoopWrites(commands, prefs.getString("saved_add_random", null), "/sys/block/*/queue", "add_random")
        addLoopWrites(commands, prefs.getString("saved_iostats", null), "/sys/block/*/queue", "iostats")

        // Memory / VM
        addWrites(commands, prefs.getString("saved_swappiness", null), "/proc/sys/vm/swappiness")
        addWrites(commands, prefs.getString("saved_page_cluster", null), "/proc/sys/vm/page-cluster")
        addWrites(commands, prefs.getString("saved_vfs", null), "/proc/sys/vm/vfs_cache_pressure")
        addWrites(commands, prefs.getString("saved_mglru", null), "/sys/kernel/mm/lru_gen/enabled")
        addWrites(commands, prefs.getString("saved_watermark_scale", null), "/proc/sys/vm/watermark_scale_factor")
        addWrites(commands, prefs.getString("saved_dirty_ratio", null), "/proc/sys/vm/dirty_ratio")
        addWrites(commands, prefs.getString("saved_dirty_bg_ratio", null), "/proc/sys/vm/dirty_background_ratio")

        // Network
        addWrites(commands, prefs.getString("saved_tcp", null), "/proc/sys/net/ipv4/tcp_congestion_control")
        addWrites(commands, prefs.getString("saved_tcp_fastopen", null), "/proc/sys/net/ipv4/tcp_fastopen")
        addWrites(commands, prefs.getString("saved_tcp_ecn", null), "/proc/sys/net/ipv4/tcp_ecn")
        addWrites(commands, prefs.getString("saved_tcp_window", null), "/proc/sys/net/ipv4/tcp_window_scaling")
        addWrites(commands, prefs.getString("saved_disable_ipv6", null),
            "/proc/sys/net/ipv6/conf/all/disable_ipv6",
            "/proc/sys/net/ipv6/conf/default/disable_ipv6"
        )

        return commands.toList()
    }

    private fun updateNotificationProgress(taskText: String, step: Int, totalSteps: Int) {
        notificationBuilder
            .setContentText(getString(R.string.service_notification_step_format, taskText, step, totalSteps))
            .setProgress(totalSteps, step, false)
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
    }

    private fun updateNotificationFinal(finalText: String) {
        notificationBuilder.setContentText(finalText)
            .setSubText(getString(R.string.service_notification_subtext))
            .setProgress(0, 0, false)
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.service_channel_desc)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}
