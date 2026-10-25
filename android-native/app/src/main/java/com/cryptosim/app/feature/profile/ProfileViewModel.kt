package com.cryptosim.app.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cryptosim.app.core.AppConstants
import com.cryptosim.app.core.data.repository.AccountRepository
import com.cryptosim.app.core.data.repository.AuthRepository
import com.cryptosim.app.core.data.repository.FavoritesRepository
import com.cryptosim.app.core.data.repository.MarketRepository
import com.cryptosim.app.core.data.repository.SyncState
import com.cryptosim.app.core.data.session.Session
import com.cryptosim.app.core.data.settings.SettingsStore
import com.cryptosim.app.core.model.Account
import com.cryptosim.app.core.model.MarkedPosition
import com.cryptosim.app.core.model.Ticker
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

data class ProfileUiState(
    val session: Session? = null,
    val isGuest: Boolean = false,
    val account: Account = Account(AppConstants.INITIAL_CASH, AppConstants.INITIAL_CASH),
    val positions: List<MarkedPosition> = emptyList(),
    val marks: Map<String, BigDecimal> = emptyMap(),
    val syncState: SyncState = SyncState.IDLE,
    val loading: Boolean = false,
    val message: String? = null,
    val loggedOut: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModel(
    private val authRepository: AuthRepository,
    private val accountRepository: AccountRepository,
    private val favoritesRepository: FavoritesRepository,
    private val settingsStore: SettingsStore,
    private val marketRepository: MarketRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ProfileUiState(
            session = authRepository.currentSession(),
            isGuest = settingsStore.isGuest,
        ),
    )
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

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
                        positions = account.markedPositions(state.marks),
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
            accountRepository.account
                .map { account -> account.positions.map { it.symbol }.distinct() }
                .distinctUntilChanged()
                .flatMapLatest { symbols -> marketRepository.tickerStream(symbols) }
                .catch { }
                .collect { ticker -> onTicker(ticker) }
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
                    _uiState.update { it.copy(loading = false, message = "账户已重置") }
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

    private fun onTicker(ticker: Ticker) {
        _uiState.update { state ->
            val marks = state.marks + (ticker.symbol to ticker.lastPrice)
            state.copy(
                marks = marks,
                positions = state.account.markedPositions(marks),
            )
        }
        accountRepository.updateMarks(mapOf(ticker.symbol to ticker.lastPrice))
    }
}
