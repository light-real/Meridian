package com.meridian.app.feature.ashare

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meridian.app.core.data.repository.AShareAccountRepository
import com.meridian.app.core.data.repository.AShareMarketRepository
import com.meridian.app.core.model.AShareAccount
import com.meridian.app.core.model.AShareCalendar
import com.meridian.app.core.model.AShareStock
import com.meridian.app.core.model.MarkedAShareHolding
import java.math.BigDecimal
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ASharePositionsUiState(
    val loading: Boolean = true,
    val account: AShareAccount = AShareAccount(),
    val holdings: List<MarkedAShareHolding> = emptyList(),
    val sessionOpen: Boolean = AShareCalendar.isTradingSession(),
    val error: String? = null,
)

class ASharePositionsViewModel(
    private val accountRepository: AShareAccountRepository,
    private val marketRepository: AShareMarketRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ASharePositionsUiState())
    val uiState: StateFlow<ASharePositionsUiState> = _uiState.asStateFlow()

    private var prices: Map<String, BigDecimal> = emptyMap()
    private var quotes: Map<String, AShareStock> = emptyMap()
    private var lastSettleCheck = 0L

    init {
        viewModelScope.launch {
            accountRepository.account.collect { account ->
                _uiState.update { it.copy(account = account, loading = false) }
                recompute()
            }
        }
        viewModelScope.launch {
            accountRepository.settleIfNeeded()
            lastSettleCheck = System.currentTimeMillis()
            refreshQuotes()
        }
        viewModelScope.launch {
            while (isActive) {
                val interval = if (AShareCalendar.isTradingSession()) 5_000L else 300_000L
                delay(interval)
                val now = System.currentTimeMillis()
                if (now - lastSettleCheck >= 60_000L) {
                    lastSettleCheck = now
                    accountRepository.settleIfNeeded()
                }
                refreshQuotes()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { refreshQuotes() }
    }

    private suspend fun refreshQuotes() {
        val symbols = _uiState.value.account.holdings.map { it.symbol }
        if (symbols.isEmpty()) {
            _uiState.update {
                it.copy(
                    holdings = emptyList(),
                    sessionOpen = AShareCalendar.isTradingSession(),
                    error = null,
                )
            }
            return
        }
        runCatching { marketRepository.quotes(symbols) }
            .onSuccess { stocks ->
                quotes = stocks.associateBy { it.symbol }
                prices = quotes.mapValues { it.value.price }
                _uiState.update {
                    it.copy(
                        sessionOpen = AShareCalendar.isTradingSession(),
                        error = null,
                    )
                }
                recompute()
            }
            .onFailure { error ->
                _uiState.update {
                    it.copy(
                        sessionOpen = AShareCalendar.isTradingSession(),
                        error = error.message ?: "A 股行情加载失败",
                    )
                }
            }
    }

    private fun recompute() {
        val account = _uiState.value.account
        val marked = account.holdings.map { holding ->
            val quote = quotes[holding.symbol]
            val price = quote?.price ?: prices[holding.symbol] ?: holding.avgCost
            MarkedAShareHolding(
                holding = holding,
                price = price,
                changePercent = quote?.changePercent ?: BigDecimal.ZERO,
                marketValue = holding.marketValue(price),
                unrealizedPnl = holding.unrealizedPnl(price),
                unrealizedPnlPercent = holding.unrealizedPnlPercent(price),
            )
        }
        _uiState.update { it.copy(holdings = marked) }
    }
}
