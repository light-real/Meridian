package com.meridian.app.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meridian.app.core.AppConstants
import com.meridian.app.core.data.market.MarketDataHub
import com.meridian.app.core.data.repository.AShareAccountRepository
import com.meridian.app.core.data.repository.AShareMarketRepository
import com.meridian.app.core.data.repository.AccountRepository
import com.meridian.app.core.data.repository.AuthRepository
import com.meridian.app.core.data.repository.FavoritesRepository
import com.meridian.app.core.data.repository.SyncState
import com.meridian.app.core.data.session.Session
import com.meridian.app.core.data.settings.SettingsStore
import com.meridian.app.core.data.sync.TradeSyncRepository
import com.meridian.app.core.domain.markedPositionsWithLiquidation
import com.meridian.app.core.model.AShareAccount
import com.meridian.app.core.model.AShareCalendar
import com.meridian.app.core.model.AShareStock
import com.meridian.app.core.model.Account
import com.meridian.app.core.model.MarkedAShareHolding
import com.meridian.app.core.model.MarkedPosition
import java.math.BigDecimal
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ProfileUiState(
    val session: Session? = null,
    val isGuest: Boolean = false,
    val account: Account = Account(AppConstants.INITIAL_CASH, AppConstants.INITIAL_CASH),
    val positions: List<MarkedPosition> = emptyList(),
    val marks: Map<String, BigDecimal> = emptyMap(),
    val aShareAccount: AShareAccount = AShareAccount(),
    val aShareHoldings: List<MarkedAShareHolding> = emptyList(),
    val lastSyncAt: Long = 0L,
    val syncing: Boolean = false,
    val syncState: SyncState = SyncState.IDLE,
    val loading: Boolean = false,
    val message: String? = null,
    val loggedOut: Boolean = false,
)

class ProfileViewModel(
    private val authRepository: AuthRepository,
    private val accountRepository: AccountRepository,
    private val favoritesRepository: FavoritesRepository,
    private val settingsStore: SettingsStore,
    private val marketDataHub: MarketDataHub,
    private val aShareAccountRepository: AShareAccountRepository,
    private val aShareMarketRepository: AShareMarketRepository,
    private val tradeSyncRepository: TradeSyncRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ProfileUiState(
            session = authRepository.currentSession(),
            isGuest = settingsStore.isGuest,
        ),
    )
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    private var aShareQuotes: Map<String, AShareStock> = emptyMap()

    init {
        viewModelScope.launch {
            authRepository.session.collect { session ->
                _uiState.update {
                    it.copy(
                        session = session,
                        isGuest = session == null && settingsStore.isGuest,
                    )
                }
            }
        }
        viewModelScope.launch {
            accountRepository.account.collect { account ->
                _uiState.update { state ->
                    state.copy(
                        account = account,
                        positions = account.markedPositionsWithLiquidation(state.marks),
                    )
                }
            }
        }
        viewModelScope.launch {
            accountRepository.syncState.collect { state ->
                _uiState.update { it.copy(syncState = state) }
            }
        }
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
        viewModelScope.launch {
            aShareAccountRepository.account.collect { account ->
                _uiState.update { it.copy(aShareAccount = account) }
                recomputeAShare()
            }
        }
        viewModelScope.launch {
            aShareAccountRepository.settleIfNeeded()
            while (isActive) {
                refreshAShareQuotes()
                delay(if (AShareCalendar.isTradingSession()) 5_000L else 300_000L)
            }
        }
        viewModelScope.launch {
            tradeSyncRepository.lastSyncAt.collect { at ->
                _uiState.update { it.copy(lastSyncAt = at) }
            }
        }
    }

    fun syncToServer() {
        if (_uiState.value.syncing) return
        viewModelScope.launch {
            _uiState.update { it.copy(syncing = true) }
            val result = tradeSyncRepository.syncNow()
            _uiState.update {
                it.copy(
                    syncing = false,
                    lastSyncAt = tradeSyncRepository.lastSyncAt.value,
                    message = if (result.isSuccess) {
                        "已同步到服务器"
                    } else {
                        result.exceptionOrNull()?.message ?: "同步失败"
                    },
                )
            }
        }
    }

    fun syncNow() {
        if (_uiState.value.loading) return
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            runCatching {
                accountRepository.syncNow()
                favoritesRepository.syncNow()
            }.onSuccess {
                _uiState.update { it.copy(loading = false, message = "同步成功") }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        loading = false,
                        message = error.message ?: "同步失败",
                    )
                }
            }
        }
    }

    fun resetAccount() {
        if (_uiState.value.loading) return
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            runCatching { accountRepository.reset() }
                .onSuccess {
                    _uiState.update { it.copy(loading = false, message = "合约账户已重置") }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            message = error.message ?: "重置失败",
                        )
                    }
                }
        }
    }

    fun resetAShareAccount() {
        if (_uiState.value.loading) return
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            runCatching { aShareAccountRepository.reset() }
                .onSuccess {
                    _uiState.update { it.copy(loading = false, message = "A 股账户已重置") }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            message = error.message ?: "重置失败",
                        )
                    }
                }
        }
    }

    private suspend fun refreshAShareQuotes() {
        val symbols = _uiState.value.aShareAccount.holdings.map { it.symbol }
        if (symbols.isEmpty()) {
            _uiState.update { it.copy(aShareHoldings = emptyList()) }
            return
        }
        runCatching { aShareMarketRepository.quotes(symbols) }
            .onSuccess { stocks ->
                aShareQuotes = stocks.associateBy { it.symbol }
                recomputeAShare()
            }
    }

    private fun recomputeAShare() {
        val account = _uiState.value.aShareAccount
        val holdings = account.holdings.map { holding ->
            val quote = aShareQuotes[holding.symbol]
            val price = quote?.price ?: holding.avgCost
            MarkedAShareHolding(
                holding = holding,
                price = price,
                changePercent = quote?.changePercent ?: BigDecimal.ZERO,
                marketValue = holding.marketValue(price),
                unrealizedPnl = holding.unrealizedPnl(price),
                unrealizedPnlPercent = holding.unrealizedPnlPercent(price),
            )
        }
        _uiState.update { it.copy(aShareHoldings = holdings) }
    }

    fun logout() {
        if (_uiState.value.loading) return
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            runCatching { authRepository.signOut() }
            accountRepository.clearLocal()
            settingsStore.resetForLogout()
            favoritesRepository.resetToDefaults()
            _uiState.update {
                it.copy(
                    loading = false,
                    session = null,
                    isGuest = false,
                    message = "已退出登录",
                    loggedOut = true,
                )
            }
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun consumeLoggedOut() {
        _uiState.update { it.copy(loggedOut = false) }
    }
}
