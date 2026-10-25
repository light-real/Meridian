package com.meridian.app.core.data.repository

import androidx.room.withTransaction
import com.meridian.app.core.data.local.AShareAccountEntity
import com.meridian.app.core.data.local.AShareDao
import com.meridian.app.core.data.local.AShareHoldingEntity
import com.meridian.app.core.data.local.AShareTradeEntity
import com.meridian.app.core.data.local.MeridianDatabase
import com.meridian.app.core.domain.AShareTradingEngine
import com.meridian.app.core.model.AShareAccount
import com.meridian.app.core.model.AShareCalendar
import com.meridian.app.core.model.AShareHolding
import com.meridian.app.core.model.AShareOrderRequest
import com.meridian.app.core.model.AShareSide
import com.meridian.app.core.model.AShareTrade
import com.meridian.app.core.model.AShareTradeResult
import com.meridian.app.core.model.AShareTradeError
import java.math.BigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A 股独立资金账户。
 *
 * 和合约账户完全隔离：现金是 CNY，持仓是 A 股现货，不参与永续的保证金/强平。
 */
class AShareAccountRepository(
    private val database: MeridianDatabase,
    private val dao: AShareDao = database.aShareDao(),
) {
    private val operationMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val account: Flow<AShareAccount> = combine(
        dao.observeAccount(),
        dao.observeHoldings(),
        dao.observeTrades(),
    ) { accountEntity, holdings, trades ->
        AShareAccount(
            cash = accountEntity?.cash?.toDecimalOr(AShareAccount.INITIAL_CASH)
                ?: AShareAccount.INITIAL_CASH,
            initialCash = accountEntity?.initialCash?.toDecimalOr(AShareAccount.INITIAL_CASH)
                ?: AShareAccount.INITIAL_CASH,
            lastSettlementDate = accountEntity?.lastSettlementDate.orEmpty(),
            holdings = holdings.map { it.toModel() },
            trades = trades.map { it.toModel() },
        )
    }

    init {
        scope.launch {
            ensureInitialized()
            settleIfNeeded()
        }
    }

    suspend fun buy(request: AShareOrderRequest): AShareTradeResult =
        trade { current -> AShareTradingEngine.buy(current, request) }

    suspend fun sell(request: AShareOrderRequest): AShareTradeResult =
        trade { current -> AShareTradingEngine.sell(current, request) }

    suspend fun settleIfNeeded() {
        operationMutex.withLock {
            database.withTransaction {
                val current = readAccount()
                val settled = AShareTradingEngine.settle(current)
                if (settled != current) {
                    dao.upsertAccount(settled.toAccountEntity())
                    dao.replaceHoldings(settled.holdings.map { it.toEntity() })
                }
            }
        }
    }

    suspend fun reset() {
        operationMutex.withLock {
            database.withTransaction {
                dao.replaceAll(
                    account = AShareAccountEntity(
                        cash = AShareAccount.INITIAL_CASH.toPlainString(),
                        initialCash = AShareAccount.INITIAL_CASH.toPlainString(),
                        lastSettlementDate = AShareCalendar.tradingDate().toString(),
                    ),
                    holdings = emptyList(),
                    trades = emptyList(),
                )
            }
        }
    }

    suspend fun getAccountSnapshot(): AShareAccount = operationMutex.withLock {
        readAccount()
    }

    private suspend fun trade(
        block: (AShareAccount) -> AShareTradeResult,
    ): AShareTradeResult {
        var result: AShareTradeResult? = null
        operationMutex.withLock {
            database.withTransaction {
                val current = readAccount()
                val outcome = block(current)
                result = outcome
                if (outcome is AShareTradeResult.Success) {
                    dao.upsertAccount(outcome.account.toAccountEntity())
                    dao.replaceHoldings(outcome.account.holdings.map { it.toEntity() })
                    dao.insertTrade(outcome.trade.toEntity())
                    dao.trimTrades(MAX_TRADES)
                }
            }
        }
        return result ?: AShareTradeResult.Rejected(AShareTradeError.Unknown("交易失败"))
    }

    private suspend fun ensureInitialized() {
        operationMutex.withLock {
            if (dao.getAccount() == null) {
                dao.upsertAccount(
                    AShareAccountEntity(
                        cash = AShareAccount.INITIAL_CASH.toPlainString(),
                        initialCash = AShareAccount.INITIAL_CASH.toPlainString(),
                        lastSettlementDate = "",
                    ),
                )
            }
        }
    }

    private suspend fun readAccount(): AShareAccount {
        val accountEntity = dao.getAccount()
        return AShareAccount(
            cash = accountEntity?.cash?.toDecimalOr(AShareAccount.INITIAL_CASH)
                ?: AShareAccount.INITIAL_CASH,
            initialCash = accountEntity?.initialCash?.toDecimalOr(AShareAccount.INITIAL_CASH)
                ?: AShareAccount.INITIAL_CASH,
            lastSettlementDate = accountEntity?.lastSettlementDate.orEmpty(),
            holdings = dao.getHoldings().map { it.toModel() },
            trades = dao.getTrades().map { it.toModel() },
        )
    }

    private fun AShareHoldingEntity.toModel(): AShareHolding = AShareHolding(
        symbol = symbol,
        name = name,
        secid = secid,
        quantity = quantity,
        availableQuantity = availableQuantity,
        avgCost = avgCost.toDecimalOr(BigDecimal.ZERO),
    )

    private fun AShareTradeEntity.toModel(): AShareTrade = AShareTrade(
        id = id,
        symbol = symbol,
        name = name,
        side = runCatching { AShareSide.valueOf(side) }.getOrDefault(AShareSide.BUY),
        quantity = quantity,
        price = price.toDecimalOr(BigDecimal.ZERO),
        amount = amount.toDecimalOr(BigDecimal.ZERO),
        commission = commission.toDecimalOr(BigDecimal.ZERO),
        stampDuty = stampDuty.toDecimalOr(BigDecimal.ZERO),
        transferFee = transferFee.toDecimalOr(BigDecimal.ZERO),
        realizedPnl = realizedPnl?.toBigDecimalOrNull(),
        createdAt = createdAt,
        note = note,
    )

    private fun AShareAccount.toAccountEntity(): AShareAccountEntity = AShareAccountEntity(
        cash = cash.toPlainString(),
        initialCash = initialCash.toPlainString(),
        lastSettlementDate = lastSettlementDate,
    )

    private fun AShareHolding.toEntity(): AShareHoldingEntity = AShareHoldingEntity(
        symbol = symbol,
        name = name,
        secid = secid,
        quantity = quantity,
        availableQuantity = availableQuantity,
        avgCost = avgCost.toPlainString(),
        updatedAt = System.currentTimeMillis(),
    )

    private fun AShareTrade.toEntity(): AShareTradeEntity = AShareTradeEntity(
        id = id,
        symbol = symbol,
        name = name,
        side = side.name,
        quantity = quantity,
        price = price.toPlainString(),
        amount = amount.toPlainString(),
        commission = commission.toPlainString(),
        stampDuty = stampDuty.toPlainString(),
        transferFee = transferFee.toPlainString(),
        realizedPnl = realizedPnl?.toPlainString(),
        createdAt = createdAt,
        note = note,
    )

    private fun String.toDecimalOr(fallback: BigDecimal): BigDecimal =
        toBigDecimalOrNull() ?: fallback

    private companion object {
        const val MAX_TRADES = 500
    }
}
