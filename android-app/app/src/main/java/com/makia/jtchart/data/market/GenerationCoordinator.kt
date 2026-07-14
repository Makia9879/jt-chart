package com.makia.jtchart.data.market

import com.makia.jtchart.domain.market.Query

data class GenerationToken(val generation: Long, val query: Query)

/**
 * Thread-safe request generation gate. Call [begin] before starting a committed refresh and
 * [accepts] immediately before every state change, cache write, or WebView hand-off.
 */
class GenerationCoordinator {
    private val lock = Any()
    private var currentGeneration = 0L

    fun begin(): Long = synchronized(lock) { ++currentGeneration }

    fun begin(query: Query): GenerationToken = GenerationToken(begin(), query)

    fun current(): Long = synchronized(lock) { currentGeneration }

    fun accepts(generation: Long): Boolean = synchronized(lock) { generation == currentGeneration }

    fun accepts(token: GenerationToken, expectedQuery: Query): Boolean =
        token.query == expectedQuery && accepts(token.generation)

    fun invalidate(): Long = begin()
}
