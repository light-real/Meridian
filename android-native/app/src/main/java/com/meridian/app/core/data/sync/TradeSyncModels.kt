package com.meridian.app.core.data.sync

import kotlinx.serialization.Serializable

@Serializable
data class TradeSyncSnapshot(
    val version: Int = 1,
    val updatedAt: Long,
    val contract: ContractSyncSnapshot,
    val aShare: AShareSyncSnapshot,
)

@Serializable
data class ContractSyncSnapshot(
    val cash: String,
    val initialCash: String,
    val totalAssets: String,
    val totalPnl: String,
    val margin: String,
    val unrealizedPnl: String,
    val positions: List<ContractPositionSync>,
    val limitOrders: List<ContractLimitOrderSync>,
    val trades: List<ContractTradeSync>,
)

@Serializable
data class ContractPositionSync(
    val id: String,
    val symbol: String,
    val baseAsset: String,
    val side: String,
    val quantity: String,
    val entryPrice: String,
    val margin: String,
    val leverage: Int,
    val liquidationPrice: String,
    val marginMode: String,
    val takeProfitPrice: String?,
    val stopLossPrice: String?,
    val openedAt: Long,
    val markPrice: String,
    val unrealizedPnl: String,
)

@Serializable
data class ContractLimitOrderSync(
    val id: String,
    val symbol: String,
    val baseAsset: String,
    val side: String,
    val notional: String,
    val limitPrice: String,
    val leverage: Int,
    val marginMode: String,
    val takeProfitPrice: String?,
    val stopLossPrice: String?,
    val frozenCash: String,
    val createdAt: Long,
    val note: String? = null,
)

@Serializable
data class ContractTradeSync(
    val id: String,
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

@Serializable
data class AShareSyncSnapshot(
    val cash: String,
    val initialCash: String,
    val lastSettlementDate: String,
    val marketValue: String,
    val totalAssets: String,
    val unrealizedPnl: String,
    val holdings: List<AShareHoldingSync>,
    val trades: List<AShareTradeSync>,
)

@Serializable
data class AShareHoldingSync(
    val symbol: String,
    val name: String,
    val secid: String,
    val quantity: Int,
    val availableQuantity: Int,
    val avgCost: String,
    val price: String,
    val marketValue: String,
    val unrealizedPnl: String,
    val unrealizedPnlPercent: String,
)

@Serializable
data class AShareTradeSync(
    val id: String,
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
