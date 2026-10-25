package com.tradesim.app.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TradeSimColorScheme = darkColorScheme(
    primary = CsColors.Primary,
    onPrimary = Color.White,
    primaryContainer = CsColors.PrimarySoft,
    onPrimaryContainer = Color.White,
    secondary = CsColors.Up,
    onSecondary = Color.Black,
    background = CsColors.Background,
    onBackground = CsColors.TextPrimary,
    surface = CsColors.Surface,
    onSurface = CsColors.TextPrimary,
    surfaceVariant = CsColors.SurfaceVariant,
    onSurfaceVariant = CsColors.TextSecondary,
    outline = CsColors.Border,
    outlineVariant = CsColors.BorderStrong,
    error = CsColors.Down,
    onError = Color.White,
    tertiary = CsColors.Warning,
)

@Composable
fun TradeSimTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TradeSimColorScheme,
        typography = CsTypography,
        content = content,
    )
}
