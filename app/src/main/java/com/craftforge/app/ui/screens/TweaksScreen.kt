package com.craftforge.app.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.craftforge.app.data.DeviceInfoProvider
import com.craftforge.app.service.TweaksService
import com.craftforge.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ОПТИМІЗАЦІЯ: Розділили I/O та Мережу
enum class TweaksRoute {
    MAIN, CPU_CONFIG, GPU_CONFIG, IO_CONFIG, NET_CONFIG
}

@Composable
fun TweaksScreen() {
    var currentRoute by remember { mutableStateOf(TweaksRoute.MAIN) }
    val context = LocalContext.current
    var isRooted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val provider = DeviceInfoProvider(context)
            @Suppress("MissingPermission")
            isRooted = provider.getStaticDeviceInfo().isRooted
        }
    }

    BackHandler(enabled = currentRoute != TweaksRoute.MAIN) {
        currentRoute = TweaksRoute.MAIN
    }

    // Маршрутизатор
    when (currentRoute) {
        TweaksRoute.MAIN -> MainTweaksMenu(isRooted = isRooted, onNavigate = { currentRoute = it })
        TweaksRoute.CPU_CONFIG -> CpuConfigScreen(isRooted) { currentRoute = TweaksRoute.MAIN }
        TweaksRoute.GPU_CONFIG -> GpuConfigScreen(isRooted) { currentRoute = TweaksRoute.MAIN }
        TweaksRoute.IO_CONFIG -> IoConfigScreen(isRooted) { currentRoute = TweaksRoute.MAIN }
        TweaksRoute.NET_CONFIG -> NetConfigScreen(isRooted) { currentRoute = TweaksRoute.MAIN }
    }
}

@Composable
fun MainTweaksMenu(isRooted: Boolean, onNavigate: (TweaksRoute) -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("TweaksPrefs", Context.MODE_PRIVATE)
    val styles = infoCardStyles()

    var runInBg by remember { mutableStateOf(isServiceRunning(context, TweaksService::class.java)) }
    var runOnBoot by remember { mutableStateOf(prefs.getBoolean("run_on_boot", false)) }

    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { _ ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            item {
                Text(
                    text = "System Tweaks",
                    style = MaterialTheme.typography.headlineLarge,
                    color = styles.titleTextColor,
                    modifier = Modifier.padding(16.dp)
                )
            }

            item {
                StyledBlockCard(styles = styles, title = "Background Service") {
                    SettingsSwitchRow(
                        title = "Run in Background", subtitle = "Keep optimizations active.", checked = runInBg, styles = styles,
                        onCheckedChange = {
                            runInBg = it
                            if (it) startTweakService(context) else context.stopService(Intent(context, TweaksService::class.java))
                        }
                    )
                    HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsSwitchRow(
                        title = "Apply on Boot", subtitle = "Start service on device boot.", checked = runOnBoot, styles = styles,
                        onCheckedChange = {
                            runOnBoot = it
                            prefs.edit().putBoolean("run_on_boot", it).apply()
                        }
                    )
                }
            }

            // ОПТИМІЗАЦІЯ: 4 окремі кнопки
            item {
                Spacer(modifier = Modifier.height(8.dp))
                StyledBlockCard(styles = styles, title = "Kernel Profiles (Root)") {
                    SettingsNavigationRow(title = "CPU Configuration", subtitle = "Governors, frequencies.", styles = styles) { onNavigate(TweaksRoute.CPU_CONFIG) }
                    HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))

                    SettingsNavigationRow(title = "GPU Configuration", subtitle = "Adreno/Mali tuning.", styles = styles) { onNavigate(TweaksRoute.GPU_CONFIG) }
                    HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))

                    SettingsNavigationRow(title = "Storage & I/O", subtitle = "Disk schedulers.", styles = styles) { onNavigate(TweaksRoute.IO_CONFIG) }
                    HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))

                    SettingsNavigationRow(title = "Network Tuning", subtitle = "TCP algorithms.", styles = styles) { onNavigate(TweaksRoute.NET_CONFIG) }
                }
            }
        }
    }
}

// ================= СПІЛЬНІ UI КОМПОНЕНТИ =================
@Composable
fun SettingsNavigationRow(title: String, subtitle: String, styles: InfoCardStyles, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = styles.titleTextColor)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = subtitle, fontSize = 12.sp, color = styles.titleTextColor.copy(alpha = 0.6f), lineHeight = 16.sp)
        }
        Box(
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(styles.accentColor.copy(alpha = 0.1f)).padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) { Text(text = "OPEN", fontSize = 12.sp, color = styles.accentColor, fontWeight = FontWeight.Bold) }
    }
}

@Composable
fun ConfigScreenLayout(title: String, styles: InfoCardStyles, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { _ ->
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = styles.titleTextColor) }
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = title, style = MaterialTheme.typography.headlineMedium, color = styles.titleTextColor)
            }
            content()
        }
    }
}

// ================= ROOT МЕНЕДЖЕР ТА СЕРВІСИ =================
object RootManager {
    suspend fun readNodeViaRoot(command: String): String? = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val result = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor()
            result.ifEmpty { null }
        } catch (e: Exception) { null }
    }

    suspend fun executeRootCommand(command: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            process.waitFor() == 0
        } catch (e: Exception) { false }
    }
}

private fun startTweakService(context: Context) {
    val intent = Intent(context, TweaksService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
}

@Suppress("DEPRECATION")
private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    for (service in manager.getRunningServices(Int.MAX_VALUE)) {
        if (serviceClass.name == service.service.className) return true
    }
    return false
}