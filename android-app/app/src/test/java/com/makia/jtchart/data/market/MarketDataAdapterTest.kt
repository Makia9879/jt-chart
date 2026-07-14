package com.makia.jtchart.data.market

import com.makia.jtchart.domain.market.CandleInterval
import com.makia.jtchart.domain.market.MarketError
import com.makia.jtchart.domain.market.MarketResult
import com.makia.jtchart.domain.market.MarketSource
import com.makia.jtchart.domain.market.Query
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketDataAdapterTest {
    @Test
    fun `all source interval mappings produce fixed official requests`() {
        val expected = mapOf(
            MarketSource.BINANCE_SPOT to listOf("1m", "5m", "15m", "1h", "4h", "1d", "1w"),
            MarketSource.BINANCE_FUTURES to listOf("1m", "5m", "15m", "1h", "4h", "1d", "1w"),
            MarketSource.BYBIT_SPOT to listOf("1", "5", "15", "60", "240", "D", "W"),
            MarketSource.BYBIT_LINEAR to listOf("1", "5", "15", "60", "240", "D", "W"),
            MarketSource.BITGET_SPOT to listOf("1min", "5min", "15min", "1h", "4h", "1day", "1week"),
            MarketSource.BITGET_FUTURES to listOf("1m", "5m", "15m", "1H", "4H", "1D", "1W"),
        )

        for ((source, intervals) in expected) {
            CandleInterval.entries.zip(intervals).forEach { (interval, expectedInterval) ->
                val request = MarketDataAdapters[source].request(Query(source, "BTCUSDT", interval, 120))
                val parameterName = if (source in setOf(MarketSource.BITGET_SPOT, MarketSource.BITGET_FUTURES)) "granularity" else "interval"
                assertEquals(expectedInterval, request.queryParameters.toMap()[parameterName])
                assertEquals("BTCUSDT", request.queryParameters.toMap()["symbol"])
                assertEquals("120", request.queryParameters.toMap()["limit"])
                assertEquals("https", request.scheme)
            }
        }

        val futures = MarketDataAdapters[MarketSource.BITGET_FUTURES]
            .request(Query(MarketSource.BITGET_FUTURES, "BTCUSDT", CandleInterval.ONE_HOUR, 120))
        assertEquals("USDT-FUTURES", futures.queryParameters.toMap()["productType"])
        assertEquals("MARKET", futures.queryParameters.toMap()["kLineType"])
    }

    @Test
    fun `binance parser preserves decimal strings and sorts rows`() {
        val body = """[
          [2000,"2.000000000000000000","3.0","1.0","2.50","10.00",0,"20.00"],
          [1000,"1.230000000000000000","2.0","1.0","1.50","9.00",0,"18.00"]
        ]"""

        val result = MarketDataAdapters[MarketSource.BINANCE_SPOT].parseSuccessfulEnvelope(body)

        result as MarketResult.Success
        assertEquals(listOf(1000L, 2000L), result.value.map { it.openTimeMs })
        assertEquals("1.230000000000000000", result.value.first().open.raw)
        assertEquals("18.00", result.value.first().quoteVolume?.raw)
    }

    @Test
    fun `bybit and bitget normalize official envelopes`() {
        val bybit = MarketDataAdapters[MarketSource.BYBIT_LINEAR].parseSuccessfulEnvelope(
            """{"retCode":0,"result":{"list":[["2000","2","3","1","2.5","10","20"],["1000","1","2","0.5","1.5","9","18"]]}}""",
        ) as MarketResult.Success
        assertEquals(listOf(1000L, 2000L), bybit.value.map { it.openTimeMs })

        val bitget = MarketDataAdapters[MarketSource.BITGET_SPOT].parseSuccessfulEnvelope(
            """{"code":"00000","data":[["2000","2","3","1","2.5","10","ignored","20"],["1000","1","2","0.5","1.5","9","ignored","18"]]}""",
        ) as MarketResult.Success
        assertEquals(listOf(1000L, 2000L), bitget.value.map { it.openTimeMs })
        assertEquals("20", bitget.value.last().quoteVolume?.raw)
    }

    @Test
    fun `duplicate timestamps fold only when complete rows are identical`() {
        val identical = """[[1000,"1","2","0.5","1.5","9",0,"18"],[1000,"1","2","0.5","1.5","9",0,"18"]]"""
        val folded = MarketDataAdapters[MarketSource.BINANCE_SPOT].parseSuccessfulEnvelope(identical) as MarketResult.Success
        assertEquals(1, folded.value.size)

        val conflicting = """[[1000,"1","2","0.5","1.5","9",0,"18"],[1000,"1","2","0.5","1.6","9",0,"18"]]"""
        val rejected = MarketDataAdapters[MarketSource.BINANCE_SPOT].parseSuccessfulEnvelope(conflicting) as MarketResult.Failure
        assertTrue(rejected.error is MarketError.Protocol)
    }

    @Test
    fun `bad rows reject the complete batch and empty success is NoData`() {
        val contradictory = """[[1000,"2","1","0.5","1.5","9",0,"18"]]"""
        val rejected = MarketDataAdapters[MarketSource.BINANCE_SPOT].parseSuccessfulEnvelope(contradictory) as MarketResult.Failure
        assertTrue(rejected.error is MarketError.Protocol)

        val empty = MarketDataAdapters[MarketSource.BINANCE_SPOT].parseSuccessfulEnvelope("[]") as MarketResult.Failure
        assertEquals(MarketError.NoData, empty.error)
    }

    @Test
    fun `vendor envelope failures are stable errors`() {
        val bybitRateLimit = MarketDataAdapters[MarketSource.BYBIT_SPOT]
            .parseSuccessfulEnvelope("""{"retCode":10006,"result":{"list":[]}}""") as MarketResult.Failure
        assertTrue(bybitRateLimit.error is MarketError.RateLimited)

        val bitgetRejected = MarketDataAdapters[MarketSource.BITGET_FUTURES]
            .parseSuccessfulEnvelope("""{"code":"40017","data":[]}""") as MarketResult.Failure
        assertEquals(MarketError.RequestRejected, bitgetRejected.error)
    }
}
