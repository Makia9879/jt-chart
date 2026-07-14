package com.makia.jtchart.ui

import com.makia.jtchart.domain.market.Candle
import com.makia.jtchart.domain.market.DecimalString
import com.makia.jtchart.domain.market.MarketError
import com.makia.jtchart.domain.market.MarketRepository
import com.makia.jtchart.domain.market.MarketResult
import com.makia.jtchart.domain.market.Query
import com.makia.jtchart.domain.settings.AppSettings
import com.makia.jtchart.domain.settings.AlgorithmSettings
import com.makia.jtchart.domain.settings.SettingsStore
import com.makia.jtchart.persistence.market.CandleSnapshot
import com.makia.jtchart.persistence.market.SnapshotCache
import com.makia.jtchart.persistence.market.SnapshotCanonicalizer
import com.makia.jtchart.persistence.market.StoredSnapshot
import com.makia.jtchart.persistence.market.StoredViewport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChartViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `matching viewport is debounced and persisted with displayed identity`() = runTest(dispatcher) {
        val settings = AppSettings()
        val cached = stored(settings.query(), 100)
        val cache = FakeCache(cached)
        val repository = QueueRepository().apply { enqueuePending() }
        val viewModel = ChartViewModel(repository, FakeSettingsStore(settings), cache) { 900 }
        advanceUntilIdle()

        val state = viewModel.state.value
        viewModel.onViewportReported(state.requestGeneration, state.renderRevision, 10.0, 80.0)
        advanceTimeBy(499)
        assertTrue(cache.savedViewports.isEmpty())
        advanceTimeBy(1)
        advanceUntilIdle()

        assertEquals(1, cache.savedViewports.size)
        assertEquals(cached.snapshot.query.canonicalKey, cache.savedViewports.single().queryKey)
        assertEquals(cached.datasetFingerprint, cache.savedViewports.single().datasetFingerprint)
    }

    @Test
    fun `process restore uses only viewport matching cached query and fingerprint`() = runTest(dispatcher) {
        val settings = AppSettings()
        val cached = stored(settings.query(), 100)
        val expected = StoredViewport(settings.query().canonicalKey, cached.datasetFingerprint, 5.0, 20.0, 99)
        val cache = FakeCache(cached).apply { viewport = expected }
        val viewModel = ChartViewModel(QueueRepository().apply { enqueuePending() }, FakeSettingsStore(settings), cache)
        runCurrent()

        assertEquals(expected, viewModel.state.value.viewport)
        assertEquals(ChartViewPolicy.RESTORE_EXACT, viewModel.state.value.viewPolicy)
    }

    @Test
    fun `reported historical range is restored exactly after WebView recreation`() = runTest(dispatcher) {
        val settings = AppSettings()
        val cached = stored(settings.query(), 100)
        val cache = FakeCache(cached)
        val viewModel = ChartViewModel(
            QueueRepository().apply { enqueuePending() },
            FakeSettingsStore(settings),
            cache,
        )
        runCurrent()
        val state = viewModel.state.value
        viewModel.onViewportReported(state.requestGeneration, state.renderRevision, 7.0, 40.0)
        viewModel.prepareWebViewRecreation()
        runCurrent()

        assertEquals(ChartViewPolicy.RESTORE_EXACT, viewModel.state.value.viewPolicy)
        assertEquals(7.0, viewModel.state.value.viewport?.logicalFrom ?: Double.NaN, 0.0)
        assertEquals(cached.datasetFingerprint, viewModel.state.value.viewport?.datasetFingerprint)
        assertEquals(1, cache.savedViewports.size)
    }

    @Test
    fun `failed refresh keeps cached chart and requested versus displayed identity`() = runTest(dispatcher) {
        val settings = AppSettings()
        val cached = stored(settings.query(), 100)
        val repository = QueueRepository().apply { enqueue(MarketResult.Failure(MarketError.Timeout)) }
        val viewModel = ChartViewModel(repository, FakeSettingsStore(settings), FakeCache(cached))
        advanceUntilIdle()

        assertSame(cached, viewModel.state.value.displayedDataset)
        assertEquals(DisplayedDatasetSource.CACHE, viewModel.state.value.displayedDatasetSource)
        assertEquals(settings.query(), viewModel.state.value.notice?.requestedQuery)
        assertEquals(settings.query(), viewModel.state.value.notice?.displayedQuery)
    }

    @Test
    fun `algorithm-only apply redraws without another network request`() = runTest(dispatcher) {
        val settings = AppSettings()
        val repository = QueueRepository().apply { enqueuePending() }
        val viewModel = ChartViewModel(repository, FakeSettingsStore(settings), FakeCache(stored(settings.query(), 100)))
        runCurrent()
        val generation = viewModel.state.value.requestGeneration
        val revision = viewModel.state.value.renderRevision

        viewModel.updateDraft { it.copy(algorithm = it.algorithm.copy(smoothLength = 9)) }
        viewModel.applyDraft()
        runCurrent()

        assertEquals(1, repository.requests.size)
        assertEquals(generation, viewModel.state.value.requestGeneration)
        assertEquals(revision + 1, viewModel.state.value.renderRevision)
    }

    @Test
    fun `older generation completing late cannot replace newer dataset`() = runTest(dispatcher) {
        val settings = AppSettings()
        val repository = NonCancellableRepository()
        val viewModel = ChartViewModel(repository, FakeSettingsStore(settings), FakeCache()) { 500 }
        runCurrent()
        viewModel.retry()
        runCurrent()

        val newer = candles(201, close = "12")
        repository.second.complete(MarketResult.Success(newer))
        runCurrent()
        val newerFingerprint = viewModel.state.value.displayedDataset?.datasetFingerprint
        repository.first.complete(MarketResult.Success(candles(201, close = "11")))
        advanceUntilIdle()

        assertNotNull(newerFingerprint)
        assertEquals(newerFingerprint, viewModel.state.value.displayedDataset?.datasetFingerprint)
    }

    @Test
    fun `background cancels and each foreground transition refreshes only once`() = runTest(dispatcher) {
        val settings = AppSettings()
        val repository = QueueRepository().apply {
            enqueuePending()
            enqueuePending()
        }
        val viewModel = ChartViewModel(repository, FakeSettingsStore(settings), FakeCache(stored(settings.query(), 100)))
        runCurrent()
        viewModel.onForegroundChanged(false)
        runCurrent()
        viewModel.onForegroundChanged(true)
        viewModel.onForegroundChanged(true)
        runCurrent()

        assertEquals(2, repository.requests.size)
    }

    @Test
    fun `configuration stop and replacement start do not cancel or refresh`() = runTest(dispatcher) {
        val settings = AppSettings()
        val repository = QueueRepository().apply { enqueuePending() }
        val viewModel = ChartViewModel(repository, FakeSettingsStore(settings), FakeCache(stored(settings.query(), 100)))
        runCurrent()

        viewModel.onForegroundChanged(false, configurationChange = true)
        viewModel.onForegroundChanged(true)
        runCurrent()

        assertTrue(viewModel.state.value.isLoading)
        assertEquals(1, repository.requests.size)
    }

    @Test
    fun `configuration WebView rebuild preserves auto refresh deadline`() = runTest(dispatcher) {
        val settings = AppSettings(autoRefreshSeconds = 15)
        val repository = QueueRepository().apply {
            enqueue(MarketResult.Failure(MarketError.Timeout))
            enqueuePending()
        }
        val viewModel = ChartViewModel(repository, FakeSettingsStore(settings), FakeCache(stored(settings.query(), 100)))
        runCurrent()
        viewModel.onWebReady(true)
        advanceTimeBy(10_000)

        viewModel.onForegroundChanged(false, configurationChange = true)
        viewModel.onWebReady(false)
        viewModel.onForegroundChanged(true)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, repository.requests.size)
        viewModel.onWebReady(true)
        runCurrent()

        assertEquals(2, repository.requests.size)
    }

    @Test
    fun `combined query algorithm and refresh apply composes all side effects`() = runTest(dispatcher) {
        val settings = AppSettings()
        val repository = QueueRepository().apply {
            enqueuePending()
            enqueue(MarketResult.Failure(MarketError.Timeout))
            enqueuePending()
        }
        val viewModel = ChartViewModel(repository, FakeSettingsStore(settings), FakeCache(stored(settings.query(), 100)))
        runCurrent()
        viewModel.onWebReady(true)
        val oldRevision = viewModel.state.value.renderRevision
        viewModel.updateDraft {
            it.copy(
                currentSymbol = "ETHUSDT",
                algorithm = it.algorithm.copy(smoothLength = 9),
                autoRefreshSeconds = 15,
            )
        }
        viewModel.applyDraft()
        runCurrent()

        assertEquals("ETHUSDT", viewModel.state.value.requestedQuery.symbol)
        assertEquals(oldRevision + 1, viewModel.state.value.renderRevision)
        assertEquals(2, repository.requests.size)
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(3, repository.requests.size)
    }

    @Test
    fun `requested candle count not exceeding WMA is rejected before render`() = runTest(dispatcher) {
        val settings = AppSettings(algorithm = AlgorithmSettings(bearWmaLength = 500))
        val old = stored(settings.query(), 100)
        val repository = QueueRepository().apply { enqueue(MarketResult.Success(candles(500))) }
        val viewModel = ChartViewModel(repository, FakeSettingsStore(settings), FakeCache(old))
        advanceUntilIdle()

        assertSame(old, viewModel.state.value.displayedDataset)
        assertEquals("K线数量必须大于熊市 WMA 周期", viewModel.state.value.notice?.message)
    }

    @Test
    fun `short upstream response reports actual count and WMA requirement`() = runTest(dispatcher) {
        val settings = AppSettings()
        val old = stored(settings.query(), 100)
        val repository = QueueRepository().apply { enqueue(MarketResult.Success(candles(100))) }
        val viewModel = ChartViewModel(repository, FakeSettingsStore(settings), FakeCache(old))
        advanceUntilIdle()

        assertSame(old, viewModel.state.value.displayedDataset)
        assertEquals("行情仅返回 100 根 K 线，无法计算 WMA 200", viewModel.state.value.notice?.message)
    }

    @Test
    fun `runtime gets one rebuild attempt and stable ack resets allowance`() = runTest(dispatcher) {
        val settings = AppSettings()
        val viewModel = ChartViewModel(
            QueueRepository().apply { enqueuePending() },
            FakeSettingsStore(settings),
            FakeCache(stored(settings.query(), 100)),
        )
        runCurrent()
        val generation = viewModel.state.value.requestGeneration
        val revision = viewModel.state.value.renderRevision

        viewModel.onChartRuntimeFailure(generation, revision)
        assertEquals(1, viewModel.state.value.webViewInstance)
        viewModel.onChartRuntimeFailure(generation, revision)
        assertEquals(ChartRuntimeState.ERROR, viewModel.state.value.chartRuntime)
        assertEquals("图表加载失败", viewModel.state.value.notice?.message?.substringAfter(" · ")?.substringBefore("，"))
    }

    private fun stored(query: Query, fetchedAt: Long): StoredSnapshot = SnapshotCanonicalizer.store(
        CandleSnapshot(query, candles(), fetchedAt),
        fetchedAt,
    )

    private fun candles() = listOf(
        Candle(1, price("10"), price("12"), price("9"), price("11"), null, null),
        Candle(2, price("11"), price("13"), price("10"), price("12"), null, null),
    )

    private fun price(value: String) = DecimalString.price(value)

    private fun candles(count: Int, close: String = "11"): List<Candle> = (0 until count).map { index ->
        Candle(index.toLong(), price("10"), price("13"), price("9"), price(close), null, null)
    }
}

