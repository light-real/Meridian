package com.meridian.app.feature.positions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meridian.app.core.AppConstants
import com.meridian.app.core.data.market.MarketDataHub
import com.meridian.app.core.data.repository.AccountRepository
import com.meridian.app.core.data.repository.MarketRepository
import com.meridian.app.core.domain.markedPositionsWithLiquidation
import com.meridian.app.core.model.Account
import com.meridian.app.core.model.MarkedPosition
import com.meridian.app.core.model.SymbolInfo
import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

class PositionsViewModel(
    private val accountRepository: AccountRepository,
    private val marketRepository: MarketRepository,
    private val marketDataHub: MarketDataHub,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PositionsUiState())
    val uiState: StateFlow<PositionsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            accountRepository.account.collect { account ->
                _uiState.update { state ->
                    state.copy(
                        account = account,
                        positions = account.markedPositionsWithLiquidation(state.marks),
                        loading = false,
                    )
                }
            }
        }
        viewModelScope.launch {
            loadSymbols()
        }
        // 标记价统一读价格中枢，页面不再自己订阅盘口 / ticker
        viewModelScope.launch {
            marketDataHub.marks.collect { marks ->
                if (marks != _uiState.value.marks) {
                    _uiState.update { state ->
                        state.copy(
                            marks = marks,
                            positions = state.account.markedPositionsWithLiquidation(marks),
                        )
                    }
                }
            }
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
}
