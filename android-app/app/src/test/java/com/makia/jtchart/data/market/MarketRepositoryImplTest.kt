package com.makia.jtchart.data.market

import com.makia.jtchart.domain.market.CandleInterval
import com.makia.jtchart.domain.market.MarketError
import com.makia.jtchart.domain.market.MarketResult
import com.makia.jtchart.domain.market.MarketSource
import com.makia.jtchart.domain.market.Query
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketRepositoryImplTest {
    @Test
    fun `connectivity failure retries once then parses success`() = runBlocking {
        val responses = ArrayDeque<TransportResult>().apply {
            add(TransportResult.Failure(MarketError.Connectivity))
            add(TransportResult.Response(TransportResponse(200, validBinanceBody(), emptyMap())))
        }
        val delays = mutableListOf<Long>()
        val repository = MarketRepositoryImpl(
            transport = MarketTransport { responses.removeFirst() },
            delayMillis = { delays += it },
            nowNanos = increasingNanos(),
            jitterMillis = { 0 },
        )

        val result = repository.fetch(query()) as MarketResult.Success

        assertEquals(1, result.value.size)
        assertEquals(listOf(1_000L), delays)
    }

    @Test
    fun `429 retries once with five second floor and reports retryAt after second failure`() = runBlocking {
        val responses = ArrayDeque<TransportResult>().apply {
            repeat(2) { add(TransportResult.Response(TransportResponse(429, "", mapOf("Retry-After" to "7")))) }
        }
        val delays = mutableListOf<Long>()
        val repository = MarketRepositoryImpl(
            transport = MarketTransport { responses.removeFirst() },
            delayMillis = { delays += it },
            nowEpochMs = { 10_000L },
            nowNanos = increasingNanos(),
            jitterMillis = { 0 },
        )

        val result = repository.fetch(query()) as MarketResult.Failure

        assertEquals(MarketError.RateLimited(17_000L), result.error)
        assertEquals(listOf(7_000L), delays)
    }

    @Test
    fun `ordinary rejection and protocol failures never retry`() = runBlocking {
        var calls = 0
        val rejected = MarketRepositoryImpl(
            transport = MarketTransport {
                calls++
                TransportResult.Response(TransportResponse(400, "", emptyMap()))
            },
            delayMillis = {},
            nowNanos = increasingNanos(),
        ).fetch(query()) as MarketResult.Failure
        assertEquals(MarketError.RequestRejected, rejected.error)
        assertEquals(1, calls)

        val protocol = MarketRepositoryImpl(
            transport = MarketTransport {
                calls++
                TransportResult.Response(TransportResponse(200, "{}", emptyMap()))
            },
            delayMillis = {},
            nowNanos = increasingNanos(),
        ).fetch(query()) as MarketResult.Failure
        assertTrue(protocol.error is MarketError.Protocol)
        assertEquals(2, calls)
    }

    @Test
    fun `bybit frequent access 403 blocks that host for ten minutes`() = runBlocking {
        var calls = 0
        var now = 50_000L
        val repository = MarketRepositoryImpl(
            transport = MarketTransport {
                calls++
                TransportResult.Response(TransportResponse(403, "Access too frequent", emptyMap()))
            },
            delayMillis = {},
            nowEpochMs = { now },
            nowNanos = increasingNanos(),
        )
        val query = Query(MarketSource.BYBIT_SPOT, "BTCUSDT", CandleInterval.ONE_HOUR, 120)

        val first = repository.fetch(query) as MarketResult.Failure
        val blocked = repository.fetch(query) as MarketResult.Failure

        assertEquals(MarketError.RateLimited(650_000L), first.error)
        assertEquals(first.error, blocked.error)
        assertEquals(1, calls)

        now = 650_000L
        repository.fetch(query)
        assertEquals(2, calls)
    }

    @Test
    fun `requests contain no authentication or cookie headers`() = runBlocking {
        var captured: MarketRequestSpec? = null
        val repository = MarketRepositoryImpl(
            transport = MarketTransport {
                captured = it
                TransportResult.Response(TransportResponse(200, validBinanceBody(), emptyMap()))
            },
            delayMillis = {},
            nowNanos = increasingNanos(),
        )

        repository.fetch(query())

        assertTrue(captured!!.url.startsWith("https://data-api.binance.vision/api/v3/klines?"))
        assertEquals(setOf("symbol", "interval", "limit"), captured!!.queryParameters.map { it.first }.toSet())
    }

    private fun query() = Query(MarketSource.BINANCE_SPOT, "BTCUSDT", CandleInterval.ONE_HOUR, 120)
    private fun validBinanceBody() = """[[1000,"1","2","0.5","1.5","9",0,"18"]]"""
    private fun increasingNanos(): () -> Long {
        var value = 0L
        return { value.also { value += 1_000_000_000L } }
    }
}
