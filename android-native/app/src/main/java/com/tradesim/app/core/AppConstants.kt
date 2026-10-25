package com.tradesim.app.core

import java.math.BigDecimal

object AppConstants {
    val INITIAL_CASH: BigDecimal = BigDecimal("10000")
    const val DEFAULT_LEVERAGE = 10
    const val MAX_LEVERAGE = 150
    const val MAX_LOCAL_HISTORY = 1000
    const val MAX_CLOUD_HISTORY = 1000

    val DEFAULT_FAVORITES = listOf(
        "BTCUSDT",
        "ETHUSDT",
        "BNBUSDT",
        "SOLUSDT",
        "XRPUSDT",
        "DOGEUSDT",
    )

    val KLINE_INTERVALS = listOf("5m", "15m", "30m", "1h", "4h", "8h", "1d", "1w", "1M")
}
