package com.meridian.app.feature.ashare

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meridian.app.core.data.repository.AShareAccountRepository
import com.meridian.app.core.data.repository.AShareFavoritesRepository
import com.meridian.app.core.data.repository.AShareMarketRepository
import com.meridian.app.core.domain.AShareTradingEngine
import com.meridian.app.core.model.AShareAccount
import com.meridian.app.core.model.AShareCalendar
import com.meridian.app.core.model.AShareFees
import com.meridian.app.core.model.AShareOrderRequest
import com.meridian.app.core.model.AShareSide
import com.meridian.app.core.model.AShareStock
import com.meridian.app.core.model.AShareTradeError
import com.meridian.app.core.model.AShareTradeResult
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class AShareOrderPreview(
    val quantity: Int = 0,
    val price: BigDecimal = BigDecimal.ZERO,
    val amount: BigDecimal = BigDecimal.ZERO,
    val commission: BigDecimal = BigDecimal.ZERO,
    val stampDuty: BigDecimal = BigDecimal.ZERO,
    val transferFee: BigDecimal = BigDecimal.ZERO,
    val totalFee: BigDecimal = BigDecimal.ZERO,
    val netCash: BigDecimal = BigDecimal.ZERO,
    val maxBuyQuantity: Int = 0,
    val availableToSell: Int = 0,
    val canSubmit: Boolean = false,
    val error: AShareTradeError? = null,
)

data class AShareTradeUiState(
    val symbol: String,
    val stock: AShareStock? = null,
    val loading: Boolean = true,
    val side: AShareSide = AShareSide.BUY,
    val quantityInput: String = "",
    val account: AShareAccount = AShareAccount(),
    val preview: AShareOrderPreview = AShareOrderPreview(),
    val sessionOpen: Boolean = AShareCalendar.isTradingSession(),
    val error: String? = null,
    val notice: String? = null,
    val submitting: Boolean = false,
    val isFavorite: Boolean = false,
)

