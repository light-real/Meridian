package com.meridian.app.core.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** A 股交易方向。 */
enum class AShareSide { BUY, SELL }

/**
 * A 股标的 + 最新报价。
 *
 * 列表页和交易页共用这个模型；[secid] 是东方财富的 market.code 标识，
 * 例如 1.600519 / 0.000001。
 */
data class AShareStock(
    val symbol: String,
    val name: String,
    val secid: String,
    val price: BigDecimal = BigDecimal.ZERO,
    val prevClose: BigDecimal = BigDecimal.ZERO,
    val open: BigDecimal = BigDecimal.ZERO,
    val high: BigDecimal = BigDecimal.ZERO,
    val low: BigDecimal = BigDecimal.ZERO,
    val change: BigDecimal = BigDecimal.ZERO,
    val changePercent: BigDecimal = BigDecimal.ZERO,
    val volume: BigDecimal = BigDecimal.ZERO,
    val amount: BigDecimal = BigDecimal.ZERO,
    val timestamp: Long = 0L,
) {
    val isSt: Boolean get() = name.contains("ST", ignoreCase = true)

    /** 新股 N / 次新 C 前 5 个交易日不设涨跌停。 */
    val hasPriceLimit: Boolean
        get() = !name.startsWith("N") && !name.startsWith("C")

    /** 涨跌停幅度：ST 5%，创业板/科创板 20%，北交所 30%，主板 10%。 */
    val limitPercent: BigDecimal
        get() = when {
            !hasPriceLimit -> BigDecimal.ZERO
            isSt -> BigDecimal("0.05")
            symbol.startsWith("300") || symbol.startsWith("301") -> BigDecimal("0.20")
            symbol.startsWith("688") || symbol.startsWith("689") -> BigDecimal("0.20")
            symbol.startsWith("8") || symbol.startsWith("4") || symbol.startsWith("92") ->
                BigDecimal("0.30")
            else -> BigDecimal("0.10")
        }

    val limitUp: BigDecimal
        get() = if (!hasPriceLimit || prevClose.signum() <= 0) {
            BigDecimal.ZERO
        } else {
            prevClose.multiply(BigDecimal.ONE.add(limitPercent))
                .setScale(2, RoundingMode.HALF_UP)
        }

    val limitDown: BigDecimal
        get() = if (!hasPriceLimit || prevClose.signum() <= 0) {
            BigDecimal.ZERO
        } else {
            prevClose.multiply(BigDecimal.ONE.subtract(limitPercent))
                .setScale(2, RoundingMode.HALF_UP)
        }

    val isSuspended: Boolean get() = price.signum() <= 0
}

data class AShareHolding(
    val symbol: String,
    val name: String,
    val secid: String,
    val quantity: Int,
    val availableQuantity: Int,
    val avgCost: BigDecimal,
) {
    fun marketValue(price: BigDecimal): BigDecimal =
        price.multiply(BigDecimal(quantity)).setScale(2, RoundingMode.HALF_UP)

    fun unrealizedPnl(price: BigDecimal): BigDecimal =
        price.subtract(avgCost).multiply(BigDecimal(quantity)).setScale(2, RoundingMode.HALF_UP)

    fun unrealizedPnlPercent(price: BigDecimal): BigDecimal =
        if (avgCost.signum() <= 0) {
            BigDecimal.ZERO
        } else {
            price.subtract(avgCost)
                .divide(avgCost, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal("100"))
        }
}

data class MarkedAShareHolding(
    val holding: AShareHolding,
    val price: BigDecimal,
    val changePercent: BigDecimal,
    val marketValue: BigDecimal,
    val unrealizedPnl: BigDecimal,
    val unrealizedPnlPercent: BigDecimal,
)

data class AShareAccount(
    val cash: BigDecimal = INITIAL_CASH,
    val initialCash: BigDecimal = INITIAL_CASH,
    val lastSettlementDate: String = "",
    val holdings: List<AShareHolding> = emptyList(),
    val trades: List<AShareTrade> = emptyList(),
) {
    fun totalMarketValue(prices: Map<String, BigDecimal>): BigDecimal =
        holdings.fold(BigDecimal.ZERO) { acc, holding ->
            acc.add(holding.marketValue(prices[holding.symbol] ?: holding.avgCost))
        }

    fun totalAssets(prices: Map<String, BigDecimal>): BigDecimal =
        cash.add(totalMarketValue(prices))

    fun totalUnrealizedPnl(prices: Map<String, BigDecimal>): BigDecimal =
        holdings.fold(BigDecimal.ZERO) { acc, holding ->
            acc.add(holding.unrealizedPnl(prices[holding.symbol] ?: holding.avgCost))
        }

    companion object {
        val INITIAL_CASH: BigDecimal = BigDecimal("100000.00")
    }
}

