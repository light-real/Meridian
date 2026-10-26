package com.meridian.app.core.domain

import com.meridian.app.core.model.Account
import com.meridian.app.core.model.CloseRequest
import com.meridian.app.core.model.LiquidationOutcome
import com.meridian.app.core.model.MarginAdjustment
import com.meridian.app.core.model.MarginMode
import com.meridian.app.core.model.OrderRequest
import com.meridian.app.core.model.Position
import com.meridian.app.core.model.Side
import com.meridian.app.core.model.Trade
import com.meridian.app.core.model.TradeError
import com.meridian.app.core.model.TradeOutcome
import com.meridian.app.core.model.TradeType
import com.meridian.app.core.model.cleanNote
import com.meridian.app.core.model.TriggerOutcome
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * 永续合约的资金引擎。
 *
 * 规则：
 * - 开仓扣 `保证金 + 手续费`
 * - 同方向加仓合并，反方向必须显式平仓，避免把多空仓合并成错误方向
 * - 平仓数量 clamp 到持仓数量，永远不产生负持仓
 * - 强平按 `浮亏 <= -保证金 × (1 - 维持保证金率)` 判定
 */
object TradingEngine {

    /** 与币安 USDT 永续 VIP0 的 taker 费率一致：0.05%。 */
    val FEE_RATE: BigDecimal = BigDecimal("0.0005")
    val MAINTENANCE_MARGIN_RATE: BigDecimal = BigDecimal("0.005")
    const val MAX_LEVERAGE: Int = 150

    private const val MONEY_SCALE = 8
    private const val QTY_SCALE = 8
    private const val PRICE_SCALE = 8
    private val EPS = BigDecimal("0.00000001")

    fun open(
        account: Account,
        request: OrderRequest,
        markPrice: BigDecimal,
    ): TradeOutcome {
        if (request.leverage !in 1..MAX_LEVERAGE) return TradeOutcome.Rejected(TradeError.InvalidLeverage)
        if (request.notional.signum() <= 0 || markPrice.signum() <= 0) {
            return TradeOutcome.Rejected(TradeError.InvalidAmount)
        }

        val marginMode = request.marginMode
        val isCross = marginMode == MarginMode.CROSS

        // 先按 stepSize 把数量取整，再拿「真正成交的名义价值」算保证金和手续费。
        // 否则像 BTC 这种 step=0.001 的标的，1000 USDT 名义价值在 8 万价位只能买到
        // 0.012 个（而不是 0.0125），但保证金和手续费还是按 1000 收，等于多锁多扣。
        val qtyScale = maxOf(request.rules.quantityScale, request.rules.stepSize.scale())
        val quantity = request.notional
            .divide(markPrice, QTY_SCALE, RoundingMode.DOWN)
            .floorToStep(request.rules.stepSize, qtyScale)
        if (quantity.signum() <= 0) {
            return TradeOutcome.Rejected(TradeError.QuantityTooSmall)
        }

        val executedNotional = quantity.multiply(markPrice)
            .setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        if (executedNotional < request.rules.minNotional) {
            return TradeOutcome.Rejected(TradeError.BelowMinNotional)
        }

        val margin = executedNotional.divide(BigDecimal(request.leverage), MONEY_SCALE, RoundingMode.HALF_UP)
        val fee = executedNotional.multiply(FEE_RATE).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        // 开仓能力检查：保证金 + 手续费要够。
        // 全仓不锁保证金，但已经开出去的全仓仓位仍然占用额度，
        // 否则同一笔余额可以被反复当作保证金，实际杠杆会超过设定值。
        // 全仓保证金不直接从现金里扣，但仍然占用额度；逐仓下单时也要扣掉
        val crossUsed = account.positions
            .filter { it.marginMode == MarginMode.CROSS }
            .fold(BigDecimal.ZERO) { acc, position -> acc.add(position.margin) }
        val available = account.cash.subtract(crossUsed)
        val requiredCash = margin.add(fee)
        if (available < requiredCash) {
            return TradeOutcome.Rejected(TradeError.InsufficientBalance)
        }
        val deducted = if (isCross) fee else requiredCash

        val existing = account.positions.firstOrNull { it.symbol == request.symbol }
        if (existing != null) {
            if (existing.side != request.side) {
                return TradeOutcome.Rejected(TradeError.OppositePositionExists)
            }
            if (existing.leverage != request.leverage) {
                return TradeOutcome.Rejected(TradeError.LeverageMismatch)
            }
            if (existing.marginMode != marginMode) {
                return TradeOutcome.Rejected(TradeError.MarginModeMismatch)
            }
        }

        val now = System.currentTimeMillis()
        val position: Position
        val positions: List<Position>

        val takeProfit = request.takeProfitPrice?.takeIf { it.signum() > 0 }
        val stopLoss = request.stopLossPrice?.takeIf { it.signum() > 0 }
        if (takeProfit != null) {
            val valid = if (request.side == Side.LONG) {
                takeProfit > markPrice
            } else {
                takeProfit < markPrice
            }
            if (!valid) return TradeOutcome.Rejected(TradeError.InvalidTriggerPrice)
        }
        if (stopLoss != null) {
            val valid = if (request.side == Side.LONG) {
                stopLoss < markPrice
            } else {
                stopLoss > markPrice
            }
            if (!valid) return TradeOutcome.Rejected(TradeError.InvalidTriggerPrice)
        }

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
                liquidationPrice = if (isCross) {
                    BigDecimal.ZERO
                } else {
                    liquidationPriceFor(newEntry, existing.side, newQuantity, newMargin)
                },
                takeProfitPrice = takeProfit ?: existing.takeProfitPrice,
                stopLossPrice = stopLoss ?: existing.stopLossPrice,
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
                liquidationPrice = if (isCross) {
                    BigDecimal.ZERO
                } else {
                    liquidationPriceFor(markPrice, request.side, quantity, margin)
                },
                openedAt = now,
                marginMode = marginMode,
                takeProfitPrice = takeProfit,
                stopLossPrice = stopLoss,
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
            positionId = position.id,
            note = request.note.cleanNote(),
        )

