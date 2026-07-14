package com.makia.jtchart.data.market

import com.makia.jtchart.domain.market.Candle
import com.makia.jtchart.domain.market.CandleInterval
import com.makia.jtchart.domain.market.DecimalString
import com.makia.jtchart.domain.market.MarketError
import com.makia.jtchart.domain.market.MarketResult
import com.makia.jtchart.domain.market.MarketSource
import com.makia.jtchart.domain.market.Query

data class MarketRequestSpec(
    val scheme: String = "https",
    val host: String,
    val path: String,
    val queryParameters: List<Pair<String, String>>,
) {
    val url: String
        get() = "$scheme://$host$path?" + queryParameters.joinToString("&") { (key, value) -> "$key=$value" }
}

interface MarketDataAdapter {
    val source: MarketSource
    fun request(query: Query): MarketRequestSpec
    fun parseSuccessfulEnvelope(body: String): MarketResult<List<Candle>>
}

object MarketDataAdapters {
    private val adapters: Map<MarketSource, MarketDataAdapter> = MarketSource.entries.associateWith(::adapter)

    operator fun get(source: MarketSource): MarketDataAdapter = adapters.getValue(source)

    private fun adapter(source: MarketSource): MarketDataAdapter = when (source) {
        MarketSource.BINANCE_SPOT -> BinanceAdapter(source, "data-api.binance.vision", "/api/v3/klines")
        MarketSource.BINANCE_FUTURES -> BinanceAdapter(source, "fapi.binance.com", "/fapi/v1/klines")
        MarketSource.BYBIT_SPOT, MarketSource.BYBIT_LINEAR -> BybitAdapter(source)
        MarketSource.BITGET_SPOT -> BitgetAdapter(source, isFutures = false)
        MarketSource.BITGET_FUTURES -> BitgetAdapter(source, isFutures = true)
    }
}

private abstract class BaseAdapter(final override val source: MarketSource) : MarketDataAdapter {
    protected fun safeParse(block: () -> List<Candle>): MarketResult<List<Candle>> = try {
        val candles = normalize(block())
        if (candles.isEmpty()) MarketResult.Failure(MarketError.NoData) else MarketResult.Success(candles)
    } catch (failure: IllegalArgumentException) {
        MarketResult.Failure(MarketError.Protocol(failure.message))
    } catch (failure: ArithmeticException) {
        MarketResult.Failure(MarketError.Protocol(failure.message))
    }

    private fun normalize(candles: List<Candle>): List<Candle> {
        val sorted = candles.sortedBy(Candle::openTimeMs)
        val result = ArrayList<Candle>(sorted.size)
        for (candle in sorted) {
            val previous = result.lastOrNull()
            when {
                previous == null || previous.openTimeMs != candle.openTimeMs -> result += candle
                previous == candle -> Unit
                else -> throw IllegalArgumentException("Conflicting duplicate open time")
            }
        }
        return result
    }

    protected fun JsonValue.Array.row(index: Int): JsonValue.Array =
        values.getOrNull(index) as? JsonValue.Array ?: throw IllegalArgumentException("Expected candle row")

    protected fun JsonValue.Array.string(index: Int): String = when (val value = values.getOrNull(index)) {
        is JsonValue.StringValue -> value.value
        is JsonValue.NumberValue -> value.lexeme
        else -> throw IllegalArgumentException("Expected scalar field")
    }

    protected fun candle(
        row: JsonValue.Array,
        baseVolumeIndex: Int?,
        quoteVolumeIndex: Int?,
    ): Candle = Candle(
        openTimeMs = row.string(0).toLong(),
        open = DecimalString.price(row.string(1)),
        high = DecimalString.price(row.string(2)),
        low = DecimalString.price(row.string(3)),
        close = DecimalString.price(row.string(4)),
        baseVolume = baseVolumeIndex?.let { DecimalString.volume(row.string(it)) },
        quoteVolume = quoteVolumeIndex?.let { DecimalString.volume(row.string(it)) },
    )
}

private class BinanceAdapter(
    source: MarketSource,
    private val host: String,
    private val path: String,
) : BaseAdapter(source) {
    override fun request(query: Query): MarketRequestSpec {
        require(query.source == source)
        return MarketRequestSpec(host = host, path = path, queryParameters = listOf(
            "symbol" to query.symbol,
            "interval" to query.interval.wireName,
            "limit" to query.limit.toString(),
        ))
    }

    override fun parseSuccessfulEnvelope(body: String): MarketResult<List<Candle>> = safeParse {
        val rows = StrictJson.parse(body) as? JsonValue.Array
            ?: throw IllegalArgumentException("Binance envelope must be an array")
        rows.values.indices.map { candle(rows.row(it), baseVolumeIndex = 5, quoteVolumeIndex = 7) }
    }
}

private class BybitAdapter(source: MarketSource) : BaseAdapter(source) {
    override fun request(query: Query): MarketRequestSpec {
        require(query.source == source)
        val category = if (source == MarketSource.BYBIT_SPOT) "spot" else "linear"
        return MarketRequestSpec(host = "api.bybit.com", path = "/v5/market/kline", queryParameters = listOf(
            "category" to category,
            "symbol" to query.symbol,
            "interval" to interval(query.interval),
            "limit" to query.limit.toString(),
        ))
    }

