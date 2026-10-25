package com.cryptosim.app.feature.trade

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.cryptosim.app.core.AppConstants
import com.cryptosim.app.core.designsystem.ChangeBadge
import com.cryptosim.app.core.designsystem.CoinAvatar
import com.cryptosim.app.core.designsystem.CsCard
import com.cryptosim.app.core.designsystem.CsChip
import com.cryptosim.app.core.designsystem.CsColors
import com.cryptosim.app.core.designsystem.CsDivider
import com.cryptosim.app.core.designsystem.CsEmptyState
import com.cryptosim.app.core.designsystem.CsLoading
import com.cryptosim.app.core.designsystem.CsPrimaryButton
import com.cryptosim.app.core.designsystem.CsSegmented
import com.cryptosim.app.core.designsystem.CsStatRow
import com.cryptosim.app.core.designsystem.CsTextField
import com.cryptosim.app.core.designsystem.FlashPriceText
import com.cryptosim.app.core.designsystem.LabelValue
import com.cryptosim.app.core.designsystem.PriceText
import com.cryptosim.app.core.designsystem.SideBadge
import com.cryptosim.app.core.domain.money
import com.cryptosim.app.core.domain.price
import com.cryptosim.app.core.domain.quantity
import com.cryptosim.app.core.domain.signed
import com.cryptosim.app.core.model.AmountMode
import com.cryptosim.app.core.model.BookLevel
import com.cryptosim.app.core.model.MarkedPosition
import com.cryptosim.app.core.model.OrderBook
import com.cryptosim.app.core.model.Side
import com.cryptosim.app.core.model.TradeError
import com.cryptosim.app.feature.trade.chart.KlineChart
import com.cryptosim.app.ui.LocalAppContainer
import com.cryptosim.app.ui.appViewModelFactory
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.launch

