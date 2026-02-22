package com.craftforge.app.ui.screens

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.craftforge.app.ui.DeviceDetailActivity
import com.craftforge.app.ui.theme.InfoCardUniversal

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val deviceName = remember {
        "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
    }

    val deviceInfo = remember {
        listOf(
            "Device" to Build.DEVICE,
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Security patch" to (Build.VERSION.SECURITY_PATCH ?: "Unknown"),
            "Board" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                "${Build.BOARD} (${Build.SOC_MANUFACTURER}-${Build.SOC_MODEL})"
            } else {
                "${Build.BOARD} (unknown)"
            },
            "Kernel" to (System.getProperty("os.version") ?: "Unknown")
        )
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            InfoCardUniversal(
                title = deviceName,
                info = deviceInfo,
                onClick = {
                    context.startActivity(Intent(context, DeviceDetailActivity::class.java))
                }
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    HomeScreen()
}