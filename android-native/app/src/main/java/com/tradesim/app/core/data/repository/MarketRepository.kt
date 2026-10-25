package com.tradesim.app.core.data.repository

import com.tradesim.app.core.data.AppException
import com.tradesim.app.core.data.network.BinanceApi
import com.tradesim.app.core.data.network.BinanceStreamClient
import com.tradesim.app.core.data.network.HttpException
import com.tradesim.app.core.model.Kline
import com.tradesim.app.core.model.SymbolInfo
import com.tradesim.app.core.model.Ticker
import java.io.IOException
import kotlinx.coroutines.flow.Flow

class MarketRepository(
    private val api: BinanceApi,
    private val streamClient: BinanceStreamClient,
) {
    suspend fun symbols(forceRefresh: Boolean = false): List<SymbolInfo> =
        safe { api.symbols(forceRefresh) }

    suspend fun tickers(symbols: List<String>): List<Ticker> =
        safe { api.tickers(symbols) }

    suspend fun ticker(symbol: String): Ticker? =
        safe { api.ticker(symbol) }

    suspend fun klines(symbol: String, interval: String, limit: Int = 200): List<Kline> =
        safe { api.klines(symbol, interval, limit) }

    fun tickerStream(symbols: List<String>): Flow<Ticker> =
        streamClient.tickerStream(symbols)

    fun klineStream(symbol: String, interval: String): Flow<Kline> =
        streamClient.klineStream(symbol, interval)

    fun depthStream(symbol: String): Flow<com.tradesim.app.core.model.OrderBook> =
        streamClient.depthStream(symbol)

    private suspend fun <T> safe(block: suspend () -> T): T {
        try {
            return block()
        } catch (e: AppException) {
            throw e
        } catch (e: HttpException) {
            val message = when (e.code) {
                429 -> "请求过于频繁，请稍后再试"
                418 -> "行情服务暂时不可用"
                else -> "行情服务异常（${e.code}）"
            }
            throw AppException(message, e)
        } catch (e: IOException) {
            throw AppException("网络异常，请检查网络后重试", e)
        }
    }
}
