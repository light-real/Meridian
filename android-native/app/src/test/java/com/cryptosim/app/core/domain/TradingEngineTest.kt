package com.cryptosim.app.core.domain

import com.cryptosim.app.core.model.Account
import com.cryptosim.app.core.model.AmountMode
import com.cryptosim.app.core.model.CloseRequest
import com.cryptosim.app.core.model.OrderRequest
import com.cryptosim.app.core.model.Side
import com.cryptosim.app.core.model.SymbolRules
import com.cryptosim.app.core.model.TradeError
import com.cryptosim.app.core.model.TradeOutcome
import java.math.BigDecimal
import org.junit.Assert.assertEquals
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

        assertDecimal("9899", result.account.cash)
        assertEquals(1, result.account.positions.size)
        val position = result.account.positions.single()
        assertDecimal("10", position.quantity)
        assertDecimal("100", position.margin)
        assertDecimal("100", position.entryPrice)
        assertDecimal("90.05", position.liquidationPrice)
        assertDecimal("1", result.trade.fee)
        assertEquals(Side.LONG, position.side)
    }

    @Test
    fun `opening same side merges into one position`() {
        val first = success(TradingEngine.open(account(), openRequest(), BigDecimal("100")))
        val second = success(TradingEngine.open(first.account, openRequest(), BigDecimal("110")))

        assertEquals(1, second.account.positions.size)
        val position = second.account.positions.single()
        assertDecimal("19.090", position.quantity)
        assertDecimal("200", position.margin)
        assertDecimal("9798", second.account.cash)
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
        assertDecimal("9998", closed.account.cash)
        assertDecimal("-1", closed.trade.pnl!!)
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
        assertDecimal("9978.56", closed.account.cash)
        assertDecimal("39.56", closed.trade.pnl!!)
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
        assertDecimal("9899", outcome.account.cash)
        assertDecimal("-100", outcome.account.trades.first().pnl!!)
    }

    @Test
    fun `total assets include unrealized pnl`() {
        val opened = success(TradingEngine.open(account(), openRequest(), BigDecimal("100")))
        val total = opened.account.totalAssets(mapOf("BTCUSDT" to BigDecimal("110")))

        assertDecimal("10099", total)
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
}
