package com.tradesim.app.core.domain

import com.tradesim.app.core.model.Account
import com.tradesim.app.core.model.AmountMode
import com.tradesim.app.core.model.ClosePreview
import com.tradesim.app.core.model.MarginMode
import com.tradesim.app.core.model.OrderPreview
import com.tradesim.app.core.model.Position
import com.tradesim.app.core.model.Side
import com.tradesim.app.core.model.SymbolRules
import com.tradesim.app.core.model.TradeError
import java.math.BigDecimal
import java.math.RoundingMode

object OrderCalculator {

    fun preview(
        account: Account,
        side: Side,
        leverage: Int,
        mode: AmountMode,
        amount: BigDecimal,
        markPrice: BigDecimal,
        rules: SymbolRules,
        marginMode: MarginMode = MarginMode.ISOLATED,
    ): OrderPreview {
        if (leverage !in 1..150) return OrderPreview(error = TradeError.InvalidLeverage)
        if (amount.signum() <= 0 || markPrice.signum() <= 0) {
            return OrderPreview(error = TradeError.InvalidAmount)
        }

        val notional = if (mode == AmountMode.MARGIN) {
            amount.multiply(BigDecimal(leverage))
        } else {
            amount
        }

        // 和引擎保持一致：先按 stepSize 取整，再用实际成交量算保证金和手续费
        val qtyScale = maxOf(rules.quantityScale, rules.stepSize.scale())
        val quantity = notional
            .divide(markPrice, 8, RoundingMode.DOWN)
            .floorToStep(rules.stepSize, qtyScale)
        val executedNotional = quantity.multiply(markPrice).setScale(8, RoundingMode.HALF_UP)

        if (quantity.signum() <= 0) {
            return OrderPreview(
                notional = notional,
                error = TradeError.QuantityTooSmall,
                marginMode = marginMode,
            )
        }
        if (executedNotional < rules.minNotional) {
            return OrderPreview(
                notional = executedNotional,
                quantity = quantity,
                error = TradeError.BelowMinNotional,
                marginMode = marginMode,
            )
        }

        val margin = executedNotional.divide(BigDecimal(leverage), 8, RoundingMode.HALF_UP)
        val fee = executedNotional.multiply(TradingEngine.FEE_RATE).setScale(8, RoundingMode.HALF_UP)
        val requiredCash = margin.add(fee)
        // 全仓下已有仓位的保证金也占额度，不能重复使用同一笔余额
        val crossUsed = if (marginMode == MarginMode.CROSS) {
            account.positions
                .filter { it.marginMode == MarginMode.CROSS }
                .fold(BigDecimal.ZERO) { acc, position -> acc.add(position.margin) }
        } else {
            BigDecimal.ZERO
        }
        val available = account.cash.subtract(crossUsed)
        // 全仓没有单仓强平价，交给账户权益统一判断
        val liquidationPrice = if (marginMode == MarginMode.CROSS) {
            BigDecimal.ZERO
        } else {
            TradingEngine.liquidationPriceFor(markPrice, side, quantity, margin)
        }

        val error = when {
            requiredCash > available -> TradeError.InsufficientBalance
            else -> null
        }

        return OrderPreview(
            notional = executedNotional,
            margin = margin,
            quantity = quantity,
            fee = fee,
            liquidationPrice = liquidationPrice,
            requiredCash = requiredCash,
            canSubmit = error == null,
            error = error,
            marginMode = marginMode,
        )
    }

    fun amountForPercent(
        account: Account,
        leverage: Int,
        mode: AmountMode,
        percent: BigDecimal,
        marginMode: MarginMode = MarginMode.ISOLATED,
    ): BigDecimal {
        // 全仓下已有仓位的保证金占用额度，不能拿整个余额再算一遍
        val used = if (marginMode == MarginMode.CROSS) {
            account.positions
                .filter { it.marginMode == MarginMode.CROSS }
                .fold(BigDecimal.ZERO) { acc, position -> acc.add(position.margin) }
        } else {
            BigDecimal.ZERO
        }
        val available = account.cash.subtract(used).max(BigDecimal.ZERO)
        val maxNotional = TradingEngine.maxNotional(available, leverage)
        val targetNotional = maxNotional.multiply(percent)
        val amount = if (mode == AmountMode.MARGIN) {
            targetNotional.divide(BigDecimal(leverage), 2, RoundingMode.DOWN)
        } else {
            targetNotional
        }
        return amount.max(BigDecimal.ZERO)
    }

    fun previewClose(
        position: Position,
        requestedQuantity: BigDecimal,
        markPrice: BigDecimal,
    ): ClosePreview {
        if (requestedQuantity.signum() <= 0 || markPrice.signum() <= 0) {
            return ClosePreview()
        }
        val applied = requestedQuantity.min(position.quantity)
        if (applied.signum() <= 0) return ClosePreview()

        val isFull = applied.compareTo(position.quantity) == 0
        val ratio = applied.divide(position.quantity, 16, RoundingMode.HALF_UP)
        val releasedMargin = when {
            position.isCross -> BigDecimal.ZERO
            isFull -> position.margin
            else -> position.margin
                .multiply(ratio)
                .setScale(8, RoundingMode.HALF_UP)
                .min(position.margin)
        }
        val notional = applied.multiply(markPrice).setScale(8, RoundingMode.HALF_UP)
        val fee = notional.multiply(TradingEngine.FEE_RATE).setScale(8, RoundingMode.HALF_UP)
        val pnl = when (position.side) {
            Side.LONG -> markPrice.subtract(position.entryPrice).multiply(applied)
            Side.SHORT -> position.entryPrice.subtract(markPrice).multiply(applied)
        }.setScale(8, RoundingMode.HALF_UP)
        val pnlNet = pnl.subtract(fee).setScale(8, RoundingMode.HALF_UP)
        val returnAmount = releasedMargin.add(pnlNet).max(BigDecimal.ZERO)

        return ClosePreview(
            quantity = applied,
            ratioPercent = ratio.multiply(BigDecimal("100")),
            pnl = pnlNet,
            fee = fee,
            returnAmount = returnAmount,
            canClose = true,
            clamped = applied < requestedQuantity,
        )
    }
}

private fun BigDecimal.floorToStep(step: BigDecimal, scale: Int): BigDecimal {
    if (step.signum() <= 0) return setScale(scale, RoundingMode.DOWN)
    val steps = divide(step, 0, RoundingMode.DOWN)
    return steps.multiply(step).setScale(scale, RoundingMode.DOWN)
}