        val updated = account.copy(
            cash = account.cash.subtract(deducted).setScale(MONEY_SCALE, RoundingMode.HALF_UP),
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
        val isCross = position.isCross

        val releasedMargin = when {
            isCross -> BigDecimal.ZERO
            isFullClose -> position.margin
            else -> position.margin
                .multiply(appliedQuantity.divide(position.quantity, 16, RoundingMode.HALF_UP))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                .min(position.margin)
        }
        val notional = appliedQuantity.multiply(request.markPrice).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        val fee = notional.multiply(FEE_RATE).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        val pnl = pnlFor(position.side, position.entryPrice, request.markPrice, appliedQuantity)
            .setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        val pnlNet = pnl.subtract(fee).setScale(MONEY_SCALE, RoundingMode.HALF_UP)

        // 逐仓返还锁定保证金 + 盈亏；全仓没有锁保证金，只结算盈亏（可能是负的）
        val delta = if (isCross) pnlNet else releasedMargin.add(pnlNet)
        val newCash = account.cash.add(delta)
            .setScale(MONEY_SCALE, RoundingMode.HALF_UP)
            .max(BigDecimal.ZERO)

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
            positionId = position.id,
            note = request.note.cleanNote(),
        )

        val updated = account.copy(
            cash = newCash,
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
        var cash = account.cash

        // 逐仓：每个仓位独立判断
        val afterIsolated = mutableListOf<Position>()
        account.positions.forEach { position ->
            if (position.marginMode == MarginMode.CROSS) {
                afterIsolated += position
            } else {
                val mark = markPrices[position.symbol]
                if (mark != null && isLiquidated(position, mark)) {
                    liquidated += position
                    liquidationTrades += liquidationTrade(position, mark, position.margin.negate(), now)
                } else {
                    afterIsolated += position
                }
            }
        }

        // 全仓：账户权益跌破维持保证金要求时，该模式下所有仓位一起强平
        val cross = afterIsolated.filter { it.marginMode == MarginMode.CROSS }
        var remaining: List<Position> = afterIsolated
        if (cross.isNotEmpty()) {
            val equity = cross.fold(cash) { acc, position ->
                acc.add(position.unrealizedPnl(markPrices[position.symbol] ?: position.entryPrice))
            }
            val maintenance = cross.fold(BigDecimal.ZERO) { acc, position ->
                acc.add(
                    position.notionalAt(markPrices[position.symbol] ?: position.entryPrice)
                        .multiply(MAINTENANCE_MARGIN_RATE),
                )
            }
            if (equity <= maintenance) {
                cross.forEach { position ->
                    val mark = markPrices[position.symbol] ?: position.entryPrice
                    liquidated += position
                    liquidationTrades += liquidationTrade(
                        position = position,
                        markPrice = mark,
                        pnl = position.unrealizedPnl(mark).setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                        now = now,
                    )
                }
                cash = equity.setScale(MONEY_SCALE, RoundingMode.HALF_UP).max(BigDecimal.ZERO)
                remaining = afterIsolated.filter { it.marginMode == MarginMode.ISOLATED }
            }
        }

        if (liquidated.isEmpty()) return LiquidationOutcome(account, emptyList())

        return LiquidationOutcome(
            account = account.copy(
                cash = cash,
                positions = remaining,
                trades = liquidationTrades + account.trades,
            ),
            liquidated = liquidated,
        )
    }

