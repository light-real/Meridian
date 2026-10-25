package com.tradesim.app.feature.market

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradesim.app.core.AppConstants
import com.tradesim.app.core.data.repository.AccountRepository
import com.tradesim.app.core.data.repository.FavoritesRepository
import com.tradesim.app.core.data.repository.MarketRepository
import com.tradesim.app.core.model.Account
import com.tradesim.app.core.model.SymbolInfo
import com.tradesim.app.core.model.Ticker
import java.math.BigDecimal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
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

@OptIn(ExperimentalCoroutinesApi::class)
class MarketViewModel(
    private val marketRepository: MarketRepository,
    private val accountRepository: AccountRepository,
    private val favoritesRepository: FavoritesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MarketUiState())
    val uiState: StateFlow<MarketUiState> = _uiState.asStateFlow()

    private val symbolsFlow = MutableStateFlow<List<SymbolInfo>>(emptyList())
    private var lastLiquidationCheck = 0L

    init {
        viewModelScope.launch {
            accountRepository.account.collect { account ->
                _uiState.update { it.copy(account = account) }
                recompute()
            }
        }
        viewModelScope.launch {
            favoritesRepository.favorites.collect { favorites ->
                _uiState.update { it.copy(favorites = favorites) }
                recompute()
            }
        }
        viewModelScope.launch {
            combine(favoritesRepository.favorites, symbolsFlow) { favorites, symbols ->
                (favorites + symbols.take(30).map { it.symbol }).distinct()
            }
                .distinctUntilChanged()
                .flatMapLatest { marketRepository.tickerStream(it) }
                .catch { error ->
                    _uiState.update { it.copy(error = error.message ?: "行情连接失败") }
                }
                .collect { ticker -> onTicker(ticker) }
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
                        state.copy(
                            tickers = state.tickers + bySymbol,
                            marks = state.marks + bySymbol.mapValues { it.value.lastPrice },
                        )
                    }
                    recompute()
                }
        }
    }

    private fun onTicker(ticker: Ticker) {
        _uiState.update { state ->
            state.copy(
                tickers = state.tickers + (ticker.symbol to ticker),
                marks = state.marks + (ticker.symbol to ticker.lastPrice),
            )
        }
        recompute()
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
