"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");

const { createChartRenderer } = require("../../main/assets/chart/renderer.js");

function fakeChart() {
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
  };
  return {
    chart: {
      timeScale() { return timeScale; },
      addCandlestickSeries() { return { setData() {}, setMarkers() {}, applyOptions() {}, priceToCoordinate() {} }; },
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

function createRenderer(charts) {
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
  });
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
