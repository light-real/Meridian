package com.meridian.app.feature.market

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meridian.app.core.designsystem.ChangeBadge
import com.meridian.app.core.designsystem.CoinAvatar
import com.meridian.app.core.designsystem.CsChip
import com.meridian.app.core.designsystem.CsColors
import com.meridian.app.core.designsystem.CsEmptyState
import com.meridian.app.core.designsystem.CsErrorState
import com.meridian.app.core.designsystem.CsLoading
import com.meridian.app.core.designsystem.CsTextField
import com.meridian.app.core.designsystem.CsToast
import com.meridian.app.core.designsystem.CsTopBar
import com.meridian.app.core.designsystem.FlashPriceText
import com.meridian.app.core.domain.money
import com.meridian.app.core.domain.price
import com.meridian.app.core.domain.signed
import com.meridian.app.core.model.AShareStock
import com.meridian.app.feature.ashare.AShareMarketViewModel
import com.meridian.app.ui.LocalAppContainer
import com.meridian.app.ui.appViewModelFactory
import java.math.BigDecimal

@Composable
fun MarketScreen(
    onOpenTrade: (String) -> Unit,
    onOpenAShareTrade: (String) -> Unit,
) {
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
    val aShareViewModel: AShareMarketViewModel = viewModel(
        factory = appViewModelFactory {
            AShareMarketViewModel(
                repository = container.aShareMarketRepository,
                favoritesRepository = container.aShareFavoritesRepository,
            )
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val aShareState by aShareViewModel.uiState.collectAsStateWithLifecycle()
    var marketTab by rememberSaveable { mutableStateOf(0) }
    var aShareSearchVisible by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(marketTab) {
        if (marketTab == 1) {
            // 每次进 A 股都先落在自选
            aShareViewModel.setTab(0)
            aShareViewModel.loadIfNeeded()
        }
    }

    Scaffold(
        containerColor = CsColors.Background,
        snackbarHost = {
            CsToast(
                message = state.notice,
                onDismiss = viewModel::consumeNotice,
            )
        },
        topBar = {
            CsTopBar(
                title = "行情",
                actions = {
                    IconButton(
                        onClick = {
                            if (marketTab == 0) viewModel.refresh() else aShareViewModel.refresh()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "刷新",
                            tint = CsColors.TextSecondary,
                        )
                    }
                    IconButton(
                        onClick = {
                            if (marketTab == 0) {
                                viewModel.toggleSearch()
                            } else {
                                aShareSearchVisible = !aShareSearchVisible
                                if (!aShareSearchVisible) aShareViewModel.setQuery("")
                            }
                        },
                    ) {
                        val searchVisible = if (marketTab == 0) {
                            state.searchVisible
                        } else {
                            aShareSearchVisible
                        }
                        Icon(
                            imageVector = if (searchVisible) {
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
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MarketTab("合约", selected = marketTab == 0) { marketTab = 0 }
                    Spacer(modifier = Modifier.width(18.dp))
                    MarketTab("A 股", selected = marketTab == 1) { marketTab = 1 }
                    Spacer(modifier = Modifier.weight(1f))

                    if (marketTab == 0) {
                        CsChip(
                            text = "自选 ${state.favorites.size}",
                            selected = state.tab == 0,
                            onClick = { viewModel.switchTab(0) },
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        CsChip(
                            text = "全部 ${state.symbols.size}",
                            selected = state.tab == 1,
                            onClick = { viewModel.switchTab(1) },
                        )
                    } else if (aShareState.query.isBlank()) {
                        CsChip(
                            text = "自选 ${aShareState.favorites.size}",
                            selected = aShareState.tab == 0,
                            onClick = { aShareViewModel.setTab(0) },
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        CsChip(
                            text = "涨幅榜",
                            selected = aShareState.tab == 1,
                            onClick = { aShareViewModel.setTab(1) },
                        )
                    }
                }
            }

            if (marketTab == 0) {
                item { MarketHero(state) }

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
            } else {
                item {
                    AShareStatusRow(
                        sessionOpen = aShareState.sessionOpen,
                        query = aShareState.query,
                        searching = aShareState.searching,
                    )
                }

                if (aShareSearchVisible) {
                    item {
                        CsTextField(
                            value = aShareState.query,
                            onValueChange = aShareViewModel::setQuery,
                            placeholder = "搜索 A 股代码 / 名称，如 600519",
                            trailingContent = {
                                if (aShareState.query.isNotEmpty()) {
                                    IconButton(onClick = { aShareViewModel.setQuery("") }) {
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
                    aShareState.loading && aShareState.display.isEmpty() -> item { CsLoading() }
                    aShareState.error != null && aShareState.display.isEmpty() -> item {
                        CsErrorState(
                            message = aShareState.error.orEmpty(),
                            onRetry = aShareViewModel::refresh,
                        )
                    }
                    aShareState.display.isEmpty() -> item {
                        CsEmptyState(
                            title = if (aShareState.tab == 0 && aShareState.query.isBlank()) {
                                "暂无自选"
                            } else {
                                "暂无 A 股行情"
                            },
                            hint = if (aShareState.tab == 0 && aShareState.query.isBlank()) {
                                "点右侧星标添加自选"
                            } else if (aShareState.query.isBlank()) {
                                "检查网络后点右上角刷新"
                            } else {
                                "没有匹配的股票"
                            },
                        )
                    }
                    else -> items(aShareState.display, key = { it.symbol }) { stock ->
                        AShareStockRow(
                            stock = stock,
                            favorite = aShareState.favorites.contains(stock.symbol),
                            onClick = { onOpenAShareTrade(stock.symbol) },
                            onToggleFavorite = { aShareViewModel.toggleFavorite(stock.symbol) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarketTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            color = if (selected) CsColors.TextPrimary else CsColors.TextMuted,
            fontSize = if (selected) 19.sp else 17.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
        Spacer(modifier = Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .width(if (selected) 18.dp else 0.dp)
                .height(2.dp)
                .background(if (selected) CsColors.Primary else Color.Transparent),
        )
    }
}

@Composable
private fun AShareStatusRow(
    sessionOpen: Boolean,
    query: String,
    searching: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when {
                query.isNotBlank() && searching -> "A 股 · 搜索中…"
                query.isNotBlank() -> "A 股 · 搜索结果"
                else -> "A 股 · T+1"
            },
            color = CsColors.TextMuted,
            fontSize = 11.sp,
        )
        Spacer(modifier = Modifier.weight(1f))
        val color = if (sessionOpen) CsColors.Up else CsColors.TextMuted
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(RoundedCornerShape(50))
                .background(color),
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = if (sessionOpen) "交易中" else "休市",
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun AShareStockRow(
    stock: AShareStock,
    favorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(CsColors.Primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stock.name.firstOrNull()?.toString() ?: "A",
                color = CsColors.Primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stock.name,
                color = CsColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = stock.symbol,
                color = CsColors.TextFaint,
                fontSize = 10.sp,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (stock.price.signum() > 0) stock.price.price(2) else "--",
                color = CsColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(3.dp))
            ChangeBadge(percent = stock.changePercent)
        }
        Spacer(modifier = Modifier.width(2.dp))
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onToggleFavorite),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = if (favorite) "取消自选" else "加入自选",
                tint = if (favorite) CsColors.Star else CsColors.TextFaint,
                modifier = Modifier.size(18.dp),
            )
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
