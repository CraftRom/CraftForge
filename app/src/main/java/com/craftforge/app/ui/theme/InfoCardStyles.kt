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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ================= СТИЛІ ТА КОЛЬОРИ =================

data class InfoCardStyles(
    val cardBackgroundColor: Color,
    val titleTextColor: Color,
    val valueTextColor: Color,
    val labelTextColor: Color,
    val progressTrackColor: Color,
    val accentColor: Color,
    val chartBackgroundColor: Color,

    val cardPadding: Dp = 16.dp,
    val innerColumnPadding: Dp = 16.dp,
    val cardCornerRadius: Dp = 16.dp,

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
        chartBackgroundColor = colors.surfaceVariant.copy(alpha = 0.5f)
    )
}

// ================= УНІВЕРСАЛЬНІ БАЗОВІ КОМПОНЕНТИ (TWEAKS STYLE) =================

@Composable
fun StyledBlockCard(title: String, styles: InfoCardStyles = infoCardStyles(), content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (title.isNotEmpty()) {
            Text(
                text = title,
                color = styles.titleTextColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(styles.cardCornerRadius))
                .background(styles.cardBackgroundColor)
                .border(1.dp, styles.accentColor.copy(alpha = 0.2f), RoundedCornerShape(styles.cardCornerRadius)),
            content = content
        )
    }
}

// ================= КОМПОНЕНТИ НАЛАШТУВАНЬ (ДЛЯ TWEAKS ТА ІНШИХ) =================

@Composable
fun SettingsSwitchRow(title: String, subtitle: String, checked: Boolean, styles: InfoCardStyles = infoCardStyles(), onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = styles.titleTextColor)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = subtitle, fontSize = 12.sp, color = styles.titleTextColor.copy(alpha = 0.6f), lineHeight = 16.sp)
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
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (isRooted) styles.titleTextColor else styles.titleTextColor.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = if (isRooted) subtitle else "Requires Root access to read data.", fontSize = 12.sp, color = styles.titleTextColor.copy(alpha = 0.6f), lineHeight = 16.sp)
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (isRooted) styles.accentColor.copy(alpha = 0.1f) else MaterialTheme.colorScheme.error.copy(alpha = 0.05f))
                .border(1.dp, if (isRooted) styles.accentColor.copy(alpha = 0.3f) else MaterialTheme.colorScheme.error.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = if (isRooted) value.uppercase() else "LOCKED", fontSize = 12.sp, color = if (isRooted) styles.accentColor else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun SettingsDropdownRow(title: String, subtitle: String, currentValue: String, availableValues: List<String>, isRooted: Boolean, styles: InfoCardStyles = infoCardStyles(), onValueSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (isRooted) styles.titleTextColor else styles.titleTextColor.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = if (isRooted) subtitle else "Requires Root access to modify.", fontSize = 12.sp, color = styles.titleTextColor.copy(alpha = 0.6f), lineHeight = 16.sp)
        }
        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isRooted) styles.accentColor.copy(alpha = 0.1f) else MaterialTheme.colorScheme.error.copy(alpha = 0.05f))
                    .border(1.dp, if (isRooted) styles.accentColor.copy(alpha = 0.3f) else MaterialTheme.colorScheme.error.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                    .clickable(enabled = isRooted && availableValues.isNotEmpty()) { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = currentValue.uppercase(), fontSize = 12.sp, color = if (isRooted) styles.accentColor else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                if (isRooted && availableValues.isNotEmpty()) {
                    Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = "Select", tint = styles.accentColor, modifier = Modifier.padding(start = 4.dp).size(16.dp))
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(styles.cardBackgroundColor)) {
                availableValues.forEach { value ->
                    DropdownMenuItem(text = { Text(text = value, color = styles.titleTextColor) }, onClick = { expanded = false; onValueSelected(value) })
                }
            }
        }
    }
}