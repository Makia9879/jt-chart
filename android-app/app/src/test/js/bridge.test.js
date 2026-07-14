"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");

const { createRuntime } = require("../../main/assets/chart/bridge.js");

function envelope(type, overrides = {}) {
  return {
    v: 1,
    type,
    id: "11111111-1111-4111-8111-111111111111",
    pageInstanceId: "22222222-2222-4222-8222-222222222222",
    generation: 4,
    renderRevision: 7,
    payload: {},
    ...overrides,
  };
}

function validPayload() {
  const candles = Array.from({ length: 12 }, (_, index) => {
    const close = 100 + index;
    return [1700000000000 + index * 3600000, `${close}.00`, `${close + 1}.00`, `${close - 1}.00`, `${close}.00`];
  });
  return {
    queryKey: { source: "spot", symbol: "BTCUSDT", interval: "1h", limit: 120 },
    datasetFingerprint: "a".repeat(64),
    candles,
    settings: { momStart: 2, momEnd: 3, zLength: 10, extThresh: 2, smoothLen: 4, bearWmaLength: 10 },
    viewPolicy: "fitContent",
    viewport: null,
  };
}

test("matching HELLO initializes charts before READY and echoes request identity", () => {
  const sent = [];
  let initialized = false;
  const runtime = createRuntime({
    pageInstanceId: envelope("").pageInstanceId,
    renderer: {
      initialize() { initialized = true; },
    },
    send(message) { sent.push(message); },
  });

  runtime.receive(JSON.stringify(envelope("chart.hello")));

  assert.equal(initialized, true);
  assert.deepEqual(sent, [{
    ...envelope("chart.ready"),
    payload: {
      lightweightChartsVersion: "4.2.3",
      capabilities: ["logicalRange", "bearOverlay", "atomicSnapshot"],
    },
  }]);
});

test("valid snapshot is calculated, rendered atomically, then acknowledged", () => {
  const sent = [];
  const rendered = [];
  const runtime = createRuntime({
    pageInstanceId: envelope("").pageInstanceId,
    renderer: {
      initialize() {},
      renderSnapshot(snapshot) { rendered.push(snapshot); },
    },
    send(message) { sent.push(message); },
  });
  runtime.receive(JSON.stringify(envelope("chart.hello")));
  sent.length = 0;

  const message = envelope("chart.renderSnapshot", {
    payload: validPayload(),
  });

  runtime.receive(JSON.stringify(message));

  assert.equal(rendered.length, 1);
  assert.equal(rendered[0].candles[0].time, 1700000000);
  assert.equal(rendered[0].pricePrecision, 2);
  assert.equal(rendered[0].oscillator.length, 9);
  assert.deepEqual(sent, [responseForTest(message, "chart.renderAck", {
    queryKey: message.payload.queryKey,
    datasetFingerprint: message.payload.datasetFingerprint,
    candleCount: 12,
    latestRegime: 0,
    markerCount: 0,
    latestMarkerTime: null,
    isExtremeUp: false,
    isExtremeDown: false,
  })]);
});

test("older generation or renderRevision can never replace a newer render", () => {
  const rendered = [];
  const sent = [];
  const runtime = createRuntime({
    pageInstanceId: envelope("").pageInstanceId,
    renderer: {
      initialize() {},
      renderSnapshot(snapshot) { rendered.push(snapshot.datasetFingerprint); },
    },
    send(message) { sent.push(message); },
  });
  runtime.receive(JSON.stringify(envelope("chart.hello")));
  sent.length = 0;

  runtime.receive(JSON.stringify(envelope("chart.renderSnapshot", {
    generation: 5,
    renderRevision: 3,
    payload: validPayload(),
  })));
  runtime.receive(JSON.stringify(envelope("chart.renderSnapshot", {
    generation: 4,
    renderRevision: 99,
    payload: { ...validPayload(), datasetFingerprint: "b".repeat(64) },
  })));
  runtime.receive(JSON.stringify(envelope("chart.renderSnapshot", {
    generation: 5,
    renderRevision: 2,
    payload: { ...validPayload(), datasetFingerprint: "c".repeat(64) },
  })));

  assert.deepEqual(rendered, ["a".repeat(64)]);
  assert.equal(sent.length, 1);
  assert.equal(sent[0].type, "chart.renderAck");
});

function responseForTest(message, type, payload) {
  const { v, id, pageInstanceId, generation, renderRevision } = message;
  return { v, type, id, pageInstanceId, generation, renderRevision, payload };
}

test("invalid snapshot keeps the displayed chart and returns stable validation error", () => {
  const sent = [];
  let renderCount = 0;
  const runtime = createRuntime({
    pageInstanceId: envelope("").pageInstanceId,
    renderer: {
      initialize() {},
      renderSnapshot() { renderCount += 1; },
    },
    send(message) { sent.push(message); },
  });
  runtime.receive(JSON.stringify(envelope("chart.hello")));
  sent.length = 0;
  const message = envelope("chart.renderSnapshot", {
    payload: {
      queryKey: { source: "spot", symbol: "BTCUSDT", interval: "1h", limit: 120 },
      datasetFingerprint: "b".repeat(64),
      candles: [[1700000000000, "100.00", "101.00", "99.00", "1e2"]],
      settings: { momStart: 2, momEnd: 3, zLength: 10, extThresh: 2, smoothLen: 4, bearWmaLength: 10 },
      viewPolicy: "fitContent",
      viewport: null,
    },
  });

  runtime.receive(JSON.stringify(message));

  assert.equal(renderCount, 0);
  assert.deepEqual(sent, [responseForTest(message, "chart.error", {
    stage: "validate",
    code: "invalid_snapshot",
  })]);
});

test("interaction, viewport capture, and dispose delegate through v1 messages", () => {
  const sent = [];
  const interactions = [];
  let disposed = false;
  const runtime = createRuntime({
    pageInstanceId: envelope("").pageInstanceId,
    renderer: {
      initialize() {},
      setInteractionEnabled(enabled) { interactions.push(enabled); },
      captureViewport() { return { logicalFrom: 1.25, logicalTo: 11.5 }; },
      dispose() { disposed = true; },
    },
    send(message) { sent.push(message); },
  });
  runtime.receive(JSON.stringify(envelope("chart.hello")));
  sent.length = 0;

  runtime.receive(JSON.stringify(envelope("chart.setInteractionEnabled", { payload: { enabled: false } })));
  const request = envelope("chart.requestViewport");
  runtime.receive(JSON.stringify(request));
  runtime.receive(JSON.stringify(envelope("chart.dispose")));

  assert.deepEqual(interactions, [false]);
  assert.equal(disposed, true);
  assert.deepEqual(sent, [responseForTest(request, "chart.viewportCaptured", {
    logicalFrom: 1.25,
    logicalTo: 11.5,
  })]);
});
