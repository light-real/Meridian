package com.tradesim.app.core.data.settings

import android.content.Context
import com.tradesim.app.core.AppConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("tradesim_settings", Context.MODE_PRIVATE)

    private val _colorConvention = MutableStateFlow(
        PriceColorConvention.fromKey(prefs.getString(KEY_COLOR_CONVENTION, null)),
    )
    val colorConvention: StateFlow<PriceColorConvention> = _colorConvention.asStateFlow()

    private val _monitorEnabled = MutableStateFlow(prefs.getBoolean(KEY_MONITOR, true))
    val monitorEnabled: StateFlow<Boolean> = _monitorEnabled.asStateFlow()

    fun setMonitorEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_MONITOR, value).apply()
        _monitorEnabled.value = value
    }

    fun setColorConvention(value: PriceColorConvention) {
        prefs.edit().putString(KEY_COLOR_CONVENTION, value.name).apply()
        _colorConvention.value = value
    }

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

    /** 该交易对上次资金费结算的时间点（对应的结算周期起点，0 表示还没建立基线）。 */
    fun fundingSettledAt(symbol: String): Long =
        prefs.getLong(KEY_FUNDING_PREFIX + symbol, 0L)

    fun setFundingSettledAt(symbol: String, time: Long) {
        prefs.edit().putLong(KEY_FUNDING_PREFIX + symbol, time).apply()
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
        const val KEY_FAVORITES = "favorites"
        const val KEY_COLOR_CONVENTION = "color_convention"
        const val KEY_FUNDING_PREFIX = "funding_settled_"
        const val KEY_MONITOR = "background_monitor"
    }
}
