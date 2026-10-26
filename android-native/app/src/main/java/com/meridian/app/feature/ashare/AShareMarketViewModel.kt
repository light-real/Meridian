package com.meridian.app.feature.ashare

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meridian.app.core.data.repository.AShareFavoritesRepository
import com.meridian.app.core.data.repository.AShareMarketRepository
import com.meridian.app.core.model.AShareCalendar
import com.meridian.app.core.model.AShareStock
import com.meridian.app.core.model.aShareSecid
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AShareMarketUiState(
    val loading: Boolean = true,
    val searching: Boolean = false,
    val stocks: List<AShareStock> = emptyList(),
    val favoriteStocks: List<AShareStock> = emptyList(),
    val searchResults: List<AShareStock> = emptyList(),
    val favorites: List<String> = emptyList(),
    val tab: Int = 0,
    val display: List<AShareStock> = emptyList(),
    val query: String = "",
    val error: String? = null,
    val sessionOpen: Boolean = AShareCalendar.isTradingSession(),
    val lastUpdated: Long = 0L,
)

class AShareMarketViewModel(
    private val repository: AShareMarketRepository,
    private val favoritesRepository: AShareFavoritesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AShareMarketUiState())
    val uiState: StateFlow<AShareMarketUiState> = _uiState.asStateFlow()

    private var started = false
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            favoritesRepository.favorites.collect { favorites ->
                _uiState.update { it.copy(favorites = favorites) }
                if (started) {
                    refreshFavoriteQuotes()
                }
            }
        }
    }

    fun loadIfNeeded() {
        if (started) return
        started = true
        load()
        viewModelScope.launch { refreshFavoriteQuotes() }
    }

    fun refresh() {
        started = true
        load(force = true)
        viewModelScope.launch { refreshFavoriteQuotes() }
    }

    fun setTab(tab: Int) {
        _uiState.update { it.copy(tab = tab) }
        applyDisplay()
        if (tab == 0 && _uiState.value.favoriteStocks.isEmpty()) {
            viewModelScope.launch { refreshFavoriteQuotes() }
        }
    }

    fun toggleFavorite(symbol: String) {
        favoritesRepository.toggle(symbol)
    }

    fun setQuery(query: String) {
        _uiState.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(searchResults = emptyList(), searching = false) }
            applyDisplay()
            return
        }
        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(searching = true, error = null) }
            delay(300L)
            runCatching { repository.search(query) }
                .onSuccess { results ->
                    if (_uiState.value.query == query) {
                        _uiState.update {
                            it.copy(
                                searchResults = results,
                                display = results,
                                searching = false,
                                sessionOpen = AShareCalendar.isTradingSession(),
                            )
                        }
                    }
                }
                .onFailure { error ->
                    if (_uiState.value.query == query) {
                        _uiState.update {
                            it.copy(
                                searching = false,
                                error = error.message ?: "A 股搜索失败",
                            )
                        }
                    }
                }
        }
    }

    private fun load(force: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = it.stocks.isEmpty(), error = null) }
            runCatching { repository.symbols(force) }
                .onSuccess { stocks ->
                    _uiState.update {
                        it.copy(
                            stocks = stocks,
                            loading = false,
                            sessionOpen = AShareCalendar.isTradingSession(),
                            lastUpdated = System.currentTimeMillis(),
                            error = null,
                        )
                    }
                    applyDisplay()
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            sessionOpen = AShareCalendar.isTradingSession(),
                            error = error.message ?: "A 股行情加载失败",
                        )
                    }
                }
        }
    }

    private suspend fun refreshFavoriteQuotes() {
        val favorites = _uiState.value.favorites
        if (favorites.isEmpty()) {
            _uiState.update { it.copy(favoriteStocks = emptyList()) }
            applyDisplay()
            return
        }
        runCatching { repository.quotes(favorites) }
            .onSuccess { stocks ->
                val bySymbol = stocks.associateBy { it.symbol }
                val ordered = favorites.map { symbol ->
                    bySymbol[symbol] ?: AShareStock(
                        symbol = symbol,
                        name = symbol,
                        secid = aShareSecid(symbol),
                    )
                }
                _uiState.update { it.copy(favoriteStocks = ordered) }
                applyDisplay()
            }
            .onFailure { error ->
                _uiState.update {
                    it.copy(error = error.message ?: "A 股自选行情加载失败")
                }
            }
    }

    private fun applyDisplay() {
        val state = _uiState.value
        val display = when {
            state.query.isNotBlank() -> state.searchResults
            state.tab == 0 -> state.favoriteStocks
            else -> state.stocks
        }
        _uiState.update { it.copy(display = display) }
    }
}
