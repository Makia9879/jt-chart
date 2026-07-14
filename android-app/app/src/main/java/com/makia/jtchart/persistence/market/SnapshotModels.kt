package com.makia.jtchart.persistence.market

import com.makia.jtchart.data.market.JsonValue
import com.makia.jtchart.data.market.StrictJson
import com.makia.jtchart.domain.market.Candle
import com.makia.jtchart.domain.market.CandleInterval
import com.makia.jtchart.domain.market.DecimalString
import com.makia.jtchart.domain.market.MarketSource
import com.makia.jtchart.domain.market.Query
import java.security.MessageDigest

data class CandleSnapshot(
    val query: Query,
    val candles: List<Candle>,
    val fetchedAtEpochMs: Long,
) {
    init {
        require(candles.isNotEmpty())
        require(candles.zipWithNext().all { (left, right) -> left.openTimeMs < right.openTimeMs }) {
            "Snapshot candles must be strictly ascending"
        }
        require(fetchedAtEpochMs >= 0)
    }
}

data class StoredSnapshot(
    val snapshot: CandleSnapshot,
    val lastAccessedAtEpochMs: Long,
    val canonicalJson: ByteArray,
    val datasetFingerprint: String,
) {
    val payloadBytes: Long get() = canonicalJson.size.toLong()
}

data class StoredViewport(
    val queryKey: String,
    val datasetFingerprint: String,
    val logicalFrom: Double,
    val logicalTo: Double,
    val updatedAtEpochMs: Long,
) {
    init {
        require(logicalFrom.isFinite() && logicalTo.isFinite() && logicalFrom < logicalTo)
    }
}

object SnapshotCanonicalizer {
    const val SCHEMA_VERSION = 1

    fun encode(snapshot: CandleSnapshot): ByteArray {
        val query = snapshot.query
        return buildString {
            append("{\"v\":1,\"query\":{\"source\":\"")
            append(query.source.wireName)
            append("\",\"symbol\":\"")
            append(query.symbol)
            append("\",\"interval\":\"")
            append(query.interval.wireName)
            append("\",\"limit\":")
            append(query.limit)
            append("},\"candles\":[")
            snapshot.candles.forEachIndexed { index, candle ->
                if (index > 0) append(',')
                append('[')
                append(candle.openTimeMs)
                append(",\"").append(candle.open.raw).append('"')
                append(",\"").append(candle.high.raw).append('"')
                append(",\"").append(candle.low.raw).append('"')
                append(",\"").append(candle.close.raw).append('"')
                append(',').appendNullable(candle.baseVolume?.raw)
                append(',').appendNullable(candle.quoteVolume?.raw)
                append(']')
            }
            append("]}")
        }.encodeToByteArray()
    }

