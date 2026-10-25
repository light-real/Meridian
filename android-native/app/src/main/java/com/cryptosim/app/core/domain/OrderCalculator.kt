package com.cryptosim.app.core.domain

import com.cryptosim.app.core.model.Account
import com.cryptosim.app.core.model.AmountMode
import com.cryptosim.app.core.model.ClosePreview
import com.cryptosim.app.core.model.OrderPreview
import com.cryptosim.app.core.model.Position
import com.cryptosim.app.core.model.Side
import com.cryptosim.app.core.model.SymbolRules
import com.cryptosim.app.core.model.TradeError
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
        if (notional < rules.minNotional) {
            return OrderPreview(
                notional = notional,
                error = TradeError.BelowMinNotional,
            )
        }

        val margin = notional.divide(BigDecimal(leverage), 8, RoundingMode.HALF_UP)
        val fee = notional.multiply(TradingEngine.FEE_RATE).setScale(8, RoundingMode.HALF_UP)
        val requiredCash = margin.add(fee)
        val qtyScale = maxOf(rules.quantityScale, rules.stepSize.scale())
        val quantity = notional
            .divide(markPrice, 8, RoundingMode.DOWN)
            .floorToStep(rules.stepSize, qtyScale)
        val liquidationPrice = TradingEngine.liquidationPrice(markPrice, side, leverage)

        val error = when {
            quantity.signum() <= 0 -> TradeError.QuantityTooSmall
            requiredCash > account.cash -> TradeError.InsufficientBalance
            else -> null
        }

        return OrderPreview(
            notional = notional,
            margin = margin,
            quantity = quantity,
            fee = fee,
            liquidationPrice = liquidationPrice,
            requiredCash = requiredCash,
            canSubmit = error == null,
            error = error,
        )
    }

    fun amountForPercent(
        account: Account,
        leverage: Int,
        mode: AmountMode,
        percent: BigDecimal,
    ): BigDecimal {
        val maxNotional = TradingEngine.maxNotional(account.cash, leverage)
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
        val releasedMargin = if (isFull) {
            position.margin
        } else {
            position.margin
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
