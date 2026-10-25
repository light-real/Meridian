package com.meridian.app.core.data.repository

import com.meridian.app.core.data.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A 股自选，纯本地存储；和合约自选分开。 */
class AShareFavoritesRepository(
    private val settingsStore: SettingsStore,
) {
    private val _favorites = MutableStateFlow(settingsStore.aShareFavorites())
    val favorites: StateFlow<List<String>> = _favorites.asStateFlow()

    fun toggle(symbol: String) {
        val current = _favorites.value
        val updated = if (current.contains(symbol)) {
            current - symbol
        } else {
            current + symbol
        }
        settingsStore.setAShareFavorites(updated)
        _favorites.value = updated
    }

    fun isFavorite(symbol: String): Boolean = _favorites.value.contains(symbol)
}
