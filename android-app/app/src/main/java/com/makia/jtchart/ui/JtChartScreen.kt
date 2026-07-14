package com.makia.jtchart.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.makia.jtchart.domain.market.CandleInterval
import com.makia.jtchart.domain.market.MarketSource
import com.makia.jtchart.domain.settings.AlgorithmSettings
import com.makia.jtchart.domain.settings.AppSettings
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class SettingsTab(val label: String) { MARKET("行情"), INDICATOR("指标"), REFRESH("刷新") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JtChartScreen(
    state: ChartUiState,
    viewModel: ChartViewModel,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(SettingsTab.MARKET) }
    LaunchedEffect(drawerState.currentValue) {
        // AndroidView update sends the interaction state to JavaScript.
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = false,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.fillMaxWidth(0.88f),
            ) {
                Text(
                    "JT Chart 设置",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(20.dp),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsTab.entries.forEach { tab ->
                        FilterChip(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            label = { Text(tab.label) },
                            modifier = Modifier.testTag("settings-tab-${tab.name.lowercase()}"),
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(top = 8.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (selectedTab) {
                        SettingsTab.MARKET -> MarketSettings(state.draftSettings, viewModel::updateDraft)
                        SettingsTab.INDICATOR -> IndicatorSettings(state.draftSettings, viewModel::updateDraft)
                        SettingsTab.REFRESH -> RefreshSettings(state.draftSettings, viewModel::updateDraft)
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    OutlinedButton(
                        onClick = {
                            viewModel.cancelDraft()
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.testTag("settings-cancel"),
                    ) { Text("取消") }
                    Button(
                        onClick = {
                            viewModel.applyDraft()
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.testTag("settings-apply"),
                    ) { Text("应用并刷新") }
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { DatasetTitle(state) },
                    navigationIcon = {
                        TextButton(
                            onClick = { scope.launch { drawerState.open() } },
                            modifier = Modifier
                                .semantics { contentDescription = "打开设置" }
                                .testTag("open-settings"),
                        ) { Text("≡", style = MaterialTheme.typography.headlineSmall) }
                    },
                    actions = {
                        TextButton(
                            onClick = viewModel::retry,
                            modifier = Modifier.semantics { contentDescription = "立即刷新行情" },
                        ) { Text("刷新") }
                    },
                )
            },
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                if (state.displayedDataset != null) {
                    ChartWebView(
                        state = state,
                        interactionEnabled = drawerState.currentValue == DrawerValue.Closed,
                        onReady = viewModel::onWebReady,
                        onRenderDispatched = viewModel::onRenderDispatched,
                        onRenderAck = viewModel::onRenderAck,
                        onViewport = viewModel::onViewportReported,
                        onViewportFlush = viewModel::prepareWebViewRecreation,
                        onRuntimeFailure = viewModel::onChartRuntimeFailure,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("chart-webview"),
                    )
                } else {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(if (state.isLoading) "正在加载行情…" else "暂无可显示行情")
                        if (!state.isLoading) Button(onClick = viewModel::retry) { Text("重试") }
                    }
                }

                if (state.notice != null) {
                    val notice = state.notice
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.medium,
                        shadowElevation = 8.dp,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(10.dp)
                            .fillMaxWidth()
                            .clickable(onClick = viewModel::retry)
                            .testTag("failure-bubble"),
                    ) {
                        Text("${notice.message} · 点击重试", Modifier.padding(12.dp))
                    }
                } else if (state.isLoading && state.displayedDataset != null) {
                    Surface(
                        tonalElevation = 4.dp,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.align(Alignment.TopCenter).padding(10.dp),
                    ) { Text("正在更新，下方保留当前图表", Modifier.padding(10.dp)) }
                }

                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .width(12.dp)
                        .fillMaxHeight()
                        .pointerInput(drawerState.currentValue) {
                            var drag = 0f
                            detectHorizontalDragGestures(
                                onHorizontalDrag = { _, amount -> drag += amount },
                                onDragEnd = {
                                    if (drag > 24f) scope.launch { drawerState.open() }
                                    drag = 0f
                                },
                                onDragCancel = { drag = 0f },
                            )
                        }
                        .testTag("drawer-edge"),
                )
            }
        }
    }
}

