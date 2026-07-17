package com.makia.jtchart.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppSettingsTest {
    @Test
    fun `symbol normalization removes invalid values and deduplicates in input order`() {
        assertEquals(
            listOf("BTCUSDT", "ETHUSDT"),
            AppSettings.normalizeSymbols(listOf(" btcusdt ", "bad-symbol", "ETHUSDT", "BTCUSDT")),
        )
        assertThrows(IllegalArgumentException::class.java) { AppSettings.normalizeSymbols(listOf("---")) }
    }

    @Test
    fun `defaults reproduce MVP settings`() {
        val settings = AppSettings()
        assertEquals(8, settings.symbols.size)
        assertEquals("BTCUSDT", settings.currentSymbol)
        assertEquals(500, settings.limit)
        assertEquals(200, settings.algorithm.bearWmaLength)
        assertEquals(0, settings.autoRefreshSeconds)
        assertEquals(true, settings.signalNotificationsEnabled)
    }
}
