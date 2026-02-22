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
import kotlinx.coroutines.*

class TweaksService : Service() {

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
            .setContentTitle("CraftForge Engine")
            .setContentText("Initializing optimization core...")
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
        val commands = mutableListOf<String>()

        // ==========================================
        // 1. CPU Базові
        // ==========================================
        prefs.getString("saved_governor", null)?.let { commands.add("echo $it > /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor") }
        prefs.getString("saved_max_freq", null)?.let { commands.add("echo $it > /sys/devices/system/cpu/cpu*/cpufreq/scaling_max_freq") }
        prefs.getString("saved_min_freq", null)?.let { commands.add("echo $it > /sys/devices/system/cpu/cpu*/cpufreq/scaling_min_freq") }

        // ==========================================
        // 2. CPU Просунуті (Touchboost, EAS, Sleep)
        // ==========================================
        prefs.getString("saved_touchboost", null)?.let { commands.add("echo $it > /sys/module/msm_performance/parameters/touchboost") }
        prefs.getString("saved_mc_power", null)?.let { commands.add("echo $it > /sys/devices/system/cpu/sched_mc_power_savings") }
        prefs.getString("saved_power_collapse", null)?.let { commands.add("echo $it > /sys/module/pm_8x60/parameters/sleep_mode") }

        // EAS (Energy Aware Scheduling) - ВСІ ФІЧІ
        prefs.getString("saved_eas_enable", null)?.let { commands.add("echo $it > /sys/devices/system/cpu/eas/enable") }
        prefs.getString("saved_sched_boost", null)?.let { commands.add("echo $it > /proc/sys/kernel/sched_boost") }
        prefs.getString("saved_sched_upmigrate", null)?.let { commands.add("echo $it > /proc/sys/kernel/sched_upmigrate") }
        prefs.getString("saved_sched_downmigrate", null)?.let { commands.add("echo $it > /proc/sys/kernel/sched_downmigrate") }
        prefs.getString("saved_capacity_margin", null)?.let { commands.add("echo $it > /proc/sys/kernel/sched_capacity_margin_up") }
        prefs.getString("saved_init_task_util", null)?.let { commands.add("echo $it > /proc/sys/kernel/sched_initial_task_util") }
        prefs.getString("saved_autogroup", null)?.let { commands.add("echo $it > /proc/sys/kernel/sched_autogroup_enabled") }

        // Tunables
        prefs.getString("saved_sched_uprate", null)?.let { commands.add("echo $it > /sys/devices/system/cpu/cpufreq/schedutil/up_rate_limit_us") }
        prefs.getString("saved_interactive_hispeed", null)?.let { commands.add("echo $it > /sys/devices/system/cpu/cpufreq/interactive/hispeed_freq") }
        prefs.getString("saved_walt_uprate", null)?.let { commands.add("echo $it > /sys/devices/system/cpu/cpufreq/walt/up_rate_limit_us") }
        prefs.getString("saved_walt_downrate", null)?.let { commands.add("echo $it > /sys/devices/system/cpu/cpufreq/walt/down_rate_limit_us") }

        // ==========================================
        // 3. GPU (Adreno)
        // ==========================================
        prefs.getString("saved_gpu_governor", null)?.let { commands.add("echo $it > /sys/class/kgsl/kgsl-3d0/devfreq/governor") }
        prefs.getString("saved_gpu_max_freq", null)?.let { commands.add("echo $it > /sys/class/kgsl/kgsl-3d0/devfreq/max_freq") }
        prefs.getString("saved_gpu_min_freq", null)?.let { commands.add("echo $it > /sys/class/kgsl/kgsl-3d0/devfreq/min_freq") }
        prefs.getString("saved_adreno_idler", null)?.let { commands.add("echo $it > /sys/module/adreno_idler/parameters/adreno_idler_active") }
        prefs.getString("saved_gpu_idle_timer", null)?.let { commands.add("echo $it > /sys/class/kgsl/kgsl-3d0/idle_timer") }
        prefs.getString("saved_adrenoboost", null)?.let { commands.add("echo $it > /sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost") }

