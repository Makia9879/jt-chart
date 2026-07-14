package com.makia.jtchart.data.market

import com.makia.jtchart.domain.market.Candle
import com.makia.jtchart.domain.market.MarketError
import com.makia.jtchart.domain.market.MarketRepository
import com.makia.jtchart.domain.market.MarketResult
import com.makia.jtchart.domain.market.Query
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.random.Random
import kotlinx.coroutines.suspendCancellableCoroutine

data class TransportResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String>,
)

sealed interface TransportResult {
    data class Response(val value: TransportResponse) : TransportResult
    data class Failure(val error: MarketError) : TransportResult
}

fun interface MarketTransport {
    suspend fun execute(request: MarketRequestSpec): TransportResult
}

class MarketRepositoryImpl(
    private val transport: MarketTransport,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val nowNanos: () -> Long = System::nanoTime,
    private val jitterMillis: () -> Long = { Random.nextLong(0, 251) },
) : MarketRepository {
    private val hostGates = ConcurrentHashMap<String, HostStartGate>()
    private val hostBlockedUntilEpochMs = ConcurrentHashMap<String, Long>()

    override suspend fun fetch(query: Query): MarketResult<List<Candle>> {
        val adapter = MarketDataAdapters[query.source]
        val request = adapter.request(query)
        hostBlockedUntilEpochMs[request.host]?.let { retryAt ->
            if (nowEpochMs() < retryAt) return MarketResult.Failure(MarketError.RateLimited(retryAt))
            hostBlockedUntilEpochMs.remove(request.host, retryAt)
        }
        var attempt = 0
        while (true) {
            try {
                hostGates.getOrPut(request.host) { HostStartGate(nowNanos, delayMillis) }.awaitTurn()
                when (val result = transport.execute(request)) {
                    is TransportResult.Failure -> {
                        if (attempt == 0 && result.error.isRetryableTransportFailure()) {
                            attempt++
                            delayMillis(1_000L + jitterMillis())
                            continue
                        }
                        return MarketResult.Failure(result.error)
                    }
                    is TransportResult.Response -> {
                        val response = result.value
                        if (response.status in 200..299) return adapter.parseSuccessfulEnvelope(response.body)
                        val error = HttpErrorClassifier.classify(
                            response.status,
                            query.source,
                            bybitFrequentAccess = response.status == 403 && response.body.contains("access too frequent", ignoreCase = true),
                        )
                        if (response.status == 403 && error is MarketError.RateLimited) {
                            val retryAt = nowEpochMs() + BYBIT_FREQUENT_ACCESS_BLOCK_MS
                            hostBlockedUntilEpochMs[request.host] = retryAt
                            return MarketResult.Failure(MarketError.RateLimited(retryAt))
                        }
                        if (attempt == 0 && HttpErrorClassifier.canRetry(response.status) && response.status != 418) {
                            attempt++
                            delayMillis(retryDelayMillis(response))
                            continue
                        }
                        return MarketResult.Failure(error.withRetryAt(response))
                    }
                }
            } catch (_: CancellationException) {
                return MarketResult.Failure(MarketError.Cancelled)
            }
        }
    }

    private fun retryDelayMillis(response: TransportResponse): Long {
        val retryAfterSeconds = response.headers.entries.firstOrNull { it.key.equals("Retry-After", true) }
            ?.value?.trim()?.toLongOrNull()
        val serverDelay = retryAfterSeconds?.times(1_000L)
        val floor = if (response.status == 429) 5_000L else 1_000L
        return maxOf(serverDelay ?: 0L, floor) + jitterMillis()
    }

    private fun MarketError.withRetryAt(response: TransportResponse): MarketError {
        if (this !is MarketError.RateLimited) return this
        val retryAfterSeconds = response.headers.entries.firstOrNull { it.key.equals("Retry-After", true) }
            ?.value?.trim()?.toLongOrNull()
        return if (retryAfterSeconds == null) this else MarketError.RateLimited(nowEpochMs() + retryAfterSeconds * 1_000L)
    }

    private fun MarketError.isRetryableTransportFailure(): Boolean =
        this == MarketError.Connectivity || this == MarketError.Timeout

    private class HostStartGate(
        private val nowNanos: () -> Long,
        private val delayMillis: suspend (Long) -> Unit,
    ) {
        private val mutex = Mutex()
        private var lastStartedNanos: Long? = null

        suspend fun awaitTurn() = mutex.withLock {
            val last = lastStartedNanos
            if (last != null) {
                val elapsedMs = (nowNanos() - last) / 1_000_000L
                if (elapsedMs < MIN_HOST_START_INTERVAL_MS) delayMillis(MIN_HOST_START_INTERVAL_MS - elapsedMs)
            }
            lastStartedNanos = nowNanos()
        }
    }

    private companion object {
        const val MIN_HOST_START_INTERVAL_MS = 250L
        const val BYBIT_FREQUENT_ACCESS_BLOCK_MS = 10L * 60L * 1_000L
    }
}

class OkHttpMarketTransport(private val client: OkHttpClient) : MarketTransport {
    override suspend fun execute(request: MarketRequestSpec): TransportResult = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(request.url).get().build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isActive) return
                continuation.resume(TransportResult.Failure(
                    if (e is SocketTimeoutException) MarketError.Timeout else MarketError.Connectivity,
                ))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!continuation.isActive) return
                    val headers = response.headers.names().associateWith { name -> response.header(name).orEmpty() }
                    continuation.resume(TransportResult.Response(TransportResponse(
                        status = response.code,
                        body = response.body.string(),
                        headers = headers,
                    )))
                }
            }
        })
    }
}

object MarketDataFactory {
    fun createRepository(client: OkHttpClient = createClient()): MarketRepository =
        MarketRepositoryImpl(OkHttpMarketTransport(client))

    fun createClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
}