@Composable
fun TradeScreen(
    symbol: String,
    onBack: () -> Unit,
) {
    val container = LocalAppContainer.current
    val viewModel: TradeViewModel = viewModel(
        factory = appViewModelFactory {
            TradeViewModel(
                initialSymbol = symbol,
                marketRepository = container.marketRepository,
                accountRepository = container.accountRepository,
                settingsStore = container.settingsStore,
            )
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var showConfirmOrder by remember { mutableStateOf(false) }

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
            TradeTopBar(
                state = state,
                onBack = onBack,
                onPickSymbol = { viewModel.showSymbolPicker(true) },
            )
        },
        bottomBar = {
            TradeActionBar(
                state = state,
                onSelectSide = { side ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.setSide(side)
                    scope.launch { listState.animateScrollToItem(2) }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ChartSection(state = state, viewModel = viewModel)
            }
            item {
                OrderBookSection(
                    orderBook = state.orderBook,
                    lastPrice = state.ticker?.lastPrice,
                    priceScale = state.symbolInfo?.pricePrecision ?: 2,
                )
            }
            item {
                OrderFormSection(
                    state = state,
                    viewModel = viewModel,
                    onSubmit = { showConfirmOrder = true },
                )
            }
            item {
                PositionsSection(state = state, viewModel = viewModel)
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
private fun TradeActionBar(
    state: TradeUiState,
    onSelectSide: (Side) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CsColors.Surface)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "可用余额", color = CsColors.TextFaint, fontSize = 10.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = state.account.cash.money(2) + " USDT",
                color = CsColors.TextPrimary,
                style = com.cryptosim.app.core.designsystem.CsNumericTextStyle.copy(fontSize = 12.sp),
            )
        }
        Button(
            onClick = { onSelectSide(Side.LONG) },
            modifier = Modifier
                .weight(1f)
                .height(44.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = CsColors.Up,
                contentColor = Color.White,
            ),
        ) {
            Text(text = "做多", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Button(
            onClick = { onSelectSide(Side.SHORT) },
            modifier = Modifier
                .weight(1f)
                .height(44.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = CsColors.Down,
                contentColor = Color.White,
            ),
        ) {
            Text(text = "做空", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
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
                text = if (state.side == Side.LONG) "确认做多 $baseAsset" else "确认做空 $baseAsset",
                color = CsColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column {
                CsStatRow("杠杆", "${state.leverage}x")
                CsStatRow("开仓数量", state.preview.quantity.quantity(info?.quantityPrecision ?: 3) + " $baseAsset")
                CsStatRow("保证金", state.preview.margin.money(2) + " USDT")
                CsStatRow("手续费", state.preview.fee.money(2) + " USDT")
                CsStatRow(
                    "预计强平价",
                    if (state.preview.liquidationPrice.signum() > 0) {
                        state.preview.liquidationPrice.price(info?.pricePrecision ?: 2)
                    } else {
                        "--"
                    },
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "模拟交易，不涉及真实资金",
                    color = CsColors.TextFaint,
                    fontSize = 10.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = if (state.side == Side.LONG) "确认做多" else "确认做空",
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

@Composable
private fun TradeTopBar(
    state: TradeUiState,
    onBack: () -> Unit,
    onPickSymbol: () -> Unit,
) {
    val info = state.symbolInfo
    val baseAsset = info?.baseAsset ?: state.symbol.removeSuffix("USDT")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(CsColors.Background)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = baseAsset,
                        color = CsColors.TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowDown,
                        contentDescription = "切换交易对",
                        tint = CsColors.TextMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text = "${state.symbol} 永续",
                    color = CsColors.TextFaint,
                    fontSize = 10.sp,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            val ticker = state.ticker
            FlashPriceText(
                text = ticker?.lastPrice?.price(info?.pricePrecision ?: 2) ?: "--",
                fontSize = 17.sp,
                flashUp = (ticker?.priceChangePercent ?: BigDecimal.ZERO).signum() >= 0,
            )
            Spacer(modifier = Modifier.height(3.dp))
            ticker?.let {
                ChangeBadge(percent = it.priceChangePercent)
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
    }
}

@Composable
private fun ChartSection(
    state: TradeUiState,
    viewModel: TradeViewModel,
) {
    val info = state.symbolInfo
    val selected = state.selectedKline
    val intervalState = rememberLazyListState()
    LaunchedEffect(state.interval) {
        val index = AppConstants.KLINE_INTERVALS.indexOf(state.interval)
        if (index >= 0) intervalState.animateScrollToItem(index)
    }
    CsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "K 线",
                color = CsColors.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "长按查看 OHLC",
                color = CsColors.TextFaint,
                fontSize = 10.sp,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyRow(
            state = intervalState,
            contentPadding = PaddingValues(end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(AppConstants.KLINE_INTERVALS) { interval ->
                CsChip(
                    text = interval,
                    selected = state.interval == interval,
                    onClick = { viewModel.setInterval(interval) },
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (selected != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(CsColors.SurfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OhlcItem("开", selected.open.price(info?.pricePrecision ?: 2), CsColors.TextSecondary)
                OhlcItem("高", selected.high.price(info?.pricePrecision ?: 2), CsColors.Up)
                OhlcItem("低", selected.low.price(info?.pricePrecision ?: 2), CsColors.Down)
                OhlcItem("收", selected.close.price(info?.pricePrecision ?: 2), CsColors.TextPrimary)
                OhlcItem("量", selected.volume.money(2), CsColors.TextMuted)
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        Box(modifier = Modifier.fillMaxWidth().height(280.dp)) {
            if (state.klines.isEmpty()) {
                if (state.loading) {
                    CsLoading()
                } else {
                    CsEmptyState(title = "暂无 K 线数据", hint = "请检查网络后重试")
                }
            } else {
                key(state.symbol, state.interval) {
                    KlineChart(
                        klines = state.klines,
                        interval = state.interval,
                        priceScale = info?.pricePrecision ?: 2,
                        showMA5 = state.showMA5,
                        showMA10 = state.showMA10,
                        showVolume = state.showVolume,
                        modifier = Modifier.fillMaxSize(),
                        onCrosshairChange = viewModel::setSelectedKline,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CsChip(
                text = "MA5",
                selected = state.showMA5,
                onClick = viewModel::toggleMA5,
                activeColor = Color(0xFFEAB308),
            )
            CsChip(
                text = "MA10",
                selected = state.showMA10,
                onClick = viewModel::toggleMA10,
                activeColor = CsColors.Primary,
            )
            CsChip(
                text = "成交量",
                selected = state.showVolume,
                onClick = viewModel::toggleVolume,
                activeColor = CsColors.Up,
            )
        }
    }
}

@Composable
private fun OhlcItem(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, color = CsColors.TextFaint, fontSize = 9.sp)
        Spacer(modifier = Modifier.width(2.dp))
        Text(text = value, color = color, fontSize = 10.sp)
    }
}

@Composable
private fun OrderBookSection(
    orderBook: OrderBook?,
    lastPrice: BigDecimal?,
    priceScale: Int,
) {
    val fallbackBook = remember(lastPrice) { lastPrice?.let { buildMockOrderBook(it) } }
    val book = orderBook ?: fallbackBook
    val isMock = orderBook == null && book != null

    CsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "盘口",
                color = CsColors.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (isMock) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "模拟",
                    color = CsColors.TextFaint,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(CsColors.SurfaceVariant)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            PriceText(
                text = lastPrice?.price(priceScale) ?: "--",
                color = CsColors.TextPrimary,
                fontSize = 12.sp,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (book == null) {
            Text(
                text = "盘口加载中…",
                color = CsColors.TextFaint,
                fontSize = 11.sp,
                modifier = Modifier.padding(vertical = 18.dp),
            )
        } else {
            val asks = book.asks.take(8).reversed()
            val bids = book.bids.take(8)
            val maxQty = (asks + bids).maxOfOrNull { it.quantity } ?: BigDecimal.ONE
            Column {
                asks.forEach { level ->
                    BookRow(level = level, maxQuantity = maxQty, isAsk = true, priceScale = priceScale)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = book.asks.firstOrNull()?.price?.price(priceScale) ?: "--",
                        color = CsColors.Down,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "价差",
                        color = CsColors.TextFaint,
                        fontSize = 9.sp,
                    )
                    Text(
                        text = book.bids.firstOrNull()?.price?.price(priceScale) ?: "--",
                        color = CsColors.Up,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                bids.forEach { level ->
                    BookRow(level = level, maxQuantity = maxQty, isAsk = false, priceScale = priceScale)
                }
            }
        }
    }
}

@Composable
private fun BookRow(
    level: BookLevel,
    maxQuantity: BigDecimal,
    isAsk: Boolean,
    priceScale: Int,
) {
    val color = if (isAsk) CsColors.Down else CsColors.Up
    val fraction = if (maxQuantity.signum() <= 0) {
        0f
    } else {
        (level.quantity.toFloat() / maxQuantity.toFloat()).coerceIn(0f, 1f)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(20.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(20.dp)
                .align(Alignment.CenterEnd)
                .background(color.copy(alpha = 0.08f)),
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = level.price.price(priceScale),
                color = color,
                fontSize = 11.sp,
            )
            Text(
                text = level.quantity.quantity(4),
                color = CsColors.TextSecondary,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun OrderFormSection(
    state: TradeUiState,
    viewModel: TradeViewModel,
    onSubmit: () -> Unit,
) {
    val info = state.symbolInfo
    val baseAsset = info?.baseAsset ?: state.symbol.removeSuffix("USDT")
    val sideColor = if (state.side == Side.LONG) CsColors.Up else CsColors.Down

    CsCard {
        CsSegmented(
            options = listOf("做多", "做空"),
            selectedIndex = if (state.side == Side.LONG) 0 else 1,
            onSelect = { index ->
                viewModel.setSide(if (index == 0) Side.LONG else Side.SHORT)
            },
            activeColor = sideColor,
        )

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "杠杆倍数",
                color = CsColors.TextMuted,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = { viewModel.setLeverage(state.leverage - 1) },
                enabled = state.leverage > 1,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Remove,
                    contentDescription = "降低杠杆",
                    tint = if (state.leverage > 1) CsColors.TextSecondary else CsColors.TextFaint,
                )
            }
            Text(
                text = "${state.leverage}x",
                color = CsColors.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            IconButton(
                onClick = { viewModel.setLeverage(state.leverage + 1) },
                enabled = state.leverage < 150,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = "提高杠杆",
                    tint = if (state.leverage < 150) CsColors.TextSecondary else CsColors.TextFaint,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(5, 10, 20, 50, 100).forEach { leverage ->
                CsChip(
                    text = "${leverage}x",
                    selected = state.leverage == leverage,
                    onClick = { viewModel.setLeverage(leverage) },
                    activeColor = sideColor,
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        CsSegmented(
            options = listOf("按保证金", "按名义价值"),
            selectedIndex = if (state.amountMode == AmountMode.MARGIN) 0 else 1,
            onSelect = { index ->
                viewModel.setAmountMode(
                    if (index == 0) AmountMode.MARGIN else AmountMode.NOTIONAL,
                )
            },
            activeColor = sideColor,
        )

        Spacer(modifier = Modifier.height(10.dp))

        CsTextField(
            value = state.amountInput,
            onValueChange = viewModel::setAmountInput,
            label = if (state.amountMode == AmountMode.MARGIN) "开仓保证金" else "开仓名义价值",
            placeholder = "0.00",
            keyboardType = KeyboardType.Decimal,
            trailingContent = {
                Text(
                    text = "USDT",
                    color = CsColors.TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(end = 10.dp),
                )
            },
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "25%" to BigDecimal("0.25"),
                "50%" to BigDecimal("0.50"),
                "75%" to BigDecimal("0.75"),
                "100%" to BigDecimal.ONE,
            ).forEach { (label, percent) ->
                CsChip(
                    text = label,
                    onClick = { viewModel.setPercent(percent) },
                    activeColor = sideColor,
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        CsDivider()
        Spacer(modifier = Modifier.height(10.dp))

        CsStatRow(
            label = "可用余额",
            value = state.account.cash.money(2) + " USDT",
        )
        CsStatRow(
            label = "所需保证金",
            value = state.preview.margin.money(2) + " USDT",
        )
        CsStatRow(
            label = "开仓数量",
            value = state.preview.quantity.quantity(info?.quantityPrecision ?: 3) + " $baseAsset",
        )
        CsStatRow(
            label = "手续费",
            value = state.preview.fee.money(2) + " USDT",
        )
        CsStatRow(
            label = "预计强平价",
            value = if (state.preview.liquidationPrice.signum() > 0) {
                state.preview.liquidationPrice.price(info?.pricePrecision ?: 2)
            } else {
                "--"
            },
            valueColor = if (state.side == Side.LONG) CsColors.Down else CsColors.Up,
        )

        val errorText = previewErrorText(state.preview.error)
        if (errorText != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = errorText, color = CsColors.Down, fontSize = 11.sp)
        }

        if (state.leverage >= 20) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "高杠杆会放大收益和亏损，请控制风险",
                color = CsColors.Warning,
                fontSize = 10.sp,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        CsPrimaryButton(
            text = if (state.side == Side.LONG) "做多 $baseAsset" else "做空 $baseAsset",
            onClick = onSubmit,
            enabled = state.preview.canSubmit,
            color = sideColor,
        )
    }
}

@Composable
private fun PositionsSection(
    state: TradeUiState,
    viewModel: TradeViewModel,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "当前持仓",
                color = CsColors.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${state.positions.size} 个",
                color = CsColors.TextFaint,
                fontSize = 10.sp,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (state.positions.isEmpty()) {
            CsEmptyState(title = "暂无持仓", hint = "在上方选择方向并开仓")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.positions.forEach { marked ->
                    PositionMiniCard(
                        marked = marked,
                        priceScale = state.symbolInfo?.pricePrecision ?: 2,
                        quantityScale = state.symbolInfo?.quantityPrecision ?: 3,
                        onClose = { viewModel.openCloseSheet(marked) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PositionMiniCard(
    marked: MarkedPosition,
    priceScale: Int,
    quantityScale: Int,
    onClose: () -> Unit,
) {
    val position = marked.position
    val pnlColor = if (marked.unrealizedPnl.signum() >= 0) CsColors.Up else CsColors.Down
    CsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoinAvatar(baseAsset = position.baseAsset, size = 30.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = position.baseAsset,
                        color = CsColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    SideBadge(side = position.side, leverage = position.leverage)
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = position.quantity.quantity(quantityScale) + " " + position.baseAsset,
                    color = CsColors.TextFaint,
                    fontSize = 10.sp,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = marked.unrealizedPnl.signed(2) + " USDT",
                    color = pnlColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                TextButton(onClick = onClose) {
                    Text(
                        text = if (position.side == Side.LONG) "平多" else "平空",
                        color = if (position.side == Side.LONG) CsColors.Down else CsColors.Up,
                        fontSize = 11.sp,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
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
            LabelValue(
                label = "保证金",
                value = position.margin.money(2),
                modifier = Modifier.weight(1f),
            )
            LabelValue(
                label = "强平价",
                value = position.liquidationPrice.price(priceScale),
                valueColor = CsColors.Down,
                modifier = Modifier.weight(1f),
            )
        }
    }
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
    TradeError.InsufficientBalance -> "可用余额不足"
    TradeError.OppositePositionExists -> "该币种已有反向持仓，请先平仓"
    TradeError.LeverageMismatch -> "同方向加仓请使用相同杠杆"
    TradeError.PositionNotFound -> "持仓不存在"
    TradeError.QuantityTooSmall -> "数量过小"
    is TradeError.Unknown -> error.message
}
