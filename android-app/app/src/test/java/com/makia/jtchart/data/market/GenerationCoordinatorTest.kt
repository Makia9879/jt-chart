package com.makia.jtchart.data.market

import com.makia.jtchart.domain.market.CandleInterval
import com.makia.jtchart.domain.market.MarketSource
import com.makia.jtchart.domain.market.Query
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationCoordinatorTest {
    @Test
    fun `only the latest generation may mutate state or cache`() {
        val coordinator = GenerationCoordinator()
        val first = coordinator.begin()
        val second = coordinator.begin()

        assertFalse(coordinator.accepts(first))
        assertTrue(coordinator.accepts(second))
        coordinator.invalidate()
        assertFalse(coordinator.accepts(second))
    }

    @Test
    fun `token must match both latest generation and expected query`() {
        val coordinator = GenerationCoordinator()
        val btc = Query(MarketSource.BINANCE_SPOT, "BTCUSDT", CandleInterval.ONE_HOUR, 120)
        val eth = btc.copy(symbol = "ETHUSDT")
        val token = coordinator.begin(btc)

        assertTrue(coordinator.accepts(token, btc))
        assertFalse(coordinator.accepts(token, eth))
        coordinator.begin(eth)
        assertFalse(coordinator.accepts(token, btc))
    }
}
