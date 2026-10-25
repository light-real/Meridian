package com.tradesim.app.feature.positions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradesim.app.core.AppConstants
import com.tradesim.app.core.data.repository.AccountRepository
import com.tradesim.app.core.data.repository.MarketRepository
import com.tradesim.app.core.model.Account
import com.tradesim.app.core.model.MarkedPosition
import com.tradesim.app.core.model.SymbolInfo
import com.tradesim.app.core.model.Ticker
import java.math.BigDecimal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PositionsUiState(
    val loading: Boolean = true,
    val account: Account = Account(AppConstants.INITIAL_CASH, AppConstants.INITIAL_CASH),
    val positions: List<MarkedPosition> = emptyList(),
    val marks: Map<String, BigDecimal> = emptyMap(),
    val symbols: Map<String, SymbolInfo> = emptyMap(),
    val error: String? = null,
    val notice: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class PositionsViewModel(
    private val accountRepository: AccountRepository,
    private val marketRepository: MarketRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PositionsUiState())
    val uiState: StateFlow<PositionsUiState> = _uiState.asStateFlow()

    private var lastLiquidationCheck = 0L

    init {
        viewModelScope.launch {
            accountRepository.account.collect { account ->
                _uiState.update { state ->
                    state.copy(
                        account = account,
                        positions = account.markedPositions(state.marks),
                        loading = false,
                    )
                }
            }
        }
        viewModelScope.launch {
            loadSymbols()
        }
        viewModelScope.launch {
            accountRepository.account
                .map { account -> account.positions.map { it.symbol }.distinct() }
                .distinctUntilChanged()
                .flatMapLatest { symbols -> marketRepository.tickerStream(symbols) }
                .catch { error ->
                    _uiState.update { it.copy(error = error.message ?: "行情连接失败") }
                }
                .collect { ticker -> onTicker(ticker) }
        }
    }

    fun refresh() {
        viewModelScope.launch { loadSymbols() }
    }

    fun consumeNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    private suspend fun loadSymbols() {
        runCatching { marketRepository.symbols() }
            .onSuccess { symbols ->
                _uiState.update { it.copy(symbols = symbols.associateBy { info -> info.symbol }) }
            }
    }

    private fun onTicker(ticker: Ticker) {
        _uiState.update { state ->
            val marks = state.marks + (ticker.symbol to ticker.lastPrice)
            state.copy(
                marks = marks,
                positions = state.account.markedPositions(marks),
            )
        }
        accountRepository.updateMarks(mapOf(ticker.symbol to ticker.lastPrice))

        val now = System.currentTimeMillis()
        if (now - lastLiquidationCheck >= 1_000L) {
            lastLiquidationCheck = now
            viewModelScope.launch {
                val liquidated = accountRepository.checkLiquidations(
                    mapOf(ticker.symbol to ticker.lastPrice),
                )
                if (liquidated.isNotEmpty()) {
                    val names = liquidated.joinToString("、") { it.baseAsset }
                    _uiState.update { it.copy(notice = "$names 仓位已触发强制平仓") }
                }
            }
        }
    }
}
