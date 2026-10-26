package com.meridian.app.core.data.network

import com.meridian.app.BuildConfig
import com.meridian.app.core.model.FundingRate
import com.meridian.app.core.model.SymbolInfo
import com.meridian.app.core.model.Ticker
import java.math.BigDecimal
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl

@Serializable
private data class ExchangeInfoDto(
    val symbols: List<SymbolDto> = emptyList(),
)

@Serializable
private data class SymbolDto(
    val symbol: String,
    val baseAsset: String = "",
    val quoteAsset: String = "USDT",
    val contractType: String? = null,
    val status: String? = null,
    val pricePrecision: Int = 2,
    val quantityPrecision: Int = 3,
    val filters: List<FilterDto> = emptyList(),
)

@Serializable
private data class FilterDto(
    val filterType: String? = null,
    val tickSize: String? = null,
    val stepSize: String? = null,
    val notional: String? = null,
)

@Serializable
private data class TickerDto(
    val symbol: String = "",
    val lastPrice: String,
    val priceChange: String,
    val priceChangePercent: String,
    val highPrice: String,
    val lowPrice: String,
    val quoteVolume: String,
) {
    fun toModel(): Ticker = Ticker(
        symbol = symbol,
        lastPrice = lastPrice.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        priceChange = priceChange.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        priceChangePercent = priceChangePercent.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        highPrice = highPrice.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        lowPrice = lowPrice.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        quoteVolume = quoteVolume.toBigDecimalOrNull() ?: BigDecimal.ZERO,
    )
}

class BinanceApi(
    private val http: HttpClient,
    private val json: Json,
) {
    private val symbolsMutex = Mutex()

    @Volatile
    private var symbolsCache: List<SymbolInfo>? = null

    suspend fun symbols(forceRefresh: Boolean = false): List<SymbolInfo> {
        symbolsCache?.takeUnless { forceRefresh }?.let { return it }
        return symbolsMutex.withLock {
            symbolsCache?.takeUnless { forceRefresh } ?: run {
                val body = http.get(url("fapi/v1/exchangeInfo"))
                val parsed = json.decodeFromString<ExchangeInfoDto>(body)
                val result = parsed.symbols
                    .asSequence()
                    .filter {
                        (it.contractType == "PERPETUAL" || it.contractType == "TRADIFI_PERPETUAL") &&
                            it.quoteAsset == "USDT" &&
                            it.status == "TRADING"
                    }
                    .map { dto ->
                        val priceFilter = dto.filters.firstOrNull { it.filterType == "PRICE_FILTER" }
                        val lotSize = dto.filters.firstOrNull { it.filterType == "LOT_SIZE" }
                        val minNotional = dto.filters.firstOrNull { it.filterType == "MIN_NOTIONAL" }
                        SymbolInfo(
                            symbol = dto.symbol,
                            baseAsset = dto.baseAsset,
                            quoteAsset = dto.quoteAsset,
                            pricePrecision = dto.pricePrecision.coerceAtLeast(0),
                            quantityPrecision = dto.quantityPrecision.coerceAtLeast(0),
                            tickSize = priceFilter?.tickSize?.toBigDecimalOrNull() ?: BigDecimal("0.01"),
                            stepSize = lotSize?.stepSize?.toBigDecimalOrNull() ?: BigDecimal("0.001"),
                            minNotional = minNotional?.notional?.toBigDecimalOrNull() ?: BigDecimal("5"),
                        )
                    }
                    .sortedWith(compareBy<SymbolInfo> { priorityOf(it.baseAsset) }.thenBy { it.symbol })
                    .toList()
                symbolsCache = result
                result
            }
        }
    }

    suspend fun tickers(symbols: List<String>): List<Ticker> {
        if (symbols.isEmpty()) return emptyList()
        val normalized = symbols.map { it.uppercase() }.distinct()

        // Futures 的 /ticker/24hr 单 symbol 返回对象且不带 symbol 字段；
        // 多 symbol 参数在部分环境会被忽略，统一在客户端按请求列表过滤。
        if (normalized.size == 1) {
            val requested = normalized.first()
            val body = http.get(
                url(
                    path = "fapi/v1/ticker/24hr",
                    params = mapOf("symbol" to requested),
                ),
            )
            val single = runCatching {
                listOf(json.decodeFromString<TickerDto>(body).copy(symbol = requested))
            }.getOrElse {
                json.decodeFromString<List<TickerDto>>(body)
            }
            return single.map { it.toModel() }
        }

        val symbolsJson = normalized.joinToString(
            separator = ",",
            prefix = "[",
            postfix = "]",
        ) { "\"$it\"" }
        val body = http.get(
            url(
                path = "fapi/v1/ticker/24hr",
                params = mapOf("symbols" to symbolsJson),
            ),
        )
        val wanted = normalized.toSet()
        return json.decodeFromString<List<TickerDto>>(body)
            .asSequence()
            .filter { it.symbol.uppercase() in wanted }
            .map { it.toModel() }
            .toList()
    }

    suspend fun ticker(symbol: String): Ticker? =
        tickers(listOf(symbol)).firstOrNull { it.symbol.equals(symbol, ignoreCase = true) }

    suspend fun fundingRate(symbol: String): FundingRate? {
        val body = http.get(
            url(
                path = "fapi/v1/premiumIndex",
                params = mapOf("symbol" to symbol),
            ),
        )
        val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return null
        val resolved = obj["symbol"]?.jsonPrimitive?.contentOrNull ?: return null
        return FundingRate(
            symbol = resolved,
            rate = obj["lastFundingRate"]?.jsonPrimitive?.contentOrNull
                ?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            nextFundingTime = obj["nextFundingTime"]?.jsonPrimitive?.longOrNull ?: 0L,
            markPrice = obj["markPrice"]?.jsonPrimitive?.contentOrNull
                ?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        )
    }

    private fun url(path: String, params: Map<String, String> = emptyMap()): String {
        val builder = (BuildConfig.BINANCE_REST_URL.trimEnd('/') + "/" + path.trimStart('/'))
            .toHttpUrl()
            .newBuilder()
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build().toString()
    }

    private fun priorityOf(baseAsset: String): Int {
        val index = PRIORITY.indexOf(baseAsset)
        return if (index >= 0) index else Int.MAX_VALUE
    }

    private fun JsonElement.asDecimal(): BigDecimal =
        jsonPrimitive.content.toBigDecimalOrNull() ?: BigDecimal.ZERO

    private fun JsonElement.asLong(): Long =
        jsonPrimitive.longOrNull ?: jsonPrimitive.content.toLongOrNull() ?: 0L

    companion object {
        private val PRIORITY = listOf(
            "BTC", "ETH", "BNB", "SOL", "XRP", "DOGE", "ADA", "AVAX", "DOT", "LINK",
        )
    }
}
