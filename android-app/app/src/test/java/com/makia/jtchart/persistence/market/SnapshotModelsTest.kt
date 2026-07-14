package com.makia.jtchart.persistence.market

import com.makia.jtchart.domain.market.Candle
import com.makia.jtchart.domain.market.CandleInterval
import com.makia.jtchart.domain.market.DecimalString
import com.makia.jtchart.domain.market.MarketSource
import com.makia.jtchart.domain.market.Query
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class SnapshotModelsTest {
    @Test
    fun `canonical snapshot preserves source text nulls and fixed field order`() {
        val snapshot = CandleSnapshot(
            query = Query(MarketSource.BINANCE_SPOT, "BTCUSDT", CandleInterval.ONE_HOUR, 120),
            candles = listOf(candle(1000, "1.2300", null, "99.00")),
            fetchedAtEpochMs = 2000,
        )

        val stored = SnapshotCanonicalizer.store(snapshot, lastAccessedAtEpochMs = 3000)

        assertEquals(
            """{"v":1,"query":{"source":"spot","symbol":"BTCUSDT","interval":"1h","limit":120},"candles":[[1000,"1.2300","2.0","1.0","1.5",null,"99.00"]]}""",
            stored.canonicalJson.decodeToString(),
        )
        assertEquals(64, stored.datasetFingerprint.length)
        assertFalse(stored.datasetFingerprint.any { it !in "0123456789abcdef" })
        assertEquals(stored.datasetFingerprint, SnapshotCanonicalizer.fingerprint(stored.canonicalJson))
    }

    @Test
    fun `fingerprint changes when trailing zero or query identity changes`() {
        val first = snapshot(Query(MarketSource.BINANCE_SPOT, "BTCUSDT", CandleInterval.ONE_HOUR, 120), "1.2300")
        val changedDecimal = snapshot(first.query, "1.23")
        val changedQuery = snapshot(Query(MarketSource.BINANCE_FUTURES, "BTCUSDT", CandleInterval.ONE_HOUR, 120), "1.2300")

        val hashes = listOf(first, changedDecimal, changedQuery).map {
            SnapshotCanonicalizer.fingerprint(SnapshotCanonicalizer.encode(it))
        }
        assertEquals(3, hashes.distinct().size)
    }

    @Test
    fun `LRU enforces count then bytes with deterministic key tie break`() {
        val entries = listOf(
            LruEntry("b", 1, 10),
            LruEntry("a", 1, 10),
            LruEntry("protected", 0, 10),
            LruEntry("new", 3, 10),
        )

        assertEquals(
            listOf("a", "b"),
            SnapshotLruPolicy.keysToEvict(entries, setOf("protected", "new"), maxSnapshots = 2, maxPayloadBytes = 100),
        )
        assertEquals(
            listOf("a", "b"),
            SnapshotLruPolicy.keysToEvict(entries, setOf("protected", "new"), maxSnapshots = 10, maxPayloadBytes = 20),
        )
        assertThrows(IllegalArgumentException::class.java) {
            SnapshotLruPolicy.keysToEvict(entries, entries.map { it.queryKey }.toSet(), maxSnapshots = 2)
        }
    }

    private fun snapshot(query: Query, open: String) = CandleSnapshot(query, listOf(candle(1000, open, "1", "2")), 2000)

    private fun candle(time: Long, open: String, base: String?, quote: String?) = Candle(
        time,
        DecimalString.price(open),
        DecimalString.price("2.0"),
        DecimalString.price("1.0"),
        DecimalString.price("1.5"),
        base?.let { DecimalString.volume(it) },
        quote?.let { DecimalString.volume(it) },
    )
}
