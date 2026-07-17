package com.makia.jtchart.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.makia.jtchart.data.market.GenerationCoordinator
import com.makia.jtchart.domain.market.MarketError
import com.makia.jtchart.domain.market.MarketRepository
import com.makia.jtchart.domain.market.MarketResult
import com.makia.jtchart.domain.market.Query
import com.makia.jtchart.domain.settings.AppSettings
import com.makia.jtchart.domain.settings.SettingsStore
import com.makia.jtchart.domain.signal.ChartSignal
import com.makia.jtchart.notifications.NoopSignalNotifier
import com.makia.jtchart.notifications.SignalNotifier
import com.makia.jtchart.persistence.market.CandleSnapshot
import com.makia.jtchart.persistence.market.SnapshotCache
import com.makia.jtchart.persistence.market.StoredSnapshot
import com.makia.jtchart.persistence.market.StoredViewport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ChartViewPolicy(val wireName: String) {
    FIT_CONTENT("fitContent"),
    RESTORE_EXACT("restoreExact"),
    PRESERVE_OR_FOLLOW_RIGHT("preserveOrFollowRight"),
}

data class FailureNotice(
    val requestedQuery: Query,
    val displayedQuery: Query?,
    val error: MarketError,
    val detailMessage: String? = null,
) {
    val message: String
        get() {
            detailMessage?.let { return it }
            val request = requestedQuery.label()
            val reason = error.userMessage()
            return displayedQuery?.let { "$request · $reason，当前仍显示 ${it.label()} 数据" }
                ?: "$request · $reason"
        }
}

enum class DisplayedDatasetSource { CACHE, NETWORK }

enum class ChartRuntimeState { LOADING, READY, AWAITING_RENDER_ACK, STABLE, ERROR }

data class ChartUiState(
    val initialized: Boolean = false,
    val appliedSettings: AppSettings = AppSettings(),
    val draftSettings: AppSettings = AppSettings(),
    val requestedQuery: Query = AppSettings().query(),
    val displayedDataset: StoredSnapshot? = null,
    val displayedDatasetSource: DisplayedDatasetSource? = null,
    val viewport: StoredViewport? = null,
    val isLoading: Boolean = false,
    val notice: FailureNotice? = null,
    val webReady: Boolean = false,
    val isForeground: Boolean = true,
    val requestGeneration: Long = 0,
    val renderRevision: Long = 0,
    val viewPolicy: ChartViewPolicy = ChartViewPolicy.FIT_CONTENT,
    val chartRuntime: ChartRuntimeState = ChartRuntimeState.LOADING,
    val webViewInstance: Long = 0,
)

