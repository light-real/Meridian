package com.meridian.app.feature.positions

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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meridian.app.core.designsystem.ChangeBadge
import com.meridian.app.core.designsystem.CoinAvatar
import com.meridian.app.core.designsystem.CsCard
import com.meridian.app.core.designsystem.CsColors
import com.meridian.app.core.designsystem.CsDivider
import com.meridian.app.core.designsystem.CsEmptyState
import com.meridian.app.core.designsystem.CsLoading
import com.meridian.app.core.designsystem.CsSegmented
import com.meridian.app.core.designsystem.CsStatRow
import com.meridian.app.core.designsystem.CsTopBar
import com.meridian.app.core.designsystem.LabelValue
import com.meridian.app.core.designsystem.SideBadge
import com.meridian.app.core.domain.money
import com.meridian.app.core.domain.price
import com.meridian.app.core.domain.quantity
import com.meridian.app.core.domain.signed
import com.meridian.app.core.model.MarkedAShareHolding
import com.meridian.app.core.model.MarkedPosition
import com.meridian.app.feature.ashare.ASharePositionsUiState
import com.meridian.app.feature.ashare.ASharePositionsViewModel
import com.meridian.app.ui.LocalAppContainer
import com.meridian.app.ui.appViewModelFactory
import java.math.BigDecimal

