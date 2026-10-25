package com.cryptosim.app.core.data.settings

import android.content.Context
import com.cryptosim.app.core.AppConstants

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("cryptosim_settings", Context.MODE_PRIVATE)

    var isGuest: Boolean
        get() = prefs.getBoolean(KEY_GUEST, false)
        set(value) {
            prefs.edit().putBoolean(KEY_GUEST, value).apply()
        }

    var lastLeverage: Int
        get() = prefs.getInt(KEY_LEVERAGE, AppConstants.DEFAULT_LEVERAGE).coerceIn(1, AppConstants.MAX_LEVERAGE)
        set(value) {
            prefs.edit().putInt(KEY_LEVERAGE, value.coerceIn(1, AppConstants.MAX_LEVERAGE)).apply()
        }

    var lastSymbol: String
        get() = prefs.getString(KEY_SYMBOL, "BTCUSDT") ?: "BTCUSDT"
        set(value) {
            prefs.edit().putString(KEY_SYMBOL, value).apply()
        }

    var lastInterval: String
        get() = prefs.getString(KEY_INTERVAL, "5m") ?: "5m"
        set(value) {
            prefs.edit().putString(KEY_INTERVAL, value).apply()
        }

    var klineExpanded: Boolean
        get() = prefs.getBoolean(KEY_KLINE_EXPANDED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_KLINE_EXPANDED, value).apply()
        }

    fun favorites(): List<String> {
        val raw = prefs.getString(KEY_FAVORITES, null) ?: return AppConstants.DEFAULT_FAVORITES
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    fun setFavorites(symbols: List<String>) {
        prefs.edit().putString(KEY_FAVORITES, symbols.distinct().joinToString(",")).apply()
    }

    fun resetForLogout() {
        prefs.edit()
            .putBoolean(KEY_GUEST, false)
            .remove(KEY_FAVORITES)
            .apply()
    }

    private companion object {
        const val KEY_GUEST = "guest"
        const val KEY_LEVERAGE = "last_leverage"
        const val KEY_SYMBOL = "last_symbol"
        const val KEY_INTERVAL = "last_interval"
        const val KEY_KLINE_EXPANDED = "kline_expanded"
        const val KEY_FAVORITES = "favorites"
    }
}
