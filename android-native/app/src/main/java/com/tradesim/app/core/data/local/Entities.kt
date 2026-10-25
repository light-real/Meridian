package com.tradesim.app.core.data.local

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
)

@Entity(
    tableName = "trades",
    indices = [Index(value = ["createdAt"])],
)
data class TradeEntity(
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
)
