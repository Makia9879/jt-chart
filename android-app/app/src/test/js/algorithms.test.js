"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const {
  buildBottomMarkers,
  buildSignalMarkers,
  buildTopMarkers,
  calculateBearMarketOverlay,
  calculateJTRegimeOscillator,
} = require("../../main/assets/chart/algorithms.js");

function fixture(name) {
  const file = path.join(__dirname, "../resources/fixtures", name);
  return JSON.parse(fs.readFileSync(file, "utf8"));
}

function closeTo(actual, expected) {
  const tolerance = Math.max(1e-10, Math.abs(expected) * 1e-10);
  assert.ok(Math.abs(actual - expected) <= tolerance, `${actual} != ${expected}`);
}

test("JT Regime v1 fixture preserves scores, extremes, and markers", () => {
  const regression = fixture("jt-regime-v1.json");
  const { candles, settings, expected } = regression;

  const oscillator = calculateJTRegimeOscillator(candles, settings);
  const markers = buildBottomMarkers(oscillator, candles);
  const indexed = new Map(oscillator.map((item) => [item.index, item]));

  assert.deepEqual(
    { index: oscillator[0].index, time: oscillator[0].time, value: oscillator[0].value },
    expected.first,
  );
  closeTo(indexed.get(39).value, expected.byIndex[39].value);
  closeTo(indexed.get(40).z1, expected.byIndex[40].z1);
  assert.equal(indexed.get(40).isExtremeDown, expected.byIndex[40].isExtremeDown);
  closeTo(indexed.get(40).value, expected.byIndex[40].value);
  closeTo(indexed.get(47).value, expected.byIndex[47].value);

  assert.deepEqual(
    markers.map(({ text, time, color, close }) => ({ text, time, color, close })),
    expected.markers.map(({ text, time, color, close }) => ({ text, time, color, close })),
  );
  expected.markers.forEach((marker, index) => closeTo(markers[index].score, marker.score));
});

test("bear WMA v1 fixture weights newest value highest and uses strict bear comparison", () => {
  const regression = fixture("wma-v1.json");
  const overlay = calculateBearMarketOverlay(regression.candles, regression.length);

  assert.deepEqual(overlay.map((item) => item.time), regression.expected.map((item) => item.time));
  regression.expected.forEach((expected, index) => {
    closeTo(overlay[index].wma, expected.wmaNumerator / expected.wmaDenominator);
  });
  assert.deepEqual(overlay.map((item) => item.isBear), regression.expected.map((item) => item.isBear));
});

test("top markers flag tentative and confirmed exit signals above bars", () => {
  const candles = Array.from({ length: 12 }, (_, index) => ({
    time: 1700000000 + index * 3600,
    high: 110 - index,
    low: 100 - index,
    close: 105 - index,
  }));
  candles[8].close = 90;
  const oscillator = [
    { index: 0, time: candles[0].time, value: 0.1, isExtremeUp: false, close: candles[0].close },
    { index: 1, time: candles[1].time, value: 0.2, isExtremeUp: false, close: candles[1].close },
    { index: 2, time: candles[2].time, value: 0.5, isExtremeUp: true, close: candles[2].close },
    { index: 3, time: candles[3].time, value: 0.8, isExtremeUp: false, close: candles[3].close },
    { index: 4, time: candles[4].time, value: 0.6, isExtremeUp: false, close: candles[4].close },
    { index: 5, time: candles[5].time, value: 0.4, isExtremeUp: false, close: candles[5].close },
    { index: 6, time: candles[6].time, value: 0.2, isExtremeUp: false, close: candles[6].close },
    { index: 7, time: candles[7].time, value: 0.1, isExtremeUp: false, close: candles[7].close },
    { index: 8, time: candles[8].time, value: -0.2, isExtremeUp: false, close: candles[8].close },
  ];

  const markers = buildTopMarkers(oscillator, candles);

  assert.deepEqual(
    markers.map(({ text, position, shape, type, strength }) => ({ text, position, shape, type, strength })),
    [
      { text: "试探逃顶", position: "aboveBar", shape: "arrowDown", type: "topTentative", strength: "tentative" },
      { text: "确认逃顶", position: "aboveBar", shape: "arrowDown", type: "topConfirmed", strength: "confirmed" },
    ],
  );
  assert.equal(buildSignalMarkers(oscillator, candles).at(-1).text, "确认逃顶");
});
