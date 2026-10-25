package com.meridian.app.core.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "a_share_account")
data class AShareAccountEntity(
    @PrimaryKey val id: Int = 1,
    val cash: String,
    val initialCash: String,
    val lastSettlementDate: String = "",
)

@Entity(tableName = "a_share_holdings")
data class AShareHoldingEntity(
    @PrimaryKey val symbol: String,
    val name: String,
    val secid: String,
    val quantity: Int,
    val availableQuantity: Int,
    val avgCost: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "a_share_trades",
    indices = [Index(value = ["createdAt"])],
)
data class AShareTradeEntity(
    @PrimaryKey val id: String,
    val symbol: String,
    val name: String,
    val side: String,
    val quantity: Int,
    val price: String,
    val amount: String,
    val commission: String,
    val stampDuty: String,
    val transferFee: String,
    val realizedPnl: String?,
    val createdAt: Long,
    val note: String? = null,
)