        // ==========================================
        // 4. STORAGE & I/O
        // ==========================================
        prefs.getString("saved_scheduler", null)?.let {
            commands.add("echo $it > /sys/block/mmcblk0/queue/scheduler")
            commands.add("echo $it > /sys/block/sda/queue/scheduler")
        }
        prefs.getString("saved_readahead", null)?.let {
            commands.add("echo $it > /sys/block/sda/queue/read_ahead_kb")
            commands.add("echo $it > /sys/block/mmcblk0/queue/read_ahead_kb")
        }
        prefs.getString("saved_nr_requests", null)?.let {
            commands.add("echo $it > /sys/block/sda/queue/nr_requests")
            commands.add("echo $it > /sys/block/mmcblk0/queue/nr_requests")
        }
        prefs.getString("saved_add_random", null)?.let {
            commands.add("echo $it > /sys/block/sda/queue/add_random")
            commands.add("echo $it > /sys/block/mmcblk0/queue/add_random")
        }
        prefs.getString("saved_iostats", null)?.let {
            commands.add("echo $it > /sys/block/sda/queue/iostats")
            commands.add("echo $it > /sys/block/mmcblk0/queue/iostats")
        }

        // ==========================================
        // 5. MEMORY & VM (ZRAM, MGLRU, Dirty Cache)
        // ==========================================
        prefs.getString("saved_zram_comp", null)?.let { commands.add("echo $it > /sys/block/zram0/comp_algorithm") }
        prefs.getString("saved_swappiness", null)?.let { commands.add("echo $it > /proc/sys/vm/swappiness") }
        prefs.getString("saved_page_cluster", null)?.let { commands.add("echo $it > /proc/sys/vm/page-cluster") }
        prefs.getString("saved_vfs", null)?.let { commands.add("echo $it > /proc/sys/vm/vfs_cache_pressure") }

        prefs.getString("saved_mglru", null)?.let { commands.add("echo $it > /sys/kernel/mm/lru_gen/enabled") }
        prefs.getString("saved_watermark_scale", null)?.let { commands.add("echo $it > /proc/sys/vm/watermark_scale_factor") }

        prefs.getString("saved_dirty_ratio", null)?.let { commands.add("echo $it > /proc/sys/vm/dirty_ratio") }
        prefs.getString("saved_dirty_bg_ratio", null)?.let { commands.add("echo $it > /proc/sys/vm/dirty_background_ratio") }

        // ==========================================
        // 6. NETWORK
        // ==========================================
        prefs.getString("saved_tcp", null)?.let { commands.add("echo $it > /proc/sys/net/ipv4/tcp_congestion_control") }
        prefs.getString("saved_tcp_fastopen", null)?.let { commands.add("echo $it > /proc/sys/net/ipv4/tcp_fastopen") }
        prefs.getString("saved_tcp_ecn", null)?.let { commands.add("echo $it > /proc/sys/net/ipv4/tcp_ecn") }
        prefs.getString("saved_tcp_window", null)?.let { commands.add("echo $it > /proc/sys/net/ipv4/tcp_window_scaling") }
        prefs.getString("saved_disable_ipv6", null)?.let {
            commands.add("echo $it > /proc/sys/net/ipv6/conf/all/disable_ipv6")
            commands.add("echo $it > /proc/sys/net/ipv6/conf/default/disable_ipv6")
        }

        if (commands.isNotEmpty()) {
            val total = commands.size
            updateNotificationProgress("Forging system...", 0, total)

            withTimeoutOrNull(EXECUTION_TIMEOUT) {
                try {
                    // Виконання всіх команд через один сеанс SU для швидкості
                    val process = Runtime.getRuntime().exec("su")
                    process.outputStream.bufferedWriter().use { writer ->
                        commands.forEachIndexed { index, cmd ->
                            writer.write("$cmd\n")
                            // Оновлюємо UI кожні 10 команд
                            if (index % 10 == 0 || index == total - 1) {
                                updateNotificationProgress("Applying tweaks...", index + 1, total)
                            }
                        }
                        writer.write("exit\n")
                        writer.flush()
                    }
                    process.waitFor()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        updateNotificationFinal("Optimizations Active & Protected")
    }

    private fun updateNotificationProgress(taskText: String, step: Int, totalSteps: Int) {
        notificationBuilder.setContentText("$taskText ($step/$totalSteps)")
            .setProgress(totalSteps, step, false)
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
    }

    private fun updateNotificationFinal(finalText: String) {
        notificationBuilder.setContentText(finalText)
            .setSubText("Hardware tuned successfully")
            .setProgress(0, 0, false)
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel() // Зупинка всіх фонових процесів
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Core System Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Critical service for hardware performance and kernel tweaks"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}