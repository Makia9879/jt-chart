package com.makia.jtchart.domain.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MarketModelsTest {
    @Test
    fun `query key is a structured canonical value`() {
        val query = Query(MarketSource.BINANCE_SPOT, "BTCUSDT", CandleInterval.ONE_HOUR, 120)

        assertEquals(
            """{"source":"spot","symbol":"BTCUSDT","interval":"1h","limit":120}""",
            query.canonicalKey,
        )
    }

    @Test
    fun `query normalizes symbol while enforcing production limit`() {
        val query = Query.normalized(MarketSource.BINANCE_SPOT, " btcusdt ", CandleInterval.ONE_HOUR)

        assertEquals("BTCUSDT", query.symbol)
        assertEquals(500, query.limit)
        assertEquals(
            """{"source":"spot","symbol":"BTCUSDT","interval":"1h","limit":500}""",
            query.canonicalKey,
        )
        assertThrows(IllegalArgumentException::class.java) {
            Query.normalized(MarketSource.BINANCE_SPOT, "BTC-USDT", CandleInterval.ONE_HOUR, 119)
        }
    }

    @Test
    fun `decimal strings retain trailing zero and reject non-contract lexemes`() {
        assertEquals("1.230000000000000000", DecimalString.price("1.230000000000000000").raw)
        listOf("-1", "+1", "1e2", " 1", "NaN", "Infinity").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { DecimalString.price(invalid) }
        }
        assertEquals("0", DecimalString.volume("0").raw)
        assertThrows(IllegalArgumentException::class.java) { DecimalString.price("0") }
    }
}
