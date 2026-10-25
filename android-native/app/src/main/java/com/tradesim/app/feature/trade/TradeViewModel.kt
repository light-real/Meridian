package com.tradesim.app.feature.trade

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradesim.app.core.AppConstants
import com.tradesim.app.core.data.market.MarketDataHub
import com.tradesim.app.core.data.repository.AccountRepository
import com.tradesim.app.core.data.repository.MarketRepository
import com.tradesim.app.core.data.settings.SettingsStore
import com.tradesim.app.core.domain.OrderCalculator
import com.tradesim.app.core.model.Account
import com.tradesim.app.core.model.AmountMode
import com.tradesim.app.core.model.ClosePreview
import com.tradesim.app.core.model.FundingRate
import com.tradesim.app.core.model.LimitOrder
import com.tradesim.app.core.model.MarginMode
import com.tradesim.app.core.model.MarkedPosition
import com.tradesim.app.core.model.OrderBook
import com.tradesim.app.core.model.OrderPreview
import com.tradesim.app.core.model.OrderType
import com.tradesim.app.core.model.Position
import com.tradesim.app.core.model.Side
import com.tradesim.app.core.model.SymbolInfo
import com.tradesim.app.core.model.Ticker
import com.tradesim.app.core.model.TradeError
import com.tradesim.app.core.model.TradeOutcome
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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
    val funding: FundingRate? = null,
    val account: Account = Account(AppConstants.INITIAL_CASH, AppConstants.INITIAL_CASH),
    val side: Side = Side.LONG,
    val leverage: Int = AppConstants.DEFAULT_LEVERAGE,
    val marginMode: MarginMode = MarginMode.ISOLATED,
    val amountInput: String = "",
    val orderType: OrderType = OrderType.MARKET,
    val limitPriceInput: String = "",
    val takeProfitInput: String = "",
    val stopLossInput: String = "",
    val preview: OrderPreview = OrderPreview(),
    val loading: Boolean = true,
    val error: String? = null,
    val notice: String? = null,
    val symbols: List<SymbolInfo> = emptyList(),
    val symbolPickerVisible: Boolean = false,
    val symbolQuery: String = "",
    val closeSheet: CloseSheetState? = null,
)

