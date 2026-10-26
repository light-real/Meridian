package com.meridian.app.feature.ashare

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meridian.app.core.designsystem.ChangeBadge
import com.meridian.app.core.designsystem.CsCard
import com.meridian.app.core.designsystem.CsColors
import com.meridian.app.core.designsystem.CsDivider
import com.meridian.app.core.designsystem.CsErrorState
import com.meridian.app.core.designsystem.CsFieldChip
import com.meridian.app.core.designsystem.CsLoading
import com.meridian.app.core.designsystem.CsNumericTextStyle
import com.meridian.app.core.designsystem.CsPrimaryButton
import com.meridian.app.core.designsystem.CsSegmented
import com.meridian.app.core.designsystem.CsStatRow
import com.meridian.app.core.designsystem.CsToast
import com.meridian.app.core.designsystem.CsTopBar
import com.meridian.app.core.domain.money
import com.meridian.app.core.domain.price
import com.meridian.app.core.domain.signed
import com.meridian.app.core.model.AShareSide
import com.meridian.app.core.model.AShareStock
import com.meridian.app.core.model.AShareTradeError
import com.meridian.app.ui.LocalAppContainer
import com.meridian.app.ui.appViewModelFactory
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
fun AShareTradeScreen(
    symbol: String,
    onBack: () -> Unit,
) {
    val container = LocalAppContainer.current
    val viewModel: AShareTradeViewModel = viewModel(
        key = "ashare-trade-$symbol",
        factory = appViewModelFactory {
            AShareTradeViewModel(
                symbol = symbol,
                marketRepository = container.aShareMarketRepository,
                accountRepository = container.aShareAccountRepository,
                favoritesRepository = container.aShareFavoritesRepository,
            )
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()


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
                title = state.stock?.name ?: symbol,
                subtitle = if (state.stock != null) symbol else null,
                onBack = onBack,
                actions = {
                    IconButton(onClick = viewModel::toggleFavorite) {
                        Icon(
                            imageVector = if (state.isFavorite) {
                                Icons.Filled.Star
                            } else {
                                Icons.Outlined.StarBorder
                            },
                            contentDescription = if (state.isFavorite) "取消自选" else "加入自选",
                            tint = if (state.isFavorite) CsColors.Star else CsColors.TextSecondary,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                state.loading && state.stock == null -> CsLoading()
                state.stock == null -> CsErrorState(
                    message = state.error ?: "行情加载失败",
                    onRetry = viewModel::refresh,
                )
                else -> {
                    val stock = state.stock!!
                    AShareQuoteCard(stock = stock, sessionOpen = state.sessionOpen)
                    AShareOrderForm(state = state, viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
private fun AShareQuoteCard(stock: AShareStock, sessionOpen: Boolean) {
    val changeColor = if (stock.changePercent.signum() >= 0) CsColors.Up else CsColors.Down
    CsCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stock.name,
                    color = CsColors.TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "${stock.symbol} · ${if (stock.isSt) "ST" else "A 股"}",
                    color = CsColors.TextMuted,
                    fontSize = 11.sp,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (sessionOpen) CsColors.Up else CsColors.TextMuted),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = if (sessionOpen) "交易中" else "休市",
                    color = if (sessionOpen) CsColors.Up else CsColors.TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = if (stock.price.signum() > 0) stock.price.price(2) else "--",
                color = changeColor,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
            )
            Spacer(modifier = Modifier.width(10.dp))
            ChangeBadge(percent = stock.changePercent)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "涨跌 ${stock.change.signed(2)}",
            color = changeColor,
            fontSize = 12.sp,
        )

        Spacer(modifier = Modifier.height(14.dp))
        CsDivider()
        Spacer(modifier = Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            QuoteMetric("今开", stock.open, Modifier.weight(1f))
            QuoteMetric("最高", stock.high, Modifier.weight(1f))
            QuoteMetric("最低", stock.low, Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            QuoteMetric("昨收", stock.prevClose, Modifier.weight(1f))
            QuoteMetric("涨停", stock.limitUp, Modifier.weight(1f), CsColors.Up)
            QuoteMetric("跌停", stock.limitDown, Modifier.weight(1f), CsColors.Down)
        }
    }
}

@Composable
private fun QuoteMetric(
    label: String,
    value: BigDecimal,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = CsColors.TextPrimary,
) {
    Column(modifier = modifier) {
        Text(text = label, color = CsColors.TextMuted, fontSize = 10.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = if (value.signum() > 0) value.price(2) else "--",
            color = valueColor,
            style = CsNumericTextStyle.copy(fontSize = 12.sp),
        )
    }
}

@Composable
private fun AShareOrderForm(
    state: AShareTradeUiState,
    viewModel: AShareTradeViewModel,
) {
    val preview = state.preview
    val isBuy = state.side == AShareSide.BUY
    val sideColor = if (isBuy) CsColors.Up else CsColors.Down
    val stock = state.stock ?: return

    CsCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        CsSegmented(
            options = listOf("买入", "卖出"),
            selectedIndex = if (isBuy) 0 else 1,
            onSelect = {
                viewModel.setSide(if (it == 0) AShareSide.BUY else AShareSide.SELL)
            },
        )

        Spacer(modifier = Modifier.height(14.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            Text("价格", color = CsColors.TextMuted, fontSize = 12.sp)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "市价 · ${stock.price.price(2)} CNY",
                color = CsColors.TextSecondary,
                fontSize = 12.sp,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = if (isBuy) "买入数量 (股)" else "卖出数量 (股)",
            color = CsColors.TextMuted,
            fontSize = 12.sp,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(CsColors.SurfaceVariant)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (state.quantityInput.isEmpty()) {
                    Text(
                        text = if (isBuy) "请输入 100 的整数倍" else "请输入卖出数量",
                        color = CsColors.TextFaint,
                        fontSize = 13.sp,
                    )
                }
                BasicTextField(
                    value = state.quantityInput,
                    onValueChange = viewModel::setQuantityInput,
                    singleLine = true,
                    textStyle = CsNumericTextStyle.copy(
                        fontSize = 16.sp,
                        color = CsColors.TextPrimary,
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(sideColor),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text("股", color = CsColors.TextMuted, fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(100, 500, 1000).forEach { quantity ->
                CsFieldChip(
                    text = quantity.toString(),
                    selected = false,
                    activeColor = sideColor,
                    onClick = { viewModel.setQuantity(quantity) },
                )
            }
            CsFieldChip(
                text = "最大",
                selected = false,
                activeColor = sideColor,
                onClick = viewModel::setMaxQuantity,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        CsStatRow(
            label = if (isBuy) "可用资金" else "可卖数量",
            value = if (isBuy) {
                state.account.cash.money(2) + " CNY"
            } else {
                "${preview.availableToSell} 股"
            },
        )
        if (isBuy) {
            CsStatRow(
                label = "最大可买",
                value = "${preview.maxBuyQuantity} 股",
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        CsDivider()
        Spacer(modifier = Modifier.height(10.dp))

        CsStatRow("成交金额", preview.amount.money(2) + " CNY")
        CsStatRow("佣金", preview.commission.money(2) + " CNY")
        if (!isBuy) {
            CsStatRow("印花税", preview.stampDuty.money(2) + " CNY")
        }
        CsStatRow("过户费", preview.transferFee.money(2) + " CNY")
        CsStatRow(
            label = if (isBuy) "合计" else "预计到账",
            value = if (isBuy) {
                preview.netCash.abs().money(2) + " CNY"
            } else {
                preview.netCash.money(2) + " CNY"
            },
            valueColor = sideColor,
        )

        if (preview.error != null && state.quantityInput.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = previewErrorText(preview.error),
                color = CsColors.Down,
                fontSize = 11.sp,
            )
        }

        if (!state.sessionOpen) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "当前休市，A 股交易时间为 9:30–11:30、13:00–15:00",
                color = CsColors.Warning,
                fontSize = 11.sp,
            )
        }

        Spacer(modifier = Modifier.height(14.dp))
        CsPrimaryButton(
            text = if (isBuy) "买入 ${stock.name}" else "卖出 ${stock.name}",
            onClick = viewModel::submit,
            enabled = preview.canSubmit,
            color = sideColor,
        )
    }
}

private fun previewErrorText(error: AShareTradeError): String = when (error) {
    AShareTradeError.MarketClosed -> "当前休市"
    AShareTradeError.NoQuote -> "暂无有效行情"
    AShareTradeError.InvalidQuantity -> "请输入有效数量"
    AShareTradeError.NotMultipleOfLot -> "买入数量必须是 100 股的整数倍"
    AShareTradeError.InsufficientCash -> "可用资金不足"
    AShareTradeError.InsufficientShares -> "可卖数量不足（当天买入 T+1 才能卖）"
    AShareTradeError.PriceLimit -> "价格超出涨跌停范围"
    AShareTradeError.Suspended -> "该股票当前停牌"
    AShareTradeError.PositionNotFound -> "没有该股票的持仓"
    is AShareTradeError.Unknown -> error.message
}
