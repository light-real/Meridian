package com.meridian.app.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meridian.app.core.data.repository.AccountRepository
import com.meridian.app.core.data.repository.AuthRepository
import com.meridian.app.core.data.repository.FavoritesRepository
import com.meridian.app.core.data.settings.SettingsStore
import com.meridian.app.core.data.supabase.SignUpResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val mode: Int = 0,
    val email: String = "",
    val password: String = "",
    val showPassword: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val isConfigured: Boolean = true,
    val navigateToMarket: Boolean = false,
)

class AuthViewModel(
    private val authRepository: AuthRepository,
    private val accountRepository: AccountRepository,
    private val favoritesRepository: FavoritesRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AuthUiState(isConfigured = authRepository.isConfigured),
    )
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun setMode(mode: Int) {
        _uiState.update { it.copy(mode = mode, error = null, message = null) }
    }

    fun onEmailChange(email: String) {
        _uiState.update { it.copy(email = email, error = null) }
    }

    fun onPasswordChange(password: String) {
        _uiState.update { it.copy(password = password, error = null) }
    }

    fun togglePasswordVisibility() {
        _uiState.update { it.copy(showPassword = !it.showPassword) }
    }

    fun submit() {
        val state = _uiState.value
        if (state.loading) return

        val email = state.email.trim()
        val password = state.password
        when {
            email.isBlank() || password.isBlank() -> {
                _uiState.update { it.copy(error = "邮箱和密码不能为空") }
                return
            }
            !EMAIL_REGEX.matches(email) -> {
                _uiState.update { it.copy(error = "请输入有效的邮箱地址") }
                return
            }
            password.length < 6 -> {
                _uiState.update { it.copy(error = "密码至少需要 6 位") }
                return
            }
        }

        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            runCatching {
                if (state.mode == 0) {
                    authRepository.signIn(email, password)
                    settingsStore.isGuest = false
                    accountRepository.restoreFromCloud()
                    favoritesRepository.restoreFromCloud()
                    _uiState.update { it.copy(loading = false, navigateToMarket = true) }
                } else {
                    when (val result = authRepository.signUp(email, password)) {
                        is SignUpResult.SignedIn -> {
                            settingsStore.isGuest = false
                            accountRepository.restoreFromCloud()
                            favoritesRepository.restoreFromCloud()
                            _uiState.update {
                                it.copy(loading = false, navigateToMarket = true)
                            }
                        }
                        SignUpResult.NeedsConfirmation -> {
                            _uiState.update {
                                it.copy(
                                    loading = false,
                                    mode = 0,
                                    message = "注册成功，请到邮箱确认后再登录",
                                )
                            }
                        }
                    }
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        loading = false,
                        error = error.message ?: "操作失败，请稍后重试",
                    )
                }
            }
        }
    }

    fun continueAsGuest() {
        settingsStore.isGuest = true
        _uiState.update { it.copy(navigateToMarket = true) }
    }

    fun consumeNavigation() {
        _uiState.update { it.copy(navigateToMarket = false) }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private companion object {
        val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}
