"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");

const { createChartRenderer } = require("../../main/assets/chart/renderer.js");

function fakeChart() {
  let rangeListener;
  const setRanges = [];
  const timeScale = {
    subscribeVisibleLogicalRangeChange(listener) { rangeListener = listener; },
    unsubscribeVisibleLogicalRangeChange() {},
    setVisibleLogicalRange(range) { setRanges.push(range); },
    getVisibleLogicalRange() { return { from: 2, to: 8 }; },
  };
  return {
    chart: {
      timeScale() { return timeScale; },
      addCandlestickSeries() { return { setData() {}, setMarkers() {}, applyOptions() {}, priceToCoordinate() {} }; },
      addHistogramSeries() { return { setData() {}, createPriceLine() {} }; },
      applyOptions() {},
      subscribeCrosshairMove() {},
      unsubscribeCrosshairMove() {},
      remove() {},
    },
    emitRange(range) { rangeListener(range); },
    setRanges,
  };
}

test("price and oscillator logical ranges synchronize bidirectionally without recursion", () => {
  const price = fakeChart();
  const oscillator = fakeChart();
  const charts = [price, oscillator];
  const renderer = createChartRenderer({
    LightweightCharts: {
      createChart() { return charts.shift().chart; },
      CrosshairMode: { Normal: 0 },
      LineStyle: { Dashed: 2 },
    },
    priceContainer: { clientWidth: 400, clientHeight: 300, addEventListener() {}, removeEventListener() {} },
    oscillatorContainer: { clientWidth: 400, clientHeight: 160 },
    overlayCanvas: { getContext() { return null; } },
    ResizeObserver: class { observe() {} disconnect() {} },
    requestAnimationFrame(callback) { callback(); return 1; },
  });
  renderer.initialize();

  price.emitRange({ from: 1, to: 9 });
  oscillator.emitRange({ from: 3, to: 7 });

  assert.deepEqual(oscillator.setRanges, [{ from: 1, to: 9 }]);
  assert.deepEqual(price.setRanges, [{ from: 3, to: 7 }]);
});
