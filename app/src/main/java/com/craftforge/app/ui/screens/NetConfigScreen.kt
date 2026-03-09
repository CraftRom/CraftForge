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
import com.craftforge.app.util.RootManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun NetConfigScreen(isRooted: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("TweaksPrefs", Context.MODE_PRIVATE)
    val styles = infoCardStyles()
    val scope = rememberCoroutineScope()

    val scrollState = rememberScrollState()

    var tcpPath by remember { mutableStateOf<String?>(null) }
    var tcpAvailablePath by remember { mutableStateOf<String?>(null) }
    var currentTcp by remember { mutableStateOf("Loading...") }
    var availableTcps by remember { mutableStateOf(listOf<String>()) }

    var tcpFastOpenPath by remember { mutableStateOf<String?>(null) }
    var isTcpFastOpenEnabled by remember { mutableStateOf(false) }

    var tcpEcnPath by remember { mutableStateOf<String?>(null) }
    var isTcpEcnEnabled by remember { mutableStateOf(false) }

    var windowScalingPath by remember { mutableStateOf<String?>(null) }
    var isWindowScalingEnabled by remember { mutableStateOf(false) }

    var ipv6AllPath by remember { mutableStateOf<String?>(null) }
    var ipv6DefaultPath by remember { mutableStateOf<String?>(null) }
    var isIpv6Disabled by remember { mutableStateOf(false) }

    LaunchedEffect(isRooted) {
        if (isRooted) {
            withContext(Dispatchers.IO) {
                suspend fun probeReadableWritable(path: String): String? {
                    val exists = RootManager.nodeExists(path)
                    val writable = RootManager.nodeWritable(path)
                    if (!exists || !writable) return null
                    return RootManager.readNode(path)?.trim()?.takeIf { it.isNotBlank() }
                }

                val tcpRaw = probeReadableWritable("/proc/sys/net/ipv4/tcp_congestion_control")
                tcpPath = tcpRaw?.let { "/proc/sys/net/ipv4/tcp_congestion_control" }
                currentTcp = tcpRaw ?: "Unknown"

                val tcpListRaw = RootManager.readNode("/proc/sys/net/ipv4/tcp_available_congestion_control")?.trim().orEmpty()
                tcpAvailablePath = tcpPath?.let { "/proc/sys/net/ipv4/tcp_available_congestion_control" }
                availableTcps = tcpListRaw.split(" ").filter { it.isNotBlank() }

                val tfoRaw = probeReadableWritable("/proc/sys/net/ipv4/tcp_fastopen")
                tcpFastOpenPath = tfoRaw?.let { "/proc/sys/net/ipv4/tcp_fastopen" }
                isTcpFastOpenEnabled = tfoRaw == "3"

                val ecnRaw = probeReadableWritable("/proc/sys/net/ipv4/tcp_ecn")
                tcpEcnPath = ecnRaw?.let { "/proc/sys/net/ipv4/tcp_ecn" }
                isTcpEcnEnabled = ecnRaw == "1"

                val wsRaw = probeReadableWritable("/proc/sys/net/ipv4/tcp_window_scaling")
                windowScalingPath = wsRaw?.let { "/proc/sys/net/ipv4/tcp_window_scaling" }
                isWindowScalingEnabled = wsRaw == "1"

                val ipv6AllRaw = probeReadableWritable("/proc/sys/net/ipv6/conf/all/disable_ipv6")
                ipv6AllPath = ipv6AllRaw?.let { "/proc/sys/net/ipv6/conf/all/disable_ipv6" }
                val ipv6DefaultRaw = probeReadableWritable("/proc/sys/net/ipv6/conf/default/disable_ipv6")
                ipv6DefaultPath = ipv6DefaultRaw?.let { "/proc/sys/net/ipv6/conf/default/disable_ipv6" }
                isIpv6Disabled = ipv6AllRaw == "1" || ipv6DefaultRaw == "1"
            }
        } else {
            currentTcp = "LOCKED"
        }
    }

    ConfigScreenLayout(title = "Network Tuning", styles = styles, onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(bottom = 24.dp)
        ) {
            val showCongestionSection = ((tcpPath != null && availableTcps.isNotEmpty()) || !isRooted)
            if (showCongestionSection) {
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
                                RootManager.executeRootCommand("echo $selected > $tcpPath")
                                prefs.edit().putString("saved_tcp", selected).apply()
                                Toast.makeText(context, "TCP Algorithm applied!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            val showAdvancedSection = tcpFastOpenPath != null || tcpEcnPath != null || windowScalingPath != null || !isRooted
            if (showAdvancedSection) {
                StyledBlockCard(styles = styles, title = "Advanced Kernel Networking") {
                    var rowShown = false

                    if (tcpFastOpenPath != null || !isRooted) {
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
                                    RootManager.executeRootCommand("echo $value > $tcpFastOpenPath")
                                    prefs.edit().putString("saved_tcp_fastopen", value).apply()
                                    Toast.makeText(context, "TCP Fast Open updated!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                        rowShown = true
                    }

                    if (tcpEcnPath != null || !isRooted) {
                        if (rowShown) HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))
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
                                    RootManager.executeRootCommand("echo $value > $tcpEcnPath")
                                    prefs.edit().putString("saved_tcp_ecn", value).apply()
                                    Toast.makeText(context, "ECN updated!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                        rowShown = true
                    }

                    if (windowScalingPath != null || !isRooted) {
                        if (rowShown) HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsSwitchRow(
                            title = "TCP Window Scaling",
                            subtitle = "Crucial for >100Mbps speeds (Wi‑Fi 5/6, 5G).",
                            checked = isWindowScalingEnabled,
                            styles = styles,
                            onCheckedChange = { isChecked ->
                                if (!isRooted) return@SettingsSwitchRow
                                isWindowScalingEnabled = isChecked
                                val value = if (isChecked) "1" else "0"
                                scope.launch {
                                    RootManager.executeRootCommand("echo $value > $windowScalingPath")
                                    prefs.edit().putString("saved_tcp_window", value).apply()
                                    Toast.makeText(context, "Window Scaling updated!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            val showProtocolSection = ipv6AllPath != null || ipv6DefaultPath != null || !isRooted
            if (showProtocolSection) {
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
                                ipv6AllPath?.let { RootManager.executeRootCommand("echo $value > $it") }
                                ipv6DefaultPath?.let { RootManager.executeRootCommand("echo $value > $it") }
                                prefs.edit().putString("saved_disable_ipv6", value).apply()
                                Toast.makeText(context, "IPv6 settings updated!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        }
    }
}
