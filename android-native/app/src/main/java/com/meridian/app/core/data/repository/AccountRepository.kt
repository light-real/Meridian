package com.meridian.app.core.data.repository

import androidx.room.withTransaction
import com.meridian.app.core.AppConstants
import com.meridian.app.core.data.local.MeridianDatabase
import com.meridian.app.core.data.local.toEntity
import com.meridian.app.core.data.local.toModel
import com.meridian.app.core.data.supabase.SupabaseClient
import com.meridian.app.core.domain.TradingEngine
import com.meridian.app.core.model.Account
import com.meridian.app.core.model.CloseRequest
import com.meridian.app.core.model.LimitOrder
import com.meridian.app.core.model.MarginMode
import com.meridian.app.core.model.OrderRequest
import com.meridian.app.core.model.Position
import com.meridian.app.core.model.Side
import com.meridian.app.core.model.SymbolInfo
import com.meridian.app.core.model.Trade
import com.meridian.app.core.model.TradeError
import com.meridian.app.core.model.TradeOutcome
import com.meridian.app.core.model.TradeType
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SyncState {
    IDLE,
    SYNCING,
    SYNCED,
    ERROR,
}

class AccountRepository(
    private val database: MeridianDatabase,
    private val authRepository: AuthRepository,
    private val supabase: SupabaseClient,
    private val cloudMapper: CloudMapper,
    private val marketRepository: MarketRepository,
) {
    private val dao = database.accountDao()
    private val operationMutex = Mutex()
    private val syncMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _syncState = MutableStateFlow(SyncState.IDLE)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    @Volatile
    private var lastMarks: Map<String, BigDecimal> = emptyMap()

    val account: Flow<Account> = combine(
        dao.observeAccount(),
        dao.observePositions(),
        dao.observeTrades(),
        dao.observeLimitOrders(),
    ) { accountEntity, positions, trades, limitOrders ->
        Account(
            cash = accountEntity?.cash?.toDecimalOr(AppConstants.INITIAL_CASH)
                ?: AppConstants.INITIAL_CASH,
            initialCash = accountEntity?.initialCash?.toDecimalOr(AppConstants.INITIAL_CASH)
                ?: AppConstants.INITIAL_CASH,
            positions = positions.map { it.toModel() },
            trades = trades.map { it.toModel() },
            limitOrders = limitOrders.map { it.toModel() },
        )
    }.distinctUntilChanged()

    init {
        scope.launch { ensureInitialized() }
    }

    suspend fun openPosition(
        symbol: SymbolInfo,
        side: Side,
        notional: BigDecimal,
        leverage: Int,
        markPrice: BigDecimal,
        marginMode: MarginMode = MarginMode.ISOLATED,
        takeProfitPrice: BigDecimal? = null,
        stopLossPrice: BigDecimal? = null,
        note: String? = null,
    ): TradeOutcome {
        val outcome = operationMutex.withLock {
            database.withTransaction {
                val current = readAccount()
                val result = TradingEngine.open(
                    account = current,
                    request = OrderRequest(
                        symbol = symbol.symbol,
                        baseAsset = symbol.baseAsset,
                        side = side,
                        notional = notional,
                        leverage = leverage,
                        rules = symbol.rules(),
                        marginMode = marginMode,
                        takeProfitPrice = takeProfitPrice,
                        stopLossPrice = stopLossPrice,
                        note = note,
                    ),
                    markPrice = markPrice,
                )
                if (result is TradeOutcome.Success) {
                    persist(result.account, result.trade)
                }
                result
            }
        }
        if (outcome is TradeOutcome.Success) syncAsync()
        return outcome
    }

    suspend fun closePosition(
        positionId: String,
        quantity: BigDecimal,
        markPrice: BigDecimal,
        note: String? = null,
    ): TradeOutcome {
        val outcome = operationMutex.withLock {
            database.withTransaction {
                val current = readAccount()
                val result = TradingEngine.close(
                    account = current,
                    request = CloseRequest(positionId, quantity, markPrice, note),
                )
                if (result is TradeOutcome.Success) {
                    persist(result.account, result.trade)
                }
                result
            }
        }
        if (outcome is TradeOutcome.Success) syncAsync()
        return outcome
    }

    /** 挂一张限价单，同时冻结保证金（逐仓 = 保证金 + 手续费，全仓 = 手续费）。 */
    suspend fun placeLimitOrder(
        order: LimitOrder,
        frozenCash: BigDecimal,
    ): Boolean {
        var ok = true
        operationMutex.withLock {
            database.withTransaction {
                val current = readAccount()
                if (current.cash < frozenCash) {
                    ok = false
                    return@withTransaction
                }
                dao.upsertAccount(
                    current.copy(cash = current.cash.subtract(frozenCash)).toEntity(),
                )
                dao.upsertLimitOrder(order.copy(frozenCash = frozenCash).toEntity())
            }
        }
        return ok
    }

    /** 撤单，把冻结的现金退回去。 */
    suspend fun cancelLimitOrder(orderId: String) {
        operationMutex.withLock {
            database.withTransaction {
                val order = dao.getLimitOrders().firstOrNull { it.id == orderId }
                    ?: return@withTransaction
                dao.deleteLimitOrder(orderId)
                val current = readAccount()
                dao.upsertAccount(
                    current
                        .copy(cash = current.cash.add(order.frozenCash.toBigDecimalOrNull() ?: BigDecimal.ZERO))
                        .toEntity(),
                )
            }
        }
    }

    /**
     * 检查限价单是否到价。到价就按限价成交（市价更优时也按限价，本地盘不做滑点补偿），
     * 成交失败的（余额不足等）直接撤单并返回原因。
     */
    suspend fun checkLimitOrders(marks: Map<String, BigDecimal>): List<LimitOrder> {
        if (marks.isEmpty()) return emptyList()

        // 先把交易规则取出来（BinanceApi 里有缓存），避免在事务里发网络请求
        val rules = runCatching { marketRepository.symbols() }
            .getOrElse { emptyList() }
            .associate { it.symbol to it.rules() }

        val filled = mutableListOf<LimitOrder>()
        operationMutex.withLock {
            database.withTransaction {
                val orders = dao.getLimitOrders().map { it.toModel() }
                if (orders.isEmpty()) return@withTransaction

                var current = readAccount()
                orders.forEach { order ->
                    val mark = marks[order.symbol] ?: return@forEach
                    if (!order.isTriggered(mark)) return@forEach

                    val ruleSet = rules[order.symbol] ?: return@forEach
                    // 先把挂单时冻结的钱解冻，再由引擎按正常开仓扣一次
                    current = current.copy(cash = current.cash.add(order.frozenCash))
                    val result = TradingEngine.open(
                        account = current,
                        request = OrderRequest(
                            symbol = order.symbol,
                            baseAsset = order.baseAsset,
                            side = order.side,
                            notional = order.notional,
                            leverage = order.leverage,
                            rules = ruleSet,
                            marginMode = order.marginMode,
                            takeProfitPrice = order.takeProfitPrice,
                            stopLossPrice = order.stopLossPrice,
                            note = order.note,
                        ),
                        markPrice = order.limitPrice,
                    )

                    if (result is TradeOutcome.Success) {
                        current = result.account
                        persist(result.account, result.trade)
                        filled += order
                    } else {
                        // 余额不够等异常：解冻后撤单
                        dao.upsertAccount(current.toEntity())
                    }
                    dao.deleteLimitOrder(order.id)
                }
            }
        }
        if (filled.isNotEmpty()) syncAsync()
        return filled
    }

    /** 调整逐仓保证金：delta 为正追加，为负减少。 */
    suspend fun adjustLeverage(
        positionId: String,
        leverage: Int,
        markPrice: BigDecimal,
    ): TradeError? {
        var error: TradeError? = null
        operationMutex.withLock {
            database.withTransaction {
                val current = readAccount()
                val result = TradingEngine.adjustLeverage(current, positionId, leverage, markPrice)
                val updated = result.account
                if (updated == null) {
                    error = result.error
                    return@withTransaction
                }
                dao.upsertAccount(updated.toEntity())
                dao.replacePositions(updated.positions.map { it.toEntity() })
            }
        }
        if (error == null) syncAsync()
        return error
    }

    suspend fun adjustMargin(
        positionId: String,
        delta: BigDecimal,
        markPrice: BigDecimal,
    ): TradeError? {
        var error: TradeError? = null
        operationMutex.withLock {
            database.withTransaction {
                val current = readAccount()
                val result = TradingEngine.adjustMargin(current, positionId, delta, markPrice)
                val updated = result.account
                if (updated == null) {
                    error = result.error
                    return@withTransaction
                }
                dao.upsertAccount(updated.toEntity())
                dao.replacePositions(updated.positions.map { it.toEntity() })
            }
        }
        if (error == null) syncAsync()
        return error
    }

    /** 修改某个仓位的止盈止损价，传 null 表示取消。 */
    suspend fun updateTriggers(
        positionId: String,
        takeProfitPrice: BigDecimal?,
        stopLossPrice: BigDecimal?,
        markPrice: BigDecimal,
    ): TradeError? {
        if (markPrice.signum() <= 0) return TradeError.InvalidAmount
        var error: TradeError? = null
        operationMutex.withLock {
            database.withTransaction {
                val current = readAccount()
                val position = current.positions.firstOrNull { it.id == positionId }
                if (position == null) {
                    error = TradeError.PositionNotFound
                    return@withTransaction
                }
                val invalid = when (position.side) {
                    Side.LONG ->
                        (takeProfitPrice != null &&
                            (takeProfitPrice.signum() <= 0 || takeProfitPrice <= markPrice)) ||
                            (stopLossPrice != null &&
                                (stopLossPrice.signum() <= 0 || stopLossPrice >= markPrice))
                    Side.SHORT ->
                        (takeProfitPrice != null &&
                            (takeProfitPrice.signum() <= 0 || takeProfitPrice >= markPrice)) ||
                            (stopLossPrice != null &&
                                (stopLossPrice.signum() <= 0 || stopLossPrice <= markPrice))
                }
                if (invalid) {
                    error = TradeError.InvalidTriggerPrice
                    return@withTransaction
                }
                val updated = current.positions.map { item ->
                    if (item.id == positionId) {
                        item.copy(
                            takeProfitPrice = takeProfitPrice,
                            stopLossPrice = stopLossPrice,
                        )
                    } else {
                        item
                    }
                }
                dao.replacePositions(updated.map { it.toEntity() })
            }
        }
        if (error == null) syncAsync()
        return error
    }

    /** 检查并执行止盈止损，返回被平掉的仓位。 */
    suspend fun checkTriggers(marks: Map<String, BigDecimal>): List<Position> {
        if (marks.isEmpty()) return emptyList()
        lastMarks = lastMarks + marks
        val effectiveMarks = lastMarks

        val closed = operationMutex.withLock {
            database.withTransaction {
                val current = readAccount()
                val outcome = TradingEngine.checkTriggers(current, effectiveMarks)
                if (outcome.closed.isNotEmpty()) {
                    dao.upsertAccount(outcome.account.toEntity())
                    dao.replacePositions(outcome.account.positions.map { it.toEntity() })
                    outcome.trades.forEach { dao.insertTrade(it.toEntity()) }
                    dao.trimTrades(AppConstants.MAX_LOCAL_HISTORY)
                }
                outcome.closed
            }
        }

        if (closed.isNotEmpty()) syncAsync()
        return closed
    }

    /**
     * 结算某个交易对上的资金费。
     *
     * 规则和币安一致：费率为正时多头付给空头，为负时反过来；
     * 支付额 = 持仓名义价值（数量 × 标记价格）× 费率。
     * 只结算在 [since] 之前就已开仓的仓位，避免收刚开仓的费用。
     */
    suspend fun applyFunding(
        symbol: String,
        rate: BigDecimal,
        markPrice: BigDecimal,
        since: Long,
    ): List<Trade> {
        if (rate.signum() == 0 || markPrice.signum() <= 0) return emptyList()

        val newTrades = operationMutex.withLock {
            database.withTransaction {
                val current = readAccount()
                val targets = current.positions.filter {
                    it.symbol == symbol && it.openedAt <= since
                }
                if (targets.isEmpty()) return@withTransaction emptyList()

                val now = System.currentTimeMillis()
                var cash = current.cash
                val trades = targets.map { position ->
                    val payment = position.quantity
                        .multiply(markPrice)
                        .multiply(rate)
                        .setScale(8, RoundingMode.HALF_UP)
                    val delta = if (position.side == Side.LONG) payment.negate() else payment
                    cash = cash.add(delta).setScale(8, RoundingMode.HALF_UP)
                    Trade(
                        id = UUID.randomUUID().toString(),
                        symbol = position.symbol,
                        baseAsset = position.baseAsset,
                        side = position.side,
                        type = TradeType.FUNDING,
                        quantity = position.quantity,
                        price = markPrice,
                        entryPrice = position.entryPrice,
                        margin = BigDecimal.ZERO,
                        leverage = position.leverage,
                        fee = BigDecimal.ZERO,
                        pnl = delta,
                        openedAt = position.openedAt,
                        createdAt = now,
                    )
                }

                val updated = current.copy(
                    cash = cash.max(BigDecimal.ZERO),
                    trades = trades + current.trades,
                )
                dao.upsertAccount(updated.toEntity())
                dao.replacePositions(updated.positions.map { it.toEntity() })
                trades.forEach { dao.insertTrade(it.toEntity()) }
                dao.trimTrades(AppConstants.MAX_LOCAL_HISTORY)
                trades
            }
        }

        if (newTrades.isNotEmpty()) syncAsync()
        return newTrades
    }

    suspend fun checkLiquidations(marks: Map<String, BigDecimal>): List<Position> {        if (marks.isEmpty()) return emptyList()
        lastMarks = lastMarks + marks
        // 用累积的所有标记价来判断：全仓强平看的是整个账户权益，
        // 只拿当前这个币的最新价会把其他仓位的浮亏当成 0。
        val effectiveMarks = lastMarks

        val liquidated = operationMutex.withLock {
            database.withTransaction {
                val current = readAccount()
                val outcome = TradingEngine.liquidate(current, effectiveMarks)
                if (outcome.liquidated.isNotEmpty()) {
                    dao.upsertAccount(outcome.account.toEntity())
                    dao.replacePositions(outcome.account.positions.map { it.toEntity() })
                    outcome.account.trades
                        .take(outcome.liquidated.size)
                        .forEach { dao.insertTrade(it.toEntity()) }
                    dao.trimTrades(AppConstants.MAX_LOCAL_HISTORY)
                }
                outcome.liquidated
            }
        }

        if (liquidated.isNotEmpty()) syncAsync()
        return liquidated
    }

    suspend fun reset() {
        operationMutex.withLock {
            database.withTransaction {
                dao.replaceAll(defaultAccount().toEntity(), emptyList(), emptyList())
            }
        }
        syncAsync()
    }

    suspend fun clearLocal() {
        operationMutex.withLock {
            database.withTransaction {
                dao.replaceAll(defaultAccount().toEntity(), emptyList(), emptyList())
            }
        }
        _syncState.value = SyncState.IDLE
    }

    suspend fun restoreFromCloud(): Boolean {
        val session = authRepository.ensureValidSession() ?: return false
        return try {
            val cloud = supabase.loadUserData(session)
            if (cloud != null) {
                val cloudAccount = cloudMapper.fromCloud(cloud)
                operationMutex.withLock {
                    database.withTransaction {
                        dao.replaceAll(
                            account = cloudAccount.toEntity(),
                            positions = cloudAccount.positions.map { it.toEntity() },
                            trades = cloudAccount.trades
                                .take(AppConstants.MAX_LOCAL_HISTORY)
                                .map { it.toEntity() },
                        )
                    }
                }
            } else {
                syncNow()
            }
            _syncState.value = SyncState.SYNCED
            true
        } catch (_: Exception) {
            _syncState.value = SyncState.ERROR
            false
        }
    }

    suspend fun syncNow() {
        val session = authRepository.ensureValidSession() ?: return
        syncMutex.withLock {
            _syncState.value = SyncState.SYNCING
            try {
                val current = readAccount()
                supabase.saveUserData(session, cloudMapper.toCloud(current, lastMarks))
                _syncState.value = SyncState.SYNCED
            } catch (e: Exception) {
                _syncState.value = SyncState.ERROR
                throw e
            }
        }
    }

    suspend fun getAccountSnapshot(): Account = readAccount()

    fun updateMarks(marks: Map<String, BigDecimal>) {
        if (marks.isNotEmpty()) lastMarks = lastMarks + marks
    }

    private fun syncAsync() {
        scope.launch {
            runCatching { syncNow() }
        }
    }

    private suspend fun ensureInitialized() {
        operationMutex.withLock {
            if (dao.getAccount() == null) {
                dao.upsertAccount(defaultAccount().toEntity())
            }
        }
    }

    private suspend fun readAccount(): Account {
        val entity = dao.getAccount()
        if (entity == null) {
            val fresh = defaultAccount()
            dao.upsertAccount(fresh.toEntity())
            return fresh
        }
        return Account(
            cash = entity.cash.toDecimalOr(AppConstants.INITIAL_CASH),
            initialCash = entity.initialCash.toDecimalOr(AppConstants.INITIAL_CASH),
            positions = dao.getPositions().map { it.toModel() },
            trades = dao.getTrades().map { it.toModel() },
        )
    }

    private suspend fun persist(account: Account, newTrade: Trade?) {
        dao.upsertAccount(account.toEntity())
        dao.replacePositions(account.positions.map { it.toEntity() })
        if (newTrade != null) {
            dao.insertTrade(newTrade.toEntity())
        }
        dao.trimTrades(AppConstants.MAX_LOCAL_HISTORY)
    }

    private fun defaultAccount(): Account = Account(
        cash = AppConstants.INITIAL_CASH,
        initialCash = AppConstants.INITIAL_CASH,
    )

    private fun String?.toDecimalOr(fallback: BigDecimal): BigDecimal =
        this?.toBigDecimalOrNull() ?: fallback
}
