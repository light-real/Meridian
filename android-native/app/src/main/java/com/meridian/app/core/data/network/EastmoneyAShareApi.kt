package com.meridian.app.core.data.network

import com.meridian.app.BuildConfig
import com.meridian.app.core.model.AShareStock
import java.math.BigDecimal
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A 股行情客户端。
 *
 * App 不直接访问东方财富/腾讯，统一走自建中转 [BuildConfig.A_SHARE_API_URL]：
 * - /list   涨幅榜（新浪）
 * - /quotes 批量报价（腾讯）
 * - /search 代码/名称搜索（东方财富）
 *
 * 这样手机开着代理/VPN 时也不会因为上游域名被拦而整个 A 股不可用。
 */
class EastmoneyAShareApi(
    private val http: HttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Volatile
    private var cachedSymbols: List<AShareStock> = emptyList()

    @Volatile
    private var cachedAt: Long = 0L

    private val baseUrl = BuildConfig.A_SHARE_API_URL.trimEnd('/')

    suspend fun symbols(forceRefresh: Boolean = false): List<AShareStock> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedSymbols.isNotEmpty() && now - cachedAt < CACHE_MS) {
            return cachedSymbols
        }
        val body = http.get("$baseUrl/list?page=1&size=100")
        val data = json.parseToJsonElement(body).jsonObject["data"] as? JsonObject
        val diff = data?.get("diff") as? JsonArray ?: JsonArray(emptyList())
        val result = diff.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            parseListRow(obj)
        }
        if (result.isNotEmpty()) {
            cachedSymbols = result
            cachedAt = now
        }
        return if (result.isNotEmpty()) result else cachedSymbols
    }

    suspend fun quote(symbol: String): AShareStock? {
        val secid = cachedSymbols.firstOrNull { it.symbol == symbol }?.secid
            ?: secidFor(symbol)
        return quotesBySecids(listOf(secid)).firstOrNull { it.symbol == symbol }
    }

    suspend fun quotes(symbols: List<String>): List<AShareStock> {
        if (symbols.isEmpty()) return emptyList()
        val secids = symbols.map { symbol ->
            cachedSymbols.firstOrNull { it.symbol == symbol }?.secid ?: secidFor(symbol)
        }
        return quotesBySecids(secids)
    }

    suspend fun search(query: String): List<AShareStock> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val body = http.get("$baseUrl/search?q=$encoded")
        val table = json.parseToJsonElement(body).jsonObject["QuotationCodeTable"] as? JsonObject
            ?: return emptyList()
        val data = table["Data"] as? JsonArray ?: return emptyList()
        val results = data.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val classify = obj.string("Classify")
            if (classify != "AStock") return@mapNotNull null
            val code = obj.string("Code") ?: return@mapNotNull null
            val name = obj.string("Name") ?: return@mapNotNull null
            val secid = obj.string("QuoteID") ?: secidFor(code)
            AShareStock(symbol = code, name = name, secid = secid)
        }
        if (results.isEmpty()) return emptyList()
        val quotes = quotesBySecids(results.map { it.secid }).associateBy { it.symbol }
        return results.map { stock -> quotes[stock.symbol]?.copy(name = stock.name) ?: stock }
    }

    private suspend fun quotesBySecids(secids: List<String>): List<AShareStock> {
        if (secids.isEmpty()) return emptyList()
        val body = http.get("$baseUrl/quotes?secids=${secids.joinToString(",")}")
        val data = json.parseToJsonElement(body).jsonObject["data"] as? JsonObject
            ?: return emptyList()
        val diff = data["diff"] as? JsonArray ?: return emptyList()
        return diff.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            parseBatchRow(obj)
        }
    }

    private fun parseListRow(obj: JsonObject): AShareStock? {
        val code = obj.string("f12") ?: return null
        val name = obj.string("f14") ?: return null
        if (code.isBlank() || name.isBlank()) return null
        val market = obj.string("f13") ?: "0"
        return AShareStock(
            symbol = code,
            name = name,
            secid = "$market.$code",
            price = obj.decimal("f2"),
            changePercent = obj.decimal("f3"),
            change = obj.decimal("f4"),
            volume = obj.decimal("f5"),
            amount = obj.decimal("f6"),
            high = obj.decimal("f15"),
            low = obj.decimal("f16"),
            open = obj.decimal("f17"),
            prevClose = obj.decimal("f18"),
            timestamp = System.currentTimeMillis(),
        )
    }

    private fun parseBatchRow(obj: JsonObject): AShareStock? {
        val code = obj.string("f12") ?: return null
        val name = obj.string("f14") ?: return null
        val market = obj.string("f13") ?: "0"
        return AShareStock(
            symbol = code,
            name = name,
            secid = "$market.$code",
            price = obj.decimal("f2"),
            changePercent = obj.decimal("f3"),
            change = obj.decimal("f4"),
            volume = obj.decimal("f5"),
            amount = obj.decimal("f6"),
            high = obj.decimal("f15"),
            low = obj.decimal("f16"),
            open = obj.decimal("f17"),
            prevClose = obj.decimal("f18"),
            timestamp = System.currentTimeMillis(),
        )
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.decimal(key: String): BigDecimal =
        string(key)?.toBigDecimalOrNull() ?: BigDecimal.ZERO

    private companion object {
        const val CACHE_MS = 5L * 60L * 1000L
    }
}

fun secidFor(symbol: String): String = if (symbol.startsWith("6")) {
    "1.$symbol"
} else {
    "0.$symbol"
}
