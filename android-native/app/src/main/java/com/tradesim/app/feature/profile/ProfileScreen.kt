package com.tradesim.app.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tradesim.app.BuildConfig
import com.tradesim.app.core.data.repository.SyncState
import com.tradesim.app.core.data.settings.PriceColorConvention
import com.tradesim.app.core.designsystem.CsCard
import com.tradesim.app.core.designsystem.CsColors
import com.tradesim.app.core.designsystem.CsDivider
import com.tradesim.app.core.designsystem.CsPrimaryButton
import com.tradesim.app.core.designsystem.CsStatRow
import com.tradesim.app.core.designsystem.CsTextButton
import com.tradesim.app.core.designsystem.CsTopBar
import com.tradesim.app.core.domain.money
import com.tradesim.app.core.domain.signed
import com.tradesim.app.ui.LocalAppContainer
import com.tradesim.app.ui.appViewModelFactory

@Composable
fun ProfileScreen(
    onOpenAuth: () -> Unit,
    onLoggedOut: () -> Unit,
) {
    val container = LocalAppContainer.current
    val viewModel: ProfileViewModel = viewModel(
        factory = appViewModelFactory {
            ProfileViewModel(
                authRepository = container.authRepository,
                accountRepository = container.accountRepository,
                favoritesRepository = container.favoritesRepository,
                settingsStore = container.settingsStore,
                marketDataHub = container.marketDataHub,
            )
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colorConvention by container.settingsStore.colorConvention.collectAsStateWithLifecycle()
    val monitorEnabled by container.settingsStore.monitorEnabled.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showResetDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showColorDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    LaunchedEffect(state.loggedOut) {
        if (state.loggedOut) {
            viewModel.consumeLoggedOut()
            onLoggedOut()
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.session != null) {
                AccountCard(state)
            } else {
                GuestCard(onOpenAuth = onOpenAuth)
            }

            StatsCard(state)

            CsCard {
                if (state.session != null) {
                    ProfileMenuRow(
                        icon = Icons.Outlined.Sync,
                        label = "立即同步",
                        value = when (state.syncState) {
                            SyncState.SYNCING -> "同步中…"
                            SyncState.SYNCED -> "已同步"
                            SyncState.ERROR -> "同步失败"
                            SyncState.IDLE -> "未同步"
                        },
                        onClick = viewModel::syncNow,
                    )
                    CsDivider()
                }
                ProfileMenuRow(
                    icon = Icons.Outlined.Palette,
                    label = "涨跌颜色",
                    value = if (colorConvention == PriceColorConvention.RED_UP) "红涨绿跌" else "绿涨红跌",
                    onClick = { showColorDialog = true },
                )
                CsDivider()
                ProfileMenuRow(
                    icon = Icons.Outlined.NotificationsActive,
                    label = "后台监控",
                    value = if (monitorEnabled) "已开启" else "已关闭",
                    onClick = {
                        container.settingsStore.setMonitorEnabled(!monitorEnabled)
                    },
                )
                CsDivider()
                ProfileMenuRow(
                    icon = Icons.Outlined.Refresh,
                    label = "重置账户",
                    value = "恢复 10,000 USDT",
                    onClick = { showResetDialog = true },
                )
                if (state.session != null) {
                    CsDivider()
                    ProfileMenuRow(
                        icon = Icons.Outlined.Logout,
                        label = "退出登录",
                        value = null,
                        danger = true,
                        onClick = { showLogoutDialog = true },
                    )
                }
                CsDivider()
                ProfileMenuRow(
                    icon = Icons.Outlined.Info,
                    label = "关于 TradeSim",
                    value = BuildConfig.VERSION_NAME,
                    onClick = { showAboutDialog = true },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showColorDialog) {
        AlertDialog(
            onDismissRequest = { showColorDialog = false },
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
            title = { Text("重置账户") },
            text = {
                Text("将清空所有持仓和交易记录，余额恢复为 10,000 USDT。此操作不可撤销。")
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

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("退出登录") },
            text = { Text("退出后本机数据将被清空，云端数据保留，下次登录可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutDialog = false
                        viewModel.logout()
                    },
                ) {
                    Text("退出", color = CsColors.Down)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
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
            title = { Text("TradeSim") },
            text = {
                Column {
                    Text("版本 ${BuildConfig.VERSION_NAME}")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("USDT 永续合约交易 App，支持做多/做空、杠杆、强平、K 线与云端同步。")
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
private fun AccountCard(state: ProfileUiState) {
    CsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(CsColors.Primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = (state.session?.email?.firstOrNull() ?: 'U').uppercase(),
                    color = CsColors.Primary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.padding(start = 12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.session?.email ?: "已登录",
                    color = CsColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.CloudDone,
                        contentDescription = null,
                        tint = CsColors.Up,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(modifier = Modifier.padding(start = 4.dp))
                    Text(
                        text = when (state.syncState) {
                            SyncState.SYNCING -> "同步中…"
                            SyncState.SYNCED -> "已同步到云端"
                            SyncState.ERROR -> "同步失败，可手动重试"
                            SyncState.IDLE -> "等待同步"
                        },
                        color = CsColors.TextMuted,
                        fontSize = 11.sp,
                    )
                }
            }
            if (state.loading) {
                CircularProgressIndicator(
                    color = CsColors.Primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun GuestCard(onOpenAuth: () -> Unit) {
    CsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(CsColors.TextMuted.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.PersonOutline,
                    contentDescription = null,
                    tint = CsColors.TextSecondary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(modifier = Modifier.padding(start = 12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "游客模式",
                    color = CsColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "数据仅保存在本机，登录后可同步到云端",
                    color = CsColors.TextMuted,
                    fontSize = 11.sp,
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        CsPrimaryButton(text = "登录 / 注册", onClick = onOpenAuth)
    }
}

@Composable
private fun StatsCard(state: ProfileUiState) {
    val totalAssets = state.account.totalAssets(state.marks)
    val totalPnl = state.account.totalPnl(state.marks)
    val pnlColor = if (totalPnl.signum() >= 0) CsColors.Up else CsColors.Down
    CsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("总资产", color = CsColors.TextMuted, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = totalAssets.money(2) + " USDT",
                    color = CsColors.TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("累计盈亏", color = CsColors.TextMuted, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = totalPnl.signed(2) + " USDT",
                    color = pnlColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        CsStatRow("持仓数量", "${state.positions.size} 个")
        CsStatRow("占用保证金", state.account.totalMargin().money(2) + " USDT")
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

@Composable
private fun ProfileMenuRow(
    icon: ImageVector,
    label: String,
    value: String?,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (danger) CsColors.Down else CsColors.TextSecondary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.padding(start = 12.dp))
        Text(
            text = label,
            color = if (danger) CsColors.Down else CsColors.TextPrimary,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(
                text = value,
                color = CsColors.TextMuted,
                fontSize = 11.sp,
            )
            Spacer(modifier = Modifier.padding(start = 6.dp))
        }
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = CsColors.TextFaint,
            modifier = Modifier.size(16.dp),
        )
    }
}