    fun fingerprint(canonicalJson: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(canonicalJson)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    fun store(snapshot: CandleSnapshot, lastAccessedAtEpochMs: Long): StoredSnapshot {
        val bytes = encode(snapshot)
        return StoredSnapshot(snapshot, lastAccessedAtEpochMs, bytes, fingerprint(bytes))
    }

    fun decode(canonicalJson: ByteArray, fetchedAtEpochMs: Long, lastAccessedAtEpochMs: Long): StoredSnapshot {
        val root = StrictJson.parse(canonicalJson.decodeToString()) as? JsonValue.Object
            ?: throw IllegalArgumentException("Snapshot must be an object")
        require(root.long("v") == SCHEMA_VERSION.toLong()) { "Unsupported snapshot version" }
        val queryObject = root.values["query"] as? JsonValue.Object
            ?: throw IllegalArgumentException("Missing snapshot query")
        val query = Query(
            source = MarketSource.fromWireName(queryObject.string("source")),
            symbol = queryObject.string("symbol"),
            interval = CandleInterval.fromWireName(queryObject.string("interval")),
            limit = queryObject.long("limit").toInt(),
        )
        val rows = root.values["candles"] as? JsonValue.Array
            ?: throw IllegalArgumentException("Missing snapshot candles")
        val candles = rows.values.map { value ->
            val row = value as? JsonValue.Array ?: throw IllegalArgumentException("Invalid snapshot candle")
            require(row.values.size == 7) { "Invalid snapshot candle width" }
            Candle(
                openTimeMs = row.long(0),
                open = DecimalString.price(row.string(1)),
                high = DecimalString.price(row.string(2)),
                low = DecimalString.price(row.string(3)),
                close = DecimalString.price(row.string(4)),
                baseVolume = row.nullableString(5)?.let { DecimalString.volume(it) },
                quoteVolume = row.nullableString(6)?.let { DecimalString.volume(it) },
            )
        }
        val snapshot = CandleSnapshot(query, candles, fetchedAtEpochMs)
        return StoredSnapshot(snapshot, lastAccessedAtEpochMs, canonicalJson, fingerprint(canonicalJson))
    }

    private fun StringBuilder.appendNullable(value: String?) {
        if (value == null) append("null") else append('"').append(value).append('"')
    }

    private fun JsonValue.Object.string(key: String): String =
        (values[key] as? JsonValue.StringValue)?.value ?: throw IllegalArgumentException("Missing string field")

    private fun JsonValue.Object.long(key: String): Long =
        (values[key] as? JsonValue.NumberValue)?.lexeme?.toLongOrNull()
            ?: throw IllegalArgumentException("Missing integer field")

    private fun JsonValue.Array.string(index: Int): String =
        (values.getOrNull(index) as? JsonValue.StringValue)?.value
            ?: throw IllegalArgumentException("Missing candle decimal")

    private fun JsonValue.Array.nullableString(index: Int): String? = when (val value = values.getOrNull(index)) {
        JsonValue.Null -> null
        is JsonValue.StringValue -> value.value
        else -> throw IllegalArgumentException("Invalid optional decimal")
    }

    private fun JsonValue.Array.long(index: Int): Long =
        (values.getOrNull(index) as? JsonValue.NumberValue)?.lexeme?.toLongOrNull()
            ?: throw IllegalArgumentException("Missing candle time")
}

interface SnapshotCache {
    suspend fun get(query: Query, accessedAtEpochMs: Long): StoredSnapshot?

    /** Replaces one successful snapshot atomically, then enforces both capacity limits. */
    suspend fun put(
        snapshot: CandleSnapshot,
        accessedAtEpochMs: Long,
        protectedQueryKeys: Set<String>,
    ): StoredSnapshot

    suspend fun saveViewport(viewport: StoredViewport)
    suspend fun getViewport(queryKey: String, datasetFingerprint: String): StoredViewport?
}

data class LruEntry(
    val queryKey: String,
    val lastAccessedAtEpochMs: Long,
    val payloadBytes: Long,
)

object SnapshotLruPolicy {
    const val MAX_SNAPSHOTS = 64
    const val MAX_PAYLOAD_BYTES = 32L * 1024L * 1024L

    fun keysToEvict(
        entries: Collection<LruEntry>,
        protectedQueryKeys: Set<String>,
        maxSnapshots: Int = MAX_SNAPSHOTS,
        maxPayloadBytes: Long = MAX_PAYLOAD_BYTES,
    ): List<String> {
        var remainingCount = entries.size
        var remainingBytes = entries.sumOf(LruEntry::payloadBytes)
        if (remainingCount <= maxSnapshots && remainingBytes <= maxPayloadBytes) return emptyList()

        val evicted = mutableListOf<String>()
        entries.asSequence()
            .filterNot { it.queryKey in protectedQueryKeys }
            .sortedWith(compareBy<LruEntry> { it.lastAccessedAtEpochMs }.thenBy { it.queryKey })
            .forEach { entry ->
                if (remainingCount <= maxSnapshots && remainingBytes <= maxPayloadBytes) return@forEach
                evicted += entry.queryKey
                remainingCount--
                remainingBytes -= entry.payloadBytes
            }
        require(remainingCount <= maxSnapshots && remainingBytes <= maxPayloadBytes) {
            "Cache limits cannot be satisfied without evicting protected snapshots"
        }
        return evicted
    }
}
