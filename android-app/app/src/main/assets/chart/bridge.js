(function (root, factory) {
  const algorithms = typeof module === "object" && module.exports
    ? require("./algorithms.js")
    : root.JTChartAlgorithms;
  const api = factory(algorithms);
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.JTChartBridge = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function (algorithms) {
  "use strict";

  const PROTOCOL_VERSION = 1;
  const DECIMAL_PATTERN = /^[0-9]+(?:\.[0-9]+)?$/;
  const FINGERPRINT_PATTERN = /^[0-9a-f]{64}$/;
  const SOURCES = new Set(["spot", "futures", "bybitSpot", "bybitLinear", "bitgetSpot", "bitgetFutures"]);
  const INTERVALS = new Set(["1m", "5m", "15m", "1h", "4h", "1d", "1w"]);
  const VIEW_POLICIES = new Set(["fitContent", "restoreExact", "preserveOrFollowRight"]);
  const MESSAGE_LIMIT_BYTES = 2 * 1024 * 1024;
  const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

  function responseFor(message, type, payload) {
    return {
      v: PROTOCOL_VERSION,
      type,
      id: message.id,
      pageInstanceId: message.pageInstanceId,
      generation: message.generation,
      renderRevision: message.renderRevision,
      payload,
    };
  }

  function isIntegerIn(value, minimum, maximum) {
    return Number.isInteger(value) && value >= minimum && value <= maximum;
  }

  function parseDecimal(text) {
    if (typeof text !== "string" || text.length > 128 || !DECIMAL_PATTERN.test(text)) {
      throw new Error("invalid_snapshot");
    }
    const value = Number(text);
    if (!Number.isFinite(value) || value <= 0) throw new Error("invalid_snapshot");
    return value;
  }

  function inferPrecision(rows) {
    let precision = 0;
    rows.forEach((row) => {
      for (let index = 1; index <= 4; index += 1) {
        const separator = row[index].indexOf(".");
        const decimals = separator < 0 ? 0 : row[index].length - separator - 1;
        precision = Math.max(precision, decimals);
      }
    });
    return Math.min(12, precision);
  }

  function validateQueryKey(queryKey) {
    return queryKey
      && SOURCES.has(queryKey.source)
      && typeof queryKey.symbol === "string"
      && /^[A-Z0-9]{3,30}$/.test(queryKey.symbol)
      && INTERVALS.has(queryKey.interval)
      && isIntegerIn(queryKey.limit, 120, 1000);
  }

  function validateSettings(settings, candleCount) {
    return settings
      && isIntegerIn(settings.momStart, 2, Number.MAX_SAFE_INTEGER)
      && isIntegerIn(settings.momEnd, 3, Number.MAX_SAFE_INTEGER)
      && settings.momEnd > settings.momStart
      && isIntegerIn(settings.zLength, 10, Number.MAX_SAFE_INTEGER)
      && Number.isFinite(settings.extThresh)
      && settings.extThresh >= 0.5
      && settings.extThresh <= 5
      && isIntegerIn(settings.smoothLen, 1, Number.MAX_SAFE_INTEGER)
      && isIntegerIn(settings.bearWmaLength, 10, 990)
      && candleCount > settings.bearWmaLength;
  }

  function prepareSnapshot(payload) {
    if (!payload
      || !validateQueryKey(payload.queryKey)
      || !FINGERPRINT_PATTERN.test(payload.datasetFingerprint || "")
      || !Array.isArray(payload.candles)
      || payload.candles.length === 0
      || payload.candles.length > 1000
      || !VIEW_POLICIES.has(payload.viewPolicy)
      || !validateSettings(payload.settings, payload.candles.length)) {
      throw new Error("invalid_snapshot");
    }

    let previousTime = -1;
    const candles = payload.candles.map((row) => {
      if (!Array.isArray(row) || row.length !== 5 || !Number.isSafeInteger(row[0]) || row[0] <= previousTime) {
        throw new Error("invalid_snapshot");
      }
      previousTime = row[0];
      const open = parseDecimal(row[1]);
      const high = parseDecimal(row[2]);
      const low = parseDecimal(row[3]);
      const close = parseDecimal(row[4]);
      if (high < Math.max(open, close) || low > Math.min(open, close) || high < low) {
        throw new Error("invalid_snapshot");
      }
      return { time: Math.floor(row[0] / 1000), open, high, low, close };
    });

    const oscillator = algorithms.calculateJTRegimeOscillator(candles, payload.settings);
    const markers = algorithms.buildBottomMarkers(oscillator, candles);
    const bearOverlay = algorithms.calculateBearMarketOverlay(candles, payload.settings.bearWmaLength);
    const outputs = oscillator.flatMap((item) => [item.value, item.z1, item.zMom])
      .concat(bearOverlay.flatMap((item) => [item.close, item.wma]));
    if (!outputs.every(Number.isFinite)) throw new Error("non_finite_output");

    return {
      queryKey: payload.queryKey,
      datasetFingerprint: payload.datasetFingerprint,
      candles,
      pricePrecision: inferPrecision(payload.candles),
      oscillator,
      markers,
      bearOverlay,
      viewPolicy: payload.viewPolicy,
      viewport: payload.viewport,
    };
  }

  function createRuntime({ pageInstanceId, renderer, send }) {
    let ready = false;
    let latestGeneration = -1;
    let latestRenderRevision = -1;
    let latestRenderMessage = null;
    let latestSnapshotIdentity = null;

    if (typeof renderer.setViewportChangedListener === "function") {
      renderer.setViewportChangedListener((viewport) => {
        if (!latestRenderMessage || !latestSnapshotIdentity) return;
        if (!viewport || !Number.isFinite(viewport.logicalFrom) || !Number.isFinite(viewport.logicalTo)) return;
        send(responseFor(latestRenderMessage, "chart.viewportChanged", {
          ...latestSnapshotIdentity,
          logicalFrom: viewport.logicalFrom,
          logicalTo: viewport.logicalTo,
        }));
      });
    }

    function genericError(stage, code) {
      send({
        v: PROTOCOL_VERSION,
        type: "chart.error",
        id: "00000000-0000-4000-8000-000000000000",
        pageInstanceId,
        generation: 0,
        renderRevision: 0,
        payload: { stage, code },
      });
    }

    function receive(raw) {
      if (typeof raw !== "string") {
        genericError("decode", "malformed_json");
        return;
      }
      if (new TextEncoder().encode(raw).byteLength > MESSAGE_LIMIT_BYTES) {
        genericError("validate", "payload_too_large");
        return;
      }
      let message;
      try {
        message = JSON.parse(raw);
      } catch (_error) {
        genericError("decode", "malformed_json");
        return;
      }
      if (!message || message.pageInstanceId !== pageInstanceId) return;
      if (message.v !== PROTOCOL_VERSION) {
        send(responseFor(message, "chart.error", { stage: "validate", code: "unsupported_version" }));
        return;
      }
      if (!UUID_PATTERN.test(message.id || "")
        || !Number.isSafeInteger(message.generation)
        || message.generation < 0
        || !Number.isSafeInteger(message.renderRevision)
        || message.renderRevision < 0
        || !message.payload
        || typeof message.payload !== "object"
        || Array.isArray(message.payload)) {
        send(responseFor(message, "chart.error", { stage: "validate", code: "invalid_envelope" }));
        return;
      }
      if (message.type === "chart.hello") {
        if (!ready) {
          try {
            renderer.initialize();
          } catch (_error) {
            send(responseFor(message, "chart.error", { stage: "render", code: "chart_init_failed" }));
            return;
          }
          ready = true;
        }
        send(responseFor(message, "chart.ready", {
          lightweightChartsVersion: "4.2.3",
          capabilities: ["logicalRange", "bearOverlay", "atomicSnapshot"],
        }));
      } else if (message.type === "chart.renderSnapshot" && ready) {
        const stale = message.generation < latestGeneration
          || (message.generation === latestGeneration && message.renderRevision <= latestRenderRevision);
        if (stale) return;
        let snapshot;
        try {
          snapshot = prepareSnapshot(message.payload);
        } catch (error) {
          const rawCode = error instanceof Error ? error.message : "algorithm_failed";
          const code = ["invalid_snapshot", "non_finite_output"].includes(rawCode)
            ? rawCode
            : "algorithm_failed";
          const stage = code === "invalid_snapshot" ? "validate" : "algorithm";
          send(responseFor(message, "chart.error", { stage, code }));
          return;
        }
        try {
          renderer.renderSnapshot(snapshot);
          latestGeneration = message.generation;
          latestRenderRevision = message.renderRevision;
          latestRenderMessage = message;
          latestSnapshotIdentity = {
            queryKey: snapshot.queryKey,
            datasetFingerprint: snapshot.datasetFingerprint,
          };
          const latest = snapshot.oscillator.at(-1);
          const latestMarker = snapshot.markers.at(-1);
          send(responseFor(message, "chart.renderAck", {
            queryKey: snapshot.queryKey,
            datasetFingerprint: snapshot.datasetFingerprint,
            candleCount: snapshot.candles.length,
            latestRegime: latest ? latest.value : null,
            markerCount: snapshot.markers.length,
            latestMarkerTime: latestMarker ? latestMarker.time : null,
            isExtremeUp: latest ? latest.isExtremeUp : false,
            isExtremeDown: latest ? latest.isExtremeDown : false,
          }));
        } catch (error) {
          const restoreCode = error instanceof Error && error.message === "invalid_viewport";
          send(responseFor(message, "chart.error", restoreCode
            ? { stage: "restore", code: "invalid_viewport" }
            : { stage: "render", code: "chart_update_failed" }));
        }
      } else if (message.type === "chart.setInteractionEnabled" && ready) {
        if (typeof message.payload?.enabled !== "boolean") {
          send(responseFor(message, "chart.error", { stage: "validate", code: "invalid_envelope" }));
          return;
        }
        renderer.setInteractionEnabled(message.payload.enabled);
      } else if (message.type === "chart.requestViewport" && ready) {
        const viewport = renderer.captureViewport();
        if (!viewport
          || !Number.isFinite(viewport.logicalFrom)
          || !Number.isFinite(viewport.logicalTo)
          || viewport.logicalFrom >= viewport.logicalTo) {
          send(responseFor(message, "chart.error", { stage: "restore", code: "invalid_viewport" }));
          return;
        }
        send(responseFor(message, "chart.viewportCaptured", {
          ...(latestSnapshotIdentity || {}),
          ...viewport,
        }));
      } else if (message.type === "chart.dispose") {
        dispose();
      }
    }

    function dispose() {
      renderer.dispose();
      ready = false;
      latestRenderMessage = null;
      latestSnapshotIdentity = null;
    }

    return { dispose, receive };
  }

  return { createRuntime };
});
