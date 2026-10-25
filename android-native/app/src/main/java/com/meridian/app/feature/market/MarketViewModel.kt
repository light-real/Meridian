package com.meridian.app.feature.market

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meridian.app.core.AppConstants
import com.meridian.app.core.data.market.MarketDataHub
import com.meridian.app.core.data.repository.AccountRepository
import com.meridian.app.core.data.repository.FavoritesRepository
import com.meridian.app.core.data.repository.MarketRepository
import com.meridian.app.core.model.Account
import com.meridian.app.core.model.SymbolInfo
import com.meridian.app.core.model.Ticker
import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MarketRow(
    val info: SymbolInfo,
    val ticker: Ticker?,
    val favorite: Boolean,
)

data class MarketUiState(
    val loading: Boolean = true,
    val symbols: List<SymbolInfo> = emptyList(),
    val tickers: Map<String, Ticker> = emptyMap(),
    val display: List<MarketRow> = emptyList(),
    val favorites: List<String> = emptyList(),
    val tab: Int = 0,
    val query: String = "",
    val searchVisible: Boolean = false,
    val account: Account = Account(AppConstants.INITIAL_CASH, AppConstants.INITIAL_CASH),
    val marks: Map<String, BigDecimal> = emptyMap(),
    val error: String? = null,
    val notice: String? = null,
)

class MarketViewModel(
    private val marketRepository: MarketRepository,
    private val accountRepository: AccountRepository,
    private val favoritesRepository: FavoritesRepository,
    private val marketDataHub: MarketDataHub,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MarketUiState())
    val uiState: StateFlow<MarketUiState> = _uiState.asStateFlow()

    private val symbolsFlow = MutableStateFlow<List<SymbolInfo>>(emptyList())

    init {
        viewModelScope.launch {
            accountRepository.account.collect { account ->
                _uiState.update { it.copy(account = account) }
            }
        }
        viewModelScope.launch {
            favoritesRepository.favorites.collect { favorites ->
                _uiState.update { it.copy(favorites = favorites) }
                recompute()
            }
        }
        // 资产卡的浮盈统一读价格中枢，不再自己订阅盘口
        viewModelScope.launch {
            marketDataHub.marks.collect { marks ->
                if (marks != _uiState.value.marks) {
                    _uiState.update { it.copy(marks = marks) }
                }
            }
        }
        loadSymbols()
    }

    fun loadSymbols(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(loading = it.symbols.isEmpty(), error = null)
            }
            runCatching { marketRepository.symbols(forceRefresh) }
                .onSuccess { symbols ->
                    symbolsFlow.value = symbols
                    _uiState.update { it.copy(symbols = symbols, loading = false) }
                    recompute()
                    refreshTickers(
                        (symbols.take(40).map { it.symbol } + _uiState.value.favorites).distinct(),
                    )
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            error = error.message ?: "行情加载失败",
                        )
                    }
                }
        }
    }

    fun refresh() {
        loadSymbols(forceRefresh = true)
    }

    fun switchTab(index: Int) {
        _uiState.update { it.copy(tab = index) }
        recompute()
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
        recompute()
    }

    fun toggleSearch() {
        _uiState.update {
            it.copy(
                searchVisible = !it.searchVisible,
                query = if (it.searchVisible) "" else it.query,
            )
        }
        recompute()
    }

    fun toggleFavorite(symbol: String) {
        favoritesRepository.toggle(symbol)
    }

    fun consumeNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    private fun refreshTickers(symbols: List<String>) {
        if (symbols.isEmpty()) return
        viewModelScope.launch {
            runCatching { marketRepository.tickers(symbols) }
                .onSuccess { tickers ->
                    val bySymbol = tickers.associateBy { it.symbol }
                    _uiState.update { state ->
                        state.copy(tickers = state.tickers + bySymbol)
                    }
                    recompute()
                }
        }
    }

    private fun recompute() {
        val state = _uiState.value
        val query = state.query.trim().uppercase()
        val source = if (state.tab == 0) {
            state.symbols.filter { state.favorites.contains(it.symbol) }
        } else {
            state.symbols
        }
        val filtered = if (query.isEmpty()) {
            source
        } else {
            source.filter {
                it.symbol.contains(query) || it.baseAsset.contains(query)
            }
        }
        val rows = filtered.map { info ->
            MarketRow(
                info = info,
                ticker = state.tickers[info.symbol],
                favorite = state.favorites.contains(info.symbol),
            )
        }
        _uiState.update { it.copy(display = rows) }
    }
}
