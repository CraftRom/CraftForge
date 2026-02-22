package com.craftforge.app.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.craftforge.app.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun NetConfigScreen(isRooted: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("TweaksPrefs", Context.MODE_PRIVATE)
    val styles = infoCardStyles()
    val scope = rememberCoroutineScope()

    // Стан для керування прокруткою
    val scrollState = rememberScrollState()

    // Стан TCP Алгоритму
    var currentTcp by remember { mutableStateOf("Loading...") }
    var availableTcps by remember { mutableStateOf(listOf<String>()) }

    // Просунуті мережеві стани
    var isTcpFastOpenEnabled by remember { mutableStateOf(false) }
    var isTcpEcnEnabled by remember { mutableStateOf(false) }
    var isWindowScalingEnabled by remember { mutableStateOf(false) }
    var isIpv6Disabled by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (isRooted) {
            // 1. TCP Алгоритм
            currentTcp = RootManager.readNodeViaRoot("cat /proc/sys/net/ipv4/tcp_congestion_control") ?: "Unknown"
            val tcpList = RootManager.readNodeViaRoot("cat /proc/sys/net/ipv4/tcp_available_congestion_control") ?: ""
            availableTcps = tcpList.split(" ").filter { it.isNotBlank() }

            // 2. TCP Fast Open
            val tfoRaw = RootManager.readNodeViaRoot("cat /proc/sys/net/ipv4/tcp_fastopen")
            isTcpFastOpenEnabled = tfoRaw == "3"

            // 3. ECN
            val ecnRaw = RootManager.readNodeViaRoot("cat /proc/sys/net/ipv4/tcp_ecn")
            isTcpEcnEnabled = ecnRaw == "1"

            // 4. TCP Window Scaling
            val wsRaw = RootManager.readNodeViaRoot("cat /proc/sys/net/ipv4/tcp_window_scaling")
            isWindowScalingEnabled = wsRaw == "1"

            // 5. Вимкнення IPv6
            val ipv6Raw = RootManager.readNodeViaRoot("cat /proc/sys/net/ipv6/conf/all/disable_ipv6")
            isIpv6Disabled = ipv6Raw == "1"

        } else {
            currentTcp = "LOCKED"
        }
    }

    ConfigScreenLayout(title = "Network Tuning", styles = styles, onBack = onBack) {
        // Додаємо Column з підтримкою вертикального скролінгу
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(bottom = 24.dp)
        ) {
            // БЛОК 1: Алгоритми
            StyledBlockCard(styles = styles, title = "Congestion & Flow") {
                SettingsDropdownRow(
                    title = "TCP Congestion",
                    subtitle = "Select 'bbr' or 'westwood' for best latency & throughput.",
                    currentValue = currentTcp,
                    availableValues = availableTcps,
                    isRooted = isRooted,
                    styles = styles,
                    onValueSelected = { selected ->
                        currentTcp = selected
                        scope.launch {
                            RootManager.executeRootCommand("echo $selected > /proc/sys/net/ipv4/tcp_congestion_control")
                            prefs.edit().putString("saved_tcp", selected).apply()
                            Toast.makeText(context, "TCP Algorithm applied!", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // БЛОК 2: Просунута оптимізація ядра
            StyledBlockCard(styles = styles, title = "Advanced Kernel Networking") {
                SettingsSwitchRow(
                    title = "TCP Fast Open (TFO)",
                    subtitle = "Send data during handshake. Massively speeds up page loads.",
                    checked = isTcpFastOpenEnabled,
                    styles = styles,
                    onCheckedChange = { isChecked ->
                        if (!isRooted) return@SettingsSwitchRow
                        isTcpFastOpenEnabled = isChecked
                        val value = if (isChecked) "3" else "1"
                        scope.launch {
                            RootManager.executeRootCommand("echo $value > /proc/sys/net/ipv4/tcp_fastopen")
                            prefs.edit().putString("saved_tcp_fastopen", value).apply()
                            Toast.makeText(context, "TCP Fast Open updated!", Toast.LENGTH_SHORT).show()
                        }
                    }
                )

                HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))

                SettingsSwitchRow(
                    title = "Explicit Congestion (ECN)",
                    subtitle = "Reduces gaming latency by avoiding packet drops.",
                    checked = isTcpEcnEnabled,
                    styles = styles,
                    onCheckedChange = { isChecked ->
                        if (!isRooted) return@SettingsSwitchRow
                        isTcpEcnEnabled = isChecked
                        val value = if (isChecked) "1" else "0"
                        scope.launch {
                            RootManager.executeRootCommand("echo $value > /proc/sys/net/ipv4/tcp_ecn")
                            prefs.edit().putString("saved_tcp_ecn", value).apply()
                            Toast.makeText(context, "ECN updated!", Toast.LENGTH_SHORT).show()
                        }
                    }
                )

                HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))

                SettingsSwitchRow(
                    title = "TCP Window Scaling",
                    subtitle = "Crucial for >100Mbps speeds (Wi-Fi 5/6, 5G).",
                    checked = isWindowScalingEnabled,
                    styles = styles,
                    onCheckedChange = { isChecked ->
                        if (!isRooted) return@SettingsSwitchRow
                        isWindowScalingEnabled = isChecked
                        val value = if (isChecked) "1" else "0"
                        scope.launch {
                            RootManager.executeRootCommand("echo $value > /proc/sys/net/ipv4/tcp_window_scaling")
                            prefs.edit().putString("saved_tcp_window", value).apply()
                            Toast.makeText(context, "Window Scaling updated!", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // БЛОК 3: Управління протоколами
            StyledBlockCard(styles = styles, title = "Protocol Management") {
                SettingsSwitchRow(
                    title = "Disable IPv6",
                    subtitle = "Improves battery and fixes DNS leaks/delays on unsupported networks.",
                    checked = isIpv6Disabled,
                    styles = styles,
                    onCheckedChange = { isChecked ->
                        if (!isRooted) return@SettingsSwitchRow
                        isIpv6Disabled = isChecked
                        val value = if (isChecked) "1" else "0"
                        scope.launch {
                            RootManager.executeRootCommand("echo $value > /proc/sys/net/ipv6/conf/all/disable_ipv6")
                            RootManager.executeRootCommand("echo $value > /proc/sys/net/ipv6/conf/default/disable_ipv6")
                            prefs.edit().putString("saved_disable_ipv6", value).apply()
                            Toast.makeText(context, "IPv6 settings updated!", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
    }
}