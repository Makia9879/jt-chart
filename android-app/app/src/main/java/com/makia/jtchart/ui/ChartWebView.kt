package com.makia.jtchart.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebMessagePortCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import com.makia.jtchart.domain.settings.AlgorithmSettings
import com.makia.jtchart.domain.signal.ChartSignal
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val CHART_URL = "https://appassets.androidplatform.net/assets/chart/index.html"
private val APP_ASSET_ORIGIN: Uri = Uri.parse("https://appassets.androidplatform.net")

@Composable
fun ChartWebView(
    state: ChartUiState,
    interactionEnabled: Boolean,
    onReady: (Boolean) -> Unit,
    onRenderDispatched: (Long, Long) -> Unit,
    onRenderAck: (Long, Long, List<ChartSignal>, Long?) -> Unit,
    onViewport: (Long, Long, Double, Double) -> Unit,
    onViewportFlush: () -> Unit,
    onRuntimeFailure: (Long?, Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var controller by remember { mutableStateOf<ChartWebController?>(null) }
    val preparedRender by produceState<PreparedChartRender?>(
        initialValue = null,
        state.requestGeneration,
        state.renderRevision,
        state.displayedDataset?.datasetFingerprint,
        state.appliedSettings.algorithm,
        state.viewPolicy,
        state.viewport,
    ) {
        value = withContext(Dispatchers.Default) { prepareChartRender(state) }
    }

    key(state.webViewInstance) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                ChartWebController(
                    context,
                    onReady,
                    onRenderDispatched,
                    onRenderAck,
                    onViewport,
                    onRuntimeFailure,
                ).also { controller = it }.webView
            },
            update = {
                controller?.setInteractionEnabled(interactionEnabled, state)
                controller?.render(state, preparedRender)
            },
        )
        DisposableEffect(state.webViewInstance) {
            val ownedController = controller
            onDispose {
                onViewportFlush()
                ownedController?.dispose(state)
                if (controller === ownedController) controller = null
                onReady(false)
            }
        }
    }
}

private data class PreparedChartRender(
    val generation: Long,
    val renderRevision: Long,
    val payloadJson: String,
)

private fun prepareChartRender(state: ChartUiState): PreparedChartRender? {
    val dataset = state.displayedDataset ?: return null
    if (state.renderRevision <= 0) return null
    val query = dataset.snapshot.query
    val payload = JSONObject()
        .put("queryKey", JSONObject()
            .put("source", query.source.wireName)
            .put("symbol", query.symbol)
            .put("interval", query.interval.wireName)
            .put("limit", query.limit))
        .put("datasetFingerprint", dataset.datasetFingerprint)
        .put("candles", JSONArray().apply {
            dataset.snapshot.candles.forEach { candle ->
                put(JSONArray()
                    .put(candle.openTimeMs)
                    .put(candle.open.raw)
                    .put(candle.high.raw)
                    .put(candle.low.raw)
                    .put(candle.close.raw))
            }
        })
        .put("settings", state.appliedSettings.algorithm.toJson())
        .put("viewPolicy", state.viewPolicy.wireName)
        .put("viewport", state.viewport
            ?.takeIf {
                state.viewPolicy == ChartViewPolicy.RESTORE_EXACT &&
                    it.queryKey == query.canonicalKey &&
                    it.datasetFingerprint == dataset.datasetFingerprint
            }
            ?.let {
                JSONObject()
                    .put("logicalFrom", it.logicalFrom)
                    .put("logicalTo", it.logicalTo)
            } ?: JSONObject.NULL)
    return PreparedChartRender(state.requestGeneration, state.renderRevision, payload.toString())
}

