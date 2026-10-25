package com.cryptosim.app.core.data.repository

import com.cryptosim.app.core.AppConstants
import com.cryptosim.app.core.data.settings.SettingsStore
import com.cryptosim.app.core.data.supabase.SupabaseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FavoritesRepository(
    private val settingsStore: SettingsStore,
    private val authRepository: AuthRepository,
    private val supabase: SupabaseClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _favorites = MutableStateFlow(settingsStore.favorites())
    val favorites: StateFlow<List<String>> = _favorites.asStateFlow()

    fun toggle(symbol: String) {
        val current = _favorites.value
        val next = if (current.contains(symbol)) current - symbol else current + symbol
        set(next)
    }

    fun set(symbols: List<String>) {
        val normalized = symbols.map { it.uppercase() }.distinct()
        settingsStore.setFavorites(normalized)
        _favorites.value = normalized
        syncAsync()
    }

    suspend fun restoreFromCloud(): Boolean {
        val session = authRepository.ensureValidSession() ?: return false
        return try {
            val cloud = supabase.loadFavorites(session)
            if (cloud != null) {
                val normalized = cloud.map { it.uppercase() }.distinct()
                settingsStore.setFavorites(normalized)
                _favorites.value = normalized
            } else {
                supabase.saveFavorites(session, _favorites.value)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun syncNow() {
        val session = authRepository.ensureValidSession() ?: return
        supabase.saveFavorites(session, _favorites.value)
    }

    fun resetToDefaults() {
        val defaults = AppConstants.DEFAULT_FAVORITES
        settingsStore.setFavorites(defaults)
        _favorites.value = defaults
    }

    private fun syncAsync() {
        scope.launch {
            runCatching { syncNow() }
        }
    }
}