class TradeViewModel(
    initialSymbol: String,
    private val marketRepository: MarketRepository,
    private val accountRepository: AccountRepository,
    private val settingsStore: SettingsStore,
    private val marketDataHub: MarketDataHub,
) : ViewModel() {

    private val initialSymbol = (
        initialSymbol.takeIf { it.isNotBlank() }
            ?: settingsStore.lastSymbol
        ).uppercase()

    private val symbolFlow = MutableStateFlow(initialSymbol)

    private val _uiState = MutableStateFlow(
        TradeUiState(
            symbol = initialSymbol,
            leverage = settingsStore.lastLeverage,
        ),
    )
    val uiState: StateFlow<TradeUiState> = _uiState.asStateFlow()

    /** 盘口从统一的价格中枢拿，避免每个页面各开一条连接。 */
    val orderBook: StateFlow<OrderBook?> = marketDataHub.orderBook

    /** 仓位单独一个 flow：每秒更新时只重组仓位列表，不牵连下单表单。 */
    private val _positions = MutableStateFlow<List<MarkedPosition>>(emptyList())
    val positions: StateFlow<List<MarkedPosition>> = _positions.asStateFlow()

    init {
        marketDataHub.setActiveSymbol(initialSymbol)

        // 持仓浮盈直接读中枢的标记价，三个页面同一份数据
        viewModelScope.launch {
            combine(accountRepository.account, marketDataHub.marks) { account, marks ->
                account to account.markedPositions(marks)
            }.collect { (account, positions) ->
                _uiState.update { state ->
                    if (state.account == account) state else state.copy(account = account)
                }
                if (positions != _positions.value) {
                    _positions.value = positions
                }
            }
        }

        viewModelScope.launch {
            symbolFlow.collectLatest { symbol -> loadMarket(symbol) }
        }

        // 这台网络下 fstream 的 ticker 流收不到数据，只保留 REST：
        // 顶部价格 3 秒一次。盘口由 MarketDataHub 统一提供。
        viewModelScope.launch {
            while (isActive) {
                delay(3_000L)
                val symbol = symbolFlow.value
                runCatching { marketRepository.ticker(symbol) }
                    .getOrNull()
                    ?.let { ticker -> onTicker(ticker) }
            }
        }

        // 资金费率 30 秒刷一次就够了，它只影响展示和 8 小时一次的结算
        viewModelScope.launch {
            while (isActive) {
                val symbol = symbolFlow.value
                runCatching { marketRepository.fundingRate(symbol) }
                    .getOrNull()
                    ?.let { rate ->
                        _uiState.update { state ->
                            if (state.symbol == symbol) state.copy(funding = rate) else state
                        }
                    }
                delay(30_000L)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            loadMarket(symbolFlow.value)
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
        marketDataHub.setActiveSymbol(normalized)
        _uiState.update {
            it.copy(
                symbol = normalized,
                symbolInfo = null,
                ticker = null,
                funding = null,
                amountInput = "",
                preview = OrderPreview(),
                symbolPickerVisible = false,
                symbolQuery = "",
                closeSheet = null,
                loading = true,
                error = null,
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

    fun setMarginMode(mode: MarginMode) {
        if (mode == _uiState.value.marginMode) return
        _uiState.update { it.copy(marginMode = mode) }
        recalcOrder()
    }

    fun setAmountInput(input: String) {
        _uiState.update { it.copy(amountInput = input) }
        recalcOrder()
    }

    fun setTakeProfitInput(input: String) {
        _uiState.update { it.copy(takeProfitInput = input) }
    }

    fun setOrderType(type: OrderType) {
        if (type == _uiState.value.orderType) return
        _uiState.update {
            it.copy(
                orderType = type,
                // 切到限价时用当前价预填，方便微调
                limitPriceInput = if (type == OrderType.LIMIT) {
                    it.limitPriceInput.ifBlank {
                        it.ticker?.lastPrice
                            ?.setScale(it.symbolInfo?.pricePrecision ?: 2, RoundingMode.HALF_UP)
                            ?.toPlainString()
                            .orEmpty()
                    }
                } else {
                    it.limitPriceInput
                },
            )
        }
        recalcOrder()
    }

    fun setLimitPriceInput(input: String) {
        _uiState.update { it.copy(limitPriceInput = input) }
        recalcOrder()
    }

    fun cancelLimitOrder(orderId: String) {
        viewModelScope.launch {
            accountRepository.cancelLimitOrder(orderId)
            _uiState.update { it.copy(notice = "已撤单") }
        }
    }

    fun setStopLossInput(input: String) {
        _uiState.update { it.copy(stopLossInput = input) }
    }

    fun updateTriggers(positionId: String, takeProfit: BigDecimal?, stopLoss: BigDecimal?) {
        viewModelScope.launch {
            accountRepository.updateTriggers(positionId, takeProfit, stopLoss)
            _uiState.update { it.copy(notice = "止盈止损已更新") }
        }
    }

    fun addMargin(positionId: String, amount: BigDecimal) {
        viewModelScope.launch {
            val error = accountRepository.addMargin(positionId, amount)
            _uiState.update {
                it.copy(
                    notice = when (error) {
                        null -> "已追加保证金 ${amount.setScale(2, RoundingMode.DOWN)} USDT"
                        TradeError.InsufficientBalance -> "可用余额不足"
                        TradeError.MarginModeMismatch -> "全仓仓位不需要追加保证金"
                        TradeError.PositionNotFound -> "持仓不存在"
                        else -> "追加保证金失败"
                    },
                )
            }
        }
    }

    fun placeOrder() {
        val state = _uiState.value
        val info = state.symbolInfo ?: return
        val price = state.ticker?.lastPrice ?: return
        if (!state.preview.canSubmit) return

        if (state.orderType == OrderType.LIMIT) {
            val limitPrice = state.limitPriceInput.toBigDecimalOrNull() ?: return
            if (limitPrice.signum() <= 0) return
            viewModelScope.launch {
                val frozen = if (state.marginMode == MarginMode.CROSS) {
                    state.preview.fee
                } else {
                    state.preview.requiredCash
                }
                val ok = accountRepository.placeLimitOrder(
                    order = LimitOrder(
                        id = java.util.UUID.randomUUID().toString(),
                        symbol = info.symbol,
                        baseAsset = info.baseAsset,
                        side = state.side,
                        notional = state.preview.notional,
                        limitPrice = limitPrice,
                        leverage = state.leverage,
                        marginMode = state.marginMode,
                        takeProfitPrice = state.takeProfitInput.toBigDecimalOrNull(),
                        stopLossPrice = state.stopLossInput.toBigDecimalOrNull(),
                    ),
                    frozenCash = frozen,
                )
                _uiState.update {
                    if (ok) {
                        it.copy(
                            amountInput = "",
                            takeProfitInput = "",
                            stopLossInput = "",
                            notice = "限价委托已挂出，冻结 ${frozen.setScale(2, RoundingMode.DOWN)} USDT",
                        )
                    } else {
                        it.copy(notice = "可用余额不足，挂单失败")
                    }
                }
            }
            return
        }

        viewModelScope.launch {
            when (
                val outcome = accountRepository.openPosition(
                    symbol = info,
                    side = state.side,
                    notional = state.preview.notional,
                    leverage = state.leverage,
                    markPrice = price,
                    marginMode = state.marginMode,
                    takeProfitPrice = state.takeProfitInput.toBigDecimalOrNull(),
                    stopLossPrice = state.stopLossInput.toBigDecimalOrNull(),
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
        _uiState.update {
            it.copy(
                closeSheet = CloseSheetState(
                    position = marked.position,
                    markPrice = marked.markPrice,
                    quantityInput = "0",
                    preview = OrderCalculator.previewClose(
                        position = marked.position,
                        requestedQuantity = BigDecimal.ZERO,
                        markPrice = marked.markPrice,
                    ),
                ),
            )
        }
    }

    fun setCloseQuantity(input: String) {
        val sheet = _uiState.value.closeSheet ?: return
        // 默认值是 "0"，继续输入时把前导 0 去掉，避免出现 "0100"
        val normalized = input.trimStart('0').let {
            when {
                it.isEmpty() -> "0"
                it.startsWith(".") -> "0$it"
                else -> it
            }
        }
        val amount = normalized.toBigDecimalOrNull() ?: BigDecimal.ZERO
        // 输入的是 USDT 金额，按标记价折算成平仓数量
        val quantity = if (sheet.markPrice.signum() > 0) {
            amount.divide(sheet.markPrice, 8, RoundingMode.DOWN)
        } else {
            BigDecimal.ZERO
        }
        val preview = OrderCalculator.previewClose(sheet.position, quantity, sheet.markPrice)
        _uiState.update {
            it.copy(closeSheet = sheet.copy(quantityInput = normalized, preview = preview))
        }
    }

    fun setClosePercent(percent: BigDecimal) {
        val sheet = _uiState.value.closeSheet ?: return
        val quantity = sheet.position.quantity.multiply(percent)
            .setScale(8, RoundingMode.DOWN)
            .stripTrailingZeros()
            .toPlainString()
        val amount = quantity.toBigDecimalOrNull()
            ?.multiply(sheet.markPrice)
            ?.setScale(2, RoundingMode.DOWN)
            ?.toPlainString()
            .orEmpty()
        val preview = OrderCalculator.previewClose(
            position = sheet.position,
            requestedQuantity = quantity.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            markPrice = sheet.markPrice,
        )
        _uiState.update {
            it.copy(closeSheet = sheet.copy(quantityInput = amount, preview = preview))
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

    private suspend fun loadMarket(symbol: String) {
        _uiState.update { it.copy(loading = true, error = null) }

        val symbols = runCatching { marketRepository.symbols() }.getOrElse { emptyList() }
        if (symbolFlow.value != symbol) return
        val info = symbols.firstOrNull { it.symbol == symbol }
        val ticker = runCatching { marketRepository.ticker(symbol) }.getOrNull()
        if (symbolFlow.value != symbol) return

        _uiState.update { state ->
            if (state.symbol != symbol) {
                state
            } else {
                state.copy(
                    symbolInfo = info ?: state.symbolInfo,
                    symbols = symbols,
                    ticker = ticker ?: state.ticker,
                    loading = false,
                    error = if (info == null) "未找到该交易对" else null,
                )
            }
        }
        recalcOrder()
    }

    private fun onTicker(ticker: Ticker) {
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
                    closeSheet = sheet,
                )
            }
        }
        recalcOrder()
    }

    private fun recalcOrder() {
        val state = _uiState.value
        val info = state.symbolInfo ?: return
        val marketPrice = state.ticker?.lastPrice ?: BigDecimal.ZERO
        val limitPrice = state.limitPriceInput.toBigDecimalOrNull()
        // 限价单的保证金按限价算，不然预览和实际占用会对不上
        val price = if (state.orderType == OrderType.LIMIT && limitPrice != null) {
            limitPrice
        } else {
            marketPrice
        }
        val amount = state.amountInput.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val preview = OrderCalculator.preview(
            account = state.account,
            side = state.side,
            leverage = state.leverage,
            mode = AmountMode.NOTIONAL,
            amount = amount,
            markPrice = price,
            rules = info.rules(),
            marginMode = state.marginMode,
        )
        _uiState.update { it.copy(preview = preview) }
    }

    private fun tradeErrorMessage(error: TradeError): String = when (error) {
        TradeError.InvalidLeverage -> "杠杆倍数需在 1-150 之间"
        TradeError.InvalidAmount -> "请输入有效金额"
        TradeError.BelowMinNotional -> "金额低于最小下单金额"
        TradeError.InsufficientBalance -> "保证金 + 手续费超出可用余额"
        TradeError.OppositePositionExists -> "该币种已有反向持仓，请先平仓"
        TradeError.LeverageMismatch -> "同方向加仓请使用相同杠杆"
        TradeError.MarginModeMismatch -> "同方向加仓请使用相同的保证金模式"
        TradeError.InvalidTriggerPrice -> "止盈/止损价方向不对"
        TradeError.PositionNotFound -> "持仓不存在"
        TradeError.QuantityTooSmall -> "数量过小"
        is TradeError.Unknown -> error.message
    }

    override fun onCleared() {
        marketDataHub.clearActiveSymbol(symbolFlow.value)
        super.onCleared()
    }
}
