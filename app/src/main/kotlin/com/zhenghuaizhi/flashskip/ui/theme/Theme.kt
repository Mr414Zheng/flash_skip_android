package com.zhenghuaizhi.flashskip.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF155EEF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEFF4FF),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF9FAFB),
    onSurface = Color(0xFF101828),
    onSurfaceVariant = Color(0xFF475467),
    outline = Color(0xFF98A2B3),
    outlineVariant = Color(0xFFEAECF0),
    error = Color(0xFFB42318),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF84ADFF),
    onPrimary = Color(0xFF002A6B),
    primaryContainer = Color(0xFF102A56),
    background = Color(0xFF0C111D),
    surface = Color(0xFF0C111D),
    surfaceContainerLow = Color(0xFF161B26),
    onSurface = Color(0xFFF2F4F7),
    onSurfaceVariant = Color(0xFFD0D5DD),
    outline = Color(0xFF667085),
    outlineVariant = Color(0xFF333741),
    error = Color(0xFFFDA29B),
)

private val FlashSkipTypography = androidx.compose.material3.Typography(
    displaySmall = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
)

object FlashSkipTokens {
    val Space4 = 4.dp
    val Space8 = 8.dp
    val Space12 = 12.dp
    val Space16 = 16.dp
    val Space20 = 20.dp
    val Space24 = 24.dp
    val Space28 = 28.dp
    val Space32 = 32.dp
    val Space40 = 40.dp
    val TouchMin = 48.dp
    val AppIcon = 56.dp
    val AppIconCompact = 48.dp
    val MaxContentWidth = 560.dp
}

val SuccessIndicatorColor = Color(0xFF12B76A)
val WarningColor = Color(0xFFB54708)
val TimelineNeutralColor = Color(0xFF667085)

@Composable
fun FlashSkipTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = FlashSkipTypography,
        content = content,
    )
}
