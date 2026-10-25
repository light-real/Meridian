package com.tradesim.app.feature.trade

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tradesim.app.core.designsystem.ChangeBadge
import com.tradesim.app.core.designsystem.CsCard
import com.tradesim.app.core.designsystem.CsColors
import com.tradesim.app.core.designsystem.CsDiamondSlider
import com.tradesim.app.core.designsystem.CsDivider
import com.tradesim.app.core.designsystem.CsFieldChip
import com.tradesim.app.core.designsystem.CsNumericTextStyle
import com.tradesim.app.core.designsystem.CsStatRow
import com.tradesim.app.core.designsystem.CsUnderlineTabs
import com.tradesim.app.core.designsystem.FlashPriceText
import com.tradesim.app.core.designsystem.LabelValue
import com.tradesim.app.core.designsystem.SideBadge
import com.tradesim.app.core.domain.TradingEngine
import com.tradesim.app.core.domain.money
import com.tradesim.app.core.domain.orderBookQty
import com.tradesim.app.core.domain.price
import com.tradesim.app.core.domain.quantity
import com.tradesim.app.core.domain.signed
import com.tradesim.app.core.model.AmountMode
import com.tradesim.app.core.model.BookLevel
import com.tradesim.app.core.model.FundingRate
import com.tradesim.app.core.model.LimitOrder
import com.tradesim.app.core.model.MarginMode
import com.tradesim.app.core.model.MarkedPosition
import com.tradesim.app.core.model.OrderBook
import com.tradesim.app.core.model.OrderType
import com.tradesim.app.core.model.Position
import com.tradesim.app.core.model.Side
import com.tradesim.app.core.model.TradeError
import com.tradesim.app.ui.LocalAppContainer
import com.tradesim.app.ui.appViewModelFactory
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.delay

@Composable
fun TradeScreen(
    symbol: String,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val container = LocalAppContainer.current
    val viewModel: TradeViewModel = viewModel(
        factory = appViewModelFactory {
            TradeViewModel(
                initialSymbol = symbol,
                marketRepository = container.marketRepository,
                accountRepository = container.accountRepository,
                settingsStore = container.settingsStore,
                marketDataHub = container.marketDataHub,
            )
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current
    var showConfirmOrder by remember { mutableStateOf(false) }
    var tabs by remember { mutableStateOf(0) }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }

    Scaffold(
        containerColor = CsColors.Background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { _ ->
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(modifier = Modifier.fillMaxSize()) {
            TradeHeader(
                state = state,
                onBack = onBack,
                onPickSymbol = { viewModel.showSymbolPicker(true) },
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 10.dp,
                    end = 10.dp,
                    top = 10.dp,
                    bottom = 16.dp + bottomInset,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OrderFormPanel(
                            state = state,
                            viewModel = viewModel,
                            onSubmit = { showConfirmOrder = true },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        OrderBookPanel(
                            viewModel = viewModel,
                            lastPrice = state.ticker?.lastPrice,
                            changePercent = state.ticker?.priceChangePercent,
                            funding = state.funding,
                            priceScale = state.symbolInfo?.pricePrecision ?: 2,
                            baseAsset = state.symbolInfo?.baseAsset
                                ?: state.symbol.removeSuffix("USDT"),
                            modifier = Modifier.width(124.dp),
                        )
                    }
                }

                item {
                    CsUnderlineTabs(
                        options = listOf(
                            "仓位 (${state.account.positions.size})",
                            "委托 (${state.account.limitOrders.size})",
                            "记录",
                        ),
                        selectedIndex = tabs,
                        onSelect = {
                            if (it == 2) onOpenHistory() else tabs = it
                        },
                    )
                }

                item {
                    if (tabs == 1) {
                        LimitOrdersSection(
                            orders = state.account.limitOrders,
                            priceScale = state.symbolInfo?.pricePrecision ?: 2,
                            onCancel = viewModel::cancelLimitOrder,
                        )
                    } else {
                        PositionsSection(
                            state = state,
                            viewModel = viewModel,
                            haptic = haptic,
                        )
                    }
                }
            }
        }
    }

    if (state.symbolPickerVisible) {
        SymbolPickerSheet(
            state = state,
            onDismiss = { viewModel.showSymbolPicker(false) },
            onQueryChange = viewModel::setSymbolQuery,
            onSelect = viewModel::selectSymbol,
        )
    }

    state.closeSheet?.let { sheet ->
        ClosePositionSheet(
            state = state,
            sheet = sheet,
            onDismiss = viewModel::dismissCloseSheet,
            onQuantityChange = viewModel::setCloseQuantity,
            onPercent = viewModel::setClosePercent,
            onConfirm = viewModel::confirmClose,
        )
    }

    if (showConfirmOrder) {
        ConfirmOrderDialog(
            state = state,
            onDismiss = { showConfirmOrder = false },
            onConfirm = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                showConfirmOrder = false
                viewModel.placeOrder()
            },
        )
    }
}

