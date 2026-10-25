package com.tradesim.app.core.designsystem

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.tradesim.app.core.data.settings.PriceColorConvention

private val CsShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private fun CsPalette.toMaterialColors(isDark: Boolean) = if (isDark) {
    darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primarySoft,
        onPrimaryContainer = onPrimary,
        secondary = up,
        onSecondary = onPrimary,
        tertiary = warning,
        background = background,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = textSecondary,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceElevated,
        surfaceContainerHighest = surfaceVariant,
        outline = borderStrong,
        outlineVariant = border,
        error = down,
        onError = onPrimary,
        scrim = scrim,
    )
} else {
    lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primarySoft,
        onPrimaryContainer = onPrimary,
        secondary = up,
        onSecondary = onPrimary,
        tertiary = warning,
        background = background,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = textSecondary,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceElevated,
        surfaceContainerHighest = surfaceVariant,
        outline = borderStrong,
        outlineVariant = border,
        error = down,
        onError = onPrimary,
        scrim = scrim,
    )
}

@Composable
fun TradeSimTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    priceColorConvention: PriceColorConvention = PriceColorConvention.GREEN_UP,
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) CsDarkPalette else CsLightPalette

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalCsPalette provides palette,
        LocalPriceColorConvention provides priceColorConvention,
    ) {
        MaterialTheme(
            colorScheme = palette.toMaterialColors(darkTheme),
            typography = CsTypography,
            shapes = CsShapes,
            content = content,
        )
    }
}