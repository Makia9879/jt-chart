(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.JTChartAlgorithms = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  function sma(values, length, index) {
    if (index < length - 1) return null;
    let sum = 0;
    for (let i = index - length + 1; i <= index; i += 1) {
      if (values[i] == null || !Number.isFinite(values[i])) return null;
      sum += values[i];
    }
    return sum / length;
  }

  function stdev(values, length, index) {
    const mean = sma(values, length, index);
    if (mean == null) return null;
    let sumSq = 0;
    for (let i = index - length + 1; i <= index; i += 1) {
      if (values[i] == null || !Number.isFinite(values[i])) return null;
      const delta = values[i] - mean;
      sumSq += delta * delta;
    }
    return Math.sqrt(sumSq / length);
  }

  function zscore(values, length, index) {
    const mean = sma(values, length, index);
    const sd = stdev(values, length, index);
    if (mean == null || sd == null || sd === 0) return 0;
    return (values[index] - mean) / sd;
  }

  function wma(values, length, index) {
    if (index < length - 1) return null;
    let weighted = 0;
    let weightSum = 0;
    for (let offset = 0; offset < length; offset += 1) {
      const value = values[index - offset];
      if (value == null || !Number.isFinite(value)) return null;
      const weight = length - offset;
      weighted += value * weight;
      weightSum += weight;
    }
    return weighted / weightSum;
  }

  function histogramColor(score, previous) {
    if (score == null) return "#7c8491";
    if (score >= 0) {
      return previous != null && score > previous
        ? "rgba(0, 176, 80, 0.82)"
        : "rgba(0, 176, 80, 0.42)";
    }
    return previous != null && score < previous
      ? "rgba(255, 59, 48, 0.82)"
      : "rgba(255, 59, 48, 0.42)";
  }

  function calculateJTRegimeOscillator(candles, settings) {
    const closes = candles.map((candle) => candle.close);
    const ret1 = new Array(candles.length).fill(null);
    const retMom = new Array(candles.length).fill(null);
    const rawScore = new Array(candles.length).fill(null);
    const output = [];

    for (let i = 0; i < candles.length; i += 1) {
      if (i >= settings.zLength + settings.momEnd) {
        ret1[i] = (closes[i] - closes[i - 1]) / closes[i - 1];
        retMom[i] = (
          closes[i - (settings.momStart - 1)] - closes[i - settings.momEnd]
        ) / closes[i - settings.momEnd];
      }

      const z1 = zscore(ret1, settings.zLength, i);
      const zMom = zscore(retMom, settings.zLength, i);
      const isExtremeUp = z1 > settings.extThresh;
      const isExtremeDown = z1 < -settings.extThresh;
      const shortTermFactor = isExtremeUp || isExtremeDown ? -z1 : z1;
      rawScore[i] = zMom + shortTermFactor;
      const score = wma(rawScore, settings.smoothLen, i);
      if (score == null) continue;
      const previous = output.length > 0 ? output[output.length - 1].value : null;
      output.push({
        index: i,
        time: candles[i].time,
        value: score,
        color: histogramColor(score, previous),
        close: candles[i].close,
        high: candles[i].high,
        low: candles[i].low,
        z1,
        zMom,
        isExtremeUp,
        isExtremeDown,
      });
    }
    return output;
  }

  function calculateBearMarketOverlay(candles, length) {
    const closes = candles.map((candle) => candle.close);
    const output = [];
    for (let index = length - 1; index < candles.length; index += 1) {
      const average = wma(closes, length, index);
      if (average == null) continue;
      output.push({
        time: candles[index].time,
        close: candles[index].close,
        wma: average,
        isBear: candles[index].close < average,
      });
    }
    return output;
  }

  function buildBottomMarkers(oscillator, candles) {
    const markers = [];
    const recentExtremeLookback = 12;
    const breakoutLookback = 5;
    const markerCooldown = 8;
    let lastMarkerIndex = -Infinity;

    for (let i = 2; i < oscillator.length; i += 1) {
      const current = oscillator[i];
      const previous = oscillator[i - 1];
      const previous2 = oscillator[i - 2];
      const recentExtremeDown = oscillator
        .slice(Math.max(0, i - recentExtremeLookback), i + 1)
        .some((item) => item.isExtremeDown);
      if (!recentExtremeDown) continue;

      const priorHigh = Math.max(
        ...candles
          .slice(Math.max(0, current.index - breakoutLookback), current.index)
          .map((candle) => candle.high),
      );
      const confirmed = previous.value <= 0
        && current.value > 0
        && Number.isFinite(priorHigh)
        && current.close > priorHigh;
      if (confirmed) {
        markers.push({
          time: current.time,
          position: "belowBar",
          color: "#4e8cff",
          shape: "arrowUp",
          text: "确认抄底",
          score: current.value,
          close: current.close,
        });
        lastMarkerIndex = i;
        continue;
      }

      const tentative = current.value < 0
        && previous.value <= previous2.value
        && current.value > previous.value
        && i - lastMarkerIndex >= markerCooldown;
      if (tentative) {
        markers.push({
          time: current.time,
          position: "belowBar",
          color: "#ffcc00",
          shape: "arrowUp",
          text: "试探抄底",
          score: current.value,
          close: current.close,
        });
        lastMarkerIndex = i;
      }
    }
    return markers;
  }

  return {
    buildBottomMarkers,
    calculateBearMarketOverlay,
    calculateJTRegimeOscillator,
    histogramColor,
    wma,
  };
});
