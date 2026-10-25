package com.tradesim.app.core.data.local

import com.tradesim.app.core.model.Account
import com.tradesim.app.core.model.Position
import com.tradesim.app.core.model.Side
import com.tradesim.app.core.model.Trade
import com.tradesim.app.core.model.TradeType
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
)

private fun String.toDecimal(): BigDecimal = toDecimalOrNull() ?: BigDecimal.ZERO

private fun String.toDecimalOrNull(): BigDecimal? =
    runCatching { toBigDecimal() }.getOrNull()

private fun String.toSide(): Side =
    runCatching { Side.valueOf(this) }.getOrDefault(Side.LONG)

private fun String.toTradeType(): TradeType =
    runCatching { TradeType.valueOf(this) }.getOrDefault(TradeType.OPEN)