    /**
     * 调整已有仓位的杠杆：仓位数量不动，只按「开仓名义价值 / 新杠杆」重算保证金。
     *
     * - 调高杠杆：保证金减少，差额退回可用
     * - 调低杠杆：保证金增加，从可用补；不够直接拒绝
     */
    fun adjustLeverage(
        account: Account,
        positionId: String,
        leverage: Int,
        markPrice: BigDecimal,
    ): MarginAdjustment {
        if (leverage !in 1..MAX_LEVERAGE || markPrice.signum() <= 0) {
            return MarginAdjustment(error = TradeError.InvalidLeverage)
        }
        val position = account.positions.firstOrNull { it.id == positionId }
            ?: return MarginAdjustment(error = TradeError.PositionNotFound)
        if (leverage == position.leverage) {
            return MarginAdjustment(account = account)
        }

        val notional = position.entryPrice.multiply(position.quantity)
            .setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        val newMargin = notional.divide(BigDecimal(leverage), MONEY_SCALE, RoundingMode.HALF_UP)
        if (newMargin.signum() <= 0) {
            return MarginAdjustment(error = TradeError.MarginTooLow)
        }

        val delta = newMargin.subtract(position.margin)
        val otherCross = account.positions
            .filter { it.id != position.id && it.isCross }
            .fold(BigDecimal.ZERO) { acc, other -> acc.add(other.margin) }
        val available = account.cash.subtract(otherCross)

        if (delta.signum() > 0) {
            // 全仓没有从现金里扣过保证金，补的是完整占用；逐仓只补差额
            val required = if (position.isCross) newMargin else delta
            if (required > available) {
                return MarginAdjustment(error = TradeError.InsufficientBalance)
            }
        }

        if (!position.isCross && newMargin <= minimumMargin(position, markPrice)) {
            return MarginAdjustment(error = TradeError.MarginTooLow)
        }

        val newLiquidation = if (position.isCross) {
            BigDecimal.ZERO
        } else {
            liquidationPriceFor(
                entryPrice = position.entryPrice,
                side = position.side,
                quantity = position.quantity,
                margin = newMargin,
            )
        }
        if (!position.isCross && newLiquidation.signum() > 0) {
            val crossed = when (position.side) {
                Side.LONG -> newLiquidation >= markPrice
                Side.SHORT -> newLiquidation <= markPrice
            }
            if (crossed) return MarginAdjustment(error = TradeError.MarginTooLow)
        }

        val updatedPosition = position.copy(
            leverage = leverage,
            margin = newMargin,
            liquidationPrice = newLiquidation,
        )
        val cash = if (position.isCross) {
            account.cash
        } else {
            account.cash.subtract(delta)
        }

        return MarginAdjustment(
            account = account.copy(
                cash = cash.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                positions = account.positions.map {
                    if (it.id == positionId) updatedPosition else it
                },
            ),
        )
    }

    private fun liquidationTrade(
        position: Position,
        markPrice: BigDecimal,
        pnl: BigDecimal,
        now: Long,
    ): Trade = Trade(
        id = UUID.randomUUID().toString(),
        symbol = position.symbol,
        baseAsset = position.baseAsset,
        side = position.side,
        type = TradeType.LIQUIDATION,
        quantity = position.quantity,
        price = markPrice,
        entryPrice = position.entryPrice,
        margin = position.margin,
        leverage = position.leverage,
        fee = BigDecimal.ZERO,
        pnl = pnl,
        openedAt = position.openedAt,
        createdAt = now,
        positionId = position.id,
        note = "强制平仓：保证金不足",
    )

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

