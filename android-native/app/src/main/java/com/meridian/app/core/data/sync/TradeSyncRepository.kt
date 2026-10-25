package com.meridian.app.core.data.sync

import com.meridian.app.BuildConfig
import com.meridian.app.core.data.market.MarketDataHub
import com.meridian.app.core.data.network.HttpClient
import com.meridian.app.core.data.repository.AShareAccountRepository
import com.meridian.app.core.data.repository.AShareMarketRepository
import com.meridian.app.core.data.repository.AccountRepository
import com.meridian.app.core.data.settings.SettingsStore
import java.math.BigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 把合约账户 + A 股账户的完整快照同步到自建服务器。
 *
 * 本地 SQLite 仍是主库；服务器只做备份和网页展示。
 * 成交后账户 Flow 会变化，防抖 1.5 秒后自动上传一次完整快照。
 */
class TradeSyncRepository(
    private val accountRepository: AccountRepository,
    private val aShareAccountRepository: AShareAccountRepository,
    private val aShareMarketRepository: AShareMarketRepository,
    private val marketDataHub: MarketDataHub,
    private val httpClient: HttpClient,
    private val json: Json,
    private val settingsStore: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val pending = MutableStateFlow(false)

    private val _lastSyncAt = MutableStateFlow(settingsStore.lastTradeSyncAt)
    val lastSyncAt: StateFlow<Long> = _lastSyncAt.asStateFlow()

    @OptIn(FlowPreview::class)
    fun start() {
        if (BuildConfig.TRADE_SYNC_TOKEN.isBlank()) return

        // 启动时先补一次，避免上次退出时还有未上传的成交
        scope.launch { syncNow() }

        // 任意一个账户发生变化（成交、结算、重置）后自动同步
        scope.launch {
            combine(
                accountRepository.account,
                aShareAccountRepository.account,
            ) { _, _ -> Unit }
                .drop(1)
                .debounce(1_500L)
                .collect { syncNow() }
        }

        // 失败后每分钟重试一次；成功就停下来
        scope.launch {
            while (isActive) {
                delay(60_000L)
                if (pending.value) syncNow()
            }
        }
    }

    fun requestSync() {
        scope.launch { syncNow() }
    }

    suspend fun syncNow(): Result<Unit> = mutex.withLock {
        if (BuildConfig.TRADE_SYNC_TOKEN.isBlank()) {
            return@withLock Result.failure(IllegalStateException("未配置同步 token"))
        }
        runCatching {
            val snapshot = buildSnapshot()
            val payload = json.encodeToString(snapshot)
            val response = httpClient.postJson(
                url = BuildConfig.TRADE_SYNC_URL,
                body = payload,
                headers = mapOf("X-Meridian-Token" to BuildConfig.TRADE_SYNC_TOKEN),
            )
            if (!response.contains("\"ok\":true")) {
                error("服务器返回异常")
            }
            val now = System.currentTimeMillis()
            settingsStore.setLastTradeSyncAt(now)
            _lastSyncAt.value = now
            pending.value = false
        }.onFailure {
            pending.value = true
        }
    }

    private suspend fun buildSnapshot(): TradeSyncSnapshot {
        val contract = accountRepository.getAccountSnapshot()
        val marks = marketDataHub.marks.value
        val contractPositions = contract.positions.map { position ->
            val mark = marks[position.symbol] ?: position.entryPrice
            ContractPositionSync(
                id = position.id,
                symbol = position.symbol,
                baseAsset = position.baseAsset,
                side = position.side.name,
                quantity = position.quantity.toPlainString(),
                entryPrice = position.entryPrice.toPlainString(),
                margin = position.margin.toPlainString(),
                leverage = position.leverage,
                liquidationPrice = position.liquidationPrice.toPlainString(),
                marginMode = position.marginMode.name,
                takeProfitPrice = position.takeProfitPrice?.toPlainString(),
                stopLossPrice = position.stopLossPrice?.toPlainString(),
                openedAt = position.openedAt,
                markPrice = mark.toPlainString(),
                unrealizedPnl = position.unrealizedPnl(mark).toPlainString(),
            )
        }
        val contractLimitOrders = contract.limitOrders.map { order ->
            ContractLimitOrderSync(
                id = order.id,
                symbol = order.symbol,
                baseAsset = order.baseAsset,
                side = order.side.name,
                notional = order.notional.toPlainString(),
                limitPrice = order.limitPrice.toPlainString(),
                leverage = order.leverage,
                marginMode = order.marginMode.name,
                takeProfitPrice = order.takeProfitPrice?.toPlainString(),
                stopLossPrice = order.stopLossPrice?.toPlainString(),
                frozenCash = order.frozenCash.toPlainString(),
                createdAt = order.createdAt,
                note = order.note,
            )
        }
        val contractTrades = contract.trades.take(MAX_TRADES).map { trade ->
            ContractTradeSync(
                id = trade.id,
                symbol = trade.symbol,
                baseAsset = trade.baseAsset,
                side = trade.side.name,
                type = trade.type.name,
                quantity = trade.quantity.toPlainString(),
                price = trade.price.toPlainString(),
                entryPrice = trade.entryPrice?.toPlainString(),
                margin = trade.margin.toPlainString(),
                leverage = trade.leverage,
                fee = trade.fee.toPlainString(),
                pnl = trade.pnl?.toPlainString(),
                openedAt = trade.openedAt,
                createdAt = trade.createdAt,
                positionId = trade.positionId,
                note = trade.note,
            )
        }

        val aShare = aShareAccountRepository.getAccountSnapshot()
        val aShareQuotes = if (aShare.holdings.isEmpty()) {
            emptyMap()
        } else {
            runCatching {
                aShareMarketRepository.quotes(aShare.holdings.map { it.symbol })
                    .associateBy { it.symbol }
            }.getOrElse { emptyMap() }
        }
        var aShareMarketValue = BigDecimal.ZERO
        var aShareUnrealized = BigDecimal.ZERO
        val aShareHoldings = aShare.holdings.map { holding ->
            val price = aShareQuotes[holding.symbol]?.price ?: holding.avgCost
            val marketValue = holding.marketValue(price)
            val unrealized = holding.unrealizedPnl(price)
            aShareMarketValue = aShareMarketValue.add(marketValue)
            aShareUnrealized = aShareUnrealized.add(unrealized)
            AShareHoldingSync(
                symbol = holding.symbol,
                name = holding.name,
                secid = holding.secid,
                quantity = holding.quantity,
                availableQuantity = holding.availableQuantity,
                avgCost = holding.avgCost.toPlainString(),
                price = price.toPlainString(),
                marketValue = marketValue.toPlainString(),
                unrealizedPnl = unrealized.toPlainString(),
                unrealizedPnlPercent = holding.unrealizedPnlPercent(price).toPlainString(),
            )
        }
        val aShareTrades = aShare.trades.take(MAX_TRADES).map { trade ->
            AShareTradeSync(
                id = trade.id,
                symbol = trade.symbol,
                name = trade.name,
                side = trade.side.name,
                quantity = trade.quantity,
                price = trade.price.toPlainString(),
                amount = trade.amount.toPlainString(),
                commission = trade.commission.toPlainString(),
                stampDuty = trade.stampDuty.toPlainString(),
                transferFee = trade.transferFee.toPlainString(),
                realizedPnl = trade.realizedPnl?.toPlainString(),
                createdAt = trade.createdAt,
                note = trade.note,
            )
        }

        return TradeSyncSnapshot(
            updatedAt = System.currentTimeMillis(),
            contract = ContractSyncSnapshot(
                cash = contract.cash.toPlainString(),
                initialCash = contract.initialCash.toPlainString(),
                totalAssets = contract.totalAssets(marks).toPlainString(),
                totalPnl = contract.totalPnl(marks).toPlainString(),
                margin = contract.totalMargin().toPlainString(),
                unrealizedPnl = contract.totalUnrealizedPnl(marks).toPlainString(),
                positions = contractPositions,
                limitOrders = contractLimitOrders,
                trades = contractTrades,
            ),
            aShare = AShareSyncSnapshot(
                cash = aShare.cash.toPlainString(),
                initialCash = aShare.initialCash.toPlainString(),
                lastSettlementDate = aShare.lastSettlementDate,
                marketValue = aShareMarketValue.toPlainString(),
                totalAssets = aShare.cash.add(aShareMarketValue).toPlainString(),
                unrealizedPnl = aShareUnrealized.toPlainString(),
                holdings = aShareHoldings,
                trades = aShareTrades,
            ),
        )
    }

    private companion object {
        const val MAX_TRADES = 500
    }
}