class AShareTradeViewModel(
    private val symbol: String,
    private val marketRepository: AShareMarketRepository,
    private val accountRepository: AShareAccountRepository,
    private val favoritesRepository: AShareFavoritesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AShareTradeUiState(
            symbol = symbol,
            sessionOpen = AShareCalendar.isTradingSession(),
        ),
    )
    val uiState: StateFlow<AShareTradeUiState> = _uiState.asStateFlow()

    private var lastSettleCheck = 0L

    init {
        viewModelScope.launch {
            favoritesRepository.favorites.collect { favorites ->
                _uiState.update { it.copy(isFavorite = favorites.contains(symbol)) }
            }
        }
        viewModelScope.launch {
            accountRepository.account.collect { account ->
                _uiState.update { it.copy(account = account) }
                recalc()
            }
        }
        viewModelScope.launch {
            accountRepository.settleIfNeeded()
            lastSettleCheck = System.currentTimeMillis()
            loadQuote()
        }
        viewModelScope.launch {
            while (isActive) {
                val interval = if (AShareCalendar.isTradingSession()) 3_000L else 30_000L
                delay(interval)
                val now = System.currentTimeMillis()
                if (now - lastSettleCheck >= 60_000L) {
                    lastSettleCheck = now
                    accountRepository.settleIfNeeded()
                }
                loadQuote()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { loadQuote() }
    }

    fun setSide(side: AShareSide) {
        if (side == _uiState.value.side) return
        _uiState.update { it.copy(side = side, quantityInput = "") }
        recalc()
    }

    fun setQuantityInput(input: String) {
        val filtered = input.filter { it.isDigit() }.take(9)
        _uiState.update { it.copy(quantityInput = filtered) }
        recalc()
    }

    fun setQuantity(quantity: Int) {
        _uiState.update { it.copy(quantityInput = quantity.toString()) }
        recalc()
    }

    fun setMaxQuantity() {
        val preview = _uiState.value.preview
        val quantity = if (_uiState.value.side == AShareSide.BUY) {
            preview.maxBuyQuantity
        } else {
            preview.availableToSell
        }
        if (quantity > 0) setQuantity(quantity)
    }

    fun submit() {
        val state = _uiState.value
        val stock = state.stock ?: return
        if (!state.preview.canSubmit || state.submitting) return
        val quantity = state.preview.quantity
        val request = AShareOrderRequest(
            symbol = stock.symbol,
            name = stock.name,
            secid = stock.secid,
            side = state.side,
            quantity = quantity,
            price = stock.price,
            prevClose = stock.prevClose,
        )

        viewModelScope.launch {
            _uiState.update { it.copy(submitting = true) }
            val result = if (state.side == AShareSide.BUY) {
                accountRepository.buy(request)
            } else {
                accountRepository.sell(request)
            }
            when (result) {
                is AShareTradeResult.Success -> {
                    _uiState.update {
                        it.copy(
                            submitting = false,
                            quantityInput = "",
                            notice = if (state.side == AShareSide.BUY) {
                                "买入 ${stock.name} ${quantity} 股成功"
                            } else {
                                "卖出 ${stock.name} ${quantity} 股成功"
                            },
                        )
                    }
                }
                is AShareTradeResult.Rejected -> {
                    _uiState.update {
                        it.copy(
                            submitting = false,
                            notice = errorText(result.error),
                        )
                    }
                }
            }
            recalc()
        }
    }

    fun consumeNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    fun toggleFavorite() {
        favoritesRepository.toggle(symbol)
    }

    private suspend fun loadQuote() {
        runCatching { marketRepository.quote(symbol) }
            .onSuccess { stock ->
                if (stock != null) {
                    _uiState.update {
                        it.copy(
                            stock = stock,
                            loading = false,
                            error = null,
                            sessionOpen = AShareCalendar.isTradingSession(),
                        )
                    }
                    recalc()
                }
            }
            .onFailure { error ->
                _uiState.update {
                    it.copy(
                        loading = false,
                        error = error.message ?: "A 股行情加载失败",
                        sessionOpen = AShareCalendar.isTradingSession(),
                    )
                }
            }
    }

    private fun recalc() {
        val state = _uiState.value
        val stock = state.stock
        val price = stock?.price ?: BigDecimal.ZERO
        val quantity = state.quantityInput.toIntOrNull() ?: 0
        val amount = price.multiply(BigDecimal(quantity))
            .setScale(2, RoundingMode.HALF_UP)
        val commission = if (quantity > 0 && price.signum() > 0) {
            AShareFees.commission(amount)
        } else {
            BigDecimal.ZERO
        }
        val stampDuty = if (quantity > 0 && price.signum() > 0) {
            AShareFees.stampDuty(amount, state.side)
        } else {
            BigDecimal.ZERO
        }
        val transferFee = if (quantity > 0 && price.signum() > 0) {
            AShareFees.transferFee(amount)
        } else {
            BigDecimal.ZERO
        }
        val totalFee = commission.add(stampDuty).add(transferFee)
            .setScale(2, RoundingMode.HALF_UP)
        val isBuy = state.side == AShareSide.BUY
        val netCash = if (isBuy) {
            amount.add(totalFee).negate()
        } else {
            amount.subtract(totalFee)
        }
        val holding = state.account.holdings.firstOrNull { it.symbol == state.symbol }
        val availableToSell = holding?.availableQuantity ?: 0
        val maxBuyQuantity = maxBuyQuantity(state.account.cash, price)

        val error = when {
            state.quantityInput.isBlank() -> null
            !state.sessionOpen -> AShareTradeError.MarketClosed
            stock == null || price.signum() <= 0 || stock.prevClose.signum() <= 0 ->
                AShareTradeError.NoQuote
            stock.isSuspended -> AShareTradeError.Suspended
            quantity <= 0 -> AShareTradeError.InvalidQuantity
            isBuy && quantity % 100 != 0 -> AShareTradeError.NotMultipleOfLot
            stock.hasPriceLimit &&
                (price > stock.limitUp || price < stock.limitDown) -> AShareTradeError.PriceLimit
            isBuy && state.account.cash < amount.add(totalFee) -> AShareTradeError.InsufficientCash
            !isBuy && availableToSell < quantity -> AShareTradeError.InsufficientShares
            else -> null
        }

        val preview = AShareOrderPreview(
            quantity = quantity,
            price = price,
            amount = amount,
            commission = commission,
            stampDuty = stampDuty,
            transferFee = transferFee,
            totalFee = totalFee,
            netCash = netCash,
            maxBuyQuantity = maxBuyQuantity,
            availableToSell = availableToSell,
            canSubmit = error == null && quantity > 0 && !state.submitting,
            error = error,
        )
        _uiState.update { it.copy(preview = preview) }
    }

    private fun maxBuyQuantity(cash: BigDecimal, price: BigDecimal): Int {
        if (price.signum() <= 0 || cash.signum() <= 0) return 0
        var lots = cash.divide(
            price.multiply(BigDecimal("100")),
            0,
            RoundingMode.DOWN,
        ).toInt()
        while (lots > 0) {
            val quantity = lots * 100
            val amount = price.multiply(BigDecimal(quantity))
                .setScale(2, RoundingMode.HALF_UP)
            val total = amount.add(AShareFees.total(amount, AShareSide.BUY))
            if (total <= cash) return quantity
            lots--
        }
        return 0
    }

    private fun errorText(error: AShareTradeError): String = when (error) {
        AShareTradeError.MarketClosed -> "当前是休市时间，A 股只能在 9:30–11:30、13:00–15:00 交易"
        AShareTradeError.NoQuote -> "暂无有效行情"
        AShareTradeError.InvalidQuantity -> "请输入有效数量"
        AShareTradeError.NotMultipleOfLot -> "买入数量必须是 100 股的整数倍"
        AShareTradeError.InsufficientCash -> "可用资金不足"
        AShareTradeError.InsufficientShares -> "可卖数量不足（当天买入的股票 T+1 才能卖）"
        AShareTradeError.PriceLimit -> "价格超出涨跌停范围"
        AShareTradeError.Suspended -> "该股票当前停牌"
        AShareTradeError.PositionNotFound -> "没有该股票的持仓"
        is AShareTradeError.Unknown -> error.message
    }
}