    /**
     * 按「实际保证金」算强平价。逐仓补保证金后强平价会跟着远离，
     * 所以引擎内部统一用这个版本，而不是按杠杆算。
     */
    fun liquidationPriceFor(
        entryPrice: BigDecimal,
        side: Side,
        quantity: BigDecimal,
        margin: BigDecimal,
    ): BigDecimal {
        if (entryPrice.signum() <= 0 || quantity.signum() <= 0 || margin.signum() <= 0) {
            return BigDecimal.ZERO
        }
        // 亏掉 99.5% 保证金时触发，换算成价格偏移
        val buffer = margin
            .multiply(BigDecimal.ONE.subtract(MAINTENANCE_MARGIN_RATE))
            .divide(quantity, 16, RoundingMode.HALF_UP)
        val price = if (side == Side.LONG) {
            entryPrice.subtract(buffer)
        } else {
            entryPrice.add(buffer)
        }
        return price.setScale(PRICE_SCALE, RoundingMode.HALF_UP).max(BigDecimal.ZERO)
    }

    /**
     * 逐仓仓位减少保证金时的最低保证金。
     *
     * 同时满足三条：
     * - 不低于维持保证金
     * - 减少后的强平价不能被当前标记价穿过
     * - 实际杠杆不能超过 150x（名义价值按当前标记价算）
     */
    fun minimumMargin(position: Position, markPrice: BigDecimal): BigDecimal {
        val notional = position.notionalAt(markPrice)
        val maintenance = notional.multiply(MAINTENANCE_MARGIN_RATE)
        val loss = position.unrealizedPnl(markPrice).negate()
        val byLiquidation = if (loss.signum() > 0) {
            loss.divide(
                BigDecimal.ONE.subtract(MAINTENANCE_MARGIN_RATE),
                MONEY_SCALE,
                RoundingMode.HALF_UP,
            )
        } else {
            BigDecimal.ZERO
        }
        return maintenance
            .max(byLiquidation)
            .max(leverageFloor(position, markPrice))
    }

    /** 维持 150x 上限所需的最低保证金。 */
    fun leverageFloor(position: Position, markPrice: BigDecimal): BigDecimal =
        position.notionalAt(markPrice)
            .divide(BigDecimal(MAX_LEVERAGE), MONEY_SCALE, RoundingMode.HALF_UP)

    /**
     * 逐仓仓位调整保证金。delta 为正表示追加，为负表示减少。
     *
     * 减少保证金时必须满足：
     * - 减少后保证金仍大于 0
     * - 不低于 [minimumMargin]（维持保证金 / 强平价 / 150x 杠杆上限）
     */
    fun adjustMargin(
        account: Account,
        positionId: String,
        delta: BigDecimal,
        markPrice: BigDecimal,
    ): MarginAdjustment {
        if (delta.signum() == 0 || markPrice.signum() <= 0) {
            return MarginAdjustment(error = TradeError.InvalidAmount)
        }
        val position = account.positions.firstOrNull { it.id == positionId }
            ?: return MarginAdjustment(error = TradeError.PositionNotFound)
        if (position.isCross) return MarginAdjustment(error = TradeError.MarginModeMismatch)

        val newMargin = position.margin.add(delta).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        if (newMargin.signum() <= 0) return MarginAdjustment(error = TradeError.MarginTooLow)

        if (delta.signum() > 0) {
            if (account.cash < delta) return MarginAdjustment(error = TradeError.InsufficientBalance)
        } else {
            if (newMargin <= minimumMargin(position, markPrice)) {
                return MarginAdjustment(error = TradeError.MarginTooLow)
            }
        }

        val updatedPosition = position.copy(
            margin = newMargin,
            liquidationPrice = liquidationPriceFor(
                entryPrice = position.entryPrice,
                side = position.side,
                quantity = position.quantity,
                margin = newMargin,
            ),
        )

        return MarginAdjustment(
            account = account.copy(
                cash = account.cash.subtract(delta).setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                positions = account.positions.map {
                    if (it.id == positionId) updatedPosition else it
                },
            ),
        )
    }

