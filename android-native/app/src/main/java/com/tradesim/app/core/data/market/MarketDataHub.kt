package com.tradesim.app.core.data.market

import com.tradesim.app.core.data.repository.AccountRepository
import com.tradesim.app.core.data.repository.MarketRepository
import com.tradesim.app.core.model.OrderBook
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 全 App 唯一的价格中枢。
 *
 * 订阅范围 = 所有持仓币种 + 所有挂单币种 + 当前正在看的币种。
 * 页面切换只改 activeSymbol，各页面不再各自开 WebSocket、也不再各自解析盘口。
 *
 * 对外节奏固定：
 *  - [marks] 每秒发布一次（三个页面共用同一份标记价）
 *  - [orderBook] 每秒一次（只有盘口面板会重组）
 *
 * 强平 / 止盈止损 / 限价成交也在这里统一检查，避免每个页面各查一遍。
 */
class MarketDataHub(
    private val marketRepository: MarketRepository,
    private val accountRepository: AccountRepository,
    private val scope: CoroutineScope,
) {
    private val _marks = MutableStateFlow<Map<String, BigDecimal>>(emptyMap())
    val marks: StateFlow<Map<String, BigDecimal>> = _marks.asStateFlow()

    private val _orderBook = MutableStateFlow<OrderBook?>(null)
    val orderBook: StateFlow<OrderBook?> = _orderBook.asStateFlow()

    private val activeSymbol = MutableStateFlow<String?>(null)

    /** 盘口最新中间价，10Hz 写入，读的时候由 [publishMarks] 统一降到 1Hz。 */
    private val midPrices = ConcurrentHashMap<String, BigDecimal>()

    /** 需要订阅盘口的币种：持仓 + 挂单 + 当前页。 */
    private val watched: StateFlow<List<String>> = combine(
        accountRepository.account.map { account ->
            (account.positions.map { it.symbol } + account.limitOrders.map { it.symbol })
                .distinct()
        },
        activeSymbol,
    ) { symbols, active ->
        (symbols + listOfNotNull(active)).distinct()
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** 有仓位或挂单时才需要每秒做一次强平 / 止盈止损 / 成交检查。 */
    private val hasWork: StateFlow<Boolean> = accountRepository.account
        .map { it.positions.isNotEmpty() || it.limitOrders.isNotEmpty() }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    private var started = false
    private var lastBookRender = 0L

    fun start() {
        if (started) return
        started = true

        // 深度：5 档、100ms，按「关注列表」合并。全 App 只有这一组连接。
        scope.launch {
            watched
                .flatMapLatest { symbols ->
                    if (symbols.isEmpty()) {
                        emptyFlow()
                    } else {
                        symbols.asFlow().flatMapMerge { symbol ->
                            marketRepository.depthStream(symbol, levels = 5)
                        }
                    }
                }
                .catch { }
                .collect { book ->
                    val mid = book.midPrice() ?: return@collect
                    midPrices[book.symbol] = mid

                    if (book.symbol == activeSymbol.value) {
                        val now = System.currentTimeMillis()
                        if (now - lastBookRender >= 1_000L) {
                            lastBookRender = now
                            _orderBook.value = book
                        }
                    }
                }
        }

        // 每秒发布一次标记价；强平 / 止盈止损每 3 秒检查一次就够，
        // 没必要每秒读三遍数据库。
        scope.launch {
            var ticks = 0
            while (isActive) {
                delay(1_000L)
                publishMarks()
                ticks++
                if (ticks % 3 == 0) {
                    checkAccount()
                }
            }
        }

        // REST 兜底：盘口断流时价格也别停住
        scope.launch {
            while (isActive) {
                delay(5_000L)
                val symbols = watched.value
                if (symbols.isEmpty()) continue
                runCatching { marketRepository.tickers(symbols) }
                    .getOrNull()
                    ?.forEach { ticker ->
                        midPrices[ticker.symbol] = ticker.lastPrice
                    }
            }
        }
    }

    /** 交易页切换币种时调用；不切页时也保留最后看的币种，不额外开连接。 */
    fun setActiveSymbol(symbol: String?) {
        if (activeSymbol.value == symbol) return
        _orderBook.value = null
        lastBookRender = 0L
        activeSymbol.value = symbol
    }

    /** 只清掉自己占用的那个币种，避免旧页面把新页面的订阅清掉。 */
    fun clearActiveSymbol(symbol: String?) {
        if (symbol != null && activeSymbol.value == symbol) {
            _orderBook.value = null
            activeSymbol.value = null
        }
    }

    private fun publishMarks() {
        val symbols = watched.value
        val snapshot = if (symbols.isEmpty()) {
            emptyMap()
        } else {
            symbols.mapNotNull { symbol ->
                midPrices[symbol]?.let { symbol to it }
            }.toMap()
        }
        if (snapshot != _marks.value) {
            _marks.value = snapshot
        }
    }

    private suspend fun checkAccount() {
        if (!hasWork.value) return
        val marks = _marks.value
        if (marks.isEmpty()) return

        accountRepository.updateMarks(marks)
        runCatching { accountRepository.checkLiquidations(marks) }
        runCatching { accountRepository.checkTriggers(marks) }
        runCatching { accountRepository.checkLimitOrders(marks) }
    }

    private fun OrderBook.midPrice(): BigDecimal? {
        val bestBid = bids.firstOrNull()?.price ?: return null
        val bestAsk = asks.firstOrNull()?.price ?: return null
        if (bestBid.signum() <= 0 || bestAsk.signum() <= 0) return null
        return bestBid.add(bestAsk).divide(BigDecimal("2"), 8, RoundingMode.HALF_UP)
    }
}
