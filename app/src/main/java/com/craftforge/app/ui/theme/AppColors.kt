package com.craftforge.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Єдине джерело всіх кольорів додатку
 * НЕ використовувати Color(...) в UI напряму
 */
object AppColors {

    // Brand (Матові, приглушені відтінки за стандартами Material 3)
    val BluePrimary = Color(0xFF415F91)       // Матовий сталевий синій
    val BluePrimaryDark = Color(0xFFAAC7FF)   // М'який пастельний синій для темної теми

    val IndigoSecondary = Color(0xFF535F70)   // Приглушений індиго/графіт
    val IndigoSecondaryDark = Color(0xFFBBC7DB)// Світлий матовий графіт

    val EmeraldAccent = Color(0xFF336B58)     // Матовий смарагдовий/хвойний
    val EmeraldAccentDark = Color(0xFF90D1B6) // М'який м'ятний для акцентів у темній темі

    // Background Dark (Глибокі матові сіро-сині тони, ніякого чистого чорного)
    val BackgroundDark = Color(0xFF111318)
    val SurfaceDark = Color(0xFF1E2025)
    val SurfaceVariantDark = Color(0xFF2E3036)

    // Background Light (М'які кремові/паперові відтінки, без "сліпучого" білого)
    val BackgroundLight = Color(0xFFF9F9FF)
    val SurfaceLight = Color(0xFFF1F4F9)
    val SurfaceVariantLight = Color(0xFFE2E2E9)

    // Text Dark (Знижений контраст для комфорту очей)
    val TextPrimaryDark = Color(0xFFE2E2E9)   // М'який білий (off-white)
    val TextSecondaryDark = Color(0xFFC4C6D0) // Світло-сірий матовий

    // Text Light
    val TextPrimaryLight = Color(0xFF191C20)  // М'який чорний (off-black)
    val TextSecondaryLight = Color(0xFF44474E) // Глибокий сірий

    // Utility (Для рамок, роздільників та ліній)
    val OutlineDark = Color(0xFF44474E)
    val OutlineLight = Color(0xFF74777F)

    // Базові константи (для рідкісних випадків абсолютної контрастності)
    val White = Color.White
    val Black = Color.Black
}