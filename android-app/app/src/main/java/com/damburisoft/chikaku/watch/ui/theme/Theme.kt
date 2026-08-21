package com.damburisoft.chikaku.watch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Primary = Color(0xFF1B5E8C)
private val PrimaryDark = Color(0xFF8CC5E8)

private val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    secondary = Color(0xFF2E7D32),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = Color(0xFF00344F),
    secondary = Color(0xFF9CD49F),
)

/**
 * 高齢者が使うことを前提に、既定より一段大きい文字サイズにしている。
 */
private val LargeTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontSize = 30.sp, fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontSize = 20.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 20.sp, lineHeight = 32.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 18.sp, lineHeight = 28.sp),
        labelLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    )
}

@Composable
fun ChikakuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = LargeTypography,
        content = content,
    )
}
