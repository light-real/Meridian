package com.tradesim.app.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 整套主题色板。界面上所有颜色都从这里取，业务代码里不直接写死 Color(0x...)，
 * 换亮色 / 暗色只需要换一个 palette 实例。
 */
@Immutable
data class CsPalette(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceVariant: Color,
    val primary: Color,
    val primarySoft: Color,
    val onPrimary: Color,
    val up: Color,
    val upSoft: Color,
    val down: Color,
    val downSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    val star: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val textFaint: Color,
    val border: Color,
    val borderStrong: Color,
    val grid: Color,
    val heroStart: Color,
    val heroEnd: Color,
    val scrim: Color,
)

val CsLightPalette = CsPalette(
    isDark = false,
    background = Color(0xFFF4F6F9),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEFF2F7),
    primary = Color(0xFF0095B6),
    primarySoft = Color(0xFF007A96),
    onPrimary = Color(0xFFFFFFFF),
    up = Color(0xFF0F8A4A),
    upSoft = Color(0x1A0F8A4A),
    down = Color(0xFFD92D20),
    downSoft = Color(0x1AD92D20),
    warning = Color(0xFFB45309),
    warningSoft = Color(0x1AB45309),
    star = Color(0xFFF0A020),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF475569),
    textMuted = Color(0xFF64748B),
    textFaint = Color(0xFF94A3B8),
    border = Color(0xFFE4E8EF),
    borderStrong = Color(0xFFCFD6E0),
    grid = Color(0xFFEDF0F5),
    heroStart = Color(0xFFFFFFFF),
    heroEnd = Color(0xFFEEF8FB),
    scrim = Color(0x52000000),
)

val CsDarkPalette = CsPalette(
    isDark = true,
    background = Color(0xFF0B0E14),
    surface = Color(0xFF141922),
    surfaceElevated = Color(0xFF1A212B),
    surfaceVariant = Color(0xFF1D242F),
    primary = Color(0xFF45C8E3),
    primarySoft = Color(0xFF0095B6),
    onPrimary = Color(0xFFFFFFFF),
    up = Color(0xFF30C48D),
    upSoft = Color(0x2630C48D),
    down = Color(0xFFFF6B72),
    downSoft = Color(0x26FF6B72),
    warning = Color(0xFFF0A33C),
    warningSoft = Color(0x26F0A33C),
    star = Color(0xFFFBBF24),
    textPrimary = Color(0xFFE9EEF6),
    textSecondary = Color(0xFFA3B0C2),
    textMuted = Color(0xFF7A8798),
    textFaint = Color(0xFF5B6675),
    border = Color(0x1FFFFFFF),
    borderStrong = Color(0x29FFFFFF),
    grid = Color(0x14FFFFFF),
    heroStart = Color(0xFF171E2A),
    heroEnd = Color(0xFF111721),
    scrim = Color(0x8A000000),
)

internal val LocalCsPalette = staticCompositionLocalOf { CsLightPalette }

internal val LocalPriceColorConvention =
    staticCompositionLocalOf { com.tradesim.app.core.data.settings.PriceColorConvention.GREEN_UP }

/**
 * 语义化颜色入口。用 @Composable getter 暴露，调用方写法保持 `CsColors.Xxx` 不变，
 * 但取到的是当前主题的值。
 */
object CsColors {
    val IsDark: Boolean
        @Composable @ReadOnlyComposable get() = LocalCsPalette.current.isDark

    val Palette: CsPalette
        @Composable @ReadOnlyComposable get() = LocalCsPalette.current

    val Background: Color
        @Composable @ReadOnlyComposable get() = LocalCsPalette.current.background

    val Surface: Color
        @Composable @ReadOnlyComposable get() = LocalCsPalette.current.surface

    val SurfaceElevated: Color
        @Composable @ReadOnlyComposable get() = LocalCsPalette.current.surfaceElevated

    val SurfaceVariant: Color
        @Composable @ReadOnlyComposable get() = LocalCsPalette.current.surfaceVariant

    val Primary: Color
        @Composable @ReadOnlyComposable get() = LocalCsPalette.current.primary

    val PrimarySoft: Color
        @Composable @ReadOnlyComposable get() = LocalCsPalette.current.primarySoft

    val OnPrimary: Color
        @Composable @ReadOnlyComposable get() = LocalCsPalette.current.onPrimary

    val Up: Color
        @Composable @ReadOnlyComposable
        get() = if (IsRedUp) LocalCsPalette.current.down else LocalCsPalette.current.up

    val UpSoft: Color
        @Composable @ReadOnlyComposable
        get() = if (IsRedUp) LocalCsPalette.current.downSoft else LocalCsPalette.current.upSoft

    val Down: Color
        @Composable @ReadOnlyComposable
        get() = if (IsRedUp) LocalCsPalette.current.up else LocalCsPalette.current.down

    val DownSoft: Color
        @Composable @ReadOnlyComposable
        get() = if (IsRedUp) LocalCsPalette.current.upSoft else LocalCsPalette.current.downSoft

    /** 是否使用「红涨绿跌」（A 股习惯）。 */
    val IsRedUp: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalPriceColorConvention.current ==
            com.tradesim.app.core.data.settings.PriceColorConvention.RED_UP

    /** 固定为绿色，不受涨跌约定影响（例如「做多」按钮）。 */
    val Green: Color
        @Composable @ReadOnlyComposable get() = LocalCsPalette.current.up

    /** 固定为红色，不受涨跌约定影响。 */
    val Red: Color
        @Composable @ReadOnlyComposable get() = LocalCsPalette.current.down

    val Warning: Color
        @Composable @ReadOnlyComposable get() = LocalCsPalette.current.warning

    val WarningSoft: Color
        @Composable @ReadOnlyComposable get() = LocalCsPalette.current.warningSoft

    val Star: Color
        @Composable @ReadOnlyComposable get() = LocalCsPalette.current.star

    val TextPrimary: Color
        @Composable @ReadOnlyComposable get() = LocalCsPalette.current.textPrimary

    val TextSecondary: Color
        @Composable @ReadOnlyComposable get() = LocalCsPalette.current.textSecondary

    val TextMuted: Color
        @Composable @ReadOnlyComposable get() = LocalCsPalette.current.textMuted

    val TextFaint: Color
        @Composable @ReadOnlyComposable get() = LocalCsPalette.current.textFaint

    val Border: Color
        @Composable @ReadOnlyComposable get() = LocalCsPalette.current.border

    val BorderStrong: Color
        @Composable @ReadOnlyComposable get() = LocalCsPalette.current.borderStrong

    val Grid: Color
        @Composable @ReadOnlyComposable get() = LocalCsPalette.current.grid

    val HeroStart: Color
        @Composable @ReadOnlyComposable get() = LocalCsPalette.current.heroStart

    val HeroEnd: Color
        @Composable @ReadOnlyComposable get() = LocalCsPalette.current.heroEnd

    val Scrim: Color
        @Composable @ReadOnlyComposable get() = LocalCsPalette.current.scrim

    /** 涨绿跌红：正数用 Up，负数用 Down。 */
    @Composable @ReadOnlyComposable
    fun ofChange(value: java.math.BigDecimal): Color =
        if (value.signum() >= 0) Up else Down
}