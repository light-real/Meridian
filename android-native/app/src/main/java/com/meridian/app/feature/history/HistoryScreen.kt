package com.meridian.app.feature.history

import androidx.compose.foundation.background
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meridian.app.core.designsystem.CsColors
import com.meridian.app.core.designsystem.CsEmptyState
import com.meridian.app.core.designsystem.CsLoading
import com.meridian.app.core.designsystem.CsSegmented
import com.meridian.app.core.designsystem.CsTopBar
import com.meridian.app.core.designsystem.LabelValue
import com.meridian.app.core.domain.formatDate
import com.meridian.app.core.domain.formatTime
import com.meridian.app.core.domain.money
import com.meridian.app.core.domain.price
import com.meridian.app.core.domain.quantity
import com.meridian.app.core.domain.signed
import com.meridian.app.core.model.AShareSide
import com.meridian.app.core.model.AShareTrade
import com.meridian.app.core.model.Side
import com.meridian.app.core.model.Trade
import com.meridian.app.core.model.TradeType
import com.meridian.app.core.model.TradeTypeFilter
import com.meridian.app.ui.LocalAppContainer
import com.meridian.app.ui.appViewModelFactory
import java.math.BigDecimal

@Composable
fun HistoryScreen(onBack: (() -> Unit)? = null) {
    val container = LocalAppContainer.current
    val viewModel: HistoryViewModel = viewModel(
        factory = appViewModelFactory {
            HistoryViewModel(
                accountRepository = container.accountRepository,
                aShareAccountRepository = container.aShareAccountRepository,
            )
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = CsColors.Background,
        topBar = { CsTopBar(title = "交易记录", onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                CsSegmented(
                    options = listOf("全部", "合约", "A 股"),
                    selectedIndex = when (state.marketFilter) {
                        HistoryMarketFilter.ALL -> 0
                        HistoryMarketFilter.CONTRACT -> 1
                        HistoryMarketFilter.A_SHARE -> 2
                    },
                    onSelect = { index ->
                        viewModel.setMarketFilter(
                            when (index) {
                                1 -> HistoryMarketFilter.CONTRACT
                                2 -> HistoryMarketFilter.A_SHARE
                                else -> HistoryMarketFilter.ALL
                            },
                        )
                    },
                )
            }

            if (state.marketFilter != HistoryMarketFilter.A_SHARE) {
                item {
                    CsSegmented(
                        options = listOf("全部", "开仓", "平仓", "资金费"),
                        selectedIndex = when (state.filter) {
                            TradeTypeFilter.ALL -> 0
                            TradeTypeFilter.OPEN -> 1
                            TradeTypeFilter.CLOSE -> 2
                            TradeTypeFilter.FUNDING -> 3
                        },
                        onSelect = { index ->
                            viewModel.setFilter(
                                when (index) {
                                    1 -> TradeTypeFilter.OPEN
                                    2 -> TradeTypeFilter.CLOSE
                                    3 -> TradeTypeFilter.FUNDING
                                    else -> TradeTypeFilter.ALL
                                },
                            )
                        },
                    )
                }
            }

            when {
                state.loading -> item { CsLoading() }
                state.filtered.isEmpty() -> item {
                    CsEmptyState(
                        title = "暂无交易记录",
                        hint = "完成第一笔交易后在此查看",
                    )
                }
                else -> {
                    state.filtered
                        .groupBy { formatDate(it.createdAt) }
                        .forEach { (date, entries) ->
                            item(key = "header-$date") {
                                Text(
                                    text = date,
                                    color = CsColors.TextMuted,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                                )
                            }
                            items(
                                entries,
                                key = { entry ->
                                    when (entry) {
                                        is HistoryEntry.Contract -> entry.trade.id
                                        is HistoryEntry.AShare -> entry.trade.id
                                    }
                                },
                            ) { entry ->
                                when (entry) {
                                    is HistoryEntry.Contract -> TradeCard(entry.trade)
                                    is HistoryEntry.AShare -> AShareTradeCard(entry.trade)
                                }
                            }
                        }
                }
            }
        }
    }
}

@Composable
private fun TradeCard(trade: Trade) {
    val typeLabel = when (trade.type) {
        TradeType.OPEN -> if (trade.side == Side.LONG) "开多" else "开空"
        TradeType.CLOSE -> if (trade.side == Side.LONG) "平多" else "平空"
        TradeType.LIQUIDATION -> if (trade.side == Side.LONG) "强平多" else "强平空"
        TradeType.FUNDING -> "资金费"
        TradeType.TAKE_PROFIT -> "止盈"
        TradeType.STOP_LOSS -> "止损"
    }
    val typeColor = when {
        trade.type == TradeType.LIQUIDATION -> CsColors.Warning
        trade.type == TradeType.FUNDING -> CsColors.TextSecondary
        trade.type == TradeType.TAKE_PROFIT -> CsColors.Up
        trade.type == TradeType.STOP_LOSS -> CsColors.Down
        trade.side == Side.LONG -> CsColors.Up
        else -> CsColors.Down
    }
    val amountText = if (trade.type == TradeType.OPEN) {
        "-" + trade.margin.money(2) + " USDT"
    } else {
        (trade.pnl ?: BigDecimal.ZERO).signed(2) + " USDT"
    }
    val amountColor = if (trade.type == TradeType.OPEN) {
        CsColors.TextSecondary
    } else if ((trade.pnl ?: BigDecimal.ZERO).signum() >= 0) {
        CsColors.Up
    } else {
        CsColors.Down
    }
    val priceScale = priceScaleFor(trade.price)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp)),
        color = CsColors.Surface,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CsColors.Border),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = typeLabel,
                    color = typeColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(typeColor.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Spacer(modifier = Modifier.padding(start = 8.dp))
                Text(
                    text = trade.baseAsset,
                    color = CsColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = " ${trade.leverage}x",
                    color = CsColors.TextFaint,
                    fontSize = 10.sp,
                )
                Spacer(modifier = Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = amountText,
                        color = amountColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = formatTime(trade.createdAt),
                        color = CsColors.TextFaint,
                        fontSize = 10.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LabelValue(
                    label = "数量",
                    value = trade.quantity.quantity(6) + " " + trade.baseAsset,
                    modifier = Modifier.weight(1f),
                )
                LabelValue(
                    label = "成交价",
                    value = trade.price.price(priceScale),
                    modifier = Modifier.weight(1f),
                )
                LabelValue(
                    label = if (trade.type == TradeType.OPEN) "保证金" else "手续费",
                    value = if (trade.type == TradeType.OPEN) {
                        trade.margin.money(2)
                    } else {
                        trade.fee.money(2)
                    },
                    modifier = Modifier.weight(1f),
                )
            }

        }
    }
}

