package com.tradesim.app.core.domain

import com.tradesim.app.core.model.Account
import com.tradesim.app.core.model.CloseRequest
import com.tradesim.app.core.model.LiquidationOutcome
import com.tradesim.app.core.model.OrderRequest
import com.tradesim.app.core.model.Position
import com.tradesim.app.core.model.Side
import com.tradesim.app.core.model.Trade
import com.tradesim.app.core.model.TradeError
import com.tradesim.app.core.model.TradeOutcome
import com.tradesim.app.core.model.TradeType
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * 模拟永续合约的资金引擎。
 *
 * 规则：
 * - 开仓扣 `保证金 + 手续费`
 * - 同方向加仓合并，反方向必须显式平仓，避免把多空仓合并成错误方向
 * - 平仓数量 clamp 到持仓数量，永远不产生负持仓
 * - 强平按 `浮亏 <= -保证金 × (1 - 维持保证金率)` 判定
 */
object TradingEngine {

    val FEE_RATE: BigDecimal = BigDecimal("0.001")
    val MAINTENANCE_MARGIN_RATE: BigDecimal = BigDecimal("0.005")

    private const val MONEY_SCALE = 8
    private const val QTY_SCALE = 8
    private const val PRICE_SCALE = 8
    private val EPS = BigDecimal("0.00000001")
    private val MAX_LEVERAGE = 150

    fun open(
        account: Account,
        request: OrderRequest,
        markPrice: BigDecimal,
    ): TradeOutcome {
        if (request.leverage !in 1..MAX_LEVERAGE) return TradeOutcome.Rejected(TradeError.InvalidLeverage)
        if (request.notional.signum() <= 0 || markPrice.signum() <= 0) {
            return TradeOutcome.Rejected(TradeError.InvalidAmount)
        }
        if (request.notional < request.rules.minNotional) {
            return TradeOutcome.Rejected(TradeError.BelowMinNotional)
        }

        val margin = request.notional.divide(BigDecimal(request.leverage), MONEY_SCALE, RoundingMode.HALF_UP)
        val fee = request.notional.multiply(FEE_RATE).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        val requiredCash = margin.add(fee)
        if (account.cash < requiredCash) {
            return TradeOutcome.Rejected(TradeError.InsufficientBalance)
        }

        val qtyScale = maxOf(request.rules.quantityScale, request.rules.stepSize.scale())
        val quantity = request.notional
            .divide(markPrice, QTY_SCALE, RoundingMode.DOWN)
            .floorToStep(request.rules.stepSize, qtyScale)
        if (quantity.signum() <= 0) {
            return TradeOutcome.Rejected(TradeError.QuantityTooSmall)
        }

        val existing = account.positions.firstOrNull { it.symbol == request.symbol }
        if (existing != null) {
            if (existing.side != request.side) {
                return TradeOutcome.Rejected(TradeError.OppositePositionExists)
            }
            if (existing.leverage != request.leverage) {
                return TradeOutcome.Rejected(TradeError.LeverageMismatch)
            }
        }

        val now = System.currentTimeMillis()
        val position: Position
        val positions: List<Position>
        if (existing != null) {
            val newQuantity = existing.quantity.add(quantity)
            val newEntry = existing.entryPrice.multiply(existing.quantity)
                .add(markPrice.multiply(quantity))
                .divide(newQuantity, PRICE_SCALE, RoundingMode.HALF_UP)
            val newMargin = existing.margin.add(margin)
            position = existing.copy(
                quantity = newQuantity,
                entryPrice = newEntry,
                margin = newMargin,
                liquidationPrice = liquidationPrice(newEntry, existing.side, existing.leverage),
            )
            positions = account.positions.map { if (it.id == existing.id) position else it }
        } else {
            position = Position(
                id = UUID.randomUUID().toString(),
                symbol = request.symbol,
                baseAsset = request.baseAsset,
                side = request.side,
                quantity = quantity,
                entryPrice = markPrice,
                margin = margin,
                leverage = request.leverage,
                liquidationPrice = liquidationPrice(markPrice, request.side, request.leverage),
                openedAt = now,
            )
            positions = account.positions + position
        }

        val trade = Trade(
            id = UUID.randomUUID().toString(),
            symbol = request.symbol,
            baseAsset = request.baseAsset,
            side = request.side,
            type = TradeType.OPEN,
            quantity = quantity,
            price = markPrice,
            entryPrice = markPrice,
            margin = margin,
            leverage = request.leverage,
            fee = fee,
            pnl = null,
            openedAt = now,
            createdAt = now,
        )

        val updated = account.copy(
            cash = account.cash.subtract(requiredCash).setScale(MONEY_SCALE, RoundingMode.HALF_UP),
            positions = positions,
            trades = listOf(trade) + account.trades,
        )
        return TradeOutcome.Success(updated, trade)
    }

