package com.meridian.app.core.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "account")
data class AccountEntity(
    @PrimaryKey val id: Int = 1,
    val cash: String,
    val initialCash: String,
)

@Entity(
    tableName = "positions",
    indices = [Index(value = ["symbol"])],
)
data class PositionEntity(
    @PrimaryKey val id: String,
    val symbol: String,
    val baseAsset: String,
    val side: String,
    val quantity: String,
    val entryPrice: String,
    val margin: String,
    val leverage: Int,
    val liquidationPrice: String,
    val openedAt: Long,
    val marginMode: String = "ISOLATED",
    val takeProfitPrice: String? = null,
    val stopLossPrice: String? = null,
)

@Entity(
    tableName = "limit_orders",
    indices = [Index(value = ["symbol"])],
)
data class LimitOrderEntity(
    @PrimaryKey val id: String,
    val symbol: String,
    val baseAsset: String,
    val side: String,
    val notional: String,
    val limitPrice: String,
    val leverage: Int,
    val marginMode: String = "ISOLATED",
    val takeProfitPrice: String? = null,
    val stopLossPrice: String? = null,
    val createdAt: Long,
    val frozenCash: String = "0",
    val note: String? = null,
)

@Entity(
    tableName = "trades",
    indices = [Index(value = ["createdAt"])],
)data class TradeEntity(
    @PrimaryKey val id: String,
    val symbol: String,
    val baseAsset: String,
    val side: String,
    val type: String,
    val quantity: String,
    val price: String,
    val entryPrice: String?,
    val margin: String,
    val leverage: Int,
    val fee: String,
    val pnl: String?,
    val openedAt: Long,
    val createdAt: Long,
    val positionId: String? = null,
    val note: String? = null,
)