data class AShareTrade(
    val id: String,
    val symbol: String,
    val name: String,
    val side: AShareSide,
    val quantity: Int,
    val price: BigDecimal,
    val amount: BigDecimal,
    val commission: BigDecimal,
    val stampDuty: BigDecimal,
    val transferFee: BigDecimal,
    val realizedPnl: BigDecimal?,
    val createdAt: Long,
    val note: String? = null,
) {
    val totalFee: BigDecimal
        get() = commission.add(stampDuty).add(transferFee)
}

sealed interface AShareTradeError {
    data object MarketClosed : AShareTradeError
    data object NoQuote : AShareTradeError
    data object InvalidQuantity : AShareTradeError
    data object NotMultipleOfLot : AShareTradeError
    data object InsufficientCash : AShareTradeError
    data object InsufficientShares : AShareTradeError
    data object PriceLimit : AShareTradeError
    data object Suspended : AShareTradeError
    data object PositionNotFound : AShareTradeError
    data class Unknown(val message: String) : AShareTradeError
}

sealed interface AShareTradeResult {
    data class Success(
        val account: AShareAccount,
        val trade: AShareTrade,
    ) : AShareTradeResult

    data class Rejected(val error: AShareTradeError) : AShareTradeResult
}

data class AShareOrderRequest(
    val symbol: String,
    val name: String,
    val secid: String,
    val side: AShareSide,
    val quantity: Int,
    val price: BigDecimal,
    val prevClose: BigDecimal,
    val note: String? = null,
)

/** A 股费用：佣金 0.025%（最低 5 元）、卖出印花税 0.05%、过户费 0.001%。 */
object AShareFees {
    val COMMISSION_RATE: BigDecimal = BigDecimal("0.00025")
    val MIN_COMMISSION: BigDecimal = BigDecimal("5")
    val STAMP_DUTY_RATE: BigDecimal = BigDecimal("0.0005")
    val TRANSFER_FEE_RATE: BigDecimal = BigDecimal("0.00001")

    fun commission(amount: BigDecimal): BigDecimal =
        amount.multiply(COMMISSION_RATE)
            .max(MIN_COMMISSION)
            .setScale(2, RoundingMode.HALF_UP)

    fun stampDuty(amount: BigDecimal, side: AShareSide): BigDecimal =
        if (side == AShareSide.SELL) {
            amount.multiply(STAMP_DUTY_RATE).setScale(2, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO
        }

    fun transferFee(amount: BigDecimal): BigDecimal =
        amount.multiply(TRANSFER_FEE_RATE).setScale(2, RoundingMode.HALF_UP)

    fun total(amount: BigDecimal, side: AShareSide): BigDecimal =
        commission(amount).add(stampDuty(amount, side)).add(transferFee(amount))
}

/** A 股交易时段 / 交易日。按上海时区，周末和法定节假日休市。 */
object AShareCalendar {
    val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    private val MORNING_START: LocalTime = LocalTime.of(9, 30)
    private val MORNING_END: LocalTime = LocalTime.of(11, 30)
    private val AFTERNOON_START: LocalTime = LocalTime.of(13, 0)
    private val AFTERNOON_END: LocalTime = LocalTime.of(15, 0)

    // 2026 年节假日；后续年份在这里补。周末即使调休上班，股市也不开市。
    private val HOLIDAYS: Set<LocalDate> = buildSet {
        addAll(dateRange("2026-01-01", "2026-01-02"))
        addAll(dateRange("2026-02-16", "2026-02-20"))
        add(LocalDate.parse("2026-04-06"))
        addAll(dateRange("2026-05-01", "2026-05-05"))
        add(LocalDate.parse("2026-06-19"))
        add(LocalDate.parse("2026-09-25"))
        addAll(dateRange("2026-10-01", "2026-10-07"))
    }

    fun now(): ZonedDateTime = ZonedDateTime.now(ZONE)

    fun isTradingDay(date: LocalDate): Boolean =
        date.dayOfWeek != DayOfWeek.SATURDAY &&
            date.dayOfWeek != DayOfWeek.SUNDAY &&
            date !in HOLIDAYS

    fun isTradingSession(now: ZonedDateTime = now()): Boolean {
        if (!isTradingDay(now.toLocalDate())) return false
        val time = now.toLocalTime()
        return (time >= MORNING_START && time <= MORNING_END) ||
            (time >= AFTERNOON_START && time <= AFTERNOON_END)
    }

    fun tradingDate(now: ZonedDateTime = now()): LocalDate = now.toLocalDate()

    private fun dateRange(start: String, end: String): List<LocalDate> {
        val from = LocalDate.parse(start)
        val to = LocalDate.parse(end)
        return generateSequence(from) { it.plusDays(1) }
            .takeWhile { !it.isAfter(to) }
            .toList()
    }
}

fun aShareSecid(symbol: String): String = when {
    symbol.startsWith("6") -> "1.$symbol"
    else -> "0.$symbol"
}