    fun close(
        account: Account,
        request: CloseRequest,
    ): TradeOutcome {
        if (request.quantity.signum() <= 0 || request.markPrice.signum() <= 0) {
            return TradeOutcome.Rejected(TradeError.InvalidAmount)
        }
        val position = account.positions.firstOrNull { it.id == request.positionId }
            ?: return TradeOutcome.Rejected(TradeError.PositionNotFound)

        val appliedQuantity = request.quantity.min(position.quantity)
        if (appliedQuantity.signum() <= 0) {
            return TradeOutcome.Rejected(TradeError.QuantityTooSmall)
        }
        val clamped = appliedQuantity < request.quantity
        val isFullClose = appliedQuantity.compareTo(position.quantity) == 0

        val releasedMargin = if (isFullClose) {
            position.margin
        } else {
            position.margin
                .multiply(appliedQuantity.divide(position.quantity, 16, RoundingMode.HALF_UP))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                .min(position.margin)
        }
        val notional = appliedQuantity.multiply(request.markPrice).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        val fee = notional.multiply(FEE_RATE).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        val pnl = pnlFor(position.side, position.entryPrice, request.markPrice, appliedQuantity)
            .setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        val pnlNet = pnl.subtract(fee).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        val returnAmount = releasedMargin.add(pnlNet).max(BigDecimal.ZERO)
            .setScale(MONEY_SCALE, RoundingMode.HALF_UP)

        val positions = if (isFullClose) {
            account.positions.filterNot { it.id == position.id }
        } else {
            account.positions.map {
                if (it.id == position.id) {
                    it.copy(
                        quantity = it.quantity.subtract(appliedQuantity),
                        margin = it.margin.subtract(releasedMargin).max(BigDecimal.ZERO),
                    )
                } else {
                    it
                }
            }
        }

        val now = System.currentTimeMillis()
        val trade = Trade(
            id = UUID.randomUUID().toString(),
            symbol = position.symbol,
            baseAsset = position.baseAsset,
            side = position.side,
            type = TradeType.CLOSE,
            quantity = appliedQuantity,
            price = request.markPrice,
            entryPrice = position.entryPrice,
            margin = releasedMargin,
            leverage = position.leverage,
            fee = fee,
            pnl = pnlNet,
            openedAt = position.openedAt,
            createdAt = now,
        )

        val updated = account.copy(
            cash = account.cash.add(returnAmount).setScale(MONEY_SCALE, RoundingMode.HALF_UP),
            positions = positions,
            trades = listOf(trade) + account.trades,
        )
        return TradeOutcome.Success(updated, trade, clamped)
    }

    fun liquidate(
        account: Account,
        markPrices: Map<String, BigDecimal>,
    ): LiquidationOutcome {
        if (account.positions.isEmpty()) return LiquidationOutcome(account, emptyList())

        val now = System.currentTimeMillis()
        val liquidated = mutableListOf<Position>()
        val liquidationTrades = mutableListOf<Trade>()

        val remaining = account.positions.filter { position ->
            val mark = markPrices[position.symbol]
            if (mark != null && isLiquidated(position, mark)) {
                liquidated += position
                liquidationTrades += Trade(
                    id = UUID.randomUUID().toString(),
                    symbol = position.symbol,
                    baseAsset = position.baseAsset,
                    side = position.side,
                    type = TradeType.LIQUIDATION,
                    quantity = position.quantity,
                    price = mark,
                    entryPrice = position.entryPrice,
                    margin = position.margin,
                    leverage = position.leverage,
                    fee = BigDecimal.ZERO,
                    pnl = position.margin.negate(),
                    openedAt = position.openedAt,
                    createdAt = now,
                )
                false
            } else {
                true
            }
        }

        if (liquidated.isEmpty()) return LiquidationOutcome(account, emptyList())

        return LiquidationOutcome(
            account = account.copy(
                positions = remaining,
                trades = liquidationTrades + account.trades,
            ),
            liquidated = liquidated,
        )
    }

    fun liquidationPrice(entryPrice: BigDecimal, side: Side, leverage: Int): BigDecimal {
        if (leverage <= 0 || entryPrice.signum() <= 0) return BigDecimal.ZERO
        val ratio = BigDecimal.ONE.subtract(MAINTENANCE_MARGIN_RATE)
            .divide(BigDecimal(leverage), 16, RoundingMode.HALF_UP)
        val factor = if (side == Side.LONG) {
            BigDecimal.ONE.subtract(ratio)
        } else {
            BigDecimal.ONE.add(ratio)
        }
        return entryPrice.multiply(factor).setScale(PRICE_SCALE, RoundingMode.HALF_UP).max(BigDecimal.ZERO)
    }

    fun isLiquidated(position: Position, markPrice: BigDecimal): Boolean {
        if (position.margin.signum() <= 0) return false
        val threshold = position.margin
            .multiply(BigDecimal.ONE.subtract(MAINTENANCE_MARGIN_RATE))
            .negate()
        return position.unrealizedPnl(markPrice) <= threshold
    }

    fun maxNotional(cash: BigDecimal, leverage: Int): BigDecimal {
        if (leverage <= 0 || cash.signum() <= 0) return BigDecimal.ZERO
        val denominator = BigDecimal.ONE
            .divide(BigDecimal(leverage), 16, RoundingMode.HALF_UP)
            .add(FEE_RATE)
        return cash.divide(denominator, MONEY_SCALE, RoundingMode.DOWN).max(BigDecimal.ZERO)
    }

    private fun pnlFor(
        side: Side,
        entryPrice: BigDecimal,
        markPrice: BigDecimal,
        quantity: BigDecimal,
    ): BigDecimal = when (side) {
        Side.LONG -> markPrice.subtract(entryPrice).multiply(quantity)
        Side.SHORT -> entryPrice.subtract(markPrice).multiply(quantity)
    }
}

private fun BigDecimal.floorToStep(step: BigDecimal, scale: Int): BigDecimal {
    if (step.signum() <= 0) return setScale(scale, RoundingMode.DOWN)
    val steps = divide(step, 0, RoundingMode.DOWN)
    return steps.multiply(step).setScale(scale, RoundingMode.DOWN)
}