    /**
     * 全仓仓位的预估强平价。
     *
     * 全仓没有「单个仓位独立强平」这回事，这里按「其他全仓仓位价格不变」
     * 反推当前仓位在账户权益 <= 维持保证金时的价格，仅用于展示。
     */
    fun crossLiquidationPrice(
        account: Account,
        position: Position,
        marks: Map<String, BigDecimal>,
    ): BigDecimal {
        if (!position.isCross || position.quantity.signum() <= 0) return BigDecimal.ZERO

        var otherEquity = account.cash
        var otherMaintenance = BigDecimal.ZERO
        account.positions.forEach { other ->
            if (other.id != position.id && other.isCross) {
                val mark = marks[other.symbol] ?: other.entryPrice
                otherEquity = otherEquity.add(other.unrealizedPnl(mark))
                otherMaintenance = otherMaintenance.add(
                    other.notionalAt(mark).multiply(MAINTENANCE_MARGIN_RATE),
                )
            }
        }

        val entryNotional = position.entryPrice.multiply(position.quantity)
        val price = when (position.side) {
            Side.LONG -> {
                val numerator = entryNotional.add(otherMaintenance).subtract(otherEquity)
                numerator.divide(
                    position.quantity.multiply(
                        BigDecimal.ONE.subtract(MAINTENANCE_MARGIN_RATE),
                    ),
                    PRICE_SCALE,
                    RoundingMode.HALF_UP,
                )
            }
            Side.SHORT -> {
                val numerator = otherEquity.add(entryNotional).subtract(otherMaintenance)
                numerator.divide(
                    position.quantity.multiply(
                        BigDecimal.ONE.add(MAINTENANCE_MARGIN_RATE),
                    ),
                    PRICE_SCALE,
                    RoundingMode.HALF_UP,
                )
            }
        }
        return price.setScale(PRICE_SCALE, RoundingMode.HALF_UP).max(BigDecimal.ZERO)
    }

    fun isLiquidated(position: Position, markPrice: BigDecimal): Boolean {
        if (position.margin.signum() <= 0) return false
        val threshold = position.margin
            .multiply(BigDecimal.ONE.subtract(MAINTENANCE_MARGIN_RATE))
            .negate()
        return position.unrealizedPnl(markPrice) <= threshold
    }

    /**
     * 检查并执行止盈止损。触发后按当前标记价市价全平。
     * 先判止盈再判止损，两者同时满足时（价格跳空）以止盈优先。
     */
    fun checkTriggers(account: Account, markPrices: Map<String, BigDecimal>): TriggerOutcome {
        if (account.positions.isEmpty()) return TriggerOutcome(account, emptyList(), emptyList())

        var current = account
        val closed = mutableListOf<Position>()
        val trades = mutableListOf<Trade>()

        account.positions.toList().forEach { position ->
            val mark = markPrices[position.symbol] ?: return@forEach
            val type = triggerType(position, mark) ?: return@forEach
            val outcome = close(
                account = current,
                request = CloseRequest(
                    positionId = position.id,
                    quantity = position.quantity,
                    markPrice = mark,
                    note = if (type == TradeType.TAKE_PROFIT) {
                        "止盈触发：自动平仓"
                    } else {
                        "止损触发：自动平仓"
                    },
                ),
            )
            if (outcome is TradeOutcome.Success) {
                current = outcome.account
                closed += position
                trades += outcome.trade.copy(type = type)
            }
        }

        return TriggerOutcome(current, closed, trades)
    }

    /** 判断某个价格是否触发止盈/止损，没触发返回 null。 */
    fun triggerType(position: Position, markPrice: BigDecimal): TradeType? {
        val takeProfit = position.takeProfitPrice
        val stopLoss = position.stopLossPrice
        return when (position.side) {
            Side.LONG -> when {
                takeProfit != null && markPrice >= takeProfit -> TradeType.TAKE_PROFIT
                stopLoss != null && markPrice <= stopLoss -> TradeType.STOP_LOSS
                else -> null
            }
            Side.SHORT -> when {
                takeProfit != null && markPrice <= takeProfit -> TradeType.TAKE_PROFIT
                stopLoss != null && markPrice >= stopLoss -> TradeType.STOP_LOSS
                else -> null
            }
        }
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
