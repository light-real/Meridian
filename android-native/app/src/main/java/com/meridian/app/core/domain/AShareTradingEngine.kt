package com.meridian.app.core.domain

import com.meridian.app.core.model.AShareAccount
import com.meridian.app.core.model.AShareCalendar
import com.meridian.app.core.model.AShareFees
import com.meridian.app.core.model.AShareHolding
import com.meridian.app.core.model.AShareOrderRequest
import com.meridian.app.core.model.AShareSide
import com.meridian.app.core.model.AShareTrade
import com.meridian.app.core.model.AShareTradeError
import com.meridian.app.core.model.AShareTradeResult
import com.meridian.app.core.model.cleanNote
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZonedDateTime
import java.util.UUID

/**
 * A 股现货交易引擎。
 *
 * 只做现金现货：买入扣现金，卖出扣可卖数量；不做空、不加杠杆。
 * 第一版只支持市价成交，价格由行情页传入。
 */
object AShareTradingEngine {

    private const val CASH_SCALE = 2
    private const val COST_SCALE = 4
    private const val LOT_SIZE = 100

    fun buy(
        account: AShareAccount,
        request: AShareOrderRequest,
        now: ZonedDateTime = AShareCalendar.now(),
    ): AShareTradeResult {
        val settled = settle(account, now)
        val validation = validate(settled, request, now, isBuy = true)
        if (validation != null) return AShareTradeResult.Rejected(validation)

        val amount = request.price.multiply(BigDecimal(request.quantity))
            .setScale(CASH_SCALE, RoundingMode.HALF_UP)
        val commission = AShareFees.commission(amount)
        val transferFee = AShareFees.transferFee(amount)
        val total = amount.add(commission).add(transferFee)
            .setScale(CASH_SCALE, RoundingMode.HALF_UP)

        if (settled.cash < total) {
            return AShareTradeResult.Rejected(AShareTradeError.InsufficientCash)
        }

        val existing = settled.holdings.firstOrNull { it.symbol == request.symbol }
        val newQuantity = (existing?.quantity ?: 0) + request.quantity
        val newAvgCost = if (existing == null) {
            request.price.setScale(COST_SCALE, RoundingMode.HALF_UP)
        } else {
            existing.avgCost.multiply(BigDecimal(existing.quantity))
                .add(request.price.multiply(BigDecimal(request.quantity)))
                .divide(BigDecimal(newQuantity), COST_SCALE, RoundingMode.HALF_UP)
        }
        val updatedHolding = AShareHolding(
            symbol = request.symbol,
            name = request.name,
            secid = request.secid,
            quantity = newQuantity,
            // T+1：今天买入的部分不计入可卖数量
            availableQuantity = existing?.availableQuantity ?: 0,
            avgCost = newAvgCost,
        )
        val holdings = if (existing == null) {
            settled.holdings + updatedHolding
        } else {
            settled.holdings.map { if (it.symbol == request.symbol) updatedHolding else it }
        }

        val nowMillis = System.currentTimeMillis()
        val trade = AShareTrade(
            id = UUID.randomUUID().toString(),
            symbol = request.symbol,
            name = request.name,
            side = AShareSide.BUY,
            quantity = request.quantity,
            price = request.price,
            amount = amount,
            commission = commission,
            stampDuty = BigDecimal.ZERO,
            transferFee = transferFee,
            realizedPnl = null,
            createdAt = nowMillis,
            note = request.note.cleanNote(),
        )

        return AShareTradeResult.Success(
            account = settled.copy(
                cash = settled.cash.subtract(total).setScale(CASH_SCALE, RoundingMode.HALF_UP),
                holdings = holdings,
                trades = listOf(trade) + settled.trades,
            ),
            trade = trade,
        )
    }