@SuppressLint("SetJavaScriptEnabled")
private class ChartWebController(
    context: android.content.Context,
    private val onReadyChanged: (Boolean) -> Unit,
    private val onRenderDispatched: (Long, Long) -> Unit,
    private val onRenderAck: (Long, Long, List<ChartSignal>, Long?) -> Unit,
    private val onViewport: (Long, Long, Double, Double) -> Unit,
    private val onRuntimeFailure: (Long?, Long?) -> Unit,
) {
    private val pageInstanceId = UUID.randomUUID().toString()
    private val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()
    private var nativePort: WebMessagePortCompat? = null
    private var ready = false
    private var lastRenderedIdentity: Pair<Long, Long>? = null
    private var lastInteractionEnabled: Boolean? = null
    private val handler = Handler(Looper.getMainLooper())
    private var handshakeReloads = 0
    private var disposed = false
    private val handshakeTimeout = Runnable {
        if (ready || disposed) return@Runnable
        if (handshakeReloads == 0) {
            handshakeReloads++
            webView.reload()
        } else {
            onRuntimeFailure(null, null)
        }
    }

    val webView: WebView = WebView(context).apply {
        setBackgroundColor(android.graphics.Color.rgb(16, 18, 22))
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        settings.databaseEnabled = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptCookie(false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        WebView.setWebContentsDebuggingEnabled(
            (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0,
        )
        webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
        }
        setDownloadListener { _, _, _, _, _ -> Unit }
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            @Deprecated("Deprecated in WebViewClient")
            override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(Uri.parse(url))

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.toString() != CHART_URL

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                closePort()
                ready = false
                onReadyChanged(false)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (url == CHART_URL) bootstrapChannel()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                closePort()
                ready = false
                onReadyChanged(false)
                onRuntimeFailure(null, null)
                return true
            }
        }
        loadUrl(CHART_URL)
    }

    private fun bootstrapChannel() {
        closePort()
        val ports = WebViewCompat.createWebMessageChannel(webView)
        nativePort = ports[0].apply {
            setWebMessageCallback(object : WebMessagePortCompat.WebMessageCallbackCompat() {
                override fun onMessage(port: WebMessagePortCompat, message: WebMessageCompat?) {
                    val body = message?.data ?: return
                    val envelope = runCatching { JSONObject(body) }.getOrNull() ?: return
                    if (envelope.optInt("v", -1) != 1 ||
                        envelope.optString("pageInstanceId") != pageInstanceId
                    ) return
                    val generation = envelope.optLong("generation", -1)
                    val renderRevision = envelope.optLong("renderRevision", -1)
                    when (envelope.optString("type")) {
                        "chart.ready" -> {
                            handler.removeCallbacks(handshakeTimeout)
                            handshakeReloads = 0
                            ready = true
                            onReadyChanged(true)
                        }
                        "chart.renderAck" -> {
                            val payload = envelope.optJSONObject("payload")
                            onRenderAck(
                                generation,
                                renderRevision,
                                payload?.optSignalList().orEmpty(),
                                payload?.takeIf { !it.isNull("latestCandleTime") }?.optLong("latestCandleTime"),
                            )
                            post("chart.requestViewport", generation, renderRevision, JSONObject())
                        }
                        "chart.viewportChanged", "chart.viewportCaptured" -> {
                            val payload = envelope.optJSONObject("payload") ?: return
                            val from = payload.optDouble("logicalFrom", Double.NaN)
                            val to = payload.optDouble("logicalTo", Double.NaN)
                            if (from.isFinite() && to.isFinite() && from < to) {
                                onViewport(generation, renderRevision, from, to)
                            }
                        }
                        "chart.error" -> {
                            val payload = envelope.optJSONObject("payload") ?: return
                            if (isStableChartError(payload.optString("stage"), payload.optString("code"))) {
                                onRuntimeFailure(generation, renderRevision)
                            }
                        }
                    }
                }
            })
        }
        val bootstrap = JSONObject()
            .put("v", 1)
            .put("type", "chart.bootstrap")
            .put("pageInstanceId", pageInstanceId)
            .toString()
        WebViewCompat.postWebMessage(
            webView,
            WebMessageCompat(bootstrap, arrayOf(ports[1])),
            APP_ASSET_ORIGIN,
        )
        post("chart.hello", generation = 0, renderRevision = 0, payload = JSONObject())
        handler.removeCallbacks(handshakeTimeout)
        handler.postDelayed(handshakeTimeout, 5_000)
    }

    fun render(state: ChartUiState, prepared: PreparedChartRender?) {
        if (!ready) return
        if (prepared == null || prepared.generation != state.requestGeneration ||
            prepared.renderRevision != state.renderRevision
        ) return
        val identity = state.requestGeneration to state.renderRevision
        if (identity == lastRenderedIdentity || state.renderRevision <= 0) return
        postPrepared("chart.renderSnapshot", prepared)
        lastRenderedIdentity = identity
        onRenderDispatched(state.requestGeneration, state.renderRevision)
    }

    fun setInteractionEnabled(enabled: Boolean, state: ChartUiState) {
        if (!ready || lastInteractionEnabled == enabled) return
        post(
            "chart.setInteractionEnabled",
            state.requestGeneration,
            state.renderRevision,
            JSONObject().put("enabled", enabled),
        )
        lastInteractionEnabled = enabled
    }

    fun dispose(state: ChartUiState) {
        disposed = true
        handler.removeCallbacks(handshakeTimeout)
        if (ready) {
            post("chart.dispose", state.requestGeneration, state.renderRevision, JSONObject())
        }
        closePort()
        webView.stopLoading()
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        webView.destroy()
    }

    private fun post(type: String, generation: Long, renderRevision: Long, payload: JSONObject) {
        val message = JSONObject()
            .put("v", 1)
            .put("type", type)
            .put("id", UUID.randomUUID().toString())
            .put("pageInstanceId", pageInstanceId)
            .put("generation", generation)
            .put("renderRevision", renderRevision)
            .put("payload", payload)
            .toString()
        nativePort?.postMessage(WebMessageCompat(message))
    }

    private fun postPrepared(type: String, prepared: PreparedChartRender) {
        val message = buildString(prepared.payloadJson.length + 256) {
            append("{\"v\":1,\"type\":\"").append(type)
            append("\",\"id\":\"").append(UUID.randomUUID())
            append("\",\"pageInstanceId\":\"").append(pageInstanceId)
            append("\",\"generation\":").append(prepared.generation)
            append(",\"renderRevision\":").append(prepared.renderRevision)
            append(",\"payload\":").append(prepared.payloadJson).append('}')
        }
        nativePort?.postMessage(WebMessageCompat(message))
    }

    private fun closePort() {
        nativePort?.close()
        nativePort = null
        lastRenderedIdentity = null
        lastInteractionEnabled = null
    }
}

