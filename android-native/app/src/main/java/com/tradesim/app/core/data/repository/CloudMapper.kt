package com.tradesim.app.core.data.repository

import com.tradesim.app.core.AppConstants
import com.tradesim.app.core.data.supabase.CloudUserData
import com.tradesim.app.core.domain.TradingEngine
import com.tradesim.app.core.domain.formatFullTime
import com.tradesim.app.core.model.Account
import com.tradesim.app.core.model.MarginMode
import com.tradesim.app.core.model.Position
import com.tradesim.app.core.model.Side
import com.tradesim.app.core.model.Trade
import com.tradesim.app.core.model.TradeType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 与现有 Supabase `user_data.stocks/history` 结构保持兼容，
 * 这样原生 App 和旧版 H5 可以读写同一份云端数据。
 */
class CloudMapper {

    fun toCloud(account: Account, marks: Map<String, BigDecimal>): JsonObject = buildJsonObject {
        put("cash", JsonPrimitive(account.cash))
        put(
            "stocks",
            buildJsonArray {
                account.positions.forEach { add(positionToJson(it)) }
            },
        )
        put(
            "history",
            buildJsonArray {
                account.trades.take(AppConstants.MAX_CLOUD_HISTORY).forEach { add(tradeToJson(it)) }
            },
        )
        put("total_assets", JsonPrimitive(account.totalAssets(marks)))
        put("profit", JsonPrimitive(account.totalPnl(marks)))
        put("updated_at", Instant.now().toString())
    }

    fun fromCloud(dto: CloudUserData): Account {
        val cash = dto.cash.decimalOrNull() ?: AppConstants.INITIAL_CASH
        val positions = dto.stocks.arrayOrNull()
            ?.mapNotNull { positionFromJson(it as? JsonObject ?: return@mapNotNull null) }
            ?: emptyList()
        val trades = dto.history.arrayOrNull()
            ?.mapNotNull { tradeFromJson(it as? JsonObject ?: return@mapNotNull null) }
            ?.sortedByDescending { it.createdAt }
            ?.take(AppConstants.MAX_CLOUD_HISTORY)
            ?: emptyList()
        return Account(
            cash = cash,
            initialCash = AppConstants.INITIAL_CASH,
            positions = positions,
            trades = trades,
        )
    }

    private fun positionToJson(position: Position): JsonObject = buildJsonObject {
        put("code", position.symbol)
        put("name", position.baseAsset)
        put("symbol", position.baseAsset)
        put("quantity", JsonPrimitive(position.quantity))
        put("cost", JsonPrimitive(position.entryPrice.multiply(position.quantity)))
        put("currentPrice", JsonPrimitive(position.entryPrice))
        put("buyPrice", JsonPrimitive(position.entryPrice))
        put("leverage", position.leverage)
        put("margin", JsonPrimitive(position.margin))
        put("tradeType", position.side.cloudValue)
        put("liqPrice", position.liquidationPrice.toPlainString())
        put("marginMode", position.marginMode.name)
        position.takeProfitPrice?.let { put("takeProfit", it.toPlainString()) }
        position.stopLossPrice?.let { put("stopLoss", it.toPlainString()) }
        put("buyTime", formatFullTime(position.openedAt))
    }

    private fun tradeToJson(trade: Trade): JsonObject = buildJsonObject {
        put("type", trade.cloudType())
        put("code", trade.symbol)
        put("name", trade.baseAsset)
        put("symbol", trade.baseAsset)
        put("quantity", JsonPrimitive(trade.quantity))
        put("price", JsonPrimitive(trade.price))
        put("leverage", trade.leverage)
        put("margin", JsonPrimitive(trade.margin))
        put("time", formatFullTime(trade.createdAt))
        if (trade.type == TradeType.OPEN) {
            val notional = trade.price.multiply(trade.quantity)
            put("amount", JsonPrimitive(notional))
            put("notional", JsonPrimitive(notional))
        } else {
            put("avgPrice", JsonPrimitive(trade.entryPrice ?: trade.price))
            put("pnl", JsonPrimitive(trade.pnl ?: BigDecimal.ZERO))
            put("closeFee", JsonPrimitive(trade.fee))
            put(
                "returnAmount",
                JsonPrimitive(
                    trade.margin.add(trade.pnl ?: BigDecimal.ZERO).max(BigDecimal.ZERO),
                ),
            )
            put("isPartial", false)
            put("isLiquidation", trade.type == TradeType.LIQUIDATION)
            put("openTime", formatFullTime(trade.openedAt))
        }
    }

