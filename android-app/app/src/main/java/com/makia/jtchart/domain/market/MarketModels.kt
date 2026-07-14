package com.makia.jtchart.domain.market

import java.math.BigDecimal

enum class MarketSource(val wireName: String) {
    BINANCE_SPOT("spot"),
    BINANCE_FUTURES("futures"),
    BYBIT_SPOT("bybitSpot"),
    BYBIT_LINEAR("bybitLinear"),
    BITGET_SPOT("bitgetSpot"),
    BITGET_FUTURES("bitgetFutures");

    companion object {
        fun fromWireName(value: String): MarketSource = entries.firstOrNull { it.wireName == value }
            ?: throw IllegalArgumentException("Unsupported source: $value")
    }
}

enum class CandleInterval(val wireName: String) {
    ONE_MINUTE("1m"),
    FIVE_MINUTES("5m"),
    FIFTEEN_MINUTES("15m"),
    ONE_HOUR("1h"),
    FOUR_HOURS("4h"),
    ONE_DAY("1d"),
    ONE_WEEK("1w");

    companion object {
        fun fromWireName(value: String): CandleInterval = entries.firstOrNull { it.wireName == value }
            ?: throw IllegalArgumentException("Unsupported interval: $value")
    }
}

data class Query(
    val source: MarketSource,
    val symbol: String,
    val interval: CandleInterval,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(SYMBOL.matches(symbol)) { "symbol must be normalized ASCII letters/digits" }
        require(symbol.length in 3..30) { "symbol length must be 3..30" }
        require(limit in MIN_LIMIT..MAX_LIMIT) { "limit must be $MIN_LIMIT..$MAX_LIMIT" }
    }

    val canonicalKey: String
        get() = """{"source":"${source.wireName}","symbol":"$symbol","interval":"${interval.wireName}","limit":$limit}"""

    companion object {
        const val DEFAULT_LIMIT = 500
        const val MIN_LIMIT = 120
        const val MAX_LIMIT = 1000
        private val SYMBOL = Regex("^[A-Z0-9]+$")

        fun normalized(
            source: MarketSource,
            symbol: String,
            interval: CandleInterval,
            limit: Int = DEFAULT_LIMIT,
        ): Query = Query(source, symbol.trim().uppercase(), interval, limit)
    }
}

@JvmInline
value class DecimalString private constructor(val raw: String) {
    fun toBigDecimal(): BigDecimal = BigDecimal(raw)
    override fun toString(): String = raw

    companion object {
        const val MAX_LENGTH = 128
        private val LEXEME = Regex("^[0-9]+(?:\\.[0-9]+)?$")

        fun price(raw: String): DecimalString = validated(raw, requirePositive = true)
        fun volume(raw: String): DecimalString = validated(raw, requirePositive = false)

        private fun validated(raw: String, requirePositive: Boolean): DecimalString {
            require(raw.length <= MAX_LENGTH && LEXEME.matches(raw)) { "Invalid decimal string" }
            val parsed = try {
                BigDecimal(raw)
            } catch (_: NumberFormatException) {
                throw IllegalArgumentException("Invalid decimal string")
            }
            require(!requirePositive || parsed.signum() > 0) { "Price must be positive" }
            return DecimalString(raw)
        }
    }
}

data class Candle(
    val openTimeMs: Long,
    val open: DecimalString,
    val high: DecimalString,
    val low: DecimalString,
    val close: DecimalString,
    val baseVolume: DecimalString?,
    val quoteVolume: DecimalString?,
) {
    init {
        require(openTimeMs >= 0) { "openTimeMs must be non-negative" }
        val o = open.toBigDecimal()
        val h = high.toBigDecimal()
        val l = low.toBigDecimal()
        val c = close.toBigDecimal()
        require(h >= o && h >= c && h >= l && l <= o && l <= c) { "Contradictory OHLC values" }
    }
}

sealed interface MarketError {
    data object Cancelled : MarketError
    data object Connectivity : MarketError
    data object Timeout : MarketError
    data class RateLimited(val retryAtEpochMs: Long? = null) : MarketError
    data object RequestRejected : MarketError
    data object AccessRestricted : MarketError
    data object UpstreamUnavailable : MarketError
    data class Protocol(val diagnostic: String? = null) : MarketError
    data object NoData : MarketError
}

sealed interface MarketResult<out T> {
    data class Success<T>(val value: T) : MarketResult<T>
    data class Failure(val error: MarketError) : MarketResult<Nothing>
}

interface MarketRepository {
    suspend fun fetch(query: Query): MarketResult<List<Candle>>
}