    fun sell(
        account: AShareAccount,
        request: AShareOrderRequest,
        now: ZonedDateTime = AShareCalendar.now(),
    ): AShareTradeResult {
        val settled = settle(account, now)
        val validation = validate(settled, request, now, isBuy = false)
        if (validation != null) return AShareTradeResult.Rejected(validation)

        val holding = settled.holdings.firstOrNull { it.symbol == request.symbol }
            ?: return AShareTradeResult.Rejected(AShareTradeError.PositionNotFound)
        if (holding.availableQuantity < request.quantity) {
            return AShareTradeResult.Rejected(AShareTradeError.InsufficientShares)
        }

        val amount = request.price.multiply(BigDecimal(request.quantity))
            .setScale(CASH_SCALE, RoundingMode.HALF_UP)
        val commission = AShareFees.commission(amount)
        val stampDuty = AShareFees.stampDuty(amount, AShareSide.SELL)
        val transferFee = AShareFees.transferFee(amount)
        val totalFee = commission.add(stampDuty).add(transferFee)
            .setScale(CASH_SCALE, RoundingMode.HALF_UP)
        val net = amount.subtract(totalFee).setScale(CASH_SCALE, RoundingMode.HALF_UP)
        val realizedPnl = request.price.subtract(holding.avgCost)
            .multiply(BigDecimal(request.quantity))
            .subtract(totalFee)
            .setScale(CASH_SCALE, RoundingMode.HALF_UP)

        val remaining = holding.quantity - request.quantity
        val availableRemaining = holding.availableQuantity - request.quantity
        val holdings = if (remaining <= 0) {
            settled.holdings.filterNot { it.symbol == request.symbol }
        } else {
            settled.holdings.map {
                if (it.symbol == request.symbol) {
                    it.copy(
                        quantity = remaining,
                        availableQuantity = availableRemaining,
                    )
                } else {
                    it
                }
            }
        }

        val trade = AShareTrade(
            id = UUID.randomUUID().toString(),
            symbol = request.symbol,
            name = request.name,
            side = AShareSide.SELL,
            quantity = request.quantity,
            price = request.price,
            amount = amount,
            commission = commission,
            stampDuty = stampDuty,
            transferFee = transferFee,
            realizedPnl = realizedPnl,
            createdAt = System.currentTimeMillis(),
            note = request.note.cleanNote(),
        )

        return AShareTradeResult.Success(
            account = settled.copy(
                cash = settled.cash.add(net).setScale(CASH_SCALE, RoundingMode.HALF_UP),
                holdings = holdings,
                trades = listOf(trade) + settled.trades,
            ),
            trade = trade,
        )
    }

    /** 新的交易日把昨天的持仓全部解锁为可卖。 */
    fun settle(
        account: AShareAccount,
        now: ZonedDateTime = AShareCalendar.now(),
    ): AShareAccount {
        val today = AShareCalendar.tradingDate(now)
        if (!AShareCalendar.isTradingDay(today)) return account
        val todayText = today.toString()
        if (account.lastSettlementDate == todayText) return account

        return account.copy(
            holdings = account.holdings.map { it.copy(availableQuantity = it.quantity) },
            lastSettlementDate = todayText,
        )
    }

    private fun validate(
        account: AShareAccount,
        request: AShareOrderRequest,
        now: ZonedDateTime,
        isBuy: Boolean,
    ): AShareTradeError? {
        if (!AShareCalendar.isTradingSession(now)) return AShareTradeError.MarketClosed
        if (request.price.signum() <= 0 || request.prevClose.signum() <= 0) {
            return AShareTradeError.NoQuote
        }
        if (request.quantity <= 0) return AShareTradeError.InvalidQuantity
        if (isBuy && request.quantity % LOT_SIZE != 0) {
            return AShareTradeError.NotMultipleOfLot
        }

        val stockLimitUp = request.prevClose
            .multiply(BigDecimal.ONE.add(limitPercent(request.symbol, request.name)))
            .setScale(2, RoundingMode.HALF_UP)
        val stockLimitDown = request.prevClose
            .multiply(BigDecimal.ONE.subtract(limitPercent(request.symbol, request.name)))
            .setScale(2, RoundingMode.HALF_UP)
        if (hasPriceLimit(request.name) &&
            (request.price > stockLimitUp || request.price < stockLimitDown)
        ) {
            return AShareTradeError.PriceLimit
        }

        if (isBuy) {
            val amount = request.price.multiply(BigDecimal(request.quantity))
                .setScale(CASH_SCALE, RoundingMode.HALF_UP)
            val total = amount.add(AShareFees.total(amount, AShareSide.BUY))
            if (account.cash < total) return AShareTradeError.InsufficientCash
        }

        return null
    }

    private fun limitPercent(symbol: String, name: String): BigDecimal = when {
        name.contains("ST", ignoreCase = true) -> BigDecimal("0.05")
        symbol.startsWith("300") || symbol.startsWith("301") -> BigDecimal("0.20")
        symbol.startsWith("688") || symbol.startsWith("689") -> BigDecimal("0.20")
        symbol.startsWith("8") || symbol.startsWith("4") || symbol.startsWith("92") ->
            BigDecimal("0.30")
        else -> BigDecimal("0.10")
    }

    private fun hasPriceLimit(name: String): Boolean =
        !name.startsWith("N") && !name.startsWith("C")
}