    private fun positionFromJson(obj: JsonObject): Position? {
        val symbol = obj["code"].stringOrNull()
            ?: obj["symbol"].stringOrNull()
            ?: return null
        val quantity = obj["quantity"].decimalOrNull() ?: return null
        if (quantity.signum() <= 0) return null
        val cost = obj["cost"].decimalOrNull()
            ?: obj["entryPrice"].decimalOrNull()?.multiply(quantity)
            ?: return null
        val entryPrice = cost.divide(quantity, 8, RoundingMode.HALF_UP)
        if (entryPrice.signum() <= 0) return null
        val side = Side.fromCloud(obj["tradeType"].stringOrNull() ?: obj["side"].stringOrNull())
        val leverage = obj["leverage"].decimalOrNull()?.toInt()?.coerceIn(1, 150) ?: 1
        val margin = obj["margin"].decimalOrNull()
            ?: entryPrice.multiply(quantity).divide(BigDecimal(leverage), 8, RoundingMode.HALF_UP)
        val liquidationPrice = obj["liqPrice"].decimalOrNull()
            ?: TradingEngine.liquidationPrice(entryPrice, side, leverage)
        val openedAt = parseTime(obj["buyTime"].stringOrNull()) ?: System.currentTimeMillis()
        val baseAsset = obj["name"].stringOrNull()
            ?: obj["symbol"].stringOrNull()
            ?: symbol.removeSuffix("USDT")
        return Position(
            id = obj["id"].stringOrNull() ?: UUID.randomUUID().toString(),
            symbol = symbol,
            baseAsset = baseAsset,
            side = side,
            quantity = quantity,
            entryPrice = entryPrice,
            margin = margin,
            leverage = leverage,
            liquidationPrice = liquidationPrice,
            openedAt = openedAt,
            marginMode = runCatching {
                MarginMode.valueOf(obj["marginMode"].stringOrNull().orEmpty())
            }.getOrDefault(MarginMode.ISOLATED),
            takeProfitPrice = obj["takeProfit"].decimalOrNull(),
            stopLossPrice = obj["stopLoss"].decimalOrNull(),
        )
    }

    private fun tradeFromJson(obj: JsonObject): Trade? {
        val typeValue = obj["type"].stringOrNull() ?: return null
        val parsed = when (typeValue) {
            "buy" -> TradeType.OPEN to Side.LONG
            "sell" -> TradeType.OPEN to Side.SHORT
            "close_buy" -> TradeType.CLOSE to Side.LONG
            "close_sell" -> TradeType.CLOSE to Side.SHORT
            "funding" -> TradeType.FUNDING to Side.LONG
            "take_profit" -> TradeType.TAKE_PROFIT to Side.LONG
            "stop_loss" -> TradeType.STOP_LOSS to Side.LONG
            else -> return null
        }
        val isLiquidation = obj["isLiquidation"].booleanOrFalse()
        val type = if (isLiquidation && parsed.first == TradeType.CLOSE) {
            TradeType.LIQUIDATION
        } else {
            parsed.first
        }
        val symbol = obj["code"].stringOrNull()
            ?: obj["symbol"].stringOrNull()
            ?: return null
        val quantity = obj["quantity"].decimalOrNull() ?: return null
        if (quantity.signum() <= 0) return null
        val price = obj["price"].decimalOrNull() ?: BigDecimal.ZERO
        val leverage = obj["leverage"].decimalOrNull()?.toInt()?.coerceIn(1, 150) ?: 1
        val margin = obj["margin"].decimalOrNull() ?: BigDecimal.ZERO
        val fee = obj["closeFee"].decimalOrNull()
            ?: obj["fee"].decimalOrNull()
            ?: BigDecimal.ZERO
        val pnl = if (type == TradeType.OPEN) {
            null
        } else {
            obj["pnl"].decimalOrNull() ?: BigDecimal.ZERO
        }
        val createdAt = parseTime(obj["time"].stringOrNull()) ?: System.currentTimeMillis()
        val openedAt = parseTime(obj["openTime"].stringOrNull()) ?: createdAt
        val baseAsset = obj["name"].stringOrNull()
            ?: obj["symbol"].stringOrNull()
            ?: symbol.removeSuffix("USDT")
        return Trade(
            id = obj["id"].stringOrNull() ?: UUID.randomUUID().toString(),
            symbol = symbol,
            baseAsset = baseAsset,
            side = parsed.second,
            type = type,
            quantity = quantity,
            price = price,
            entryPrice = obj["avgPrice"].decimalOrNull(),
            margin = margin,
            leverage = leverage,
            fee = fee,
            pnl = pnl,
            openedAt = openedAt,
            createdAt = createdAt,
        )
    }

    private fun Trade.cloudType(): String = when (type) {
        TradeType.OPEN -> side.cloudValue
        TradeType.CLOSE, TradeType.LIQUIDATION -> if (side == Side.LONG) "close_buy" else "close_sell"
        TradeType.FUNDING -> "funding"
        TradeType.TAKE_PROFIT -> "take_profit"
        TradeType.STOP_LOSS -> "stop_loss"
    }

    private fun JsonElement?.stringOrNull(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement?.decimalOrNull(): BigDecimal? =
        stringOrNull()?.toBigDecimalOrNull()

    private fun JsonElement?.booleanOrFalse(): Boolean =
        (this as? JsonPrimitive)?.booleanOrNull ?: false

    private fun JsonElement?.arrayOrNull(): JsonArray? = this as? JsonArray

    private fun parseTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        runCatching { return Instant.parse(value).toEpochMilli() }
        return runCatching {
            LocalDateTime.parse(value, FULL_TIME_FORMATTER)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }

    private companion object {
        val FULL_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
