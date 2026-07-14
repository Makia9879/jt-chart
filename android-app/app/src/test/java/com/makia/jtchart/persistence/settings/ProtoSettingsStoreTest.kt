package com.makia.jtchart.persistence.settings

import com.makia.jtchart.domain.market.CandleInterval
import com.makia.jtchart.domain.market.MarketSource
import com.makia.jtchart.domain.settings.AlgorithmSettings
import com.makia.jtchart.domain.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtoSettingsStoreTest {
    @Test
    fun `proto v1 round trip preserves all applied settings and symbol order`() {
        val settings = AppSettings(
            symbols = listOf("ETHUSDT", "BTCUSDT"),
            currentSymbol = "ETHUSDT",
            source = MarketSource.BYBIT_LINEAR,
            interval = CandleInterval.FOUR_HOURS,
            limit = 860,
            algorithm = AlgorithmSettings(3, 60, 80, 2.4, 9, 300),
            autoRefreshSeconds = 30,
        )

        assertEquals(settings, settings.toProto().toDomain())
    }

    @Test
    fun `serializer default is a complete valid MVP configuration`() {
        assertEquals(AppSettings(), AppSettingsSerializer.defaultValue.toDomain())
    }
}
