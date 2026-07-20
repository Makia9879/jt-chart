"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const { assertOverlayLayering, createChartRenderer } = require("../../main/assets/chart/renderer.js");

function fakeChart(options = {}) {
  let rangeListener;
  let crosshairListener;
  let currentRange = { from: 2, to: 8 };
  const setRanges = [];
  const crosshairPositions = [];
  const clearedCrosshairs = [];
  const timeScale = {
    subscribeVisibleLogicalRangeChange(listener) { rangeListener = listener; },
    unsubscribeVisibleLogicalRangeChange() {},
    setVisibleLogicalRange(range) { currentRange = range; setRanges.push(range); },
    getVisibleLogicalRange() { return currentRange; },
    fitContent() {},
    scrollToRealTimeCalls: 0,
    scrollToRealTime() { this.scrollToRealTimeCalls += 1; },
    timeToCoordinate(time) { return options.timeToCoordinate ? options.timeToCoordinate(time) : time * 10; },
  };
  const candleSeries = {
    setData() {},
    setMarkers() {},
    applyOptions() {},
    priceToCoordinate(price) { return options.priceToCoordinate ? options.priceToCoordinate(price) : price * 10; },
  };
  return {
    chart: {
      timeScale() { return timeScale; },
      addCandlestickSeries() { return candleSeries; },
      addHistogramSeries() { return { setData() {}, createPriceLine() {} }; },
      applyOptions() {},
      subscribeCrosshairMove(listener) { crosshairListener = listener; },
      unsubscribeCrosshairMove() {},
      setCrosshairPosition(price, time, series) { crosshairPositions.push({ price, time, series }); },
      clearCrosshairPosition() { clearedCrosshairs.push(true); },
      remove() {},
    },
    emitRange(range) { rangeListener(range); },
    emitCrosshair(param) { crosshairListener(param); },
    clearedCrosshairs,
    crosshairPositions,
    setRanges,
    timeScale,
  };
}

function createRenderer(charts, overrides = {}) {
  return createChartRenderer({
    LightweightCharts: {
      createChart() { return charts.shift().chart; },
      CrosshairMode: { Normal: 0 },
      LineStyle: { Dashed: 2 },
    },
    priceContainer: { clientWidth: 400, clientHeight: 300, addEventListener() {}, removeEventListener() {} },
    oscillatorContainer: { clientWidth: 400, clientHeight: 160, addEventListener() {}, removeEventListener() {} },
    overlayCanvas: { getContext() { return null; } },
    ResizeObserver: class { observe() {} disconnect() {} },
    requestAnimationFrame(callback) { callback(); return 1; },
    ...overrides,
  });
}

