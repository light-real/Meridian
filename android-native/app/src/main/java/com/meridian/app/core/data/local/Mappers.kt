package com.meridian.app.core.data.local

import com.meridian.app.core.model.Account
import com.meridian.app.core.model.LimitOrder
import com.meridian.app.core.model.MarginMode
import com.meridian.app.core.model.Position
import com.meridian.app.core.model.Side
import com.meridian.app.core.model.Trade
import com.meridian.app.core.model.TradeType
import java.math.BigDecimal

fun AccountEntity.toModel(): Account = Account(
    cash = cash.toDecimal(),
    initialCash = initialCash.toDecimal(),
)

fun Account.toEntity(): AccountEntity = AccountEntity(
    id = 1,
    cash = cash.toPlainString(),
    initialCash = initialCash.toPlainString(),
)

fun LimitOrderEntity.toModel(): LimitOrder = LimitOrder(
    id = id,
    symbol = symbol,
    baseAsset = baseAsset,
    side = side.toSide(),
    notional = notional.toDecimal(),
    limitPrice = limitPrice.toDecimal(),
    leverage = leverage,
    marginMode = runCatching { MarginMode.valueOf(marginMode) }
        .getOrDefault(MarginMode.ISOLATED),
    takeProfitPrice = takeProfitPrice?.toDecimalOrNull(),
    stopLossPrice = stopLossPrice?.toDecimalOrNull(),
    createdAt = createdAt,
    frozenCash = frozenCash.toDecimal(),
    note = note,
)

fun LimitOrder.toEntity(): LimitOrderEntity = LimitOrderEntity(
    id = id,
    symbol = symbol,
    baseAsset = baseAsset,
    side = side.name,
    notional = notional.toPlainString(),
    limitPrice = limitPrice.toPlainString(),
    leverage = leverage,
    marginMode = marginMode.name,
    takeProfitPrice = takeProfitPrice?.toPlainString(),
    stopLossPrice = stopLossPrice?.toPlainString(),
    createdAt = createdAt,
    frozenCash = frozenCash.toPlainString(),
    note = note,
)

fun PositionEntity.toModel(): Position = Position(
    id = id,
    symbol = symbol,
    baseAsset = baseAsset,
    side = side.toSide(),
    quantity = quantity.toDecimal(),
    entryPrice = entryPrice.toDecimal(),
    margin = margin.toDecimal(),
    leverage = leverage,
    liquidationPrice = liquidationPrice.toDecimal(),
    openedAt = openedAt,
    marginMode = runCatching { MarginMode.valueOf(marginMode) }
        .getOrDefault(MarginMode.ISOLATED),
    takeProfitPrice = takeProfitPrice?.toDecimalOrNull(),
    stopLossPrice = stopLossPrice?.toDecimalOrNull(),
)

fun TradeEntity.toModel(): Trade = Trade(
    id = id,
    symbol = symbol,
    baseAsset = baseAsset,
    side = side.toSide(),
    type = type.toTradeType(),
    quantity = quantity.toDecimal(),
    price = price.toDecimal(),
    entryPrice = entryPrice?.toDecimalOrNull(),
    margin = margin.toDecimal(),
    leverage = leverage,
    fee = fee.toDecimal(),
    pnl = pnl?.toDecimalOrNull(),
    openedAt = openedAt,
    createdAt = createdAt,
    positionId = positionId,
    note = note,
)

fun Position.toEntity(): PositionEntity = PositionEntity(
    id = id,
    symbol = symbol,
    baseAsset = baseAsset,
    side = side.name,
    quantity = quantity.toPlainString(),
    entryPrice = entryPrice.toPlainString(),
    margin = margin.toPlainString(),
    leverage = leverage,
    liquidationPrice = liquidationPrice.toPlainString(),
    openedAt = openedAt,
    marginMode = marginMode.name,
    takeProfitPrice = takeProfitPrice?.toPlainString(),
    stopLossPrice = stopLossPrice?.toPlainString(),
)

fun Trade.toEntity(): TradeEntity = TradeEntity(
    id = id,
    symbol = symbol,
    baseAsset = baseAsset,
    side = side.name,
    type = type.name,
    quantity = quantity.toPlainString(),
    price = price.toPlainString(),
    entryPrice = entryPrice?.toPlainString(),
    margin = margin.toPlainString(),
    leverage = leverage,
    fee = fee.toPlainString(),
    pnl = pnl?.toPlainString(),
    openedAt = openedAt,
    createdAt = createdAt,
    positionId = positionId,
    note = note,
)

private fun String.toDecimal(): BigDecimal = toDecimalOrNull() ?: BigDecimal.ZERO

private fun String.toDecimalOrNull(): BigDecimal? =
    runCatching { toBigDecimal() }.getOrNull()

private fun String.toSide(): Side =
    runCatching { Side.valueOf(this) }.getOrDefault(Side.LONG)

private fun String.toTradeType(): TradeType =
    runCatching { TradeType.valueOf(this) }.getOrDefault(TradeType.OPEN)