@Composable
private fun TradeHeader(
    state: TradeUiState,
    onBack: () -> Unit,
    onPickSymbol: () -> Unit,
) {
    val ticker = state.ticker
    val changeColor = if ((ticker?.priceChangePercent ?: BigDecimal.ZERO).signum() >= 0) {
        CsColors.Up
    } else {
        CsColors.Down
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CsColors.Background)
            .statusBarsPadding()
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Outlined.ArrowBack,
                    contentDescription = "返回",
                    tint = CsColors.TextPrimary,
                )
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onPickSymbol)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.symbol,
                    color = CsColors.TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "永续",
                    color = CsColors.TextMuted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(CsColors.SurfaceVariant)
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = "切换交易对",
                    tint = CsColors.TextMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = (ticker?.priceChangePercent ?: BigDecimal.ZERO).signed(2) + "%",
                color = changeColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "24h",
                color = CsColors.TextFaint,
                fontSize = 10.sp,
            )
            Spacer(modifier = Modifier.weight(1f))
            ticker?.let {
                FlashPriceText(
                    text = it.lastPrice.price(state.symbolInfo?.pricePrecision ?: 2),
                    fontSize = 14.sp,
                    flashUp = it.priceChangePercent.signum() >= 0,
                )
            }
        }
    }
}