function overlayDeclarations() {
  const cssPath = path.join(__dirname, "../../main/assets/chart/chart.css");
  const css = fs.readFileSync(cssPath, "utf8");
  const body = css.match(/#bear-overlay\s*\{([^}]*)\}/)?.[1];
  assert.ok(body, "#bear-overlay rule must exist");
  return Object.fromEntries(Array.from(body.matchAll(/([\w-]+)\s*:\s*([^;]+);/g), ([, name, value]) => [
    name.replace(/-([a-z])/g, (_, letter) => letter.toUpperCase()),
    value.trim(),
  ]));
}

function recordingContext() {
  const fillCalls = [];
  const strokeCalls = [];
  const context = {
    fillStyle: "",
    strokeStyle: "",
    lineWidth: 0,
    lineJoin: "",
    lineCap: "",
    setTransform() {},
    clearRect() {},
    save() {},
    beginPath() {},
    rect() {},
    clip() {},
    moveTo() {},
    lineTo() {},
    closePath() {},
    fill() { fillCalls.push({ fillStyle: this.fillStyle }); },
    stroke() { strokeCalls.push({ strokeStyle: this.strokeStyle, lineWidth: this.lineWidth }); },
    restore() {},
  };
  return { context, fillCalls, strokeCalls };
}

function snapshot(count, viewPolicy) {
  const candles = Array.from({ length: count }, (_, time) => ({ time, open: 1, high: 2, low: 1, close: 2 }));
  return {
    candles,
    markers: [],
    oscillator: candles.map(({ time }) => ({ time, value: 0, color: "#fff" })),
    bearOverlay: [],
    pricePrecision: 2,
    viewPolicy,
    viewport: null,
  };
}

test("price and oscillator logical ranges synchronize bidirectionally without recursion", () => {
  const price = fakeChart();
  const oscillator = fakeChart();
  const renderer = createRenderer([price, oscillator]);
  renderer.initialize();

  price.emitRange({ from: 1, to: 9 });
  oscillator.emitRange({ from: 3, to: 7 });

  assert.deepEqual(oscillator.setRanges, [{ from: 1, to: 9 }]);
  assert.deepEqual(price.setRanges, [{ from: 3, to: 7 }]);
});

test("a viewport within two candles of the old right edge follows realtime", () => {
  const price = fakeChart();
  const oscillator = fakeChart();
  const renderer = createRenderer([price, oscillator]);
  renderer.initialize();
  renderer.renderSnapshot(snapshot(10, "fitContent"));
  price.timeScale.setVisibleLogicalRange({ from: 3, to: 7 });

  renderer.renderSnapshot(snapshot(12, "preserveOrFollowRight"));

  assert.equal(price.timeScale.scrollToRealTimeCalls, 1);
  assert.equal(oscillator.timeScale.scrollToRealTimeCalls, 1);
});

test("price crosshair drives oscillator crosshair at the same time", () => {
  const price = fakeChart();
  const oscillator = fakeChart();
  const renderer = createRenderer([price, oscillator]);
  renderer.initialize();
  renderer.renderSnapshot(snapshot(4, "fitContent"));

  price.emitCrosshair({ time: 2 });

  assert.deepEqual(oscillator.crosshairPositions.map(({ price, time }) => ({ price, time })), [
    { price: 0, time: 2 },
  ]);
});

test("oscillator crosshair drives price crosshair at the same time", () => {
  const price = fakeChart();
  const oscillator = fakeChart();
  const renderer = createRenderer([price, oscillator]);
  renderer.initialize();
  renderer.renderSnapshot(snapshot(4, "fitContent"));

  oscillator.emitCrosshair({ time: 3 });

  assert.deepEqual(price.crosshairPositions.map(({ price, time }) => ({ price, time })), [
    { price: 2, time: 3 },
  ]);
});

test("missing crosshair time clears the paired chart", () => {
  const price = fakeChart();
  const oscillator = fakeChart();
  const renderer = createRenderer([price, oscillator]);
  renderer.initialize();
  renderer.renderSnapshot(snapshot(4, "fitContent"));

  price.emitCrosshair({});
  oscillator.emitCrosshair({});

  assert.equal(oscillator.clearedCrosshairs.length, 1);
  assert.equal(price.clearedCrosshairs.length, 1);
});

test("a non-empty bear overlay fills bear regions and strokes the white 2px WMA", () => {
  const price = fakeChart();
  const oscillator = fakeChart();
  const drawing = recordingContext();
  const renderer = createRenderer([price, oscillator], {
    overlayCanvas: { getContext() { return drawing.context; } },
  });
  renderer.initialize();
  const rendered = snapshot(3, "fitContent");
  rendered.bearOverlay = [
    { time: 0, close: 8, wma: 10, isBear: true },
    { time: 1, close: 9, wma: 10, isBear: true },
    { time: 2, close: 11, wma: 10, isBear: false },
  ];

  renderer.renderSnapshot(rendered);

  assert.ok(drawing.fillCalls.length > 0);
  assert.ok(drawing.fillCalls.every(({ fillStyle }) => fillStyle === "rgba(0, 128, 0, 0.30)"));
  assert.deepEqual(drawing.strokeCalls, [{ strokeStyle: "#ffffff", lineWidth: 2 }]);
});

test("the shipped overlay style is above Lightweight Charts canvases and passes pointer events through", () => {
  const declarations = overlayDeclarations();
  const overlayCanvas = { computedStyle: declarations };
  const priceContainer = {
    querySelectorAll(selector) {
      assert.equal(selector, "canvas");
      return [
        { computedStyle: { zIndex: "1" } },
        { computedStyle: { zIndex: "2" } },
      ];
    },
  };
  const readComputedStyle = (element) => element.computedStyle;

  assert.doesNotThrow(() => assertOverlayLayering(overlayCanvas, priceContainer, readComputedStyle));
  assert.ok(Number(declarations.zIndex) > 2);
  assert.equal(declarations.pointerEvents, "none");
});

test("renderer initialization rejects an obscured or interactive overlay", () => {
  for (const computedStyle of [
    { zIndex: "auto", pointerEvents: "none" },
    { zIndex: "2", pointerEvents: "none" },
    { zIndex: "3", pointerEvents: "auto" },
  ]) {
    const price = fakeChart();
    const oscillator = fakeChart();
    const priceContainer = {
      clientWidth: 400,
      clientHeight: 300,
      addEventListener() {},
      removeEventListener() {},
      querySelectorAll() { return [{ computedStyle: { zIndex: "2" } }]; },
    };
    const renderer = createRenderer([price, oscillator], {
      priceContainer,
      overlayCanvas: { computedStyle, getContext() { return null; } },
      getComputedStyle(element) { return element.computedStyle; },
    });

    assert.throws(() => renderer.initialize(), /bear_overlay_/);
  }
});