@Composable
private fun AShareTradeCard(trade: AShareTrade) {
    val isBuy = trade.side == AShareSide.BUY
    val typeLabel = if (isBuy) "买入" else "卖出"
    val typeColor = if (isBuy) CsColors.Up else CsColors.Down
    val netAmount = if (isBuy) {
        trade.amount.add(trade.totalFee)
    } else {
        trade.amount.subtract(trade.totalFee)
    }
    val amountText = (if (isBuy) "-" else "+") + netAmount.money(2) + " CNY"
    val amountColor = if (isBuy) {
        CsColors.TextSecondary
    } else {
        CsColors.ofChange(trade.realizedPnl ?: BigDecimal.ZERO)
    }
    val pnlColor = CsColors.ofChange(trade.realizedPnl ?: BigDecimal.ZERO)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp)),
        color = CsColors.Surface,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CsColors.Border),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = typeLabel,
                    color = typeColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(typeColor.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Spacer(modifier = Modifier.padding(start = 8.dp))
                Text(
                    text = trade.name,
                    color = CsColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.padding(start = 6.dp))
                Text(
                    text = trade.symbol,
                    color = CsColors.TextFaint,
                    fontSize = 10.sp,
                )
                Spacer(modifier = Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = amountText,
                        color = amountColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = formatTime(trade.createdAt),
                        color = CsColors.TextFaint,
                        fontSize = 10.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LabelValue(
                    label = "数量",
                    value = "${trade.quantity} 股",
                    modifier = Modifier.weight(1f),
                )
                LabelValue(
                    label = "成交价",
                    value = trade.price.price(2),
                    modifier = Modifier.weight(1f),
                )
                LabelValue(
                    label = "手续费",
                    value = trade.totalFee.money(2),
                    modifier = Modifier.weight(1f),
                )
            }

            trade.realizedPnl?.let { realized ->
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("已实现盈亏", color = CsColors.TextMuted, fontSize = 11.sp)
                    Text(
                        text = realized.signed(2) + " CNY",
                        color = pnlColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

        }
    }
}

private fun priceScaleFor(price: BigDecimal): Int = when {
    price >= BigDecimal("1000") -> 2
    price >= BigDecimal("1") -> 4
    else -> 6
}