@Composable
private fun OrderFormPanel(
    state: TradeUiState,
    viewModel: TradeViewModel,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val info = state.symbolInfo
    val baseAsset = info?.baseAsset ?: state.symbol.removeSuffix("USDT")
    val sideColor = if (state.side == Side.LONG) CsColors.Up else CsColors.Down
    var showLeverageSheet by remember { mutableStateOf(false) }
    var showMarginSheet by remember { mutableStateOf(false) }

    if (showLeverageSheet) {
        LeverageSheet(
            current = state.leverage,
            cash = state.account.cash,
            onDismiss = { showLeverageSheet = false },
            onConfirm = { value ->
                viewModel.setLeverage(value)
                showLeverageSheet = false
            },
        )
    }

    if (showMarginSheet) {
        MarginModeSheet(
            current = state.marginMode,
            onDismiss = { showMarginSheet = false },
            onSelect = { mode ->
                viewModel.setMarginMode(mode)
                showMarginSheet = false
            },
        )
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(CsColors.SurfaceVariant)
                .padding(3.dp),
        ) {
            listOf("买入", "卖出").forEachIndexed { index, label ->
                val isLong = index == 0
                val selected = (state.side == Side.LONG) == isLong
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (selected) {
                                if (isLong) CsColors.Up else CsColors.Down
                            } else {
                                Color.Transparent
                            },
                        )
                        .clickable {
                            viewModel.setSide(if (isLong) Side.LONG else Side.SHORT)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = if (selected) CsColors.OnPrimary else CsColors.TextSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CsFieldChip(
                text = state.marginMode.label,
                selected = true,
                activeColor = sideColor,
                onClick = { showMarginSheet = true },
            )
            CsFieldChip(
                text = "${state.leverage}x",
                selected = true,
                activeColor = sideColor,
                onClick = { showLeverageSheet = true },
            )
            CsFieldChip(
                text = state.orderType.label,
                selected = state.orderType == OrderType.LIMIT,
                activeColor = sideColor,
                onClick = {
                    viewModel.setOrderType(
                        if (state.orderType == OrderType.MARKET) {
                            OrderType.LIMIT
                        } else {
                            OrderType.MARKET
                        },
                    )
                },
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(CsColors.SurfaceVariant)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "价格(USDT)",
                color = CsColors.TextFaint,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = state.ticker?.lastPrice?.price(info?.pricePrecision ?: 2) ?: "--",
                color = CsColors.TextSecondary,
                style = CsNumericTextStyle.copy(fontSize = 13.sp),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (state.orderType == OrderType.LIMIT) {
            TriggerInput(
                value = state.limitPriceInput,
                onValueChange = viewModel::setLimitPriceInput,
                placeholder = "限价(USDT)",
                tint = sideColor,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(CsColors.SurfaceVariant)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (state.amountInput.isEmpty()) {
                    Text(
                        text = "总额(USDT)",
                        color = CsColors.TextFaint,
                        fontSize = 13.sp,
                    )
                }
                BasicTextField(
                    value = state.amountInput,
                    onValueChange = viewModel::setAmountInput,
                    singleLine = true,
                    textStyle = CsNumericTextStyle.copy(
                        fontSize = 15.sp,
                        color = CsColors.TextPrimary,
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(sideColor),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                text = "USDT",
                color = CsColors.TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "可用 " + availableCash(state).money(2),
                color = CsColors.TextMuted,
                fontSize = 11.sp,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "保证金 " + state.preview.margin.money(2),
                color = CsColors.TextMuted,
                fontSize = 11.sp,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TriggerInput(
                value = state.takeProfitInput,
                onValueChange = viewModel::setTakeProfitInput,
                placeholder = "止盈价",
                tint = CsColors.Up,
                modifier = Modifier.weight(1f),
            )
            TriggerInput(
                value = state.stopLossInput,
                onValueChange = viewModel::setStopLossInput,
                placeholder = "止损价",
                tint = CsColors.Down,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "最大可开",
                color = CsColors.TextFaint,
                fontSize = 11.sp,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = maxQuantityText(state, baseAsset) + " " + baseAsset,
                color = CsColors.TextSecondary,
                style = CsNumericTextStyle.copy(fontSize = 12.sp),
            )
        }

        previewErrorText(state.preview.error)?.let { errorText ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = errorText, color = CsColors.Down, fontSize = 11.sp)
        }

        Spacer(modifier = Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (state.preview.canSubmit) sideColor else sideColor.copy(alpha = 0.35f),
                )
                .clickable(enabled = state.preview.canSubmit, onClick = onSubmit),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = when {
                    state.orderType == OrderType.LIMIT && state.side == Side.LONG -> "限价买入 $baseAsset"
                    state.orderType == OrderType.LIMIT -> "限价卖出 $baseAsset"
                    state.side == Side.LONG -> "买入 $baseAsset"
                    else -> "卖出 $baseAsset"
                },
                color = CsColors.OnPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (state.leverage >= 20) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "高杠杆会放大收益和亏损",
                color = CsColors.Warning,
                fontSize = 10.sp,
            )
        }
    }
}

private fun maxQuantityText(state: TradeUiState, baseAsset: String): String {
    val priceValue = state.ticker?.lastPrice ?: return "--"
    if (priceValue.signum() <= 0) return "--"
    val maxNotional = TradingEngine.maxNotional(availableCash(state), state.leverage)
    return maxNotional
        .divide(priceValue, 8, RoundingMode.DOWN)
        .quantity(state.symbolInfo?.quantityPrecision ?: 3)
}

/** 扣掉全仓已占用保证金后的可用余额。 */
private fun availableCash(state: TradeUiState): BigDecimal {
    val crossUsed = if (state.marginMode == MarginMode.CROSS) {
        state.account.positions
            .filter { it.marginMode == MarginMode.CROSS && it.symbol != state.symbol }
            .fold(BigDecimal.ZERO) { acc, position -> acc.add(position.margin) }
    } else {
        BigDecimal.ZERO
    }
    return state.account.cash.subtract(crossUsed).max(BigDecimal.ZERO)
}

@Composable
private fun OrderBookPanel(
    viewModel: TradeViewModel,
    lastPrice: BigDecimal?,
    changePercent: BigDecimal?,
    funding: FundingRate?,
    priceScale: Int,
    baseAsset: String,
    modifier: Modifier = Modifier,
) {
    // 在这一层订阅盘口，深度推送只重组这个面板，不牵连整页
    val orderBook by viewModel.orderBook.collectAsStateWithLifecycle()
    val fallbackBook = remember(lastPrice) { lastPrice?.let { buildMockOrderBook(it) } }
    val book = orderBook ?: fallbackBook
    val levels = 5
    val lastColor = if ((changePercent ?: BigDecimal.ZERO).signum() >= 0) {
        CsColors.Up
    } else {
        CsColors.Down
    }

    Column(modifier = modifier) {
        if (funding != null) {
            FundingHeader(funding = funding)
            Spacer(modifier = Modifier.height(8.dp))
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "价格(USDT)",
                color = CsColors.TextFaint,
                fontSize = 9.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "数量($baseAsset)",
                color = CsColors.TextFaint,
                fontSize = 9.sp,
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        if (book == null) {
            Text(
                text = "盘口加载中…",
                color = CsColors.TextFaint,
                fontSize = 10.sp,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            return@Column
        }

        val asks = book.asks.take(levels).reversed()
        val bids = book.bids.take(levels)
        val maxQty = (asks + bids).maxOfOrNull { it.quantity } ?: BigDecimal.ONE

        asks.forEach { level ->
            BookLevelRow(
                price = level.price.price(priceScale),
                quantity = level.quantity.orderBookQty(),
                fraction = fractionOf(level.quantity, maxQty),
                isAsk = true,
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = lastPrice?.price(priceScale) ?: "--",
            color = lastColor,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Text(
            text = "价差 " + spreadText(book, priceScale),
            color = CsColors.TextFaint,
            fontSize = 9.sp,
            maxLines = 1,
        )

        Spacer(modifier = Modifier.height(6.dp))

        bids.forEach { level ->
            BookLevelRow(
                price = level.price.price(priceScale),
                quantity = level.quantity.orderBookQty(),
                fraction = fractionOf(level.quantity, maxQty),
                isAsk = false,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        val bidVolume = book.bids.take(levels).fold(BigDecimal.ZERO) { acc, it -> acc.add(it.quantity) }
        val askVolume = book.asks.take(levels).fold(BigDecimal.ZERO) { acc, it -> acc.add(it.quantity) }
        val total = bidVolume.add(askVolume)
        val bidRatio = if (total.signum() <= 0) {
            0.5f
        } else {
            bidVolume.toFloat() / total.toFloat()
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = percentLabel(bidRatio),
                color = CsColors.Up,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
            ) {
                Box(
                    modifier = Modifier
                        .weight(bidRatio.coerceAtLeast(0.001f))
                        .fillMaxHeight()
                        .background(CsColors.Up),
                )
                Box(
                    modifier = Modifier
                        .weight((1f - bidRatio).coerceAtLeast(0.001f))
                        .fillMaxHeight()
                        .background(CsColors.Down),
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = percentLabel(1f - bidRatio),
                color = CsColors.Down,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun FundingHeader(funding: FundingRate) {
    val now by produceState(
        initialValue = System.currentTimeMillis(),
        key1 = funding.nextFundingTime,
    ) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val remainingMs = (funding.nextFundingTime - now).coerceAtLeast(0L)
    val hours = remainingMs / 3_600_000L
    val minutes = remainingMs % 3_600_000L / 60_000L
    val seconds = remainingMs % 60_000L / 1_000L
    val countdown = if (hours > 0L) {
        String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
    }
    val rateText = funding.rate
        .multiply(BigDecimal("100"))
        .setScale(4, RoundingMode.HALF_UP)
        .toPlainString() + "%"

    Column {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "资金费率",
                color = CsColors.TextFaint,
                fontSize = 9.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = rateText,
                color = CsColors.TextSecondary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "距结算",
                color = CsColors.TextFaint,
                fontSize = 9.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = countdown,
                color = CsColors.TextSecondary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun LimitOrdersSection(
    orders: List<LimitOrder>,
    priceScale: Int,
    onCancel: (String) -> Unit,
) {
    if (orders.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Outlined.SwapVert,
                contentDescription = null,
                tint = CsColors.TextFaint,
                modifier = Modifier.size(34.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = "暂无委托", color = CsColors.TextMuted, fontSize = 13.sp)
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        orders.forEach { order ->
            CsCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = order.baseAsset,
                                color = CsColors.TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            SideBadge(side = order.side, leverage = order.leverage)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = order.marginMode.label,
                                color = CsColors.TextMuted,
                                fontSize = 9.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(CsColors.SurfaceVariant)
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "限价 " + order.limitPrice.price(priceScale) +
                                " · " + order.notional.money(2) + " USDT",
                            color = CsColors.TextSecondary,
                            fontSize = 11.sp,
                        )
                    }
                    TextButton(onClick = { onCancel(order.id) }) {
                        Text(text = "撤单", color = CsColors.Down, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TriggerInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(CsColors.SurfaceVariant)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    color = tint.copy(alpha = 0.75f),
                    fontSize = 12.sp,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = { input ->
                    onValueChange(input.filter { it.isDigit() || it == '.' }.take(16))
                },
                singleLine = true,
                textStyle = CsNumericTextStyle.copy(
                    fontSize = 13.sp,
                    color = CsColors.TextPrimary,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(tint),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun MarginDialog(
    position: Position,
    priceScale: Int,
    available: BigDecimal,
    onDismiss: () -> Unit,
    onConfirm: (BigDecimal) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val amount = input.toBigDecimalOrNull() ?: BigDecimal.ZERO
    val valid = amount.signum() > 0 && amount <= available

    val newMargin = position.margin.add(amount)
    val newLiquidation = if (amount.signum() > 0) {
        TradingEngine.liquidationPriceFor(
            entryPrice = position.entryPrice,
            side = position.side,
            quantity = position.quantity,
            margin = newMargin,
        )
    } else {
        position.liquidationPrice
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "${position.baseAsset} 追加保证金",
                color = CsColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("当前保证金", color = CsColors.TextMuted, fontSize = 12.sp)
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = position.margin.money(2) + " USDT",
                        color = CsColors.TextPrimary,
                        style = CsNumericTextStyle.copy(fontSize = 12.sp),
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("可用余额", color = CsColors.TextMuted, fontSize = 12.sp)
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = available.money(2) + " USDT",
                        color = CsColors.TextSecondary,
                        style = CsNumericTextStyle.copy(fontSize = 12.sp),
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                TriggerInput(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = "追加金额(USDT)",
                    tint = CsColors.Primary,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("追加后强平价", color = CsColors.TextMuted, fontSize = 11.sp)
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = newLiquidation.price(priceScale),
                        color = CsColors.Down,
                        style = CsNumericTextStyle.copy(fontSize = 12.sp),
                    )
                }
                if (!valid && input.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (amount > available) "超出可用余额" else "请输入大于 0 的金额",
                        color = CsColors.Down,
                        fontSize = 11.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(amount) },
                enabled = valid,
            ) {
                Text(
                    text = "确认",
                    color = if (valid) CsColors.Primary else CsColors.TextFaint,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", color = CsColors.TextMuted)
            }
        },
        containerColor = CsColors.SurfaceElevated,
        titleContentColor = CsColors.TextPrimary,
        textContentColor = CsColors.TextSecondary,
    )
}

@Composable
private fun TriggerDialog(
    position: Position,
    priceScale: Int,
    onDismiss: () -> Unit,
    onConfirm: (BigDecimal?, BigDecimal?) -> Unit,
) {
    var takeProfit by remember {
        mutableStateOf(position.takeProfitPrice?.price(priceScale) ?: "")
    }
    var stopLoss by remember {
        mutableStateOf(position.stopLossPrice?.price(priceScale) ?: "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "${position.baseAsset} 止盈止损",
                color = CsColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column {
                Text(
                    text = "留空表示不设置。触发后按市价全平。",
                    color = CsColors.TextMuted,
                    fontSize = 11.sp,
                )
                Spacer(modifier = Modifier.height(12.dp))
                TriggerInput(
                    value = takeProfit,
                    onValueChange = { takeProfit = it },
                    placeholder = "止盈价",
                    tint = CsColors.Up,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                TriggerInput(
                    value = stopLoss,
                    onValueChange = { stopLoss = it },
                    placeholder = "止损价",
                    tint = CsColors.Down,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        takeProfit.toBigDecimalOrNull(),
                        stopLoss.toBigDecimalOrNull(),
                    )
                },
            ) {
                Text(
                    text = "保存",
                    color = CsColors.Primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", color = CsColors.TextMuted)
            }
        },
        containerColor = CsColors.SurfaceElevated,
        titleContentColor = CsColors.TextPrimary,
        textContentColor = CsColors.TextSecondary,
    )
}

@Composable
private fun BookLevelRow(
    price: String,
    quantity: String,
    fraction: Float,
    isAsk: Boolean,
) {
    val color = if (isAsk) CsColors.Down else CsColors.Up
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .align(Alignment.CenterEnd)
                .background(color.copy(alpha = 0.12f)),
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 1.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = price,
                color = color,
                fontSize = 11.sp,
                maxLines = 1,
            )
            Text(
                text = quantity,
                color = CsColors.TextPrimary,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
    }
}

private fun fractionOf(quantity: BigDecimal, maxQuantity: BigDecimal): Float {
    if (maxQuantity.signum() <= 0) return 0f
    return (quantity.toFloat() / maxQuantity.toFloat()).coerceIn(0f, 1f)
}

private fun spreadText(book: OrderBook, priceScale: Int): String {
    val ask = book.asks.firstOrNull()?.price ?: return "--"
    val bid = book.bids.firstOrNull()?.price ?: return "--"
    return ask.subtract(bid).abs().price(priceScale)
}

private fun percentLabel(ratio: Float): String =
    (ratio * 100f).coerceIn(0f, 100f).let { String.format(java.util.Locale.US, "%.2f%%", it) }

@Composable
private fun PositionsSection(
    state: TradeUiState,
    viewModel: TradeViewModel,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
) {
    var editing by remember { mutableStateOf<Position?>(null) }
    var addingMarginTo by remember { mutableStateOf<Position?>(null) }
    // 每秒变化的仓位在这里收集，外层交易页不跟着重组
    val positions by viewModel.positions.collectAsStateWithLifecycle()

    if (positions.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Outlined.SwapVert,
                contentDescription = null,
                tint = CsColors.TextFaint,
                modifier = Modifier.size(34.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = "暂无持仓", color = CsColors.TextMuted, fontSize = 13.sp)
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        positions.forEach { marked ->
            PositionMiniCard(
                marked = marked,
                priceScale = state.symbolInfo?.pricePrecision ?: 2,
                quantityScale = state.symbolInfo?.quantityPrecision ?: 3,
                onClose = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.openCloseSheet(marked)
                },
                onEditTriggers = { editing = marked.position },
                onAddMargin = { addingMarginTo = marked.position },
            )
        }
    }

    addingMarginTo?.let { position ->
        MarginDialog(
            position = position,
            priceScale = state.symbolInfo?.pricePrecision ?: 2,
            available = availableCash(state),
            onDismiss = { addingMarginTo = null },
            onConfirm = { amount ->
                viewModel.addMargin(position.id, amount)
                addingMarginTo = null
            },
        )
    }

    editing?.let { position ->
        TriggerDialog(
            position = position,
            priceScale = state.symbolInfo?.pricePrecision ?: 2,
            onDismiss = { editing = null },
            onConfirm = { takeProfit, stopLoss ->
                viewModel.updateTriggers(position.id, takeProfit, stopLoss)
                editing = null
            },
        )
    }
}

@Composable
private fun PositionMiniCard(
    marked: MarkedPosition,
    priceScale: Int,
    quantityScale: Int,
    onClose: () -> Unit,
    onEditTriggers: () -> Unit,
    onAddMargin: () -> Unit,
) {
    val position = marked.position
    val pnlColor = if (marked.unrealizedPnl.signum() >= 0) CsColors.Up else CsColors.Down
    val roiColor = if (marked.roi.signum() >= 0) CsColors.Up else CsColors.Down
    val pnlTint = if (marked.unrealizedPnl.signum() >= 0) {
        CsColors.Up.copy(alpha = 0.07f)
    } else {
        CsColors.Down.copy(alpha = 0.07f)
    }

    CsCard(contentPadding = PaddingValues(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = position.symbol,
                color = CsColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.width(6.dp))
            SideBadge(side = position.side, leverage = position.leverage)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = position.marginMode.label + " · " +
                    position.quantity.quantity(quantityScale) + " " + position.baseAsset,
                color = CsColors.TextMuted,
                fontSize = 10.sp,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "平仓",
                color = CsColors.Primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(CsColors.Primary.copy(alpha = 0.1f))
                    .clickable(onClick = onClose)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(pnlTint)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "未实现盈亏",
                    color = CsColors.TextMuted,
                    fontSize = 10.sp,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = marked.unrealizedPnl.signed(2) + " USDT",
                    color = pnlColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "收益率",
                    color = CsColors.TextMuted,
                    fontSize = 10.sp,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = (if (marked.roi.signum() >= 0) "+" else "") +
                        marked.roi.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%",
                    color = roiColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                PositionStatRow("仓位价值", position.notional.money(2) + " USDT")
                Spacer(modifier = Modifier.height(7.dp))
                PositionStatRow("开仓均价", position.entryPrice.price(priceScale))
                Spacer(modifier = Modifier.height(7.dp))
                PositionStatRow("标记价格", marked.markPrice.price(priceScale))
            }
            Spacer(modifier = Modifier.width(18.dp))
            Column(modifier = Modifier.weight(1f)) {
                PositionStatRow("保证金", position.margin.money(2) + " USDT")
                Spacer(modifier = Modifier.height(7.dp))
                PositionStatRow(
                    label = "强平价",
                    value = if (position.liquidationPrice.signum() > 0) {
                        position.liquidationPrice.price(priceScale)
                    } else {
                        "--"
                    },
                    valueColor = CsColors.Down,
                )
                Spacer(modifier = Modifier.height(7.dp))
                PositionStatRow(
                    label = "止盈 / 止损",
                    value = triggerSummary(position, priceScale),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!position.isCross) {
                Text(
                    text = "加保证金",
                    color = CsColors.TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(CsColors.SurfaceVariant)
                        .clickable(onClick = onAddMargin)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "设置止盈止损",
                color = CsColors.Primary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onEditTriggers)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun PositionStatRow(
    label: String,
    value: String,
    valueColor: Color = CsColors.TextPrimary,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = CsColors.TextFaint, fontSize = 11.sp)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = value,
            color = valueColor,
            style = CsNumericTextStyle.copy(fontSize = 12.sp),
            maxLines = 1,
        )
    }
}

private fun triggerSummary(position: Position, priceScale: Int): String {
    val takeProfit = position.takeProfitPrice?.price(priceScale)
    val stopLoss = position.stopLossPrice?.price(priceScale)
    return when {
        takeProfit != null && stopLoss != null -> "$takeProfit / $stopLoss"
        takeProfit != null -> "$takeProfit / --"
        stopLoss != null -> "-- / $stopLoss"
        else -> "未设置"
    }
}

@Composable
private fun ConfirmOrderDialog(
    state: TradeUiState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val info = state.symbolInfo
    val baseAsset = info?.baseAsset ?: state.symbol.removeSuffix("USDT")
    val sideColor = if (state.side == Side.LONG) CsColors.Up else CsColors.Down
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (state.side == Side.LONG) "确认买入 $baseAsset" else "确认卖出 $baseAsset",
                color = CsColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column {
                CsStatRow("保证金模式", state.marginMode.label)
                CsStatRow("杠杆", "${state.leverage}x")
                CsStatRow(
                    "开仓数量",
                    state.preview.quantity.quantity(info?.quantityPrecision ?: 3) + " $baseAsset",
                )
                CsStatRow("保证金", state.preview.margin.money(2) + " USDT")
                CsStatRow("手续费", state.preview.fee.money(2) + " USDT")
                CsStatRow(
                    "预计强平价",
                    if (state.preview.liquidationPrice.signum() > 0) {
                        state.preview.liquidationPrice.price(info?.pricePrecision ?: 2)
                    } else {
                        "全仓统一计算"
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = if (state.side == Side.LONG) "确认买入" else "确认卖出",
                    color = sideColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", color = CsColors.TextMuted)
            }
        },
        containerColor = CsColors.SurfaceElevated,
        titleContentColor = CsColors.TextPrimary,
        textContentColor = CsColors.TextSecondary,
    )
}

private fun buildMockOrderBook(lastPrice: BigDecimal): OrderBook {
    val tick = when {
        lastPrice >= BigDecimal("10000") -> BigDecimal("0.1")
        lastPrice >= BigDecimal("1000") -> BigDecimal("0.01")
        lastPrice >= BigDecimal("1") -> BigDecimal("0.001")
        else -> BigDecimal("0.0001")
    }
    val asks = (1..8).map { index ->
        BookLevel(
            price = lastPrice.add(tick.multiply(BigDecimal(index.toString()))),
            quantity = mockQuantity(index),
        )
    }
    val bids = (1..8).map { index ->
        BookLevel(
            price = lastPrice
                .subtract(tick.multiply(BigDecimal(index.toString())))
                .max(BigDecimal.ZERO),
            quantity = mockQuantity(index + 3),
        )
    }
    return OrderBook(symbol = "", bids = bids, asks = asks)
}

private fun mockQuantity(seed: Int): BigDecimal =
    BigDecimal("0.4").add(
        BigDecimal(((seed * 7) % 13).toString())
            .divide(BigDecimal("10"), 3, RoundingMode.HALF_UP),
    )

private fun previewErrorText(error: TradeError?): String? = when (error) {
    null -> null
    TradeError.InvalidLeverage -> "杠杆倍数需在 1-150 之间"
    TradeError.InvalidAmount -> "请输入金额"
    TradeError.BelowMinNotional -> "金额低于最小下单金额"
    TradeError.InsufficientBalance -> "保证金 + 手续费超出可用余额"
    TradeError.OppositePositionExists -> "该币种已有反向持仓，请先平仓"
    TradeError.LeverageMismatch -> "同方向加仓请使用相同杠杆"
    TradeError.MarginModeMismatch -> "同方向加仓请使用相同的保证金模式"
    TradeError.InvalidTriggerPrice -> "止盈/止损价方向不对：多仓止盈要高于开仓价、止损要低于开仓价"
    TradeError.PositionNotFound -> "持仓不存在"
    TradeError.QuantityTooSmall -> "数量过小"
    is TradeError.Unknown -> error.message
}