private val STABLE_CHART_ERRORS = mapOf(
    "decode" to setOf("malformed_json"),
    "validate" to setOf("unsupported_version", "invalid_envelope", "invalid_snapshot", "payload_too_large"),
    "algorithm" to setOf("algorithm_failed", "non_finite_output"),
    "render" to setOf("chart_init_failed", "chart_update_failed"),
    "restore" to setOf("invalid_viewport", "restore_failed"),
)

private fun isStableChartError(stage: String, code: String): Boolean =
    code in (STABLE_CHART_ERRORS[stage] ?: emptySet())

private fun AlgorithmSettings.toJson(): JSONObject = JSONObject()
    .put("momStart", momStart)
    .put("momEnd", momEnd)
    .put("zLength", zLength)
    .put("extThresh", extremeThreshold)
    .put("smoothLen", smoothLength)
    .put("bearWmaLength", bearWmaLength)

private fun JSONObject.optSignalList(): List<ChartSignal> {
    val items = optJSONArray("signals") ?: return emptyList()
    return buildList {
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val id = item.optString("id").takeIf(String::isNotBlank) ?: continue
            val text = item.optString("text").takeIf(String::isNotBlank) ?: continue
            val time = item.optLong("time", -1)
            val score = item.optDouble("score", Double.NaN)
            val close = item.optDouble("close", Double.NaN)
            if (time < 0 || !score.isFinite() || !close.isFinite()) continue
            add(
                ChartSignal(
                    id = id,
                    time = time,
                    text = text,
                    type = item.optString("type", "unknown"),
                    strength = item.optString("strength", "unknown"),
                    score = score,
                    close = close,
                ),
            )
        }
    }
}
