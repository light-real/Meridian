package com.cryptosim.app.core.data.repository

import androidx.room.withTransaction
import com.cryptosim.app.core.AppConstants
import com.cryptosim.app.core.data.local.CryptoSimDatabase
import com.cryptosim.app.core.data.local.toEntity
import com.cryptosim.app.core.data.local.toModel
import com.cryptosim.app.core.data.supabase.SupabaseClient
import com.cryptosim.app.core.domain.TradingEngine
import com.cryptosim.app.core.model.Account
import com.cryptosim.app.core.model.CloseRequest
import com.cryptosim.app.core.model.OrderRequest
import com.cryptosim.app.core.model.Position
import com.cryptosim.app.core.model.Side
import com.cryptosim.app.core.model.SymbolInfo
import com.cryptosim.app.core.model.Trade
import com.cryptosim.app.core.model.TradeOutcome
import java.math.BigDecimal
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
    private val database: CryptoSimDatabase,
    private val authRepository: AuthRepository,
    private val supabase: SupabaseClient,
    private val cloudMapper: CloudMapper,
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
    ) { accountEntity, positions, trades ->
        Account(
            cash = accountEntity?.cash?.toDecimalOr(AppConstants.INITIAL_CASH)
                ?: AppConstants.INITIAL_CASH,
            initialCash = accountEntity?.initialCash?.toDecimalOr(AppConstants.INITIAL_CASH)
                ?: AppConstants.INITIAL_CASH,
            positions = positions.map { it.toModel() },
            trades = trades.map { it.toModel() },
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
    ): TradeOutcome {
        val outcome = operationMutex.withLock {
            database.withTransaction {
                val current = readAccount()
                val result = TradingEngine.close(
                    account = current,
                    request = CloseRequest(positionId, quantity, markPrice),
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

    suspend fun checkLiquidations(marks: Map<String, BigDecimal>): List<Position> {
        if (marks.isEmpty()) return emptyList()
        lastMarks = lastMarks + marks

        val liquidated = operationMutex.withLock {
            database.withTransaction {
                val current = readAccount()
                val outcome = TradingEngine.liquidate(current, marks)
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