class ChartViewModel(
    private val repository: MarketRepository,
    private val settingsStore: SettingsStore,
    private val snapshotCache: SnapshotCache,
    private val signalNotifier: SignalNotifier = NoopSignalNotifier,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val generation = GenerationCoordinator()
    private val _state = MutableStateFlow(ChartUiState())
    val state: StateFlow<ChartUiState> = _state.asStateFlow()

    private var requestJob: Job? = null
    private var autoRefreshJob: Job? = null
    private var viewportSaveJob: Job? = null
    private var runtimeRecoveryUsed = false
    private var configurationChangeInProgress = false
    private var refreshDueOnReady = false

    init {
        viewModelScope.launch { initialize() }
    }

    fun updateDraft(transform: (AppSettings) -> AppSettings) {
        _state.update { it.copy(draftSettings = transform(it.draftSettings)) }
    }

    fun cancelDraft() {
        _state.update { it.copy(draftSettings = it.appliedSettings) }
    }

    fun applyDraft() {
        viewModelScope.launch {
            val before = state.value.appliedSettings
            val after = state.value.draftSettings
            try {
                settingsStore.save(after)
            } catch (_: Exception) {
                _state.update {
                    it.copy(notice = FailureNotice(after.query(), it.displayedDataset?.snapshot?.query, MarketError.Protocol("LocalPersistence")))
                }
                return@launch
            }
            val queryChanged = before.query() != after.query()
            val algorithmChanged = before.algorithm != after.algorithm
            val refreshChanged = before.autoRefreshSeconds != after.autoRefreshSeconds
            _state.update { it.copy(appliedSettings = after, requestedQuery = after.query()) }
            if (queryChanged) {
                switchQuery(after.query())
                if (algorithmChanged && state.value.displayedDataset?.snapshot?.query != after.query()) {
                    redrawCurrentDatasetOrReportWma(after.algorithm.bearWmaLength)
                }
            } else if (algorithmChanged) {
                redrawCurrentDatasetOrReportWma(after.algorithm.bearWmaLength)
            }
            if (!queryChanged && (algorithmChanged || refreshChanged)) {
                scheduleAutoRefresh()
            }
        }
    }

    fun retry() {
        if (state.value.chartRuntime == ChartRuntimeState.ERROR) {
            runtimeRecoveryUsed = false
            _state.update {
                it.copy(
                    notice = null,
                    webReady = false,
                    chartRuntime = ChartRuntimeState.LOADING,
                    webViewInstance = it.webViewInstance + 1,
                )
            }
            return
        }
        refresh(ChartViewPolicy.PRESERVE_OR_FOLLOW_RIGHT)
    }

    private fun redrawCurrentDatasetOrReportWma(required: Int) {
        val dataset = state.value.displayedDataset ?: return
        val actual = dataset.snapshot.candles.size
        if (actual <= required) {
            val detail = if (actual == dataset.snapshot.query.limit) {
                "K线数量必须大于熊市 WMA 周期"
            } else {
                "行情仅返回 $actual 根 K 线，无法计算 WMA $required"
            }
            _state.update {
                it.copy(
                    notice = FailureNotice(
                        it.requestedQuery,
                        dataset.snapshot.query,
                        MarketError.NoData,
                        detail,
                    ),
                )
            }
            return
        }
        _state.update {
            it.copy(
                notice = null,
                renderRevision = it.renderRevision + 1,
                viewPolicy = ChartViewPolicy.PRESERVE_OR_FOLLOW_RIGHT,
            )
        }
    }

    fun onWebReady(ready: Boolean) {
        _state.update {
            it.copy(
                webReady = ready,
                chartRuntime = if (ready) ChartRuntimeState.READY else ChartRuntimeState.LOADING,
            )
        }
        if (ready) {
            configurationChangeInProgress = false
            if (refreshDueOnReady) {
                refreshDueOnReady = false
                refresh(ChartViewPolicy.PRESERVE_OR_FOLLOW_RIGHT)
            } else {
                scheduleAutoRefresh(replaceExisting = false)
            }
        } else if (!configurationChangeInProgress) {
            autoRefreshJob?.cancel()
        }
    }

    fun onRenderDispatched(generation: Long, renderRevision: Long) {
        if (matchesRender(generation, renderRevision)) {
            _state.update { it.copy(chartRuntime = ChartRuntimeState.AWAITING_RENDER_ACK) }
        }
    }

    fun onRenderAck(
        generation: Long,
        renderRevision: Long,
        signals: List<ChartSignal> = emptyList(),
        latestCandleTime: Long? = null,
    ) {
        if (!matchesRender(generation, renderRevision)) return
        notifyLatestSignals(signals, latestCandleTime)
        runtimeRecoveryUsed = false
        _state.update { it.copy(chartRuntime = ChartRuntimeState.STABLE) }
    }

    private fun notifyLatestSignals(signals: List<ChartSignal>, latestCandleTime: Long?) {
        val current = state.value
        if (!current.appliedSettings.signalNotificationsEnabled) return
        val query = current.displayedDataset?.snapshot?.query ?: return
        val latestTime = latestCandleTime ?: return
        signals.asSequence()
            .filter { it.time == latestTime }
            .forEach { signalNotifier.notify(query, it) }
    }

    fun onChartRuntimeFailure(generation: Long?, renderRevision: Long?) {
        if (generation != null && renderRevision != null && !matchesRender(generation, renderRevision)) return
        if (!runtimeRecoveryUsed) {
            runtimeRecoveryUsed = true
            _state.update {
                it.copy(
                    webReady = false,
                    chartRuntime = ChartRuntimeState.LOADING,
                    webViewInstance = it.webViewInstance + 1,
                )
            }
        } else {
            val current = state.value
            _state.update {
                it.copy(
                    webReady = false,
                    chartRuntime = ChartRuntimeState.ERROR,
                    notice = FailureNotice(
                        current.requestedQuery,
                        current.displayedDataset?.snapshot?.query,
                        MarketError.Protocol("ChartRuntime"),
                    ),
                )
            }
        }
    }

    fun onViewportReported(
        generation: Long,
        renderRevision: Long,
        logicalFrom: Double,
        logicalTo: Double,
    ) {
        if (!matchesRender(generation, renderRevision) || !logicalFrom.isFinite() ||
            !logicalTo.isFinite() || logicalFrom >= logicalTo
        ) return
        val dataset = state.value.displayedDataset ?: return
        val viewport = StoredViewport(
            dataset.snapshot.query.canonicalKey,
            dataset.datasetFingerprint,
            logicalFrom,
            logicalTo,
            nowEpochMs(),
        )
        _state.update { it.copy(viewport = viewport) }
        viewportSaveJob?.cancel()
        viewportSaveJob = viewModelScope.launch {
            delay(500)
            val current = state.value
            val displayed = current.displayedDataset
            if (matchesRender(generation, renderRevision) &&
                displayed?.snapshot?.query?.canonicalKey == viewport.queryKey &&
                displayed.datasetFingerprint == viewport.datasetFingerprint
            ) {
                runCatching { snapshotCache.saveViewport(viewport) }
            }
        }
    }

    fun prepareWebViewRecreation() {
        val viewport = state.value.viewport ?: return
        val displayed = state.value.displayedDataset ?: return
        if (displayed.snapshot.query.canonicalKey != viewport.queryKey ||
            displayed.datasetFingerprint != viewport.datasetFingerprint
        ) return
        _state.update { it.copy(viewPolicy = ChartViewPolicy.RESTORE_EXACT) }
        viewportSaveJob?.cancel()
        viewportSaveJob = viewModelScope.launch {
            runCatching { snapshotCache.saveViewport(viewport.copy(updatedAtEpochMs = nowEpochMs())) }
        }
    }

    private fun matchesRender(generation: Long, renderRevision: Long): Boolean =
        state.value.requestGeneration == generation && state.value.renderRevision == renderRevision

    fun onForegroundChanged(foreground: Boolean, configurationChange: Boolean = false) {
        if (configurationChange) {
            configurationChangeInProgress = true
            return
        }
        val wasForeground = state.value.isForeground
        if (wasForeground == foreground) return
        _state.update { it.copy(isForeground = foreground) }
        if (!foreground) {
            refreshDueOnReady = false
            autoRefreshJob?.cancel()
            requestJob?.cancel()
            generation.invalidate()
            _state.update { it.copy(isLoading = false) }
        } else if (state.value.initialized) {
            refresh(ChartViewPolicy.PRESERVE_OR_FOLLOW_RIGHT)
        }
    }

    private suspend fun initialize() {
        val settings = try {
            settingsStore.load()
        } catch (_: Exception) {
            AppSettings()
        }
        val query = settings.query()
        val cached = try {
            snapshotCache.get(query, nowEpochMs())
        } catch (_: Exception) {
            null
        }
        val viewport = cached?.let {
            runCatching { snapshotCache.getViewport(query.canonicalKey, it.datasetFingerprint) }.getOrNull()
        }
        _state.update {
            it.copy(
                initialized = true,
                appliedSettings = settings,
                draftSettings = settings,
                requestedQuery = query,
                displayedDataset = cached,
                displayedDatasetSource = cached?.let { DisplayedDatasetSource.CACHE },
                viewport = viewport,
                renderRevision = if (cached == null) 0 else 1,
                viewPolicy = if (viewport == null) ChartViewPolicy.FIT_CONTENT else ChartViewPolicy.RESTORE_EXACT,
            )
        }
        refresh(if (cached == null) ChartViewPolicy.FIT_CONTENT else ChartViewPolicy.PRESERVE_OR_FOLLOW_RIGHT)
    }

    private suspend fun switchQuery(query: Query) {
        requestJob?.cancel()
        autoRefreshJob?.cancel()
        val cached = try {
            snapshotCache.get(query, nowEpochMs())
        } catch (_: Exception) {
            null
        }
        _state.update {
            it.copy(
                requestedQuery = query,
                displayedDataset = cached ?: it.displayedDataset,
                displayedDatasetSource = if (cached == null) it.displayedDatasetSource else DisplayedDatasetSource.CACHE,
                viewport = if (cached == null) it.viewport else null,
                notice = null,
                renderRevision = if (cached == null) it.renderRevision else it.renderRevision + 1,
                viewPolicy = if (cached == null) it.viewPolicy else ChartViewPolicy.FIT_CONTENT,
            )
        }
        refresh(if (cached == null) ChartViewPolicy.FIT_CONTENT else ChartViewPolicy.PRESERVE_OR_FOLLOW_RIGHT)
    }

    private fun refresh(viewPolicy: ChartViewPolicy) {
        if (!state.value.isForeground) return
        requestJob?.cancel()
        autoRefreshJob?.cancel()
        val query = state.value.requestedQuery
        val requestGeneration = generation.begin()
        _state.update { it.copy(isLoading = true, requestGeneration = requestGeneration) }
        requestJob = viewModelScope.launch {
            try {
                when (val result = repository.fetch(query)) {
                    is MarketResult.Success -> {
                        if (!generation.accepts(requestGeneration)) return@launch
                        val required = state.value.appliedSettings.algorithm.bearWmaLength
                        if (result.value.size <= required) {
                            val detail = if (result.value.size == query.limit) {
                                "K线数量必须大于熊市 WMA 周期"
                            } else {
                                "行情仅返回 ${result.value.size} 根 K 线，无法计算 WMA $required"
                            }
                            _state.update {
                                it.copy(
                                    isLoading = false,
                                    notice = FailureNotice(
                                        query,
                                        it.displayedDataset?.snapshot?.query,
                                        MarketError.NoData,
                                        detail,
                                    ),
                                )
                            }
                            return@launch
                        }
                        val snapshot = CandleSnapshot(query, result.value, nowEpochMs())
                        val stored = snapshotCache.put(
                            snapshot = snapshot,
                            accessedAtEpochMs = nowEpochMs(),
                            protectedQueryKeys = setOfNotNull(state.value.displayedDataset?.snapshot?.query?.canonicalKey),
                        )
                        if (!generation.accepts(requestGeneration)) return@launch
                        _state.update {
                            it.copy(
                                displayedDataset = stored,
                                displayedDatasetSource = DisplayedDatasetSource.NETWORK,
                                viewport = null,
                                isLoading = false,
                                notice = null,
                                renderRevision = it.renderRevision + 1,
                                viewPolicy = viewPolicy,
                            )
                        }
                    }
                    is MarketResult.Failure -> {
                        if (!generation.accepts(requestGeneration)) return@launch
                        _state.update {
                            it.copy(
                                isLoading = false,
                                notice = if (result.error == MarketError.Cancelled) it.notice else FailureNotice(
                                    query,
                                    it.displayedDataset?.snapshot?.query,
                                    result.error,
                                ),
                            )
                        }
                    }
                }
            } catch (_: CancellationException) {
                // Lifecycle and newer-generation cancellation are intentionally silent.
            } catch (_: Exception) {
                if (generation.accepts(requestGeneration)) {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            notice = FailureNotice(query, it.displayedDataset?.snapshot?.query, MarketError.Protocol()),
                        )
                    }
                }
            } finally {
                if (generation.accepts(requestGeneration)) scheduleAutoRefresh()
            }
        }
    }

    private fun scheduleAutoRefresh(replaceExisting: Boolean = true) {
        if (!replaceExisting && autoRefreshJob?.isActive == true) return
        autoRefreshJob?.cancel()
        val snapshot = state.value
        val intervalSeconds = snapshot.appliedSettings.autoRefreshSeconds
        if (intervalSeconds == 0 || !snapshot.isForeground || !snapshot.webReady || snapshot.isLoading) return
        autoRefreshJob = viewModelScope.launch {
            delay(intervalSeconds * 1_000L)
            if (configurationChangeInProgress || !state.value.webReady) {
                refreshDueOnReady = true
            } else {
                refresh(ChartViewPolicy.PRESERVE_OR_FOLLOW_RIGHT)
            }
        }
    }

    class Factory(
        private val repository: MarketRepository,
        private val settingsStore: SettingsStore,
        private val snapshotCache: SnapshotCache,
        private val signalNotifier: SignalNotifier = NoopSignalNotifier,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ChartViewModel(repository, settingsStore, snapshotCache, signalNotifier) as T
    }
}

private fun Query.label(): String = "$symbol / ${source.wireName} / ${interval.wireName}"

private fun MarketError.userMessage(): String = when (this) {
    MarketError.Cancelled -> "请求已取消"
    MarketError.Connectivity -> "网络连接失败"
    MarketError.Timeout -> "请求超时"
    is MarketError.RateLimited -> "请求过于频繁"
    MarketError.RequestRejected -> "交易对或参数不受支持"
    MarketError.AccessRestricted -> "当前网络或地区无法访问该数据源"
    MarketError.UpstreamUnavailable -> "数据源暂时不可用"
    is MarketError.Protocol -> when (diagnostic) {
        "LocalPersistence" -> "本地数据读取失败"
        "ChartRuntime" -> "图表加载失败"
        else -> "行情数据格式异常"
    }
    MarketError.NoData -> "没有可用行情"
}
