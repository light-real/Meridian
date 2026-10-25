package com.tradesim.app.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradesim.app.core.data.repository.AccountRepository
import com.tradesim.app.core.model.Trade
import com.tradesim.app.core.model.TradeType
import com.tradesim.app.core.model.TradeTypeFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryUiState(
    val loading: Boolean = true,
    val trades: List<Trade> = emptyList(),
    val filtered: List<Trade> = emptyList(),
    val filter: TradeTypeFilter = TradeTypeFilter.ALL,
)

class HistoryViewModel(
    accountRepository: AccountRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            accountRepository.account.collect { account ->
                _uiState.update { state ->
                    val filtered = filterTrades(account.trades, state.filter)
                    state.copy(
                        loading = false,
                        trades = account.trades,
                        filtered = filtered,
                    )
                }
            }
        }
    }

    fun setFilter(filter: TradeTypeFilter) {
        _uiState.update { state ->
            state.copy(
                filter = filter,
                filtered = filterTrades(state.trades, filter),
            )
        }
    }

    private fun filterTrades(trades: List<Trade>, filter: TradeTypeFilter): List<Trade> =
        when (filter) {
            TradeTypeFilter.ALL -> trades
            TradeTypeFilter.OPEN -> trades.filter { it.type == TradeType.OPEN }
            TradeTypeFilter.CLOSE -> trades.filter {
                it.type == TradeType.CLOSE || it.type == TradeType.LIQUIDATION
            }
        }
}
