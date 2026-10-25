package com.tradesim.app.core.model

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
    FUNDING,
    TAKE_PROFIT,
    STOP_LOSS,
}

enum class AmountMode {
    MARGIN,
    NOTIONAL,
}

enum class OrderType {
    MARKET,
    LIMIT,
    ;

    val label: String get() = if (this == MARKET) "市价" else "限价"
}

/** 保证金模式：逐仓锁保证金，全仓用账户余额统一担保。 */
enum class MarginMode {
    ISOLATED,
    CROSS,
    ;

    val label: String get() = if (this == ISOLATED) "逐仓" else "全仓"
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

data class BookLevel(
    val price: BigDecimal,
    val quantity: BigDecimal,
)

/** 资金费率快照：下一次结算的费率、时间和标记价格。 */
data class FundingRate(
    val symbol: String,
    val rate: BigDecimal,
    val nextFundingTime: Long,
    val markPrice: BigDecimal,
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
    val marginMode: MarginMode = MarginMode.ISOLATED,
    val takeProfitPrice: BigDecimal? = null,
    val stopLossPrice: BigDecimal? = null,
) {
    val notional: BigDecimal get() = quantity.multiply(entryPrice)

    val isCross: Boolean get() = marginMode == MarginMode.CROSS

    fun notionalAt(markPrice: BigDecimal): BigDecimal = quantity.multiply(markPrice)

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
    FUNDING,
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

/** 限价委托：等价格到了才成交。 */
data class LimitOrder(
    val id: String,
    val symbol: String,
    val baseAsset: String,
    val side: Side,
    val notional: BigDecimal,
    val limitPrice: BigDecimal,
    val leverage: Int,
    val marginMode: MarginMode = MarginMode.ISOLATED,
    val takeProfitPrice: BigDecimal? = null,
    val stopLossPrice: BigDecimal? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** 挂单时预先冻结的现金（逐仓 = 保证金 + 手续费，全仓 = 手续费）。 */
    val frozenCash: BigDecimal = BigDecimal.ZERO,
) {
    /** 买（做多）在市价跌到限价以下成交，卖（做空）在市价涨到限价以上成交。 */
    fun isTriggered(markPrice: BigDecimal): Boolean = when (side) {
        Side.LONG -> markPrice <= limitPrice
        Side.SHORT -> markPrice >= limitPrice
    }
}

data class Account(
    val cash: BigDecimal,
    val initialCash: BigDecimal,
    val positions: List<Position> = emptyList(),
    val trades: List<Trade> = emptyList(),
    val limitOrders: List<LimitOrder> = emptyList(),
) {
    fun markPriceOf(position: Position, marks: Map<String, BigDecimal>): BigDecimal =
        marks[position.symbol] ?: position.entryPrice

    fun totalMargin(): BigDecimal =
        positions
            .filter { it.marginMode == MarginMode.ISOLATED }
            .fold(BigDecimal.ZERO) { acc, p -> acc.add(p.margin) }

    fun totalUnrealizedPnl(marks: Map<String, BigDecimal>): BigDecimal =
        positions.fold(BigDecimal.ZERO) { acc, p ->
            acc.add(p.unrealizedPnl(markPriceOf(p, marks)))
        }

    fun totalAssets(marks: Map<String, BigDecimal>): BigDecimal =
        cash.add(positions.fold(BigDecimal.ZERO) { acc, p ->
            val mark = markPriceOf(p, marks)
            val locked = if (p.marginMode == MarginMode.ISOLATED) p.margin else BigDecimal.ZERO
            acc.add(locked).add(p.unrealizedPnl(mark))
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
    val marginMode: MarginMode = MarginMode.ISOLATED,
    val takeProfitPrice: BigDecimal? = null,
    val stopLossPrice: BigDecimal? = null,
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
    data object MarginModeMismatch : TradeError
    data object InvalidTriggerPrice : TradeError
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

/** 止盈止损触发的结果。 */
data class TriggerOutcome(
    val account: Account,
    val closed: List<Position>,
    val trades: List<Trade>,
)

/** 调整保证金的结果。 */
data class MarginAdjustment(
    val account: Account? = null,
    val error: TradeError? = null,
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
    val marginMode: MarginMode = MarginMode.ISOLATED,
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
