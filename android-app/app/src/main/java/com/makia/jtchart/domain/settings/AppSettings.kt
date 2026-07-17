package com.makia.jtchart.domain.settings

import com.makia.jtchart.domain.market.CandleInterval
import com.makia.jtchart.domain.market.MarketSource
import com.makia.jtchart.domain.market.Query

data class AlgorithmSettings(
    val momStart: Int = 2,
    val momEnd: Int = 52,
    val zLength: Int = 52,
    val extremeThreshold: Double = 2.0,
    val smoothLength: Int = 8,
    val bearWmaLength: Int = 200,
) {
    init {
        require(momStart >= 2)
        require(momEnd >= 3 && momEnd > momStart)
        require(zLength >= 10)
        require(extremeThreshold in 0.5..5.0)
        require(smoothLength >= 1)
        require(bearWmaLength in 10..990)
    }
}

data class AppSettings(
    val schemaVersion: Int = 1,
    val symbols: List<String> = DEFAULT_SYMBOLS,
    val currentSymbol: String = "BTCUSDT",
    val source: MarketSource = MarketSource.BINANCE_SPOT,
    val interval: CandleInterval = CandleInterval.ONE_HOUR,
    val limit: Int = Query.DEFAULT_LIMIT,
    val algorithm: AlgorithmSettings = AlgorithmSettings(),
    val autoRefreshSeconds: Int = 0,
    val signalNotificationsEnabled: Boolean = true,
) {
    init {
        require(schemaVersion >= 1)
        require(symbols.isNotEmpty() && symbols.distinct().size == symbols.size)
        symbols.forEach { Query(source, it, interval, limit) }
        require(currentSymbol in symbols)
        require(autoRefreshSeconds in AUTO_REFRESH_OPTIONS)
    }

    fun query(): Query = Query(source, currentSymbol, interval, limit)

    companion object {
        val DEFAULT_SYMBOLS = listOf(
            "BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT",
            "XRPUSDT", "DOGEUSDT", "PENDLEUSDT", "MONUSDT",
        )
        val AUTO_REFRESH_OPTIONS = setOf(0, 15, 30, 60, 300)

        fun normalizeSymbols(raw: List<String>): List<String> = raw
            .asSequence()
            .map { it.trim().uppercase() }
            .filter { it.matches(Regex("^[A-Z0-9]{3,30}$")) }
            .distinct()
            .toList()
            .also { require(it.isNotEmpty()) { "At least one valid symbol is required" } }
    }
}

interface SettingsStore {
    suspend fun load(): AppSettings
    suspend fun save(settings: AppSettings)
}
