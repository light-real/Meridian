package com.cryptosim.app.core.model

import java.math.BigDecimal
import java.math.RoundingMode

enum class Side {
    LONG,
    SHORT;

    val cloudValue: String
        get() = if (this == LONG) "buy" else "sell"

    companion object {
        fun fromCloud(value: String?): Side =
            if (value == "sell") SHORT else LONG
    }
}

enum class TradeType {
    OPEN,
    CLOSE,
    LIQUIDATION,
}

enum class AmountMode {
    MARGIN,
    NOTIONAL,
}

data class SymbolInfo(
    val symbol: String,
    val baseAsset: String,
    val quoteAsset: String = "USDT",
    val pricePrecision: Int = 2,
    val quantityPrecision: Int = 3,
    val minNotional: BigDecimal = BigDecimal("5"),
    val tickSize: BigDecimal = BigDecimal("0.01"),
    val stepSize: BigDecimal = BigDecimal("0.001"),
) {
    val displayName: String get() = baseAsset

    fun rules(): SymbolRules = SymbolRules(
        priceScale = pricePrecision,
        quantityScale = quantityPrecision,
        minNotional = minNotional,
        stepSize = stepSize,
    )
}

data class SymbolRules(
    val priceScale: Int,
    val quantityScale: Int,
    val minNotional: BigDecimal,
    val stepSize: BigDecimal,
)

data class Ticker(
    val symbol: String,
    val lastPrice: BigDecimal,
    val priceChange: BigDecimal,
    val priceChangePercent: BigDecimal,
    val highPrice: BigDecimal,
    val lowPrice: BigDecimal,
    val quoteVolume: BigDecimal,
)

data class Kline(
    val openTime: Long,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: BigDecimal,
    val closeTime: Long,
    val isClosed: Boolean,
)

data class BookLevel(
    val price: BigDecimal,
    val quantity: BigDecimal,
)

data class OrderBook(
    val symbol: String,
    val bids: List<BookLevel>,
    val asks: List<BookLevel>,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class Position(
    val id: String,
    val symbol: String,
    val baseAsset: String,
    val side: Side,
    val quantity: BigDecimal,
    val entryPrice: BigDecimal,
    val margin: BigDecimal,
    val leverage: Int,
    val liquidationPrice: BigDecimal,
    val openedAt: Long,
) {
    val notional: BigDecimal get() = quantity.multiply(entryPrice)

    fun unrealizedPnl(markPrice: BigDecimal): BigDecimal = when (side) {
        Side.LONG -> markPrice.subtract(entryPrice).multiply(quantity)
        Side.SHORT -> entryPrice.subtract(markPrice).multiply(quantity)
    }

    fun roi(markPrice: BigDecimal): BigDecimal =
        if (margin.signum() == 0) {
            BigDecimal.ZERO
        } else {
            unrealizedPnl(markPrice)
                .divide(margin, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal("100"))
        }
}

enum class TradeTypeFilter {
    ALL,
    OPEN,
    CLOSE,
}

data class Trade(
    val id: String,
    val symbol: String,
    val baseAsset: String,
    val side: Side,
    val type: TradeType,
    val quantity: BigDecimal,
    val price: BigDecimal,
    val entryPrice: BigDecimal?,
    val margin: BigDecimal,
    val leverage: Int,
    val fee: BigDecimal,
    val pnl: BigDecimal?,
    val openedAt: Long,
    val createdAt: Long,
)

data class Account(
    val cash: BigDecimal,
    val initialCash: BigDecimal,
    val positions: List<Position> = emptyList(),
    val trades: List<Trade> = emptyList(),
) {
    fun markPriceOf(position: Position, marks: Map<String, BigDecimal>): BigDecimal =
        marks[position.symbol] ?: position.entryPrice

    fun totalMargin(): BigDecimal =
        positions.fold(BigDecimal.ZERO) { acc, p -> acc.add(p.margin) }

    fun totalUnrealizedPnl(marks: Map<String, BigDecimal>): BigDecimal =
        positions.fold(BigDecimal.ZERO) { acc, p ->
            acc.add(p.unrealizedPnl(markPriceOf(p, marks)))
        }

    fun totalAssets(marks: Map<String, BigDecimal>): BigDecimal =
        cash.add(positions.fold(BigDecimal.ZERO) { acc, p ->
            val mark = markPriceOf(p, marks)
            acc.add(p.margin).add(p.unrealizedPnl(mark))
        })

    fun totalPnl(marks: Map<String, BigDecimal>): BigDecimal =
        totalAssets(marks).subtract(initialCash)

    fun markedPositions(marks: Map<String, BigDecimal>): List<MarkedPosition> =
        positions.map { p ->
            val mark = markPriceOf(p, marks)
            MarkedPosition(
                position = p,
                markPrice = mark,
                unrealizedPnl = p.unrealizedPnl(mark),
                roi = p.roi(mark),
            )
        }
}

data class MarkedPosition(
    val position: Position,
    val markPrice: BigDecimal,
    val unrealizedPnl: BigDecimal,
    val roi: BigDecimal,
)

data class OrderRequest(
    val symbol: String,
    val baseAsset: String,
    val side: Side,
    val notional: BigDecimal,
    val leverage: Int,
    val rules: SymbolRules,
)

data class CloseRequest(
    val positionId: String,
    val quantity: BigDecimal,
    val markPrice: BigDecimal,
)

sealed interface TradeError {
    data object InvalidLeverage : TradeError
    data object InvalidAmount : TradeError
    data object BelowMinNotional : TradeError
    data object InsufficientBalance : TradeError
    data object OppositePositionExists : TradeError
    data object LeverageMismatch : TradeError
    data object PositionNotFound : TradeError
    data object QuantityTooSmall : TradeError
    data class Unknown(val message: String) : TradeError
}

sealed interface TradeOutcome {
    data class Success(
        val account: Account,
        val trade: Trade,
        val clamped: Boolean = false,
    ) : TradeOutcome

    data class Rejected(val error: TradeError) : TradeOutcome
}

data class LiquidationOutcome(
    val account: Account,
    val liquidated: List<Position>,
)

data class OrderPreview(
    val notional: BigDecimal = BigDecimal.ZERO,
    val margin: BigDecimal = BigDecimal.ZERO,
    val quantity: BigDecimal = BigDecimal.ZERO,
    val fee: BigDecimal = BigDecimal.ZERO,
    val liquidationPrice: BigDecimal = BigDecimal.ZERO,
    val requiredCash: BigDecimal = BigDecimal.ZERO,
    val canSubmit: Boolean = false,
    val error: TradeError? = null,
)

data class ClosePreview(
    val quantity: BigDecimal = BigDecimal.ZERO,
    val ratioPercent: BigDecimal = BigDecimal.ZERO,
    val pnl: BigDecimal = BigDecimal.ZERO,
    val fee: BigDecimal = BigDecimal.ZERO,
    val returnAmount: BigDecimal = BigDecimal.ZERO,
    val canClose: Boolean = false,
    val clamped: Boolean = false,
)
