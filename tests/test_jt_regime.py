import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from jt_shared import (
    atomic_write_json,
    build_bottom_markers,
    calculate_jt_regime_oscillator,
    closed_candles,
    read_json_file,
    signal_key,
)


def fixture_candles():
    price = 100.0
    candles = []
    for index in range(80):
        if index < 30:
            price += 0.1
        elif index < 40:
            price -= 0.1
        elif index == 40:
            price -= 10
        elif index < 43:
            price -= 2
        elif index < 48:
            price += 1
        elif index < 54:
            price += 4
        else:
            price += 0.1
        close = round(price, 4)
        candles.append({
            "time": 1700000000 + index * 3600,
            "open": close,
            "high": close + 0.3,
            "low": close - 0.3,
            "close": close,
        })
    return candles


class JTRegimeTests(unittest.TestCase):
    def test_formula_matches_page_semantics_for_scores_and_markers(self):
        settings = {
            "marketType": "spot",
            "interval": "1h",
            "limit": 200,
            "momStart": 2,
            "momEnd": 10,
            "zLength": 10,
            "extThresh": 1.5,
            "smoothLen": 4,
        }
        oscillator = calculate_jt_regime_oscillator(fixture_candles(), settings)
        markers = build_bottom_markers(oscillator, fixture_candles())

        self.assertEqual(oscillator[0]["index"], 3)
        self.assertEqual(oscillator[0]["value"], 0)

        indexed = {item["index"]: item for item in oscillator}
        self.assertAlmostEqual(indexed[39]["value"], -1.265519, places=6)
        self.assertAlmostEqual(indexed[40]["z1"], -3.0, places=6)
        self.assertTrue(indexed[40]["isExtremeDown"])
        self.assertAlmostEqual(indexed[47]["value"], 0.061238, places=6)

        marker_summary = [
            (marker["text"], marker["time"], round(marker["score"], 6), marker["close"])
            for marker in markers[:2]
        ]
        self.assertEqual(marker_summary, [
            ("试探抄底", 1700129600, -2.790339, 102.3),
            ("确认抄底", 1700169200, 0.061238, 93.0),
        ])

    def test_python_formula_matches_javascript_page_semantics(self):
        if shutil.which("node") is None:
            self.skipTest("node is not installed")

        settings = {
            "marketType": "spot",
            "interval": "1h",
            "limit": 200,
            "momStart": 2,
            "momEnd": 10,
            "zLength": 10,
            "extThresh": 1.5,
            "smoothLen": 4,
        }
        candles = fixture_candles()
        python_oscillator = calculate_jt_regime_oscillator(candles, settings)
        python_markers = build_bottom_markers(python_oscillator, candles)
        script = r"""
const input = JSON.parse(process.argv[1]);
const candles = input.candles;
const settings = input.settings;
function sma(values, length, index) {
  if (index < length - 1) return null;
  let sum = 0;
  for (let i = index - length + 1; i <= index; i++) {
    if (values[i] == null || !Number.isFinite(values[i])) return null;
    sum += values[i];
  }
  return sum / length;
}
function stdev(values, length, index) {
  const mean = sma(values, length, index);
  if (mean == null) return null;
  let sumSq = 0;
  for (let i = index - length + 1; i <= index; i++) {
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
  for (let offset = 0; offset < length; offset++) {
    const value = values[index - offset];
    if (value == null || !Number.isFinite(value)) return null;
    const weight = length - offset;
    weighted += value * weight;
    weightSum += weight;
  }
  return weighted / weightSum;
}
function calculateJTRegimeOscillator(candles, settings) {
  const closes = candles.map((candle) => candle.close);
  const ret1 = new Array(candles.length).fill(null);
  const retMom = new Array(candles.length).fill(null);
  const rawScore = new Array(candles.length).fill(null);
  const output = [];
  for (let i = 0; i < candles.length; i++) {
    if (i >= settings.zLength + settings.momEnd) {
      ret1[i] = (closes[i] - closes[i - 1]) / closes[i - 1];
      retMom[i] = (closes[i - (settings.momStart - 1)] - closes[i - settings.momEnd]) / closes[i - settings.momEnd];
    }
    const z1 = zscore(ret1, settings.zLength, i);
    const zMom = zscore(retMom, settings.zLength, i);
    const isExtremeUp = z1 > settings.extThresh;
    const isExtremeDown = z1 < -settings.extThresh;
    const shortTermFactor = isExtremeUp || isExtremeDown ? -z1 : z1;
    rawScore[i] = zMom + shortTermFactor;
    const score = wma(rawScore, settings.smoothLen, i);
    if (score != null) {
      output.push({ index: i, time: candles[i].time, value: score, close: candles[i].close, high: candles[i].high, low: candles[i].low, z1, zMom, isExtremeUp, isExtremeDown });
    }
  }
  return output;
}
function buildBottomMarkers(oscillator, candles) {
  const markers = [];
  const recentExtremeLookback = 12;
  const breakoutLookback = 5;
  const markerCooldown = 8;
  let lastMarkerIndex = -Infinity;
  for (let i = 2; i < oscillator.length; i++) {
    const current = oscillator[i];
    const prev = oscillator[i - 1];
    const prev2 = oscillator[i - 2];
    const recentExtremeDown = oscillator.slice(Math.max(0, i - recentExtremeLookback), i + 1).some((item) => item.isExtremeDown);
    if (!recentExtremeDown) continue;
    const stillBelowZero = current.value < 0;
    const crossedAboveZero = prev.value <= 0 && current.value > 0;
    const firstTurnUp = prev.value <= prev2.value && current.value > prev.value;
    const candleIndex = current.index;
    const from = Math.max(0, candleIndex - breakoutLookback);
    const priorHigh = Math.max(...candles.slice(from, candleIndex).map((candle) => candle.high));
    const reclaimedShortStructure = Number.isFinite(priorHigh) && current.close > priorHigh;
    if (crossedAboveZero && reclaimedShortStructure) {
      markers.push({ time: current.time, position: "belowBar", color: "#4e8cff", shape: "arrowUp", text: "确认抄底", score: current.value, close: current.close });
      lastMarkerIndex = i;
      continue;
    }
    if (stillBelowZero && firstTurnUp && i - lastMarkerIndex >= markerCooldown) {
      markers.push({ time: current.time, position: "belowBar", color: "#ffcc00", shape: "arrowUp", text: "试探抄底", score: current.value, close: current.close });
      lastMarkerIndex = i;
    }
  }
  return markers;
}
const oscillator = calculateJTRegimeOscillator(candles, settings);
const markers = buildBottomMarkers(oscillator, candles);
console.log(JSON.stringify({ oscillator, markers }));
"""
        result = subprocess.run(
            ["node", "-e", script, json.dumps({"candles": candles, "settings": settings})],
            check=True,
            capture_output=True,
            text=True,
        )
        js = json.loads(result.stdout)

        self.assertEqual(len(js["oscillator"]), len(python_oscillator))
        for index in [3, 39, 40, 47]:
            py_item = next(item for item in python_oscillator if item["index"] == index)
            js_item = next(item for item in js["oscillator"] if item["index"] == index)
            self.assertAlmostEqual(py_item["value"], js_item["value"], places=10)
            self.assertAlmostEqual(py_item["z1"], js_item["z1"], places=10)
            self.assertAlmostEqual(py_item["zMom"], js_item["zMom"], places=10)
            self.assertEqual(py_item["isExtremeDown"], js_item["isExtremeDown"])

        self.assertEqual(
            [(item["text"], item["time"], round(item["score"], 10)) for item in python_markers],
            [(item["text"], item["time"], round(item["score"], 10)) for item in js["markers"]],
        )

    def test_closed_candles_excludes_candles_not_closed_for_10_seconds(self):
        candles = [
            {"time": 1000, "open": 1, "high": 1, "low": 1, "close": 1},
            {"time": 1060, "open": 2, "high": 2, "low": 2, "close": 2},
            {"time": 1120, "open": 3, "high": 3, "low": 3, "close": 3},
        ]

        self.assertEqual(
            [candle["time"] for candle in closed_candles(candles, "1m", now_seconds=1129)],
            [1000],
        )
        self.assertEqual(
            [candle["time"] for candle in closed_candles(candles, "1m", now_seconds=1130)],
            [1000, 1060],
        )

    def test_signal_key_includes_signal_type_for_deduplication(self):
        settings = {"marketType": "spot", "interval": "1h"}
        trial = {"time": 1700000000, "text": "试探抄底"}
        confirmed = {"time": 1700000000, "text": "确认抄底"}

        self.assertEqual(signal_key(trial, settings, "BTCUSDT"), "spot:BTCUSDT:1h:1700000000:试探抄底")
        self.assertNotEqual(
            signal_key(trial, settings, "BTCUSDT"),
            signal_key(confirmed, settings, "BTCUSDT"),
        )

    def test_atomic_json_write_round_trips_valid_json(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            path = Path(tmpdir) / "monitor.json"
            atomic_write_json(path, {"sent_signal_keys": ["a"], "nested": {"ok": True}})

            self.assertEqual(
                read_json_file(path, {}),
                {"sent_signal_keys": ["a"], "nested": {"ok": True}},
            )
            self.assertEqual(json.loads(path.read_text(encoding="utf-8"))["sent_signal_keys"], ["a"])


if __name__ == "__main__":
    unittest.main()
