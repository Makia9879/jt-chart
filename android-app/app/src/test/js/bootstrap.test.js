"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const { acceptsNativePortEvent, viewportHeightCss } = require("../../main/assets/chart/bootstrap.js");

test("native bootstrap accepts only the source-less single-port event before connection", () => {
  assert.equal(acceptsNativePortEvent({ origin: "", ports: [{}] }, false), true);
  assert.equal(acceptsNativePortEvent({ origin: "https://evil.example", ports: [{}] }, false), false);
  assert.equal(acceptsNativePortEvent({ origin: "", ports: [] }, false), false);
  assert.equal(acceptsNativePortEvent({ origin: "", ports: [{}] }, true), false);
});

test("WebView viewport height is converted to an explicit positive pixel height", () => {
  assert.equal(viewportHeightCss(856.6), "857px");
  assert.equal(viewportHeightCss(0), null);
  assert.equal(viewportHeightCss(Number.NaN), null);
});