@Composable
fun PositionsScreen(
    onOpenTrade: (String) -> Unit,
    onOpenAShareTrade: (String) -> Unit,
) {
    val container = LocalAppContainer.current
    val viewModel: PositionsViewModel = viewModel(
        factory = appViewModelFactory {
            PositionsViewModel(
                accountRepository = container.accountRepository,
                marketRepository = container.marketRepository,
                marketDataHub = container.marketDataHub,
            )
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val aShareViewModel: ASharePositionsViewModel = viewModel(
        key = "ashare-positions",
        factory = appViewModelFactory {
            ASharePositionsViewModel(
                accountRepository = container.aShareAccountRepository,
                marketRepository = container.aShareMarketRepository,
            )
        },
    )
    val aShareState by aShareViewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var filter by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }

    Scaffold(
        containerColor = CsColors.Background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { CsTopBar(title = "持仓") },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                CsSegmented(
                    options = listOf("全部", "合约", "A 股"),
                    selectedIndex = filter,
                    onSelect = { filter = it },
                )
            }

            if (filter != 2) {
                item { PositionsSummary(state, title = "合约账户") }
                if (filter == 0) {
                    item { PositionsSectionTitle("合约仓位") }
                }
                when {
                    state.loading && state.positions.isEmpty() -> item { CsLoading() }
                    state.positions.isEmpty() -> item {
                        CsEmptyState(
                            title = "暂无合约仓位",
                            hint = "去「行情 → 合约」开仓",
                        )
                    }
                    else -> items(state.positions, key = { it.position.id }) { marked ->
                        PositionCard(
                            marked = marked,
                            priceScale = state.symbols[marked.position.symbol]?.pricePrecision ?: 2,
                            quantityScale = state.symbols[marked.position.symbol]?.quantityPrecision ?: 3,
                            onClick = { onOpenTrade(marked.position.symbol) },
                        )
                    }
                }
            }

            if (filter != 1) {
                item { AShareSummary(aShareState) }
                if (filter == 0) {
                    item { PositionsSectionTitle("A 股持仓") }
                }
                when {
                    aShareState.loading && aShareState.holdings.isEmpty() -> item { CsLoading() }
                    aShareState.holdings.isEmpty() -> item {
                        CsEmptyState(
                            title = "暂无 A 股持仓",
                            hint = "去「行情 → A 股」买入",
                        )
                    }
                    else -> items(
                        aShareState.holdings,
                        key = { "ashare-${it.holding.symbol}" },
                    ) { marked ->
                        ASharePositionCard(
                            marked = marked,
                            onClick = { onOpenAShareTrade(marked.holding.symbol) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PositionsSectionTitle(text: String) {
    Text(
        text = text,
        color = CsColors.TextMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

@Composable
private fun PositionsSummary(state: PositionsUiState, title: String = "合约账户") {
    val account = state.account
    val upnl = account.totalUnrealizedPnl(state.marks)
    val pnlColor = if (upnl.signum() >= 0) CsColors.Up else CsColors.Down
    CsCard {
        Text(
            text = title,
            color = CsColors.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(10.dp))
        CsStatRow("占用保证金", account.totalMargin().money(2) + " USDT")
        CsStatRow("未实现盈亏", upnl.signed(2) + " USDT", pnlColor)
        CsStatRow("总资产", account.totalAssets(state.marks).money(2) + " USDT")
    }
}

@Composable
private fun AShareSummary(state: ASharePositionsUiState) {
    val account = state.account
    val marketValue = state.holdings.fold(BigDecimal.ZERO) { acc, marked ->
        acc.add(marked.marketValue)
    }
    val totalAssets = account.cash.add(marketValue)
    val upnl = state.holdings.fold(BigDecimal.ZERO) { acc, marked ->
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
                text = if (state.sessionOpen) "交易中" else "休市",
                color = if (state.sessionOpen) CsColors.Up else CsColors.TextMuted,
                fontSize = 10.sp,
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        CsStatRow("总资产", totalAssets.money(2) + " CNY")
        CsStatRow("可用资金", account.cash.money(2) + " CNY")
        CsStatRow("持仓市值", marketValue.money(2) + " CNY")
        CsStatRow("浮动盈亏", upnl.signed(2) + " CNY", pnlColor)
    }
}

@Composable
private fun ASharePositionCard(
    marked: MarkedAShareHolding,
    onClick: () -> Unit,
) {
    val holding = marked.holding
    val pnlColor = if (marked.unrealizedPnl.signum() >= 0) CsColors.Up else CsColors.Down
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        color = CsColors.Surface,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CsColors.Border),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(CsColors.Primary.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = holding.name.firstOrNull()?.toString() ?: "A",
                        color = CsColors.Primary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(modifier = Modifier.padding(start = 10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = holding.name,
                            color = CsColors.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "A 股",
                            color = CsColors.Primary,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(CsColors.Primary.copy(alpha = 0.10f))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "${holding.symbol} · T+1",
                        color = CsColors.TextFaint,
                        fontSize = 10.sp,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = marked.unrealizedPnl.signed(2) + " CNY",
                        color = pnlColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = (if (marked.unrealizedPnlPercent.signum() >= 0) "+" else "") +
                            marked.unrealizedPnlPercent.setScale(2, java.math.RoundingMode.HALF_UP)
                                .toPlainString() + "%",
                        color = pnlColor,
                        fontSize = 11.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            CsDivider()
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LabelValue(
                    label = "持仓",
                    value = "${holding.quantity} 股",
                    modifier = Modifier.weight(1f),
                )
                LabelValue(
                    label = "可卖",
                    value = "${holding.availableQuantity} 股",
                    modifier = Modifier.weight(1f),
                )
                LabelValue(
                    label = "成本价",
                    value = holding.avgCost.price(2),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LabelValue(
                    label = "现价",
                    value = marked.price.price(2),
                    valueColor = if (marked.changePercent.signum() >= 0) {
                        CsColors.Up
                    } else {
                        CsColors.Down
                    },
                    modifier = Modifier.weight(1f),
                )
                LabelValue(
                    label = "市值",
                    value = marked.marketValue.money(2) + " CNY",
                    modifier = Modifier.weight(1f),
                )
                LabelValue(
                    label = "涨跌",
                    value = marked.changePercent.signed(2) + "%",
                    valueColor = if (marked.changePercent.signum() >= 0) {
                        CsColors.Up
                    } else {
                        CsColors.Down
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PositionCard(
    marked: MarkedPosition,
    priceScale: Int,
    quantityScale: Int,
    onClick: () -> Unit,
) {
    val position = marked.position
    val pnlColor = if (marked.unrealizedPnl.signum() >= 0) CsColors.Up else CsColors.Down
    val roi = marked.roi
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        color = CsColors.Surface,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CsColors.Border),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoinAvatar(baseAsset = position.baseAsset, size = 34.dp)
                Spacer(modifier = Modifier.padding(start = 10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = position.baseAsset,
                            color = CsColors.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.padding(start = 8.dp))
                        SideBadge(side = position.side, leverage = position.leverage)
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = position.symbol,
                        color = CsColors.TextFaint,
                        fontSize = 10.sp,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = marked.unrealizedPnl.signed(2) + " USDT",
                        color = pnlColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = (if (roi.signum() >= 0) "+" else "") + roi.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + "%",
                        color = pnlColor,
                        fontSize = 11.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            CsDivider()
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LabelValue(
                    label = "数量",
                    value = position.quantity.quantity(quantityScale) + " " + position.baseAsset,
                    modifier = Modifier.weight(1f),
                )
                LabelValue(
                    label = "开仓均价",
                    value = position.entryPrice.price(priceScale),
                    modifier = Modifier.weight(1f),
                )
                LabelValue(
                    label = "标记价格",
                    value = marked.markPrice.price(priceScale),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LabelValue(
                    label = "保证金",
                    value = position.margin.money(2) + " USDT",
                    modifier = Modifier.weight(1f),
                )
                LabelValue(
                    label = "强平价",
                    value = when {
                        marked.liquidationPrice.signum() <= 0 -> "--"
                        position.isCross -> "≈ " + marked.liquidationPrice.price(priceScale)
                        else -> marked.liquidationPrice.price(priceScale)
                    },
                    valueColor = CsColors.Down,
                    modifier = Modifier.weight(1f),
                )
                LabelValue(
                    label = "名义价值",
                    value = position.quantity.multiply(marked.markPrice).money(2) + " USDT",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
