package com.tradesim.app.feature.trade

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradesim.app.core.AppConstants
import com.tradesim.app.core.data.repository.AccountRepository
import com.tradesim.app.core.data.repository.MarketRepository
import com.tradesim.app.core.data.settings.SettingsStore
import com.tradesim.app.core.domain.OrderCalculator
import com.tradesim.app.core.model.Account
import com.tradesim.app.core.model.AmountMode
import com.tradesim.app.core.model.ClosePreview
import com.tradesim.app.core.model.Kline
import com.tradesim.app.core.model.MarkedPosition
import com.tradesim.app.core.model.OrderBook
import com.tradesim.app.core.model.OrderPreview
import com.tradesim.app.core.model.Position
import com.tradesim.app.core.model.Side
import com.tradesim.app.core.model.SymbolInfo
import com.tradesim.app.core.model.Ticker
import com.tradesim.app.core.model.TradeError
import com.tradesim.app.core.model.TradeOutcome
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CloseSheetState(
    val position: Position,
    val markPrice: BigDecimal,
    val quantityInput: String,
    val preview: ClosePreview,
)

data class TradeUiState(
    val symbol: String,
    val symbolInfo: SymbolInfo? = null,
    val ticker: Ticker? = null,
    val klines: List<Kline> = emptyList(),
    val interval: String = "5m",
    val showMA5: Boolean = false,
    val showMA10: Boolean = false,
    val showVolume: Boolean = false,
    val chartExpanded: Boolean = true,
    val account: Account = Account(AppConstants.INITIAL_CASH, AppConstants.INITIAL_CASH),
    val positions: List<MarkedPosition> = emptyList(),
    val orderBook: OrderBook? = null,
    val side: Side = Side.LONG,
    val leverage: Int = AppConstants.DEFAULT_LEVERAGE,
    val amountMode: AmountMode = AmountMode.MARGIN,
    val amountInput: String = "",
    val preview: OrderPreview = OrderPreview(),
    val loading: Boolean = true,
    val error: String? = null,
    val notice: String? = null,
    val symbols: List<SymbolInfo> = emptyList(),
    val symbolPickerVisible: Boolean = false,
    val symbolQuery: String = "",
    val closeSheet: CloseSheetState? = null,
    val selectedKline: Kline? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TradeViewModel(
    initialSymbol: String,
    private val marketRepository: MarketRepository,
    private val accountRepository: AccountRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val initialSymbol = (
        initialSymbol.takeIf { it.isNotBlank() }
            ?: settingsStore.lastSymbol
        ).uppercase()

    private val symbolFlow = MutableStateFlow(initialSymbol)
    private val intervalFlow = MutableStateFlow(settingsStore.lastInterval)

    private val _uiState = MutableStateFlow(
        TradeUiState(
            symbol = initialSymbol,
            interval = settingsStore.lastInterval,
            leverage = settingsStore.lastLeverage,
            chartExpanded = settingsStore.klineExpanded,
        ),
    )
    val uiState: StateFlow<TradeUiState> = _uiState.asStateFlow()

    private var lastLiquidationCheck = 0L

    @Volatile
    private var lastTickerAt = 0L

    init {
        viewModelScope.launch {
            accountRepository.account.collect { account ->
                _uiState.update { state ->
                    val marks = state.ticker?.let { mapOf(state.symbol to it.lastPrice) } ?: emptyMap()
                    state.copy(
                        account = account,
                        positions = account.markedPositions(marks)
                            .filter { it.position.symbol == state.symbol },
                    )
                }
            }
        }

        viewModelScope.launch {
            symbolFlow
                .flatMapLatest { symbol -> marketRepository.tickerStream(listOf(symbol)) }
                .catch { error ->
                    _uiState.update { it.copy(error = error.message ?: "行情连接失败") }
                }
                .collect { ticker -> onTicker(ticker) }
        }

        viewModelScope.launch {
            symbolFlow
                .flatMapLatest { symbol -> marketRepository.depthStream(symbol) }
                .catch { }
                .collect { orderBook ->
                    _uiState.update { it.copy(orderBook = orderBook) }
                }
        }

        viewModelScope.launch {
            combine(symbolFlow, intervalFlow) { symbol, interval -> symbol to interval }
                .distinctUntilChanged()
                .flatMapLatest { (symbol, interval) ->
                    marketRepository.klineStream(symbol, interval)
                }
                .catch { }
                .collect { kline -> mergeKline(kline) }
        }

        viewModelScope.launch {
            combine(symbolFlow, intervalFlow) { symbol, interval -> symbol to interval }
                .distinctUntilChanged()
                .collectLatest { (symbol, interval) -> loadMarket(symbol, interval) }
        }

        // WebSocket 不可用时（部分网络会拒绝 fstream），用单 symbol REST 兜底，
        // 保证交易页价格和强平检测仍然能更新。
        viewModelScope.launch {
            while (isActive) {
                delay(2_000L)
                if (System.currentTimeMillis() - lastTickerAt > 3_000L) {
                    runCatching { marketRepository.ticker(symbolFlow.value) }
                        .getOrNull()
                        ?.let { onTicker(it) }
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            loadMarket(symbolFlow.value, intervalFlow.value)
        }
    }

    fun selectSymbol(symbol: String) {
        val normalized = symbol.uppercase()
        if (normalized == symbolFlow.value) {
            _uiState.update { it.copy(symbolPickerVisible = false) }
            return
        }
        settingsStore.lastSymbol = normalized
        symbolFlow.value = normalized
        _uiState.update {
            it.copy(
                symbol = normalized,
                symbolInfo = null,
                ticker = null,
                klines = emptyList(),
                orderBook = null,
                positions = emptyList(),
                amountInput = "",
                preview = OrderPreview(),
                symbolPickerVisible = false,
                symbolQuery = "",
                closeSheet = null,
                selectedKline = null,
                loading = true,
                error = null,
            )
        }
    }

    fun setInterval(interval: String) {
        if (interval == intervalFlow.value) return
        settingsStore.lastInterval = interval
        intervalFlow.value = interval
        _uiState.update {
            it.copy(
                interval = interval,
                klines = emptyList(),
                selectedKline = null,
                loading = true,
            )
        }
    }

    fun setSide(side: Side) {
        _uiState.update { it.copy(side = side) }
        recalcOrder()
    }

    fun setLeverage(leverage: Int) {
        val value = leverage.coerceIn(1, AppConstants.MAX_LEVERAGE)
        settingsStore.lastLeverage = value
        _uiState.update { it.copy(leverage = value) }
        recalcOrder()
    }

    fun setAmountMode(mode: AmountMode) {
        if (mode == _uiState.value.amountMode) return
        _uiState.update { it.copy(amountMode = mode, amountInput = "") }
        recalcOrder()
    }

    fun setAmountInput(input: String) {
        _uiState.update { it.copy(amountInput = input) }
        recalcOrder()
    }

    fun setPercent(percent: BigDecimal) {
        val state = _uiState.value
        val amount = OrderCalculator.amountForPercent(
            account = state.account,
            leverage = state.leverage,
            mode = state.amountMode,
            percent = percent,
        )
        _uiState.update {
            it.copy(amountInput = amount.setScale(2, RoundingMode.DOWN).toPlainString())
        }
        recalcOrder()
    }

    fun placeOrder() {
        val state = _uiState.value
        val info = state.symbolInfo ?: return
        val price = state.ticker?.lastPrice ?: return
        if (!state.preview.canSubmit) return

        viewModelScope.launch {
            when (
                val outcome = accountRepository.openPosition(
                    symbol = info,
                    side = state.side,
                    notional = state.preview.notional,
                    leverage = state.leverage,
                    markPrice = price,
                )
            ) {
                is TradeOutcome.Success -> {
                    _uiState.update {
                        it.copy(
                            amountInput = "",
                            preview = OrderPreview(),
                            notice = "开仓成功",
                        )
                    }
                }
                is TradeOutcome.Rejected -> {
                    _uiState.update { it.copy(notice = tradeErrorMessage(outcome.error)) }
                }
            }
        }
    }

    fun toggleMA5() {
        _uiState.update { it.copy(showMA5 = !it.showMA5) }
    }

    fun toggleMA10() {
        _uiState.update { it.copy(showMA10 = !it.showMA10) }
    }

    fun toggleVolume() {
        _uiState.update { it.copy(showVolume = !it.showVolume) }
    }

    fun toggleChart() {
        val expanded = !_uiState.value.chartExpanded
        settingsStore.klineExpanded = expanded
        _uiState.update { it.copy(chartExpanded = expanded) }
    }

    fun setSelectedKline(kline: Kline?) {
        _uiState.update { it.copy(selectedKline = kline) }
    }

    fun showSymbolPicker(visible: Boolean) {
        _uiState.update {
            it.copy(
                symbolPickerVisible = visible,
                symbolQuery = if (visible) it.symbolQuery else "",
            )
        }
    }

    fun setSymbolQuery(query: String) {
        _uiState.update { it.copy(symbolQuery = query) }
    }

    fun openCloseSheet(marked: MarkedPosition) {
        val quantity = marked.position.quantity.setScale(8, RoundingMode.DOWN)
            .stripTrailingZeros()
            .toPlainString()
        val preview = OrderCalculator.previewClose(
            position = marked.position,
            requestedQuantity = marked.position.quantity,
            markPrice = marked.markPrice,
        )
        _uiState.update {
            it.copy(
                closeSheet = CloseSheetState(
                    position = marked.position,
                    markPrice = marked.markPrice,
                    quantityInput = quantity,
                    preview = preview,
                ),
            )
        }
    }

    fun setCloseQuantity(input: String) {
        val sheet = _uiState.value.closeSheet ?: return
        val quantity = input.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val preview = OrderCalculator.previewClose(sheet.position, quantity, sheet.markPrice)
        _uiState.update {
            it.copy(closeSheet = sheet.copy(quantityInput = input, preview = preview))
        }
    }

    fun setClosePercent(percent: BigDecimal) {
        val sheet = _uiState.value.closeSheet ?: return
        val quantity = sheet.position.quantity.multiply(percent)
            .setScale(8, RoundingMode.DOWN)
            .stripTrailingZeros()
            .toPlainString()
        val preview = OrderCalculator.previewClose(
            position = sheet.position,
            requestedQuantity = quantity.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            markPrice = sheet.markPrice,
        )
        _uiState.update {
            it.copy(closeSheet = sheet.copy(quantityInput = quantity, preview = preview))
        }
    }

    fun confirmClose() {
        val sheet = _uiState.value.closeSheet ?: return
        if (!sheet.preview.canClose) return
        viewModelScope.launch {
            when (
                val outcome = accountRepository.closePosition(
                    positionId = sheet.position.id,
                    quantity = sheet.preview.quantity,
                    markPrice = sheet.markPrice,
                )
            ) {
                is TradeOutcome.Success -> {
                    _uiState.update {
                        it.copy(
                            closeSheet = null,
                            notice = if (outcome.clamped) {
                                "已按最大可平数量平仓"
                            } else {
                                "平仓成功"
                            },
                        )
                    }
                }
                is TradeOutcome.Rejected -> {
                    _uiState.update { it.copy(notice = tradeErrorMessage(outcome.error)) }
                }
            }
        }
    }

    fun dismissCloseSheet() {
        _uiState.update { it.copy(closeSheet = null) }
    }

    fun consumeNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    private suspend fun loadMarket(symbol: String, interval: String) {
        _uiState.update { it.copy(loading = true, error = null) }

        val symbols = runCatching { marketRepository.symbols() }.getOrElse { emptyList() }
        if (symbolFlow.value != symbol) return
        val info = symbols.firstOrNull { it.symbol == symbol }
        val ticker = runCatching { marketRepository.ticker(symbol) }.getOrNull()
        val klines = runCatching {
            marketRepository.klines(symbol, interval, 200)
        }.getOrElse { emptyList() }
        if (symbolFlow.value != symbol) return
        if (ticker != null) lastTickerAt = System.currentTimeMillis()

        _uiState.update { state ->
            if (state.symbol != symbol) {
                state
            } else {
                state.copy(
                    symbolInfo = info ?: state.symbolInfo,
                    symbols = symbols,
                    ticker = ticker ?: state.ticker,
                    klines = klines,
                    loading = false,
                    error = if (info == null) "未找到该交易对" else null,
                )
            }
        }
        recalcOrder()
    }

    private fun onTicker(ticker: Ticker) {
        lastTickerAt = System.currentTimeMillis()
        val marks = mapOf(ticker.symbol to ticker.lastPrice)
        _uiState.update { state ->
            if (state.symbol != ticker.symbol) {
                state
            } else {
                val sheet = state.closeSheet?.let { current ->
                    val preview = OrderCalculator.previewClose(
                        position = current.position,
                        requestedQuantity = current.quantityInput.toBigDecimalOrNull()
                            ?: BigDecimal.ZERO,
                        markPrice = ticker.lastPrice,
                    )
                    current.copy(markPrice = ticker.lastPrice, preview = preview)
                }
                state.copy(
                    ticker = ticker,
                    orderBook = state.orderBook,
                    positions = state.account.markedPositions(marks)
                        .filter { it.position.symbol == state.symbol },
                    closeSheet = sheet,
                )
            }
        }
        recalcOrder()
        accountRepository.updateMarks(marks)

        val now = System.currentTimeMillis()
        if (now - lastLiquidationCheck >= 1_000L) {
            lastLiquidationCheck = now
            viewModelScope.launch {
                val liquidated = accountRepository.checkLiquidations(marks)
                if (liquidated.isNotEmpty()) {
                    val names = liquidated.joinToString("、") { it.baseAsset }
                    _uiState.update { it.copy(notice = "$names 仓位已触发强制平仓") }
                }
            }
        }
    }

    private fun recalcOrder() {
        val state = _uiState.value
        val info = state.symbolInfo ?: return
        val price = state.ticker?.lastPrice ?: BigDecimal.ZERO
        val amount = state.amountInput.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val preview = OrderCalculator.preview(
            account = state.account,
            side = state.side,
            leverage = state.leverage,
            mode = state.amountMode,
            amount = amount,
            markPrice = price,
            rules = info.rules(),
        )
        _uiState.update { it.copy(preview = preview) }
    }

    private fun mergeKline(kline: Kline) {
        _uiState.update { state ->
            if (state.klines.isEmpty()) {
                state.copy(klines = listOf(kline))
            } else {
                val list = state.klines.toMutableList()
                val last = list.last()
                when {
                    kline.openTime == last.openTime -> list[list.lastIndex] = kline
                    kline.openTime > last.openTime -> {
                        list.add(kline)
                        if (list.size > 300) list.removeAt(0)
                    }
                }
                state.copy(klines = list)
            }
        }
    }

    private fun tradeErrorMessage(error: TradeError): String = when (error) {
        TradeError.InvalidLeverage -> "杠杆倍数需在 1-150 之间"
        TradeError.InvalidAmount -> "请输入有效金额"
        TradeError.BelowMinNotional -> "金额低于最小下单金额"
        TradeError.InsufficientBalance -> "可用余额不足"
        TradeError.OppositePositionExists -> "该币种已有反向持仓，请先平仓"
        TradeError.LeverageMismatch -> "同方向加仓请使用相同杠杆"
        TradeError.PositionNotFound -> "持仓不存在"
        TradeError.QuantityTooSmall -> "数量过小"
        is TradeError.Unknown -> error.message
    }
}
