package com.cryptosim.app.feature.positions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cryptosim.app.core.designsystem.CoinAvatar
import com.cryptosim.app.core.designsystem.CsCard
import com.cryptosim.app.core.designsystem.CsColors
import com.cryptosim.app.core.designsystem.CsDivider
import com.cryptosim.app.core.designsystem.CsEmptyState
import com.cryptosim.app.core.designsystem.CsLoading
import com.cryptosim.app.core.designsystem.CsStatRow
import com.cryptosim.app.core.designsystem.CsTopBar
import com.cryptosim.app.core.designsystem.LabelValue
import com.cryptosim.app.core.designsystem.SideBadge
import com.cryptosim.app.core.domain.money
import com.cryptosim.app.core.domain.price
import com.cryptosim.app.core.domain.quantity
import com.cryptosim.app.core.domain.signed
import com.cryptosim.app.core.model.MarkedPosition
import com.cryptosim.app.ui.LocalAppContainer
import com.cryptosim.app.ui.appViewModelFactory
import java.math.BigDecimal

@Composable
fun PositionsScreen(onOpenTrade: (String) -> Unit) {
    val container = LocalAppContainer.current
    val viewModel: PositionsViewModel = viewModel(
        factory = appViewModelFactory {
            PositionsViewModel(
                accountRepository = container.accountRepository,
                marketRepository = container.marketRepository,
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
                PositionsSummary(state)
            }

            when {
                state.loading -> item { CsLoading() }
                state.positions.isEmpty() -> item {
                    CsEmptyState(
                        title = "暂无持仓",
                        hint = "去行情页开一笔模拟单",
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
    }
}

@Composable
private fun PositionsSummary(state: PositionsUiState) {
    val account = state.account
    val upnl = account.totalUnrealizedPnl(state.marks)
    val pnlColor = if (upnl.signum() >= 0) CsColors.Up else CsColors.Down
    CsCard {
        Text(
            text = "账户概览",
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
                    value = position.liquidationPrice.price(priceScale),
                    valueColor = CsColors.Down,
                    modifier = Modifier.weight(1f),
                )
                LabelValue(
                    label = "名义价值",
                    value = position.quantity.multiply(marked.markPrice).money(2) + " USDT",
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = "查看 / 平仓 ›",
                    color = CsColors.Primary,
                    fontSize = 10.sp,
                )
            }
        }
    }
}
