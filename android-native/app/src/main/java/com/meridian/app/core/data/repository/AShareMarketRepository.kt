package com.meridian.app.core.data.repository

import com.meridian.app.core.data.network.EastmoneyAShareApi
import com.meridian.app.core.model.AShareStock

class AShareMarketRepository(
    private val api: EastmoneyAShareApi,
) {
    suspend fun symbols(forceRefresh: Boolean = false): List<AShareStock> =
        api.symbols(forceRefresh)

    suspend fun quote(symbol: String): AShareStock? = api.quote(symbol)

    suspend fun quotes(symbols: List<String>): List<AShareStock> = api.quotes(symbols)

    suspend fun search(query: String): List<AShareStock> = api.search(query)
}
