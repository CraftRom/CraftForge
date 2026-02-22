package com.craftforge.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.craftforge.app.data.DeviceInfoItem
import com.craftforge.app.data.models.BatteryData
import com.craftforge.app.data.models.InfoCardTheme
import com.craftforge.app.data.models.RamData
import com.craftforge.app.data.models.StorageData
import com.craftforge.app.ui.theme.*

@Composable
fun InfoProgressBar(progress: Float, styles: InfoCardStyles, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { progress },
        modifier = modifier
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp)),
        color = styles.accentColor,
        trackColor = styles.progressTrackColor,
        strokeCap = StrokeCap.Round
    )
}

@Composable
fun InfoBlock(
    title: String,
    icon: ImageVector?,
    contentPadding: Dp = 12.dp,
    styles: InfoCardStyles = infoCardStyles(),
    content: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // ДОДАНО: Рамка, ідентична до TweaksScreen
            .border(
                width = 1.dp,
                color = styles.accentColor.copy(alpha = 0.2f),
                shape = RoundedCornerShape(styles.cardCornerRadius)
            )
            .clip(RoundedCornerShape(styles.cardCornerRadius))
            .background(styles.cardBackgroundColor)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon?.let {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(styles.accentColor.copy(alpha = 0.15f))
                    .padding(6.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = it,
                    contentDescription = title,
                    tint = styles.accentColor,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = styles.titleFontSize,
                fontWeight = FontWeight.Bold,
                color = styles.titleTextColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
fun RamInfoBlock(data: RamData, styles: InfoCardStyles = infoCardStyles()) {
    InfoBlock(title = "Random Access Memory", icon = null, contentPadding = styles.cardPadding) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = "RAM - ${data.totalMb} MB Total",
                fontSize = styles.rowFontSize,
                fontWeight = FontWeight.Bold,
                color = styles.titleTextColor
            )
            Text(
                text = "${data.usedMb} MB Used (${data.usedPercent}%)",
                fontSize = styles.rowFontSize,
                color = styles.valueTextColor
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            RamGraphCompose(
                history = data.history,
                styles = styles,
                modifier = Modifier.fillMaxWidth().height(80.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${data.freeMb} MB Free",
                fontSize = styles.rowFontSize,
                color = styles.labelTextColor,
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

@Composable
fun StorageInfoBlock(data: StorageData, styles: InfoCardStyles = infoCardStyles()) {
    val progress = if (data.totalGb > 0) (data.usedPercent / 100f) else 0f
    InfoBlock(title = "Internal Storage", icon = Icons.Default.Storage, styles = styles) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            InfoProgressBar(progress = progress, styles = styles, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "${data.usedPercent}%",
                fontSize = styles.titleFontSize,
                fontWeight = FontWeight.Bold,
                color = styles.valueTextColor
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "Free: ${"%.1f".format(data.freeGb)} GB / ${"%.1f".format(data.totalGb)} GB",
            fontSize = styles.rowFontSize,
            color = styles.labelTextColor
        )
    }
}

@Composable
fun BatteryInfoBlock(data: BatteryData, styles: InfoCardStyles = infoCardStyles()) {
    val progress = data.levelPercent / 100f
    InfoBlock(title = "Battery", icon = Icons.Default.BatteryChargingFull, styles = styles) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            InfoProgressBar(progress = progress, styles = styles, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "${data.levelPercent}%",
                fontSize = styles.titleFontSize,
                fontWeight = FontWeight.Bold,
                color = styles.valueTextColor
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        val baseInfo = if (data.temperature.toFloat() != -1f) {
            "Voltage: ${data.voltageMv} mV / Temp: ${data.temperature}°C"
        } else {
            "Voltage: ${data.voltageMv} mV"
        }
        val secondaryInfoText = if (data.isCharging && data.batteryChargePower > 0.0) {
            "$baseInfo / Charging: ${"%.1f".format(data.batteryChargePower)} W"
        } else {
            baseInfo
        }
        Text(text = secondaryInfoText, fontSize = styles.rowFontSize, color = styles.labelTextColor)
    }
}

@Composable
fun HeadBlock(title: String, items: List<DeviceInfoItem>, styles: InfoCardStyles = infoCardStyles(), modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(styles.cardPadding)
            // ДОДАНО: Рамка навколо Card
            .border(
                width = 1.dp,
                color = styles.accentColor.copy(alpha = 0.2f),
                shape = RoundedCornerShape(styles.cardCornerRadius)
            ),
        shape = RoundedCornerShape(styles.cardCornerRadius),
        colors = CardDefaults.cardColors(containerColor = styles.cardBackgroundColor)
    ) {
        Column(modifier = Modifier.padding(styles.cardPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = title,
                color = styles.titleTextColor,
                fontSize = styles.titleFontSize,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(styles.titleSpacerHeight))
            items.forEach { item ->
                InfoRowStyled(
                    label = item.label,
                    value = item.value,
                    labelColor = styles.labelTextColor,
                    valueColor = styles.valueTextColor,
                    fontSize = styles.rowFontSize
                )
            }
        }
    }
}

@Composable
fun DeviceInfoRow(item: DeviceInfoItem, styles: InfoCardStyles = infoCardStyles()) { // Замінив theme на styles
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text = item.label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), fontSize = 12.sp)
        val permissionRequiredMsg = "READ_PHONE_STATE permission required"
        val valueColor = when {
            item.isError -> Color(0xFFFF5252)
            item.value == permissionRequiredMsg -> Color(0xFFFFC285)
            else -> MaterialTheme.colorScheme.onSurface
        }
        Text(text = item.value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(
            Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)).padding(top = 6.dp)
        )
    }
}