private class NonCancellableRepository : MarketRepository {
    val first = CompletableDeferred<MarketResult<List<Candle>>>()
    val second = CompletableDeferred<MarketResult<List<Candle>>>()
    private var count = 0

    override suspend fun fetch(query: Query): MarketResult<List<Candle>> {
        val result = if (count++ == 0) first else second
        return withContext(NonCancellable) { result.await() }
    }
}

private class FakeSettingsStore(private var settings: AppSettings) : SettingsStore {
    override suspend fun load(): AppSettings = settings
    override suspend fun save(settings: AppSettings) { this.settings = settings }
}

private class QueueRepository : MarketRepository {
    val requests = mutableListOf<Query>()
    private val results = ArrayDeque<CompletableDeferred<MarketResult<List<Candle>>>>()

    fun enqueuePending(): CompletableDeferred<MarketResult<List<Candle>>> =
        CompletableDeferred<MarketResult<List<Candle>>>().also(results::addLast)

    fun enqueue(result: MarketResult<List<Candle>>) {
        results.addLast(CompletableDeferred(result))
    }

    override suspend fun fetch(query: Query): MarketResult<List<Candle>> {
        requests += query
        return results.removeFirst().await()
    }
}

private class FakeCache(private val initial: StoredSnapshot? = null) : SnapshotCache {
    val savedViewports = mutableListOf<StoredViewport>()
    var viewport: StoredViewport? = null

    override suspend fun get(query: Query, accessedAtEpochMs: Long): StoredSnapshot? =
        initial?.takeIf { it.snapshot.query == query }

    override suspend fun put(
        snapshot: CandleSnapshot,
        accessedAtEpochMs: Long,
        protectedQueryKeys: Set<String>,
    ): StoredSnapshot = SnapshotCanonicalizer.store(snapshot, accessedAtEpochMs)

    override suspend fun saveViewport(viewport: StoredViewport) {
        savedViewports += viewport
        this.viewport = viewport
    }

    override suspend fun getViewport(queryKey: String, datasetFingerprint: String): StoredViewport? =
        viewport?.takeIf { it.queryKey == queryKey && it.datasetFingerprint == datasetFingerprint }
}
