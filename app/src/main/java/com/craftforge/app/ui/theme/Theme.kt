package com.craftforge.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat

private val DarkScheme = darkColorScheme(

    primary = AppColors.BluePrimaryDark,
    onPrimary = AppColors.BackgroundDark,

    secondary = AppColors.IndigoSecondaryDark,
    onSecondary = AppColors.BackgroundDark,

    tertiary = AppColors.EmeraldAccentDark,

    background = AppColors.BackgroundDark,
    onBackground = AppColors.TextPrimaryDark,

    surface = AppColors.SurfaceDark,
    onSurface = AppColors.TextPrimaryDark,

    surfaceVariant = AppColors.SurfaceVariantDark,

    outlineVariant = AppColors.OutlineDark
)

private val LightScheme = lightColorScheme(

    primary = AppColors.BluePrimary,
    onPrimary = AppColors.White,

    secondary = AppColors.IndigoSecondary,
    onSecondary = AppColors.White,

    tertiary = AppColors.EmeraldAccent,

    background = AppColors.BackgroundLight,
    onBackground = AppColors.TextPrimaryLight,

    surface = AppColors.SurfaceLight,
    onSurface = AppColors.TextPrimaryLight,

    surfaceVariant = AppColors.SurfaceVariantLight,

    outlineVariant = AppColors.OutlineLight
)

@Composable
fun DeviceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {

    val scheme = if (darkTheme) DarkScheme else LightScheme

    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {

            val window = (view.context as Activity).window

            window.statusBarColor = scheme.surface.toArgb()
            window.navigationBarColor = scheme.background.toArgb()

            WindowCompat
                .getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = AppTypography,
        content = content
    )
}
