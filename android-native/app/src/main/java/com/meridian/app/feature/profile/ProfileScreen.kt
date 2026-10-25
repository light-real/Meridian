package com.meridian.app.feature.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meridian.app.BuildConfig
import com.meridian.app.core.data.settings.PriceColorConvention
import com.meridian.app.core.designsystem.CsCard
import com.meridian.app.core.designsystem.CsColors
import com.meridian.app.core.designsystem.CsDivider
import com.meridian.app.core.designsystem.CsTopBar
import com.meridian.app.core.domain.formatTime
import com.meridian.app.core.domain.money
import com.meridian.app.core.domain.signed
import com.meridian.app.ui.LocalAppContainer
import com.meridian.app.ui.appViewModelFactory
import java.math.BigDecimal

@Composable
fun ProfileScreen() {
    val container = LocalAppContainer.current
    val viewModel: ProfileViewModel = viewModel(
        factory = appViewModelFactory {
            ProfileViewModel(
                authRepository = container.authRepository,
                accountRepository = container.accountRepository,
                favoritesRepository = container.favoritesRepository,
                settingsStore = container.settingsStore,
                marketDataHub = container.marketDataHub,
                aShareAccountRepository = container.aShareAccountRepository,
                aShareMarketRepository = container.aShareMarketRepository,
                tradeSyncRepository = container.tradeSyncRepository,
            )
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colorConvention by container.settingsStore.colorConvention.collectAsStateWithLifecycle()
    val monitorEnabled by container.settingsStore.monitorEnabled.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showResetDialog by remember { mutableStateOf(false) }
    var showResetAShareDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showColorDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        containerColor = CsColors.Background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { CsTopBar(title = "我的") },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            ProfileContractCard(state = state)

            ProfileAShareCard(state = state)

            ProfileSection(title = "数据同步") {
                SettingsGroup {
                    SettingRow(
                        icon = Icons.Outlined.CloudUpload,
                        title = "备份到服务器",
                        subtitle = if (state.lastSyncAt > 0) {
                            "上次同步 " + formatTime(state.lastSyncAt)
                        } else {
                            "还没有同步过"
                        },
                        value = if (state.syncing) "同步中" else "立即同步",
                        valueColor = CsColors.Primary,
                        iconTint = CsColors.Primary,
                        iconBackground = CsColors.PrimarySoft,
                        onClick = { viewModel.syncToServer() },
                    )
                }
            }

            ProfileSection(title = "偏好设置") {
                SettingsGroup {
                    SettingRow(
                        icon = Icons.Outlined.Palette,
                        title = "涨跌颜色",
                        subtitle = "当前：" + if (colorConvention == PriceColorConvention.RED_UP) {
                            "红涨绿跌"
                        } else {
                            "绿涨红跌"
                        },
                        iconTint = CsColors.Warning,
                        iconBackground = CsColors.WarningSoft,
                        onClick = { showColorDialog = true },
                    )
                    CsDivider(modifier = Modifier.padding(start = 62.dp))
                    SettingRow(
                        icon = Icons.Outlined.NotificationsActive,
                        title = "后台监控",
                        subtitle = "有持仓或挂单时检查强平、止盈止损",
                        value = if (monitorEnabled) "已开启" else "已关闭",
                        valueColor = if (monitorEnabled) CsColors.Up else CsColors.TextMuted,
                        iconTint = if (monitorEnabled) CsColors.Up else CsColors.TextMuted,
                        iconBackground = if (monitorEnabled) {
                            CsColors.UpSoft
                        } else {
                            CsColors.SurfaceVariant
                        },
                        onClick = {
                            container.settingsStore.setMonitorEnabled(!monitorEnabled)
                        },
                    )
                }
            }

            ProfileSection(title = "其他") {
                SettingsGroup {
                    SettingRow(
                        icon = Icons.Outlined.Refresh,
                        title = "重置合约账户",
                        subtitle = "清空合约持仓和记录，恢复 10,000 USDT",
                        danger = true,
                        onClick = { showResetDialog = true },
                    )
                    CsDivider(modifier = Modifier.padding(start = 62.dp))
                    SettingRow(
                        icon = Icons.Outlined.Refresh,
                        title = "重置 A 股账户",
                        subtitle = "清空 A 股持仓和记录，恢复 100,000 CNY",
                        danger = true,
                        onClick = { showResetAShareDialog = true },
                    )
                    CsDivider(modifier = Modifier.padding(start = 62.dp))
                    SettingRow(
                        icon = Icons.Outlined.Info,
                        title = "关于 Meridian",
                        subtitle = "版本 ${BuildConfig.VERSION_NAME}",
                        iconTint = CsColors.TextSecondary,
                        iconBackground = CsColors.SurfaceVariant,
                        onClick = { showAboutDialog = true },
                    )
                }
            }

            Text(
                text = "Meridian · v${BuildConfig.VERSION_NAME}",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = CsColors.TextFaint,
                fontSize = 10.sp,
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
    }

    if (showColorDialog) {
        AlertDialog(
            onDismissRequest = { showColorDialog = false },
            shape = RoundedCornerShape(20.dp),
            title = { Text("涨跌颜色") },
            text = {
                Column {
                    Text(
                        text = "数字资产和美股习惯绿涨红跌，A 股习惯红涨绿跌。选一个你看得顺眼的。",
                        color = CsColors.TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    PriceColorOption(
                        title = "绿涨红跌",
                        hint = "加密货币 / 美股习惯",
                        upColor = CsColors.Green,
                        downColor = CsColors.Red,
                        selected = colorConvention == PriceColorConvention.GREEN_UP,
                        onClick = {
                            container.settingsStore.setColorConvention(PriceColorConvention.GREEN_UP)
                            showColorDialog = false
                        },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    PriceColorOption(
                        title = "红涨绿跌",
                        hint = "A 股习惯",
                        upColor = CsColors.Red,
                        downColor = CsColors.Green,
                        selected = colorConvention == PriceColorConvention.RED_UP,
                        onClick = {
                            container.settingsStore.setColorConvention(PriceColorConvention.RED_UP)
                            showColorDialog = false
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showColorDialog = false }) {
                    Text("关闭", color = CsColors.TextMuted)
                }
            },
            containerColor = CsColors.SurfaceElevated,
            titleContentColor = CsColors.TextPrimary,
            textContentColor = CsColors.TextSecondary,
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            shape = RoundedCornerShape(20.dp),
            title = { Text("重置合约账户") },
            text = {
                Text("将清空所有合约持仓和交易记录，余额恢复为 10,000 USDT。此操作不可撤销。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        viewModel.resetAccount()
                    },
                ) {
                    Text("确认重置", color = CsColors.Down)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("取消", color = CsColors.TextMuted)
                }
            },
            containerColor = CsColors.SurfaceElevated,
            titleContentColor = CsColors.TextPrimary,
            textContentColor = CsColors.TextSecondary,
        )
    }

    if (showResetAShareDialog) {
        AlertDialog(
            onDismissRequest = { showResetAShareDialog = false },
            shape = RoundedCornerShape(20.dp),
            title = { Text("重置 A 股账户") },
            text = {
                Text("将清空所有 A 股持仓和交易记录，余额恢复为 100,000 CNY。此操作不可撤销。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetAShareDialog = false
                        viewModel.resetAShareAccount()
                    },
                ) {
                    Text("确认重置", color = CsColors.Down)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetAShareDialog = false }) {
                    Text("取消", color = CsColors.TextMuted)
                }
            },
            containerColor = CsColors.SurfaceElevated,
            titleContentColor = CsColors.TextPrimary,
            textContentColor = CsColors.TextSecondary,
        )
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            shape = RoundedCornerShape(20.dp),
            title = { Text("Meridian") },
            text = {
                Column {
                    Text("版本 ${BuildConfig.VERSION_NAME}")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("USDT 永续合约 + A 股现货交易 App，支持做多/做空、杠杆、强平、止盈止损、A 股 T+1。所有数据仅保存在本机。")
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("知道了", color = CsColors.Primary)
                }
            },
            containerColor = CsColors.SurfaceElevated,
            titleContentColor = CsColors.TextPrimary,
            textContentColor = CsColors.TextSecondary,
        )
    }
}

@Composable
private fun ProfileContractCard(state: ProfileUiState) {
    val account = state.account
    val totalAssets = account.totalAssets(state.marks)
    val totalPnl = account.totalPnl(state.marks)
    val pnlColor = if (totalPnl.signum() >= 0) CsColors.Up else CsColors.Down
    val pnlSoft = if (totalPnl.signum() >= 0) CsColors.UpSoft else CsColors.DownSoft

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = CsColors.Surface,
        border = BorderStroke(1.dp, CsColors.Border),
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        colors = listOf(CsColors.HeroStart, CsColors.HeroEnd),
                    ),
                )
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "合约账户",
                    color = CsColors.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (state.loading) {
                    CircularProgressIndicator(
                        color = CsColors.Primary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Text(
                        text = "USDT 永续",
                        color = CsColors.TextMuted,
                        fontSize = 10.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "总资产 (USDT)",
                color = CsColors.TextMuted,
                fontSize = 11.sp,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = totalAssets.money(2),
                color = CsColors.TextPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.6).sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(pnlSoft)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = totalPnl.signed(2) + " USDT",
                        color = pnlColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(modifier = Modifier.width(7.dp))
                Text(
                    text = "累计盈亏",
                    color = CsColors.TextMuted,
                    fontSize = 11.sp,
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                HeroMetric(
                    label = "持仓",
                    value = "${state.positions.size} 个",
                    modifier = Modifier.weight(1f),
                )
                HeroDivider()
                HeroMetric(
                    label = "占用保证金",
                    value = account.totalMargin().money(2),
                    modifier = Modifier.weight(1f),
                )
                HeroDivider()
                HeroMetric(
                    label = "可用余额",
                    value = account.cash.money(2),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ProfileAShareCard(state: ProfileUiState) {
    val account = state.aShareAccount
    val marketValue = state.aShareHoldings.fold(BigDecimal.ZERO) { acc, marked ->
        acc.add(marked.marketValue)
    }
    val totalAssets = account.cash.add(marketValue)
    val upnl = state.aShareHoldings.fold(BigDecimal.ZERO) { acc, marked ->
        acc.add(marked.unrealizedPnl)
    }
    val pnlColor = if (upnl.signum() >= 0) CsColors.Up else CsColors.Down

    CsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "A 股账户",
                color = CsColors.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "T+1 · CNY",
                color = CsColors.TextMuted,
                fontSize = 10.sp,
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = totalAssets.money(2),
                color = CsColors.TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "CNY",
                color = CsColors.TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "浮动盈亏 " + upnl.signed(2) + " CNY",
            color = pnlColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeroMetric(
                label = "可用资金",
                value = account.cash.money(2),
                modifier = Modifier.weight(1f),
            )
            HeroDivider()
            HeroMetric(
                label = "持仓市值",
                value = marketValue.money(2),
                modifier = Modifier.weight(1f),
            )
            HeroDivider()
            HeroMetric(
                label = "持仓",
                value = "${state.aShareHoldings.size} 只",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun HeroMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            color = CsColors.TextMuted,
            fontSize = 10.sp,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = value,
            color = CsColors.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HeroDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(28.dp)
            .background(CsColors.Border),
    )
}

@Composable
private fun ProfileSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            color = CsColors.TextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp),
        )
        content()
    }
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = CsColors.Surface,
        border = BorderStroke(1.dp, CsColors.Border),
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    valueColor: Color = CsColors.TextMuted,
    danger: Boolean = false,
    iconTint: Color? = null,
    iconBackground: Color? = null,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    val resolvedTint = iconTint ?: if (danger) CsColors.Down else CsColors.Primary
    val resolvedBackground = iconBackground ?: if (danger) {
        CsColors.DownSoft
    } else {
        CsColors.Primary.copy(alpha = 0.10f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(resolvedBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = resolvedTint,
                modifier = Modifier.size(18.dp),
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (danger) CsColors.Down else CsColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = CsColors.TextMuted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (loading) {
            CircularProgressIndicator(
                color = CsColors.Primary,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
            )
        } else {
            if (value != null) {
                Text(
                    text = value,
                    color = valueColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.width(5.dp))
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = CsColors.TextFaint,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun PriceColorOption(
    title: String,
    hint: String,
    upColor: Color,
    downColor: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) CsColors.Primary.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = CsColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = hint, color = CsColors.TextMuted, fontSize = 11.sp)
        }
        Text(
            text = "+3.24%",
            color = upColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "-1.08%",
            color = downColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.width(10.dp))
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = CsColors.Primary,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Spacer(modifier = Modifier.size(18.dp))
        }
    }
}


