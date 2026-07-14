"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const {
  buildBottomMarkers,
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
