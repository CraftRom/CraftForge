package com.craftforge.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import com.craftforge.app.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class InfoCardStyles(
    val cardBackgroundColor: Color,
    val titleTextColor: Color,
    val valueTextColor: Color,
    val labelTextColor: Color,
    val progressTrackColor: Color,
    val accentColor: Color,
    val chartBackgroundColor: Color,
    val rowBackgroundColor: Color,
    val chipBackgroundColor: Color,
    val mutedTextColor: Color,

    val cardPadding: Dp = 16.dp,
    val innerColumnPadding: Dp = 16.dp,
    val cardCornerRadius: Dp = 20.dp,
    val rowCornerRadius: Dp = 14.dp,

    val progressSize: Dp = 80.dp,
    val progressStrokeWidth: Dp = 4.dp,

    val chartHeight: Dp = 100.dp,
    val chartLineThickness: Float = 3f,

    val titleFontSize: TextUnit = 18.sp,
    val rowFontSize: TextUnit = 12.sp,
    val progressFontSize: TextUnit = 20.sp,

    val titleSpacerHeight: Dp = 4.dp
)

@Composable
fun infoCardStyles(): InfoCardStyles {
    val colors = MaterialTheme.colorScheme
    val isDark = isSystemInDarkTheme()

    return InfoCardStyles(
        cardBackgroundColor = colors.surface,
        titleTextColor = colors.onSurface,
        valueTextColor = colors.onSurface,
        labelTextColor = if (isDark) AppColors.TextSecondaryDark else AppColors.TextSecondaryLight,
        progressTrackColor = colors.onSurface.copy(alpha = 0.1f),
        accentColor = colors.secondary,
        chartBackgroundColor = colors.surfaceVariant.copy(alpha = 0.5f),
        rowBackgroundColor = colors.surfaceVariant.copy(alpha = if (isDark) 0.22f else 0.68f),
        chipBackgroundColor = colors.secondaryContainer.copy(alpha = if (isDark) 0.52f else 0.9f),
        mutedTextColor = colors.onSurface.copy(alpha = 0.68f)
    )
}

@Composable
fun StyledBlockCard(title: String, styles: InfoCardStyles = infoCardStyles(), content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (title.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .padding(bottom = 10.dp, start = 4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(styles.chipBackgroundColor)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(styles.accentColor)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = title,
                    color = styles.titleTextColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(styles.cardCornerRadius))
                .background(styles.cardBackgroundColor)
                .border(1.dp, styles.accentColor.copy(alpha = 0.12f), RoundedCornerShape(styles.cardCornerRadius)),
            content = content
        )
    }
}

@Composable
fun SettingsSwitchRow(title: String, subtitle: String, checked: Boolean, styles: InfoCardStyles = infoCardStyles(), onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .clip(RoundedCornerShape(styles.rowCornerRadius))
            .background(styles.rowBackgroundColor)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = styles.titleTextColor)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = subtitle, fontSize = 12.sp, color = styles.mutedTextColor, lineHeight = 16.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.surface,
                checkedTrackColor = styles.accentColor,
                uncheckedThumbColor = styles.titleTextColor.copy(alpha = 0.7f),
                uncheckedTrackColor = styles.cardBackgroundColor,
                uncheckedBorderColor = styles.titleTextColor.copy(alpha = 0.3f)
            )
        )
    }
}

@Composable
fun SettingsBadgeRow(title: String, subtitle: String, value: String, isRooted: Boolean, styles: InfoCardStyles = infoCardStyles()) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(styles.rowCornerRadius))
            .background(styles.rowBackgroundColor)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (isRooted) styles.titleTextColor else styles.titleTextColor.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = if (isRooted) subtitle else stringResource(R.string.common_requires_root_read), fontSize = 12.sp, color = styles.mutedTextColor, lineHeight = 16.sp)
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (isRooted) styles.chipBackgroundColor else MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
                .border(1.dp, if (isRooted) styles.accentColor.copy(alpha = 0.18f) else MaterialTheme.colorScheme.error.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = if (isRooted) value else stringResource(R.string.common_locked), fontSize = 12.sp, color = if (isRooted) styles.accentColor else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
fun SettingsDropdownRow(
    title: String,
    subtitle: String,
    currentValue: String,
    availableValues: List<String>,
    isRooted: Boolean,
    styles: InfoCardStyles = infoCardStyles(),
    isBusy: Boolean = false,
    progress: Float? = null,
    progressText: String? = null,
    onValueSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(styles.rowCornerRadius))
            .background(styles.rowBackgroundColor)
            .padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (isRooted) styles.titleTextColor else styles.titleTextColor.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = if (isRooted) subtitle else stringResource(R.string.common_requires_root_modify), fontSize = 12.sp, color = styles.mutedTextColor, lineHeight = 16.sp)
            }
            Box {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isRooted) styles.chipBackgroundColor else MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
                        .border(1.dp, if (isRooted) styles.accentColor.copy(alpha = 0.2f) else MaterialTheme.colorScheme.error.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                        .clickable(enabled = isRooted && availableValues.isNotEmpty() && !isBusy) { expanded = true }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isRooted) currentValue else stringResource(R.string.common_locked),
                        fontSize = 12.sp,
                        color = if (isRooted) styles.accentColor else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isRooted && availableValues.isNotEmpty()) {
                        Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null, tint = styles.accentColor, modifier = Modifier.padding(start = 4.dp).size(16.dp))
                    }
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(styles.cardBackgroundColor)) {
                    availableValues.forEach { value ->
                        DropdownMenuItem(text = { Text(text = value, color = styles.titleTextColor) }, onClick = { expanded = false; onValueSelected(value) })
                    }
                }
            }
        }

        if (isBusy || progress != null || !progressText.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            if (!progressText.isNullOrBlank()) {
                Text(text = progressText, fontSize = 11.sp, color = styles.mutedTextColor)
                Spacer(modifier = Modifier.height(6.dp))
            }
            LinearProgressIndicator(
                progress = progress?.coerceIn(0f, 1f) ?: 0f,
                modifier = Modifier.fillMaxWidth(),
                color = styles.accentColor,
                trackColor = styles.progressTrackColor
            )
        }
    }
}