    override fun parseSuccessfulEnvelope(body: String): MarketResult<List<Candle>> {
        val root = try { StrictJson.parse(body) as? JsonValue.Object } catch (failure: IllegalArgumentException) {
            return MarketResult.Failure(MarketError.Protocol(failure.message))
        } ?: return MarketResult.Failure(MarketError.Protocol("Bybit envelope must be an object"))
        val retCode = (root.values["retCode"] as? JsonValue.NumberValue)?.lexeme?.toLongOrNull()
            ?: return MarketResult.Failure(MarketError.Protocol("Missing Bybit retCode"))
        if (retCode != 0L) {
            return if (retCode == 10006L) MarketResult.Failure(MarketError.RateLimited())
            else MarketResult.Failure(MarketError.RequestRejected)
        }
        return safeParse {
            val result = root.values["result"] as? JsonValue.Object
                ?: throw IllegalArgumentException("Missing Bybit result")
            val rows = result.values["list"] as? JsonValue.Array
                ?: throw IllegalArgumentException("Missing Bybit list")
            rows.values.indices.map { candle(rows.row(it), baseVolumeIndex = 5, quoteVolumeIndex = 6) }
        }
    }

    private fun interval(value: CandleInterval): String = when (value) {
        CandleInterval.ONE_MINUTE -> "1"
        CandleInterval.FIVE_MINUTES -> "5"
        CandleInterval.FIFTEEN_MINUTES -> "15"
        CandleInterval.ONE_HOUR -> "60"
        CandleInterval.FOUR_HOURS -> "240"
        CandleInterval.ONE_DAY -> "D"
        CandleInterval.ONE_WEEK -> "W"
    }
}

private class BitgetAdapter(source: MarketSource, private val isFutures: Boolean) : BaseAdapter(source) {
    override fun request(query: Query): MarketRequestSpec {
        require(query.source == source)
        val fixed = if (isFutures) listOf("productType" to "USDT-FUTURES", "kLineType" to "MARKET") else emptyList()
        return MarketRequestSpec(
            host = "api.bitget.com",
            path = if (isFutures) "/api/v2/mix/market/candles" else "/api/v2/spot/market/candles",
            queryParameters = listOf("symbol" to query.symbol) + fixed + listOf(
                "granularity" to interval(query.interval),
                "limit" to query.limit.toString(),
            ),
        )
    }

    override fun parseSuccessfulEnvelope(body: String): MarketResult<List<Candle>> {
        val root = try { StrictJson.parse(body) as? JsonValue.Object } catch (failure: IllegalArgumentException) {
            return MarketResult.Failure(MarketError.Protocol(failure.message))
        } ?: return MarketResult.Failure(MarketError.Protocol("Bitget envelope must be an object"))
        val code = (root.values["code"] as? JsonValue.StringValue)?.value
            ?: return MarketResult.Failure(MarketError.Protocol("Missing Bitget code"))
        if (code != "00000") return MarketResult.Failure(MarketError.RequestRejected)
        return safeParse {
            val rows = root.values["data"] as? JsonValue.Array
                ?: throw IllegalArgumentException("Missing Bitget data")
            rows.values.indices.map {
                candle(rows.row(it), baseVolumeIndex = 5, quoteVolumeIndex = if (isFutures) 6 else 7)
            }
        }
    }

    private fun interval(value: CandleInterval): String = if (isFutures) when (value) {
        CandleInterval.ONE_MINUTE -> "1m"
        CandleInterval.FIVE_MINUTES -> "5m"
        CandleInterval.FIFTEEN_MINUTES -> "15m"
        CandleInterval.ONE_HOUR -> "1H"
        CandleInterval.FOUR_HOURS -> "4H"
        CandleInterval.ONE_DAY -> "1D"
        CandleInterval.ONE_WEEK -> "1W"
    } else when (value) {
        CandleInterval.ONE_MINUTE -> "1min"
        CandleInterval.FIVE_MINUTES -> "5min"
        CandleInterval.FIFTEEN_MINUTES -> "15min"
        CandleInterval.ONE_HOUR -> "1h"
        CandleInterval.FOUR_HOURS -> "4h"
        CandleInterval.ONE_DAY -> "1day"
        CandleInterval.ONE_WEEK -> "1week"
    }
}

object HttpErrorClassifier {
    fun classify(status: Int, source: MarketSource, bybitFrequentAccess: Boolean = false): MarketError = when {
        status == 408 -> MarketError.UpstreamUnavailable
        status == 418 -> MarketError.RateLimited()
        status == 429 -> MarketError.RateLimited()
        status >= 500 -> MarketError.UpstreamUnavailable
        status == 401 -> MarketError.AccessRestricted
        status == 403 && source in setOf(MarketSource.BYBIT_SPOT, MarketSource.BYBIT_LINEAR) && bybitFrequentAccess -> MarketError.RateLimited()
        status == 403 -> MarketError.AccessRestricted
        status in 400..499 -> MarketError.RequestRejected
        else -> MarketError.Protocol("Unexpected HTTP status")
    }

    fun canRetry(status: Int): Boolean = status == 408 || status == 429 || status >= 500
}