@Composable
private fun DatasetTitle(state: ChartUiState) {
    val dataset = state.displayedDataset
    Column {
        Text(
            dataset?.snapshot?.query?.let {
                "${it.symbol} · ${it.source.label} · ${it.interval.wireName} · ${it.limit}"
            } ?: "JT Chart",
            style = MaterialTheme.typography.titleMedium,
        )
        dataset?.let {
            val time = remember(it.snapshot.fetchedAtEpochMs) {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(it.snapshot.fetchedAtEpochMs))
            }
            val freshness = if (state.displayedDatasetSource == DisplayedDatasetSource.CACHE) "缓存数据" else "已更新"
            Text("$freshness · $time · 实返 ${it.snapshot.candles.size}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun MarketSettings(settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    Text("币对", style = MaterialTheme.typography.titleMedium)
    settings.symbols.forEach { symbol ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = symbol == settings.currentSymbol,
                onClick = { update { it.copy(currentSymbol = symbol) } },
                label = { Text(symbol) },
            )
            if (symbol == settings.currentSymbol) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { update { it.moveCurrent(-1) } }) { Text("上移") }
                TextButton(onClick = { update { it.moveCurrent(1) } }) { Text("下移") }
            }
        }
    }
    var symbolInput by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = symbolInput,
            onValueChange = { symbolInput = it.uppercase().filter(Char::isLetterOrDigit).take(30) },
            label = { Text("新增币对") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = {
            val normalized = AppSettings.normalizeSymbols(settings.symbols + symbolInput)
            update { it.copy(symbols = normalized, currentSymbol = symbolInput) }
            symbolInput = ""
        }, enabled = symbolInput.length >= 3) { Text("添加") }
    }

    Text("数据源", style = MaterialTheme.typography.titleMedium)
    MarketSource.entries.forEach { source ->
        FilterChip(
            selected = settings.source == source,
            onClick = { update { it.copy(source = source) } },
            label = { Text(source.label) },
        )
    }
    Text("周期", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CandleInterval.entries.forEach { interval ->
            FilterChip(
                selected = settings.interval == interval,
                onClick = { update { it.copy(interval = interval) } },
                label = { Text(interval.wireName) },
            )
        }
    }
    IntegerField("K 线数量（120–1000）", settings.limit) { value ->
        if (value in 120..1000) update { it.copy(limit = value) }
    }
}

@Composable
private fun IndicatorSettings(settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    val algorithm = settings.algorithm
    IntegerField("Lag Start", algorithm.momStart) { value -> update.algorithm(settings, algorithm.copy(momStart = value)) }
    IntegerField("Lag End", algorithm.momEnd) { value -> update.algorithm(settings, algorithm.copy(momEnd = value)) }
    IntegerField("Z 窗口", algorithm.zLength) { value -> update.algorithm(settings, algorithm.copy(zLength = value)) }
    Text("极端阈值 ${"%.1f".format(algorithm.extremeThreshold)}")
    Slider(
        value = algorithm.extremeThreshold.toFloat(),
        onValueChange = { value -> update.algorithm(settings, algorithm.copy(extremeThreshold = (value * 10).toInt() / 10.0)) },
        valueRange = 0.5f..5f,
        steps = 44,
    )
    IntegerField("平滑", algorithm.smoothLength) { value -> update.algorithm(settings, algorithm.copy(smoothLength = value)) }
    IntegerField("熊市 WMA 周期", algorithm.bearWmaLength) { value -> update.algorithm(settings, algorithm.copy(bearWmaLength = value)) }
    if (settings.limit <= algorithm.bearWmaLength) {
        Text("K线数量必须大于熊市 WMA 周期", color = MaterialTheme.colorScheme.error)
    }
}

private fun (((AppSettings) -> AppSettings) -> Unit).algorithm(
    settings: AppSettings,
    candidate: AlgorithmSettings,
) {
    runCatching { settings.copy(algorithm = candidate) }.getOrNull()?.let { valid -> invoke { valid } }
}

@Composable
private fun RefreshSettings(settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    Text("自动刷新", style = MaterialTheme.typography.titleMedium)
    AppSettings.AUTO_REFRESH_OPTIONS.sorted().forEach { seconds ->
        FilterChip(
            selected = settings.autoRefreshSeconds == seconds,
            onClick = { update { it.copy(autoRefreshSeconds = seconds) } },
            label = { Text(if (seconds == 0) "关闭" else "$seconds 秒") },
        )
    }
    Text("仅在 App 前台、屏幕未锁定且图表可见时刷新。回到前台只补刷一次。")
}

@Composable
private fun IntegerField(label: String, value: Int, onValidValue: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw.filter(Char::isDigit).take(6)
            text.toIntOrNull()?.let(onValidValue)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun AppSettings.moveCurrent(delta: Int): AppSettings {
    val from = symbols.indexOf(currentSymbol)
    val to = (from + delta).coerceIn(symbols.indices)
    if (from == to) return this
    val reordered = symbols.toMutableList().apply {
        val item = removeAt(from)
        add(to, item)
    }
    return copy(symbols = reordered)
}

private val MarketSource.label: String
    get() = when (this) {
        MarketSource.BINANCE_SPOT -> "Binance 现货"
        MarketSource.BINANCE_FUTURES -> "Binance U本位"
        MarketSource.BYBIT_SPOT -> "Bybit 现货"
        MarketSource.BYBIT_LINEAR -> "Bybit USDT 永续"
        MarketSource.BITGET_SPOT -> "Bitget 现货"
        MarketSource.BITGET_FUTURES -> "Bitget USDT 永续"
    }
