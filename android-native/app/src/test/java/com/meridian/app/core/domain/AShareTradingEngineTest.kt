package com.meridian.app.core.domain

import com.meridian.app.core.model.AShareAccount
import com.meridian.app.core.model.AShareCalendar
import com.meridian.app.core.model.AShareOrderRequest
import com.meridian.app.core.model.AShareSide
import com.meridian.app.core.model.AShareTradeError
import com.meridian.app.core.model.AShareTradeResult
import java.math.BigDecimal
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AShareTradingEngineTest {

    private val monday = ZonedDateTime.of(2026, 10, 26, 10, 0, 0, 0, AShareCalendar.ZONE)
    private val tuesday = monday.plusDays(1)

    private fun request(
        side: AShareSide = AShareSide.BUY,
        quantity: Int = 100,
        price: String = "10.00",
        prevClose: String = "10.00",
        symbol: String = "600000",
        name: String = "浦发银行",
    ) = AShareOrderRequest(
        symbol = symbol,
        name = name,
        secid = "1.$symbol",
        side = side,
        quantity = quantity,
        price = BigDecimal(price),
        prevClose = BigDecimal(prevClose),
    )

    private fun buy(
        account: AShareAccount = AShareAccount(),
        request: AShareOrderRequest = request(),
        now: ZonedDateTime = monday,
    ): AShareTradeResult = AShareTradingEngine.buy(account, request, now)

    private fun success(result: AShareTradeResult): AShareTradeResult.Success {
        assertTrue("expected success but was $result", result is AShareTradeResult.Success)
        return result as AShareTradeResult.Success
    }

    private fun assertDecimal(expected: String, actual: BigDecimal) {
        assertEquals("expected $expected but was $actual", 0, BigDecimal(expected).compareTo(actual))
    }

    @Test
    fun `buy keeps the note on the trade`() {
        val result = success(buy(request = request().copy(note = "业绩超预期，先建底仓")))

        assertEquals("业绩超预期，先建底仓", result.trade.note)
    }

    @Test
    fun `buy deducts amount and fees and locks shares for t+1`() {
        val result = success(buy())

        assertDecimal("98994.99", result.account.cash)
        val holding = result.account.holdings.single()
        assertEquals(100, holding.quantity)
        assertEquals(0, holding.availableQuantity)
        assertDecimal("10", holding.avgCost)
        assertDecimal("5", result.trade.commission)
        assertDecimal("0.01", result.trade.transferFee)
        assertEquals(AShareSide.BUY, result.trade.side)

        val second = AShareTradingEngine.sell(
            result.account,
            request(side = AShareSide.SELL, quantity = 100, price = "10.50"),
            monday,
        )
        assertEquals(
            AShareTradeError.InsufficientShares,
            (second as AShareTradeResult.Rejected).error,
        )
    }

    @Test
    fun `sell next trading day unlocks shares and charges stamp duty`() {
        val bought = success(buy())
        val sold = success(
            AShareTradingEngine.sell(
                bought.account,
                request(side = AShareSide.SELL, quantity = 100, price = "11.00"),
                tuesday,
            ),
        )

        assertDecimal("100089.43", sold.account.cash)
        assertDecimal("5", sold.trade.commission)
        assertDecimal("0.55", sold.trade.stampDuty)
        assertDecimal("0.01", sold.trade.transferFee)
        assertDecimal("94.44", sold.trade.realizedPnl!!)
        assertTrue(sold.account.holdings.isEmpty())
    }

    @Test
    fun `buy must be a multiple of 100 shares`() {
        val result = buy(request = request(quantity = 150))
        assertEquals(
            AShareTradeError.NotMultipleOfLot,
            (result as AShareTradeResult.Rejected).error,
        )
    }

    @Test
    fun `buy outside trading hours is rejected`() {
        val result = buy(now = ZonedDateTime.of(2026, 10, 25, 10, 0, 0, 0, AShareCalendar.ZONE))
        assertEquals(
            AShareTradeError.MarketClosed,
            (result as AShareTradeResult.Rejected).error,
        )
    }

    @Test
    fun `buy above the price limit is rejected`() {
        val result = buy(request = request(price = "11.01", prevClose = "10.00"))
        assertEquals(
            AShareTradeError.PriceLimit,
            (result as AShareTradeResult.Rejected).error,
        )
    }

    @Test
    fun `buy with insufficient cash is rejected`() {
        val result = buy(
            account = AShareAccount(cash = BigDecimal("100.00")),
            request = request(quantity = 100, price = "10.00"),
        )
        assertEquals(
            AShareTradeError.InsufficientCash,
            (result as AShareTradeResult.Rejected).error,
        )
    }

    @Test
    fun `st stock uses a five percent price limit`() {
        val result = buy(
            request = request(
                price = "10.51",
                prevClose = "10.00",
                name = "ST 测试",
            ),
        )
        assertEquals(
            AShareTradeError.PriceLimit,
            (result as AShareTradeResult.Rejected).error,
        )
    }
}
