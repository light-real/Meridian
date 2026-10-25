package com.tradesim.app.feature.market

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tradesim.app.core.designsystem.ChangeBadge
import com.tradesim.app.core.designsystem.CoinAvatar
import com.tradesim.app.core.designsystem.CsColors
import com.tradesim.app.core.designsystem.CsEmptyState
import com.tradesim.app.core.designsystem.CsErrorState
import com.tradesim.app.core.designsystem.CsLoading
import com.tradesim.app.core.designsystem.CsSegmented
import com.tradesim.app.core.designsystem.CsTextField
import com.tradesim.app.core.designsystem.CsTopBar
import com.tradesim.app.core.designsystem.FlashPriceText
import com.tradesim.app.core.domain.money
import com.tradesim.app.core.domain.price
import com.tradesim.app.core.domain.signed
import com.tradesim.app.ui.LocalAppContainer
import com.tradesim.app.ui.appViewModelFactory
import java.math.BigDecimal

@Composable
fun MarketScreen(onOpenTrade: (String) -> Unit) {
    val container = LocalAppContainer.current
    val viewModel: MarketViewModel = viewModel(
        factory = appViewModelFactory {
            MarketViewModel(
                marketRepository = container.marketRepository,
                accountRepository = container.accountRepository,
                favoritesRepository = container.favoritesRepository,
                marketDataHub = container.marketDataHub,
            )
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }

    Scaffold(
        containerColor = CsColors.Background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CsTopBar(
                title = "行情",
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "刷新",
                            tint = CsColors.TextSecondary,
                        )
                    }
                    IconButton(onClick = viewModel::toggleSearch) {
                        Icon(
                            imageVector = if (state.searchVisible) {
                                Icons.Outlined.Close
                            } else {
                                Icons.Outlined.Search
                            },
                            contentDescription = "搜索",
                            tint = CsColors.TextSecondary,
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { MarketHero(state) }

            item {
                CsSegmented(
                    options = listOf(
                        "自选 (${state.favorites.size})",
                        "全部 (${state.symbols.size})",
                    ),
                    selectedIndex = state.tab,
                    onSelect = viewModel::switchTab,
                )
            }

            if (state.searchVisible) {
                item {
                    CsTextField(
                        value = state.query,
                        onValueChange = viewModel::onQueryChange,
                        placeholder = "搜索币种，如 BTC",
                        trailingContent = {
                            if (state.query.isNotEmpty()) {
                                IconButton(onClick = { viewModel.onQueryChange("") }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = "清空",
                                        tint = CsColors.TextMuted,
                                    )
                                }
                            }
                        },
                    )
                }
            }

            when {
                state.loading && state.display.isEmpty() -> item { CsLoading() }
                state.error != null && state.display.isEmpty() -> item {
                    CsErrorState(
                        message = state.error.orEmpty(),
                        onRetry = viewModel::refresh,
                    )
                }
                state.display.isEmpty() -> item {
                    CsEmptyState(
                        title = if (state.tab == 0) "暂无自选" else "暂无行情",
                        hint = if (state.tab == 0) {
                            "切换到「全部」，点击右侧星标添加自选"
                        } else {
                            null
                        },
                    )
                }
                else -> items(state.display, key = { it.info.symbol }) { row ->
                    MarketCoinRow(
                        row = row,
                        onClick = { onOpenTrade(row.info.symbol) },
                        onToggleFavorite = { viewModel.toggleFavorite(row.info.symbol) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MarketHero(state: MarketUiState) {
    val totalAssets = state.account.totalAssets(state.marks)
    // 这里只统计「当前持仓的浮动盈亏」，不含已实现盈亏、手续费和资金费。
    // 累计盈亏在「我的」页单独展示。
    val pnl = state.account.totalUnrealizedPnl(state.marks)
    val pnlColor = if (pnl.signum() >= 0) CsColors.Up else CsColors.Down

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = CsColors.Surface,
        border = BorderStroke(1.dp, CsColors.Border),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(CsColors.HeroStart, CsColors.HeroEnd),
                    ),
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "总资产 (USDT)",
                    color = CsColors.TextMuted,
                    fontSize = 11.sp,
                )
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = totalAssets.money(2),
                    color = CsColors.TextPrimary,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "未实现盈亏",
                    color = CsColors.TextMuted,
                    fontSize = 11.sp,
                )
                Spacer(modifier = Modifier.height(7.dp))
                Text(
                    text = pnl.signed(2),
                    color = pnlColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun MarketCoinRow(
    row: MarketRow,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val ticker = row.ticker
    val change = ticker?.priceChangePercent ?: BigDecimal.ZERO
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoinAvatar(baseAsset = row.info.baseAsset)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.info.baseAsset,
                color = CsColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = row.info.symbol,
                color = CsColors.TextFaint,
                fontSize = 10.sp,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            FlashPriceText(
                text = ticker?.lastPrice?.price(row.info.pricePrecision) ?: "--",
                fontSize = 14.sp,
                flashUp = change.signum() >= 0,
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (ticker != null) {
                ChangeBadge(percent = change)
            } else {
                Text(text = "--", color = CsColors.TextFaint, fontSize = 11.sp)
            }
        }
        IconButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggleFavorite()
            },
        ) {
            Icon(
                imageVector = if (row.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = if (row.favorite) "取消自选" else "添加自选",
                tint = if (row.favorite) CsColors.Star else CsColors.TextFaint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
