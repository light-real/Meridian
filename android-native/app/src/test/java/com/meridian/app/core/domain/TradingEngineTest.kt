package com.meridian.app.core.domain

import com.meridian.app.core.model.Account
import com.meridian.app.core.model.AmountMode
import com.meridian.app.core.model.CloseRequest
import com.meridian.app.core.model.LimitOrder
import com.meridian.app.core.model.MarginMode
import com.meridian.app.core.model.OrderRequest
import com.meridian.app.core.model.Side
import com.meridian.app.core.model.SymbolRules
import com.meridian.app.core.model.TradeError
import com.meridian.app.core.model.TradeOutcome
import com.meridian.app.core.model.TradeType
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TradingEngineTest {

    private val rules = SymbolRules(
        priceScale = 2,
        quantityScale = 3,
        minNotional = BigDecimal("5"),
        stepSize = BigDecimal("0.001"),
    )

    private fun account(cash: String = "10000"): Account =
        Account(cash = BigDecimal(cash), initialCash = BigDecimal("10000"))

    private fun openRequest(
        side: Side = Side.LONG,
        notional: String = "1000",
        leverage: Int = 10,
    ) = OrderRequest(
        symbol = "BTCUSDT",
        baseAsset = "BTC",
        side = side,
        notional = BigDecimal(notional),
        leverage = leverage,
        rules = rules,
    )

    private fun success(outcome: TradeOutcome): TradeOutcome.Success {
        assertTrue("expected success but was $outcome", outcome is TradeOutcome.Success)
        return outcome as TradeOutcome.Success
    }

    private fun assertDecimal(expected: String, actual: BigDecimal) {
        assertEquals("expected $expected but was $actual", 0, BigDecimal(expected).compareTo(actual))
    }

    @Test
    fun `open long deducts margin and fee`() {
        val result = success(TradingEngine.open(account(), openRequest(), BigDecimal("100")))

        assertDecimal("9899.5", result.account.cash)
        assertEquals(1, result.account.positions.size)
        val position = result.account.positions.single()
        assertDecimal("10", position.quantity)
        assertDecimal("100", position.margin)
        assertDecimal("100", position.entryPrice)
        assertDecimal("90.05", position.liquidationPrice)
        assertDecimal("0.5", result.trade.fee)
        assertEquals(Side.LONG, position.side)
    }

    @Test
    fun `open and close keep the note and share position id`() {
        val openNote = "突破后回踩 20 日线"
        val opened = success(
            TradingEngine.open(
                account(),
                openRequest().copy(note = openNote),
                BigDecimal("100"),
            ),
        )
        assertEquals(openNote, opened.trade.note)
        assertEquals(opened.account.positions.single().id, opened.trade.positionId)

        val closed = success(
            TradingEngine.close(
                opened.account,
                com.meridian.app.core.model.CloseRequest(
                    positionId = opened.trade.positionId!!,
                    quantity = BigDecimal("10"),
                    markPrice = BigDecimal("110"),
                    note = "到目标位，先落袋",
                ),
            ),
        )
        assertEquals("到目标位，先落袋", closed.trade.note)
        assertEquals(opened.trade.positionId, closed.trade.positionId)
    }

    @Test
    fun `blank note is stored as null`() {
        val opened = success(
            TradingEngine.open(
                account(),
                openRequest().copy(note = "   "),
                BigDecimal("100"),
            ),
        )
        assertNull(opened.trade.note)
    }

    @Test
    fun `opening same side merges into one position`() {
        val first = success(TradingEngine.open(account(), openRequest(), BigDecimal("100")))
        val second = success(TradingEngine.open(first.account, openRequest(), BigDecimal("110")))

        assertEquals(1, second.account.positions.size)
        val position = second.account.positions.single()
        assertDecimal("19.090", position.quantity)
        // 第二笔在 110 只能买到 9.090 个（step 0.001），实际名义 999.9，保证金 99.99
        assertDecimal("199.99", position.margin)
        assertDecimal("9799.01005", second.account.cash)
        assertTrue(
            "entry should be a weighted average",
            position.entryPrice > BigDecimal("104.7") && position.entryPrice < BigDecimal("104.8"),
        )
    }

    @Test
    fun `opening opposite side is rejected`() {
        val long = success(TradingEngine.open(account(), openRequest(), BigDecimal("100")))
        val short = TradingEngine.open(
            long.account,
            openRequest(side = Side.SHORT),
            BigDecimal("100"),
        )

        assertEquals(
            TradeOutcome.Rejected(TradeError.OppositePositionExists),
            short,
        )
    }

    @Test
    fun `opening same side with a different leverage is rejected`() {
        val first = success(TradingEngine.open(account(), openRequest(), BigDecimal("100")))
        val second = TradingEngine.open(
            first.account,
            openRequest(leverage = 20),
            BigDecimal("100"),
        )

        assertEquals(TradeOutcome.Rejected(TradeError.LeverageMismatch), second)
    }

    @Test
    fun `closing more than held never creates a negative position`() {
        val opened = success(TradingEngine.open(account(), openRequest(), BigDecimal("100")))
        val positionId = opened.account.positions.single().id

        val closed = success(
            TradingEngine.close(
                opened.account,
                CloseRequest(positionId, BigDecimal("20"), BigDecimal("100")),
            ),
        )

        assertTrue(closed.clamped)
        assertTrue(closed.account.positions.isEmpty())
        assertDecimal("10", closed.trade.quantity)
        assertDecimal("9999", closed.account.cash)
        assertDecimal("-0.5", closed.trade.pnl!!)
    }

    @Test
    fun `partial close keeps the remaining position and releases proportional margin`() {
        val opened = success(TradingEngine.open(account(), openRequest(), BigDecimal("100")))
        val positionId = opened.account.positions.single().id

        val closed = success(
            TradingEngine.close(
                opened.account,
                CloseRequest(positionId, BigDecimal("4"), BigDecimal("110")),
            ),
        )

        val remaining = closed.account.positions.single()
        assertDecimal("6", remaining.quantity)
        assertDecimal("60", remaining.margin)
        assertDecimal("9979.28", closed.account.cash)
        assertDecimal("39.78", closed.trade.pnl!!)
    }

    @Test
    fun `liquidation triggers at the maintenance margin boundary`() {
        val opened = success(TradingEngine.open(account(), openRequest(), BigDecimal("100")))
        val position = opened.account.positions.single()

        assertTrue(TradingEngine.isLiquidated(position, BigDecimal("90.05")))
        assertTrue(!TradingEngine.isLiquidated(position, BigDecimal("90.06")))

        val outcome = TradingEngine.liquidate(opened.account, mapOf("BTCUSDT" to BigDecimal("90.05")))
        assertEquals(1, outcome.liquidated.size)
        assertTrue(outcome.account.positions.isEmpty())
        assertDecimal("9899.5", outcome.account.cash)
        assertDecimal("-100", outcome.account.trades.first().pnl!!)
    }

    @Test
    fun `total assets include unrealized pnl`() {
        val opened = success(TradingEngine.open(account(), openRequest(), BigDecimal("100")))
        val total = opened.account.totalAssets(mapOf("BTCUSDT" to BigDecimal("110")))

        assertDecimal("10099.5", total)
        assertDecimal("100", opened.account.totalUnrealizedPnl(mapOf("BTCUSDT" to BigDecimal("110"))))
    }

    @Test
    fun `order preview rejects insufficient cash and small notional`() {
        val poor = Account(cash = BigDecimal("100"), initialCash = BigDecimal("100"))
        val insufficient = OrderCalculator.preview(
            account = poor,
            side = Side.LONG,
            leverage = 10,
            mode = AmountMode.MARGIN,
            amount = BigDecimal("100"),
            markPrice = BigDecimal("100"),
            rules = rules,
        )
        assertEquals(TradeError.InsufficientBalance, insufficient.error)

        val tiny = OrderCalculator.preview(
            account = account(),
            side = Side.LONG,
            leverage = 1,
            mode = AmountMode.MARGIN,
            amount = BigDecimal("1"),
            markPrice = BigDecimal("100"),
            rules = rules,
        )
        assertEquals(TradeError.BelowMinNotional, tiny.error)
    }

    @Test
    fun `one hundred percent margin still leaves enough cash for the fee`() {
        val maxAmount = OrderCalculator.amountForPercent(
            account = account(),
            leverage = 10,
            mode = AmountMode.MARGIN,
            percent = BigDecimal.ONE,
        )
        val preview = OrderCalculator.preview(
            account = account(),
            side = Side.LONG,
            leverage = 10,
            mode = AmountMode.MARGIN,
            amount = maxAmount,
            markPrice = BigDecimal("100"),
            rules = rules,
        )

        assertTrue("preview should be submittable: $preview", preview.canSubmit)
        assertTrue(preview.requiredCash <= BigDecimal("10000"))
    }

    @Test
    fun `margin is based on executed notional after step rounding`() {
        // BTC step=0.001，1000 名义价值在 80000 价位只能买到 0.012 个（不是 0.0125）
        val result = success(
            TradingEngine.open(
                account(),
                openRequest(notional = "1000", leverage = 10),
                BigDecimal("80000"),
            ),
        )

        val position = result.account.positions.single()
        assertDecimal("0.012", position.quantity)
        assertDecimal("96", position.margin)
        assertDecimal("0.48", result.trade.fee)
        assertDecimal("9903.52", result.account.cash)
    }

    @Test
    fun `cross open only deducts fee`() {
        val result = success(
            TradingEngine.open(
                account(),
                openRequest().copy(marginMode = MarginMode.CROSS),
                BigDecimal("100"),
            ),
        )

        // 全仓不锁保证金，只扣 1000 * 0.05% = 0.5 的手续费
        assertDecimal("9999.5", result.account.cash)
        val position = result.account.positions.single()
        assertDecimal("100", position.margin)
        assertEquals(MarginMode.CROSS, position.marginMode)
        assertDecimal("0", position.liquidationPrice)
    }

    @Test
    fun `cross margin reduces the cash available to isolated orders`() {
        val cross = success(
            TradingEngine.open(
                account(),
                openRequest(notional = "1000", leverage = 10)
                    .copy(marginMode = MarginMode.CROSS),
                BigDecimal("100"),
            ),
        )

        // 现金 9999.5，全仓占用 100，还剩 9899.5；1x 开 9900 名义要 9904.95，不够
        val isolated = TradingEngine.open(
            cross.account,
            openRequest(notional = "9900", leverage = 1),
            BigDecimal("100"),
        )

        assertTrue(isolated is TradeOutcome.Rejected)
        assertEquals(
            TradeError.InsufficientBalance,
            (isolated as TradeOutcome.Rejected).error,
        )
    }

    @Test
    fun `raising leverage releases isolated margin`() {
        val opened = success(
            TradingEngine.open(
                account(),
                openRequest(notional = "1000", leverage = 10),
                BigDecimal("100"),
            ),
        )
        val position = opened.account.positions.single()

        val adjusted = TradingEngine.adjustLeverage(
            account = opened.account,
            positionId = position.id,
            leverage = 20,
            markPrice = BigDecimal("100"),
        )

        val updated = adjusted.account!!
        assertDecimal("9949.5", updated.cash)
        val changed = updated.positions.single()
        assertEquals(20, changed.leverage)
        assertDecimal("50", changed.margin)
        assertDecimal("10", changed.quantity)
    }

    @Test
    fun `lowering leverage is rejected when cash cannot cover the extra margin`() {
        val opened = success(
            TradingEngine.open(
                account(),
                openRequest(notional = "20000", leverage = 10),
                BigDecimal("100"),
            ),
        )
        val position = opened.account.positions.single()

        val adjusted = TradingEngine.adjustLeverage(
            account = opened.account,
            positionId = position.id,
            leverage = 1,
            markPrice = BigDecimal("100"),
        )

        assertEquals(TradeError.InsufficientBalance, adjusted.error)
        assertNull(adjusted.account)
        assertEquals(10, position.leverage)
    }

    @Test
    fun `cross leverage change only adjusts occupied margin`() {
        val opened = success(
            TradingEngine.open(
                account(),
                openRequest(notional = "1000", leverage = 10)
                    .copy(marginMode = MarginMode.CROSS),
                BigDecimal("100"),
            ),
        )
        val position = opened.account.positions.single()

        val adjusted = TradingEngine.adjustLeverage(
            account = opened.account,
            positionId = position.id,
            leverage = 100,
            markPrice = BigDecimal("100"),
        )

        val updated = adjusted.account!!
        assertDecimal("9999.5", updated.cash)
        val changed = updated.positions.single()
        assertEquals(100, changed.leverage)
        assertDecimal("10", changed.margin)
    }

    @Test
    fun `cross close returns only pnl`() {
        val opened = success(
            TradingEngine.open(
                account(),
                openRequest().copy(marginMode = MarginMode.CROSS),
                BigDecimal("100"),
            ),
        )
        val position = opened.account.positions.single()

        val closed = success(
            TradingEngine.close(
                opened.account,
                CloseRequest(
                    positionId = position.id,
                    quantity = position.quantity,
                    markPrice = BigDecimal("110"),
                ),
            ),
        )

        // 盈利 10 * 10 = 100，平仓手续费 1100 * 0.05% = 0.55 → 净 99.45
        assertDecimal("10098.95", closed.account.cash)
        assertTrue(closed.account.positions.isEmpty())
    }

    @Test
    fun `cross position is liquidated when equity falls under maintenance margin`() {
        // 全仓 5 万名义价值、10 倍，账户 1 万：价格跌到 80.5 以下时账户权益跌破维持保证金
        val opened = success(
            TradingEngine.open(
                account(),
                openRequest().copy(notional = BigDecimal("50000"), marginMode = MarginMode.CROSS),
                BigDecimal("100"),
            ),
        )

        val safe = TradingEngine.liquidate(opened.account, mapOf("BTCUSDT" to BigDecimal("95")))
        assertTrue(safe.liquidated.isEmpty())

        val liquidated = TradingEngine.liquidate(
            opened.account,
            mapOf("BTCUSDT" to BigDecimal("79")),
        )
        assertEquals(1, liquidated.liquidated.size)
        assertTrue(liquidated.account.positions.isEmpty())
        // 权益已经为负，强平后现金归零
        assertDecimal("0", liquidated.account.cash)
    }

    @Test
    fun `take profit closes a long position`() {
        val opened = success(
            TradingEngine.open(
                account(),
                openRequest().copy(takeProfitPrice = BigDecimal("120")),
                BigDecimal("100"),
            ),
        )
        val position = opened.account.positions.single()
        assertDecimal("120", position.takeProfitPrice!!)

        // 没到价不动
        val idle = TradingEngine.checkTriggers(opened.account, mapOf("BTCUSDT" to BigDecimal("115")))
        assertTrue(idle.closed.isEmpty())

        val triggered = TradingEngine.checkTriggers(
            opened.account,
            mapOf("BTCUSDT" to BigDecimal("121")),
        )
        assertEquals(1, triggered.closed.size)
        assertTrue(triggered.account.positions.isEmpty())
        assertEquals(TradeType.TAKE_PROFIT, triggered.trades.single().type)
    }

    @Test
    fun `stop loss closes a short position`() {
        val opened = success(
            TradingEngine.open(
                account(),
                openRequest(side = Side.SHORT).copy(stopLossPrice = BigDecimal("110")),
                BigDecimal("100"),
            ),
        )

        val idle = TradingEngine.checkTriggers(opened.account, mapOf("BTCUSDT" to BigDecimal("105")))
        assertTrue(idle.closed.isEmpty())

        val triggered = TradingEngine.checkTriggers(
            opened.account,
            mapOf("BTCUSDT" to BigDecimal("111")),
        )
        assertEquals(TradeType.STOP_LOSS, triggered.trades.single().type)
        assertTrue(triggered.account.positions.isEmpty())
    }

    @Test
    fun `trigger price in the wrong direction is rejected`() {
        val rejected = TradingEngine.open(
            account(),
            openRequest().copy(takeProfitPrice = BigDecimal("90")),
            BigDecimal("100"),
        )

        assertTrue(rejected is TradeOutcome.Rejected)
        assertEquals(
            TradeError.InvalidTriggerPrice,
            (rejected as TradeOutcome.Rejected).error,
        )
    }

    @Test
    fun `limit order fills only when the price reaches it`() {
        val buy = LimitOrder(
            id = "1",
            symbol = "BTCUSDT",
            baseAsset = "BTC",
            side = Side.LONG,
            notional = BigDecimal("1000"),
            limitPrice = BigDecimal("95"),
            leverage = 10,
        )
        assertTrue(!buy.isTriggered(BigDecimal("100")))
        assertTrue(buy.isTriggered(BigDecimal("95")))
        assertTrue(buy.isTriggered(BigDecimal("94")))

        val sell = buy.copy(side = Side.SHORT, limitPrice = BigDecimal("105"))
        assertTrue(!sell.isTriggered(BigDecimal("100")))
        assertTrue(sell.isTriggered(BigDecimal("105")))
        assertTrue(sell.isTriggered(BigDecimal("106")))
    }

    @Test
    fun `opening with a different margin mode is rejected`() {
        val first = success(TradingEngine.open(account(), openRequest(), BigDecimal("100")))
        val second = TradingEngine.open(
            first.account,
            openRequest().copy(marginMode = MarginMode.CROSS),
            BigDecimal("100"),
        )

        assertTrue(second is TradeOutcome.Rejected)
        assertEquals(TradeError.MarginModeMismatch, (second as TradeOutcome.Rejected).error)
    }

    @Test
    fun `adding isolated margin moves cash into position`() {
        val opened = success(TradingEngine.open(account(), openRequest(), BigDecimal("100")))
        val position = opened.account.positions.single()

        val result = TradingEngine.adjustMargin(
            account = opened.account,
            positionId = position.id,
            delta = BigDecimal("50"),
            markPrice = BigDecimal("100"),
        )

        assertTrue("expected success but was ${result.error}", result.account != null)
        val updated = result.account!!
        assertDecimal("150", updated.positions.single().margin)
        assertDecimal("9849.5", updated.cash)
        assertDecimal("85.075", updated.positions.single().liquidationPrice)
    }

    @Test
    fun `reducing isolated margin returns cash to balance`() {
        val opened = success(TradingEngine.open(account(), openRequest(), BigDecimal("100")))
        val position = opened.account.positions.single()

        val result = TradingEngine.adjustMargin(
            account = opened.account,
            positionId = position.id,
            delta = BigDecimal("-50"),
            markPrice = BigDecimal("100"),
        )

        assertTrue("expected success but was ${result.error}", result.account != null)
        val updated = result.account!!
        assertDecimal("50", updated.positions.single().margin)
        assertDecimal("9949.5", updated.cash)
        assertDecimal("95.025", updated.positions.single().liquidationPrice)
    }

    @Test
    fun `reducing margin below liquidation floor is rejected`() {
        val opened = success(TradingEngine.open(account(), openRequest(), BigDecimal("100")))
        val position = opened.account.positions.single()

        // 价格跌到 95，浮亏 50；再减 60 后保证金只剩 40，低于「不立即强平」的下限
        val result = TradingEngine.adjustMargin(
            account = opened.account,
            positionId = position.id,
            delta = BigDecimal("-60"),
            markPrice = BigDecimal("95"),
        )

        assertEquals(TradeError.MarginTooLow, result.error)
        assertTrue(result.account == null)
    }

    @Test
    fun `reducing all margin is rejected`() {
        val opened = success(TradingEngine.open(account(), openRequest(), BigDecimal("100")))
        val position = opened.account.positions.single()

        val result = TradingEngine.adjustMargin(
            account = opened.account,
            positionId = position.id,
            delta = BigDecimal("-100"),
            markPrice = BigDecimal("100"),
        )

        assertEquals(TradeError.MarginTooLow, result.error)
    }

    @Test
    fun `adding more margin than available cash is rejected`() {
        val opened = success(TradingEngine.open(account(), openRequest(), BigDecimal("100")))
        val position = opened.account.positions.single()

        val result = TradingEngine.adjustMargin(
            account = opened.account,
            positionId = position.id,
            delta = BigDecimal("20000"),
            markPrice = BigDecimal("100"),
        )

        assertEquals(TradeError.InsufficientBalance, result.error)
    }

    @Test
    fun `adjusting cross margin is rejected`() {
        val opened = success(
            TradingEngine.open(
                account(),
                openRequest().copy(marginMode = MarginMode.CROSS),
                BigDecimal("100"),
            ),
        )
        val position = opened.account.positions.single()

        val result = TradingEngine.adjustMargin(
            account = opened.account,
            positionId = position.id,
            delta = BigDecimal("50"),
            markPrice = BigDecimal("100"),
        )

        assertEquals(TradeError.MarginModeMismatch, result.error)
    }

    @Test
    fun `cross long liquidation uses the whole account as collateral`() {
        val opened = success(
            TradingEngine.open(
                account(cash = "10050"),
                openRequest(notional = "100000", leverage = 10)
                    .copy(marginMode = MarginMode.CROSS),
                BigDecimal("100"),
            ),
        )
        val position = opened.account.positions.single()

        val liquidation = TradingEngine.crossLiquidationPrice(
            account = opened.account,
            position = position,
            marks = mapOf("BTCUSDT" to BigDecimal("100")),
        )

        // 10 万名义价值、1 万本金，强平价约在 90.45
        assertDecimal("90.45226131", liquidation)
    }

    @Test
    fun `cross short liquidation uses the whole account as collateral`() {
        val opened = success(
            TradingEngine.open(
                account(cash = "10050"),
                openRequest(side = Side.SHORT, notional = "100000", leverage = 10)
                    .copy(marginMode = MarginMode.CROSS),
                BigDecimal("100"),
            ),
        )
        val position = opened.account.positions.single()

        val liquidation = TradingEngine.crossLiquidationPrice(
            account = opened.account,
            position = position,
            marks = mapOf("BTCUSDT" to BigDecimal("100")),
        )

        assertDecimal("109.45273632", liquidation)
    }

    @Test
    fun `reducing a 150x position is rejected by the leverage cap`() {
        val opened = success(
            TradingEngine.open(
                account(),
                openRequest(notional = "1000", leverage = 150),
                BigDecimal("100"),
            ),
        )
        val position = opened.account.positions.single()

        // 150x 开仓的保证金已经是 150x 下限，再减 1 U 就会超过 150x
        val result = TradingEngine.adjustMargin(
            account = opened.account,
            positionId = position.id,
            delta = BigDecimal("-1"),
            markPrice = BigDecimal("100"),
        )

        assertEquals(TradeError.MarginTooLow, result.error)
        assertDecimal(
            "6.66666667",
            TradingEngine.minimumMargin(position, BigDecimal("100")),
        )
    }
}
