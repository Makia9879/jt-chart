package com.makia.jtchart.persistence.settings

import com.makia.jtchart.domain.market.CandleInterval
import com.makia.jtchart.domain.market.MarketSource
import com.makia.jtchart.domain.settings.AlgorithmSettings
import com.makia.jtchart.domain.settings.AppSettings
import com.makia.jtchart.persistence.settings.proto.AppSettingsProto
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
            signalNotificationsEnabled = false,
        )

        assertEquals(settings, settings.toProto().toDomain())
    }

    @Test
    fun `old proto without notification field keeps default notifications enabled`() {
        val oldProto = AppSettingsProto.newBuilder()
            .setSchemaVersion(1)
            .addAllSymbols(AppSettings.DEFAULT_SYMBOLS)
            .setCurrentSymbol("BTCUSDT")
            .setSource(MarketSource.BINANCE_SPOT.wireName)
            .setInterval(CandleInterval.ONE_HOUR.wireName)
            .setLimit(500)
            .setMomStart(2)
            .setMomEnd(52)
            .setZLength(52)
            .setExtremeThreshold(2.0)
            .setSmoothLength(8)
            .setBearWmaLength(200)
            .setAutoRefreshSeconds(0)
            .build()

        assertEquals(true, oldProto.toDomain().signalNotificationsEnabled)
    }

    @Test
    fun `serializer default is a complete valid MVP configuration`() {
        assertEquals(AppSettings(), AppSettingsSerializer.defaultValue.toDomain())
    }
}
