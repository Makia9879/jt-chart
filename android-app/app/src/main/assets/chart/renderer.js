(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.JTChartRenderer = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  const CHART_OPTIONS = {
    layout: {
      background: { color: "#101216" },
      textColor: "#c9d1d9",
      fontFamily: "system-ui, -apple-system, Segoe UI, sans-serif",
      attributionLogo: false,
    },
    grid: { vertLines: { color: "#20252d" }, horzLines: { color: "#20252d" } },
    rightPriceScale: { borderColor: "#2c323c" },
    timeScale: { borderColor: "#2c323c", timeVisible: true, secondsVisible: false },
  };

  function formatShanghai(time, full) {
    const options = full
      ? { year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false }
      : { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false };
    return new Intl.DateTimeFormat("zh-CN", { ...options, timeZone: "Asia/Shanghai" })
      .format(new Date(Number(time) * 1000));
  }

  function createChartRenderer(dependencies) {
    const {
      LightweightCharts,
      priceContainer,
      oscillatorContainer,
      overlayCanvas,
      ResizeObserver,
      requestAnimationFrame,
    } = dependencies;
    let priceChart;
    let oscillatorChart;
    let candleSeries;
    let oscillatorSeries;
    let resizeObserver;
    let currentSnapshot;
    let changingPrice = false;
    let changingOscillator = false;
    let overlayFrame = null;
    let priceRangeListener;
    let oscillatorRangeListener;
    let viewportChangedListener = function () {};
    let rendering = false;
    const inputEvents = ["pointermove", "pointerup", "wheel", "dblclick"];

    function chartOptions(container, extra) {
      return {
        ...CHART_OPTIONS,
        ...extra,
        width: container.clientWidth,
        height: container.clientHeight,
        crosshair: { mode: LightweightCharts.CrosshairMode.Normal },
        localization: { locale: "zh-CN", timeFormatter: (time) => formatShanghai(time, true) },
        timeScale: { ...CHART_OPTIONS.timeScale, tickMarkFormatter: (time) => formatShanghai(time, false) },
      };
    }

    function requestOverlayDraw() {
      if (overlayFrame != null) return;
      overlayFrame = requestAnimationFrame(() => {
        overlayFrame = null;
        drawOverlay();
      });
    }

    function initialize() {
      if (priceChart || !LightweightCharts) return;
      if (typeof LightweightCharts.version === "function" && LightweightCharts.version() !== "4.2.3") {
        throw new Error("Unexpected Lightweight Charts version");
      }
      priceChart = LightweightCharts.createChart(priceContainer, chartOptions(priceContainer));
      oscillatorChart = LightweightCharts.createChart(oscillatorContainer, chartOptions(oscillatorContainer, {
        rightPriceScale: { borderColor: "#2c323c", scaleMargins: { top: 0.12, bottom: 0.12 } },
      }));
      candleSeries = priceChart.addCandlestickSeries({
        upColor: "#00b050", downColor: "#ff3b30",
        borderUpColor: "#00b050", borderDownColor: "#ff3b30",
        wickUpColor: "#00b050", wickDownColor: "#ff3b30",
      });
      oscillatorSeries = oscillatorChart.addHistogramSeries({
        priceFormat: { type: "price", precision: 2, minMove: 0.01 }, base: 0,
      });
      oscillatorSeries.createPriceLine({
        price: 0, color: "#7c8491", lineWidth: 1,
        lineStyle: LightweightCharts.LineStyle.Dashed, axisLabelVisible: true, title: "Zero",
      });

      priceRangeListener = (range) => {
        if (!range || changingOscillator) return;
        changingPrice = true;
        oscillatorChart.timeScale().setVisibleLogicalRange(range);
        changingPrice = false;
        requestOverlayDraw();
        if (!rendering) viewportChangedListener({ logicalFrom: range.from, logicalTo: range.to });
      };
      oscillatorRangeListener = (range) => {
        if (!range || changingPrice) return;
        changingOscillator = true;
        priceChart.timeScale().setVisibleLogicalRange(range);
        changingOscillator = false;
        if (!rendering) viewportChangedListener({ logicalFrom: range.from, logicalTo: range.to });
      };
      priceChart.timeScale().subscribeVisibleLogicalRangeChange(priceRangeListener);
      oscillatorChart.timeScale().subscribeVisibleLogicalRangeChange(oscillatorRangeListener);
      priceChart.subscribeCrosshairMove(requestOverlayDraw);
      inputEvents.forEach((name) => priceContainer.addEventListener(name, requestOverlayDraw, { passive: true }));
      resizeObserver = new ResizeObserver(() => {
        priceChart.applyOptions({ width: priceContainer.clientWidth, height: priceContainer.clientHeight });
        oscillatorChart.applyOptions({ width: oscillatorContainer.clientWidth, height: oscillatorContainer.clientHeight });
        requestOverlayDraw();
      });
      resizeObserver.observe(priceContainer);
      resizeObserver.observe(oscillatorContainer);
    }

    function clampRange(viewport, candleCount) {
      if (!viewport || !Number.isFinite(viewport.logicalFrom) || !Number.isFinite(viewport.logicalTo)) return null;
      const from = Math.max(0, Math.min(candleCount - 1, viewport.logicalFrom));
      const to = Math.max(0, Math.min(candleCount - 1, viewport.logicalTo));
      return from < to ? { from, to } : null;
    }

    function applyData(snapshot) {
      candleSeries.applyOptions({
        priceFormat: {
          type: "price",
          precision: snapshot.pricePrecision,
          minMove: Number(`1e-${snapshot.pricePrecision}`),
        },
      });
      candleSeries.setData(snapshot.candles);
      candleSeries.setMarkers(snapshot.markers);
      oscillatorSeries.setData(snapshot.oscillator.map(({ time, value, color }) => ({ time, value, color })));
    }

    function applyViewPolicy(snapshot, previousRange, followedRight) {
      const priceScale = priceChart.timeScale();
      const oscillatorScale = oscillatorChart.timeScale();
      if (snapshot.viewPolicy === "fitContent") {
        priceScale.fitContent();
        oscillatorScale.fitContent();
        return;
      }
      if (snapshot.viewPolicy === "restoreExact") {
        const restored = clampRange(snapshot.viewport, snapshot.candles.length);
        if (!restored) throw new Error("invalid_viewport");
        priceScale.setVisibleLogicalRange(restored);
        oscillatorScale.setVisibleLogicalRange(restored);
        return;
      }
      if (followedRight && typeof priceScale.scrollToRealTime === "function") {
        priceScale.scrollToRealTime();
        oscillatorScale.scrollToRealTime();
        return;
      }
      const preserved = previousRange && clampRange({
        logicalFrom: previousRange.from,
        logicalTo: previousRange.to,
      }, snapshot.candles.length);
      if (preserved) {
        priceScale.setVisibleLogicalRange(preserved);
        oscillatorScale.setVisibleLogicalRange(preserved);
      } else {
        priceScale.fitContent();
        oscillatorScale.fitContent();
      }
    }

    function renderSnapshot(snapshot) {
      const previousRange = currentSnapshot ? priceChart.timeScale().getVisibleLogicalRange() : null;
      const followedRight = Boolean(previousRange && currentSnapshot
        && previousRange.to >= currentSnapshot.candles.length - 1 - 0.5);
      rendering = true;
      try {
        applyData(snapshot);
        applyViewPolicy(snapshot, previousRange, followedRight);
        currentSnapshot = snapshot;
        requestOverlayDraw();
      } catch (error) {
        if (currentSnapshot) applyData(currentSnapshot);
        throw error;
      } finally {
        rendering = false;
      }
    }

    function drawOverlay() {
      const context = overlayCanvas.getContext("2d");
      if (!context || !currentSnapshot) return;
      const width = priceContainer.clientWidth;
      const height = priceContainer.clientHeight;
      const ratio = globalThis.devicePixelRatio || 1;
      overlayCanvas.width = Math.max(1, Math.round(width * ratio));
      overlayCanvas.height = Math.max(1, Math.round(height * ratio));
      context.setTransform(ratio, 0, 0, ratio, 0, 0);
      context.clearRect(0, 0, width, height);
      const points = currentSnapshot.bearOverlay.map((item) => ({
        ...item,
        x: priceChart.timeScale().timeToCoordinate(item.time),
        closeY: candleSeries.priceToCoordinate(item.close),
        wmaY: candleSeries.priceToCoordinate(item.wma),
      })).filter((point) => [point.x, point.closeY, point.wmaY].every(Number.isFinite));
      if (points.length < 1) return;
      context.save();
      context.beginPath();
      context.rect(0, 0, width, height);
      context.clip();
      context.fillStyle = "rgba(0, 128, 0, 0.30)";
      for (let index = 1; index < points.length; index += 1) {
        const previous = points[index - 1];
        const current = points[index];
        const previousDelta = previous.close - previous.wma;
        const currentDelta = current.close - current.wma;
        if (!previous.isBear && !current.isBear) continue;
        let start = previous;
        let end = current;
        if (previous.isBear !== current.isBear) {
          const fraction = -previousDelta / (currentDelta - previousDelta);
          const crossing = {
            x: previous.x + (current.x - previous.x) * fraction,
            closeY: previous.closeY + (current.closeY - previous.closeY) * fraction,
            wmaY: previous.wmaY + (current.wmaY - previous.wmaY) * fraction,
          };
          if (previous.isBear) end = crossing;
          else start = crossing;
        }
        context.beginPath();
        context.moveTo(start.x, start.wmaY);
        context.lineTo(end.x, end.wmaY);
        context.lineTo(end.x, end.closeY);
        context.lineTo(start.x, start.closeY);
        context.closePath();
        context.fill();
      }
      context.beginPath();
      context.moveTo(points[0].x, points[0].wmaY);
      for (let index = 1; index < points.length; index += 1) context.lineTo(points[index].x, points[index].wmaY);
      context.strokeStyle = "#ffffff";
      context.lineWidth = 2;
      context.lineJoin = "round";
      context.lineCap = "round";
      context.stroke();
      context.restore();
    }

    function setInteractionEnabled(enabled) {
      const interaction = {
        handleScroll: enabled,
        handleScale: enabled,
      };
      priceChart.applyOptions(interaction);
      oscillatorChart.applyOptions(interaction);
    }

    function captureViewport() {
      const range = priceChart.timeScale().getVisibleLogicalRange();
      return range ? { logicalFrom: range.from, logicalTo: range.to } : null;
    }

    function dispose() {
      if (!priceChart) return;
      resizeObserver.disconnect();
      priceChart.timeScale().unsubscribeVisibleLogicalRangeChange(priceRangeListener);
      oscillatorChart.timeScale().unsubscribeVisibleLogicalRangeChange(oscillatorRangeListener);
      priceChart.unsubscribeCrosshairMove(requestOverlayDraw);
      inputEvents.forEach((name) => priceContainer.removeEventListener(name, requestOverlayDraw));
      priceChart.remove();
      oscillatorChart.remove();
      priceChart = null;
      oscillatorChart = null;
      currentSnapshot = null;
    }

    function setViewportChangedListener(listener) {
      viewportChangedListener = typeof listener === "function" ? listener : function () {};
    }

    return {
      captureViewport,
      dispose,
      initialize,
      renderSnapshot,
      setInteractionEnabled,
      setViewportChangedListener,
    };
  }

  return { createChartRenderer };
});
