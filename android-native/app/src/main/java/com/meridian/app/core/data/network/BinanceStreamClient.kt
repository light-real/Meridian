package com.meridian.app.core.data.network

import com.meridian.app.BuildConfig
import com.meridian.app.core.model.BookLevel
import com.meridian.app.core.model.OrderBook
import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class BinanceStreamClient(
    private val client: OkHttpClient,
    private val json: Json,
) {
    private val wsBase = BuildConfig.BINANCE_WS_URL.trimEnd('/')

    fun depthStream(symbol: String, levels: Int = 20): Flow<OrderBook> {
        val normalized = symbol.uppercase()
        val url = "$wsBase/ws/${normalized.lowercase()}@depth$levels@100ms"
        return reconnectFlow(url) { payload -> parseDepth(payload, normalized) }
    }

    private fun <T> reconnectFlow(
        url: String,
        parser: (JsonObject) -> T?,
    ): Flow<T> = flow {
        var backoff = 1_000L
        while (currentCoroutineContext().isActive) {
            var received = false
            try {
                socketFlow(url).collect { message ->
                    val value = parser(message)
                    if (value != null) {
                        received = true
                        backoff = 1_000L
                        emit(value)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 网络断开、服务端关闭、解析失败都走重连
            }

            if (!currentCoroutineContext().isActive) break
            if (!received) {
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            } else {
                delay(1_000L)
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun socketFlow(url: String): Flow<JsonObject> = callbackFlow {
        val request = Request.Builder().url(url).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                android.util.Log.d("BinanceWS", "open $url")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    json.parseToJsonElement(text).jsonObject
                }.getOrNull()?.let { trySend(it) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                android.util.Log.w("BinanceWS", "failure $url", t)
                close(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                android.util.Log.d("BinanceWS", "closed $url code=$code reason=$reason")
                close()
            }
        }

        val socket = client.newWebSocket(request, listener)
        awaitClose { socket.close(1000, "client closed") }
    }

    private fun payload(message: JsonObject): JsonObject =
        (message["data"] as? JsonObject) ?: message

    private fun parseDepth(message: JsonObject, expectedSymbol: String): OrderBook? {
        val data = payload(message)
        val symbol = data.string("s")?.uppercase() ?: expectedSymbol
        val bids = data["b"] ?: data["bids"]
        val asks = data["a"] ?: data["asks"]
        if (bids == null && asks == null) return null
        return OrderBook(
            symbol = symbol,
            bids = parseLevels(bids as? JsonArray),
            asks = parseLevels(asks as? JsonArray),
        )
    }

    private fun parseLevels(array: JsonArray?): List<BookLevel> {
        if (array == null) return emptyList()
        return array.mapNotNull { element ->
            val row = element as? JsonArray ?: return@mapNotNull null
            if (row.size < 2) return@mapNotNull null
            val price = row[0].asDecimal() ?: return@mapNotNull null
            val quantity = row[1].asDecimal() ?: return@mapNotNull null
            BookLevel(price, quantity)
        }
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.decimal(key: String): BigDecimal? =
        string(key)?.toBigDecimalOrNull()

    private fun JsonElement.asDecimal(): BigDecimal? =
        jsonPrimitive.contentOrNull?.toBigDecimalOrNull()
}
