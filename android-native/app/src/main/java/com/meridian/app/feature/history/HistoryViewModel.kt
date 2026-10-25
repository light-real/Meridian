package com.meridian.app.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meridian.app.core.data.repository.AShareAccountRepository
import com.meridian.app.core.data.repository.AccountRepository
import com.meridian.app.core.model.AShareTrade
import com.meridian.app.core.model.Trade
import com.meridian.app.core.model.TradeType
import com.meridian.app.core.model.TradeTypeFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class HistoryMarketFilter { ALL, CONTRACT, A_SHARE }

sealed interface HistoryEntry {
    val createdAt: Long

    data class Contract(val trade: Trade) : HistoryEntry {
        override val createdAt: Long get() = trade.createdAt
    }

    data class AShare(val trade: AShareTrade) : HistoryEntry {
        override val createdAt: Long get() = trade.createdAt
    }
}

data class HistoryUiState(
    val loading: Boolean = true,
    val entries: List<HistoryEntry> = emptyList(),
    val filtered: List<HistoryEntry> = emptyList(),
    val contractTrades: List<Trade> = emptyList(),
    val aShareTrades: List<AShareTrade> = emptyList(),
    val filter: TradeTypeFilter = TradeTypeFilter.ALL,
    val marketFilter: HistoryMarketFilter = HistoryMarketFilter.ALL,
)

class HistoryViewModel(
    accountRepository: AccountRepository,
    aShareAccountRepository: AShareAccountRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            accountRepository.account.collect { account ->
                _uiState.update {
                    it.copy(
                        loading = false,
                        contractTrades = account.trades,
                    )
                }
                recompute()
            }
        }
        viewModelScope.launch {
            aShareAccountRepository.account.collect { account ->
                _uiState.update {
                    it.copy(
                        loading = false,
                        aShareTrades = account.trades,
                    )
                }
                recompute()
            }
        }
    }

    fun setFilter(filter: TradeTypeFilter) {
        _uiState.update { it.copy(filter = filter) }
        recompute()
    }

    fun setMarketFilter(filter: HistoryMarketFilter) {
        _uiState.update {
            it.copy(
                marketFilter = filter,
                filter = if (filter == HistoryMarketFilter.A_SHARE) {
                    TradeTypeFilter.ALL
                } else {
                    it.filter
                },
            )
        }
        recompute()
    }

    private fun recompute() {
        val state = _uiState.value
        val entries = buildList {
            if (state.marketFilter != HistoryMarketFilter.A_SHARE) {
                addAll(state.contractTrades.map { HistoryEntry.Contract(it) })
            }
            if (state.marketFilter != HistoryMarketFilter.CONTRACT) {
                addAll(state.aShareTrades.map { HistoryEntry.AShare(it) })
            }
        }.sortedByDescending { it.createdAt }

        val filtered = entries.filter { entry ->
            if (state.marketFilter == HistoryMarketFilter.A_SHARE) {
                true
            } else {
                when (entry) {
                    is HistoryEntry.Contract ->
                        filterTrades(listOf(entry.trade), state.filter).isNotEmpty()
                    is HistoryEntry.AShare -> state.filter == TradeTypeFilter.ALL
                }
            }
        }
        _uiState.update { it.copy(entries = entries, filtered = filtered) }
    }

    private fun filterTrades(trades: List<Trade>, filter: TradeTypeFilter): List<Trade> =
        when (filter) {
            TradeTypeFilter.ALL -> trades
            TradeTypeFilter.OPEN -> trades.filter { it.type == TradeType.OPEN }
            TradeTypeFilter.CLOSE -> trades.filter {
                it.type == TradeType.CLOSE || it.type == TradeType.LIQUIDATION
            }
            TradeTypeFilter.FUNDING -> trades.filter { it.type == TradeType.FUNDING }
        }
}
